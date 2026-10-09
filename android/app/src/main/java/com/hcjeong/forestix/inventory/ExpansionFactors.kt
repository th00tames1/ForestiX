// Port of iOS InventoryEngine/ExpansionFactors.swift.
// Spec §7.6 + §1 Glossary.
//
// Expansion factor (EF):
//   - fixed-area:     EF = 1 / plot_area_acres  (trees per acre per tree)
//   - variable-radius (BAF): per-tree EF = BAF / BA_tree   (trees per acre
//     per "in" tree), with BAF and BA_tree BOTH in square feet.
//
// These functions are shared between the live plot-tally display and the
// stand aggregation pipeline.

package com.hcjeong.forestix.inventory

import com.hcjeong.forestix.data.cruise.Tree

object ExpansionFactors {

    /// §7.6 Fixed-area plot expansion factor: trees/acre per tallied tree.
    /// Input: plot area in acres. Output: expansion factor.
    fun fixedArea(plotAreaAcres: Float): Float {
        require(plotAreaAcres > 0f) { "plotAreaAcres must be > 0" }
        return 1f / plotAreaAcres
    }

    /// §7.6 BAF expansion factor for a single tree: trees/acre per "in" tree.
    /// EF_i = BAF / BA_i.
    ///
    /// BOTH SIDES IN SQUARE FEET. `baf` is ft²/ac — see the unit note at the
    /// top of `BasalAreaMath.kt` — so the tree's area comes from
    /// `basalAreaFt2`, not the metric `basalAreaM2` this used to divide by.
    /// The ratio of the two is 10.76, and it lands on every TPA, basal area
    /// and volume a prism plot reports.
    fun variableRadius(baf: Float, dbhCm: Float): Float {
        require(baf > 0f) { "baf must be > 0" }
        val ba = basalAreaFt2(dbhCm = dbhCm)
        require(ba > 0f) { "DBH must be > 0" }
        return baf / ba
    }

    /// Convenience: per-acre attribute total for a fixed-area plot.
    /// `attr(tree)` returns the per-tree value (e.g., volume, BA, count=1).
    /// (Swift is generic over AdditiveArithmetic; specialized to Float here —
    /// every call site uses Float.)
    fun perAcreFixedArea(
        trees: List<Tree>,
        plotAreaAcres: Float,
        attr: (Tree) -> Float,
    ): Float {
        val live = trees.filter { it.deletedAt == null }
        val sum = live.fold(0f) { acc, t -> acc + attr(t) }
        // scale by EF (constant across all trees in fixed-area plot)
        return sum * fixedArea(plotAreaAcres = plotAreaAcres)
    }

    /// Convenience: per-acre attribute total for a BAF plot (each tree has
    /// its own EF).
    fun perAcreBAF(
        trees: List<Tree>,
        baf: Float,
        attr: (Tree) -> Float,
    ): Float {
        val live = trees.filter { it.deletedAt == null }
        return live.fold(0f) { acc, t ->
            acc + attr(t) * variableRadius(baf = baf, dbhCm = t.dbhCm)
        }
    }
}
