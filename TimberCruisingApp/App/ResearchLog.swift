// Research log — the data-capture spine planned for the ForestiX validation
// study. Each row is intended to capture not just a committed DBH/height
// value but the full diagnostic context the paper's tier/σ validation needs:
// σ, confidence tier, distance, point/inlier counts, arc coverage, RMSE,
// angles, camera intrinsics, depth-map size, depth source, device model,
// plus the operator-set tree id / ground-truth true value.
//
// Storage is a single append-only CSV in the app's Documents directory.
//
// WIRED (developer mode only): the DBH / Height / Distance screens append a
// row on every accepted reading, with an optional user-entered true value →
// error column. Settings › Developer exports/clears this CSV. The Android
// data/ResearchLog.kt mirrors the same column order so the two platforms'
// exports concatenate for the cross-platform accuracy analysis.

import Foundation
#if canImport(UIKit)
import UIKit
#endif

public final class ResearchLog {

    public static let shared = ResearchLog()
    private init() {}

    /// Intended cross-platform column order. No Android ResearchLog exists
    /// yet — this is the schema to match when it lands so the exports join.
    public static let columns: [String] = [
        "timestamp_iso", "platform", "os_version", "device_model",
        "measure_type", "method", "depth_source",
        "tree_id", "repeat", "true_value", "measured_value", "unit", "error",
        "sigma", "confidence_tier", "distance_m", "n_points", "arc_deg", "rmse_mm",
        "pitch_deg", "alpha_top_deg", "alpha_base_deg",
        "fx", "depth_w", "depth_h", "depth_noise_mm",
        "raw_points_path", "species", "note",
        // aim_drift_m — how far the instrument moved between the base and
        // the top sighting on a walk-off height. The tangent identity
        // H = d_h(tan a_top - tan a_base) assumes ONE origin, so this is the
        // size of the assumption violation, and the error it implies is
        // roughly the vertical component one-for-one plus the radial
        // component scaled by H/d_h. It used to be shown to the cruiser at
        // accept time and then discarded, which left the analyst unable to
        // flag, weight or exclude a drifted row — including every row that
        // stayed under the warning threshold and so was never announced at
        // all. Empty for non-height rows and when no pose was available.
        "aim_drift_m",
        // truth_unit — the unit the operator TYPED `true_value` in ("cm" |
        // "in" | "m" | "ft"). `true_value` itself is always the metric base,
        // normalised on the way in, so nothing else in the row says whether
        // the cruiser was working in inches or centimetres. Recording it makes
        // the reconciliation arithmetic instead of guesswork. Empty when no
        // truth was typed. NEW COLUMNS GO AT THE END: `prepareFileLocked`
        // re-emits an older on-disk log under this order by column NAME, so
        // every row a device already holds keeps its columns where they were
        // and a pooled export sees one schema.
        "truth_unit",
        // tracking_dropped — "true" | "false" on a walk-off height row: did
        // VIO tracking drop at any point between anchoring the trunk and the
        // aim taps? A dropout moves the world frame the trunk anchor sits in,
        // so it moves d_h — and d_h is the ENTIRE scale of H, which makes it a
        // larger threat to the reading than the aim drift beside it. The
        // cruiser is warned on screen and can retake, but before this column an
        // accepted run taken across a dropout exported byte-identical to a
        // clean one and the analyst could neither flag nor exclude it. Empty on
        // rows with no walk-off (DBH, typed manual heights).
        "tracking_dropped",
    ]

    private let queue = DispatchQueue(label: "forestix.researchlog")

    /// True once the on-disk header has been confirmed to be the current
    /// column order in this process. The check parses the whole file, and the
    /// header can only change under us via `clear()`, which runs on the same
    /// queue and resets this. Guarded by `queue`.
    private var headerIsCurrent = false

    public var fileURL: URL {
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        return dir.appendingPathComponent("forestix_research_log.csv")
    }

    public var hasData: Bool { FileManager.default.fileExists(atPath: fileURL.path) }

    public func rowCount() -> Int {
        guard let text = try? String(contentsOf: fileURL, encoding: .utf8) else { return 0 }
        let lines = text.split(separator: "\n", omittingEmptySubsequences: true)
        return max(0, lines.count - 1) // minus header
    }

    /// Next 1-based repeat index for (tree id, measure type), counted from
    /// the CSV itself so it survives app restarts. Must run on `queue`.
    /// Naive comma split is safe here: the only field that can contain a
    /// comma (note) sits AFTER the indices we read.
    private func nextRepeatLocked(treeId: String, measureType: String) -> Int {
        guard let text = try? String(contentsOf: fileURL, encoding: .utf8) else { return 1 }
        let typeIdx = Self.columns.firstIndex(of: "measure_type") ?? 4
        let treeIdx = Self.columns.firstIndex(of: "tree_id") ?? 7
        var n = 0
        for line in text.split(separator: "\n").dropFirst() {
            let cols = line.split(separator: ",", omittingEmptySubsequences: false)
            guard cols.count > max(typeIdx, treeIdx) else { continue }
            if cols[typeIdx] == measureType, cols[treeIdx] == treeId { n += 1 }
        }
        return n + 1
    }

    /// Append one measurement. The caller supplies the measurement-specific
    /// fields; this fills timestamp / platform / os / device, and — when a
    /// tree_id is present — the 1-based repeat index for that target.
    public func record(_ fields: [String: String]) {
        var f = fields
        f["timestamp_iso"] = Self.timestamp()
        f["platform"] = "iOS"
        f["os_version"] = Self.osVersion()
        f["device_model"] = Self.deviceModel()
        queue.async {
            // Bring an older on-disk header up to the current column order
            // FIRST: a row is written positionally, so appending under a
            // header this build no longer matches is silent corruption.
            let state = self.prepareFileLocked()
            guard state != .unusable else { return }
            // After the migration, so the indices this reads are the ones the
            // file now actually uses.
            if let tree = f["tree_id"], !tree.isEmpty, f["repeat"] == nil {
                f["repeat"] = "\(self.nextRepeatLocked(treeId: tree, measureType: f["measure_type"] ?? ""))"
            }
            let row = Self.columns.map { Self.csvEscape(f[$0] ?? "") }.joined(separator: ",")
            let url = self.fileURL
            var out = ""
            if state == .fresh { out += Self.columns.joined(separator: ",") + "\n" }
            out += row + "\n"
            guard let data = out.data(using: .utf8) else { return }
            if state == .fresh {
                // Fresh file — `out` already carries the header + first row.
                try? data.write(to: url)
            } else if let handle = try? FileHandle(forWritingTo: url) {
                handle.seekToEndOfFile()
                handle.write(data)
                try? handle.close()
            }
            // File exists but couldn't be opened for append: skip this row
            // rather than `write(to:)`, which would truncate the whole log to
            // just this header-less row.
        }
    }

    // MARK: - Header migration

    /// What the log on disk is, from the point of view of the next append.
    private enum FileState {
        /// Nothing usable on disk — write the header, then the row.
        case fresh
        /// The header on disk IS the current column order — append.
        case ready
        /// The header could not be brought up to date. Nothing may be
        /// appended: a mismatched row is worse than a missing one.
        case unusable
    }

    /// Bring a log written by an EARLIER build up to the current column order
    /// before anything is appended to it.
    ///
    /// A row is written positionally, so appending a 31-field row under the
    /// 30-name header a device already carries leaves every column after the
    /// difference reading shifted: `pandas.read_csv` on the export either
    /// raises "Expected 30 fields, saw 31" or, with bad lines suppressed,
    /// drops exactly the newest rows. Deleting the log was the only remedy,
    /// which throws away the field days already recorded.
    ///
    /// Old rows are re-emitted under the new order by COLUMN NAME. A column
    /// the old build never had is written empty — that is what "this build did
    /// not record it" means, and no value is invented. If the old header names
    /// a column this build no longer has, re-emitting would silently drop
    /// that data, so the file is set aside whole and a new log is started
    /// instead. Must run on `queue`.
    private func prepareFileLocked() -> FileState {
        let fm = FileManager.default
        let url = fileURL
        guard fm.fileExists(atPath: url.path) else {
            headerIsCurrent = false
            return .fresh
        }
        if headerIsCurrent { return .ready }
        // Exists but unreadable: do NOT fall through to `.fresh`, which
        // writes with `write(to:)` and would truncate the whole log.
        guard let text = try? String(contentsOf: url, encoding: .utf8) else {
            return .unusable
        }
        let records = Self.parseRecords(text)
        // Exists but holds no record (zero-length, or only blank lines):
        // safe to start it over under the current header.
        guard let header = records.first else { return .fresh }
        if header == Self.columns {
            headerIsCurrent = true
            return .ready
        }

        guard header.allSatisfy({ Self.columns.contains($0) }) else {
            return Self.archive(url) ? .fresh : .unusable
        }
        var out = Self.columns.joined(separator: ",") + "\n"
        for row in records.dropFirst() {
            var byName: [String: String] = [:]
            for (i, name) in header.enumerated() where i < row.count {
                byName[name] = row[i]
            }
            out += Self.columns.map { Self.csvEscape(byName[$0] ?? "") }
                .joined(separator: ",") + "\n"
        }
        do {
            // Atomic: a crash mid-migration must not leave a half-written log.
            try out.write(to: url, atomically: true, encoding: .utf8)
            headerIsCurrent = true
            return .ready
        } catch {
            return .unusable
        }
    }

    /// Move a log this build cannot re-emit out of the way, KEEPING IT WHOLE,
    /// so a new log can start under the current header. Returns false when it
    /// could not be moved — in which case nothing may be appended.
    private static func archive(_ url: URL) -> Bool {
        let fm = FileManager.default
        let base = url.deletingPathExtension()
        for n in 1...50 {
            let dst = base.appendingPathExtension("legacy\(n)")
                .appendingPathExtension("csv")
            if fm.fileExists(atPath: dst.path) { continue }
            do { try fm.moveItem(at: url, to: dst); return true } catch { return false }
        }
        return false
    }

    /// Split CSV text into records of fields, honouring the quoting
    /// `csvEscape` emits: a quoted field may contain ',', '"' and newlines, so
    /// neither a naive line split nor a naive comma split can be used to
    /// rewrite the file. Blank lines are skipped.
    static func parseRecords(_ text: String) -> [[String]] {
        var records: [[String]] = []
        var record: [String] = []
        var field = ""
        var inQuotes = false
        // Tells a genuinely empty trailing field apart from "no field yet",
        // so a trailing newline doesn't append a phantom one-field record.
        var started = false
        var i = text.startIndex
        while i < text.endIndex {
            let c = text[i]
            if inQuotes {
                if c == "\"" {
                    let next = text.index(after: i)
                    if next < text.endIndex, text[next] == "\"" {
                        field.append("\"")   // "" inside quotes is one quote
                        i = next
                    } else {
                        inQuotes = false
                    }
                } else {
                    field.append(c)
                }
            } else if c == "\"" {
                inQuotes = true
                started = true
            } else if c == "," {
                record.append(field)
                field = ""
                started = true
            } else if c.isNewline {
                if started || !field.isEmpty {
                    record.append(field)
                    records.append(record)
                }
                record = []
                field = ""
                started = false
            } else {
                field.append(c)
                started = true
            }
            i = text.index(after: i)
        }
        if started || !field.isEmpty {
            record.append(field)
            records.append(record)
        }
        return records
    }

    public func clear() {
        queue.async {
            try? FileManager.default.removeItem(at: self.fileURL)
            self.headerIsCurrent = false
        }
    }

    // MARK: - Helpers

    private static func timestamp() -> String {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f.string(from: Date())
    }

    private static func osVersion() -> String {
        #if canImport(UIKit)
        return "iOS " + UIDevice.current.systemVersion
        #else
        return "iOS"
        #endif
    }

    private static func deviceModel() -> String {
        var sysinfo = utsname()
        uname(&sysinfo)
        let machine = withUnsafePointer(to: &sysinfo.machine) {
            $0.withMemoryRebound(to: CChar.self, capacity: 1) { String(cString: $0) }
        }
        return machine.isEmpty ? "Apple" : machine
    }

    static func csvEscape(_ s: String) -> String {
        if s.contains(",") || s.contains("\"") || s.contains("\n") {
            return "\"" + s.replacingOccurrences(of: "\"", with: "\"\"") + "\""
        }
        return s
    }
}
