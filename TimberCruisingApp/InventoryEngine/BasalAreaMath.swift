// Spec §7.6 Plot & Tree Computations. Pure functions; no sensor dependencies.
// REQ-TAL-005, REQ-AGG-002, REQ-AGG-003 consume these.

import Foundation
import Common
import Models

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
public func basalAreaM2(dbhCm: Float) -> Float {
    let dM = dbhCm / 100
    return .pi * dM * dM / 4
}

/// The same per-tree basal area in SQUARE FEET — the system a BAF is
/// denominated in, and the only correct denominator under one.
///
/// Equivalent to the US cruising identity BA = 0.005454 · DBH_in², which is
/// what the app's own reference page prints; this derives it from the metric
/// area instead of re-hardcoding the constant, so the two can never drift.
public func basalAreaFt2(dbhCm: Float) -> Float {
    Float(Units.squareMetersToSquareFeet(Double(basalAreaM2(dbhCm: dbhCm))))
}

// MARK: - Fixed-area plot statistics

/// §7.6  TPA = n_live · (1 / plot_area_acres). Soft-deleted trees excluded.
public func tpa(plot: Plot, trees: [Tree]) -> Float {
    let ef = 1.0 / plot.plotAreaAcres
    return Float(trees.filter { $0.deletedAt == nil }.count) * ef
}

/// §7.6  BA/ac = Σ BA_tree · EF. Units: m²/acre.
public func baPerAcre(plot: Plot, trees: [Tree]) -> Float {
    let ef = 1.0 / plot.plotAreaAcres
    return trees
        .filter { $0.deletedAt == nil }
        .reduce(0) { $0 + basalAreaM2(dbhCm: $1.dbhCm) } * ef
}

/// §7.6  QMD = sqrt(Σ DBH² / n). cm.
public func qmd(trees: [Tree]) -> Float {
    let live = trees.filter { $0.deletedAt == nil }
    guard !live.isEmpty else { return 0 }
    let sumSq = live.reduce(0) { $0 + $1.dbhCm * $1.dbhCm }
    return sqrt(sumSq / Float(live.count))
}

// MARK: - Variable-radius (BAF) plot statistics

/// §7.6  tree-factor (trees per acre per "in" stem) = BAF / BA_tree, both
/// sides in square feet. `baf` is ft²/ac (see the note at the top).
public func treeFactorBAF(tree: Tree, baf: Float) -> Float {
    return baf / basalAreaFt2(dbhCm: tree.dbhCm)
}

/// §7.6  BA/ac for a BAF plot = n_in · BAF.
///
/// RETURNS m²/ACRE — the same unit as `baPerAcre` above, so `PlotStats
/// .baPerAcreM2` means one thing whichever plot type filled it. The identity
/// is n · BAF in ft²/ac, and the conversion is the last step rather than the
/// caller's problem: this field feeds the plot summary, the stand roll-up and
/// the PDF, none of which know which plot type produced it.
public func baPerAcreBAF(trees: [Tree], baf: Float) -> Float {
    let n = Float(trees.filter { $0.deletedAt == nil }.count)
    return Float(Units.squareFeetToSquareMeters(Double(n * baf)))
}
