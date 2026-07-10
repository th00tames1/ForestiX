// Offline basemap planner — 1:1 port of iOS Basemap/OfflineBasemap.swift
// (spec §8, REQ-PRJ-006 download queue planner).
//
// Given a project AOI (union of stratum polygons) and a zoom range, compute
// the set of tiles that must be on disk for offline use. The spec specifies
// zoom 12-17 plus a 1 km buffer around the AOI.
//
// The download engine itself is UI-owned (it needs progress UI); this file
// only plans the job and prunes already-cached tiles so the download queue
// shows accurate counts. Network I/O lives in TileFetcher.

package com.hcjeong.forestix.basemap

import com.hcjeong.forestix.geo.CoordinateConversions
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

data class OfflineBasemapJob(
    val tiles: List<TileCache.Key>,
    val alreadyCached: Int,
    val totalTiles: Int,
) {
    val remaining: Int get() = tiles.size
    val isEmpty: Boolean get() = tiles.isEmpty()
}

object OfflineBasemap {

    /// Spec default: tiles for zoom 12..17 inclusive, buffered 1 km beyond the
    /// AOI's bounding box.
    val defaultZoomRange: IntRange = 12..17
    const val defaultBufferMeters: Double = 1_000.0

    /// Build the download job. AOI is expressed as one or more outer rings in
    /// WGS84 lat/lon (stratum outer rings work); holes are ignored because
    /// prefetching slightly too many tiles is cheap.
    fun planJob(
        aoiRings: List<List<CoordinateConversions.LatLon>>,
        zoomRange: IntRange = defaultZoomRange,
        bufferMeters: Double = defaultBufferMeters,
        cache: TileCache,
    ): OfflineBasemapJob {
        val bbox = bufferedBoundingBox(aoiRings = aoiRings, bufferMeters = bufferMeters)
            ?: return OfflineBasemapJob(tiles = emptyList(), alreadyCached = 0, totalTiles = 0)

        val all = ArrayList<TileCache.Key>()
        for (z in zoomRange) {
            // Tile indices of the NW and SE corners of the buffered bbox.
            val nw = TileMath.tile(
                CoordinateConversions.LatLon(latitude = bbox.maxLat, longitude = bbox.minLon),
                zoom = z,
            )
            val se = TileMath.tile(
                CoordinateConversions.LatLon(latitude = bbox.minLat, longitude = bbox.maxLon),
                zoom = z,
            )
            val minX = min(nw.x, se.x)
            val maxX = max(nw.x, se.x)
            val minY = min(nw.y, se.y)
            val maxY = max(nw.y, se.y)
            for (y in minY..maxY) {
                for (x in minX..maxX) {
                    all.add(TileCache.Key(z = z, x = x, y = y))
                }
            }
        }
        val needed = all.filter { !cache.isCached(it) }
        return OfflineBasemapJob(
            tiles = needed,
            alreadyCached = all.size - needed.size,
            totalTiles = all.size,
        )
    }

    // MARK: - Bounding box

    internal data class LatLonBBox(
        val minLat: Double,
        val maxLat: Double,
        val minLon: Double,
        val maxLon: Double,
    )

    internal fun bufferedBoundingBox(
        aoiRings: List<List<CoordinateConversions.LatLon>>,
        bufferMeters: Double,
    ): LatLonBBox? {
        val all = aoiRings.flatten()
        if (all.isEmpty()) return null
        var minLat = Double.POSITIVE_INFINITY
        var maxLat = Double.NEGATIVE_INFINITY
        var minLon = Double.POSITIVE_INFINITY
        var maxLon = Double.NEGATIVE_INFINITY
        for (p in all) {
            minLat = min(minLat, p.latitude); maxLat = max(maxLat, p.latitude)
            minLon = min(minLon, p.longitude); maxLon = max(maxLon, p.longitude)
        }
        val midLat = (minLat + maxLat) / 2
        val dLat = bufferMeters / CoordinateConversions.metersPerDegreeLatitude
        val cosLat = cos(midLat * Math.PI / 180)
        val dLon = if (cosLat == 0.0) 0.0
        else bufferMeters / (CoordinateConversions.metersPerDegreeLatitude * cosLat)
        return LatLonBBox(
            minLat = max(-85.05112878, minLat - dLat),
            maxLat = min(85.05112878, maxLat + dLat),
            minLon = max(-180.0, minLon - dLon),
            maxLon = min(180.0, maxLon + dLon),
        )
    }
}
