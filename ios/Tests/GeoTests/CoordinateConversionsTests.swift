// Spec §8 Geo/CoordinateConversions — lat/lon ↔ ENU, haversine, bearing.

import XCTest
@testable import Geo

final class CoordinateConversionsTests: XCTestCase {

    func testToENURoundTrip() {
        let origin = CoordinateConversions.LatLon(latitude: 47.65, longitude: -122.30)
        let point = CoordinateConversions.LatLon(latitude: 47.66, longitude: -122.29)
        let enu = CoordinateConversions.toENU(point: point, origin: origin)
        let back = CoordinateConversions.toLatLon(enu: enu, origin: origin)
        XCTAssertEqual(back.latitude, point.latitude, accuracy: 1e-9)
        XCTAssertEqual(back.longitude, point.longitude, accuracy: 1e-9)
    }

    func testToENUOriginIsZero() {
        let origin = CoordinateConversions.LatLon(latitude: 47.0, longitude: -122.0)
        let enu = CoordinateConversions.toENU(point: origin, origin: origin)
        XCTAssertEqual(enu.east, 0, accuracy: 1e-9)
        XCTAssertEqual(enu.north, 0, accuracy: 1e-9)
    }

    func testOneDegreeNorthIsAbout111kmNorth() {
        let origin = CoordinateConversions.LatLon(latitude: 47.0, longitude: -122.0)
        let point = CoordinateConversions.LatLon(latitude: 48.0, longitude: -122.0)
        let enu = CoordinateConversions.toENU(point: point, origin: origin)
        XCTAssertEqual(enu.east, 0, accuracy: 1e-6)
        XCTAssertEqual(enu.north, 111_320, accuracy: 1e-3)
    }

    func testHaversineKnownPair() {
        // Seattle → Vancouver BC ≈ 196 km (great-circle).
        let sea = CoordinateConversions.LatLon(latitude: 47.6062, longitude: -122.3321)
        let yvr = CoordinateConversions.LatLon(latitude: 49.2827, longitude: -123.1207)
        let d = CoordinateConversions.haversineMeters(sea, yvr)
        XCTAssertEqual(d, 196_000, accuracy: 5_000)
    }

    func testInitialBearingCardinals() {
        let a = CoordinateConversions.LatLon(latitude: 47.0, longitude: -122.0)
        let north = CoordinateConversions.LatLon(latitude: 48.0, longitude: -122.0)
        let south = CoordinateConversions.LatLon(latitude: 46.0, longitude: -122.0)
        XCTAssertEqual(CoordinateConversions.initialBearingDegrees(from: a, to: north), 0, accuracy: 0.01)
        XCTAssertEqual(CoordinateConversions.initialBearingDegrees(from: a, to: south), 180, accuracy: 0.01)
        // Due-east along a great circle initially points slightly north of 90°
        // at mid-latitudes. At 47°N across 1° lon this deviates ~0.4°.
        let east = CoordinateConversions.LatLon(latitude: 47.0, longitude: -121.0)
        let bearing = CoordinateConversions.initialBearingDegrees(from: a, to: east)
        XCTAssertLessThan(bearing, 90)
        XCTAssertGreaterThan(bearing, 85)
    }
}
