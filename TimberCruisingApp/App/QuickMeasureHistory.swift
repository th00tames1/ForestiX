// On-device log of one-off diameter / height measurements captured from
// the Quick Measure entry point. These are NOT Tree/Plot records —
// just the last-N readings a cruiser wants to glance back at or export
// without opening the full project workflow.
//
// Storage strategy (durability for the app's most-used surface):
//
// • Primary: a JSONL sidecar file at
//       `Application Support/Forestix/quick-measure.jsonl`
//   One line per entry, append-only. Survives UserDefaults resets,
//   which is the single biggest data-loss footgun in the old design.
//
// • Cache: the last N entries encoded into UserDefaults as a single
//   blob — fast to read on launch, no disk I/O for the first paint.
//   If the cache fails to decode (schema drift after an app update,
//   corruption), we fall back to replaying the JSONL.
//
// • Schema versioning: every file write is prefixed by a single-line
//   header `#v 1`. Future entry-model changes bump the version and
//   add an explicit migration rather than `try?`-swallowing decode
//   errors and silently returning `[]`.

import Foundation
import Models

// MARK: - Entry

public struct QuickMeasureEntry: Codable, Identifiable, Sendable, Equatable {

    public enum Kind: String, Codable, Sendable {
        case dbh
        case height
    }

    public let id: UUID
    public let kind: Kind
    /// DBH is stored in centimetres; Height in metres. The display layer
    /// converts to imperial if the user's unit preference says so.
    public let value: Double
    /// Precision (1σ). DBH sigma is stored in millimetres; Height sigma
    /// in metres — mirrors the scan result types. Display + CSV always
    /// include the unit alongside the number so readers can't mix them
    /// up.
    public let sigma: Double?
    public let confidenceRaw: String
    public let method: String
    public let createdAt: Date

    public init(
        id: UUID = UUID(),
        kind: Kind,
        value: Double,
        sigma: Double?,
        confidenceRaw: String,
        method: String,
        createdAt: Date = Date()
    ) {
        self.id = id
        self.kind = kind
        self.value = value
        self.sigma = sigma
        self.confidenceRaw = confidenceRaw
        self.method = method
        self.createdAt = createdAt
    }

    /// Unit string for `value`. `cm` for diameter, `m` for height.
    public var valueUnit: String {
        switch kind {
        case .dbh:    return "cm"
        case .height: return "m"
        }
    }

    /// Unit string for `sigma`. `mm` for diameter (millimetre-scale
    /// RANSAC RMSE) and `m` for height (metres of combined geometric
    /// uncertainty).
    public var sigmaUnit: String {
        switch kind {
        case .dbh:    return "mm"
        case .height: return "m"
        }
    }
}

// MARK: - Store

@MainActor
public final class QuickMeasureHistory: ObservableObject {

    public enum Keys {
        public static let entries = "tc.quickMeasure.entries"
    }

    /// Current schema version stamped on every JSONL sidecar write.
    /// Bump when `QuickMeasureEntry` gains or removes a non-optional
    /// field; add a matching case to `migrate(_:to:)`.
    public static let schemaVersion: Int = 1

    @Published public private(set) var entries: [QuickMeasureEntry] = []
    /// Fires `true` when a new append has pushed the history within
    /// 5 % of the cap — the UI can surface a toast so the cruiser
    /// archives before silent truncation kicks in.
    @Published public private(set) var isNearCapacity: Bool = false

    private let defaults: UserDefaults
    private let capacity: Int
    private let sidecarURL: URL?

    public init(defaults: UserDefaults = .standard,
                capacity: Int = 500,
                sidecarURL: URL? = nil) {
        self.defaults = defaults
        self.capacity = capacity
        // Resolved lazily on first access so the class itself stays
        // constructible from non-main-actor callsites (tests, etc.).
        let resolved = sidecarURL ?? Self.defaultSidecarURL()
        self.sidecarURL = resolved
        self.entries = Self.loadBest(defaults: defaults, sidecar: resolved)
        self.recomputeCapacityFlag()
    }

    /// Test / preview factory backed by an isolated UserDefaults suite
    /// and a temp-directory sidecar (so tests don't collide with real
    /// app data).
    public static func ephemeral(capacity: Int = 500) -> QuickMeasureHistory {
        let name = "tc.quickMeasure.preview.\(UUID().uuidString)"
        let ud = UserDefaults(suiteName: name) ?? .standard
        let tmp = FileManager.default.temporaryDirectory
            .appendingPathComponent("qm-\(UUID().uuidString).jsonl")
        return QuickMeasureHistory(defaults: ud, capacity: capacity,
                                   sidecarURL: tmp)
    }

    // MARK: Mutations

    public func append(_ entry: QuickMeasureEntry) {
        var next = entries
        next.insert(entry, at: 0)
        if next.count > capacity {
            next = Array(next.prefix(capacity))
        }
        entries = next
        appendToSidecar(entry)
        persistCache()
        recomputeCapacityFlag()
    }

    public func delete(id: UUID) {
        entries.removeAll { $0.id == id }
        rewriteSidecar()
        persistCache()
        recomputeCapacityFlag()
    }

    public func clearAll() {
        entries = []
        rewriteSidecar()
        persistCache()
        recomputeCapacityFlag()
    }

    // MARK: CSV export

    /// Writes the current history as RFC-4180-compliant CSV to
    /// `Documents/Exports/quick-measure-<ts>.csv` and returns the URL.
    ///
    /// • Every field is quoted and embedded quotes are doubled.
    /// • Line separator is CRLF (Excel on Windows expects it).
    /// • A UTF-8 BOM prefix keeps Excel happy on double-byte content.
    /// • Units are now explicit per-field columns (`value_unit`,
    ///   `sigma_unit`) so spreadsheet formulas can't accidentally mix
    ///   DBH millimetres with height metres.
    public func exportCSV() -> URL? {
        guard !entries.isEmpty else { return nil }
        let fm = FileManager.default
        guard let docs = try? fm.url(for: .documentDirectory,
                                     in: .userDomainMask,
                                     appropriateFor: nil,
                                     create: true) else { return nil }
        let dir = docs.appendingPathComponent("Exports", isDirectory: true)
        try? fm.createDirectory(at: dir, withIntermediateDirectories: true)

        let iso = ISO8601DateFormatter()
        iso.formatOptions = [.withInternetDateTime]
        let stamp = iso.string(from: Date())
            .replacingOccurrences(of: ":", with: "-")
        let url = dir.appendingPathComponent("quick-measure-\(stamp).csv")

        let headers = ["id", "timestamp", "kind", "value", "value_unit",
                       "sigma", "sigma_unit", "confidence", "method"]
        var out = headers.map(Self.csvField).joined(separator: ",")
        out += "\r\n"

        for e in entries {
            let sigma = e.sigma.map { String(format: "%.3f", $0) } ?? ""
            let row = [
                e.id.uuidString,
                iso.string(from: e.createdAt),
                e.kind.rawValue,
                String(format: "%.3f", e.value),
                e.valueUnit,
                sigma,
                e.sigma == nil ? "" : e.sigmaUnit,
                e.confidenceRaw,
                e.method
            ].map(Self.csvField).joined(separator: ",")
            out += row + "\r\n"
        }

        // UTF-8 BOM + body. Without the BOM Excel on Windows
        // misinterprets any non-ASCII (e.g. µ / °) as Latin-1.
        var data = Data([0xEF, 0xBB, 0xBF])
        data.append(out.data(using: .utf8) ?? Data())

        do {
            try data.write(to: url)
            return url
        } catch {
            return nil
        }
    }

    /// RFC-4180 field quoting: wraps every value in `"…"` and doubles
    /// any embedded double-quotes. CR / LF inside a field survive
    /// because the surrounding quotes escape them.
    private static func csvField(_ s: String) -> String {
        "\"" + s.replacingOccurrences(of: "\"", with: "\"\"") + "\""
    }

    /// Delete export CSVs older than `maxAge` from the `Exports`
    /// directory. Call once at app launch to stop stale exports from
    /// accumulating forever in user-visible Documents.
    public static func sweepOldExports(olderThan maxAge: TimeInterval
                                        = 7 * 24 * 3600) {
        let fm = FileManager.default
        guard let docs = try? fm.url(for: .documentDirectory,
                                     in: .userDomainMask,
                                     appropriateFor: nil, create: false)
        else { return }
        let dir = docs.appendingPathComponent("Exports", isDirectory: true)
        guard let contents = try? fm.contentsOfDirectory(
                at: dir,
                includingPropertiesForKeys: [.contentModificationDateKey],
                options: [.skipsHiddenFiles])
        else { return }
        let cutoff = Date().addingTimeInterval(-maxAge)
        for url in contents where url.lastPathComponent.hasPrefix("quick-measure-") {
            let mod = (try? url.resourceValues(forKeys: [.contentModificationDateKey])
                .contentModificationDate) ?? Date()
            if mod < cutoff { try? fm.removeItem(at: url) }
        }
    }

    // MARK: Capacity awareness

    private func recomputeCapacityFlag() {
        isNearCapacity = entries.count >= Int(Double(capacity) * 0.95)
    }

    // MARK: Sidecar (JSONL)

    /// Canonical on-disk location for the sidecar. `Application
    /// Support` is preserved by iCloud Backup but hidden from the
    /// user-visible Files app.
    public static func defaultSidecarURL() -> URL? {
        let fm = FileManager.default
        guard let base = try? fm.url(for: .applicationSupportDirectory,
                                     in: .userDomainMask,
                                     appropriateFor: nil, create: true)
        else { return nil }
        let dir = base.appendingPathComponent("Forestix", isDirectory: true)
        try? fm.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("quick-measure.jsonl")
    }

    private func appendToSidecar(_ entry: QuickMeasureEntry) {
        guard let url = sidecarURL else { return }
        let fm = FileManager.default
        if !fm.fileExists(atPath: url.path) {
            // First write establishes the header line.
            let header = "#v \(Self.schemaVersion)\n"
            try? header.data(using: .utf8)?.write(to: url)
        }
        guard let data = try? JSONEncoder().encode(entry),
              let line = (String(data: data, encoding: .utf8) ?? "") + "\n" as String?
        else { return }
        guard let handle = try? FileHandle(forWritingTo: url) else { return }
        defer { try? handle.close() }
        _ = try? handle.seekToEnd()
        try? handle.write(contentsOf: Data(line.utf8))
    }

    /// Full rewrite — used after delete / clearAll. Cheap at our sizes
    /// (< 500 entries × ~160 bytes = 80 kB).
    private func rewriteSidecar() {
        guard let url = sidecarURL else { return }
        var out = "#v \(Self.schemaVersion)\n"
        for e in entries.reversed() {   // oldest-first on disk for debugging
            guard let data = try? JSONEncoder().encode(e),
                  let line = String(data: data, encoding: .utf8)
            else { continue }
            out += line + "\n"
        }
        try? out.data(using: .utf8)?.write(to: url)
    }

    // MARK: Cache (UserDefaults)

    private func persistCache() {
        do {
            let data = try JSONEncoder().encode(entries)
            defaults.set(data, forKey: Keys.entries)
        } catch {
            // Non-fatal — the JSONL sidecar is the durable store.
        }
    }

    // MARK: Loading

    /// Tries the UserDefaults cache first (fast path). If missing or
    /// unreadable, replays the JSONL sidecar. Last resort: empty log.
    private static func loadBest(defaults: UserDefaults,
                                  sidecar: URL?) -> [QuickMeasureEntry] {
        if let data = defaults.data(forKey: Keys.entries),
           let decoded = try? JSONDecoder().decode(
                [QuickMeasureEntry].self, from: data) {
            return decoded
        }
        return loadSidecar(sidecar)
    }

    private static func loadSidecar(_ url: URL?) -> [QuickMeasureEntry] {
        guard let url, let text = try? String(contentsOf: url, encoding: .utf8)
        else { return [] }
        var lines = text.split(separator: "\n", omittingEmptySubsequences: true)
            .map(String.init)
        // Strip the optional schema header.
        if let first = lines.first, first.hasPrefix("#v") {
            lines.removeFirst()
            // Future: parse version and dispatch to a migrator.
        }
        var out: [QuickMeasureEntry] = []
        for line in lines {
            guard let data = line.data(using: .utf8),
                  let entry = try? JSONDecoder().decode(
                      QuickMeasureEntry.self, from: data)
            else { continue }
            out.append(entry)
        }
        // Sidecar is oldest-first; the view expects newest-first.
        return out.reversed()
    }
}
