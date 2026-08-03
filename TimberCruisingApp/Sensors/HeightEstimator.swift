// Spec §7.2 VIO walk-off tangent height estimation. Pure function —
// accepts a fully-formed input struct (anchor pose, standing pose,
// α_top, α_base, tracking-was-normal flag) and returns a HeightResult.
// All §7.2 failure modes are enforced here so the view model only has to
// shuttle data.
//
// The formula `H = d_h · (tan α_top − tan α_base)` assumes both angles
// are measured from the same standing position — phone eye-level `y_B`
// cancels algebraically, which is why the method is robust on slopes.
// See spec §7.2 "Why the formula works" for the derivation.

import Foundation
import simd
import Common
import Models

// MARK: - Input

public struct HeightMeasureInput: Sendable {
    public let anchorPointWorld: SIMD3<Float>
    public let standingPointWorld: SIMD3<Float>
    public let alphaTopRad: Float
    public let alphaBaseRad: Float
    public let trackingStateWasNormalThroughout: Bool
    public let projectCalibration: ProjectCalibration
    /// The units the cruiser reads. Nothing computed here depends on it —
    /// the maths and every stored value stay metric. It is carried so the
    /// σ check's sentence can quote its ± band in the same unit as the
    /// height printed next to it; an imperial cruiser was being handed a
    /// band in metres. Defaults to metric so the replay harness and the
    /// unit tests are unaffected.
    public let unitSystem: UnitSystem

    public init(
        anchorPointWorld: SIMD3<Float>,
        standingPointWorld: SIMD3<Float>,
        alphaTopRad: Float,
        alphaBaseRad: Float,
        trackingStateWasNormalThroughout: Bool,
        projectCalibration: ProjectCalibration,
        unitSystem: UnitSystem = .metric
    ) {
        self.anchorPointWorld = anchorPointWorld
        self.standingPointWorld = standingPointWorld
        self.alphaTopRad = alphaTopRad
        self.alphaBaseRad = alphaBaseRad
        self.trackingStateWasNormalThroughout = trackingStateWasNormalThroughout
        self.projectCalibration = projectCalibration
        self.unitSystem = unitSystem
    }
}

// MARK: - Estimator

public enum HeightEstimator {

    /// Spec §7.2 constants.

    /// GEOMETRIC SANITY FLOOR — deliberately tiny, and NOT a quality
    /// proxy. Below this the standing point and the anchor are
    /// effectively the same point: there is no baseline for the tangent
    /// triangle and d_h is pure pose noise.
    ///
    /// This used to be 3.0 m, a crude stand-in for "the aim angles will
    /// be bad from this close" — a thing σ_H and the α_top check below
    /// already measure directly, and measure correctly at every tree
    /// size. Distance is the wrong variable:
    ///
    ///   • A 2 m sapling from d_h = 1.5 m gives α_top ≈ 18°,
    ///     α_base ≈ −45°, H = 2.0 m and σ_H/H ≈ 2.2% — comfortably
    ///     inside the 5% green band, yet the old floor refused it
    ///     outright, which made a tree farm unmeasurable.
    ///   • A 20 m tree at exactly 3.0 m has α_top ≈ 81° and is a poor
    ///     measurement, yet it satisfied the old floor.
    ///
    /// So the floor is now only the degenerate case, and the tier comes
    /// from σ_H/H and the angle checks — unchanged, and unweakened.
    public static let minDhMeters: Float = 0.3
    public static let yellowDhMeters: Float = 25.0
    public static let highDriftDhMeters: Float = 30.0
    public static let maxAlphaTopRadRed: Float = 85 * .pi / 180
    public static let maxAlphaTopRadYellow: Float = 75 * .pi / 180
    /// Lower bound on a plausible measured height. 0.3 m (was 1.5 m) so
    /// seedlings and saplings are measurable at all. The inversion guard
    /// and `maxHMeters` are untouched: a negative or inverted result is
    /// still refused, and so is anything above 100 m.
    public static let minHMeters: Float = 0.3
    public static let maxHMeters: Float = 100.0
    public static let sigmaAlphaRad: Float = 0.3 * .pi / 180
    public static let sigmaRatioYellow: Float = 0.05

    /// §7.2 pipeline. Always returns a HeightResult — red-tier results
    /// carry a non-nil `rejectionReason`.
    public static func estimate(input: HeightMeasureInput) -> HeightResult {

        // Step 1. Horizontal distance — drop Y to project onto the
        // gravity-aligned ground plane.
        let dx = input.standingPointWorld.x - input.anchorPointWorld.x
        let dz = input.standingPointWorld.z - input.anchorPointWorld.z
        let dh = sqrt(dx * dx + dz * dz)

        // Step 2. Guard rails — each either short-circuits to red or
        // fabricates the corresponding §7.9 warn check.
        if !input.trackingStateWasNormalThroughout {
            return red(input: input,
                       dh: dh,
                       reason: "The camera lost its place while you walked back — measure this tree again, moving more slowly.")
        }
        // Degenerate geometry only — see `minDhMeters`. Everything the
        // old 3 m floor was standing in for is measured by σ_H and the
        // α_top check in Step 5, which are correct at every tree size.
        if dh < minDhMeters {
            return red(input: input,
                       dh: dh,
                       reason: "Too close; step back "
                               + "(walked back \(String(format: "%.1f", dh)) m so far)")
        }
        if abs(input.alphaTopRad) > maxAlphaTopRadRed {
            return red(input: input,
                       dh: dh,
                       reason: "Top angle too steep; step back")
        }
        if abs(input.alphaBaseRad) > maxAlphaTopRadRed {
            return red(input: input,
                       dh: dh,
                       reason: "Base angle too steep; step back")
        }

        // Step 3. Height — the two-tangent formula.
        let tanTop  = tan(input.alphaTopRad)
        let tanBase = tan(input.alphaBaseRad)

        // Phase 15.1: explicit inversion guard. The H-range check below
        // mathematically rejects negative or near-zero heights too, but
        // its generic "Computed height -2.3 m out of range" message
        // hides the actual cause (the cruiser captured the top aim at
        // or below the base aim — geometrically impossible). Mirrors
        // DBH 14.1's chord/diameter deflation guard: silent wrong
        // answers are the worst kind of trust failure, so surface a
        // specific actionable reason for this case.
        if tanTop <= tanBase {
            return red(input: input,
                       dh: dh,
                       reason: "Top aim was at or below the base — re-capture the top higher")
        }

        let H = dh * (tanTop - tanBase)

        if !(H >= minHMeters && H <= maxHMeters) {
            return red(input: input,
                       dh: dh,
                       reason: "Computed height \(String(format: "%.1f", H)) m out of range")
        }

        // Step 4. σ_H propagation — three variance terms per §7.2.
        // Same helper the red paths use, so there is exactly one σ_H in
        // the app and a red fit's σ is computed the same way a green
        // one's is. It cannot be nil here (d_h, both angles and the
        // aim ordering were all checked above), but the tier must never
        // be derived from a fabricated number, so the impossible case
        // returns red with σ unset rather than σ = 0.
        guard let sigmaH = propagatedSigma(dh: dh,
                                           alphaTopRad: input.alphaTopRad,
                                           alphaBaseRad: input.alphaBaseRad,
                                           calibration: input.projectCalibration)
        else {
            return red(input: input,
                       dh: dh,
                       reason: "Couldn't work out how accurate this height is — retake the base and top aims.")
        }

        // Step 5. Tier from the §7.9 check matrix.
        // `d_h > 30 m` is explicitly yellow per §7.2 failure table, so we
        // fold it into the warn set rather than rejecting.
        // EVERY reason below reaches the cruiser. Unlike DBH — where the
        // banner shows `firstFailingRejectReason` and the warn reasons
        // never leave the struct — a height result is graded entirely by
        // warn checks, and `failedCheckReasons` joins all of them into
        // `rejectionReason` for the result panel and the banner. So each
        // one is a sentence: what happened, then what to do about it.
        // Every THRESHOLD here is unchanged.
        let checks: [Check] = [
            // The ± band is the propagated σ_H as a length — an amount a
            // cruiser can act on, unlike the σ/H ratio the check itself
            // is written in. `UncertaintyBand` renders it in the cruiser's
            // own units (the band used to be metres inside a sentence shown
            // beside a height in feet) and never as a zero band. The CHECK
            // is unchanged; only the sentence that reports it is.
            check(sigmaH / H <= sigmaRatioYellow,
                  sev: .warn,
                  reason: "This height could be off by about "
                          + UncertaintyBand.text(metres: Double(sigmaH),
                                                 in: input.unitSystem)
                          + " — walk further back and retake for a tighter reading."),
            // The walk-back distances are quoted through `GuidanceDistance`
            // for the same reason the band above is: these sentences tell the
            // cruiser where to stand, and one worded in a unit they do not
            // pace in is not an instruction. The THRESHOLDS are the metric
            // ones they have always been and are read from the same constants
            // the checks apply, so the wording cannot drift from the gate.
            check(dh <= yellowDhMeters,
                  sev: .warn,
                  reason: "You walked back more than "
                          + GuidanceDistance.text(metres: Double(yellowDhMeters),
                                                  in: input.unitSystem)
                          + " — that far out, a small slip in aim "
                          + "turns into a big height error. Move closer and retake if you can."),
            check(abs(input.alphaTopRad) <= maxAlphaTopRadYellow,
                  sev: .warn,
                  reason: "You aimed more than 75° up at the top — you are too close to the "
                          + "tree. Walk back until you can see the top comfortably, then retake."),
            check(dh <= highDriftDhMeters,
                  sev: .warn,
                  reason: "You walked back more than "
                          + GuidanceDistance.text(metres: Double(highDriftDhMeters),
                                                  in: input.unitSystem)
                          + " — the distance may have crept. Move closer and retake if you can.")
        ]
        let tier = combineChecks(checks)

        // A red tier HERE is the σ / angle / walk-back verdict — the
        // criteria that actually measure quality, now that the flat
        // distance floor is gone. Name the failing checks so the result
        // panel can show WHY inline: the fit is still acceptable, and a
        // cruiser must be able to read the reason next to the number
        // before committing it. Mirrors DBHEstimator's red-only
        // `rejectionReason` line; yellow/green stay nil as before.
        let reason: String? = tier == .red
            ? (failedCheckReasons(checks)
               ?? "This reading didn't pass the accuracy checks — retake it, or accept it and flag the tree.")
            : nil

        return HeightResult(
            heightM: H,
            dHm: dh,
            alphaTopRad: input.alphaTopRad,
            alphaBaseRad: input.alphaBaseRad,
            sigmaHm: sigmaH,
            confidence: tier,
            method: .vioWalkoffTangent,
            rejectionReason: reason
        )
    }

    // MARK: - σ_H propagation

    /// §7.2 σ_H from the three variance terms:
    ///
    ///   σ_d   = vioDriftFraction · d_h
    ///   σ_α   = 0.3° constant (IMU pitch noise)
    ///   σ_H²  = (tan α_top − tan α_base)² · σ_d²
    ///         + d_h² · sec⁴(α_top) · σ_α²
    ///         + d_h² · sec⁴(α_base) · σ_α²
    ///
    /// EVERY path that produces a height uses this — green, yellow and
    /// red alike. The red paths used to short-circuit to σ = 0, which
    /// was harmless only while a red fit could never be committed; now
    /// that it can, a zero there would persist the WORST readings into
    /// `Tree.heightSigmaM`, the quick-measure history, the research CSV
    /// and the raw-capture manifest claiming perfect precision, and
    /// silently corrupt the σ-propagation spine the accuracy study rests
    /// on. The formula is well defined for any finite d_h and angles —
    /// a steep aim simply produces a large σ, which is the true answer
    /// and exactly what the analysis needs to see.
    ///
    /// Returns nil only where σ is genuinely meaningless, i.e. where the
    /// result is not a measurement:
    ///   • a non-finite pose or angle (nothing to propagate),
    ///   • d_h below `minDhMeters` — there is no baseline, so σ_d =
    ///     f · d_h is not an uncertainty on anything,
    ///   • an inverted aim pair, where H itself is unphysical.
    /// `canAccept` refuses all three, so a nil σ cannot reach storage.
    static func propagatedSigma(dh: Float,
                                alphaTopRad: Float,
                                alphaBaseRad: Float,
                                calibration: ProjectCalibration) -> Float? {
        guard dh.isFinite, alphaTopRad.isFinite, alphaBaseRad.isFinite,
              dh >= minDhMeters else { return nil }
        let tanTop = tan(alphaTopRad)
        let tanBase = tan(alphaBaseRad)
        guard tanTop.isFinite, tanBase.isFinite, tanTop > tanBase else { return nil }

        let sigmaD = calibration.vioDriftFraction * dh
        let tanDiff = tanTop - tanBase
        let secTop  = 1.0 / cos(alphaTopRad)
        let secBase = 1.0 / cos(alphaBaseRad)
        let term1 = tanDiff * tanDiff * sigmaD * sigmaD
        let term2 = dh * dh * pow(secTop,  4) * sigmaAlphaRad * sigmaAlphaRad
        let term3 = dh * dh * pow(secBase, 4) * sigmaAlphaRad * sigmaAlphaRad
        let sigmaH = sqrt(term1 + term2 + term3)
        return sigmaH.isFinite ? sigmaH : nil
    }

    /// Every failing check's reason, joined — what made the tier red.
    private static func failedCheckReasons(_ checks: [Check]) -> String? {
        let failed = checks
            .filter { !$0.passed && !$0.reason.isEmpty }
            .map(\.reason)
        return failed.isEmpty ? nil : failed.joined(separator: " · ")
    }

    // MARK: - Acceptability

    /// Whether a result is a real fit the cruiser may commit.
    ///
    /// THE TIER IS NOT PART OF THIS. A red fit is still a fit: the
    /// cruiser sees the reason inline and decides, and the tier travels
    /// with the reading (Tree.heightConfidence, research CSV, raw-capture
    /// manifest) so the accuracy analysis can filter on it. Refused here
    /// is only a result that is not a measurement at all — an inverted
    /// pair of aims, a height outside the plausible window, degenerate
    /// standing-point/anchor geometry, or (equivalently) a σ_H the
    /// propagation could not derive. Those carry no physical meaning,
    /// so no amount of cruiser judgement makes them safe to store.
    ///
    /// Non-tangent methods (manual entry, tape, imputed H–D) are not this
    /// function's business and always pass.
    public static func canAccept(_ result: HeightResult) -> Bool {
        guard result.method == .vioWalkoffTangent else { return true }
        // σ must be a REAL propagated value. A tangent reading whose σ is
        // unset is one the geometry could not put an uncertainty on, and
        // committing it would write a height into `Tree.heightSigmaM`,
        // the quick-measure history and the research CSV with no
        // uncertainty attached at all. Belt-and-braces against a fake
        // zero ever being reintroduced upstream: a σ that is not finite
        // and positive is refused here too, so nothing claiming perfect
        // precision can reach storage.
        guard let sigma = result.sigmaHm, sigma.isFinite, sigma > 0 else { return false }
        guard result.heightM.isFinite, result.dHm.isFinite else { return false }
        guard result.heightM >= minHMeters,
              result.heightM <= maxHMeters else { return false }
        guard result.dHm >= minDhMeters else { return false }
        // Inversion guard, re-derived from the stored aims: a top aim at
        // or below the base is geometrically impossible.
        guard tan(result.alphaTopRad) > tan(result.alphaBaseRad) else { return false }
        return true
    }

    // MARK: - Red helper

    private static func red(input: HeightMeasureInput,
                            dh: Float,
                            reason: String) -> HeightResult {
        // Best-effort H so the result panel still shows a number on red.
        // Phase 13 originally set this to 0 (rendered as "—") to hide
        // the wildly-inflated values produced by extreme angles or a
        // standing-point fallback to the world origin. With those root
        // causes now blocked (base-angle red guard + captureTopNow nil
        // guard), the formula stays in a sensible range even on red,
        // and showing the number gives the cruiser useful context next
        // to the rejection reason ("0.8 m — too close, step back" is
        // more informative than "— too close, step back").
        let H = dh * (tan(input.alphaTopRad) - tan(input.alphaBaseRad))
        return HeightResult(
            heightM: H,
            dHm: dh,
            alphaTopRad: input.alphaTopRad,
            alphaBaseRad: input.alphaBaseRad,
            // The REAL σ for this geometry, not a zero. Tracking-lost and
            // steep-aim reds are ordinary tangent measurements that the
            // cruiser may commit, so they must carry a σ the analysis can
            // propagate; `propagatedSigma` returns nil only for the reds
            // that are not measurements, and `canAccept` refuses those.
            sigmaHm: propagatedSigma(dh: dh,
                                     alphaTopRad: input.alphaTopRad,
                                     alphaBaseRad: input.alphaBaseRad,
                                     calibration: input.projectCalibration),
            confidence: .red,
            method: .vioWalkoffTangent,
            rejectionReason: reason
        )
    }
}
