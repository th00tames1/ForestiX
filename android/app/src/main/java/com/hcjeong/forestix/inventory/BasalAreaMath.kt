// Port of iOS InventoryEngine/BasalAreaMath.swift.
// Spec §7.6 Plot & Tree Computations. Pure functions; no sensor dependencies.
// REQ-TAL-005, REQ-AGG-002, REQ-AGG-003 consume these.

package com.hcjeong.forestix.inventory

import com.hcjeong.forestix.data.cruise.Plot
import com.hcjeong.forestix.data.cruise.Tree
import kotlin.math.PI
import kotlin.math.sqrt

// MARK: - Per-tree basal area

/// §7.6  BA = π · DBH² / 4, with DBH converted cm → m.
fun basalAreaM2(dbhCm: Float): Float {
    val dM = dbhCm / 100f
    return PI.toFloat() * dM * dM / 4f
}

// MARK: - Fixed-area plot statistics

/// §7.6  TPA = n_live · (1 / plot_area_acres). Soft-deleted trees excluded.
fun tpa(plot: Plot, trees: List<Tree>): Float {
    val ef = 1.0f / plot.plotAreaAcres
    return trees.count { it.deletedAt == null }.toFloat() * ef
}

/// §7.6  BA/ac = Σ BA_tree · EF. Units: m²/acre.
fun baPerAcre(plot: Plot, trees: List<Tree>): Float {
    val ef = 1.0f / plot.plotAreaAcres
    return trees
        .filter { it.deletedAt == null }
        .fold(0f) { acc, t -> acc + basalAreaM2(dbhCm = t.dbhCm) } * ef
}

/// §7.6  QMD = sqrt(Σ DBH² / n). cm.
fun qmd(trees: List<Tree>): Float {
    val live = trees.filter { it.deletedAt == null }
    if (live.isEmpty()) return 0f
    val sumSq = live.fold(0f) { acc, t -> acc + t.dbhCm * t.dbhCm }
    return sqrt(sumSq / live.size.toFloat())
}

// MARK: - Variable-radius (BAF) plot statistics

/// §7.6  tree-factor (trees per acre per "in" stem) = BAF / BA_tree.
fun treeFactorBAF(tree: Tree, baf: Float): Float {
    return baf / basalAreaM2(dbhCm = tree.dbhCm)
}

/// §7.6  BA/ac for a BAF plot = n_in · BAF.
fun baPerAcreBAF(trees: List<Tree>, baf: Float): Float {
    return trees.count { it.deletedAt == null }.toFloat() * baf
}
