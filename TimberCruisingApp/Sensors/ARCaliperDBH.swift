// AR-caliper DBH — diameter from two trunk-edge ray directions × an
// AR-estimated distance, for the NON-LiDAR path (and the within-device
// LiDAR-vs-AR comparison study). No depth map required, so it runs on
// every iPhone, not just LiDAR Pro models.
//
// Geometry: the cruiser taps the left then right trunk edge at breast
// height. Each tap gives a world-space camera-ray direction; their
// subtended angle α brackets the stem. Treating the two rays as tangents
// to a circular stem whose front surface is at raycast distance d:
//
//     sin(α/2) = R / (d + R)   ⇒   D = 2·d·sin(α/2) / (1 − sin(α/2))
//
// which reduces to D ≈ d·α for small α. The distance d comes from an
// ARKit estimated-plane raycast (no LiDAR), which is the dominant error
// term — a 1–2 px tap contributes a comparatively tiny angular error.

import Foundation
import simd
import Common
import Models

public enum ARCaliperDBH {

    /// Provisional relative uncertainty of the AR-estimated distance — the
    /// dominant term for this path. This is a PLACEHOLDER to be calibrated
    /// by the LiDAR-vs-AR field study; it is surfaced as σ + a confidence
    /// tier so the cruiser (and the data) see that AR is the less certain
    /// path. 0.05 lands a nominal measurement at the yellow tier.
    public static let distanceRelSigma: Float = 0.05

    /// `leftDir` / `rightDir` are world-space (or camera-space — only the
    /// angle between them matters) ray directions through the two edge
    /// taps. `distanceM` is the AR-estimated distance to the trunk front.
    public static func estimate(leftDir: SIMD3<Float>,
                                rightDir: SIMD3<Float>,
                                distanceM: Float) -> DBHResult? {
        guard distanceM.isFinite, distanceM > 0.2, distanceM < 12 else { return nil }
        let l = simd_normalize(leftDir)
        let r = simd_normalize(rightDir)
        guard l.x.isFinite, r.x.isFinite else { return nil }

        let cosA = max(-1, min(1, simd_dot(l, r)))
        let alpha = acos(cosA)                       // subtended angle (rad)
        let halfSin = Double(sin(alpha / 2))
        guard alpha > 1e-4, halfSin < 0.95 else { return nil }

        let d = Double(distanceM)
        let diameterCm = Float(2.0 * d * halfSin / (1.0 - halfSin) * 100.0)
        guard diameterCm > 1, diameterCm < 400 else { return nil }

        // σ_R combines the two independent error sources instead of pinning the
        // tier to a constant. Distance is the dominant term (AR plane depth),
        // but the two edge taps carry a small angular uncertainty σ_α that is
        // amplified when the trunk subtends a small angle — so a thin or distant
        // stem correctly degrades the tier rather than always reading yellow.
        //   D = 2·d·s/(1−s), s = sin(α/2)  ⇒  (dD/dα)/D = cos(α/2)/(2·s·(1−s))
        let a = Double(alpha)
        let angleSensRel = cos(a / 2.0) / (2.0 * halfSin * (1.0 - halfSin))    // per rad
        // Per-tap angular precision. A screen tap lands within a few pixels;
        // at a typical fx≈1400 px that is ≈0.15°, so σ_α = √2·0.15° for the
        // two-tap difference. (An earlier 0.5°/tap over-penalised every
        // measurement into the red tier.)
        let sigmaAlpha = 2.0.squareRoot() * (0.15 * Double.pi / 180.0)         // √2·0.15°/tap
        let relFromAngle = angleSensRel * sigmaAlpha
        let relFromDist = Double(distanceRelSigma)
        let relSigmaR = (relFromDist * relFromDist + relFromAngle * relFromAngle).squareRoot()

        let radiusMm = diameterCm * 10 / 2
        let sigmaRmm = Float(Double(radiusMm) * relSigmaR)
        let ratio = relSigmaR                        // σ_R / R
        // A well-framed trunk (angle term small) sits at the distance floor
        // ≈0.05 → yellow; thin/distant stems (angle term dominant) → red.
        let tier: ConfidenceTier = ratio <= 0.04 ? .green
                                 : ratio <= 0.09 ? .yellow
                                 : .red

        return DBHResult(
            diameterCm: diameterCm,
            centerXZ: SIMD2<Float>(0, 0),
            arcCoverageDeg: 0,
            rmseMm: 0,
            sigmaRmm: sigmaRmm,
            nInliers: 2,
            confidence: tier,
            method: .arCaliper,
            rawPointsPath: nil,
            rejectionReason: tier == .red ? "AR-caliper: distance uncertain" : nil)
    }
}
