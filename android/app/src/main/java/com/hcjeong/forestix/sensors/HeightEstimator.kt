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

import com.hcjeong.forestix.common.MeasurementFormatter
import com.hcjeong.forestix.common.UncertaintyBand
import com.hcjeong.forestix.common.UnitSystem
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

    /// σ_H (m) — the PROPAGATED uncertainty, never a placeholder.
    ///
    /// NULL means "no uncertainty is derivable from this geometry", which is
    /// the only honest value for a non-measurement (degenerate d_h, inverted
    /// aims, non-finite inputs). It is NOT a synonym for "bad reading".
    ///
    /// Every red return used to report 0 here. That was harmless only while
    /// a red fit could never be accepted; now that it can, a 0 would persist
    /// the WORST readings claiming PERFECT precision — into Tree.heightSigmaM,
    /// the quick-measure history, the research CSV and the raw-capture
    /// manifest, i.e. straight into the σ-propagation spine the accuracy
    /// study rests on. So a red fit now carries its real (large) σ_H, and
    /// [canAccept] refuses any tangent result whose σ_H is still null.
    val sigmaHm: Float?,

    val confidence: ConfidenceTier,
    val method: HeightMethod,
    val rejectionReason: String?,
)

object HeightEstimator {

    // Spec §7.2 constants (verbatim from iOS).

    /// GEOMETRIC SANITY FLOOR — deliberately tiny, and NOT a quality proxy.
    /// Below this the standing point and the anchor are effectively the same
    /// point: there is no baseline for the tangent triangle and d_h is pure
    /// pose noise.
    ///
    /// This used to be 3.0 m, a crude stand-in for "the aim angles will be
    /// bad from this close" — a thing σ_H and the α_top check below already
    /// measure directly, and measure correctly at every tree size. Distance
    /// is the wrong variable:
    ///
    ///   • A 2 m sapling from d_h = 1.5 m gives α_top ≈ 18°, α_base ≈ −45°,
    ///     H = 2.0 m and σ_H/H ≈ 2.2% — comfortably inside the 5% green
    ///     band, yet the old floor refused it outright, which made a tree
    ///     farm unmeasurable.
    ///   • A 20 m tree at exactly 3.0 m has α_top ≈ 81° and is a poor
    ///     measurement, yet it satisfied the old floor.
    ///
    /// So the floor is now only the degenerate case, and the tier comes from
    /// σ_H/H and the angle checks — unchanged, and unweakened.
    const val MIN_DH_M = 0.3f
    const val YELLOW_DH_M = 25.0f
    const val HIGH_DRIFT_DH_M = 30.0f
    val MAX_ALPHA_TOP_RED = Math.toRadians(85.0).toFloat()
    val MAX_ALPHA_TOP_YELLOW = Math.toRadians(75.0).toFloat()

    /// Lower bound on a plausible measured height. 0.3 m (was 1.5 m) so
    /// seedlings and saplings are measurable at all. The inversion guard and
    /// [MAX_H_M] are untouched: a negative or inverted result is still
    /// refused, and so is anything above 100 m.
    const val MIN_H_M = 0.3f
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
        /// Only the σ sentence below reads this — the band it quotes is a
        /// LENGTH, and an imperial cruiser was being handed it in metres.
        /// Nothing else here converts: the geometry, σ_H and every
        /// threshold stay metric. Replay keeps the metric default.
        unitSystem: UnitSystem = UnitSystem.METRIC,
    ): HeightResult {
        // Step 1 — horizontal distance (drop Y onto the ground plane).
        val dx = standingX - anchorX
        val dz = standingZ - anchorZ
        val dh = sqrt(dx * dx + dz * dz)

        // Step 2 — guard rails -> red. (The old "AR tracking lost
        // mid-measurement" hard reject was removed — field fix, both
        // platforms: transient tracking dips flagged good captures; the
        // walk-off drift risk is already covered by the d_h WARN checks.)
        //
        // Degenerate geometry only — see [MIN_DH_M]. Everything the old 3 m
        // floor was standing in for is measured by σ_H and the α_top check
        // in Step 5, which are correct at every tree size.
        if (dh < MIN_DH_M)
            return red(dh, alphaTopRad, alphaBaseRad, vioDriftFraction,
                "Too close; step back (walked back ${fmt(dh)} m so far)")
        if (abs(alphaTopRad) > MAX_ALPHA_TOP_RED)
            return red(dh, alphaTopRad, alphaBaseRad, vioDriftFraction,
                "Top angle too steep; step back")
        if (abs(alphaBaseRad) > MAX_ALPHA_TOP_RED)
            return red(dh, alphaTopRad, alphaBaseRad, vioDriftFraction,
                "Base angle too steep; step back")

        // Step 3 — two-tangent height.
        val tanTop = tan(alphaTopRad)
        val tanBase = tan(alphaBaseRad)
        if (tanTop <= tanBase)
            return red(dh, alphaTopRad, alphaBaseRad, vioDriftFraction,
                "Top aim was at or below the base — re-capture the top higher")

        val h = dh * (tanTop - tanBase)
        if (!(h in MIN_H_M..MAX_H_M))
            return red(dh, alphaTopRad, alphaBaseRad, vioDriftFraction,
                "Computed height ${fmt(h)} m out of range")

        // Step 4 — σ_H propagation. ONE shared implementation ([sigmaH]), so
        // the red path reports exactly the number the green path would.
        // The geometry is sound by here, so it always returns a value; the
        // null branch is defensive (a non-finite input that slipped every
        // guard above) and must not collapse into a silent zero.
        val sigma = sigmaH(dh, alphaTopRad, alphaBaseRad, vioDriftFraction)
            ?: return red(dh, alphaTopRad, alphaBaseRad, vioDriftFraction,
                "Couldn't work out how accurate this height is — retake the base and top aims.")

        // Step 5 — tier from §7.9 check matrix.
        val checks = listOf(
            // The ± band is the propagated σ_H as a length — an amount a
            // cruiser can act on, unlike the σ/H ratio the check itself
            // is written in. [UncertaintyBand] renders it in the cruiser's
            // own units (the band used to be metres inside a sentence shown
            // beside a height in feet) and never as a zero band. The CHECK
            // is unchanged; only the sentence that reports it is.
            check(
                sigma / h <= SIGMA_RATIO_YELLOW, Severity.WARN,
                "This height could be off by about " +
                    UncertaintyBand.text(sigma.toDouble(), unitSystem) +
                    " — walk further back and retake for a tighter reading.",
            ),
            // Every height check is a WARN, and two WARNs make the tier red —
            // so failedCheckReasons below can put ANY of these four in front of
            // the cruiser, not just the σ one. All four therefore say what
            // happened AND what to do about it. Predicates unchanged.
            // The walk-back distances are quoted through `guidanceDistance`
            // for the same reason the band above is: these sentences tell the
            // cruiser where to stand, and one worded in a unit they do not pace
            // in is not an instruction. The THRESHOLDS are the metric ones they
            // have always been and are read from the same constants the checks
            // apply, so the wording cannot drift from the gate.
            check(dh <= YELLOW_DH_M, Severity.WARN,
                "You walked back more than " +
                    MeasurementFormatter.guidanceDistance(YELLOW_DH_M.toDouble(), unitSystem) +
                    " — that far out, a small slip in aim " +
                    "turns into a big height error. Move closer and retake if you can."),
            check(abs(alphaTopRad) <= MAX_ALPHA_TOP_YELLOW, Severity.WARN,
                "You aimed more than 75\u00B0 up at the top — you are too close to the " +
                    "tree. Walk back until you can see the top comfortably, then retake."),
            check(dh <= HIGH_DRIFT_DH_M, Severity.WARN,
                "You walked back more than " +
                    MeasurementFormatter.guidanceDistance(HIGH_DRIFT_DH_M.toDouble(), unitSystem) +
                    " — the distance may have crept. Move closer and retake if you can."),
        )
        val tier = combineChecks(checks)

        // A red tier HERE is the σ / angle / walk-back verdict — the criteria
        // that actually measure quality, now that the flat distance floor is
        // gone. Name the failing checks so the result panel can show WHY
        // inline: the fit is still acceptable, and a cruiser must be able to
        // read the reason next to the number before committing it. Mirrors
        // DbhEstimator's red-only reason line; yellow/green stay null.
        val reason: String? =
            if (tier == ConfidenceTier.RED) {
                failedCheckReasons(checks)
                    ?: "This reading didn't pass the accuracy checks — retake it, or accept it and flag the tree."
            } else null

        return HeightResult(h, dh, alphaTopRad, alphaBaseRad, sigma, tier, HeightMethod.VIO_WALKOFF_TANGENT, reason)
    }

    /// σ_H (m) for one tangent geometry — the spec §7.2 propagation, three
    /// variance terms: the baseline (σ_d = drift·d_h) and the two aim angles
    /// (each d_h²·sec⁴α·σ_α²).
    ///
    /// This is the ONLY implementation, and it is deliberately independent of
    /// the tier: the formula is well defined for ANY finite d_h and any pair
    /// of angles short of the vertical, so a poor reading gets its true (and
    /// large) σ_H rather than a placeholder. Steep aims are exactly where
    /// sec⁴ makes σ_H explode — reporting that number IS the point.
    ///
    /// Null only when there is no measurement to attach an uncertainty to:
    ///   • a non-finite input (d_h or either angle),
    ///   • a degenerate baseline (d_h < [MIN_DH_M]) — anchor and standing
    ///     point are the same spot, so σ would come out reassuringly TINY
    ///     for what is pure pose noise,
    ///   • inverted aims (tan α_top ≤ tan α_base) — there is no H,
    ///   • a vertical aim (|α| = 90°), where sec is infinite.
    /// [canAccept] refuses every one of those, so a null σ_H can never reach
    /// storage through the accept path.
    fun sigmaH(
        dHm: Float,
        alphaTopRad: Float,
        alphaBaseRad: Float,
        vioDriftFraction: Float = DEFAULT_VIO_DRIFT_FRACTION,
    ): Float? {
        if (!dHm.isFinite() || !alphaTopRad.isFinite() || !alphaBaseRad.isFinite()) return null
        if (!vioDriftFraction.isFinite()) return null
        if (dHm < MIN_DH_M) return null
        val tanTop = tan(alphaTopRad)
        val tanBase = tan(alphaBaseRad)
        if (!tanTop.isFinite() || !tanBase.isFinite() || tanTop <= tanBase) return null
        val sigmaD = vioDriftFraction * dHm
        val tanDiff = tanTop - tanBase
        val term1 = tanDiff * tanDiff * sigmaD * sigmaD
        val secTop = 1f / cos(alphaTopRad)
        val secBase = 1f / cos(alphaBaseRad)
        val term2 = dHm * dHm * secTop.pow(4) * SIGMA_ALPHA_RAD * SIGMA_ALPHA_RAD
        val term3 = dHm * dHm * secBase.pow(4) * SIGMA_ALPHA_RAD * SIGMA_ALPHA_RAD
        val s = sqrt(term1 + term2 + term3)
        return if (s.isFinite() && s >= 0f) s else null
    }

    /// Every failing check's reason, joined — what made the tier red.
    private fun failedCheckReasons(checks: List<Check>): String? {
        val failed = checks.filter { !it.passed && it.reason.isNotEmpty() }.map { it.reason }
        return if (failed.isEmpty()) null else failed.joinToString(" · ")
    }

    /// Whether a result is a real fit the cruiser may commit.
    ///
    /// THE TIER IS NOT PART OF THIS. A red fit is still a fit: the cruiser
    /// sees the reason inline and decides, and the tier travels with the
    /// reading (Tree.heightConfidence, the research CSV row and the
    /// raw-capture manifest) so the accuracy analysis can filter on it.
    /// Refused here is only a result that is not a measurement at all — an
    /// inverted pair of aims, a height outside the plausible window,
    /// degenerate standing-point/anchor geometry, or (the same set seen from
    /// the other side) a σ_H that could not be derived. Those carry no
    /// physical meaning, so no amount of cruiser judgement makes them safe
    /// to store.
    ///
    /// Non-tangent methods (manual entry, tape, imputed H–D) are not this
    /// function's business and always pass.
    fun canAccept(result: HeightResult): Boolean {
        if (result.method != HeightMethod.VIO_WALKOFF_TANGENT) return true
        if (!result.heightM.isFinite() || !result.dHm.isFinite()) return false
        if (result.heightM < MIN_H_M || result.heightM > MAX_H_M) return false
        if (result.dHm < MIN_DH_M) return false
        // Inversion guard, re-derived from the stored aims: a top aim at or
        // below the base is geometrically impossible.
        if (tan(result.alphaTopRad) <= tan(result.alphaBaseRad)) return false
        // σ_H must be a REAL propagated value. Everything downstream of
        // Accept — Tree.heightSigmaM, the quick-measure history, the research
        // CSV — treats this field as the reading's uncertainty, so a missing
        // one may never be stored as though it were a confident zero. A red
        // TIER still passes (it carries a real, large σ_H); an underivable σ
        // never does.
        val sigma = result.sigmaHm
        if (sigma == null || !sigma.isFinite() || sigma < 0f) return false
        return true
    }

    /// Red return. σ_H is the REAL propagated value wherever the geometry
    /// permits one (see [sigmaH]) — a steep aim or an out-of-range height
    /// still has a well-defined uncertainty, and reporting it is what keeps
    /// the σ spine honest now that a red fit is committable. It stays null
    /// only for the non-measurements, which [canAccept] then refuses.
    private fun red(dh: Float, at: Float, ab: Float, drift: Float, reason: String): HeightResult {
        // Best-effort H so the panel still shows a number on red.
        val h = dh * (tan(at) - tan(ab))
        val sigma = sigmaH(dh, at, ab, drift)
        return HeightResult(h, dh, at, ab, sigma, ConfidenceTier.RED, HeightMethod.VIO_WALKOFF_TANGENT, reason)
    }

    private fun fmt(v: Float) = String.format(Locale.US, "%.1f", v)
}
