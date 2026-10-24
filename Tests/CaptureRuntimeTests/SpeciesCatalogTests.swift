import XCTest
import Common
import Models
import Persistence
#if canImport(UI)
@testable import UI
#else
@testable import CaptureRuntime
#endif

private final class SpeciesTestRepository: SpeciesConfigRepository {
    var values: [SpeciesConfig] = []
    var failing = false
    func create(_ s: SpeciesConfig) throws -> SpeciesConfig { values.append(s); return s }
    func read(code: String) throws -> SpeciesConfig? { values.first { $0.code == code } }
    func update(_ s: SpeciesConfig) throws -> SpeciesConfig { values.removeAll { $0.code == s.code }; values.append(s); return s }
    func delete(code: String) throws { values.removeAll { $0.code == code } }
    func list() throws -> [SpeciesConfig] { if failing { throw SpeciesRegistrationError.invalidArchive }; return values }
}

final class SpeciesCatalogTests: XCTestCase {
    @MainActor func testInternationalBundleDoesNotApplyAnImplicitUSLogRule() throws {
        let history = QuickMeasureHistory.ephemeral()
        let plot = history.createPlot(name: "Synthetic plot", acres: 0.1)
        history.append(.typed(kind: .dbh, value: 50, treeNumber: 1, plotID: plot.id))
        history.append(.typed(kind: .height, value: 20, treeNumber: 1, plotID: plot.id))
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        func calculations(country: Country) throws -> String {
            let bytes = try Data(contentsOf: XCTUnwrap(history.exportBundle(country: country, directory: root)))
            return try XCTUnwrap(String(data: XCTUnwrap(Common.ZipReader.readAll(bytes).first { $0.name == "Calculations.csv" }).data, encoding: .utf8))
        }
        XCTAssertTrue(try calculations(country: .unitedStates).contains("\"scribner\""))
        let international = try calculations(country: .international)
        XCTAssertFalse(international.contains("\"scribner\""))
        XCTAssertTrue(international.contains(",\"\",\"\",\"\"\r\n"))
        XCTAssertEqual(history.entries.count, 2)
    }
    @MainActor func testCountryBackwardsCompatibilityAndInternationalDefaults() {
        XCTAssertEqual(Country.allCases.map(\.rawValue), ["us", "fi", "de", "kr", "international"])
        XCTAssertEqual(Country.fromRaw(nil), .unitedStates); XCTAssertEqual(Country.fromRaw("unknown"), .unitedStates)
        XCTAssertEqual(Country.international.defaultUnitSystem, .metric)
        XCTAssertFalse(Country.international.usesLogRule); XCTAssertFalse(Country.international.hasRegions)
        XCTAssertEqual(Country.international.volumeStandard, .unconfigured)
        XCTAssertTrue(Country.international.volumeStandard.isPending)
        let settings = AppSettings.ephemeral(); settings.country = .international; settings.unitSystem = .imperial
        XCTAssertEqual(settings.unitSystem, .imperial)
    }
    @MainActor func testAllSpeciesNoLongerEmptyAndRegisteredSpeciesAvailableEverywhere() throws {
        let repo = SpeciesTestRepository(), catalog = SpeciesCatalogStore(repository: SpeciesTestRepository())
        XCTAssertGreaterThan(catalog.options(country: .unitedStates, region: .all).count, 30)
        XCTAssertEqual(catalog.options(country: .unitedStates, region: .pnwEast).first { $0.code == "LP" }?.commonName, "Lodgepole pine")
        XCTAssertEqual(catalog.options(country: .unitedStates, region: .coastalPlain).first { $0.code == "LP" }?.commonName, "Loblolly pine")
        let store = SpeciesCatalogStore(repository: repo)
        let registered = try store.add(commonName: "Synthetic oak", scientificName: "Testus oak")
        for country in Country.allCases { XCTAssertTrue(store.options(country: country, region: .pnwWest).contains(registered)) }
        XCTAssertEqual(RegionalSpecies.name(forCode: registered.code), "Synthetic oak")
        XCTAssertTrue(store.records.contains { $0.matches("testus") })
        let reopened = SpeciesCatalogStore(repository: repo)
        XCTAssertTrue(reopened.records.contains(registered))
        XCTAssertEqual(try repo.read(code: registered.code)?.volumeEquationId, "")
    }
    @MainActor func testStandaloneBackupRestoreWithoutProjectAndNoOverwrite() throws {
        let source = SpeciesCatalogStore(repository: SpeciesTestRepository())
        let registered = try source.add(commonName: "Synthetic birch", scientificName: "")
        let destination = SpeciesCatalogStore(repository: SpeciesTestRepository())
        XCTAssertEqual(try destination.restore(source.archiveData()), 1)
        XCTAssertEqual(try destination.restore(source.archiveData()), 0)
        XCTAssertTrue(destination.records.contains(registered))
        let conflict = SpeciesCatalogArchive(species: [SpeciesRecord(code: registered.code, commonName: "Wrong")])
        XCTAssertThrowsError(try destination.restore(JSONEncoder().encode(conflict)))
        XCTAssertTrue(destination.records.contains(registered))
    }
    @MainActor func testReadFailureDoesNotPretendToRegisterOrExport() throws {
        let repo = SpeciesTestRepository(); repo.failing = true
        let store = SpeciesCatalogStore(repository: repo)
        XCTAssertNotNil(store.loadError)
        XCTAssertThrowsError(try store.add(commonName: "Lost", scientificName: ""))
        XCTAssertThrowsError(try store.archiveData())
        XCTAssertTrue(repo.values.isEmpty)
    }
    @MainActor func testQuickCSVAndBundleCarryNamesWithoutChangingStoredValues() throws {
        let catalog = SpeciesCatalogStore(repository: SpeciesTestRepository())
        let s = try catalog.add(commonName: "Synthetic \"oak\", A", scientificName: "Testus alpha")
        let history = QuickMeasureHistory.ephemeral()
        let e = QuickMeasureEntry.typed(kind: .dbh, value: 23.5, treeNumber: 1, plotID: nil, speciesCode: s.code)
        history.append(e)
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let csv = try String(contentsOf: XCTUnwrap(history.exportCSV(directory: root)), encoding: .utf8)
        XCTAssertTrue(csv.contains("species_common_name")); XCTAssertTrue(csv.contains("Synthetic \"\"oak\"\", A"))
        XCTAssertTrue(csv.contains("Testus alpha")); XCTAssertTrue(csv.contains(s.code))
        XCTAssertEqual(history.entries.first?.value, 23.5)
        let zip = try Data(contentsOf: XCTUnwrap(history.exportBundle(directory: root)))
        let files = Dictionary(uniqueKeysWithValues: try Common.ZipReader.readAll(zip).map { ($0.name, $0.data) })
        XCTAssertTrue(try XCTUnwrap(String(data: XCTUnwrap(files["Trees.csv"]), encoding: .utf8)).contains("Testus alpha"))
    }
}
