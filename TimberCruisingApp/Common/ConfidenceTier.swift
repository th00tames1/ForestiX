// Spec §6.1 (enum ConfidenceTier) + §7.9 (Confidence Framework).
// Authoritative location per user decision: enum lives in Common/ so that
// both Models/ and InventoryEngine/ (and later Sensors/) can import it.

import Foundation

/// §6.1 Confidence tier attached to every measurement.
public enum ConfidenceTier: String, Codable, Sendable, CaseIterable {
    case green
    case yellow
    case red
}

/// §7.9 Severity for a single quality check.
public enum Severity: Sendable {
    case reject
    case warn
}

/// §7.9 One quality check applied to a measurement.
public struct Check: Sendable {
    public let passed: Bool
    public let severity: Severity
    public let reason: String

    public init(passed: Bool, severity: Severity, reason: String) {
        self.passed = passed
        self.severity = severity
        self.reason = reason
    }
}

/// §7.9 Combine checks into a tier.
///
/// Rules (spec §7.9):
///   - any failed `.reject` check ⇒ `.red`
///   - ≥ 2 failed `.warn` checks  ⇒ `.red`
///   - ≥ 1 failed `.warn` check   ⇒ `.yellow`
///   - otherwise                  ⇒ `.green`
public func combineChecks(_ checks: [Check]) -> ConfidenceTier {
    let rejectFail = checks.contains { !$0.passed && $0.severity == .reject }
    if rejectFail { return .red }
    let warnCount = checks.filter { !$0.passed && $0.severity == .warn }.count
    if warnCount >= 2 { return .red }
    if warnCount >= 1 { return .yellow }
    return .green
}

/// Convenience builder used by pseudocode in §7.1 Step 9.
public func check(_ passed: Bool, sev: Severity, reason: String = "") -> Check {
    Check(passed: passed, severity: sev, reason: reason)
}
