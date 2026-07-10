// Port of iOS InventoryEngine/StandStatistics.swift.
// Spec §7.5 Stand-Level Statistics. Stratified sampling with Satterthwaite
// approximation for the overall degrees of freedom.
//
// Per stratum h, plot values y_{h,j}:
//   ȳ_h     = mean(y)
//   s²_h    = unbiased variance
//   var_ȳ_h = s²_h / n_h · (1 − n_h/N_h)        [FPC]
//   Ŷ       = Σ_h A_h · ȳ_h
//   var_Ŷ   = Σ_h A_h² · var_ȳ_h
//   SE_Ŷ    = sqrt(var_Ŷ)
//   df      = Satterthwaite( {s²_h, n_h, A_h} )
//   CI95    = Ŷ ± t_{0.975, df} · SE_Ŷ
//
// If N_h is unknown (null), treat FPC as 1 (infinite-population assumption).

package com.hcjeong.forestix.inventory

import kotlin.math.max
import kotlin.math.sqrt

object StandStatistics {

    // MARK: - Input types

    data class StratumSample(
        val areaAcres: Float,                // A_h
        val plotValues: List<Float>,          // y_{h,j}
        val populationSize: Int? = null,      // N_h (optional; null ⇒ skip FPC)
    )

    // MARK: - Output

    data class Result(
        val total: Float,                     // Ŷ  (attribute × acre)
        val se: Float,                        // SE(Ŷ)
        val df: Float,                        // Satterthwaite degrees of freedom
        val ci95Lower: Float,                 // Ŷ − t · SE
        val ci95Upper: Float,                 // Ŷ + t · SE
        val perStratumMean: List<Float>,      // ȳ_h in stratum order
        val perStratumVarOfMean: List<Float>, // var(ȳ_h) in stratum order
    )

    // MARK: - Public entry point

    fun compute(strata: List<StratumSample>): Result {
        require(strata.isNotEmpty()) { "at least one stratum required" }

        var yhat = 0f
        var varYhat = 0f
        val means = mutableListOf<Float>()
        val varMeans = mutableListOf<Float>()

        // Also gather per-stratum variance components for Satterthwaite df.
        var denom = 0f                       // Σ (A_h² · var_ȳ_h)² / (n_h − 1)
        var totalN = 0

        for (s in strata) {
            totalN += s.plotValues.size
            val n = s.plotValues.size
            if (n < 1) {
                means.add(0f)
                varMeans.add(0f)
                continue
            }
            val yBar = s.plotValues.fold(0f) { acc, v -> acc + v } / n.toFloat()
            var s2 = 0f
            if (n >= 2) {
                s2 = s.plotValues.fold(0f) { acc, v -> acc + (v - yBar) * (v - yBar) } / (n - 1).toFloat()
            }
            val N = s.populationSize
            val fpc: Float = if (N != null && N > 0) {
                max(0f, 1f - n.toFloat() / N.toFloat())
            } else {
                1f
            }
            val varYBar = if (n >= 2) (s2 / n.toFloat()) * fpc else 0f
            means.add(yBar)
            varMeans.add(varYBar)

            yhat += s.areaAcres * yBar
            varYhat += s.areaAcres * s.areaAcres * varYBar

            val contribution = s.areaAcres * s.areaAcres * varYBar
            if (n >= 2) {
                denom += (contribution * contribution) / (n - 1).toFloat()
            }
        }

        val numer = varYhat * varYhat        // (Σ A_h² · var_ȳ_h)²
        val df: Float = if (denom > 0f) (numer / denom) else max(1, totalN - strata.size).toFloat()
        val se = sqrt(max(0f, varYhat))
        val tVal = tStudent975(df = df)
        val lower = yhat - tVal * se
        val upper = yhat + tVal * se

        return Result(
            total = yhat,
            se = se,
            df = df,
            ci95Lower = lower,
            ci95Upper = upper,
            perStratumMean = means,
            perStratumVarOfMean = varMeans,
        )
    }

    // MARK: - Student's t (two-sided 95%) approximation
    //
    // A table-driven approximation keyed on integer df, linearly interpolated.
    // For df ≥ 120 we use the normal quantile 1.960.
    // Values from standard Student-t tables (0.975 quantile, two-sided 95%).

    private val tTable: List<Pair<Float, Float>> = listOf(  // (df, t)
        1f to 12.706f,
        2f to 4.303f,
        3f to 3.182f,
        4f to 2.776f,
        5f to 2.571f,
        6f to 2.447f,
        7f to 2.365f,
        8f to 2.306f,
        9f to 2.262f,
        10f to 2.228f,
        12f to 2.179f,
        15f to 2.131f,
        20f to 2.086f,
        25f to 2.060f,
        30f to 2.042f,
        40f to 2.021f,
        60f to 2.000f,
        120f to 1.980f,
    )

    fun tStudent975(df: Float): Float {
        if (df <= tTable.first().first) return tTable.first().second
        if (df >= 120f) return 1.960f
        for (i in 1 until tTable.size) {
            val (aDf, aT) = tTable[i - 1]
            val (bDf, bT) = tTable[i]
            if (df <= bDf) {
                val t = (df - aDf) / (bDf - aDf)
                return aT + (bT - aT) * t
            }
        }
        return 1.960f
    }
}
