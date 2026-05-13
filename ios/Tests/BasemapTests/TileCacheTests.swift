// Spec §8 Basemap/TileCache. REQ-PRJ-006.

import XCTest
import Geo
@testable import Basemap

final class TileCacheTests: XCTestCase {

    private var tmp: URL!

    override func setUpWithError() throws {
        tmp = FileManager.default.temporaryDirectory
            .appendingPathComponent("TileCacheTests-\(UUID().uuidString)")
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: tmp)
    }

    private func makeCache() throws -> TileCache {
        let provider = TileCache.ProviderConfig(
            urlTemplate: "https://tile.example.com/{z}/{x}/{y}.png",
            fileExtension: "png",
            providerId: "test-provider"
        )
        return try TileCache(rootURL: tmp, provider: provider)
    }

    func testStoreAndRetrieveRoundTrip() throws {
        let cache = try makeCache()
        let key = TileCache.Key(z: 14, x: 1234, y: 5678)
        let payload = Data([0x89, 0x50, 0x4E, 0x47])
        XCTAssertFalse(cache.isCached(key))
        try cache.store(payload, for: key)
        XCTAssertTrue(cache.isCached(key))
        XCTAssertEqual(cache.data(for: key), payload)
    }

    func testFileURLReflectsSlippyPath() throws {
        let cache = try makeCache()
        let url = cache.fileURL(for: .init(z: 12, x: 3, y: 4))
        XCTAssertTrue(url.path.hasSuffix("/test-provider/12/3/4.png"))
    }

    func testStatsCountsStoredFiles() throws {
        let cache = try makeCache()
        try cache.store(Data(count: 100), for: .init(z: 12, x: 0, y: 0))
        try cache.store(Data(count: 50),  for: .init(z: 12, x: 1, y: 0))
        let stats = cache.stats()
        XCTAssertEqual(stats.fileCount, 2)
        XCTAssertEqual(stats.byteCount, 150)
    }

    func testClearEmptiesTheProviderDirectory() throws {
        let cache = try makeCache()
        try cache.store(Data([1]), for: .init(z: 12, x: 0, y: 0))
        XCTAssertGreaterThan(cache.stats().fileCount, 0)
        try cache.clear()
        XCTAssertEqual(cache.stats().fileCount, 0)
    }

    func testResolvedURLSubstitutesTokens() throws {
        let cache = try makeCache()
        let url = cache.resolvedURL(for: .init(z: 14, x: 1, y: 2))
        XCTAssertEqual(url?.absoluteString, "https://tile.example.com/14/1/2.png")
    }

    // MARK: - Tile math

    func testTileForKnownLocation() {
        // Greenwich (0°,0°) at z=2 should be tile (2, 2) in XYZ.
        let key = TileMath.tile(
            for: CoordinateConversions.LatLon(latitude: 0, longitude: 0),
            zoom: 2
        )
        XCTAssertEqual(key.x, 2)
        XCTAssertEqual(key.y, 2)
    }

    func testTileCornerRoundTripsThroughTileLookup() {
        let key = TileCache.Key(z: 15, x: 5263, y: 11494)
        let nw = TileMath.northwestCorner(of: key)
        // The NW corner must belong to the same tile at this zoom. Nudge
        // slightly inside to dodge boundary-rounding ambiguity.
        let inside = CoordinateConversions.LatLon(
            latitude: nw.latitude - 0.00005,
            longitude: nw.longitude + 0.00005
        )
        XCTAssertEqual(TileMath.tile(for: inside, zoom: 15), key)
    }
}
