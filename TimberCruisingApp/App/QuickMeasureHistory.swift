// Lightweight on-device log of one-off DBH / Height measurements captured
// from the Quick Measure entry point. These are NOT Tree/Plot records —
// just the last-N readings a cruiser wants to glance back at or export
// without opening the full project workflow.
//
// Storage: a single JSON-encoded array in UserDefaults. Entries are value
// types, sortable by `createdAt` (newest first). CSV export is provided
// so a cruiser can AirDrop / email a session's worth of readings.
//
// This store is independent of Core Data on purpose: Quick Measure users
// haven't set up a project, so there's no valid Tree row to attach to.
// If a user later flips "Advanced mode" on they can still re-enter the
// same readings against a real plot — the Quick Measure log is a
// separate surface.

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
}

// MARK: - Store

@MainActor
public final class QuickMeasureHistory: ObservableObject {

    public enum Keys {
        public static let entries = "tc.quickMeasure.entries"
    }

    @Published public private(set) var entries: [QuickMeasureEntry] = []

    private let defaults: UserDefaults
    private let capacity: Int

    public init(defaults: UserDefaults = .standard, capacity: Int = 200) {
        self.defaults = defaults
        self.capacity = capacity
        self.entries = Self.load(from: defaults)
    }

    /// Test / preview factory backed by an isolated UserDefaults suite.
    public static func ephemeral(capacity: Int = 200) -> QuickMeasureHistory {
        let name = "tc.quickMeasure.preview.\(UUID().uuidString)"
        let ud = UserDefaults(suiteName: name) ?? .standard
        return QuickMeasureHistory(defaults: ud, capacity: capacity)
    }

    // MARK: Mutations

    public func append(_ entry: QuickMeasureEntry) {
        var next = entries
        next.insert(entry, at: 0)
        if next.count > capacity {
            next = Array(next.prefix(capacity))
        }
        entries = next
        persist()
    }

    public func delete(id: UUID) {
        entries.removeAll { $0.id == id }
        persist()
    }

    public func clearAll() {
        entries = []
        persist()
    }

    // MARK: CSV export

    /// Writes the current history to `Documents/Exports/quick-measure-<ts>.csv`
    /// and returns the URL, or `nil` if nothing to export or write failed.
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

        var out = "timestamp,kind,value,unit,sigma,confidence,method\n"
        for e in entries {
            let unit = (e.kind == .dbh) ? "cm" : "m"
            let sigma = e.sigma.map { String(format: "%.3f", $0) } ?? ""
            out += "\(iso.string(from: e.createdAt)),"
            out += "\(e.kind.rawValue),"
            out += String(format: "%.3f", e.value) + ","
            out += "\(unit),\(sigma),\(e.confidenceRaw),\(e.method)\n"
        }

        do {
            try out.data(using: .utf8)?.write(to: url)
            return url
        } catch {
            return nil
        }
    }

    // MARK: Persistence

    private func persist() {
        do {
            let data = try JSONEncoder().encode(entries)
            defaults.set(data, forKey: Keys.entries)
        } catch {
            // Non-fatal — next write will retry. Don't crash the UI.
        }
    }

    private static func load(from defaults: UserDefaults) -> [QuickMeasureEntry] {
        guard let data = defaults.data(forKey: Keys.entries) else { return [] }
        return (try? JSONDecoder().decode([QuickMeasureEntry].self, from: data)) ?? []
    }
}
