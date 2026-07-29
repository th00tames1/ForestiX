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

    // MARK: - Plot-level progress (how much of the subsample is left)

    /// How many measured heights the rule asks of one plot, and how many of
    /// them it already has. The plot peek reads this to answer the only
    /// question a cruiser has in the stand: is this plot's subsample done.
    ///
    /// `measured` — measured heights that COUNT TOWARD `target`, never the
    /// plot's raw height count, so the numerator can never overrun the
    /// denominator or report a plot as done on the strength of heights the
    /// rule did not ask for. When there is no target it falls back to the
    /// plot's plain measured count, which is all that can honestly be said.
    ///
    /// `target` — how many the rule asks of THIS plot, or null when the rule
    /// sets no target at all. NEVER invented: a null (or zero) target has to
    /// be rendered as a plain count, not as a denominator.
    data class Progress(val measured: Int, val target: Int?)

    /// Progress of `rule` over one plot's trees. Deleted trees are dropped
    /// here exactly as `shouldMeasureHeight` drops them.
    ///
    /// One owner for the rule: `AllTrees` and `EveryKth` are decided per tree
    /// from the tree's own tally number, so their target is counted by asking
    /// `shouldMeasureHeight` itself rather than re-deriving the k arithmetic
    /// beside it — the peek cannot ask for a different number than the flow.
    fun progress(
        rule: HeightSubsampleRule,
        treesOnPlot: List<Tree>,
    ): Progress {
        val live = treesOnPlot.filter { it.deletedAt == null }
        // The plot's plain measured count — what is reported when the rule
        // asks nothing of this plot and there is no denominator to pair with.
        val plainCount = live.count { isMeasured(it) }

        return when (rule) {
            // The rule asks for nothing, so there is no denominator: a
            // fraction here would invent an obligation the cruiser does not
            // have. The plain count still reports what the plot carries,
            // because a height measured off-rule is still a height.
            is HeightSubsampleRule.None -> Progress(plainCount, null)

            // The rule is per SPECIES, so a single plot-wide "of N" is a lie
            // the moment a second species is tallied — a plot could satisfy
            // the rule for oak, carry nothing for pine, and still read done.
            // Summed per species instead, with BOTH sides capped at what that
            // species can supply: you cannot measure a tree that is not on
            // the plot, and surplus heights for one species never fill
            // another's quota.
            is HeightSubsampleRule.PerSpeciesCount -> {
                val n = rule.minPerSpeciesOnPlot
                if (n <= 0) {
                    Progress(plainCount, 0)
                } else {
                    var target = 0
                    var have = 0
                    for ((_, ofSpecies) in live.groupBy { it.speciesCode }) {
                        target += minOf(n, ofSpecies.size)
                        have += minOf(n, ofSpecies.count { isMeasured(it) })
                    }
                    Progress(have, target)
                }
            }

            // Counted over the SELECTED trees only. `EveryKth` names
            // particular tally numbers, so a height on a tree the rule did
            // not pick does not close the gap on one it did: with k = 5 over
            // eleven trees, heights on #1–#3 are one of the three the rule
            // wants, not three of three, and #6 and #11 are still standing
            // unmeasured. Reading "3 of 3" there is the exact lie this
            // readout exists to prevent.
            is HeightSubsampleRule.AllTrees, is HeightSubsampleRule.EveryKth -> {
                val selected = live.filter {
                    shouldMeasureHeight(rule, it.treeNumber, it.speciesCode, live)
                }
                if (selected.isEmpty()) {
                    Progress(plainCount, 0)
                } else {
                    Progress(selected.count { isMeasured(it) }, selected.size)
                }
            }
        }
    }

    /// The rule's own notion of "this tree already has one": a MEASURED
    /// height. `PerSpeciesCount` counts exactly this, so the progress readout
    /// counts exactly this — an imputed height fills a volume, not a
    /// subsample slot, and must never make a plot read as done. In practice
    /// only the height flows write `heightM`, and they stamp
    /// `heightSource = "measured"` as they do; the tag is what separates them
    /// from imputed heights arriving through an import.
    private fun isMeasured(tree: Tree): Boolean =
        tree.heightM != null && tree.heightSource == "measured"
}
