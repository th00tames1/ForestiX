// AR-caliper DBH — 1:1 port of iOS Sensors/ARCaliperDBH.swift. Diameter
// from two trunk-edge ray directions × an AR-estimated distance, for the
// NON-depth path (and the within-device depth-vs-AR comparison study). No
// depth map required, so it runs on every ARCore device.
//
// Geometry: the cruiser taps the left then right trunk edge at breast
// height. Each tap gives a world-space camera-ray direction; their
// subtended angle α brackets the stem. Treating the two rays as tangents
// to a circular stem whose front surface is at raycast distance d:
//
//     sin(α/2) = R / (d + R)   ⇒   D = 2·d·sin(α/2) / (1 − sin(α/2))
//
// which reduces to D ≈ d·α for small α. The distance d comes from an
// ARCore plane/depth hit-test (the analogue of the iOS estimated-plane
// raycast), which is the dominant error term — a 1–2 px tap contributes a
// comparatively tiny angular error.

package com.hcjeong.forestix.sensors

import com.hcjeong.forestix.ar.Vec3
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

object ArCaliperDbh {

    /// Provisional relative uncertainty of the AR-estimated distance — the
    /// dominant term for this path. PLACEHOLDER to be calibrated by the
    /// depth-vs-AR field study; surfaced as σ + a confidence tier so the
    /// cruiser (and the data) see that AR is the less certain path. 0.05
    /// lands a nominal measurement at the yellow tier.
    const val distanceRelSigma: Float = 0.05f

    /// `leftDir` / `rightDir` are world-space (or camera-space — only the
    /// angle between them matters) ray directions through the two edge taps.
    /// `distanceM` is the AR-estimated distance to the trunk front.
    fun estimate(leftDir: Vec3, rightDir: Vec3, distanceM: Float): DBHResult? {
        if (!distanceM.isFinite() || distanceM <= 0.2f || distanceM >= 12f) return null
        val l = normalize(leftDir) ?: return null
        val r = normalize(rightDir) ?: return null
        if (!l.x.isFinite() || !r.x.isFinite()) return null

        val cosA = max(-1.0, min(1.0, l.dot(r).toDouble()))
        val alpha = acos(cosA)                         // subtended angle (rad)
        val halfSin = sin(alpha / 2.0)
        if (alpha <= 1e-4 || halfSin >= 0.95) return null

        val d = distanceM.toDouble()
        val diameterCm = (2.0 * d * halfSin / (1.0 - halfSin) * 100.0).toFloat()
        if (diameterCm <= 1f || diameterCm >= 400f) return null

        // σ_R combines the two independent error sources instead of pinning the
        // tier to a constant. Distance dominates (AR plane/depth hit), but the
        // two edge taps carry a small angular uncertainty σ_α that is amplified
        // when the trunk subtends a small angle — so a thin or distant stem
        // correctly degrades the tier rather than always reading yellow.
        //   D = 2·d·s/(1−s), s = sin(α/2)  ⇒  (dD/dα)/D = cos(α/2)/(2·s·(1−s))
        val angleSensRel = cos(alpha / 2.0) / (2.0 * halfSin * (1.0 - halfSin))   // per rad
        // Per-tap angular precision. A screen tap lands within a few pixels;
        // at a typical fx≈1400 px that is ≈0.15°, so σ_α = √2·0.15° for the
        // two-tap difference. (An earlier 0.5°/tap over-penalised every
        // measurement into the red tier.)
        val sigmaAlpha = sqrt(2.0) * (0.15 * PI / 180.0)                         // √2·0.15°/tap
        val relFromAngle = angleSensRel * sigmaAlpha
        val relFromDist = distanceRelSigma.toDouble()
        val relSigmaR = sqrt(relFromDist * relFromDist + relFromAngle * relFromAngle)

        val radiusMm = diameterCm * 10f / 2f
        val sigmaRmm = (radiusMm.toDouble() * relSigmaR).toFloat()
        val ratio = relSigmaR                          // σ_R / R
        // A well-framed trunk (angle term small) sits at the distance floor
        // ≈0.05 → yellow; thin/distant stems (angle term dominant) → red.
        val tier: ConfidenceTier = when {
            ratio <= 0.04 -> ConfidenceTier.GREEN
            ratio <= 0.09 -> ConfidenceTier.YELLOW
            else -> ConfidenceTier.RED
        }

        return DBHResult(
            diameterCm = diameterCm,
            centerX = 0f,
            centerZ = 0f,
            arcCoverageDeg = 0f,
            rmseMm = 0f,
            sigmaRmm = sigmaRmm,
            nInliers = 2,
            confidence = tier,
            method = DBHMethod.AR_CALIPER,
            rejectionReason = if (tier == ConfidenceTier.RED) "AR-caliper: distance uncertain" else null,
        )
    }

    private fun normalize(v: Vec3): Vec3? {
        val len = v.length()
        if (!len.isFinite() || len < 1e-9f) return null
        val inv = 1f / len
        return Vec3(v.x * inv, v.y * inv, v.z * inv)
    }
}
