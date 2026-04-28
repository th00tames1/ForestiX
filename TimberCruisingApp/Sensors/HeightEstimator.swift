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

    public init(
        anchorPointWorld: SIMD3<Float>,
        standingPointWorld: SIMD3<Float>,
        alphaTopRad: Float,
        alphaBaseRad: Float,
        trackingStateWasNormalThroughout: Bool,
        projectCalibration: ProjectCalibration
    ) {
        self.anchorPointWorld = anchorPointWorld
        self.standingPointWorld = standingPointWorld
        self.alphaTopRad = alphaTopRad
        self.alphaBaseRad = alphaBaseRad
        self.trackingStateWasNormalThroughout = trackingStateWasNormalThroughout
        self.projectCalibration = projectCalibration
    }
}

// MARK: - Estimator

public enum HeightEstimator {

    /// Spec §7.2 constants.
    public static let minDhMeters: Float = 3.0
    public static let yellowDhMeters: Float = 25.0
    public static let highDriftDhMeters: Float = 30.0
    public static let maxAlphaTopRadRed: Float = 85 * .pi / 180
    public static let maxAlphaTopRadYellow: Float = 75 * .pi / 180
    public static let minHMeters: Float = 1.5
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
                       reason: "AR tracking lost mid-measurement")
        }
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
        let H = dh * (tanTop - tanBase)

        if !(H >= minHMeters && H <= maxHMeters) {
            return red(input: input,
                       dh: dh,
                       reason: "Computed height \(String(format: "%.1f", H)) m out of range")
        }

        // Step 4. σ_H propagation — three variance terms per §7.2.
        //   σ_d   = vioDriftFraction · d_h
        //   σ_α   = 0.3° constant (IMU pitch noise)
        //   σ_H²  = (tanTop − tanBase)² · σ_d²
        //         + d_h² · sec⁴(α_top) · σ_α²
        //         + d_h² · sec⁴(α_base) · σ_α²
        let sigmaD = input.projectCalibration.vioDriftFraction * dh
        let tanDiff = tanTop - tanBase
        let term1 = tanDiff * tanDiff * sigmaD * sigmaD
        let secTop  = 1.0 / cos(input.alphaTopRad)
        let secBase = 1.0 / cos(input.alphaBaseRad)
        let term2 = dh * dh * pow(secTop,  4) * sigmaAlphaRad * sigmaAlphaRad
        let term3 = dh * dh * pow(secBase, 4) * sigmaAlphaRad * sigmaAlphaRad
        let sigmaH = sqrt(term1 + term2 + term3)

        // Step 5. Tier from the §7.9 check matrix.
        // `d_h > 30 m` is explicitly yellow per §7.2 failure table, so we
        // fold it into the warn set rather than rejecting.
        let checks: [Check] = [
            check(sigmaH / H <= sigmaRatioYellow,
                  sev: .warn,
                  reason: "Height precision worse than ±5%"),
            check(dh <= yellowDhMeters,
                  sev: .warn,
                  reason: "Walked back more than 25 m"),
            check(abs(input.alphaTopRad) <= maxAlphaTopRadYellow,
                  sev: .warn,
                  reason: "Top aim angle steeper than 75°"),
            check(dh <= highDriftDhMeters,
                  sev: .warn,
                  reason: "Walked back more than 30 m (tracking drift risk)")
        ]
        let tier = combineChecks(checks)

        return HeightResult(
            heightM: H,
            dHm: dh,
            alphaTopRad: input.alphaTopRad,
            alphaBaseRad: input.alphaBaseRad,
            sigmaHm: sigmaH,
            confidence: tier,
            method: .vioWalkoffTangent,
            rejectionReason: nil
        )
    }

    // MARK: - Red helper

    private static func red(input: HeightMeasureInput,
                            dh: Float,
                            reason: String) -> HeightResult {
        // No "best-effort H" on red — when geometry is out of spec
        // (too close, near-vertical aim, etc.) the two-tangent formula
        // produces grossly misleading values (e.g. 100+ m for a desk
        // measured at 1.8 m), and surfacing that number alongside the
        // rejection reason just confuses the cruiser. Callers decide
        // how to render heightM == 0 (typically as "—").
        return HeightResult(
            heightM: 0,
            dHm: dh,
            alphaTopRad: input.alphaTopRad,
            alphaBaseRad: input.alphaBaseRad,
            sigmaHm: 0,
            confidence: .red,
            method: .vioWalkoffTangent,
            rejectionReason: reason
        )
    }
}
