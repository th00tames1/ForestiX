// Cardinal-direction + distance sanity tests for TreePlacementHelper.

import XCTest
import simd
@testable import Sensors

final class TreePlacementHelperTests: XCTestCase {

    private let center = SIMD3<Float>(0, 0, 0)

    func testNorthIs0Degrees() {
        // North = -Z, 10 m north of center.
        let camera = SIMD3<Float>(0, 0, -10)
        let p = TreePlacementHelper.placement(
            plotCenterWorld: center, cameraWorld: camera)
        XCTAssertNotNil(p)
        XCTAssertEqual(p!.bearingDeg, 0, accuracy: 0.001)
        XCTAssertEqual(p!.distanceFromCenterM, 10, accuracy: 1e-4)
    }

    func testEastIs90Degrees() {
        let camera = SIMD3<Float>(5, 0, 0)
        let p = TreePlacementHelper.placement(
            plotCenterWorld: center, cameraWorld: camera)!
        XCTAssertEqual(p.bearingDeg, 90, accuracy: 0.001)
        XCTAssertEqual(p.distanceFromCenterM, 5, accuracy: 1e-4)
    }

    func testSouthIs180Degrees() {
        let camera = SIMD3<Float>(0, 0, 7.5)
        let p = TreePlacementHelper.placement(
            plotCenterWorld: center, cameraWorld: camera)!
        XCTAssertEqual(p.bearingDeg, 180, accuracy: 0.001)
        XCTAssertEqual(p.distanceFromCenterM, 7.5, accuracy: 1e-4)
    }

    func testWestIs270Degrees() {
        let camera = SIMD3<Float>(-3, 0, 0)
        let p = TreePlacementHelper.placement(
            plotCenterWorld: center, cameraWorld: camera)!
        XCTAssertEqual(p.bearingDeg, 270, accuracy: 0.001)
    }

    func testNortheastIs45Degrees() {
        // +X (east) +(-Z) (north) equal magnitudes.
        let camera = SIMD3<Float>(10, 0, -10)
        let p = TreePlacementHelper.placement(
            plotCenterWorld: center, cameraWorld: camera)!
        XCTAssertEqual(p.bearingDeg, 45, accuracy: 0.001)
        XCTAssertEqual(p.distanceFromCenterM, 14.142, accuracy: 0.01)
    }

    func testYComponentIgnored() {
        // Horizontal distance ignores vertical — standing on a slope.
        let camera = SIMD3<Float>(3, 2, -4)   // 2 m "up"
        let p = TreePlacementHelper.placement(
            plotCenterWorld: center, cameraWorld: camera)!
        XCTAssertEqual(p.distanceFromCenterM, 5, accuracy: 1e-4)
    }

    func testTooCloseReturnsNil() {
        let camera = SIMD3<Float>(0.005, 0, 0.005)
        let p = TreePlacementHelper.placement(
            plotCenterWorld: center, cameraWorld: camera)
        XCTAssertNil(p)
    }

    func testNonZeroCenterDelta() {
        let center = SIMD3<Float>(100, 0, 50)
        let camera = SIMD3<Float>(110, 0, 40)       // +10 east, -10 north
        let p = TreePlacementHelper.placement(
            plotCenterWorld: center, cameraWorld: camera)!
        XCTAssertEqual(p.bearingDeg, 45, accuracy: 0.001)
        XCTAssertEqual(p.distanceFromCenterM, 14.142, accuracy: 0.01)
    }
}
