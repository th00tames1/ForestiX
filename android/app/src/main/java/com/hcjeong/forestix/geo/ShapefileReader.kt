// ESRI Shapefile READER — the inverse of Export/ShapefileExporter, which
// already writes .shp/.shx/.dbf/.prj byte-for-byte. Used by the survey
// boundary import (Map settings → Survey boundary).
//
// ## References
//   * ESRI Shapefile Technical Description (ESRI White Paper, 1998).
//
// ## Supported geometry
//   * 1 Point, 3 Polyline, 5 Polygon — plus their Z (11/13/15) and M
//     (21/23/25) variants: the leading layout is identical, so the extra
//     Z/M ordinates are simply ignored rather than failing the import.
//   * 0 Null records are skipped.
//
// ## Byte order
// Record headers and the file code/length are BIG-endian; shape type and
// every coordinate is LITTLE-endian. Two views over the same array keep
// that straight.
//
// Ring roles follow the spec: within one polygon record, CLOCKWISE parts
// are outer rings and COUNTER-CLOCKWISE parts are holes of the preceding
// outer ring. No coordinate validation happens here — the WGS84 gate in
// BoundaryImport is the single place that decides what may be drawn.

package com.hcjeong.forestix.geo

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object ShapefileReader {

    /// Parse a whole .shp into boundary geometries, in file order.
    fun read(shp: ByteArray): List<BoundaryGeometry> {
        if (shp.size < 100) {
            throw BoundaryImportError("The .shp file is truncated (${shp.size} bytes).")
        }
        val be = ByteBuffer.wrap(shp).order(ByteOrder.BIG_ENDIAN)
        val le = ByteBuffer.wrap(shp).order(ByteOrder.LITTLE_ENDIAN)
        if (be.getInt(0) != 9994) {
            throw BoundaryImportError("This is not an ESRI shapefile (bad .shp file code).")
        }
        // Header file length is in 16-bit words and includes the header.
        val declared = be.getInt(24).toLong() * 2L
        val end = if (declared in 100L..shp.size.toLong()) declared.toInt() else shp.size

        val out = ArrayList<BoundaryGeometry>()
        var offset = 100
        while (offset + 8 <= end) {
            val contentLength = be.getInt(offset + 4) * 2
            val start = offset + 8
            if (contentLength <= 0 || start + contentLength > end) break
            out += parseRecord(le, start, contentLength)
            offset = start + contentLength
        }
        if (out.isEmpty()) {
            throw BoundaryImportError(
                "The shapefile holds no point, line or polygon features."
            )
        }
        return out
    }

    // MARK: - One record

    private fun parseRecord(le: ByteBuffer, start: Int, length: Int): List<BoundaryGeometry> {
        if (length < 4) return emptyList()
        return when (le.getInt(start)) {
            0 -> emptyList()                                   // Null shape
            1, 11, 21 -> parsePoint(le, start, length)         // Point / Z / M
            3, 13, 23 -> parsePath(le, start, length, polygon = false)
            5, 15, 25 -> parsePath(le, start, length, polygon = true)
            // MultiPoint (8/18/28) and the multipatch types carry no
            // boundary meaning for a survey outline — skipped, not fatal.
            else -> emptyList()
        }
    }

    private fun parsePoint(le: ByteBuffer, start: Int, length: Int): List<BoundaryGeometry> {
        if (length < 20) return emptyList()
        val x = le.getDouble(start + 4)
        val y = le.getDouble(start + 12)
        return listOf(
            BoundaryGeometry(
                kind = BoundaryGeometryKind.POINT,
                rings = listOf(listOf(CoordinateConversions.LatLon(latitude = y, longitude = x))),
            )
        )
    }

    /// Polyline / Polygon share one layout: bbox(4d) numParts numPoints
    /// parts[] points[]. Z/M ordinates trail the points array and are
    /// never read.
    private fun parsePath(
        le: ByteBuffer,
        start: Int,
        length: Int,
        polygon: Boolean,
    ): List<BoundaryGeometry> {
        // 4 type + 32 bbox + 4 numParts + 4 numPoints
        if (length < 44) return emptyList()
        val numParts = le.getInt(start + 36)
        val numPoints = le.getInt(start + 40)
        if (numParts <= 0 || numPoints <= 0) return emptyList()
        val partsAt = start + 44
        val pointsAt = partsAt + numParts * 4
        if (pointsAt + numPoints * 16 > start + length) {
            throw BoundaryImportError("A shapefile record is truncated.")
        }
        val partStarts = IntArray(numParts) { le.getInt(partsAt + it * 4) }

        val parts = ArrayList<List<CoordinateConversions.LatLon>>(numParts)
        for (i in 0 until numParts) {
            val from = partStarts[i]
            val to = if (i + 1 < numParts) partStarts[i + 1] else numPoints
            if (from < 0 || to > numPoints || to <= from) continue
            val ring = ArrayList<CoordinateConversions.LatLon>(to - from)
            for (p in from until to) {
                val at = pointsAt + p * 16
                ring.add(
                    CoordinateConversions.LatLon(
                        latitude = le.getDouble(at + 8),
                        longitude = le.getDouble(at),
                    )
                )
            }
            if (ring.isNotEmpty()) parts.add(ring)
        }
        if (parts.isEmpty()) return emptyList()

        if (!polygon) {
            return parts.map {
                BoundaryGeometry(kind = BoundaryGeometryKind.POLYLINE, rings = listOf(it))
            }
        }

        // Polygon: clockwise part = new outer ring, counter-clockwise =
        // a hole of the ring that precedes it.
        val out = ArrayList<BoundaryGeometry>()
        val current = ArrayList<List<CoordinateConversions.LatLon>>()
        fun flush() {
            if (current.isNotEmpty()) {
                out.add(
                    BoundaryGeometry(
                        kind = BoundaryGeometryKind.POLYGON,
                        rings = current.toList(),
                    )
                )
                current.clear()
            }
        }
        for (ring in parts) {
            if (isClockwise(ring) || current.isEmpty()) {
                if (isClockwise(ring)) flush()
                current.add(ring)
            } else {
                current.add(ring)
            }
        }
        flush()
        return out
    }

    /// Shoelace sign in the lon/lat plane: > 0 is clockwise, which the
    /// shapefile spec reserves for outer rings.
    private fun isClockwise(ring: List<CoordinateConversions.LatLon>): Boolean {
        if (ring.size < 3) return true
        var sum = 0.0
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[(i + 1) % ring.size]
            sum += (b.longitude - a.longitude) * (b.latitude + a.latitude)
        }
        return sum > 0.0
    }
}
