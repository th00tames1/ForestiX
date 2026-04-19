// Spec §7.2 Done-Criteria coverage plus a tier-transition sweep across
// the σ_H / d_h / α_top warn thresholds. All fixtures use the identity
// ProjectCalibration so vioDriftFraction = 0.02 per spec default.

import XCTest
import simd
import Common
import Models
@testable import Sensors

final class HeightEstimatorTests: XCTestCase {

    // MARK: - Convenience

    private func input(
        dh: Float,
        alphaTopDeg: Float,
        alphaBaseDeg: Float,
        tracking: Bool = true,
        vioDrift: Float = 0.02
    ) -> HeightMeasureInput {
        let calib = ProjectCalibration(
            depthNoiseMm: 5,
            dbhCorrectionAlpha: 0,
            dbhCorrectionBeta: 1,
            vioDriftFraction: vioDrift)
        return HeightMeasureInput(
            anchorPointWorld: SIMD3<Float>(0, 0, 0),
            standingPointWorld: SIMD3<Float>(dh, 1.6, 0), // Y component irrelevant
            alphaTopRad: alphaTopDeg * .pi / 180,
            alphaBaseRad: alphaBaseDeg * .pi / 180,
            trackingStateWasNormalThroughout: tracking,
            projectCalibration: calib)
    }

    // MARK: - §7.2 Done Criteria

    /// `d_h = 25, α_top = 56.9°, α_base = -3.66°` → H ≈ 40 m and σ_H ≈ 0.9 m.
    /// Spec tolerance 0.1 m on height; we also bound σ_H within 0.1 m.
    func testSpec7_2KnownHeight() {
        let r = HeightEstimator.estimate(input: input(
            dh: 25, alphaTopDeg: 56.9, alphaBaseDeg: -3.66))

        XCTAssertEqual(r.heightM, 40.0, accuracy: 0.1)
        XCTAssertEqual(r.sigmaHm, 0.9, accuracy: 0.1)
        XCTAssertEqual(r.method, .vioWalkoffTangent)
        XCTAssertNil(r.rejectionReason)
    }

    func testTooCloseReturnsRed() {
        let r = HeightEstimator.estimate(input: input(
            dh: 2, alphaTopDeg: 60, alphaBaseDeg: -5))
        XCTAssertEqual(r.confidence, .red)
        XCTAssertNotNil(r.rejectionReason)
        XCTAssertTrue(r.rejectionReason!.contains("Too close"),
                      "reason = \(r.rejectionReason!)")
    }

    func testTooSteepAlphaTopReturnsRed() {
        let r = HeightEstimator.estimate(input: input(
            dh: 15, alphaTopDeg: 88, alphaBaseDeg: -5))
        XCTAssertEqual(r.confidence, .red)
        XCTAssertTrue(r.rejectionReason?.contains("steep") ?? false,
                      "reason = \(r.rejectionReason ?? "nil")")
    }

    func testTrackingLostAtAnyPointReturnsRed() {
        // Otherwise-green geometry; only the tracking flag trips the red.
        let r = HeightEstimator.estimate(input: input(
            dh: 15, alphaTopDeg: 50, alphaBaseDeg: -3, tracking: false))
        XCTAssertEqual(r.confidence, .red)
        XCTAssertTrue(r.rejectionReason?.contains("tracking") ?? false,
                      "reason = \(r.rejectionReason ?? "nil")")
    }

    func testSigmaHMonotonicInDh() {
        // For fixed angles, σ_H should grow with d_h. Walk 5→10→15→20 m.
        let angles: (Float, Float) = (45, -5)
        var prev: Float = 0
        for dh: Float in [5, 10, 15, 20] {
            let r = HeightEstimator.estimate(input: input(
                dh: dh, alphaTopDeg: angles.0, alphaBaseDeg: angles.1))
            XCTAssertGreaterThan(
                r.sigmaHm, prev,
                "σ_H should strictly increase with d_h; at d_h=\(dh) σ_H=\(r.sigmaHm) prev=\(prev)")
            prev = r.sigmaHm
        }
    }

    // MARK: - Tier transitions

    func testGreenTierOnCanonicalInput() {
        // Short d_h, moderate angles, low σ_H: all four warn checks pass.
        let r = HeightEstimator.estimate(input: input(
            dh: 12, alphaTopDeg: 45, alphaBaseDeg: -5))
        XCTAssertEqual(r.confidence, .green,
                       "σ_H = \(r.sigmaHm), H = \(r.heightM)")
    }

    func testYellowOnSingleWarn_dhOver25() {
        // d_h = 27 → only the "d_h > 25" warn trips; σ_H/H stays ≤ 5%.
        // Low angles keep σ_H in the green band.
        let r = HeightEstimator.estimate(input: input(
            dh: 27, alphaTopDeg: 40, alphaBaseDeg: -2))
        XCTAssertEqual(r.confidence, .yellow,
                       "σ_H/H = \(r.sigmaHm / r.heightM), H = \(r.heightM)")
    }

    func testHeightOutOfRangeReturnsRed() {
        // Pick angles that yield H well below 1.5 m.
        let r = HeightEstimator.estimate(input: input(
            dh: 10, alphaTopDeg: 2, alphaBaseDeg: -2))
        XCTAssertEqual(r.confidence, .red)
        XCTAssertTrue(r.rejectionReason?.contains("out of range") ?? false,
                      "reason = \(r.rejectionReason ?? "nil")")
    }

    func testFormulaMatchesManualCalculation() {
        // H = d_h · (tan α_top − tan α_base). Pick round values.
        let dh: Float = 20
        let atDeg: Float = 30
        let abDeg: Float = -10
        let expectedH = dh * (tanf(atDeg * .pi / 180) - tanf(abDeg * .pi / 180))
        let r = HeightEstimator.estimate(input: input(
            dh: dh, alphaTopDeg: atDeg, alphaBaseDeg: abDeg))
        XCTAssertEqual(r.heightM, expectedH, accuracy: 1e-4)
    }
}
