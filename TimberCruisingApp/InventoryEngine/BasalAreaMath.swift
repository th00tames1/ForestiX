// Spec §7.6 Plot & Tree Computations. Pure functions; no sensor dependencies.
// REQ-TAL-005, REQ-AGG-002, REQ-AGG-003 consume these.

import Foundation
import Models

// MARK: - Per-tree basal area

/// §7.6  BA = π · DBH² / 4, with DBH converted cm → m.
public func basalAreaM2(dbhCm: Float) -> Float {
    let dM = dbhCm / 100
    return .pi * dM * dM / 4
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

/// §7.6  tree-factor (trees per acre per "in" stem) = BAF / BA_tree.
public func treeFactorBAF(tree: Tree, baf: Float) -> Float {
    return baf / basalAreaM2(dbhCm: tree.dbhCm)
}

/// §7.6  BA/ac for a BAF plot = n_in · BAF.
public func baPerAcreBAF(trees: [Tree], baf: Float) -> Float {
    return Float(trees.filter { $0.deletedAt == nil }.count) * baf
}
