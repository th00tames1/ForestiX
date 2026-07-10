// Port of iOS InventoryEngine/HeightSubsample.swift.
// Spec §7.4 + REQ-HGT-007. Deterministic height-subsample rule evaluated
// once per tree at Add-Tree time to decide whether the flow asks for a
// measured height or leaves `heightM` null for later H–D imputation.
//
// Pure function; uses only the rule + existing (non-deleted) trees on the
// plot. `newTreeNumber` is the tally number that would be assigned to the
// tree about to be added (typically liveCount+1 at the point of call).

package com.hcjeong.forestix.inventory

import com.hcjeong.forestix.data.cruise.HeightSubsampleRule
import com.hcjeong.forestix.data.cruise.Tree

object HeightSubsample {

    /// Returns `true` when the flow should require a measured height on the
    /// tree currently being added. Callers still allow the user to skip
    /// measurement (it will be imputed from the H–D model).
    ///
    /// Live trees only are counted when applying per-species rules — dead or
    /// soft-deleted trees don't carry measured heights into the subsample.
    fun shouldMeasureHeight(
        rule: HeightSubsampleRule,
        newTreeNumber: Int,
        newSpeciesCode: String,
        existingTreesOnPlot: List<Tree>,
    ): Boolean = when (rule) {
        is HeightSubsampleRule.AllTrees -> true
        is HeightSubsampleRule.None -> false
        is HeightSubsampleRule.EveryKth -> {
            val k = rule.k
            if (k <= 0) {
                true
            } else {
                // First tree (newTreeNumber == 1) always measured.
                newTreeNumber % k == 1 || k == 1
            }
        }
        is HeightSubsampleRule.PerSpeciesCount -> {
            val haveMeasuredForSpecies = existingTreesOnPlot.count {
                it.deletedAt == null &&
                    it.speciesCode == newSpeciesCode &&
                    it.heightM != null &&
                    it.heightSource == "measured"
            }
            haveMeasuredForSpecies < rule.minPerSpeciesOnPlot
        }
    }
}
