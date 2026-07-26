// On-disk home of the ONE imported survey boundary.
//
// Layout — Application Support (not Caches: a cruiser who imported a
// stand boundary before driving into the woods must not have the OS
// purge it under disk pressure), mirroring the basemap tile root:
//
//     <AppSupport>/Forestix/survey-boundary/
//         boundary.geojson   normalised RFC 7946 FeatureCollection
//         boundary.json      the small record the sheet shows
//                            { displayName, featureCount, importedAt,
//                              sourceFormat }
//
// One boundary at a time: importing another overwrites both files;
// Remove deletes the directory. The record is stored separately from the
// geometry so the sheet's row (name · feature count · date) can be read
// without deserialising a large FeatureCollection.

import Foundation

public final class SurveyBoundaryStore: @unchecked Sendable {

    public struct Record: Codable, Equatable, Sendable {
        public var displayName: String
        public var featureCount: Int
        public var importedAt: Date
        public var sourceFormat: String

        public init(displayName: String, featureCount: Int,
                    importedAt: Date, sourceFormat: String) {
            self.displayName = displayName
            self.featureCount = featureCount
            self.importedAt = importedAt
            self.sourceFormat = sourceFormat
        }
    }

    private let root: URL
    private let fm: FileManager

    public init(rootURL: URL? = nil, fileManager: FileManager = .default) {
        self.fm = fileManager
        self.root = rootURL ?? Self.defaultRoot(fileManager: fileManager)
    }

    public static func defaultRoot(fileManager fm: FileManager = .default) -> URL {
        let base = (try? fm.url(for: .applicationSupportDirectory,
                                in: .userDomainMask,
                                appropriateFor: nil, create: true))
            ?? fm.temporaryDirectory
        return base
            .appendingPathComponent("Forestix", isDirectory: true)
            .appendingPathComponent("survey-boundary", isDirectory: true)
    }

    public var geoJSONURL: URL { root.appendingPathComponent("boundary.geojson") }
    public var recordURL: URL { root.appendingPathComponent("boundary.json") }

    // MARK: - Read

    public var hasBoundary: Bool {
        fm.fileExists(atPath: recordURL.path) && fm.fileExists(atPath: geoJSONURL.path)
    }

    /// The record alone — cheap, for the sheet's current-state row.
    public func loadRecord() -> Record? {
        guard let data = try? Data(contentsOf: recordURL) else { return nil }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return try? decoder.decode(Record.self, from: data)
    }

    /// Record + geometry. Returns nil when nothing is stored; throws only
    /// when what IS stored cannot be read back (corrupt file), so the
    /// caller can say so instead of silently drawing nothing.
    public func load() throws -> SurveyBoundary? {
        guard let record = loadRecord() else { return nil }
        guard let geo = try? Data(contentsOf: geoJSONURL) else { return nil }
        let features = try SurveyBoundary.features(fromGeoJSON: geo)
        // Belt and braces: the range gate again on read-back, so a file
        // edited on disk can never sneak projected coordinates onto the map.
        try SurveyBoundaryImporter.verifyCoordinateRange(features)
        return SurveyBoundary(displayName: record.displayName,
                              importedAt: record.importedAt,
                              sourceFormat: record.sourceFormat,
                              features: features)
    }

    // MARK: - Write

    @discardableResult
    public func save(_ boundary: SurveyBoundary) throws -> Record {
        try fm.createDirectory(at: root, withIntermediateDirectories: true)
        let geo = try boundary.geoJSONData()
        try geo.write(to: geoJSONURL, options: .atomic)

        let record = Record(displayName: boundary.displayName,
                            featureCount: boundary.featureCount,
                            importedAt: boundary.importedAt,
                            sourceFormat: boundary.sourceFormat)
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        encoder.outputFormatting = [.sortedKeys]
        try encoder.encode(record).write(to: recordURL, options: .atomic)
        return record
    }

    public func clear() {
        try? fm.removeItem(at: geoJSONURL)
        try? fm.removeItem(at: recordURL)
        try? fm.removeItem(at: root)
    }
}
