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
//     the caller shows `warning(...)` next to the field. The window is judged
//     on the metric base and WORDED in the unit the field is typed in.
//   • The UNIT a truth was typed in belongs to the entry, not to the reader.
//     See the `Unit` section below.

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

    // MARK: What is being typed

    /// The quantity a truth field stands for. It picks the unit pair, the
    /// label and the plausibility window in one place, so those three can
    /// never disagree about what the field holds.
    public enum Quantity: Sendable {
        case diameter      // base cm
        case height        // base m
        case distance      // base m
    }

    // MARK: The unit the operator typed in

    /// The unit a typed truth is IN, recorded with the value (manifest
    /// `truth_unit`, research CSV `truth_unit`).
    ///
    /// The stored number stays in the app's metric base, so after the fact
    /// nothing in an export says whether a 12.5 was typed as centimetres or
    /// converted from inches — and a field labelled in metric while the
    /// cruiser worked in imperial is how a day of analysis was lost. Recording
    /// what was typed makes the reconciliation arithmetic instead of guesswork.
    public enum Unit: String, Sendable {
        case cm
        case inches = "in"
        case meters = "m"
        case feet = "ft"
    }

    /// The unit a FRESH entry starts in: the cruiser's ACTIVE system, never a
    /// fixed metric default.
    public static func defaultUnit(_ quantity: Quantity, imperial: Bool) -> Unit {
        switch quantity {
        case .diameter: return imperial ? .inches : .cm
        case .height, .distance: return imperial ? .feet : .meters
        }
    }

    /// The other unit for the same quantity — what the per-entry toggle picks.
    public static func toggled(_ unit: Unit) -> Unit {
        switch unit {
        case .cm: return .inches
        case .inches: return .cm
        case .meters: return .feet
        case .feet: return .meters
        }
    }

    /// A typed value converted to the app's metric base (cm for a diameter,
    /// m for a height or distance). The ONE place that conversion happens —
    /// every truth field routes through here rather than converting at the
    /// call site, because a call site that forgets is silently 2.54x wrong.
    public static func toBase(_ value: Double, unit: Unit) -> Double {
        switch unit {
        case .cm, .meters: return value
        case .inches: return Units.inchesToCm(value)
        case .feet: return Units.feetToMeters(value)
        }
    }

    /// A stored base value expressed back in `unit` — for putting a value the
    /// app is handing back into the field it was typed in.
    public static func fromBase(_ base: Double, unit: Unit) -> Double {
        switch unit {
        case .cm, .meters: return base
        case .inches: return Units.cmToInches(base)
        case .feet: return Units.metersToFeet(base)
        }
    }

    /// Parse and convert in one step: the base value a typed field means, or
    /// nil when nothing usable was typed.
    public static func parsePositiveBase(_ raw: String, unit: Unit) -> Double? {
        guard let v = parsePositive(raw) else { return nil }
        return toBase(v, unit: unit)
    }

    /// Compact label for the scan panels' inline field — carries the unit, so
    /// what is typed and what is read can never disagree.
    public static func fieldLabel(_ quantity: Quantity, unit: Unit) -> String {
        switch quantity {
        case .diameter: return "True Ø (\(unit.rawValue))"
        case .height: return "True H (\(unit.rawValue))"
        case .distance: return "True (\(unit.rawValue))"
        }
    }

    /// Longer label for the raw-capture console's field placeholder.
    public static func promptLabel(_ quantity: Quantity, unit: Unit) -> String {
        switch quantity {
        case .diameter: return "True Ø (\(unit.rawValue))"
        case .height: return "True height (\(unit.rawValue))"
        case .distance: return "True distance (\(unit.rawValue))"
        }
    }

    // MARK: Plausibility windows

    public static let dbhMinCm: Double = 1
    public static let dbhMaxCm: Double = 300
    public static let heightMinM: Double = 1
    public static let heightMaxM: Double = 120

    /// A window bound written in the unit the FIELD is in: converted from the
    /// metric base, rounded to one decimal and trailing zeros trimmed. The
    /// metric bounds therefore still render exactly "1", "300" and "120".
    private static func boundText(_ base: Double, unit: Unit) -> String {
        let v = fromBase(base, unit: unit)
        return text((v * 10).rounded() / 10)
    }

    /// Warning for a typed DBH truth, or nil when it is inside 1–300 cm.
    ///
    /// `unit` is the unit the FIELD is in. The window is ONE physical range
    /// and the judgement is made on the converted base either way, but the
    /// sentence has to be denominated in what the cruiser typed: reading
    /// "Outside 1–300 cm" under a field labelled "True Ø (in)" is the exact
    /// cm/in confusion this screen exists to prevent.
    public static func dbhWarning(cm: Double, unit: Unit) -> String? {
        guard cm < dbhMinCm || cm > dbhMaxCm else { return nil }
        let lo = boundText(dbhMinCm, unit: unit)
        let hi = boundText(dbhMaxCm, unit: unit)
        return "Outside \(lo)–\(hi) \(unit.rawValue) — check the value"
    }

    /// Warning for a typed height truth, or nil when inside 1–120 m. Same
    /// rule as `dbhWarning`: judged in the base, worded in the field's unit.
    public static func heightWarning(m: Double, unit: Unit) -> String? {
        guard m < heightMinM || m > heightMaxM else { return nil }
        let lo = boundText(heightMinM, unit: unit)
        let hi = boundText(heightMaxM, unit: unit)
        return "Outside \(lo)–\(hi) \(unit.rawValue) — check the value"
    }

    /// Quantity-dispatched window check. The value is the METRIC BASE, so the
    /// window is stated once and an imperial entry is judged by the same
    /// numbers as a metric one; `unit` only decides how the bounds are
    /// WRITTEN. A distance has no cruising window.
    public static func warning(base: Double, quantity: Quantity,
                               unit: Unit) -> String? {
        switch quantity {
        case .diameter: return dbhWarning(cm: base, unit: unit)
        case .height: return heightWarning(m: base, unit: unit)
        case .distance: return nil
        }
    }

    /// The inline warning a truth field shows for its CURRENT text and unit:
    /// "Not a number" for unparseable input, the plausibility warning for an
    /// out-of-window value, nil otherwise.
    public static func fieldWarning(_ raw: String,
                                    quantity: Quantity,
                                    unit: Unit) -> String? {
        if isUnparseable(raw) { return "Not a number" }
        guard let base = parsePositiveBase(raw, unit: unit) else { return nil }
        return warning(base: base, quantity: quantity, unit: unit)
    }

    /// A value put BACK into a truth field, rendered in the unit it was typed
    /// in — used when a queued truth could not be stored and the operator must
    /// see the number again instead of losing it. Trailing zeros are trimmed
    /// so "12.5" round-trips as typed.
    public static func text(base: Double, unit: Unit) -> String {
        text(fromBase(base, unit: unit))
    }

    /// The same rendering for a value already in the unit it will be shown in.
    public static func text(_ value: Double) -> String {
        var s = String(format: "%.4f", value)
        while s.hasSuffix("0") { s.removeLast() }
        if s.hasSuffix(".") { s.removeLast() }
        return (s.isEmpty || s == "-") ? "0" : s
    }
}
