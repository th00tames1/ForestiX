// Port of iOS InventoryEngine/BasalAreaMath.swift.
// Spec §7.6 Plot & Tree Computations. Pure functions; no sensor dependencies.
// REQ-TAL-005, REQ-AGG-002, REQ-AGG-003 consume these.

package com.hcjeong.forestix.inventory

import com.hcjeong.forestix.common.Units
import com.hcjeong.forestix.data.cruise.Plot
import com.hcjeong.forestix.data.cruise.Tree
import kotlin.math.PI
import kotlin.math.sqrt

// THE TWO UNITS IN THIS FILE, AND WHY THEY ARE NOT THE SAME ONE.
//
// A per-tree basal area is in SQUARE METRES, because a DBH is stored in
// centimetres and the whole app's linear base is metric.
//
// A BAF is in SQUARE FEET PER ACRE. It is not a measurement — it is the
// number etched on the cruiser's prism, and every prism in the field and
// every BAF a project has ever stored (the setup sheet's default is 20)
// carries the US convention. `CruiseDesign.baf` therefore holds ft²/ac,
// stated there and honoured here; the setup sheet converts a metric
// cruiser's m²/ha on the way in and back out again.
//
// So a BAF expansion factor MUST divide square feet by square feet. It used
// to divide the ft²/ac BAF by the m² tree area, which is not a unit error
// that shows up as an odd-looking number — it comes out 10.76× too high and
// still looks like a plausible stand, on screen, in the CSV and in the
// client PDF. Every divide below goes through `basalAreaFt2`.

// MARK: - Per-tree basal area

/// §7.6  BA = π · DBH² / 4, with DBH converted cm → m.
fun basalAreaM2(dbhCm: Float): Float {
    val dM = dbhCm / 100f
    return PI.toFloat() * dM * dM / 4f
}

/// The same per-tree basal area in SQUARE FEET — the system a BAF is
/// denominated in, and the only correct denominator under one.
///
/// Equivalent to the US cruising identity BA = 0.005454 · DBH_in², which is
/// what the app's own reference page prints; this derives it from the metric
/// area instead of re-hardcoding the constant, so the two can never drift.
fun basalAreaFt2(dbhCm: Float): Float =
    Units.squareMetersToSquareFeet(basalAreaM2(dbhCm = dbhCm).toDouble()).toFloat()

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

/// §7.6  tree-factor (trees per acre per "in" stem) = BAF / BA_tree, both
/// sides in square feet. `baf` is ft²/ac (see the note at the top).
fun treeFactorBAF(tree: Tree, baf: Float): Float {
    return baf / basalAreaFt2(dbhCm = tree.dbhCm)
}

/// §7.6  BA/ac for a BAF plot = n_in · BAF.
///
/// RETURNS m²/ACRE — the same unit as `baPerAcre` above, so `PlotStats
/// .baPerAcreM2` means one thing whichever plot type filled it. The identity
/// is n · BAF in ft²/ac, and the conversion is the last step rather than the
/// caller's problem: this field feeds the plot summary, the stand roll-up and
/// the PDF, none of which know which plot type produced it.
fun baPerAcreBAF(trees: List<Tree>, baf: Float): Float {
    val n = trees.count { it.deletedAt == null }.toFloat()
    return Units.squareFeetToSquareMeters((n * baf).toDouble()).toFloat()
}
