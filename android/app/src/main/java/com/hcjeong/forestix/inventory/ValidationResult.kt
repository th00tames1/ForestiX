// Port of iOS Common/ValidationResult.swift (dependency of PlotValidation).
// Spec §7.9 + REQ-AGG-001. Shared validation result type used by the plot-
// close flow and any future pure validators. iOS keeps this in Common/ so
// both InventoryEngine (rules) and UI (display) can import it; on Android it
// lives alongside the rules that produce it.

package com.hcjeong.forestix.inventory

import java.util.UUID

/// One validation issue produced by a pure validator. Immutable; purely
/// informational. The `code` is machine-stable (e.g., `"unknownSpecies"`);
/// `message` is the human-facing string shown in the summary list.
data class ValidationIssue(
    val code: String,
    val message: String,
    val affectedId: UUID? = null,           // tree or plot id, if applicable
)

/// Aggregated outcome of a plot-close (or other) validation run.
///
/// Spec §7.9 distinguishes **reject** (errors — block close) from **warn**
/// (advisory — don't block but surface). `canClose` is the contract the UI
/// uses to enable/disable the "Close plot" action.
data class ValidationResult(
    val errors: List<ValidationIssue> = emptyList(),
    val warnings: List<ValidationIssue> = emptyList(),
) {
    val hasErrors: Boolean get() = errors.isNotEmpty()
    val hasWarnings: Boolean get() = warnings.isNotEmpty()
    val canClose: Boolean get() = errors.isEmpty()

    companion object {
        val ok = ValidationResult()
    }
}
