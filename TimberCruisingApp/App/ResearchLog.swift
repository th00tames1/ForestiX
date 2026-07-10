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
    ]

    private let queue = DispatchQueue(label: "forestix.researchlog")

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
            if let tree = f["tree_id"], !tree.isEmpty, f["repeat"] == nil {
                f["repeat"] = "\(self.nextRepeatLocked(treeId: tree, measureType: f["measure_type"] ?? ""))"
            }
            let row = Self.columns.map { Self.csvEscape(f[$0] ?? "") }.joined(separator: ",")
            let url = self.fileURL
            let exists = FileManager.default.fileExists(atPath: url.path)
            var out = ""
            if !exists { out += Self.columns.joined(separator: ",") + "\n" }
            out += row + "\n"
            guard let data = out.data(using: .utf8) else { return }
            if !exists {
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

    public func clear() {
        queue.async {
            try? FileManager.default.removeItem(at: self.fileURL)
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
