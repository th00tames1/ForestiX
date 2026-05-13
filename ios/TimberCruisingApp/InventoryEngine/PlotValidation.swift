// Spec §7.9 + REQ-AGG-001: pure plot-close validators. Called by
// PlotSummaryViewModel to decide whether a plot may be closed (errors) and
// what to surface as advisories (warnings).
//
// Pure functions only — no I/O, no dates, no randomness. All inputs passed
// explicitly so the whole thing is trivially unit-testable.

import Foundation
import Common
import Models

public enum PlotValidation {

    // MARK: - Issue codes (machine-stable)

    public enum Code {
        public static let noTrees            = "noTrees"
        public static let unknownSpecies     = "unknownSpecies"
        public static let dbhBelowMin        = "dbhBelowMin"
        public static let dbhAboveMax        = "dbhAboveMax"
        public static let redTierDbh         = "redTierDbh"
        public static let redTierHeight      = "redTierHeight"
        public static let missingHeightOnLive = "missingHeightOnLive"
    }

    /// Entry point. Returns errors that block close + warnings that don't.
    ///
    /// Rules:
    ///   • No live trees on the plot ⇒ warning (empty plot is valid in spec
    ///     §7.5 — zero observation — but worth flagging).
    ///   • Species code not in `speciesByCode` ⇒ **error** (unknown species
    ///     block closes because stats can't be computed).
    ///   • `dbhCm < species.expectedDbhMinCm` ⇒ warning (below merch /
    ///     expected range — may be a data-entry slip).
    ///   • `dbhCm > species.expectedDbhMaxCm` ⇒ warning.
    ///   • Any `dbhConfidence == .red` ⇒ warning.
    ///   • Any live tree with `heightConfidence == .red` ⇒ warning.
    ///   • Live tree with no `heightM` and no imputed source ⇒ warning
    ///     (volume will fall back to H-D imputation).
    ///
    /// Soft-deleted trees (`deletedAt != nil`) are ignored entirely.
    public static func validatePlotForClose(
        plot: Plot,
        trees: [Tree],
        speciesByCode: [String: SpeciesConfig]
    ) -> ValidationResult {
        var errors: [ValidationIssue] = []
        var warnings: [ValidationIssue] = []

        let liveTrees = trees.filter { $0.deletedAt == nil }

        if liveTrees.isEmpty {
            warnings.append(.init(
                code: Code.noTrees,
                message: "No trees tallied on this plot.",
                affectedId: plot.id))
        }

        for tree in liveTrees {
            guard let sp = speciesByCode[tree.speciesCode] else {
                errors.append(.init(
                    code: Code.unknownSpecies,
                    message: "Tree #\(tree.treeNumber): unknown species code '\(tree.speciesCode)'.",
                    affectedId: tree.id))
                continue
            }

            if tree.dbhCm < sp.expectedDbhMinCm {
                warnings.append(.init(
                    code: Code.dbhBelowMin,
                    message: """
                        Tree #\(tree.treeNumber): DBH \(formatted(tree.dbhCm)) cm \
                        below \(sp.commonName) expected minimum \(formatted(sp.expectedDbhMinCm)) cm.
                        """,
                    affectedId: tree.id))
            }
            if tree.dbhCm > sp.expectedDbhMaxCm {
                warnings.append(.init(
                    code: Code.dbhAboveMax,
                    message: """
                        Tree #\(tree.treeNumber): DBH \(formatted(tree.dbhCm)) cm \
                        above \(sp.commonName) expected maximum \(formatted(sp.expectedDbhMaxCm)) cm.
                        """,
                    affectedId: tree.id))
            }
            if tree.dbhConfidence == .red {
                warnings.append(.init(
                    code: Code.redTierDbh,
                    message: "Tree #\(tree.treeNumber): DBH measurement is red-tier.",
                    affectedId: tree.id))
            }
            if tree.status == .live, tree.heightConfidence == .red {
                warnings.append(.init(
                    code: Code.redTierHeight,
                    message: "Tree #\(tree.treeNumber): height measurement is red-tier.",
                    affectedId: tree.id))
            }
            if tree.status == .live, tree.heightM == nil, tree.heightSource != "imputed" {
                warnings.append(.init(
                    code: Code.missingHeightOnLive,
                    message: "Tree #\(tree.treeNumber): live tree has no height — volume will be imputed.",
                    affectedId: tree.id))
            }
        }

        return ValidationResult(errors: errors, warnings: warnings)
    }

    // MARK: - Formatting

    private static func formatted(_ v: Float) -> String {
        String(format: "%.1f", v)
    }
}
