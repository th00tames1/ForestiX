// GROUND-TRUTH INPUT — the ONE parser every "typed truth" field on either
// platform runs through (scan-screen dev block, raw-capture console).
//
// CROSS-PLATFORM: rules are identical to the Android sibling.
//   • ',' is accepted as the decimal separator and normalised to '.'.
//     A European/Korean numeric keypad emits ',' and a digits-only filter
//     silently turned "12,5" into 125 — a 10x corrupted ground truth.
//   • Whitespace is trimmed. An empty / unparseable field yields nil, which
//     callers MUST treat as "no value typed" — never as zero and never as
//     "clear the stored truth". Clearing a stored truth is always explicit.
//   • Plausibility windows: DBH 1–300 cm, height 1–120 m. A value outside
//     the window is still accepted (it is the operator's measurement) but
//     the caller shows `warning(...)` next to the field.

import Foundation

public enum TruthInput {

    // MARK: Parsing

    /// ',' → '.', whitespace trimmed. Safe to call on every keystroke.
    public static func normalized(_ raw: String) -> String {
        raw.replacingOccurrences(of: ",", with: ".")
           .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Parsed value, or nil when the field is empty / not a number.
    /// Locale-independent (`Double(_:)` on the normalised string).
    public static func parse(_ raw: String) -> Double? {
        let s = normalized(raw)
        guard !s.isEmpty else { return nil }
        return Double(s)
    }

    /// Parsed value that is also usable as a truth (finite and > 0).
    public static func parsePositive(_ raw: String) -> Double? {
        guard let v = parse(raw), v.isFinite, v > 0 else { return nil }
        return v
    }

    /// True when the field holds characters but doesn't parse — the state
    /// that must NEVER overwrite (or silently discard) a stored truth.
    public static func isUnparseable(_ raw: String) -> Bool {
        !normalized(raw).isEmpty && parsePositive(raw) == nil
    }

    // MARK: Plausibility windows

    public static let dbhMinCm: Double = 1
    public static let dbhMaxCm: Double = 300
    public static let heightMinM: Double = 1
    public static let heightMaxM: Double = 120

    /// Warning for a typed DBH truth, or nil when it is inside 1–300 cm.
    public static func dbhWarning(cm: Double) -> String? {
        (cm < dbhMinCm || cm > dbhMaxCm)
            ? "Outside 1–300 cm — check the value" : nil
    }

    /// Warning for a typed height truth, or nil when inside 1–120 m.
    public static func heightWarning(m: Double) -> String? {
        (m < heightMinM || m > heightMaxM)
            ? "Outside 1–120 m — check the value" : nil
    }

    /// Kind-dispatched convenience for the shared truth console.
    public static func warning(value: Double, isHeight: Bool) -> String? {
        isHeight ? heightWarning(m: value) : dbhWarning(cm: value)
    }
}
