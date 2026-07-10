// Sampling-statistics engine — pure functions for cruise design and
// post-cruise reporting. Direct port of iOS Sensors/SamplingStats.swift.
//
// What's covered:
//   • cv / se / mean / standardDeviation
//   • confidenceInterval — two-sided
//   • requiredSampleSize(targetSEPct: cv: t:) — for cruise design
//   • neymanAllocation — for stratified cruise design
//   • reineke SDI helpers
//   • curtisRD — Curtis Relative Density (PNW standard)
//
// All inputs are plain lists of plot-level metrics; nothing here touches
// Room, AR, or persistence.

package com.hcjeong.forestix.sensors

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

object SamplingStats {

    // MARK: - Basic descriptive stats

    fun mean(xs: List<Double>): Double {
        if (xs.isEmpty()) return 0.0
        return xs.sum() / xs.size.toDouble()
    }

    /// Unbiased sample standard deviation (n − 1 denominator).
    /// Returns 0 for sample size < 2.
    fun standardDeviation(xs: List<Double>): Double {
        if (xs.size < 2) return 0.0
        val m = mean(xs)
        val sumSq = xs.fold(0.0) { acc, x -> acc + (x - m) * (x - m) }
        return sqrt(sumSq / (xs.size - 1).toDouble())
    }

    /// Coefficient of variation as a percentage.
    fun cv(xs: List<Double>): Double {
        val m = mean(xs)
        if (m == 0.0) return 0.0
        return standardDeviation(xs) / m * 100.0
    }

    /// Standard error of the mean (absolute units, not %).
    fun se(xs: List<Double>): Double {
        if (xs.size < 2) return 0.0
        return standardDeviation(xs) / sqrt(xs.size.toDouble())
    }

    /// Standard error of the mean as a percentage of the mean (one-
    /// sided SE%, often called sampling error in cruise reports).
    fun sePct(xs: List<Double>): Double {
        val m = mean(xs)
        if (m == 0.0) return 0.0
        return se(xs) / m * 100.0
    }

    // MARK: - Confidence intervals

    /// 95 % two-sided CI half-width on the mean using a Student-t
    /// approximation. `tApprox` defaults to 2.0 (n ≥ 30 rule of thumb).
    fun ciHalfWidth(xs: List<Double>, tApprox: Double = 2.0): Double =
        tApprox * se(xs)

    fun confidenceInterval(xs: List<Double>, tApprox: Double = 2.0): Pair<Double, Double>? {
        if (xs.size < 2) return null
        val m = mean(xs)
        val h = ciHalfWidth(xs, tApprox = tApprox)
        return Pair(m - h, m + h)
    }

    // MARK: - Required sample size

    /// Target sample size for a desired one-sided sampling error
    /// (`targetSEPct`) given an estimated CV (`cv`, in %) and a
    /// t-multiplier (~2 for n ≥ 30, ~3 for very small n). Result rounds up.
    fun requiredSampleSize(targetSEPct: Double, cv: Double, t: Double = 2.0): Int {
        if (targetSEPct <= 0.0) return 0
        val n = ((t * cv) / targetSEPct).pow(2)
        return ceil(n).toInt()
    }

    // MARK: - Neyman allocation

    /// Optimal stratified sample-size allocation. For each stratum:
    ///     n_h = totalN × (W_h × σ_h) / Σ (W_k × σ_k)
    /// Returns plots per stratum, rounded down with the remainder
    /// distributed largest-fraction-first.
    fun neymanAllocation(
        strataCV: List<Double>,
        strataN: List<Double>,
        totalSampleSize: Int,
    ): List<Int> {
        if (strataCV.size != strataN.size || strataCV.isEmpty() || totalSampleSize <= 0) {
            return List(strataCV.size) { 0 }
        }
        val totalN = strataN.sum()
        if (totalN <= 0.0) return List(strataCV.size) { 0 }
        val weights = strataCV.zip(strataN).map { (c, n) -> c * n }
        val sumW = weights.sum()
        if (sumW <= 0.0) return List(strataCV.size) { 0 }
        val raw = weights.map { totalSampleSize.toDouble() * it / sumW }
        val floored = raw.map { it.toInt() }.toMutableList()
        var remaining = totalSampleSize - floored.sum()
        val fractionsSorted = raw.mapIndexed { idx, v -> Pair(idx, v - v.toInt().toDouble()) }
            .sortedByDescending { it.second }
        for ((idx, _) in fractionsSorted) {
            if (remaining <= 0) break
            floored[idx] += 1
            remaining -= 1
        }
        return floored
    }

    // MARK: - Density indices

    /// Reineke SDI = TPA × (QMD_in / 10)^1.605. Returns 0 for non-positive inputs.
    fun reinekeSDI(tpa: Double, qmdInches: Double): Double {
        if (tpa <= 0.0 || qmdInches <= 0.0) return 0.0
        return tpa * (qmdInches / 10.0).pow(1.605)
    }

    /// Reineke relative density vs a species Max SDI baseline, as a
    /// percentage. Generic Max SDI of 717 is used downstream when
    /// species-specific tables aren't available.
    fun reinekeRelativeDensityPct(sdi: Double, maxSDI: Double): Double {
        if (maxSDI <= 0.0) return 0.0
        return min(200.0, max(0.0, sdi / maxSDI * 100.0))
    }

    /// Curtis Relative Density (PNW standard):
    ///     RD = BA / sqrt(QMD)   (BA in ft²/ac, QMD in inches)
    fun curtisRD(baPerAcreFt2: Double, qmdInches: Double): Double {
        if (qmdInches <= 0.0) return 0.0
        return baPerAcreFt2 / sqrt(qmdInches)
    }

    // MARK: - Cruise rating

    /// Three-band cruise rating off the standard sampling-error percentage.
    /// Raw values match the Swift enum cases byte-for-byte.
    enum class CruiseRating(val raw: String) {
        ACCEPTABLE("acceptable"),
        MARGINAL("marginal"),
        POOR("poor"),
    }

    fun rating(forSEPct: Double): CruiseRating = when {
        forSEPct < 10.0 -> CruiseRating.ACCEPTABLE
        forSEPct < 20.0 -> CruiseRating.MARGINAL
        else -> CruiseRating.POOR
    }
}
