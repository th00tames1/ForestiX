// Pure-math coverage for PlotBoundaryRenderer: ring geometry, slope
// correction via synthetic sampler, fixed-area + variable-radius
// membership bands, and the 15 m drift warn threshold. The RealityKit
// rendering path is iOS-only and intentionally not exercised here
// (Phase 3 Q1 decision: unit-test the math, skip RealityKit snapshots).

import XCTest
import simd
@testable import AR

final class PlotBoundaryRendererTests: XCTestCase {

    // MARK: - ringVertices

    func testRingVerticesReturnsCountPlusOne() {
        let v = PlotBoundaryRenderer.ringVertices(
            center: SIMD3<Float>(0, 0, 0),
            radiusM: 5,
            count: 72)
        XCTAssertEqual(v.count, 73)
    }

    func testRingVerticesAllOnRadius() {
        let center = SIMD3<Float>(1, 2, 3)
        let R: Float = 11.28
        let v = PlotBoundaryRenderer.ringVertices(
            center: center, radiusM: R, count: 72)
        for p in v {
            let dx = p.x - center.x
            let dz = p.z - center.z
            let d = sqrt(dx * dx + dz * dz)
            XCTAssertEqual(d, R, accuracy: 1e-4)
            XCTAssertEqual(p.y, center.y, accuracy: 1e-5)
        }
    }

    func testRingFirstAndLastCoincide() {
        let v = PlotBoundaryRenderer.ringVertices(
            center: SIMD3<Float>(0, 0, 0),
            radiusM: 3,
            count: 72)
        let first = v.first!
        let last = v.last!
        XCTAssertEqual(first.x, last.x, accuracy: 1e-5)
        XCTAssertEqual(first.y, last.y, accuracy: 1e-5)
        XCTAssertEqual(first.z, last.z, accuracy: 1e-5)
    }

    // MARK: - slopeCorrected

    func testSlopeCorrectedUsesSamplerY() {
        let ring = PlotBoundaryRenderer.ringVertices(
            center: SIMD3<Float>(0, 0, 0),
            radiusM: 5,
            count: 12)
        // Synthetic sloped mesh: y = 0.1 · x
        let corrected = PlotBoundaryRenderer.slopeCorrected(ring) { x, _ in
            0.1 * x
        }
        XCTAssertEqual(corrected.count, ring.count)
        for p in corrected {
            XCTAssertEqual(p.y, 0.1 * p.x, accuracy: 1e-5)
        }
    }

    func testSlopeCorrectedPreservesYWhenSamplerReturnsNil() {
        let ring = PlotBoundaryRenderer.ringVertices(
            center: SIMD3<Float>(0, 7, 0),
            radiusM: 4,
            count: 8)
        let corrected = PlotBoundaryRenderer.slopeCorrected(ring) { _, _ in nil }
        for p in corrected {
            XCTAssertEqual(p.y, 7, accuracy: 1e-5)
        }
    }

    // MARK: - Fixed-area membership

    func testFixedAreaMembershipInside() {
        let m = PlotBoundaryRenderer.membership(
            stemPositionXZ: SIMD2<Float>(1, 1),
            centerXZ: SIMD2<Float>(0, 0),
            radiusM: 5)
        XCTAssertEqual(m, .inside)
    }

    func testFixedAreaMembershipOutside() {
        let m = PlotBoundaryRenderer.membership(
            stemPositionXZ: SIMD2<Float>(10, 0),
            centerXZ: SIMD2<Float>(0, 0),
            radiusM: 5)
        XCTAssertEqual(m, .outside)
    }

    func testFixedAreaMembershipBorderlineBand() {
        // ±0.2 m default band around radius 5.
        for d: Float in [4.85, 5.0, 5.15] {
            let m = PlotBoundaryRenderer.membership(
                stemPositionXZ: SIMD2<Float>(d, 0),
                centerXZ: SIMD2<Float>(0, 0),
                radiusM: 5)
            XCTAssertEqual(m, .borderline, "d=\(d) should be borderline")
        }
    }

    // MARK: - Variable-radius membership

    func testVariableRadiusMembership() {
        XCTAssertEqual(
            PlotBoundaryRenderer.membership(
                distanceToStemM: 3, limitDistanceM: 5),
            .inside)
        XCTAssertEqual(
            PlotBoundaryRenderer.membership(
                distanceToStemM: 7, limitDistanceM: 5),
            .outside)
        XCTAssertEqual(
            PlotBoundaryRenderer.membership(
                distanceToStemM: 5.1, limitDistanceM: 5),
            .borderline)
    }

    // MARK: - Drift warn

    func testIsDriftedBeyondDefault15m() {
        // Within 15 m → false
        XCTAssertFalse(PlotBoundaryRenderer.isDriftedBeyond(
            userXZ: SIMD2<Float>(10, 0),
            centerXZ: SIMD2<Float>(0, 0)))
        // Exactly at 15 m → false (strict >)
        XCTAssertFalse(PlotBoundaryRenderer.isDriftedBeyond(
            userXZ: SIMD2<Float>(15, 0),
            centerXZ: SIMD2<Float>(0, 0)))
        // Beyond 15 m → true
        XCTAssertTrue(PlotBoundaryRenderer.isDriftedBeyond(
            userXZ: SIMD2<Float>(16, 0),
            centerXZ: SIMD2<Float>(0, 0)))
    }

    func testIsDriftedBeyondCustomThreshold() {
        XCTAssertTrue(PlotBoundaryRenderer.isDriftedBeyond(
            userXZ: SIMD2<Float>(6, 0),
            centerXZ: SIMD2<Float>(0, 0),
            driftRadiusM: 5))
        XCTAssertFalse(PlotBoundaryRenderer.isDriftedBeyond(
            userXZ: SIMD2<Float>(3, 0),
            centerXZ: SIMD2<Float>(0, 0),
            driftRadiusM: 5))
    }
}
