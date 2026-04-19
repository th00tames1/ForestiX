// Spec §7.9 + REQ-AGG-001. Shared validation result type used by the plot-
// close flow and any future pure validators. Lives in Common/ so both
// InventoryEngine (rules) and UI (display) can import it without creating a
// circular dependency.

import Foundation

/// One validation issue produced by a pure validator. Immutable; purely
/// informational. The `code` is machine-stable (e.g., `"unknownSpecies"`);
/// `message` is the human-facing string shown in the summary list.
public struct ValidationIssue: Sendable, Equatable {
    public let code: String
    public let message: String
    public let affectedId: UUID?            // tree or plot id, if applicable

    public init(code: String, message: String, affectedId: UUID? = nil) {
        self.code = code
        self.message = message
        self.affectedId = affectedId
    }
}

/// Aggregated outcome of a plot-close (or other) validation run.
///
/// Spec §7.9 distinguishes **reject** (errors — block close) from **warn**
/// (advisory — don't block but surface). `canClose` is the contract the UI
/// uses to enable/disable the "Close plot" action.
public struct ValidationResult: Sendable, Equatable {
    public let errors: [ValidationIssue]
    public let warnings: [ValidationIssue]

    public init(errors: [ValidationIssue] = [], warnings: [ValidationIssue] = []) {
        self.errors = errors
        self.warnings = warnings
    }

    public var hasErrors: Bool { !errors.isEmpty }
    public var hasWarnings: Bool { !warnings.isEmpty }
    public var canClose: Bool { errors.isEmpty }

    public static let ok = ValidationResult()
}
