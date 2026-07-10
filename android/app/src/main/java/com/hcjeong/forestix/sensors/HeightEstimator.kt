// VIO walk-off tangent height estimation — 1:1 port of iOS
// Sensors/HeightEstimator.swift (spec §7.2). Same formula, same guard
// rails, same σ_H propagation, same green/yellow/red check matrix, so the
// Android number matches iOS for the same anchor / standing / α inputs.
//
//   H = d_h · (tan α_top − tan α_base)
//
// α_top / α_base are the aim-ray elevation angles (camera-forward pitch)
// captured at the two taps; d_h is the horizontal walk-off distance from
// the trunk anchor to the standing pose.

package com.hcjeong.forestix.sensors

import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tan

enum class HeightMethod(val raw: String) {
    VIO_WALKOFF_TANGENT("vioWalkoffTangent"),
    TAPE_TANGENT("tapeTangent"),
    MANUAL_ENTRY("manualEntry"),
    IMPUTED_HD("imputedHD"),
}

data class HeightResult(
    val heightM: Float,
    val dHm: Float,
    val alphaTopRad: Float,
    val alphaBaseRad: Float,
    val sigmaHm: Float,
    val confidence: ConfidenceTier,
    val method: HeightMethod,
    val rejectionReason: String?,
)

object HeightEstimator {

    // Spec §7.2 constants (verbatim from iOS).
    const val MIN_DH_M = 3.0f
    const val YELLOW_DH_M = 25.0f
    const val HIGH_DRIFT_DH_M = 30.0f
    val MAX_ALPHA_TOP_RED = Math.toRadians(85.0).toFloat()
    val MAX_ALPHA_TOP_YELLOW = Math.toRadians(75.0).toFloat()
    const val MIN_H_M = 1.5f
    const val MAX_H_M = 100.0f
    val SIGMA_ALPHA_RAD = Math.toRadians(0.3).toFloat()
    const val SIGMA_RATIO_YELLOW = 0.05f

    /// Default VIO drift fraction (ProjectCalibration.vioDriftFraction = 0.02 on iOS).
    const val DEFAULT_VIO_DRIFT_FRACTION = 0.02f

    fun estimate(
        anchorX: Float, anchorZ: Float,
        standingX: Float, standingZ: Float,
        alphaTopRad: Float, alphaBaseRad: Float,
        vioDriftFraction: Float = DEFAULT_VIO_DRIFT_FRACTION,
    ): HeightResult {
        // Step 1 — horizontal distance (drop Y onto the ground plane).
        val dx = standingX - anchorX
        val dz = standingZ - anchorZ
        val dh = sqrt(dx * dx + dz * dz)

        // Step 2 — guard rails -> red. (The old "AR tracking lost
        // mid-measurement" hard reject was removed — field fix, both
        // platforms: transient tracking dips flagged good captures; the
        // walk-off drift risk is already covered by the d_h WARN checks.)
        if (dh < MIN_DH_M)
            return red(dh, alphaTopRad, alphaBaseRad,
                "Too close; step back (walked back ${fmt(dh)} m so far)")
        if (abs(alphaTopRad) > MAX_ALPHA_TOP_RED)
            return red(dh, alphaTopRad, alphaBaseRad, "Top angle too steep; step back")
        if (abs(alphaBaseRad) > MAX_ALPHA_TOP_RED)
            return red(dh, alphaTopRad, alphaBaseRad, "Base angle too steep; step back")

        // Step 3 — two-tangent height.
        val tanTop = tan(alphaTopRad)
        val tanBase = tan(alphaBaseRad)
        if (tanTop <= tanBase)
            return red(dh, alphaTopRad, alphaBaseRad,
                "Top aim was at or below the base — re-capture the top higher")

        val h = dh * (tanTop - tanBase)
        if (!(h in MIN_H_M..MAX_H_M))
            return red(dh, alphaTopRad, alphaBaseRad, "Computed height ${fmt(h)} m out of range")

        // Step 4 — σ_H propagation (three variance terms, spec §7.2).
        val sigmaD = vioDriftFraction * dh
        val tanDiff = tanTop - tanBase
        val term1 = tanDiff * tanDiff * sigmaD * sigmaD
        val secTop = 1f / cos(alphaTopRad)
        val secBase = 1f / cos(alphaBaseRad)
        val term2 = dh * dh * secTop.pow(4) * SIGMA_ALPHA_RAD * SIGMA_ALPHA_RAD
        val term3 = dh * dh * secBase.pow(4) * SIGMA_ALPHA_RAD * SIGMA_ALPHA_RAD
        val sigmaH = sqrt(term1 + term2 + term3)

        // Step 5 — tier from §7.9 check matrix.
        val checks = listOf(
            check(sigmaH / h <= SIGMA_RATIO_YELLOW, Severity.WARN, "Height precision worse than \u00B15%"),
            check(dh <= YELLOW_DH_M, Severity.WARN, "Walked back more than 25 m"),
            check(abs(alphaTopRad) <= MAX_ALPHA_TOP_YELLOW, Severity.WARN, "Top aim angle steeper than 75\u00B0"),
            check(dh <= HIGH_DRIFT_DH_M, Severity.WARN, "Walked back more than 30 m (tracking drift risk)"),
        )
        val tier = combineChecks(checks)

        return HeightResult(h, dh, alphaTopRad, alphaBaseRad, sigmaH, tier, HeightMethod.VIO_WALKOFF_TANGENT, null)
    }

    private fun red(dh: Float, at: Float, ab: Float, reason: String): HeightResult {
        // Best-effort H so the panel still shows a number on red.
        val h = dh * (tan(at) - tan(ab))
        return HeightResult(h, dh, at, ab, 0f, ConfidenceTier.RED, HeightMethod.VIO_WALKOFF_TANGENT, reason)
    }

    private fun fmt(v: Float) = String.format(Locale.US, "%.1f", v)
}
