import Foundation
import Combine
import Models
import Persistence

@MainActor
public final class SpeciesCatalogStore: ObservableObject {
    @Published public private(set) var records: [SpeciesRecord] = []
    @Published public private(set) var loadError: String?
    private let repository: any SpeciesConfigRepository

    public init(repository: any SpeciesConfigRepository) {
        self.repository = repository
        refresh()
    }
    public func refresh() {
        do {
            var all: [String: SpeciesRecord] = [:]
            for region in Region.allCases where region != .all {
                for (code, name) in RegionalSpecies.defaultSpecies(for: region) where all[code] == nil {
                    all[code] = SpeciesRecord(code: code, commonName: name)
                }
            }
            for (code, name) in Country.allNonUSSpecies { all[code] = SpeciesRecord(code: code, commonName: name) }
            for s in try repository.list() { all[s.code] = SpeciesRecord(s) }
            all["OT"] = SpeciesRecord(code: "OT", commonName: "Other")
            records = all.values.sorted {
                let order = $0.commonName.localizedCaseInsensitiveCompare($1.commonName)
                return order == .orderedSame ? $0.code < $1.code : order == .orderedAscending
            }
            SpeciesNameLookup.shared.replace(records)
            loadError = nil
        } catch { loadError = "Unable to load saved species. Try again before adding a species." }
    }
    public func options(country: Country, region: Region?) -> [SpeciesRecord] {
        if country == .international || (country == .unitedStates && (region == nil || region == .all)) { return records }
        // Legacy US codes collide between regions (e.g. LP). Keep each
        // curated region's original label/order; never relabel those choices
        // using a different region's canonical display-name fallback.
        let byCode = Dictionary(records.map { ($0.code, $0) }, uniquingKeysWith: { first, _ in first })
        let presets = country.speciesPresets(region: region).map { code, name in
            SpeciesRecord(code: code, commonName: name,
                scientificName: byCode[code]?.commonName == name ? byCode[code]?.scientificName ?? "" : "")
        }
        return presets + records.filter { $0.code.hasPrefix("USR-") || $0.code == "OT" }
    }
    @discardableResult
    public func add(commonName: String, scientificName: String) throws -> SpeciesRecord {
        // Read first. A failed load must not silently register a duplicate.
        _ = try repository.list()
        refresh()
        guard loadError == nil else { throw SpeciesRegistrationError.invalidArchive }
        let record = try SpeciesRegistration.make(commonName: commonName, scientificName: scientificName, existing: records)
        _ = try repository.create(record.unconfigured)
        refresh()
        return record
    }
    public func archiveData() throws -> Data {
        let saved = try repository.list().filter { $0.code.hasPrefix("USR-") }.map(SpeciesRecord.init)
        let encoder = JSONEncoder(); encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        return try encoder.encode(SpeciesCatalogArchive(species: saved))
    }
    public func restore(_ data: Data) throws -> Int {
        guard data.count <= 8 * 1024 * 1024 else { throw SpeciesRegistrationError.invalidArchive }
        let archive = try JSONDecoder().decode(SpeciesCatalogArchive.self, from: data)
        let saved = try repository.list().map(SpeciesRecord.init)
        let additions = try archive.additions(to: saved)
        for s in additions { _ = try repository.create(s.unconfigured) }
        refresh()
        return additions.count
    }
}
