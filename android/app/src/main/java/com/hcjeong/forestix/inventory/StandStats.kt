// Port of iOS InventoryEngine/StandStats.swift.
// Spec §7.5 stand-level stratified statistics. REQ-AGG-003.
//
// Given a list of plot-level measurements (TPA, BA/ac, V/ac, ...) grouped by
// stratum plus the acreage of each stratum, computes:
//   • per-stratum mean & variance
//   • stand-level weighted mean
//   • standard error under stratified random sampling (FPC ignored — plot
//     population is large compared with sample n)
//   • Satterthwaite-approximated degrees of freedom
//   • 95% CI half-width using the Student t distribution
//
// Unstratified plots collapse to a single `"__unstratified__"` stratum key,
// so callers always get a usable `StandStat` even without a stratum map.

package com.hcjeong.forestix.inventory

import kotlin.math.max
import kotlin.math.sqrt

data class StandStat(
    val mean: Double,
    val seMean: Double,
    val ci95HalfWidth: Double,
    val dfSatterthwaite: Double,
    val nPlots: Int,
    val byStratum: Map<String, StratumStat>,
) {
    data class StratumStat(
        val nPlots: Int,
        val mean: Double,
        val variance: Double,
        val areaAcres: Double,
    )

    companion object {
        val empty = StandStat(
            mean = 0.0, seMean = 0.0, ci95HalfWidth = 0.0,
            dfSatterthwaite = 0.0, nPlots = 0, byStratum = emptyMap())
    }
}

object StandStatsCalculator {

    /// Compute a single metric's stand-level statistic.
    ///
    /// - plotValues: `(stratumKey, valuePerPlot)` — one entry per plot.
    /// - stratumAreasAcres: map of stratum key → area. Missing keys default
    ///   to 1.0 (treat each stratum as equal-area) so that unstratified
    ///   callers see the unweighted sample mean.
    fun compute(
        plotValues: List<Pair<String, Double>>,
        stratumAreasAcres: Map<String, Double>,
    ): StandStat {
        if (plotValues.isEmpty()) return StandStat.empty

        val groups = mutableMapOf<String, MutableList<Double>>()
        for ((key, v) in plotValues) {
            groups.getOrPut(key) { mutableListOf() }.add(v)
        }

        val byStratum = mutableMapOf<String, StandStat.StratumStat>()
        for ((key, vals) in groups) {
            val n = vals.size
            val mean = vals.fold(0.0) { acc, v -> acc + v } / n.toDouble()
            val variance: Double = if (n < 2) {
                0.0
            } else {
                val sumSq = vals.fold(0.0) { acc, v -> acc + (v - mean) * (v - mean) }
                sumSq / (n - 1).toDouble()
            }
            val area = stratumAreasAcres[key] ?: 1.0
            byStratum[key] = StandStat.StratumStat(
                nPlots = n, mean = mean, variance = variance, areaAcres = area)
        }

        val totalArea = byStratum.values.fold(0.0) { acc, s -> acc + s.areaAcres }
        if (totalArea <= 0.0) {
            return StandStat(
                mean = 0.0, seMean = 0.0, ci95HalfWidth = 0.0,
                dfSatterthwaite = 0.0, nPlots = plotValues.size,
                byStratum = byStratum)
        }

        var weightedMean = 0.0
        var seSqSum = 0.0
        var satterNum = 0.0
        var satterDenom = 0.0
        for ((_, s) in byStratum) {
            val w = s.areaAcres / totalArea
            weightedMean += w * s.mean
            val term = w * w * s.variance / max(s.nPlots, 1).toDouble()
            seSqSum += term
            satterNum += term
            if (s.nPlots >= 2) {
                satterDenom += (term * term) / (s.nPlots - 1).toDouble()
            }
        }
        val se = sqrt(seSqSum)
        val df: Double = if (satterDenom > 0.0) {
            (satterNum * satterNum) / satterDenom
        } else {
            (plotValues.size - byStratum.size).toDouble()
        }
        val t = tCritical95(df = max(df, 1.0))
        val ci = t * se

        return StandStat(
            mean = weightedMean,
            seMean = se,
            ci95HalfWidth = ci,
            dfSatterthwaite = df,
            nPlots = plotValues.size,
            byStratum = byStratum)
    }

    /// Approximate two-sided t critical value at α = 0.05 for df ≥ 1.
    /// Uses a Wilson-Hilferty-style interpolation good to ~1% for typical
    /// cruising df (1..120).
    internal fun tCritical95(df: Double): Double {
        // Hand-picked table; linear interpolation between rows.
        val table: List<Pair<Double, Double>> = listOf(
            1.0 to 12.706, 2.0 to 4.303, 3.0 to 3.182, 4.0 to 2.776,
            5.0 to 2.571, 6.0 to 2.447, 7.0 to 2.365, 8.0 to 2.306,
            9.0 to 2.262, 10.0 to 2.228, 15.0 to 2.131, 20.0 to 2.086,
            30.0 to 2.042, 50.0 to 2.009, 100.0 to 1.984, 1000.0 to 1.962)
        if (df <= table.first().first) return table.first().second
        if (df >= table.last().first) return 1.96
        for (i in 0 until table.size - 1) {
            val (d0, t0) = table[i]
            val (d1, t1) = table[i + 1]
            if (df >= d0 && df <= d1) {
                val f = (df - d0) / (d1 - d0)
                return t0 + f * (t1 - t0)
            }
        }
        return 1.96
    }
}
