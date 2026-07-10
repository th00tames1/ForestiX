// Port of iOS InventoryEngine/VolumeEquations/TableLookup.swift.
// Spec §7.7. User-provided volume table with bilinear interpolation on
// (DBH, H). The coefficient record encodes the grid as a flat dictionary:
//
//   "dbh_0", "dbh_1", ..., "dbh_{n-1}"         monotonic DBH grid (cm)
//   "h_0",   "h_1",   ..., "h_{m-1}"           monotonic H grid (m)
//   "v_{i}_{j}" for i in 0..<n, j in 0..<m     V(cm, m) = m³
//
// Out-of-range queries clamp to the nearest edge.

package com.hcjeong.forestix.inventory

import kotlin.math.max
import kotlin.math.min

class TableLookup(coefficients: Map<String, Float>) : VolumeEquation {

    private val dbhGrid: List<Float>     // ascending cm
    private val hGrid: List<Float>       // ascending m
    private val volumes: Array<FloatArray>   // [i][j] = V at (dbhGrid[i], hGrid[j])
    private val merchFraction: Float

    init {
        val dbhCount = count(prefix = "dbh_", dict = coefficients)
        val hCount = count(prefix = "h_", dict = coefficients)
        require(dbhCount >= 2 && hCount >= 2) {
            "TableLookup needs at least 2 DBH and 2 H grid points"
        }
        dbhGrid = (0 until dbhCount).map { CoefficientLookup.required(coefficients, "dbh_$it") }
        hGrid = (0 until hCount).map { CoefficientLookup.required(coefficients, "h_$it") }
        volumes = Array(dbhCount) { i ->
            FloatArray(hCount) { j ->
                CoefficientLookup.required(coefficients, "v_${i}_$j")
            }
        }
        merchFraction = CoefficientLookup.optional(coefficients, "merchFraction", default = 0.85f)
    }

    override fun totalVolumeM3(dbhCm: Float, heightM: Float): Float {
        if (dbhCm <= 0f || heightM <= 0f) return 0f
        val (i0, i1, tI) = bracket(dbhCm, grid = dbhGrid)
        val (j0, j1, tJ) = bracket(heightM, grid = hGrid)
        val v00 = volumes[i0][j0]
        val v01 = volumes[i0][j1]
        val v10 = volumes[i1][j0]
        val v11 = volumes[i1][j1]
        val v0 = v00 + (v01 - v00) * tJ
        val v1 = v10 + (v11 - v10) * tJ
        return v0 + (v1 - v0) * tI
    }

    override fun merchantableVolumeM3(dbhCm: Float, heightM: Float,
                                      topDibCm: Float, stumpHeightCm: Float): Float =
        totalVolumeM3(dbhCm = dbhCm, heightM = heightM) * merchFraction

    // MARK: - helpers

    private companion object {

        /// Number of sequential keys `<prefix>0, <prefix>1, ...` present in `dict`.
        fun count(prefix: String, dict: Map<String, Float>): Int {
            var n = 0
            while (dict["$prefix$n"] != null) n += 1
            return n
        }

        /// Return (low-index, high-index, t) for bracketing `x` within `grid`.
        /// Clamps at both ends.
        fun bracket(x: Float, grid: List<Float>): Triple<Int, Int, Float> {
            if (x <= grid.first()) return Triple(0, min(1, grid.size - 1), 0f)
            if (x >= grid.last()) {
                val last = grid.size - 1
                return Triple(max(0, last - 1), last, 1f)
            }
            var lo = 0
            var hi = grid.size - 1
            while (hi - lo > 1) {
                val mid = (hi + lo) / 2
                if (grid[mid] <= x) lo = mid else hi = mid
            }
            val t = (x - grid[lo]) / (grid[hi] - grid[lo])
            return Triple(lo, hi, t)
        }
    }
}
