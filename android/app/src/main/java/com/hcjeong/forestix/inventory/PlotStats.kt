// Port of iOS InventoryEngine/PlotStats.swift.
// Spec §7.6 live plot statistics for the tally-screen header strip.
// REQ-TAL-005: live updates must be ready within 300 ms of a tree add, so
// every path here is O(n_live_trees) with no I/O.
//
// Inputs are *pure* snapshots — the caller resolves species, volume
// equations, and (optionally) H–D fits before invoking this function. The
// result is a value type suitable for a state-flow binding.

package com.hcjeong.forestix.inventory

import com.hcjeong.forestix.data.cruise.CruiseDesign
import com.hcjeong.forestix.data.cruise.Plot
import com.hcjeong.forestix.data.cruise.PlotType
import com.hcjeong.forestix.data.cruise.SpeciesConfig
import com.hcjeong.forestix.data.cruise.Tree
import com.hcjeong.forestix.data.cruise.TreeStatus
import kotlin.math.sqrt

// MARK: - Result types

data class PlotStats(
    val liveTreeCount: Int,
    val tpa: Float,                          // trees/acre
    val baPerAcreM2: Float,                  // m²/acre
    val qmdCm: Float,                        // cm
    val grossVolumePerAcreM3: Float,         // m³/acre (all live trees)
    val merchVolumePerAcreM3: Float,         // m³/acre (stump → top-DIB)
    val bySpecies: Map<String, SpeciesStat>,
) {
    data class SpeciesStat(
        val count: Int,
        val tpa: Float,
        val baPerAcreM2: Float,
        val grossVolumePerAcreM3: Float,
    )

    companion object {
        val empty = PlotStats(
            liveTreeCount = 0, tpa = 0f, baPerAcreM2 = 0f, qmdCm = 0f,
            grossVolumePerAcreM3 = 0f, merchVolumePerAcreM3 = 0f,
            bySpecies = emptyMap())
    }
}

// MARK: - Calculator

object PlotStatsCalculator {

    /// Per-species accumulation bucket (Swift uses an inline tuple).
    private class Bucket {
        var count = 0
        var tpa = 0f
        var ba = 0f
        var vol = 0f
    }

    /// Compute live stats for a plot. Soft-deleted trees and non-live
    /// status trees are excluded from TPA, BA, QMD, and volume. Trees missing
    /// a measured height but with an `hdFits` entry for their species have
    /// height imputed via the Näslund model; otherwise volume for that tree
    /// contributes 0 (surfaced as a "missing height" warning elsewhere).
    ///
    /// - plot: denormalized plot (uses `plotAreaAcres` for fixed-area EF).
    /// - cruiseDesign: only `plotType` and `baf` are consulted.
    /// - trees: all trees on the plot (caller need not pre-filter).
    /// - species: map of speciesCode → config (for merch volume topDIB/stump).
    /// - volumeEquations: map of speciesCode → volume equation. Missing
    ///   entries ⇒ volume contribution 0 for that species.
    /// - hdFits: map of speciesCode → H–D fit for imputing missing heights.
    fun compute(
        plot: Plot,
        cruiseDesign: CruiseDesign,
        trees: List<Tree>,
        species: Map<String, SpeciesConfig>,
        volumeEquations: Map<String, VolumeEquation>,
        hdFits: Map<String, HDModel.Fit> = emptyMap(),
    ): PlotStats {

        val live = trees.filter { it.deletedAt == null && it.status == TreeStatus.LIVE }
        if (live.isEmpty()) return PlotStats.empty

        val isFixed = cruiseDesign.plotType == PlotType.FIXED_AREA
        // Pre-compute expansion factor for fixed-area (constant) or build
        // per-tree factor inside the loop for BAF.
        val fixedEF: Float = if (isFixed) {
            ExpansionFactors.fixedArea(plotAreaAcres = plot.plotAreaAcres)
        } else {
            0f
        }

        var totalTPA = 0f
        var totalBAPerAcre = 0f
        var sumDbhSq = 0f                    // for QMD
        var totalGrossVolPerAcre = 0f
        var totalMerchVolPerAcre = 0f
        val bySpecies = mutableMapOf<String, Bucket>()

        for (tree in live) {
            val ba = basalAreaM2(dbhCm = tree.dbhCm)
            sumDbhSq += tree.dbhCm * tree.dbhCm

            // Per-tree expansion factor.
            val ef: Float = if (isFixed) {
                fixedEF
            } else {
                cruiseDesign.baf?.let { if (ba > 0f) it / ba else 0f } ?: 0f
            }

            totalTPA += ef
            totalBAPerAcre += ba * ef

            // Height: measured or imputed from fit.
            val measured = tree.heightM
            val fit = hdFits[tree.speciesCode]
            val h: Float = when {
                measured != null && measured > 1.3f -> measured
                fit != null -> HDModel.impute(dbhCm = tree.dbhCm, fit = fit)
                else -> 0f   // no height, no volume contribution
            }

            var grossVolPerAcre = 0f
            val eq = volumeEquations[tree.speciesCode]
            if (h > 1.3f && eq != null) {
                val vGross = eq.totalVolumeM3(dbhCm = tree.dbhCm, heightM = h)
                grossVolPerAcre = vGross * ef
                totalGrossVolPerAcre += grossVolPerAcre
                val sp = species[tree.speciesCode]
                if (sp != null) {
                    val vMerch = eq.merchantableVolumeM3(
                        dbhCm = tree.dbhCm, heightM = h,
                        topDibCm = sp.merchTopDibCm,
                        stumpHeightCm = sp.stumpHeightCm)
                    totalMerchVolPerAcre += vMerch * ef
                }
            }

            val bucket = bySpecies.getOrPut(tree.speciesCode) { Bucket() }
            bucket.count += 1
            bucket.tpa += ef
            bucket.ba += ba * ef
            bucket.vol += grossVolPerAcre
        }

        val qmd = sqrt(sumDbhSq / live.size.toFloat())

        val speciesOut = bySpecies.mapValues { (_, bucket) ->
            PlotStats.SpeciesStat(
                count = bucket.count,
                tpa = bucket.tpa,
                baPerAcreM2 = bucket.ba,
                grossVolumePerAcreM3 = bucket.vol)
        }

        return PlotStats(
            liveTreeCount = live.size,
            tpa = totalTPA,
            baPerAcreM2 = totalBAPerAcre,
            qmdCm = qmd,
            grossVolumePerAcreM3 = totalGrossVolPerAcre,
            merchVolumePerAcreM3 = totalMerchVolPerAcre,
            bySpecies = speciesOut)
    }
}
