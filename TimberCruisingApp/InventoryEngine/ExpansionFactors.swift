// Spec §7.6 + §1 Glossary.
//
// Expansion factor (EF):
//   - fixed-area:     EF = 1 / plot_area_acres  (trees per acre per tree)
//   - variable-radius (BAF): per-tree EF = BAF / BA_tree   (trees per acre
//     per "in" tree).
//
// These functions are shared between the live plot-tally display and the
// stand aggregation pipeline.

import Foundation
import Models

public enum ExpansionFactors {

    /// §7.6 Fixed-area plot expansion factor: trees/acre per tallied tree.
    /// Input: plot area in acres. Output: expansion factor.
    public static func fixedArea(plotAreaAcres: Float) -> Float {
        precondition(plotAreaAcres > 0, "plotAreaAcres must be > 0")
        return 1 / plotAreaAcres
    }

    /// §7.6 BAF expansion factor for a single tree: trees/acre per "in" tree.
    /// EF_i = BAF / BA_i.
    public static func variableRadius(baf: Float, dbhCm: Float) -> Float {
        precondition(baf > 0, "baf must be > 0")
        let ba = basalAreaM2(dbhCm: dbhCm)
        precondition(ba > 0, "DBH must be > 0")
        return baf / ba
    }

    /// Convenience: per-acre attribute total for a fixed-area plot.
    /// `attr(tree)` returns the per-tree value (e.g., volume, BA, count=1).
    public static func perAcreFixedArea<T>(
        trees: [Tree],
        plotAreaAcres: Float,
        attr: (Tree) -> T
    ) -> T where T: AdditiveArithmetic {
        let live = trees.filter { $0.deletedAt == nil }
        let sum = live.reduce(T.zero) { $0 + attr($1) }
        // scale by EF (constant across all trees in fixed-area plot)
        return scale(sum, by: fixedArea(plotAreaAcres: plotAreaAcres))
    }

    /// Convenience: per-acre attribute total for a BAF plot (each tree has
    /// its own EF).
    public static func perAcreBAF(
        trees: [Tree],
        baf: Float,
        attr: (Tree) -> Float
    ) -> Float {
        let live = trees.filter { $0.deletedAt == nil }
        return live.reduce(0) { $0 + attr($1) * variableRadius(baf: baf, dbhCm: $1.dbhCm) }
    }

    // Float-only scale helper because AdditiveArithmetic has no multiplication.
    private static func scale<T>(_ value: T, by factor: Float) -> T where T: AdditiveArithmetic {
        if let v = value as? Float, let r = (v * factor) as? T { return r }
        if let v = value as? Double, let r = (v * Double(factor)) as? T { return r }
        if let v = value as? Int {
            // Rare: callers should pass Float/Double; keep behavior predictable.
            if let r = Int(Float(v) * factor) as? T { return r }
        }
        return value
    }
}
