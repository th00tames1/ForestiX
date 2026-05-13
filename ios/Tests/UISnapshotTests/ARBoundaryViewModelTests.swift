// Pure-logic coverage for ARBoundaryViewModel: center placement, ring
// slope-correction against a supplied ground mesh, fixed-area
// membership classification, and the 15 m drift warn. No ARKit is
// touched — the default initializer uses the macOS stub session.

import XCTest
import simd
import AR
@testable import UI

@MainActor
final class ARBoundaryViewModelTests: XCTestCase {

    func testInitialStateIsEmpty() {
        let vm = ARBoundaryViewModel()
        XCTAssertNil(vm.centerWorld)
        XCTAssertTrue(vm.ringVertices.isEmpty)
        XCTAssertEqual(vm.userDistanceM, 0, accuracy: 1e-5)
        XCTAssertFalse(vm.isDrifted)
    }

    func testSetCenterBuilds73RingVertices() {
        let vm = ARBoundaryViewModel()
        vm.setCenter(SIMD3<Float>(1, 0, 2))
        XCTAssertEqual(vm.ringVertices.count, 73)
        XCTAssertNotNil(vm.centerWorld)
        // Every vertex is on the ring radius (flat because mesh empty).
        for v in vm.ringVertices {
            let dx = v.x - 1
            let dz = v.z - 2
            let d = sqrt(dx * dx + dz * dz)
            XCTAssertEqual(d, vm.radiusM, accuracy: 1e-4)
        }
    }

    func testClearCenterResetsAllDerivedState() {
        let vm = ARBoundaryViewModel()
        vm.setCenter(SIMD3<Float>(0, 0, 0))
        vm.updateUserPosition(SIMD3<Float>(20, 0, 0))
        XCTAssertTrue(vm.isDrifted)
        vm.clearCenter()
        XCTAssertNil(vm.centerWorld)
        XCTAssertTrue(vm.ringVertices.isEmpty)
        XCTAssertFalse(vm.isDrifted)
        XCTAssertEqual(vm.userDistanceM, 0, accuracy: 1e-5)
    }

    func testRingSlopeCorrectionFromGroundMesh() {
        let vm = ARBoundaryViewModel()
        vm.radiusM = 5
        // Large flat quad at y = 4 covering the ring footprint.
        let mesh = GroundMeshSnapshot(
            vertices: [
                SIMD3<Float>(-10, 4, -10),
                SIMD3<Float>( 10, 4, -10),
                SIMD3<Float>( 10, 4,  10),
                SIMD3<Float>(-10, 4,  10)
            ],
            triangles: [0, 1, 2, 0, 2, 3])
        vm.updateGroundMesh(mesh)
        vm.setCenter(SIMD3<Float>(0, 0, 0))
        for v in vm.ringVertices {
            XCTAssertEqual(v.y, 4, accuracy: 1e-4,
                           "ring vertex should snap to mesh Y")
        }
    }

    func testMembershipInsideOutsideBorderline() {
        let vm = ARBoundaryViewModel()
        vm.radiusM = 5
        vm.setCenter(SIMD3<Float>(0, 0, 0))
        XCTAssertEqual(
            vm.membership(forStemXZ: SIMD2<Float>(1, 0)),
            .inside)
        XCTAssertEqual(
            vm.membership(forStemXZ: SIMD2<Float>(10, 0)),
            .outside)
        XCTAssertEqual(
            vm.membership(forStemXZ: SIMD2<Float>(5.05, 0)),
            .borderline)
    }

    func testMembershipNilWithoutCenter() {
        let vm = ARBoundaryViewModel()
        XCTAssertNil(vm.membership(forStemXZ: SIMD2<Float>(0, 0)))
    }

    func testDriftWarnAt15mThreshold() {
        let vm = ARBoundaryViewModel()
        vm.setCenter(SIMD3<Float>(0, 0, 0))
        vm.updateUserPosition(SIMD3<Float>(14, 0, 0))
        XCTAssertFalse(vm.isDrifted)
        vm.updateUserPosition(SIMD3<Float>(15.5, 0, 0))
        XCTAssertTrue(vm.isDrifted)
        XCTAssertEqual(vm.userDistanceM, 15.5, accuracy: 1e-4)
    }

    func testDriftCustomThreshold() {
        let vm = ARBoundaryViewModel()
        vm.driftRadiusM = 8
        vm.setCenter(SIMD3<Float>(0, 0, 0))
        vm.updateUserPosition(SIMD3<Float>(9, 0, 0))
        XCTAssertTrue(vm.isDrifted)
    }
}
