import XCTest
@testable import Models

final class SpeciesRegistrationTests: XCTestCase {
    private let id = UUID(uuidString: "12345678-1234-1234-1234-123456789ABC")!
    func testRegistrationHasStableCodeAndNoInventedEquation() throws {
        let s = try SpeciesRegistration.make(commonName: "  Test cedar  ", scientificName: " Testus cedrus ", existing: [], id: id)
        XCTAssertEqual(s.code, "USR-12345678-1234-1234-1234-123456789ABC")
        XCTAssertEqual(s.commonName, "Test cedar"); XCTAssertEqual(s.scientificName, "Testus cedrus")
        XCTAssertEqual(s.unconfigured.volumeEquationId, "")
        XCTAssertTrue(s.matches("CEDRUS")); XCTAssertTrue(s.matches("test cedar")); XCTAssertTrue(s.matches("12345678"))
    }
    func testScientificNameIsOptional() throws {
        XCTAssertEqual(try SpeciesRegistration.make(commonName: "Sample", scientificName: "", existing: []).scientificName, "")
    }
    func testEmptyLongAndControlNamesRejected() {
        for name in [" ", String(repeating: "x", count: 121), "Name\nother"] {
            XCTAssertThrowsError(try SpeciesRegistration.make(commonName: name, scientificName: "", existing: []))
        }
    }
    func testDuplicateNamesAndCodeRejected() throws {
        let old = try SpeciesRegistration.make(commonName: "Sample", scientificName: "Example species", existing: [], id: id)
        XCTAssertThrowsError(try SpeciesRegistration.make(commonName: " sample ", scientificName: "", existing: [old]))
        XCTAssertThrowsError(try SpeciesRegistration.make(commonName: "Other", scientificName: "EXAMPLE SPECIES", existing: [old]))
        XCTAssertThrowsError(try SpeciesRegistration.make(commonName: "Other", scientificName: "", existing: [old], id: id))
    }
    func testPortableArchiveRoundTripAndIdempotentRestore() throws {
        let s = try SpeciesRegistration.make(commonName: "시험 수종, \"A\"", scientificName: "Testus example", existing: [], id: id)
        let archive = SpeciesCatalogArchive(species: [s])
        let restored = try JSONDecoder().decode(SpeciesCatalogArchive.self, from: JSONEncoder().encode(archive))
        XCTAssertEqual(try restored.additions(to: []), [s])
        XCTAssertEqual(try restored.additions(to: [s]), [])
        // Same wire format as Android; schemaVersion must be encoded.
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(archive)) as? [String: Any])
        XCTAssertEqual(json["schemaVersion"] as? Int, 1)
    }
    func testConflictsAndUnsupportedFilesRejectedBeforeWriting() throws {
        let old = try SpeciesRegistration.make(commonName: "Sample", scientificName: "", existing: [], id: id)
        let changed = SpeciesRecord(code: old.code, commonName: "Different")
        XCTAssertThrowsError(try SpeciesCatalogArchive(species: [changed]).additions(to: [old]))
        XCTAssertThrowsError(try SpeciesCatalogArchive(species: [SpeciesRecord(code: "DF", commonName: "Replacement")]).additions(to: []))
        var future = SpeciesCatalogArchive(species: [old]); future.schemaVersion = 2
        XCTAssertThrowsError(try future.additions(to: []))
    }
    func testLookupIsCaseInsensitiveWithoutFileIO() {
        let lookup = SpeciesNameLookup()
        lookup.replace([SpeciesRecord(code: "USR-TEST", commonName: "Test cedar", scientificName: "Testus")])
        XCTAssertEqual(lookup.record(" usr-test ")?.scientificName, "Testus")
        XCTAssertNil(lookup.record("unknown"))
    }
}
