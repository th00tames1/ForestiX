import Foundation

/// Portable species identity, independent of regional volume equations.
public struct SpeciesRecord: Codable, Identifiable, Equatable, Sendable {
    public var id: String { code }
    public let code: String
    public let commonName: String
    public let scientificName: String

    public init(code: String, commonName: String, scientificName: String = "") {
        self.code = code; self.commonName = commonName; self.scientificName = scientificName
    }
    public init(_ config: SpeciesConfig) {
        self.init(code: config.code, commonName: config.commonName, scientificName: config.scientificName)
    }
    public func matches(_ query: String) -> Bool {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
        return q.isEmpty || [commonName, scientificName, code].contains {
            $0.range(of: q, options: [.caseInsensitive, .diacriticInsensitive]) != nil
        }
    }
    /// An empty equation id is deliberately unresolved; never bind a new name
    /// to another species' coefficients. Ranges do not constrain measurements.
    public var unconfigured: SpeciesConfig {
        SpeciesConfig(code: code, commonName: commonName, scientificName: scientificName,
            volumeEquationId: "", merchTopDibCm: 0, stumpHeightCm: 0,
            expectedDbhMinCm: 0, expectedDbhMaxCm: .greatestFiniteMagnitude,
            expectedHeightMinM: 0, expectedHeightMaxM: .greatestFiniteMagnitude)
    }
}

public enum SpeciesRegistrationError: Error, LocalizedError {
    case invalidName, duplicateName, invalidArchive, conflictingCode
    public var errorDescription: String? {
        switch self {
        case .invalidName: return "Enter a common name (up to 120 characters) and an optional scientific name (up to 160 characters)."
        case .duplicateName: return "This species is already listed. Select the existing entry."
        case .invalidArchive: return "This is not a supported species catalogue."
        case .conflictingCode: return "A species code already has a different name. Nothing has been overwritten."
        }
    }
}

public enum SpeciesRegistration {
    public static func make(commonName: String, scientificName: String, existing: [SpeciesRecord], id: UUID = UUID()) throws -> SpeciesRecord {
        let name = commonName.trimmingCharacters(in: .whitespacesAndNewlines)
        let scientific = scientificName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard valid(name: name, scientific: scientific) else { throw SpeciesRegistrationError.invalidName }
        guard !existing.contains(where: {
            $0.commonName.caseInsensitiveCompare(name) == .orderedSame ||
            (!scientific.isEmpty && $0.scientificName.caseInsensitiveCompare(scientific) == .orderedSame)
        }) else { throw SpeciesRegistrationError.duplicateName }
        let code = "USR-" + id.uuidString.uppercased()
        guard !existing.contains(where: { $0.code == code }) else { throw SpeciesRegistrationError.conflictingCode }
        return SpeciesRecord(code: code, commonName: name, scientificName: scientific)
    }
    static func valid(name: String, scientific: String) -> Bool {
        !name.isEmpty && name.count <= 120 && scientific.count <= 160 &&
        !name.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) }) &&
        !scientific.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) })
    }
}

/// Names-only backup for registered species, usable without a Cruising project.
/// It cannot import coefficients or overwrite an existing code/name binding.
public struct SpeciesCatalogArchive: Codable, Sendable {
    public var schemaVersion: Int = 1
    public var species: [SpeciesRecord]
    public init(species: [SpeciesRecord]) { self.species = species }
    public func additions(to existing: [SpeciesRecord]) throws -> [SpeciesRecord] {
        guard schemaVersion == 1, species.count <= 10000 else { throw SpeciesRegistrationError.invalidArchive }
        var byCode = Dictionary(existing.map { ($0.code, $0) }, uniquingKeysWith: { first, _ in first })
        var result: [SpeciesRecord] = []
        for s in species {
            guard s.code.hasPrefix("USR-"), UUID(uuidString: String(s.code.dropFirst(4))) != nil,
                  s.code == s.code.uppercased(),
                  SpeciesRegistration.valid(name: s.commonName, scientific: s.scientificName),
                  s.commonName == s.commonName.trimmingCharacters(in: .whitespacesAndNewlines),
                  s.scientificName == s.scientificName.trimmingCharacters(in: .whitespacesAndNewlines)
            else { throw SpeciesRegistrationError.invalidArchive }
            if let old = byCode[s.code] {
                guard old == s else { throw SpeciesRegistrationError.conflictingCode }
            } else { result.append(s); byCode[s.code] = s }
        }
        return result
    }
}

/// In-memory name snapshot for existing result/CSV renderers. Persistence
/// remains the repository's responsibility; no file I/O in a map row or frame.
public final class SpeciesNameLookup: @unchecked Sendable {
    public static let shared = SpeciesNameLookup()
    private let lock = NSLock()
    private var records: [String: SpeciesRecord] = [:]
    public init() {}
    public func replace(_ species: [SpeciesRecord]) {
        lock.lock(); defer { lock.unlock() }
        records = Dictionary(species.map { ($0.code.uppercased(), $0) }, uniquingKeysWith: { _, last in last })
    }
    public func record(_ code: String) -> SpeciesRecord? {
        lock.lock(); defer { lock.unlock() }
        return records[code.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()]
    }
}
