// GeoJSON stratum importer — 1:1 port of iOS Geo/GeoJSONImporter.swift
// (spec §8, REQ-PRJ-002): import stratum boundaries from a GeoJSON
// FeatureCollection. Accepts Polygon and MultiPolygon geometries in WGS84
// decimal degrees.
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

    /// Canonical Polygon GeoJSON string for persistence on `Stratum.polygonGeoJSON`.
    /// Compact, sorted keys ("coordinates" < "type"), matching iOS
    /// JSONSerialization with `.sortedKeys`.
    fun serialise(rings: List<List<CoordinateConversions.LatLon>>): String {
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
        sb.append("],\"type\":\"Polygon\"}")
        return sb.toString()
    }

    /// NSNumber-style JSON number: integral doubles without a decimal point,
    /// otherwise shortest round-trip `Double.toString()` form.
    private fun jsonNumber(v: Double): String {
        if (v == v.toLong().toDouble() && abs(v) < 1e15) return v.toLong().toString()
        return v.toString()
    }
}
