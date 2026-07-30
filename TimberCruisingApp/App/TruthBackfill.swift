// GROUND-TRUTH RECOVERY — attach a truth typed for a raw CAPTURE to the
// READING it belongs to.
//
// CROSS-PLATFORM: matching rule, provenance stamp, persisted keys and every
// user-visible string are byte-identical to the Android sibling
// (sensors/TruthBackfill.kt).
//
// WHY THIS EXISTS. A ground truth used to live only in the raw-capture
// manifest, keyed on tree number. Both scan screens began writing it onto the
// reading as well in one commit, so captures taken since are symmetric — but
// everything recorded before that has its tape numbers in manifests and
// nowhere else, and the read-only sheet section that used to display them is
// gone. The values were never destroyed (they are still in the manifest and
// still ship in the raw-capture ZIP), but the field log shows a blank True
// field for trees the cruiser measured with a tape, which is indistinguishable
// on screen from never having measured them.
//
// There is a SECOND, still-live source of the same shape: the raw-captures
// console lets a truth be typed against a stored bundle long after the fact,
// and that path writes the manifest only. So this is not a one-time migration
// of historic data — it is a repair that can find new work at any time, which
// is why it is an action the cruiser triggers rather than a launch pass, and
// why it must be idempotent rather than version-flagged.
//
// WHAT IT WILL NOT DO
//   • It never overwrites a truth already on a reading. The typed field wins.
//   • It never creates a reading. A manifest truth whose reading was deleted
//     stays in the manifest; inventing a measurement to hang it from would put
//     a number into the corpus that nothing measured.
//   • It never edits a manifest. The manifest copy is the capture's own record
//     and still ships in the ZIP exactly as before.
//   • It never touches the estimator, the value, σ, or anything but `truth`
//     and its provenance stamp.

import Foundation
import Sensors

public enum TruthBackfill {

    // MARK: - What a manifest contributes

    /// One recoverable truth, lifted out of a raw-capture manifest.
    ///
    /// `treeNumber` is required, not optional: a truth with no tree number
    /// names no stem and can never be matched to one.
    public struct ManifestTruth: Sendable, Equatable {
        public let captureID: String
        public let kind: QuickMeasureEntry.Kind
        public let treeNumber: Int
        /// Metric base, as the manifest stores it — cm for a diameter, m for
        /// a height. Same unit as `QuickMeasureEntry.truth`, so no conversion
        /// happens anywhere on this path.
        public let value: Double
        public let createdAt: Date

        public init(captureID: String, kind: QuickMeasureEntry.Kind,
                    treeNumber: Int, value: Double, createdAt: Date) {
            self.captureID = captureID
            self.kind = kind
            self.treeNumber = treeNumber
            self.value = value
            self.createdAt = createdAt
        }
    }

    /// What a run WOULD do, computed before anything is written.
    public struct Plan: Sendable {
        /// reading id → truth value to attach.
        public let attachments: [UUID: Double]
        /// Manifest truths whose value ALREADY sits on a reading of that kind
        /// and tree. Nothing to do and nothing to report: the manifest and the
        /// reading simply agree, which is the normal state of every capture
        /// taken since the scan screens started writing both.
        public let alreadyPresent: Int
        /// Manifest truths with no reading to sit on. These are reported, not
        /// discarded — they stay in the manifest and in the ZIP.
        public let unmatched: [ManifestTruth]
        /// Bundles read off disk, parseable or not — the denominator the
        /// cruiser reconciles against their own backup.
        public let scanned: Int
    }

    /// A stored truth and a manifest truth are the SAME value inside this
    /// band. Both are written to the metric base from the same parser, so the
    /// only difference between them is float round-trip — the same reasoning
    /// and the same number as the field log's `valueEpsilon`.
    static let valueEpsilon: Double = 0.001

    // MARK: - Reading the corpus

    /// Manifest `created_at` is written by both platforms as an ISO-8601
    /// instant with milliseconds; older bundles on either platform may carry
    /// one without. Both shapes are parsed rather than one being assumed,
    /// because a timestamp that silently fails to parse would quietly make
    /// every capture of that vintage unmatchable.
    static func parseISO(_ s: String) -> Date? {
        let withFraction = ISO8601DateFormatter()
        withFraction.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let d = withFraction.date(from: s) { return d }
        let plain = ISO8601DateFormatter()
        plain.formatOptions = [.withInternetDateTime]
        return plain.date(from: s)
    }

    /// The recoverable truths in a set of bundle summaries.
    ///
    /// CRUISE CAPTURES ARE SKIPPED. `context.mode` says which world a capture
    /// belongs to, and a cruise capture's reading is a cruise Tree/Stem record,
    /// not a `QuickMeasureEntry` — the cruise flow appends nothing to the quick
    /// log at all. Offering a cruise truth to a quick-measure reading with the
    /// same tree number would attach it to a different stem entirely.
    public static func recoverable(from summaries: [RawCaptureSummary]) -> [ManifestTruth] {
        summaries.compactMap { s -> ManifestTruth? in
            guard s.manifest.context.mode != "cruise" else { return nil }
            guard let value = s.manifest.truth.value, value.isFinite, value > 0
            else { return nil }                       // no truth typed here
            guard let tree = s.manifest.context.treeNumber else { return nil }
            guard let kind = kindOf(s.manifest.kind) else { return nil }
            guard let created = parseISO(s.manifest.createdAt) else { return nil }
            return ManifestTruth(captureID: s.id, kind: kind, treeNumber: tree,
                                 value: value, createdAt: created)
        }
    }

    /// Only the two kinds a raw capture can record. `crown` / `distance` /
    /// `samplingPlot` have no capture bundle and no truth field.
    static func kindOf(_ raw: String) -> QuickMeasureEntry.Kind? {
        switch raw {
        case "dbh":    return .dbh
        case "height": return .height
        default:       return nil
        }
    }

    // MARK: - The matching rule

    /// Group key: one kind on one tree. The plot is NOT part of it — see
    /// `plan` for why it cannot be.
    private struct GroupKey: Hashable {
        let kind: QuickMeasureEntry.Kind
        let tree: Int
    }

    /// Work out which truth goes on which reading, changing nothing.
    ///
    /// THE RULE, in order:
    ///   1. Same kind, same tree number.
    ///   2. Same plot — enforced as "the plot must be unambiguous". A quick
    ///      capture's manifest records NO plot (`context.plot_id` is the cruise
    ///      plot and is null for every quick capture, and a quick-measure plot
    ///      id was never written there at all), so the plot cannot be read off
    ///      the manifest and compared. What CAN be checked is whether the tree
    ///      number is ambiguous: if this kind of reading exists for this tree
    ///      number in more than one plot, the manifest cannot say which of them
    ///      it was taped for, and the truth is left unmatched. Anything else
    ///      would be a coin toss between two different stems.
    ///   3. Never overwrite. A reading that already carries a truth is not a
    ///      candidate; the typed field wins.
    ///   4. Nearest in time. A capture and the reading it produced are seconds
    ///      apart, so |Δt| identifies which of a tree's several readings a truth
    ///      was typed against — the same time-matching the validation analysis
    ///      used to pair phones. Pairs are taken globally smallest-Δt first, so
    ///      two truths competing for one reading resolve the same way every
    ///      run, and neither a truth nor a reading is used twice.
    ///
    /// No maximum Δt is imposed. A window would be a threshold nobody measured,
    /// and it would silently strand real tape values whose only rival is "this
    /// one is old" — the whole complaint being repaired here.
    ///
    /// IDEMPOTENT: everything it attaches makes its reading ineligible (rule 3),
    /// so a second run over the same corpus plans zero attachments.
    public static func plan(manifests: [ManifestTruth],
                            entries: [QuickMeasureEntry],
                            defaultPlotID: UUID?,
                            scanned: Int) -> Plan {
        // Readings of a recoverable kind that belong to a tree, grouped.
        var groups: [GroupKey: [QuickMeasureEntry]] = [:]
        for e in entries {
            guard e.kind == .dbh || e.kind == .height, let tree = e.treeNumber
            else { continue }
            groups[GroupKey(kind: e.kind, tree: tree), default: []].append(e)
        }

        // Rule 2: a group spread across plots cannot be resolved by a manifest
        // that names no plot. Computed over ALL of the group's readings, not
        // just the truthless ones — a plot whose reading already has its own
        // truth still proves the tree number is ambiguous.
        var ambiguous: Set<GroupKey> = []
        for (key, group) in groups {
            let plots = Set(group.map { ($0.plotID ?? defaultPlotID)?.uuidString ?? "" })
            if plots.count > 1 { ambiguous.insert(key) }
        }

        // ALREADY HOME. A manifest whose value is already carried by a reading
        // of that kind and tree needs nothing done and must not be reported as
        // stranded — every capture taken since the scan screens started
        // writing the truth to both places is in exactly this state, and
        // without this the first run would tell the cruiser that a couple of
        // hundred tape values are missing from readings that plainly show them.
        // It is also what keeps a re-measure quiet: the replacement reading
        // carries the truth across, so the manifest is still accounted for.
        var settled: Set<Int> = []
        for (i, m) in manifests.enumerated() {
            let group = groups[GroupKey(kind: m.kind, tree: m.treeNumber)] ?? []
            if group.contains(where: {
                guard let t = $0.truth else { return false }
                return abs(t - m.value) <= valueEpsilon
            }) { settled.insert(i) }
        }

        // Every legal (truth, reading) pairing, with the distance that ranks it.
        struct Pair {
            let manifestIndex: Int
            let entryID: UUID
            let deltaSeconds: TimeInterval
            let manifestCreatedAt: Date
            let captureID: String
            let entryIDText: String
        }
        var pairs: [Pair] = []
        for (i, m) in manifests.enumerated() where !settled.contains(i) {
            let key = GroupKey(kind: m.kind, tree: m.treeNumber)
            guard !ambiguous.contains(key), let group = groups[key] else { continue }
            for e in group where e.truth == nil {
                pairs.append(Pair(
                    manifestIndex: i,
                    entryID: e.id,
                    deltaSeconds: abs(e.createdAt.timeIntervalSince(m.createdAt)),
                    manifestCreatedAt: m.createdAt,
                    captureID: m.captureID,
                    entryIDText: e.id.uuidString))
            }
        }
        // Total order, so two devices (or two runs) resolve a contested reading
        // identically. Δt first; the rest are tie-breakers that only exist so
        // the sort can never depend on dictionary iteration order.
        pairs.sort { a, b in
            if a.deltaSeconds != b.deltaSeconds { return a.deltaSeconds < b.deltaSeconds }
            if a.manifestCreatedAt != b.manifestCreatedAt {
                return a.manifestCreatedAt < b.manifestCreatedAt
            }
            if a.captureID != b.captureID { return a.captureID < b.captureID }
            return a.entryIDText < b.entryIDText
        }

        var attachments: [UUID: Double] = [:]
        var claimedManifests: Set<Int> = []
        var claimedEntries: Set<UUID> = []
        for p in pairs {
            guard !claimedManifests.contains(p.manifestIndex),
                  !claimedEntries.contains(p.entryID) else { continue }
            claimedManifests.insert(p.manifestIndex)
            claimedEntries.insert(p.entryID)
            attachments[p.entryID] = manifests[p.manifestIndex].value
        }

        let unmatched = manifests.enumerated()
            .filter { !claimedManifests.contains($0.offset) && !settled.contains($0.offset) }
            .map(\.element)
        return Plan(attachments: attachments,
                    alreadyPresent: settled.count,
                    unmatched: unmatched,
                    scanned: scanned)
    }

    // MARK: - Running it

    /// The two things the planner needs off the main actor, carried across in
    /// one value rather than as a tuple of two separate reads.
    struct LogSnapshot: Sendable {
        let entries: [QuickMeasureEntry]
        let defaultPlotID: UUID?
        @MainActor init(history: QuickMeasureHistory) {
            entries = history.entries
            defaultPlotID = history.defaultPlotID()
        }
    }

    /// Read the corpus, attach what matches, record what did not, and return
    /// the sentence the cruiser reads.
    ///
    /// Reads every manifest on disk, so it is `nonisolated` and callers run it
    /// off the main actor; only the write back into the history hops onto the
    /// main actor, and it does so once.
    public nonisolated static func run(history: QuickMeasureHistory) async -> String {
        let listing = RawCaptureStore.listing()
        let manifests = recoverable(from: listing.summaries)
        // ONE hop for both, so the readings and the plot they are judged
        // against are read at the same instant.
        let snapshot = await MainActor.run { LogSnapshot(history: history) }
        let plan = plan(manifests: manifests,
                        entries: snapshot.entries,
                        defaultPlotID: snapshot.defaultPlotID,
                        scanned: listing.inventory.directories)
        let attached = await MainActor.run {
            history.backfillTruths(plan.attachments)
        }
        // The REPORT records what actually happened, so a reading that the
        // history refused between planning and writing is counted as attached
        // by neither the sentence nor the sheet.
        let stranded = plan.unmatched
        TruthBackfillReport(scanned: plan.scanned,
                            attached: attached,
                            alreadyPresent: plan.alreadyPresent,
                            unmatched: stranded).save()
        return summary(attached: attached,
                       alreadyPresent: plan.alreadyPresent,
                       unmatched: stranded.count,
                       scanned: plan.scanned)
    }

    // MARK: - Wording (byte-identical to Android)

    /// The result line. Every truth the sweep found is in exactly one of the
    /// three counts, so `attached + already + unmatched` is the number of
    /// manifest truths on the device and the cruiser can reconcile it against
    /// their own backup — which is the whole reason this is not silent.
    ///
    /// No plural branching anywhere: the two platforms have to emit the same
    /// bytes, and "1 ground truths" is a smaller cost than two pluralisation
    /// rules drifting apart.
    public static func summary(attached: Int, alreadyPresent: Int,
                               unmatched: Int, scanned: Int) -> String {
        guard scanned > 0 else { return "No raw captures on this device." }
        return "Attached \(attached) ground truths to readings. "
            + "\(alreadyPresent) were already on one. "
            + "\(unmatched) found no reading and stay in the raw-capture ZIP. "
            + "Scanned \(scanned) captures."
    }

    /// The quiet line the field log shows where the cruiser would look for a
    /// truth that is in the ZIP and on no reading. `value` is rendered in the
    /// unit of the truth field directly above it, so the two never disagree.
    public static func strandedLine(_ text: String) -> String {
        "\(text) was typed for a raw capture of this tree that no reading "
            + "matches — it stays in the raw-capture ZIP."
    }
}

// MARK: - The report on disk

/// What the last run did, kept so the field log can name a stranded value
/// without re-reading ~300 manifests to draw one sheet.
///
/// CROSS-PLATFORM: file name and every JSON key are byte-identical to Android.
/// It is a RECORD, not a source of truth — deleting it loses nothing except
/// the sheet's notice until the next run.
public struct TruthBackfillReport: Codable, Sendable {

    public struct Stranded: Codable, Sendable {
        public var captureID: String
        public var kind: String
        public var treeNumber: Int
        public var value: Double
        public var createdAt: String
        enum CodingKeys: String, CodingKey {
            case captureID = "capture_id"
            case kind
            case treeNumber = "tree_number"
            case value
            case createdAt = "created_at"
        }
    }

    public var schema: Int
    public var ranAt: String
    public var scanned: Int
    public var attached: Int
    /// Manifest truths the readings already carried. Recorded so
    /// `attached + already_present + unmatched.count` adds up to the number of
    /// manifest truths on the device.
    public var alreadyPresent: Int
    public var unmatched: [Stranded]

    enum CodingKeys: String, CodingKey {
        case schema
        case ranAt = "ran_at"
        case scanned, attached
        case alreadyPresent = "already_present"
        case unmatched
    }

    init(scanned: Int, attached: Int, alreadyPresent: Int,
         unmatched: [TruthBackfill.ManifestTruth]) {
        let iso = ISO8601DateFormatter()
        iso.formatOptions = [.withInternetDateTime]
        self.schema = 1
        self.ranAt = iso.string(from: Date())
        self.scanned = scanned
        self.attached = attached
        self.alreadyPresent = alreadyPresent
        self.unmatched = unmatched.map {
            Stranded(captureID: $0.captureID,
                     kind: $0.kind.rawValue,
                     treeNumber: $0.treeNumber,
                     value: $0.value,
                     createdAt: iso.string(from: $0.createdAt))
        }
    }

    /// Documents, beside the raw-capture tree it describes.
    public static var fileURL: URL? {
        let fm = FileManager.default
        guard let docs = try? fm.url(for: .documentDirectory, in: .userDomainMask,
                                     appropriateFor: nil, create: true)
        else { return nil }
        return docs.appendingPathComponent("truth-backfill.json")
    }

    public func save() {
        guard let url = Self.fileURL else { return }
        let e = JSONEncoder()
        e.outputFormatting = [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]
        guard let data = try? e.encode(self) else { return }
        try? data.write(to: url, options: .atomic)
    }

    public static func load() -> TruthBackfillReport? {
        guard let url = fileURL, let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(TruthBackfillReport.self, from: data)
    }

    /// Stranded values recorded against one tree and kind — what the field log
    /// section asks for.
    ///
    /// Matched on tree number and kind ONLY, because a quick capture's manifest
    /// records no plot to match on (see `TruthBackfill.plan`). A tree number
    /// that exists in two plots is exactly the case the planner refuses to
    /// attach, so the notice appearing on both of those sheets is the honest
    /// outcome: the value is real, and which stem it belongs to is unresolved.
    public func stranded(kind: QuickMeasureEntry.Kind, treeNumber: Int) -> [Stranded] {
        unmatched.filter { $0.kind == kind.rawValue && $0.treeNumber == treeNumber }
    }
}
