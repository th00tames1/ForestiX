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
    func testSpec7_2KnownHeight() throws {
        let r = HeightEstimator.estimate(input: input(
            dh: 25, alphaTopDeg: 56.9, alphaBaseDeg: -3.66))

        XCTAssertEqual(r.heightM, 40.0, accuracy: 0.1)
        XCTAssertEqual(try XCTUnwrap(r.sigmaHm), 0.9, accuracy: 0.1)
        XCTAssertEqual(r.method, .vioWalkoffTangent)
        XCTAssertNil(r.rejectionReason)
    }

    /// d_h = 2 m was refused outright by the old flat 3 m floor. It is a
    /// perfectly good sapling measurement — H = 3.64 m at σ_H ≈ 0.085 m,
    /// i.e. 2.3%, comfortably inside the 5% green band — so the tier is
    /// GREEN, the σ is a real propagated number, and it is committable.
    /// Distance is not a quality proxy; σ_H and the aim angles are.
    func testCloseInSaplingIsGreenWithRealSigma() throws {
        let r = HeightEstimator.estimate(input: input(
            dh: 2, alphaTopDeg: 60, alphaBaseDeg: -5))
        XCTAssertEqual(r.confidence, .green,
                       "reason = \(r.rejectionReason ?? "nil")")
        XCTAssertNil(r.rejectionReason)
        XCTAssertEqual(r.heightM, 3.639, accuracy: 0.01)

        let sigma = try XCTUnwrap(r.sigmaHm, "a real fit must carry a real σ_H")
        XCTAssertEqual(sigma, 0.085, accuracy: 0.01)
        XCTAssertLessThan(sigma / r.heightM, HeightEstimator.sigmaRatioYellow)
        XCTAssertTrue(HeightEstimator.canAccept(r))
    }

    /// The ONLY distance refusal left. Below `minDhMeters` the standing
    /// point and the anchor are the same point: there is no baseline, so
    /// σ_d = f · d_h is not an uncertainty on anything and σ_H is not
    /// derivable. That makes the result a non-measurement — σ unset, and
    /// `canAccept` refuses it so it can never reach storage.
    func testDegenerateBaselineIsRedWithUnsetSigmaAndUnacceptable() {
        let r = HeightEstimator.estimate(input: input(
            dh: 0.1, alphaTopDeg: 60, alphaBaseDeg: -5))
        XCTAssertEqual(r.confidence, .red)
        XCTAssertTrue(r.rejectionReason?.contains("Too close") ?? false,
                      "reason = \(r.rejectionReason ?? "nil")")
        XCTAssertNil(r.sigmaHm)
        XCTAssertFalse(HeightEstimator.canAccept(r))
    }

    /// S1 REGRESSION GUARD — the whole point of the σ fix.
    ///
    /// A red fit that is still a MEASUREMENT is committable, so it must
    /// carry the real propagated σ_H. The red short-circuit used to return
    /// σ = 0, which was harmless only while red could never be accepted;
    /// once it could, the worst readings were persisted claiming ZERO
    /// uncertainty into Tree.heightSigmaM, the quick-measure history, the
    /// research CSV and the raw-capture manifest.
    func testAcceptableRedCarriesRealSigma() throws {
        // α_top = 88° trips the 85° red guard. The geometry is otherwise
        // sound: d_h = 1 m, aims correctly ordered, H = 29.5 m in range.
        let r = HeightEstimator.estimate(input: input(
            dh: 1, alphaTopDeg: 88, alphaBaseDeg: -40))
        XCTAssertEqual(r.confidence, .red)
        XCTAssertTrue(r.rejectionReason?.contains("steep") ?? false,
                      "reason = \(r.rejectionReason ?? "nil")")

        let sigma = try XCTUnwrap(r.sigmaHm, "an acceptable red must carry a real σ_H")
        XCTAssertTrue(sigma.isFinite)
        XCTAssertGreaterThan(sigma, 0, "σ = 0 would claim this poor fit is exact")
        // The steep sec⁴ term dominates: ±4.3 m on a 29.5 m height.
        XCTAssertEqual(sigma, 4.34, accuracy: 0.05)
        XCTAssertTrue(HeightEstimator.canAccept(r),
                      "a red fit that is still a measurement stays committable")
    }

    /// The σ rule is symmetric: a tangent result whose σ is unset is not a
    /// measurement, and `canAccept` must refuse it whatever else looks
    /// plausible — otherwise a fabricated zero could be reintroduced
    /// upstream and walk straight into storage.
    func testCanAcceptRefusesTangentResultWithUnsetSigma() {
        let r = HeightResult(
            heightM: 20,
            dHm: 15,
            alphaTopRad: 50 * .pi / 180,
            alphaBaseRad: -3 * .pi / 180,
            sigmaHm: nil,
            confidence: .yellow,
            method: .vioWalkoffTangent,
            rejectionReason: nil)
        XCTAssertFalse(HeightEstimator.canAccept(r))
    }

    /// A typed height has no propagated σ at all, and is not this
    /// function's business — non-tangent methods always pass.
    func testCanAcceptAllowsManualEntryWithoutSigma() {
        let r = HeightResult(
            heightM: 0.8,
            dHm: 0,
            alphaTopRad: 0,
            alphaBaseRad: 0,
            sigmaHm: nil,
            confidence: .yellow,
            method: .manualEntry,
            rejectionReason: nil)
        XCTAssertTrue(HeightEstimator.canAccept(r))
    }

    func testTooSteepAlphaTopReturnsRed() {
        let r = HeightEstimator.estimate(input: input(
            dh: 15, alphaTopDeg: 88, alphaBaseDeg: -5))
        XCTAssertEqual(r.confidence, .red)
        XCTAssertTrue(r.rejectionReason?.contains("steep") ?? false,
                      "reason = \(r.rejectionReason ?? "nil")")
    }

    /// Phase 15.1: when `α_top ≤ α_base` the formula gives a non-positive
    /// (or zero) height — geometrically impossible. The H-range check
    /// also catches it, but the inversion guard fires first with a
    /// cruiser-actionable reason instead of "out of range". H itself is
    /// unphysical here, so σ_H is meaningless and stays unset.
    func testInvertedAlphaPairReturnsRed() {
        // α_top = 30°, α_base = 35° → tanTop − tanBase < 0
        let r = HeightEstimator.estimate(input: input(
            dh: 15, alphaTopDeg: 30, alphaBaseDeg: 35))
        XCTAssertEqual(r.confidence, .red)
        XCTAssertNotNil(r.rejectionReason)
        XCTAssertTrue(r.rejectionReason!.contains("Top aim"),
                      "reason should call out the inversion specifically — got \(r.rejectionReason!)")
        XCTAssertNil(r.sigmaHm)
        XCTAssertFalse(HeightEstimator.canAccept(r))
    }

    /// Tracking loss says nothing about the geometry — d_h and both aims
    /// are real numbers — so this red carries a real σ and stays
    /// committable, with the tier travelling alongside it.
    func testTrackingLostAtAnyPointReturnsRed() throws {
        // Otherwise-green geometry; only the tracking flag trips the red.
        let r = HeightEstimator.estimate(input: input(
            dh: 15, alphaTopDeg: 50, alphaBaseDeg: -3, tracking: false))
        XCTAssertEqual(r.confidence, .red)
        // Not the word "tracking" — that was developer language, and commit
        // 4cb8e6f rewrote it into something a cruiser can act on ("The camera
        // lost its place while you walked back — measure this tree again,
        // moving more slowly") without touching the predicate that produces
        // it. What matters here is that a reason is GIVEN for a red that the
        // geometry alone does not explain, and that it names the recovery.
        let reason = try XCTUnwrap(r.rejectionReason)
        XCTAssertTrue(reason.lowercased().contains("again"),
                      "a tracking red must tell the cruiser what to do — got \(reason)")
        XCTAssertGreaterThan(try XCTUnwrap(r.sigmaHm), 0)
        XCTAssertTrue(HeightEstimator.canAccept(r))
    }

    func testSigmaHMonotonicInDh() throws {
        // For fixed angles, σ_H should grow with d_h. Walk 5→10→15→20 m.
        let angles: (Float, Float) = (45, -5)
        var prev: Float = 0
        for dh: Float in [5, 10, 15, 20] {
            let r = HeightEstimator.estimate(input: input(
                dh: dh, alphaTopDeg: angles.0, alphaBaseDeg: angles.1))
            let sigma = try XCTUnwrap(r.sigmaHm)
            XCTAssertGreaterThan(
                sigma, prev,
                "σ_H should strictly increase with d_h; at d_h=\(dh) σ_H=\(sigma) prev=\(prev)")
            prev = sigma
        }
    }

    // MARK: - Tier transitions

    func testGreenTierOnCanonicalInput() throws {
        // Short d_h, moderate angles, low σ_H: all four warn checks pass.
        let r = HeightEstimator.estimate(input: input(
            dh: 12, alphaTopDeg: 45, alphaBaseDeg: -5))
        let sigma = try XCTUnwrap(r.sigmaHm)
        XCTAssertEqual(r.confidence, .green,
                       "σ_H = \(sigma), H = \(r.heightM)")
    }

    func testYellowOnSingleWarn_dhOver25() throws {
        // d_h = 27 → only the "d_h > 25" warn trips; σ_H/H stays ≤ 5%.
        // Low angles keep σ_H in the green band.
        let r = HeightEstimator.estimate(input: input(
            dh: 27, alphaTopDeg: 40, alphaBaseDeg: -2))
        let sigma = try XCTUnwrap(r.sigmaHm)
        XCTAssertEqual(r.confidence, .yellow,
                       "σ_H/H = \(sigma / r.heightM), H = \(r.heightM)")
    }

    // MARK: - The 0.3 m plausible-height bound

    /// The plausible-height window now opens at `minHMeters` = 0.3 m (it
    /// was 1.5 m), so seedlings are measurable at all. This input sits
    /// UNDER the new bound — d_h = 10 m with ±0.5° aims gives H = 0.17 m —
    /// and is still refused as out of range.
    ///
    /// Note the σ: the geometry here is fine (real baseline, correctly
    /// ordered aims), so σ_H IS derivable and is reported. What makes the
    /// result uncommittable is the implausible height, which `canAccept`
    /// checks independently.
    func testHeightBelowMinReturnsRedOutOfRange() throws {
        let r = HeightEstimator.estimate(input: input(
            dh: 10, alphaTopDeg: 0.5, alphaBaseDeg: -0.5))
        XCTAssertLessThan(r.heightM, HeightEstimator.minHMeters)
        XCTAssertEqual(r.confidence, .red)
        XCTAssertTrue(r.rejectionReason?.contains("out of range") ?? false,
                      "reason = \(r.rejectionReason ?? "nil")")
        XCTAssertGreaterThan(try XCTUnwrap(r.sigmaHm), 0)
        XCTAssertFalse(HeightEstimator.canAccept(r))
    }

    /// The other side of the same bound — ±1.0° over the same 10 m walk
    /// gives H = 0.35 m, just INSIDE 0.3 m. The old 1.5 m floor refused
    /// this outright; it is now a real (if noisy) reading the cruiser may
    /// commit, with the tier carrying the σ verdict.
    func testHeightJustAboveMinIsInRangeAndAcceptable() throws {
        let r = HeightEstimator.estimate(input: input(
            dh: 10, alphaTopDeg: 1.0, alphaBaseDeg: -1.0))
        XCTAssertGreaterThan(r.heightM, HeightEstimator.minHMeters)
        XCTAssertEqual(r.heightM, 0.349, accuracy: 0.01)
        XCTAssertFalse(r.rejectionReason?.contains("out of range") ?? false,
                       "reason = \(r.rejectionReason ?? "nil")")
        XCTAssertGreaterThan(try XCTUnwrap(r.sigmaHm), 0)
        XCTAssertTrue(HeightEstimator.canAccept(r))
    }

    /// The upper bound is untouched: 100 m is still the ceiling.
    func testHeightAboveMaxReturnsRedOutOfRange() {
        let r = HeightEstimator.estimate(input: input(
            dh: 60, alphaTopDeg: 70, alphaBaseDeg: -5))
        XCTAssertGreaterThan(r.heightM, HeightEstimator.maxHMeters)
        XCTAssertEqual(r.confidence, .red)
        XCTAssertTrue(r.rejectionReason?.contains("out of range") ?? false,
                      "reason = \(r.rejectionReason ?? "nil")")
        XCTAssertFalse(HeightEstimator.canAccept(r))
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
