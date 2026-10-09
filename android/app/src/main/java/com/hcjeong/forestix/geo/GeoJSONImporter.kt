// GeoJSON stratum importer — 1:1 port of iOS Geo/GeoJSONImporter.swift
// (spec §8, REQ-PRJ-002): import stratum boundaries from a GeoJSON
// FeatureCollection. Accepts Polygon and MultiPolygon geometries in WGS84
// decimal degrees.
//
// "WGS84 decimal degrees" is CHECKED, not assumed: a legacy `crs` member
// naming anything else is refused (see `GeoJSONCRS`) wherever it sits —
// root, Feature, geometry or GeometryCollection member, since GeoJSON 2008
// permitted it on any object — and every position is range-checked.
// Without the first of those a Korea 2000 stratum — degrees, in range —
// sizes and places every plot in it a few hundred metres off, and the
// acreage it reports looks entirely reasonable.
//
// If a feature's properties supply `name` or `areaAcres`, those values are
// used; otherwise the importer falls back to a sensible name and computes
// area via the spherical-excess formula (§8 "spherical excess area" note in
// the spec). The resulting `ImportedPolygon` carries both the original
// geometry (re-serialised to a `Polygon` GeoJSON string for persistence on
// the Stratum record) and the computed area in acres.
//
// Platform note: iOS uses JSONSerialization; here we walk a
// kotlinx-serialization JsonElement tree. Serialisation output matches the
// iOS `.sortedKeys` compact form ("coordinates" before "type", no spaces),
// with integral doubles written without a decimal point like NSNumber.

package com.hcjeong.forestix.geo

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/// Import failure reasons; `description` strings match the iOS
/// `GeoJSONImportError` cases byte-for-byte.
sealed class GeoJSONImportError(val description: String) : Exception(description) {
    class MalformedJSON(reason: String) : GeoJSONImportError("Malformed GeoJSON: $reason")
    class UnsupportedGeometry(reason: String) : GeoJSONImportError("Unsupported geometry: $reason")
    class EmptyFeatureCollection : GeoJSONImportError("GeoJSON has no features")
    class InvalidCoordinate(reason: String) : GeoJSONImportError("Invalid coordinate: $reason")

    /// The legacy `crs` member named a CRS that is not WGS84. Word for
    /// word the sentence the boundary importer uses — a stratum drawn in
    /// the wrong CRS is wrong for exactly the same reason a boundary is.
    class NotWGS84(found: String) : GeoJSONImportError(BoundaryCRS.notWgs84(found))
}

/// A polygon resolved from one GeoJSON feature. `rings` stores the outer ring
/// first, followed by any inner (hole) rings; each ring is a closed list of
/// WGS84 points in lon/lat order.
data class ImportedPolygon(
    val name: String,
    val areaAcres: Double,
    /// ring 0 = outer; rest = holes
    val rings: List<List<CoordinateConversions.LatLon>>,
    /// canonical serialisation
    val geoJSONString: String,
)

object GeoJSONImporter {

    // MARK: - Public API

    /// Parse a GeoJSON document (FeatureCollection / Feature / bare Polygon /
    /// MultiPolygon) into a list of `ImportedPolygon`s.
    fun importStrata(data: ByteArray): List<ImportedPolygon> =
        importStrata(String(data, Charsets.UTF_8))

    fun importStrata(text: String): List<ImportedPolygon> {
        val any = try {
            Json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            throw GeoJSONImportError.MalformedJSON(e.message ?: "parse failed")
        }
        val obj = any as? JsonObject
            ?: throw GeoJSONImportError.MalformedJSON("Top-level JSON is not an object")
        // The legacy `crs` member, read on this path for the same reason
        // the boundary importer reads it: a Korea 2000 stratum is degrees
        // and in range, so the per-position range check below cannot tell
        // it apart from a WGS84 one. Read at EVERY level — root, Feature,
        // geometry, GeometryCollection member — because GeoJSON 2008
        // permitted the member on any object and exporters used that.
        GeoJSONCRS.rejectionAnywhere(obj)?.let { throw GeoJSONImportError.NotWGS84(it) }

        val result = collect(obj, inheritedProperties = JsonObject(emptyMap()))
        if (result.isEmpty()) throw GeoJSONImportError.EmptyFeatureCollection()
        return result
    }

    // MARK: - Recursive collect

    private fun collect(
        obj: JsonObject,
        inheritedProperties: JsonObject,
    ): List<ImportedPolygon> {
        val type = (obj["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""
        return when (type) {
            "FeatureCollection" -> {
                val features = (obj["features"] as? JsonArray)
                    ?.map { it as? JsonObject }
                    ?.takeIf { list -> list.all { it != null } }
                    ?.filterNotNull()
                    ?: throw GeoJSONImportError.MalformedJSON("FeatureCollection missing features array")
                val out = mutableListOf<ImportedPolygon>()
                for (feature in features) {
                    out += collect(feature, inheritedProperties = JsonObject(emptyMap()))
                }
                out
            }

            "Feature" -> {
                val props = (obj["properties"] as? JsonObject) ?: JsonObject(emptyMap())
                val geometry = obj["geometry"] as? JsonObject
                    ?: throw GeoJSONImportError.MalformedJSON("Feature missing geometry")
                collect(geometry, inheritedProperties = props)
            }

            "Polygon" -> {
                val polygon = parsePolygon(obj)
                listOf(buildImported(rings = polygon, properties = inheritedProperties))
            }

            "MultiPolygon" -> {
                val polygons = parseMultiPolygon(obj)
                polygons.mapIndexed { idx, rings ->
                    // Literal port of the iOS branch (which, like here, only
                    // fires when a base name exists but props["name"] is nil —
                    // i.e. never, since props starts as a copy).
                    val props = inheritedProperties.toMutableMap()
                    if (props["name"] == null) {
                        val base = (inheritedProperties["name"] as? JsonPrimitive)
                            ?.takeIf { it.isString }?.content
                        if (base != null) props["name"] = JsonPrimitive("$base #${idx + 1}")
                    }
                    buildImported(rings = rings, properties = JsonObject(props))
                }
            }

            else -> throw GeoJSONImportError.UnsupportedGeometry(
                "type=${if (type.isEmpty()) "<missing>" else type}"
            )
        }
    }

    // MARK: - Geometry parsing

    private fun parsePolygon(obj: JsonObject): List<List<CoordinateConversions.LatLon>> {
        val coords = numberRings(obj["coordinates"], "Polygon coordinates malformed")
        return coords.map(::parseRing)
    }

    private fun parseMultiPolygon(obj: JsonObject): List<List<List<CoordinateConversions.LatLon>>> {
        val outer = obj["coordinates"] as? JsonArray
            ?: throw GeoJSONImportError.MalformedJSON("MultiPolygon coordinates malformed")
        val coords = outer.map { polygon ->
            numberRings(polygon, "MultiPolygon coordinates malformed")
        }
        return coords.map { polygon -> polygon.map(::parseRing) }
    }

    /// Decode a `[[[Double]]]` nesting (rings → positions → values), throwing
    /// `MalformedJSON` on any structural mismatch — mirroring the iOS
    /// `as? [[[Double]]]` cast, which rejects the whole geometry if any value
    /// is not a number.
    private fun numberRings(element: Any?, errorMessage: String): List<List<List<Double>>> {
        val rings = element as? JsonArray ?: throw GeoJSONImportError.MalformedJSON(errorMessage)
        return rings.map { ring ->
            val positions = ring as? JsonArray
                ?: throw GeoJSONImportError.MalformedJSON(errorMessage)
            positions.map { position ->
                val values = position as? JsonArray
                    ?: throw GeoJSONImportError.MalformedJSON(errorMessage)
                values.map { value ->
                    val prim = value as? JsonPrimitive
                        ?: throw GeoJSONImportError.MalformedJSON(errorMessage)
                    if (prim.isString) throw GeoJSONImportError.MalformedJSON(errorMessage)
                    prim.doubleOrNull ?: throw GeoJSONImportError.MalformedJSON(errorMessage)
                }
            }
        }
    }

    private fun parseRing(ring: List<List<Double>>): List<CoordinateConversions.LatLon> {
        if (ring.size < 4) {
            throw GeoJSONImportError.InvalidCoordinate("Ring has < 4 positions (must close)")
        }
        return ring.map { pair ->
            if (pair.size < 2) {
                throw GeoJSONImportError.InvalidCoordinate("Position has < 2 values")
            }
            val lon = pair[0]
            val lat = pair[1]
            if (lon !in -180.0..180.0 || lat !in -90.0..90.0) {
                throw GeoJSONImportError.InvalidCoordinate("lat/lon out of range ($lat, $lon)")
            }
            CoordinateConversions.LatLon(latitude = lat, longitude = lon)
        }
    }

    // MARK: - Assembly

    private fun buildImported(
        rings: List<List<CoordinateConversions.LatLon>>,
        properties: JsonObject,
    ): ImportedPolygon {
        val name = (properties["name"] as? JsonPrimitive)
            ?.takeIf { it.isString }?.content?.trim() ?: "Stratum"

        val suppliedAcres: Double? = when (val v = properties["areaAcres"]) {
            is JsonPrimitive ->
                if (v.isString) v.content.toDoubleOrNull() else v.doubleOrNull
            else -> null
        }

        val computedAreaM2 = signedPolygonAreaMetersSquared(rings)
        val areaAcres = suppliedAcres ?: metersSquaredToAcres(abs(computedAreaM2))
        val geoJSONString = serialise(rings)

        return ImportedPolygon(
            name = if (name.isEmpty()) "Stratum" else name,
            areaAcres = areaAcres,
            rings = rings,
            geoJSONString = geoJSONString,
        )
    }

    // MARK: - Area (spherical excess)

    /// Polygon area in m² using the spherical-excess formula. Outer ring is
    /// positive; subsequent rings (holes) are subtracted. Sign of each ring
    /// is determined by its winding orientation; absolute value is taken by
    /// the caller when total area is reported.
    fun signedPolygonAreaMetersSquared(
        rings: List<List<CoordinateConversions.LatLon>>,
    ): Double {
        val outer = rings.firstOrNull() ?: return 0.0
        var area = sphericalRingArea(outer)
        for (hole in rings.drop(1)) {
            area -= abs(sphericalRingArea(hole))
        }
        return area
    }

    /// Signed area of a single ring in m² (spherical-excess / L'Huilier-style
    /// reduction). Positive for counter-clockwise rings when viewed from
    /// outside the sphere.
    fun sphericalRingArea(ring: List<CoordinateConversions.LatLon>): Double {
        if (ring.size < 4) return 0.0
        var total = 0.0
        val r = CoordinateConversions.earthRadiusMeters
        for (i in 0 until ring.size - 1) {
            val p1 = ring[i]
            val p2 = ring[i + 1]
            val lambda1 = p1.longitude * PI / 180
            val lambda2 = p2.longitude * PI / 180
            val phi1 = p1.latitude * PI / 180
            val phi2 = p2.latitude * PI / 180
            total += (lambda2 - lambda1) * (sin(phi1) + sin(phi2))
        }
        return total * r * r / 2
    }

    const val metersSquaredPerAcre: Double = 4_046.8564224

    fun metersSquaredToAcres(m2: Double): Double = m2 / metersSquaredPerAcre

    // MARK: - Serialisation

    /// The member a circular stratum carries so it can be re-OPENED as a
    /// circle instead of as the 128-corner polygon it is stored as.
    ///
    /// A foreign member inside a geometry object, which RFC 7946 §6.1
    /// permits and every reader ignores — the same trick and the same
    /// justification `forestix:source` already uses on a drawn boundary.
    /// The namespace prefix is what keeps it from ever colliding with a
    /// real GeoJSON key, and the name sorts between "coordinates" and
    /// "type" so the builder below emits it exactly where iOS's
    /// `.sortedKeys` puts it.
    const val CIRCLE_MEMBER_KEY: String = "forestix:circle"

    /// Canonical Polygon GeoJSON string for persistence on `Stratum.polygonGeoJSON`.
    /// Compact, sorted keys ("coordinates" < "forestix:circle" < "type"),
    /// matching iOS JSONSerialization with `.sortedKeys`.
    ///
    /// `circle` is provenance only: the ring passed in is already the
    /// densified circle, and everything that reads this string back —
    /// `parseRings`, plot layout, both exporters — sees nothing but a
    /// Polygon.
    fun serialise(
        rings: List<List<CoordinateConversions.LatLon>>,
        circle: BoundaryDraft.Circle? = null,
    ): String {
        val sb = StringBuilder()
        sb.append("{\"coordinates\":[")
        rings.forEachIndexed { ri, ring ->
            if (ri > 0) sb.append(',')
            sb.append('[')
            ring.forEachIndexed { pi, p ->
                if (pi > 0) sb.append(',')
                sb.append('[')
                    .append(jsonNumber(p.longitude))
                    .append(',')
                    .append(jsonNumber(p.latitude))
                    .append(']')
            }
            sb.append(']')
        }
        sb.append(']')
        if (circle != null) {
            // Through `jsonNumber` like every other number in here — iOS
            // writes these with NSNumber's integral-without-a-point rule,
            // and a bare toString() would diverge on a round radius.
            sb.append(",\"").append(CIRCLE_MEMBER_KEY).append("\":{\"lat\":")
                .append(jsonNumber(circle.centre.latitude))
                .append(",\"lon\":")
                .append(jsonNumber(circle.centre.longitude))
                .append(",\"radiusM\":")
                .append(jsonNumber(circle.radiusMeters))
                .append('}')
        }
        sb.append(",\"type\":\"Polygon\"}")
        return sb.toString()
    }

    /// The circle a stored stratum was drawn as, or null for every polygon
    /// ever saved before circles existed.
    ///
    /// NEVER THROWS. A note that cannot be read must degrade to "this is a
    /// polygon" — which is the behaviour of every build before this one —
    /// and never to a refused edit: the ring is intact either way, and
    /// losing the ability to reshape a stand because a provenance hint went
    /// bad would be the app punishing the cruiser for its own bookkeeping.
    fun parseCircle(geojson: String): BoundaryDraft.Circle? {
        val note = try {
            (Json.parseToJsonElement(geojson) as? JsonObject)
                ?.get(CIRCLE_MEMBER_KEY) as? JsonObject
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } ?: return null
        val lat = (note["lat"] as? JsonPrimitive)?.doubleOrNull ?: return null
        val lon = (note["lon"] as? JsonPrimitive)?.doubleOrNull ?: return null
        val radius = (note["radiusM"] as? JsonPrimitive)?.doubleOrNull ?: return null
        if (!lat.isFinite() || !lon.isFinite() || !radius.isFinite() || radius <= 0) return null
        return BoundaryDraft.Circle(CoordinateConversions.LatLon(lat, lon), radius)
    }

    /// NSNumber-style JSON number: integral doubles without a decimal point,
    /// otherwise shortest round-trip `Double.toString()` form.
    ///
    /// THE INTEGRAL RULE MATCHES APPLE; THE FRACTIONAL ONE DOES NOT, and
    /// that is worth knowing before someone spends a day on it.
    /// `JSONSerialization` renders a fractional double at 17 significant
    /// digits with trailing zeros trimmed, so it writes 44.6 as
    /// `44.600000000000001` and 0.1 as `0.10000000000000001`, where this
    /// writes `44.6` and `0.1`. The two files therefore describe the same
    /// coordinates to the last representable bit but are not the same
    /// bytes. That has been true of every ring this function has ever
    /// written; what IS identical across the platforms, and what the
    /// readers actually depend on, is the key ORDER and the structure.
    private fun jsonNumber(v: Double): String {
        if (v == v.toLong().toDouble() && abs(v) < 1e15) return v.toLong().toString()
        return v.toString()
    }
}
