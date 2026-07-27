// Port of iOS InventoryEngine/PlotValidation.swift.
// Spec §7.9 + REQ-AGG-001: pure plot-close validators. Called by
// the plot-summary view model to decide whether a plot may be closed
// (errors) and what to surface as advisories (warnings).
//
// Pure functions only — no I/O, no dates, no randomness. All inputs passed
// explicitly so the whole thing is trivially unit-testable.

package com.hcjeong.forestix.inventory

import com.hcjeong.forestix.data.cruise.Plot
import com.hcjeong.forestix.data.cruise.SpeciesConfig
import com.hcjeong.forestix.data.cruise.Tree
import com.hcjeong.forestix.data.cruise.TreeStatus
import com.hcjeong.forestix.sensors.ConfidenceTier
import java.util.Locale

object PlotValidation {

    // MARK: - Issue codes (machine-stable)

    object Code {
        const val noTrees = "noTrees"
        const val unknownSpecies = "unknownSpecies"
        const val dbhBelowMin = "dbhBelowMin"
        const val dbhAboveMax = "dbhAboveMax"
        const val redTierDbh = "redTierDbh"
        const val redTierHeight = "redTierHeight"
        const val missingHeightOnLive = "missingHeightOnLive"
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
    ///   • Any `dbhConfidence == RED` ⇒ warning.
    ///   • Any live tree with `heightConfidence == RED` ⇒ warning.
    ///   • Live tree with no `heightM` and no imputed source ⇒ warning
    ///     (volume will fall back to H-D imputation).
    ///
    /// Soft-deleted trees (`deletedAt != null`) are ignored entirely.
    fun validatePlotForClose(
        plot: Plot,
        trees: List<Tree>,
        speciesByCode: Map<String, SpeciesConfig>,
    ): ValidationResult {
        val errors = mutableListOf<ValidationIssue>()
        val warnings = mutableListOf<ValidationIssue>()

        val liveTrees = trees.filter { it.deletedAt == null }

        if (liveTrees.isEmpty()) {
            warnings.add(ValidationIssue(
                code = Code.noTrees,
                message = "No trees tallied on this plot.",
                affectedId = plot.id))
        }

        for (tree in liveTrees) {
            val sp = speciesByCode[tree.speciesCode]
            if (sp == null) {
                errors.add(ValidationIssue(
                    code = Code.unknownSpecies,
                    message = "Tree #${tree.treeNumber}: unknown species code '${tree.speciesCode}'.",
                    affectedId = tree.id))
                continue
            }

            if (tree.dbhCm < sp.expectedDbhMinCm) {
                warnings.add(ValidationIssue(
                    code = Code.dbhBelowMin,
                    message = "Tree #${tree.treeNumber}: DBH ${formatted(tree.dbhCm)} cm " +
                        "below ${sp.commonName} expected minimum ${formatted(sp.expectedDbhMinCm)} cm.",
                    affectedId = tree.id))
            }
            if (tree.dbhCm > sp.expectedDbhMaxCm) {
                warnings.add(ValidationIssue(
                    code = Code.dbhAboveMax,
                    message = "Tree #${tree.treeNumber}: DBH ${formatted(tree.dbhCm)} cm " +
                        "above ${sp.commonName} expected maximum ${formatted(sp.expectedDbhMaxCm)} cm.",
                    affectedId = tree.id))
            }
            if (tree.dbhConfidence == ConfidenceTier.RED) {
                warnings.add(ValidationIssue(
                    code = Code.redTierDbh,
                    message = "Tree #${tree.treeNumber}: the diameter reading is flagged Check — " +
                        "re-measure if you can.",
                    affectedId = tree.id))
            }
            if (tree.status == TreeStatus.LIVE && tree.heightConfidence == ConfidenceTier.RED) {
                warnings.add(ValidationIssue(
                    code = Code.redTierHeight,
                    message = "Tree #${tree.treeNumber}: the height reading is flagged Check — " +
                        "re-measure if you can.",
                    affectedId = tree.id))
            }
            if (tree.status == TreeStatus.LIVE && tree.heightM == null && tree.heightSource != "imputed") {
                warnings.add(ValidationIssue(
                    code = Code.missingHeightOnLive,
                    message = "Tree #${tree.treeNumber}: no height measured — the height will be " +
                        "estimated from this project's height curve.",
                    affectedId = tree.id))
            }
        }

        return ValidationResult(errors = errors, warnings = warnings)
    }

    // MARK: - Formatting

    private fun formatted(v: Float): String =
        String.format(Locale.US, "%.1f", v)
}
