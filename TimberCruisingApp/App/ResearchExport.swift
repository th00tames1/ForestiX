// RESEARCH-LOG EXPORT — split the append-only research CSV into what the
// FIELD LOG still shows and what it no longer does, without destroying either.
//
// CROSS-PLATFORM: every user-visible string, every file name inside the
// archive, the `export_status` vocabulary and the column order are
// byte-identical to the Android sibling (data/ResearchExport.kt).
//
// WHY THIS EXISTS. `ResearchLog` is append-only by design and says so in its
// own header: it writes a row on every accepted reading and nothing removes
// that row when the reading is later deleted or replaced by a retake. That is
// the right shape for a research corpus — the retake ITSELF is data, and this
// corpus has already been mined for within-burst jitter and base-to-top drift
// from rows nobody was looking at when they were written — but it is the wrong
// shape for a file handed straight to an analysis, where a superseded reading
// sitting quietly beside its replacement is how one tree gets counted twice.
//
// SO NOTHING IS DELETED AND NOTHING IS HIDDEN. One export produces TWO CSVs in
// one archive: `research_log.csv`, which is what the field log shows, and
// `research_log_superseded.csv`, which is everything held back from it. Both
// ship, both carry the `export_status` column that says which rule put the row
// where it is, and the on-disk log is not touched by any of it.
//
// THE JOIN, AND WHY IT IS A HEURISTIC. A research row identifies itself by
// `timestamp_iso`, `measure_type`, `tree_id` (a tree NUMBER, not a reading id)
// and `measured_value`. There is NO reading id in the schema and no plot
// column, so a row cannot be joined to the reading it was written for; it can
// only be matched to the set of readings that share its kind and tree number.
// That set is ambiguous in ways this file refuses to guess through:
//
//   • A cruise measurement writes a research row but NO quick-measure reading
//     (the cruise flow stores a Core Data Tree), and cruise and quick trees
//     share one tree-number namespace.
//   • The quick log holds the last 500 readings; an older reading is evicted
//     by capacity, not by the cruiser.
//   • A tree number that exists in two plots names two different stems, and
//     the row records no plot — the same wall `TruthBackfill.plan` stops at.
//   • Distance rows deliberately carry no tree_id at all.
//
// So `superseded` is asserted ONLY on positive evidence (below), everything
// else that cannot be matched is `unclassified`, and an unclassified row stays
// in the DEFAULT file — withholding it would be the wrong error, because a
// wrong join here drops good data. Being in the wrong one of two shipped files
// is a recoverable mistake; being absent is not.

import Foundation
import Common

public enum ResearchExport {

    // MARK: - Vocabulary

    /// The column the exporter appends to every exported row. It exists only
    /// in the EXPORT, never in the stored log: "is this row still what the
    /// field log shows" is a fact about the log as it is right now, and it
    /// changes every time a reading is deleted or retaken. Freezing that
    /// judgement into an append-only row at write time would make it wrong
    /// the moment the cruiser retook the tree.
    ///
    /// Appended LAST, after `tracking_dropped`, by the same rule the log's own
    /// schema follows: new columns go at the end so an existing reader still
    /// lines up.
    public static let statusColumn = "export_status"

    /// What one exported row is, relative to the field log.
    public enum Status: String, Sendable {
        /// A live reading of this kind, tree and value is in the field log,
        /// and its ground truth agrees with the row's.
        case live = "live"
        /// Matched to a live reading whose GROUND TRUTH differs — the cruiser
        /// typed a truth, then corrected it. This row carries the corrected
        /// value; the as-recorded original is in the held-back file as
        /// `superseded-truth`.
        case corrected = "corrected"
        /// The tree still has a live reading of this kind, a LATER one, and
        /// this row's measured value is not any of them: a retake, or a
        /// delete-and-remeasure. The only positive evidence available.
        case superseded = "superseded"
        /// The as-recorded copy of a `corrected` row — held back, never
        /// discarded, so the truth the cruiser first typed is still in the
        /// export beside the one they replaced it with.
        case supersededTruth = "superseded-truth"
        /// No live reading this row can be matched to, or nothing to match on.
        /// NOT a claim that the reading is gone — see the file header.
        case unclassified = "unclassified"
    }

    /// The file the analysis reads: what the field log shows.
    public static let defaultFileName = "research_log.csv"
    /// Everything held back from it. Ships in the same archive.
    public static let heldBackFileName = "research_log_superseded.csv"
    /// The counts and the rule, so the analyst reads them too and not only the
    /// cruiser who tapped Export.
    public static let notesFileName = "research_log_export.txt"
    public static let archiveFileName = "forestix_research_export.zip"

    /// A logged `measured_value` and a stored reading value are the SAME
    /// measurement inside this band. The log rounds to two decimals
    /// (`"%.2f"` on the scan screens), so the band is half of the last
    /// recorded digit — anything tighter would fail to match a row against
    /// the very reading that wrote it. Deliberately NOT the log's
    /// `truthValueEpsilon` (0.001), which compares two full-precision copies
    /// of the same stored number and has no rounding to absorb.
    public static let valueEpsilon: Double = 0.005
    /// Same reasoning for `true_value`, which the screens also write at
    /// two decimals.
    public static let truthEpsilon: Double = 0.005

    // MARK: - What the classification reads

    /// A live reading, reduced to the fields the join needs. A projection
    /// rather than the entry itself so the classification is pure data and can
    /// run off the main actor without dragging the store across.
    public struct Reading: Sendable {
        public let kind: String
        public let treeNumber: Int?
        public let value: Double
        /// MAY BE A TIME THE CRUISER SET BY HAND (`QuickMeasureEntry`'s
        /// `timeSource` == "typed"), and the join below deliberately uses it
        /// anyway: a corrected time is the cruiser's best account of when the
        /// tree was measured, which is precisely what "nearest row" and "is
        /// there a later reading" are trying to ask.
        ///
        /// The research-log ROW keeps the stamp it was written with — this log
        /// is append-only and nothing here rewrites it, exactly as nothing
        /// rewrites a raw-capture manifest. So after a hand-set time the two
        /// no longer agree, which is why the reading's own `time_source`
        /// travels in the quick-measure CSV: an analyst who needs to know
        /// whether a Δt is real can see it there.
        public let createdAt: Date
        public let truth: Double?
        public let truthUnit: String?

        public init(kind: String, treeNumber: Int?, value: Double,
                    createdAt: Date, truth: Double?, truthUnit: String?) {
            self.kind = kind
            self.treeNumber = treeNumber
            self.value = value
            self.createdAt = createdAt
            self.truth = truth
            self.truthUnit = truthUnit
        }
    }

    /// Every row is in exactly one of the first four counts, so
    /// `total` is the number of rows in the log and the cruiser can reconcile
    /// it against the row count Settings shows. `supersededTruth` is NOT in
    /// the total: those rows are copies, not rows of their own.
    public struct Counts: Sendable, Equatable {
        public var live = 0
        public var corrected = 0
        public var unclassified = 0
        public var superseded = 0
        public var supersededTruth = 0
        public init() {}
        public var total: Int { live + corrected + unclassified + superseded }
    }

    /// The two files, as rows, plus what went where.
    public struct Classified: Sendable {
        public var header: [String]
        public var kept: [[String]]
        public var heldBack: [[String]]
        public var counts: Counts
    }

    // MARK: - Classification

    /// Split the log's records into the two files. Pure: reads nothing, writes
    /// nothing, invents no value.
    ///
    /// `records` is the log exactly as `ResearchLog` parses it — header first.
    /// Returns nil when there is no header, which is the only shape that
    /// cannot be classified at all.
    public static func classify(records: [[String]],
                                readings: [Reading]) -> Classified? {
        guard let header = records.first else { return nil }
        let idx = Index(header: header)
        // Live readings that name a tree, grouped by the only key a row can
        // offer. A reading with no tree number can never be matched to a row
        // and is simply not in the index. Readings are held by INDEX, not by
        // value, because the pairing below has to be able to say "this reading
        // is taken" — see `claim`.
        var groupIndices: [GroupKey: [Int]] = [:]
        for (i, r) in readings.enumerated() {
            guard let tree = r.treeNumber else { continue }
            groupIndices[GroupKey(kind: r.kind, tree: tree), default: []].append(i)
        }

        // Each row parsed ONCE. A short row (written by a build with fewer
        // columns and never re-emitted) is padded rather than skipped, so the
        // cells this reads land where the header says they are — the same rule
        // the log's own repair pass follows.
        var rows: [Row] = []
        for (i, raw) in records.dropFirst().enumerated() {
            var cells = raw
            if cells.count < header.count {
                cells += Array(repeating: "", count: header.count - cells.count)
            }
            let tree = Int(cell(cells, idx.tree))
            rows.append(Row(
                index: i,
                cells: cells,
                key: tree.map { GroupKey(kind: cell(cells, idx.type), tree: $0) },
                measured: TruthInput.parse(cell(cells, idx.measured)),
                stamp: TruthBackfill.parseISO(cell(cells, idx.timestamp))))
        }

        let claimed = claim(rows: rows, readings: readings, groups: groupIndices)

        var out = Classified(header: header + [statusColumn],
                             kept: [], heldBack: [], counts: Counts())
        for row in rows {
            switch verdict(for: row, claimed: claimed, readings: readings,
                           groups: groupIndices, idx: idx) {
            case .plain(let status):
                // `.plain` is only ever `.live` or `.unclassified` — the other
                // three statuses have their own verdicts because they decide
                // WHICH file the row goes in, not just what it is labelled.
                out.kept.append(row.cells + [status.rawValue])
                if status == .live { out.counts.live += 1 }
                else { out.counts.unclassified += 1 }
            case .superseded:
                out.heldBack.append(row.cells + [Status.superseded.rawValue])
                out.counts.superseded += 1
            case .corrected(let rebased):
                out.kept.append(rebased + [Status.corrected.rawValue])
                out.heldBack.append(row.cells + [Status.supersededTruth.rawValue])
                out.counts.corrected += 1
                out.counts.supersededTruth += 1
            }
        }
        return out
    }

    /// One row of the log, parsed once: the cells, and the three things the
    /// join reads out of them.
    private struct Row {
        let index: Int
        let cells: [String]
        let key: GroupKey?
        let measured: Double?
        let stamp: Date?
    }

    /// One row's fate. `corrected` carries the REBASED row, so the caller
    /// writes the corrected copy to the default file and the row it was handed
    /// to the held-back file.
    private enum Verdict {
        case plain(Status)
        case superseded
        case corrected([String])
    }

    private struct GroupKey: Hashable {
        let kind: String
        let tree: Int
    }

    /// Column positions, resolved once against the header on disk rather than
    /// against `ResearchLog.columns`: a log this build has not migrated yet
    /// may legitimately be missing a column, and reading by name is what keeps
    /// that from shifting every cell.
    private struct Index {
        let type: Int?, tree: Int?, measured: Int?, truth: Int?
        let error: Int?, unit: Int?, timestamp: Int?
        init(header: [String]) {
            type = header.firstIndex(of: "measure_type")
            tree = header.firstIndex(of: "tree_id")
            measured = header.firstIndex(of: "measured_value")
            truth = header.firstIndex(of: "true_value")
            error = header.firstIndex(of: "error")
            unit = header.firstIndex(of: "truth_unit")
            timestamp = header.firstIndex(of: "timestamp_iso")
        }
    }

    private static func cell(_ row: [String], _ i: Int?) -> String {
        guard let i, i < row.count else { return "" }
        return row[i]
    }

    /// Row index → reading index, one reading to at most one row.
    ///
    /// ONE READING, ONE ROW is the whole point. A tree measured three times
    /// leaves three rows, and if the retake happened to land on the same
    /// diameter as the keeper then two of them match the surviving reading by
    /// value — and both would be called `live`, which is exactly the
    /// counted-twice the cruiser reported. Claiming globally smallest-Δt first
    /// gives the reading to the row that actually wrote it and pushes the
    /// others onto the supersession test, and it resolves identically on every
    /// run and on both platforms because the ordering is total. Same rule and
    /// same reason as `TruthBackfill.plan`.
    ///
    /// A row this build cannot place in time sorts LAST (its Δt is unknown,
    /// not zero) rather than being dropped: it can still claim a reading no
    /// dated row wanted.
    private static func claim(rows: [Row], readings: [Reading],
                              groups: [GroupKey: [Int]]) -> [Int: Int] {
        struct Pair {
            let row: Int
            let reading: Int
            let delta: TimeInterval
        }
        var pairs: [Pair] = []
        for row in rows {
            guard let key = row.key, let measured = row.measured,
                  let candidates = groups[key] else { continue }
            // Rows carry the measurement at two (sometimes three) decimals, so
            // the match is a band, not equality — see `valueEpsilon`.
            for ri in candidates
            where abs(readings[ri].value - measured) <= valueEpsilon {
                let delta = row.stamp.map {
                    abs(readings[ri].createdAt.timeIntervalSince($0))
                } ?? .greatestFiniteMagnitude
                pairs.append(Pair(row: row.index, reading: ri, delta: delta))
            }
        }
        pairs.sort { a, b in
            if a.delta != b.delta { return a.delta < b.delta }
            if a.row != b.row { return a.row < b.row }
            return a.reading < b.reading
        }
        var byRow: [Int: Int] = [:]
        var takenReadings: Set<Int> = []
        for p in pairs {
            guard byRow[p.row] == nil, !takenReadings.contains(p.reading) else { continue }
            byRow[p.row] = p.reading
            takenReadings.insert(p.reading)
        }
        return byRow
    }

    private static func verdict(for row: Row, claimed: [Int: Int],
                                readings: [Reading],
                                groups: [GroupKey: [Int]],
                                idx: Index) -> Verdict {
        guard let readingIndex = claimed[row.index] else {
            // Unmatched. `superseded` needs positive evidence: the tree must
            // still have a live reading OF THIS KIND, and it must have been
            // taken LATER than this row. Without a tree number, without a
            // parseable timestamp, or with no later reading, the row says
            // nothing — a cruise measurement, a reading past the 500-row cap,
            // a cleared log and a deletion all look identical from here, and
            // the log cannot tell them apart.
            guard let key = row.key, let stamp = row.stamp,
                  let candidates = groups[key],
                  candidates.contains(where: { readings[$0].createdAt > stamp })
            else { return .plain(.unclassified) }
            return .superseded
        }
        let reading = readings[readingIndex]
        // Matched. The remaining question is the GROUND TRUTH: a truth typed
        // wrong and corrected in the field log leaves the wrong number here.
        let logged = TruthInput.parsePositive(cell(row.cells, idx.truth))
        switch (logged, reading.truth) {
        case (nil, nil):
            return .plain(.live)
        case let (a?, b?) where abs(a - b) <= truthEpsilon:
            return .plain(.live)
        default:
            // `measured` is non-nil on any claimed row — a row with no
            // measured value can never have matched one.
            return .corrected(Self.rebased(row.cells, idx: idx, to: reading,
                                           measured: row.measured ?? 0))
        }
    }

    /// The row rewritten around the truth the cruiser corrected to.
    ///
    /// Nothing is invented. Both numbers are the cruiser's own: the truth is
    /// the one they corrected to, and the error is arithmetic over a
    /// measurement this pass does not touch (the estimator is frozen), exactly
    /// as `ResearchLog.repairImperialTruths` recomputes it. A cleared truth
    /// clears the error and the unit with it rather than leaving a number
    /// computed against a truth that is no longer there.
    ///
    /// Ported 1:1 from the Android `rebased`.
    private static func rebased(_ row: [String],
                                idx: Index,
                                to reading: Reading,
                                measured: Double) -> [String] {
        var out = row
        func put(_ i: Int?, _ value: String) {
            guard let i, out.indices.contains(i) else { return }
            out[i] = value
        }
        if let truth = reading.truth {
            put(idx.truth, String(format: "%.2f", truth))
            put(idx.error, String(format: "%.2f", measured - truth))
            // The unit comes from the reading too: carrying the old row's unit
            // beside a new value would stamp a number with a unit nobody typed
            // it in. Empty means NOT STATED, the same as everywhere else.
            put(idx.unit, reading.truthUnit ?? "")
        } else {
            put(idx.truth, "")
            put(idx.error, "")
            put(idx.unit, "")
        }
        return out
    }

    // MARK: - Wording (byte-identical to Android)

    /// The line the cruiser reads at the moment of export. Every count is
    /// named: a quiet filter is how someone later concludes data went missing.
    ///
    /// No plural branching anywhere — the two platforms have to emit the same
    /// bytes, and "1 rows" is a smaller cost than two pluralisation rules
    /// drifting apart. Same rule as `TruthBackfill.summary`.
    public static func summary(_ c: Counts) -> String {
        "Exported \(c.total) research rows: \(c.live) live, "
            + "\(c.corrected) truth-corrected, \(c.unclassified) unclassifiable, "
            + "\(c.superseded) superseded. The first three are in "
            + "\(defaultFileName); the superseded ones, plus \(c.supersededTruth) "
            + "as-recorded copies of the truth-corrected rows, are in "
            + "\(heldBackFileName). Nothing was deleted from this device."
    }

    public static let emptyMessage = "No research rows on this device."
    public static let failureMessage =
        "Research export failed — the log could not be read or written."

    /// Shipped inside the archive so the analyst gets the same accounting and
    /// the same caveat the cruiser did, rather than inferring a filter from
    /// two files with different row counts.
    public static func notes(_ c: Counts) -> String {
        var out = summary(c) + "\n\n"
        out += "export_status values\n"
        out += "  live              a reading with this kind, tree and value is in the field log\n"
        out += "  corrected         matched, but the field log's ground truth differs; true_value,\n"
        out += "                    error and truth_unit here are the field log's, and the row as\n"
        out += "                    recorded is in \(heldBackFileName) as superseded-truth\n"
        out += "  unclassified      no live reading to match — a cruise measurement, a reading past\n"
        out += "                    the 500-row cap, a cleared log, or a deletion. The log cannot\n"
        out += "                    say which, so it does not guess, and the row is kept here\n"
        out += "  superseded        the tree still has a LATER live reading of this kind and this\n"
        out += "                    value is not it: a retake or a delete-and-remeasure\n"
        out += "  superseded-truth  the as-recorded copy of a corrected row\n\n"
        out += "The research log carries no reading id and no plot, so rows are matched on\n"
        out += "measure_type, tree_id, measured_value and timestamp_iso only. That join is a\n"
        out += "heuristic: superseded is asserted only on the positive evidence above, and\n"
        out += "everything else stays unclassified rather than being guessed into a bucket.\n"
        return out
    }

    // MARK: - Building the archive

    /// The two CSVs and the notes, as one stored archive. Rows are re-emitted
    /// through the log's own escaping so a note containing a comma survives
    /// the round trip.
    public static func archiveData(_ c: Classified) -> Data {
        func csv(_ rows: [[String]]) -> Data {
            var out = c.header.map(ResearchLog.csvEscape).joined(separator: ",") + "\n"
            for row in rows {
                out += row.map(ResearchLog.csvEscape).joined(separator: ",") + "\n"
            }
            return Data(out.utf8)
        }
        return ZipWriter.storedArchive(files: [
            (defaultFileName, csv(c.kept)),
            (heldBackFileName, csv(c.heldBack)),
            (notesFileName, Data(notes(c.counts).utf8))
        ])
    }

    // MARK: - Running it

    /// The readings the classification is judged against, read in ONE hop so
    /// the log and the field log are sampled at the same instant.
    @MainActor
    public static func readings(from history: QuickMeasureHistory) -> [Reading] {
        history.entries.map {
            Reading(kind: $0.kind.rawValue, treeNumber: $0.treeNumber,
                    value: $0.value, createdAt: $0.createdAt,
                    truth: $0.truth, truthUnit: $0.truthUnit)
        }
    }

    /// Read the log, classify it against the field log, write the archive, and
    /// return the file to share plus the sentence the cruiser reads.
    ///
    /// Parses the whole research CSV, so callers run it off the main actor —
    /// the same shape as the ground-truth recovery beside it in Settings.
    public nonisolated static func run(
        history: QuickMeasureHistory
    ) async -> (url: URL?, message: String) {
        guard let records = ResearchLog.shared.snapshotRecords(),
              records.count > 1 else {
            return (nil, emptyMessage)
        }
        let live = await MainActor.run { readings(from: history) }
        guard let classified = classify(records: records, readings: live) else {
            return (nil, failureMessage)
        }
        guard let url = write(archiveData(classified)) else {
            return (nil, failureMessage)
        }
        return (url, summary(classified.counts))
    }

    /// Beside the quick-measure exports, under a FIXED name: the previous
    /// export is replaced rather than accumulating a folder of near-identical
    /// archives the cruiser then has to tell apart by timestamp.
    private static func write(_ data: Data) -> URL? {
        let fm = FileManager.default
        guard let docs = try? fm.url(for: .documentDirectory, in: .userDomainMask,
                                     appropriateFor: nil, create: true)
        else { return nil }
        let dir = docs.appendingPathComponent("Exports", isDirectory: true)
        try? fm.createDirectory(at: dir, withIntermediateDirectories: true)
        let url = dir.appendingPathComponent(archiveFileName)
        do {
            try data.write(to: url, options: .atomic)
            return url
        } catch {
            return nil
        }
    }
}
