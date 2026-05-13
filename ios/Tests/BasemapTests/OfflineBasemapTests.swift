// Spec §8 Basemap/OfflineBasemap. REQ-PRJ-006.

import XCTest
import Geo
@testable import Basemap

final class OfflineBasemapTests: XCTestCase {

    private var tmp: URL!
    override func setUpWithError() throws {
        tmp = FileManager.default.temporaryDirectory
            .appendingPathComponent("OfflineBasemapTests-\(UUID().uuidString)")
    }
    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: tmp)
    }

    private func makeCache() throws -> TileCache {
        let provider = TileCache.ProviderConfig(
            urlTemplate: "https://tile.example.com/{z}/{x}/{y}.png",
            fileExtension: "png", providerId: "test")
        return try TileCache(rootURL: tmp, provider: provider)
    }

    private let aoi: [[CoordinateConversions.LatLon]] = [[
        .init(latitude: 47.60, longitude: -122.30),
        .init(latitude: 47.60, longitude: -122.29),
        .init(latitude: 47.61, longitude: -122.29),
        .init(latitude: 47.61, longitude: -122.30),
        .init(latitude: 47.60, longitude: -122.30)
    ]]

    func testJobIncludesMultipleZooms() throws {
        let cache = try makeCache()
        let job = OfflineBasemap.planJob(aoiRings: aoi, cache: cache)
        XCTAssertFalse(job.isEmpty)
        let zooms = Set(job.tiles.map(\.z))
        XCTAssertEqual(zooms, Set(12...17))
    }

    func testJobCountIsFullSetWhenCacheEmpty() throws {
        let cache = try makeCache()
        let job = OfflineBasemap.planJob(aoiRings: aoi, cache: cache)
        XCTAssertEqual(job.remaining, job.totalTiles)
        XCTAssertEqual(job.alreadyCached, 0)
    }

    func testAlreadyCachedTilesAreOmitted() throws {
        let cache = try makeCache()
        let first = OfflineBasemap.planJob(aoiRings: aoi, cache: cache)
        // Store a chunk of the tiles so the next plan sees them as cached.
        let toCache = Array(first.tiles.prefix(10))
        for key in toCache {
            try cache.store(Data([0]), for: key)
        }
        let second = OfflineBasemap.planJob(aoiRings: aoi, cache: cache)
        XCTAssertEqual(second.alreadyCached, 10)
        XCTAssertEqual(second.remaining, first.totalTiles - 10)
    }

    func testEmptyAOIReturnsEmptyJob() throws {
        let cache = try makeCache()
        let job = OfflineBasemap.planJob(aoiRings: [], cache: cache)
        XCTAssertTrue(job.isEmpty)
        XCTAssertEqual(job.totalTiles, 0)
    }

    func testBufferExpandsBoundingBox() {
        let bboxNoBuffer = OfflineBasemap.bufferedBoundingBox(aoiRings: aoi, bufferMeters: 0)!
        let bboxBuffered = OfflineBasemap.bufferedBoundingBox(aoiRings: aoi, bufferMeters: 1000)!
        XCTAssertLessThan(bboxBuffered.minLat, bboxNoBuffer.minLat)
        XCTAssertGreaterThan(bboxBuffered.maxLat, bboxNoBuffer.maxLat)
        XCTAssertLessThan(bboxBuffered.minLon, bboxNoBuffer.minLon)
        XCTAssertGreaterThan(bboxBuffered.maxLon, bboxNoBuffer.maxLon)
    }
}
