// Port of iOS Export/ShapefileExporter.swift (spec §8 Export — Shapefile,
// Phase 6). Pure-Kotlin ESRI Shapefile + dBase III + "stored" ZIP writer —
// no external dependencies, matching the iOS in-house implementation.
//
// ## References
//   * ESRI Shapefile Technical Description (ESRI White Paper, 1998).
//   * dBase III PLUS File Structure (xBase docs).
//   * PKWARE APPNOTE.TXT v6.3.10 (ZIP, stored = method 0).
//
// ## Supported geometry
//   * Shape type 1 — Point (plot centres, planned plots).
//   * Shape type 5 — Polygon (stratum boundaries).
//
// ## Byte order
// Shapefile mixes big-endian (record headers, file-code, file-length) with
// little-endian (shape type, coordinates, dBase). The writer keeps those
// straight via the BinaryData helpers.
//
// ## Character encoding
// The .dbf string fields are UTF-8; a sibling `.cpg` file declares this
// explicitly so standard GIS readers pick it up.

package com.hcjeong.forestix.export

import com.hcjeong.forestix.data.cruise.PlannedPlot
import com.hcjeong.forestix.data.cruise.Plot
import com.hcjeong.forestix.data.cruise.Stratum
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max
import kotlin.math.min

sealed class ShapefileExporterError(message: String) : Exception(message) {
    class InvalidGeometry(g: String) : ShapefileExporterError("Invalid geometry: $g")
    class SerializationFailed(s: String) : ShapefileExporterError("Shapefile serialization failed: $s")
    class EmptyLayer : ShapefileExporterError("Cannot write shapefile with zero features")
}

// MARK: - Public API

object ShapefileExporter {

    /// Build a zipped shapefile bundle for the measured plot centres of a
    /// cruise. Returns the raw bytes of a `.zip` suitable for writing
    /// directly to disk — four constituent files (`<name>.shp`, `.shx`,
    /// `.dbf`, `.prj`) plus a `.cpg` encoding sidecar.
    fun plotCentersZip(
        plots: List<Plot>,
        layerName: String = "plots",
    ): ByteArray {
        val rows: List<DBFRow> = plots.map { p ->
            listOf(
                "plot_num" to DBFValue.IntValue(p.plotNumber),
                "tier" to DBFValue.StringValue(p.positionTier.raw, width = 1),
                "pos_src" to DBFValue.StringValue(p.positionSource.raw, width = 16),
                "area_ac" to DBFValue.DoubleValue(p.plotAreaAcres.toDouble(), width = 12, decimals = 4),
                "closed" to DBFValue.StringValue(if (p.closedAt == null) "no" else "yes", width = 3),
                "plot_id" to DBFValue.StringValue(p.id.uuidString, width = 36),
            )
        }
        val shapes: List<ShapeGeometry> = plots.map {
            ShapeGeometry.Point(x = it.centerLon, y = it.centerLat)
        }
        if (shapes.isEmpty()) throw ShapefileExporterError.EmptyLayer()
        return zipped(layerName = layerName,
            shapeType = ShapeType.POINT,
            geometries = shapes,
            attributes = rows)
    }

    /// Build a zipped shapefile bundle for planned plot points, carrying
    /// the `visited` flag so the shapefile can be styled planned-vs-visited
    /// in a GIS.
    fun plannedPlotsZip(
        plannedPlots: List<PlannedPlot>,
        layerName: String = "planned_plots",
    ): ByteArray {
        val sorted = plannedPlots.sortedBy { it.plotNumber }
        val rows: List<DBFRow> = sorted.map { p ->
            listOf(
                "plot_num" to DBFValue.IntValue(p.plotNumber),
                "stratum" to DBFValue.StringValue(p.stratumId?.uuidString ?: "", width = 36),
                "visited" to DBFValue.StringValue(if (p.visited) "yes" else "no", width = 3),
                "planned_id" to DBFValue.StringValue(p.id.uuidString, width = 36),
            )
        }
        val shapes: List<ShapeGeometry> = sorted.map {
            ShapeGeometry.Point(x = it.plannedLon, y = it.plannedLat)
        }
        if (shapes.isEmpty()) throw ShapefileExporterError.EmptyLayer()
        return zipped(layerName = layerName,
            shapeType = ShapeType.POINT,
            geometries = shapes,
            attributes = rows)
    }

    /// Build a zipped shapefile bundle for stratum polygons. Each
    /// `Stratum.polygonGeoJSON` is parsed; polygons/multi-polygons are
    /// flattened into a single polygon feature with one outer ring plus
    /// any inner holes, matching ESRI's Polygon shape type.
    fun strataZip(
        strata: List<Stratum>,
        layerName: String = "strata",
    ): ByteArray {
        val geoms = mutableListOf<ShapeGeometry>()
        val rows = mutableListOf<DBFRow>()
        for (s in strata) {
            val poly = parseStratumPolygon(s.polygonGeoJSON) ?: continue
            geoms.add(poly)
            rows.add(listOf(
                "id" to DBFValue.StringValue(s.id.uuidString, width = 36),
                "name" to DBFValue.StringValue(s.name, width = 64),
                "area_ac" to DBFValue.DoubleValue(s.areaAcres.toDouble(), width = 12, decimals = 4),
            ))
        }
        if (geoms.isEmpty()) throw ShapefileExporterError.EmptyLayer()
        return zipped(layerName = layerName,
            shapeType = ShapeType.POLYGON,
            geometries = geoms,
            attributes = rows)
    }

    // MARK: - Orchestration

    private fun zipped(
        layerName: String,
        shapeType: ShapeType,
        geometries: List<ShapeGeometry>,
        attributes: List<DBFRow>,
    ): ByteArray {
        check(geometries.size == attributes.size) {
            "geometries and attributes must line up"
        }

        val (shp, shx) = writeShpShx(shapeType = shapeType, geometries = geometries)
        val dbf = writeDBF(rows = attributes)
        val prj = wgs84PRJ.toByteArray(Charsets.UTF_8)
        val cpg = "UTF-8\n".toByteArray(Charsets.UTF_8)

        val files = listOf(
            "$layerName.shp" to shp,
            "$layerName.shx" to shx,
            "$layerName.dbf" to dbf,
            "$layerName.prj" to prj,
            "$layerName.cpg" to cpg,
        )
        return ZipWriter.storedArchive(files)
    }

    // MARK: - GeoJSON polygon → ShapeGeometry.Polygon

    private fun parseStratumPolygon(json: String): ShapeGeometry? {
        val dict = try { JSONObject(json) } catch (_: Exception) { return null }
        val type = dict.optString("type", "")
        val coordsAny = dict.opt("coordinates") ?: return null

        // Collect rings across Polygon and MultiPolygon; ESRI Polygons hold
        // all rings in a single shape (outer ring clockwise, holes CCW).
        val parts = mutableListOf<Int>()
        val points = mutableListOf<Pair<Double, Double>>()

        fun appendRing(coords: JSONArray) {
            if (coords.length() == 0) return
            parts.add(points.size)
            for (i in 0 until coords.length()) {
                val pt = coords.optJSONArray(i) ?: continue
                if (pt.length() >= 2) {
                    points.add(pt.optDouble(0) to pt.optDouble(1))
                }
            }
        }

        when (type) {
            "Polygon" -> {
                val rings = coordsAny as? JSONArray ?: return null
                for (i in 0 until rings.length()) {
                    rings.optJSONArray(i)?.let { appendRing(it) }
                }
            }
            "MultiPolygon" -> {
                val polys = coordsAny as? JSONArray ?: return null
                for (i in 0 until polys.length()) {
                    val poly = polys.optJSONArray(i) ?: continue
                    for (j in 0 until poly.length()) {
                        poly.optJSONArray(j)?.let { appendRing(it) }
                    }
                }
            }
            else -> return null
        }
        if (points.isEmpty()) return null
        return ShapeGeometry.Polygon(parts = parts, points = points)
    }
}

// MARK: - Geometry types

internal enum class ShapeType(val raw: Int) {
    NULL(0),
    POINT(1),
    POLYGON(5),
}

/// Polygon rings are (closed) WGS84 lat/lon points in (x = lon, y = lat)
/// order — matching GeoJSON convention. All rings for one feature live
/// in `parts` / `points`; parts holds the index into `points` where each
/// ring starts.
internal sealed class ShapeGeometry {
    data class Point(val x: Double, val y: Double) : ShapeGeometry()
    data class Polygon(
        val parts: List<Int>,
        val points: List<Pair<Double, Double>>,
    ) : ShapeGeometry()
}

// MARK: - .shp / .shx writers

private fun writeShpShx(
    shapeType: ShapeType,
    geometries: List<ShapeGeometry>,
): Pair<ByteArray, ByteArray> {
    val shpRecords = BinaryData()
    val shxRecords = BinaryData()

    val bbox = MutableBBox()
    var fileOffsetWords = 50  // header is 100 bytes = 50 × 16-bit words

    for ((i, geom) in geometries.withIndex()) {
        val recordContent = encodeShape(geom, bbox)
        // Record header: 4B BE record number, 4B BE content length (in
        // 16-bit words, excluding the 8-byte header itself).
        val recordNumber = i + 1
        val contentLengthWords = recordContent.size / 2

        shpRecords.appendBEInt32(recordNumber)
        shpRecords.appendBEInt32(contentLengthWords)
        shpRecords.append(recordContent)

        // Index: 4B BE offset (words), 4B BE content length (words).
        shxRecords.appendBEInt32(fileOffsetWords)
        shxRecords.appendBEInt32(contentLengthWords)

        fileOffsetWords += 4 + recordContent.size / 2
    }

    val shpLengthWords = 50 + shpRecords.size / 2
    val shxLengthWords = 50 + shxRecords.size / 2

    val shpHeader = shapefileHeader(
        totalLengthWords = shpLengthWords,
        shapeType = shapeType,
        bbox = bbox)
    val shxHeader = shapefileHeader(
        totalLengthWords = shxLengthWords,
        shapeType = shapeType,
        bbox = bbox)
    return (shpHeader + shpRecords.toByteArray()) to (shxHeader + shxRecords.toByteArray())
}

private fun encodeShape(geom: ShapeGeometry, bbox: MutableBBox): ByteArray {
    val out = BinaryData()
    when (geom) {
        is ShapeGeometry.Point -> {
            bbox.insert(x = geom.x, y = geom.y)
            out.appendLEInt32(ShapeType.POINT.raw)
            out.appendLEDouble(geom.x)
            out.appendLEDouble(geom.y)
        }
        is ShapeGeometry.Polygon -> {
            val localBBox = MutableBBox()
            for ((x, y) in geom.points) localBBox.insert(x = x, y = y)
            bbox.insert(localBBox)
            out.appendLEInt32(ShapeType.POLYGON.raw)
            out.appendLEDouble(localBBox.xmin)
            out.appendLEDouble(localBBox.ymin)
            out.appendLEDouble(localBBox.xmax)
            out.appendLEDouble(localBBox.ymax)
            out.appendLEInt32(geom.parts.size)
            out.appendLEInt32(geom.points.size)
            for (p in geom.parts) out.appendLEInt32(p)
            for ((x, y) in geom.points) {
                out.appendLEDouble(x)
                out.appendLEDouble(y)
            }
        }
    }
    return out.toByteArray()
}

private fun shapefileHeader(
    totalLengthWords: Int,
    shapeType: ShapeType,
    bbox: MutableBBox,
): ByteArray {
    val h = BinaryData()
    h.appendBEInt32(9994)                // File code
    h.appendZeros(20)                    // 5 × int32 zeros
    h.appendBEInt32(totalLengthWords)    // File length (16-bit words)
    h.appendLEInt32(1000)                // Version
    h.appendLEInt32(shapeType.raw)
    // BBox: Xmin, Ymin, Xmax, Ymax, Zmin, Zmax, Mmin, Mmax
    h.appendLEDouble(bbox.xmin)
    h.appendLEDouble(bbox.ymin)
    h.appendLEDouble(bbox.xmax)
    h.appendLEDouble(bbox.ymax)
    h.appendLEDouble(0.0); h.appendLEDouble(0.0)
    h.appendLEDouble(0.0); h.appendLEDouble(0.0)
    return h.toByteArray()
}

// MARK: - BBox accumulator

internal class MutableBBox {
    var xmin = 0.0; var ymin = 0.0; var xmax = 0.0; var ymax = 0.0
    private var hasAny = false

    fun insert(x: Double, y: Double) {
        if (!hasAny) {
            xmin = x; xmax = x; ymin = y; ymax = y; hasAny = true
        } else {
            xmin = min(xmin, x); xmax = max(xmax, x)
            ymin = min(ymin, y); ymax = max(ymax, y)
        }
    }

    fun insert(other: MutableBBox) {
        if (other.hasAny) {
            if (!hasAny) {
                xmin = other.xmin; xmax = other.xmax
                ymin = other.ymin; ymax = other.ymax
                hasAny = true
            } else {
                xmin = min(xmin, other.xmin); xmax = max(xmax, other.xmax)
                ymin = min(ymin, other.ymin); ymax = max(ymax, other.ymax)
            }
        }
    }
}

// MARK: - dBase III writer

internal typealias DBFRow = List<Pair<String, DBFValue>>

internal sealed class DBFValue {
    data class IntValue(val n: Int) : DBFValue()
    data class DoubleValue(val x: Double, val width: Int, val decimals: Int) : DBFValue()
    data class StringValue(val s: String, val width: Int) : DBFValue()
}

internal class DBFField(
    val name: String,       // up to 10 ASCII chars, uppercased internally
    val type: Int,          // 'C', 'N'
    val length: Int,
    val decimals: Int,
)

internal fun writeDBF(rows: List<DBFRow>): ByteArray {
    // Infer one field schema from the first row.
    val first = rows.firstOrNull() ?: throw ShapefileExporterError.EmptyLayer()
    val fields: List<DBFField> = first.map { (name, value) ->
        val fname = name.take(10)
        when (value) {
            is DBFValue.IntValue ->
                DBFField(name = fname, type = 0x4E /* N */, length = 11, decimals = 0)
            is DBFValue.DoubleValue ->
                DBFField(name = fname, type = 0x4E, length = value.width, decimals = value.decimals)
            is DBFValue.StringValue ->
                DBFField(name = fname, type = 0x43 /* C */, length = max(1, value.width), decimals = 0)
        }
    }

    val headerLength = 32 + 32 * fields.size + 1
    val recordLength = 1 + fields.sumOf { it.length }
    val out = BinaryData()

    // Header (32 bytes)
    out.appendByte(0x03)                            // version: dBase III no memo
    val now = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    out.appendByte(now.get(Calendar.YEAR) - 1900)
    out.appendByte(now.get(Calendar.MONTH) + 1)
    out.appendByte(now.get(Calendar.DAY_OF_MONTH))
    out.appendLEInt32(rows.size)
    out.appendLEInt16(headerLength)
    out.appendLEInt16(recordLength)
    out.appendZeros(20)                             // reserved/flags

    // Field descriptors
    for (f in fields) {
        var nameBytes = f.name.uppercase(Locale.US).toByteArray(Charsets.UTF_8)
        if (nameBytes.size > 10) {
            nameBytes = nameBytes.copyOfRange(0, 10)
        }
        out.append(nameBytes)
        out.appendZeros(11 - nameBytes.size)        // pad to 11
        out.appendByte(f.type)
        out.appendZeros(4)                          // field data address
        out.appendByte(f.length)
        out.appendByte(f.decimals)
        out.appendZeros(14)                         // reserved + flags
    }
    out.appendByte(0x0D)                            // header terminator

    // Records
    for (row in rows) {
        out.appendByte(0x20)                        // not-deleted flag
        check(row.size == fields.size) {
            "dBase row / field count mismatch"
        }
        for ((idx, cell) in row.withIndex()) {
            val f = fields[idx]
            out.append(encodeDBFCell(cell.second, width = f.length))
        }
    }
    out.appendByte(0x1A)                            // EOF marker
    return out.toByteArray()
}

private fun encodeDBFCell(value: DBFValue, width: Int): ByteArray = when (value) {
    is DBFValue.IntValue ->
        rightAlignedAscii(value.n.toString(), width = width)
    is DBFValue.DoubleValue ->
        // printf-semantics (half-even) via the shared helper so DBF numeric
        // cells match the iOS `String(format:)` bytes exactly.
        rightAlignedAscii(
            CSVExporter.printfF(value.x, value.decimals), width = width)
    is DBFValue.StringValue -> {
        // dBase III character fields are left-aligned, space-padded,
        // fixed-width. Truncate long strings so we never exceed width.
        val utf8 = value.s.toByteArray(Charsets.UTF_8)
        if (utf8.size >= width) {
            utf8.copyOfRange(0, width)
        } else {
            utf8 + ByteArray(width - utf8.size) { 0x20 }
        }
    }
}

private fun rightAlignedAscii(s: String, width: Int): ByteArray {
    val utf8 = s.toByteArray(Charsets.UTF_8)
    if (utf8.size >= width) {
        return utf8.copyOfRange(0, width)
    }
    return ByteArray(width - utf8.size) { 0x20 } + utf8
}

// MARK: - PRJ content

/// Standard WKT for WGS 84, compatible with standard GIS readers.
internal val wgs84PRJ: String =
    """GEOGCS["GCS_WGS_1984",DATUM["D_WGS_1984",SPHEROID["WGS_1984",6378137.0,298.257223563]],PRIMEM["Greenwich",0.0],UNIT["Degree",0.0174532925199433]]"""
