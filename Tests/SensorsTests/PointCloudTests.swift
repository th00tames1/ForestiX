// Spec §7.1 Steps 4 (back-projection) and 6 (statistical outlier removal).

import XCTest
import simd
@testable import Sensors

final class PointCloudTests: XCTestCase {

    // MARK: - Back projection

    /// Principal-axis ray: pixel at the principal point unprojects to
    /// (0, 0, d) in the camera frame. With identity pose the world point
    /// equals the camera point.
    func testBackProjectPrincipalRayIdentityPose() {
        let K = simd_float3x3(
            SIMD3<Float>(500, 0, 0),
            SIMD3<Float>(0, 500, 0),
            SIMD3<Float>(320, 240, 1))
        let T = matrix_identity_float4x4
        let p = BackProjection.worldPoint(
            x: 320, y: 240, depth: 2.5,
            intrinsics: K, cameraPoseWorld: T)
        XCTAssertEqual(p.x, 0, accuracy: 1e-6)
        XCTAssertEqual(p.y, 0, accuracy: 1e-6)
        XCTAssertEqual(p.z, 2.5, accuracy: 1e-6)
    }

    /// Off-axis pixel: x displacement = (px - cx) · d / fx.
    func testBackProjectOffAxisIdentityPose() {
        let K = simd_float3x3(
            SIMD3<Float>(500, 0, 0),
            SIMD3<Float>(0, 500, 0),
            SIMD3<Float>(320, 240, 1))
        let T = matrix_identity_float4x4
        let p = BackProjection.worldPoint(
            x: 320 + 100, y: 240, depth: 2.0,
            intrinsics: K, cameraPoseWorld: T)
        // (100 * 2.0 / 500) = 0.4
        XCTAssertEqual(p.x, 0.4, accuracy: 1e-6)
        XCTAssertEqual(p.y, 0, accuracy: 1e-6)
        XCTAssertEqual(p.z, 2.0, accuracy: 1e-6)
    }

    /// Pose translation: applying T = translate(+1 in world x) shifts the
    /// unprojected point by +1 in world x.
    func testWorldPoseTranslation() {
        let K = simd_float3x3(
            SIMD3<Float>(500, 0, 0),
            SIMD3<Float>(0, 500, 0),
            SIMD3<Float>(320, 240, 1))
        var T = matrix_identity_float4x4
        T.columns.3 = SIMD4<Float>(1.0, 0.0, 0.0, 1.0)
        let p = BackProjection.worldPoint(
            x: 320, y: 240, depth: 2.5,
            intrinsics: K, cameraPoseWorld: T)
        XCTAssertEqual(p.x, 1.0, accuracy: 1e-6)
        XCTAssertEqual(p.y, 0, accuracy: 1e-6)
        XCTAssertEqual(p.z, 2.5, accuracy: 1e-6)
    }

    /// worldXZ(…) projects the world point to the horizontal plane.
    func testWorldXZDropsY() {
        let K = simd_float3x3(
            SIMD3<Float>(500, 0, 0),
            SIMD3<Float>(0, 500, 0),
            SIMD3<Float>(320, 240, 1))
        var T = matrix_identity_float4x4
        T.columns.3 = SIMD4<Float>(0, 5.0, 2.0, 1.0)
        let xz = BackProjection.worldXZ(
            x: 320, y: 240, depth: 2.5,
            intrinsics: K, cameraPoseWorld: T)
        XCTAssertEqual(xz.x, 0, accuracy: 1e-6)
        XCTAssertEqual(xz.y, 4.5, accuracy: 1e-6)  // world Z = 2.0 + 2.5
    }

    // MARK: - Statistical outlier removal

    /// 100 points on a circle (small noise) plus two obvious outliers.
    /// With k=8 σ=2 the outliers must be dropped, inliers retained.
    func testStatisticalOutlierRemovalDropsFarPoints() {
        var rng = LCG(seed: 17)
        var pts: [SIMD2<Double>] = []
        let r = 0.3
        for i in 0..<100 {
            let t = Double(i) / 100 * 2 * .pi
            let dr = rng.gaussian() * 0.003
            pts.append(SIMD2(cos(t) * (r + dr), sin(t) * (r + dr)))
        }
        pts.append(SIMD2(5.0, 5.0))     // far outlier
        pts.append(SIMD2(-6.0, 2.0))    // far outlier

        let kept = OutlierRemoval.statistical(points: pts, k: 8, sigmaMult: 2.0)

        XCTAssertLessThan(kept.count, pts.count,
            "SOR should drop at least the two far outliers")
        for p in kept {
            XCTAssertLessThan(
                (p.x * p.x + p.y * p.y).squareRoot(), 1.0,
                "No surviving point should be the far outlier at (5,5) or (-6,2)")
        }
        XCTAssertGreaterThan(kept.count, 90,
            "Most circle points must be retained")
    }

    /// Degenerate — fewer points than k+1 — must not crash and must
    /// return the input unchanged.
    func testStatisticalOutlierRemovalTinyInput() {
        let pts: [SIMD2<Double>] = [
            SIMD2(0, 0), SIMD2(1, 0), SIMD2(0, 1)
        ]
        let kept = OutlierRemoval.statistical(points: pts, k: 8, sigmaMult: 2.0)
        XCTAssertEqual(kept.count, 3)
    }

    /// Mask output aligns one-to-one with input indices.
    func testStatisticalMaskSameLength() {
        var rng = LCG(seed: 3)
        var pts: [SIMD2<Double>] = []
        for _ in 0..<60 { pts.append(SIMD2(rng.gaussian(), rng.gaussian())) }
        let mask = OutlierRemoval.statisticalMask(
            points: pts, k: 8, sigmaMult: 2.0)
        XCTAssertEqual(mask.count, pts.count)
    }
}

// MARK: - Deterministic RNG shared with CircleFitTests via file-private

private struct LCG {
    private var state: UInt64
    init(seed: UInt64) { state = seed &* 6_364_136_223_846_793_005 &+ 1 }
    mutating func next() -> UInt64 {
        state = state &* 6_364_136_223_846_793_005 &+ 1_442_695_040_888_963_407
        return state
    }
    mutating func unit() -> Double {
        Double(next() >> 11) / Double(1 << 53)
    }
    mutating func gaussian() -> Double {
        let u1 = max(unit(), 1e-12)
        let u2 = unit()
        return sqrt(-2 * log(u1)) * cos(2 * .pi * u2)
    }
}
