// Spec §8 Export/TrackLogRepository. REQ-NAV-004.
//
// Per-session NDJSON breadcrumb log. Writes one JSON object per line
// to `Documents/tracklogs/{session-uuid}.ndjson`. Append-only so a
// crash mid-cruise can't corrupt prior fixes. Read-back helpers
// materialise the file into GPXTrackPoints for export.

import Foundation

public struct TrackLogEntry: Codable, Sendable, Equatable {
    public let lat: Double
    public let lon: Double
    public let timestamp: Date
    public let horizontalAccuracyM: Double?

    public init(
        lat: Double, lon: Double,
        timestamp: Date,
        horizontalAccuracyM: Double? = nil
    ) {
        self.lat = lat; self.lon = lon
        self.timestamp = timestamp
        self.horizontalAccuracyM = horizontalAccuracyM
    }
}

public protocol TrackLogRepository: Sendable {
    func append(sessionId: UUID, entry: TrackLogEntry) throws
    func readAll(sessionId: UUID) throws -> [TrackLogEntry]
    func deleteSession(_ sessionId: UUID) throws
    func fileURL(for sessionId: UUID) throws -> URL
}

public final class FileTrackLogRepository: TrackLogRepository {

    public let rootDirectory: URL

    public init(rootDirectory: URL) {
        self.rootDirectory = rootDirectory
    }

    /// Convenience: writes under `<Documents>/tracklogs/`.
    public static func `default`() throws -> FileTrackLogRepository {
        let docs = try FileManager.default.url(
            for: .documentDirectory, in: .userDomainMask,
            appropriateFor: nil, create: true)
        let dir = docs.appendingPathComponent("tracklogs", isDirectory: true)
        return FileTrackLogRepository(rootDirectory: dir)
    }

    public func fileURL(for sessionId: UUID) throws -> URL {
        try ensureDir()
        return rootDirectory
            .appendingPathComponent("\(sessionId.uuidString).ndjson")
    }

    public func append(sessionId: UUID, entry: TrackLogEntry) throws {
        let url = try fileURL(for: sessionId)
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        var line = try encoder.encode(entry)
        line.append(UInt8(ascii: "\n"))
        if FileManager.default.fileExists(atPath: url.path) {
            let handle = try FileHandle(forWritingTo: url)
            try handle.seekToEnd()
            try handle.write(contentsOf: line)
            try handle.close()
        } else {
            try line.write(to: url, options: .atomic)
        }
    }

    public func readAll(sessionId: UUID) throws -> [TrackLogEntry] {
        let url = try fileURL(for: sessionId)
        guard FileManager.default.fileExists(atPath: url.path) else { return [] }
        let raw = try String(contentsOf: url, encoding: .utf8)
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return try raw.split(separator: "\n")
            .filter { !$0.isEmpty }
            .map { line in
                try decoder.decode(
                    TrackLogEntry.self,
                    from: Data(line.utf8))
            }
    }

    public func deleteSession(_ sessionId: UUID) throws {
        let url = try fileURL(for: sessionId)
        if FileManager.default.fileExists(atPath: url.path) {
            try FileManager.default.removeItem(at: url)
        }
    }

    private func ensureDir() throws {
        try FileManager.default.createDirectory(
            at: rootDirectory,
            withIntermediateDirectories: true)
    }
}
