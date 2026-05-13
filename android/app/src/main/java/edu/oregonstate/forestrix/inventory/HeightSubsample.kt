package edu.oregonstate.forestrix.inventory

import edu.oregonstate.forestrix.models.HeightSubsampleRule
import edu.oregonstate.forestrix.models.Tree

object HeightSubsample {
    fun shouldMeasureHeight(
        rule: HeightSubsampleRule,
        newTreeNumber: Int,
        newSpeciesCode: String,
        existingTreesOnPlot: List<Tree>
    ): Boolean {
        return when (rule) {
            HeightSubsampleRule.AllTrees -> true
            HeightSubsampleRule.None -> false
            is HeightSubsampleRule.EveryKth -> {
                val k = rule.k
                k <= 1 || newTreeNumber % k == 1
            }
            is HeightSubsampleRule.PerSpeciesCount -> {
                val measuredForSpecies = existingTreesOnPlot.count {
                    it.deletedAtMillis == null &&
                        it.speciesCode == newSpeciesCode &&
                        it.heightM != null &&
                        it.heightSource == "measured"
                }
                measuredForSpecies < rule.minPerSpeciesOnPlot
            }
        }
    }
}
