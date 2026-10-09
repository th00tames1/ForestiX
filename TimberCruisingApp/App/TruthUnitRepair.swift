// GROUND-TRUTH UNIT REPAIR — re-base tape values that were typed in imperial
// and stored as if the digits were already the metric base.
//
// CROSS-PLATFORM: selection rule, provenance stamps, persisted keys and every
// user-visible string are byte-identical to the Android sibling
// (data/TruthUnitRepair.kt).
//
// WHAT WENT WRONG. The ground-truth field had no unit toggle. The cruiser was
// working from an imperial tape — inches on the diameter, feet on the height —
// and typed those digits into a field the app labelled and stored in metric.
// A stem taped at 27 in went into the corpus as 27 cm.
//
// HOW AN AFFECTED VALUE IS RECOGNISED. By the ABSENCE OF A RECORDED UNIT, and
// by nothing else. The commit that gave the truth field its per-entry unit
// toggle is the same commit that started recording `truth_unit` on the
// manifest, `truth_unit` in the research CSV, and a truth on the reading at
// all. So:
//
//   • A truth carrying a unit was typed with the toggle on screen. The cruiser
//     said what they meant and the stored base is already right. Never touched.
//   • A truth carrying NO unit was typed before the toggle existed. Every
//     writer that can record a unit does record one — `RawCaptureStore
//     .applyTruth` takes a non-optional unit, and the scan screens and the
//     raw-captures console are its only callers — so an unstamped truth cannot
//     have come from a build that had the toggle.
//   • On a READING the same absence is not enough on its own, because the
//     reading has never carried a unit for a hand-typed truth. The extra
//     condition is `truth_source = capture`: a truth the cruiser typed onto a
//     reading was necessarily typed in a build that had the toggle (the field
//     and the toggle landed together), so only a value COPIED from a manifest
//     can be carrying pre-toggle digits. That manifest is then read directly
//     rather than assumed — see `plan`.
//
// WHAT IS DELIBERATELY NOT PART OF THE RULE. `settings.units` on the manifest,
// which says which system the app was DISPLAYING. Measured against the
// cruiser's own corpus it carries no information about what was typed: in the
// session of 2026-07-27 the app was in metric for every capture, and the
// diameter truths there still sit at about 1/2.54 of what the app measured and
// the heights at about 1/0.3048. What the app displayed and what the cruiser
// wrote on the tape were simply different things.
//
// THE VALUE IS NEVER DOUBLED. 27 cm and 68.58 cm are both ordinary stems, so
// the correction cannot be read back out of the number. Every repair therefore
// WRITES THE UNIT it re-based into — `truth_unit` on the manifest and in the
// research CSV, `truthUnit` on the reading — which is exactly the marker the
// rule selects on. A repaired value no longer matches the rule that chose it,
// on any device, in any order, without a version flag.

import Foundation
import Common
import Sensors

public enum TruthUnitRepair {

    // MARK: - What is being re-based

    /// The two quantities the defect was confirmed for. Distance truths are
    /// NOT here: the cruiser's corpus has no evidence about what unit a
    /// distance was typed in, and a repair with no evidence behind it is a
    /// guess written into research data.
    public enum Quantity: String, Sendable, Equatable {
        case diameter
        case height

        /// The unit the digits were really in.
        public var typedUnit: TruthInput.Unit {
            self == .diameter ? .inches : .feet
        }

        /// The app's metric base for this quantity — what the digits were
        /// wrongly stored as.
        public var baseUnitText: String {
            self == .diameter ? "cm" : "m"
        }

        /// `measure_type` in the research CSV and `kind` on a manifest.
        public var rawKind: String {
            self == .diameter ? "dbh" : "height"
        }

        public static func of(_ kind: QuickMeasureEntry.Kind) -> Quantity? {
            switch kind {
            case .dbh:    return .diameter
            case .height: return .height
            case .crown, .distance, .samplingPlot: return nil
            }
        }

        public static func ofRawKind(_ raw: String) -> Quantity? {
            switch raw {
            case "dbh":    return .diameter
            case "height": return .height
            default:       return nil
            }
        }
    }

    /// The corrected base for digits that were typed in `quantity`'s imperial
    /// unit. Routed through `TruthInput.toBase` rather than multiplying here,
    /// because that is the ONE place a typed truth becomes a base value and a
    /// second copy of 2.54 is how the two would eventually disagree.
    public static func repaired(_ typed: Double, _ quantity: Quantity) -> Double {
        TruthInput.toBase(typed, unit: quantity.typedUnit)
    }

    /// Two truth values are the SAME value inside this band — `TruthBackfill
    /// .valueEpsilon` by the same reasoning: every writer puts the metric base
    /// through one parser, so the only difference between two copies of a
    /// truth is float round-trip.
    static let valueEpsilon: Double = 0.001

    // MARK: - One planned change

    public struct Change: Sendable, Equatable {
        public let quantity: Quantity
        /// The digits as the cruiser typed them (and as they are stored now).
        public let before: Double
        /// The metric base those digits meant.
        public let after: Double
        /// Which store this row lives in — "reading" | "capture" | "log".
        public let store: String
        /// Reading id, capture id, or the research row's tree id. Empty when
        /// the row names nothing.
        public let key: String

        public init(quantity: Quantity, before: Double, after: Double,
                    store: String, key: String) {
            self.quantity = quantity
            self.before = before
            self.after = after
            self.store = store
            self.key = key
        }

        /// "27 -> 68.6 cm (27.0 in)". The digits on the left are rendered the
        /// way the field would have shown them back (trailing zeros trimmed);
        /// both converted numbers are one decimal, which is the precision the
        /// cruiser reads a tape to.
        public var exampleLine: String {
            let base = String(format: "%.1f", after)
            let typed = String(format: "%.1f", before)
            return TruthInput.text(before) + " -> " + base + " "
                + quantity.baseUnitText + " (" + typed + " "
                + quantity.typedUnit.rawValue + ")"
        }
    }

    /// Why a truth the sweep SAW is being left alone. Every truth in every
    /// store lands in exactly one of these or in the change list, so the
    /// numbers the cruiser is shown add up to what is on the device.
    public struct Kept: Sendable, Equatable {
        /// A unit was recorded with it — entered knowingly, already right.
        public var unitRecorded = 0
        /// Typed onto the reading itself, which was only possible from the
        /// build that had the unit toggle.
        public var typedOnReading = 0
        /// Capture-recovered, but no unit-less capture on this device carries
        /// that value — so there is nothing to prove it predates the toggle.
        public var unverifiable = 0
        /// A distance row. The defect was confirmed for diameter and height.
        public var otherQuantity = 0

        public var total: Int {
            unitRecorded + typedOnReading + unverifiable + otherQuantity
        }
    }

    /// What a run WOULD do, computed before anything is written.
    public struct Plan: Sendable {
        /// reading id → the change to apply.
        public var readings: [UUID: Change] = [:]
        /// capture id → the change to apply.
        public var captures: [String: Change] = [:]
        /// Research-log rows, in file order. Applied by re-deriving them
        /// inside the log's own queue, not by index.
        public var logRows: [Change] = []
        public var kept = Kept()

        public var readingDiameters: Int { count(readings.values, .diameter) }
        public var readingHeights: Int { count(readings.values, .height) }
        public var captureDiameters: Int { count(captures.values, .diameter) }
        public var captureHeights: Int { count(captures.values, .height) }
        public var logDiameters: Int { count(logRows, .diameter) }
        public var logHeights: Int { count(logRows, .height) }

        public var isEmpty: Bool {
            readings.isEmpty && captures.isEmpty && logRows.isEmpty
        }

        private func count<S: Sequence>(_ s: S, _ q: Quantity) -> Int
        where S.Element == Change {
            s.reduce(0) { $0 + ($1.quantity == q ? 1 : 0) }
        }

        /// Up to four worked examples — two diameters and two heights, taken
        /// in a stable order so the same corpus previews the same lines every
        /// time. Captures first because every affected value has one.
        public var examples: [String] {
            var all: [Change] = captures.sorted { $0.key < $1.key }.map(\.value)
            all += readings.sorted { $0.key.uuidString < $1.key.uuidString }
                .map(\.value)
            all += logRows
            var out: [String] = []
            for q in [Quantity.diameter, Quantity.height] {
                var taken = 0
                for c in all where c.quantity == q && taken < 2 {
                    let line = c.exampleLine
                    guard !out.contains(line) else { continue }
                    out.append(line)
                    taken += 1
                }
            }
            return out
        }
    }

    // MARK: - The rule

    /// One kind on one tree — how a reading is tied back to the captures that
    /// could have supplied its truth.
    private struct GroupKey: Hashable {
        let quantity: Quantity
        let tree: Int
    }

    /// Work out what will change, changing nothing.
    ///
    /// CAPTURES. A bundle is affected when it holds a truth and records no
    /// `truth_unit`. Cruise-mode bundles are included — unlike `TruthBackfill`,
    /// which skips them because it is deciding which READING a truth belongs
    /// to. Nothing is being decided here: the field being corrected is the
    /// bundle's own, and a cruise capture's tape number was typed into the same
    /// unit-less field as every other.
    ///
    /// READINGS. A reading is affected when it holds a truth, records no unit,
    /// and carries `truth_source = capture` — the only way pre-toggle digits
    /// can reach a reading. That is still not taken on trust: at least one
    /// capture of the same kind and tree must hold that same value with NO
    /// unit, and no capture of that kind and tree may hold it WITH one. So a
    /// truth typed into the raw-captures console today (which records a unit)
    /// and matched onto a reading by the recovery pass is recognised as correct
    /// and left alone, and a reading whose capture has been deleted is left
    /// alone as well, because nothing on the device can say what it was.
    ///
    /// RESEARCH LOG. Rows are selected by the log itself (see `ResearchLog
    /// .repairImperialTruths`), which owns its file and its queue; the rows it
    /// reports are folded into the same plan so one preview covers all three.
    public static func plan(summaries: [RawCaptureSummary],
                            entries: [QuickMeasureEntry],
                            logRows: [Change],
                            logKept: Kept) -> Plan {
        var plan = Plan()
        plan.kept = logKept
        plan.logRows = logRows

        // Captures, and the two lookups the reading rule needs.
        var unitless: [GroupKey: [Double]] = [:]
        var stamped: [GroupKey: [Double]] = [:]
        for s in summaries {
            guard let value = s.manifest.truth.value, value.isFinite, value > 0
            else { continue }
            guard let q = Quantity.ofRawKind(s.manifest.kind) else {
                plan.kept.otherQuantity += 1
                continue
            }
            if let tree = s.manifest.context.treeNumber {
                let key = GroupKey(quantity: q, tree: tree)
                if s.manifest.truth.truthUnit == nil {
                    unitless[key, default: []].append(value)
                } else {
                    stamped[key, default: []].append(value)
                }
            }
            guard s.manifest.truth.truthUnit == nil else {
                plan.kept.unitRecorded += 1
                continue
            }
            plan.captures[s.id] = Change(quantity: q, before: value,
                                         after: repaired(value, q),
                                         store: "capture", key: s.id)
        }

        for e in entries {
            guard let truth = e.truth, truth.isFinite, truth > 0 else { continue }
            guard let q = Quantity.of(e.kind) else {
                plan.kept.otherQuantity += 1
                continue
            }
            guard e.truthUnit == nil else {
                plan.kept.unitRecorded += 1
                continue
            }
            guard e.truthSource == QuickMeasureEntry.TruthSource.capture.rawValue
            else {
                plan.kept.typedOnReading += 1
                continue
            }
            guard let tree = e.treeNumber else {
                plan.kept.unverifiable += 1
                continue
            }
            let key = GroupKey(quantity: q, tree: tree)
            let matches: (Double) -> Bool = { abs($0 - truth) <= valueEpsilon }
            // A stamped capture holding this value is proof the value was
            // entered knowingly. It wins over an unstamped one: the rule may
            // only fire when NOTHING on the device says a unit was given.
            if (stamped[key] ?? []).contains(where: matches) {
                plan.kept.unitRecorded += 1
                continue
            }
            guard (unitless[key] ?? []).contains(where: matches) else {
                plan.kept.unverifiable += 1
                continue
            }
            plan.readings[e.id] = Change(quantity: q, before: truth,
                                         after: repaired(truth, q),
                                         store: "reading",
                                         key: e.id.uuidString)
        }
        return plan
    }

    // MARK: - Running it

    /// What actually happened, so the sentence and the report agree with the
    /// disk rather than with the plan.
    public struct Result: Sendable {
        public var readings = 0
        public var captures = 0
        public var log = 0
        /// Captures whose manifest could not be rewritten, and 1 when the
        /// research log could not be rewritten. Nothing is hidden: a partial
        /// run says which part failed and leaves the rest alone.
        public var failedCaptures = 0
        public var logFailed = false
        public var kept = 0
    }

    /// The two things the planner needs off the main actor.
    struct LogSnapshot: Sendable {
        let entries: [QuickMeasureEntry]
        @MainActor init(history: QuickMeasureHistory) {
            entries = history.entries
        }
    }

    /// Read all three stores and work out what would change. Writes nothing.
    public nonisolated static func preview(history: QuickMeasureHistory) async -> Plan {
        let summaries = RawCaptureStore.list()
        let snapshot = await MainActor.run { LogSnapshot(history: history) }
        let log = ResearchLog.shared.repairImperialTruths(apply: false)
        return plan(summaries: summaries,
                    entries: snapshot.entries,
                    logRows: log.rows,
                    logKept: log.kept)
    }

    /// Apply a plan the cruiser has just confirmed, then record what happened.
    ///
    /// ORDER MATTERS. Readings first, then captures, then the log — so if the
    /// run dies part-way, what is left behind is a reading whose unit-less
    /// capture still backs it, which is a state the rule reads correctly on the
    /// next run. Doing captures first would strip the evidence the reading rule
    /// depends on and strand the reading unrepairable.
    public nonisolated static func applyPlan(_ plan: Plan,
                                             history: QuickMeasureHistory) async -> Result {
        var result = Result()
        result.kept = plan.kept.total

        // Spelled with a named parameter rather than `$0`: a closure body that
        // opens with a parenthesised label list is ambiguous with a parameter
        // list, and the reading is worse than the keystrokes are worth.
        let readingWrites = plan.readings.mapValues { c in
            (before: c.before, after: c.after,
             unit: c.quantity.typedUnit.rawValue)
        }
        result.readings = await MainActor.run {
            history.repairTruthUnits(readingWrites)
        }

        for (id, c) in plan.captures {
            let ok = RawCaptureStore.repairTruthUnit(id: id,
                                                     before: c.before,
                                                     after: c.after,
                                                     unit: c.quantity.typedUnit)
            if ok { result.captures += 1 } else { result.failedCaptures += 1 }
        }

        let log = ResearchLog.shared.repairImperialTruths(apply: true)
        result.log = log.written
        result.logFailed = log.failed

        TruthUnitRepairReport(plan: plan, result: result).save()
        return result
    }

    // MARK: - Wording (byte-identical to Android)

    /// The preview the cruiser confirms against. Three lines of counts (one per
    /// store, because the same tape value lives in all three and adding them
    /// would claim three times the work), then the worked examples, then what
    /// is being left alone and why.
    ///
    /// No plural branching anywhere: the two platforms have to emit the same
    /// bytes, and "1 diameters" is a smaller cost than two pluralisation rules
    /// drifting apart.
    public static func previewText(_ plan: Plan) -> String {
        guard !plan.isEmpty else {
            return "Nothing to repair. " + keptText(plan.kept)
        }
        var s = "Readings: \(plan.readingDiameters) diameters, "
        s += "\(plan.readingHeights) heights.\n"
        s += "Captures: \(plan.captureDiameters) diameters, "
        s += "\(plan.captureHeights) heights.\n"
        s += "Research log: \(plan.logDiameters) diameters, "
        s += "\(plan.logHeights) heights.\n\n"
        for line in plan.examples { s += line + "\n" }
        s += "\n" + keptText(plan.kept)
        s += "\nNothing is written until you confirm."
        return s
    }

    /// The "and here is what it will not touch" sentence, so the arithmetic
    /// closes: everything the sweep saw is either changing or in one of these.
    public static func keptText(_ kept: Kept) -> String {
        "Leaving \(kept.total) alone: \(kept.unitRecorded) already record the "
            + "unit they were typed in, \(kept.typedOnReading) were typed onto "
            + "a reading after the unit toggle existed, \(kept.unverifiable) "
            + "have no unit-less capture to check against, and "
            + "\(kept.otherQuantity) are distance readings the defect was never "
            + "confirmed for."
    }

    /// The result line. Counts what reached the disk, not what was planned.
    public static func resultText(_ r: Result) -> String {
        var s = "Repaired \(r.readings) readings, \(r.captures) captures and "
        s += "\(r.log) research-log rows. "
        if r.failedCaptures > 0 {
            s += "\(r.failedCaptures) captures could not be rewritten and are "
            s += "unchanged. "
        }
        if r.logFailed {
            s += "The research log could not be rewritten and is unchanged. "
        }
        s += "\(r.kept) were left alone. "
        s += "Every before-and-after is in truth-repair.json."
        return s
    }
}

// MARK: - The report on disk

/// EVERY change the run made, written where the cruiser can pull it off the
/// device and diff it against their backup. That is the whole point of a
/// repair to research data being loud: the counts on screen say how much moved,
/// and this says exactly which numbers.
///
/// CROSS-PLATFORM: file name and every JSON key byte-identical to Android.
public struct TruthUnitRepairReport: Codable, Sendable {

    public struct Row: Codable, Sendable {
        public var store: String
        public var key: String
        public var quantity: String
        public var before: Double
        public var after: Double
        public var unit: String
    }

    public var schema: Int
    public var ranAt: String
    public var readings: Int
    public var captures: Int
    public var log: Int
    public var failedCaptures: Int
    public var logFailed: Bool
    public var kept: Int
    public var changes: [Row]

    enum CodingKeys: String, CodingKey {
        case schema
        case ranAt = "ran_at"
        case readings, captures, log
        case failedCaptures = "failed_captures"
        case logFailed = "log_failed"
        case kept, changes
    }

    init(plan: TruthUnitRepair.Plan, result: TruthUnitRepair.Result) {
        let iso = ISO8601DateFormatter()
        iso.formatOptions = [.withInternetDateTime]
        self.schema = 1
        self.ranAt = iso.string(from: Date())
        self.readings = result.readings
        self.captures = result.captures
        self.log = result.log
        self.failedCaptures = result.failedCaptures
        self.logFailed = result.logFailed
        self.kept = result.kept
        var rows: [Row] = []
        let planned = plan.captures.sorted { $0.key < $1.key }.map(\.value)
            + plan.readings.sorted { $0.key.uuidString < $1.key.uuidString }
                .map(\.value)
            + plan.logRows
        for c in planned {
            rows.append(Row(store: c.store, key: c.key,
                            quantity: c.quantity.rawValue,
                            before: c.before, after: c.after,
                            unit: c.quantity.typedUnit.rawValue))
        }
        self.changes = rows
    }

    /// Documents, beside the raw-capture tree and the backfill report.
    public static var fileURL: URL? {
        let fm = FileManager.default
        guard let docs = try? fm.url(for: .documentDirectory, in: .userDomainMask,
                                     appropriateFor: nil, create: true)
        else { return nil }
        return docs.appendingPathComponent("truth-repair.json")
    }

    public func save() {
        guard let url = Self.fileURL else { return }
        let e = JSONEncoder()
        e.outputFormatting = [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]
        guard let data = try? e.encode(self) else { return }
        try? data.write(to: url, options: .atomic)
    }

    public static func load() -> TruthUnitRepairReport? {
        guard let url = fileURL, let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(TruthUnitRepairReport.self, from: data)
    }
}
