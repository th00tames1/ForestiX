// Spec §8 Basemap/OfflineBasemap. REQ-PRJ-006 download queue planner.
//
// Given a project AOI (union of stratum polygons) and a zoom range, compute
// the set of tiles that must be on disk for offline use. The spec specifies
// zoom 12-17 plus a 1 km buffer around the AOI.
//
// The download engine itself is UI-owned (it needs URLSession progress UI);
// this file only plans the job and prunes already-cached tiles so the
// download queue shows accurate counts. Network I/O is deferred so that
// Phase 1 `swift test` does not need a live provider.

import Foundation
import Geo

public struct OfflineBasemapJob: Equatable, Sendable {
    public let tiles: [TileCache.Key]
    public let alreadyCached: Int
    public let totalTiles: Int

    public var remaining: Int { tiles.count }
    public var isEmpty: Bool { tiles.isEmpty }
}

public enum OfflineBasemap {

    /// Spec default: tiles for zoom 12..17 inclusive, buffered 1 km beyond the
    /// AOI's bounding box.
    public static let defaultZoomRange: ClosedRange<Int> = 12...17
    public static let defaultBufferMeters: Double = 1_000

    /// Build the download job. AOI is expressed as one or more outer rings in
    /// WGS84 lat/lon (stratum outer rings work); holes are ignored because
    /// prefetching slightly too many tiles is cheap.
    public static func planJob(
        aoiRings: [[CoordinateConversions.LatLon]],
        zoomRange: ClosedRange<Int> = defaultZoomRange,
        bufferMeters: Double = defaultBufferMeters,
        cache: TileCache
    ) -> OfflineBasemapJob {
        guard let bbox = bufferedBoundingBox(aoiRings: aoiRings, bufferMeters: bufferMeters) else {
            return OfflineBasemapJob(tiles: [], alreadyCached: 0, totalTiles: 0)
        }

        var all: [TileCache.Key] = []
        for z in zoomRange {
            // Tile indices of the NW and SE corners of the buffered bbox.
            let nw = TileMath.tile(for: CoordinateConversions.LatLon(
                latitude: bbox.maxLat, longitude: bbox.minLon), zoom: z)
            let se = TileMath.tile(for: CoordinateConversions.LatLon(
                latitude: bbox.minLat, longitude: bbox.maxLon), zoom: z)
            let minX = min(nw.x, se.x); let maxX = max(nw.x, se.x)
            let minY = min(nw.y, se.y); let maxY = max(nw.y, se.y)
            for y in minY...maxY {
                for x in minX...maxX {
                    all.append(TileCache.Key(z: z, x: x, y: y))
                }
            }
        }
        let needed = all.filter { !cache.isCached($0) }
        return OfflineBasemapJob(
            tiles: needed,
            alreadyCached: all.count - needed.count,
            totalTiles: all.count
        )
    }

    // MARK: - Bounding box

    struct LatLonBBox: Equatable {
        let minLat, maxLat, minLon, maxLon: Double
    }

    static func bufferedBoundingBox(
        aoiRings: [[CoordinateConversions.LatLon]],
        bufferMeters: Double
    ) -> LatLonBBox? {
        let all = aoiRings.flatMap { $0 }
        guard !all.isEmpty else { return nil }
        var minLat = Double.infinity, maxLat = -Double.infinity
        var minLon = Double.infinity, maxLon = -Double.infinity
        for p in all {
            minLat = min(minLat, p.latitude); maxLat = max(maxLat, p.latitude)
            minLon = min(minLon, p.longitude); maxLon = max(maxLon, p.longitude)
        }
        let midLat = (minLat + maxLat) / 2
        let dLat = bufferMeters / CoordinateConversions.metersPerDegreeLatitude
        let cosLat = cos(midLat * .pi / 180)
        let dLon = cosLat == 0
            ? 0
            : bufferMeters / (CoordinateConversions.metersPerDegreeLatitude * cosLat)
        return LatLonBBox(
            minLat: max(-85.05112878, minLat - dLat),
            maxLat: min(85.05112878, maxLat + dLat),
            minLon: max(-180, minLon - dLon),
            maxLon: min(180, maxLon + dLon)
        )
    }
}
