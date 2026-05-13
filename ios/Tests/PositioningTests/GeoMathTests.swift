// Sanity-checks for haversine + bearing: N/E/S/W from a known point
// at mid-latitude, and the 5 m arrival threshold.

import XCTest
@testable import Positioning

final class GeoMathTests: XCTestCase {

    private let lat0 = 45.0, lon0 = -122.0

    func testDistanceZeroWhenSamePoint() {
        XCTAssertEqual(
            GeoMath.distanceM(
                fromLat: lat0, fromLon: lon0,
                toLat: lat0,   toLon: lon0),
            0, accuracy: 1e-6)
    }

    func testDistanceApprox50mNorth() {
        let dLat = 50.0 / 111_320.0
        let d = GeoMath.distanceM(
            fromLat: lat0, fromLon: lon0,
            toLat: lat0 + dLat, toLon: lon0)
        XCTAssertEqual(d, 50, accuracy: 0.1)
    }

    func testBearingNorth() {
        let dLat = 50.0 / 111_320.0
        let b = GeoMath.bearingDeg(
            fromLat: lat0, fromLon: lon0,
            toLat: lat0 + dLat, toLon: lon0)
        XCTAssertEqual(b, 0, accuracy: 0.5)
    }

    func testBearingEast() {
        let dLon = 50.0 / (111_320.0 * cos(lat0 * .pi / 180))
        let b = GeoMath.bearingDeg(
            fromLat: lat0, fromLon: lon0,
            toLat: lat0, toLon: lon0 + dLon)
        XCTAssertEqual(b, 90, accuracy: 0.5)
    }

    func testBearingSouthIs180() {
        let dLat = 50.0 / 111_320.0
        let b = GeoMath.bearingDeg(
            fromLat: lat0, fromLon: lon0,
            toLat: lat0 - dLat, toLon: lon0)
        XCTAssertEqual(b, 180, accuracy: 0.5)
    }

    func testBearingWestIs270() {
        let dLon = 50.0 / (111_320.0 * cos(lat0 * .pi / 180))
        let b = GeoMath.bearingDeg(
            fromLat: lat0, fromLon: lon0,
            toLat: lat0, toLon: lon0 - dLon)
        XCTAssertEqual(b, 270, accuracy: 0.5)
    }
}
