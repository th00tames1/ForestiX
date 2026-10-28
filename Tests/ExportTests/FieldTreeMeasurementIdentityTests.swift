import XCTest
import Foundation
import Models
import Export

final class FieldTreeMeasurementIdentityTests: XCTestCase {
    private func tree() -> Tree {
        var tree = ExportFixtures.trees()[0]
        tree.heightM = 20; tree.heightMethod = .manualEntry; tree.heightSource = "measured"
        tree.dbhMeasurementId = UUID().uuidString.lowercased(); tree.heightMeasurementId = UUID().uuidString.lowercased()
        return tree
    }
    func testMetadataEditsKeepBothUUIDsAndLegacyNativeCodecsRemainCompatible() throws {
        let original = tree(); var edited = original
        edited.notes = "updated metadata"; edited.treeName = "new label"; edited.latitude = 45; edited.speciesCode = "OTHER"
        XCTAssertEqual(try FieldTreeMeasurementIdentity.prepare(edited,previous: original),edited)
        let bytes = try JSONEncoder().encode(edited); XCTAssertEqual(try JSONDecoder().decode(Tree.self,from: bytes),edited)
        var old = try XCTUnwrap(JSONSerialization.jsonObject(with: bytes) as? [String:Any]); old.removeValue(forKey: "dbhMeasurementId"); old.removeValue(forKey: "heightMeasurementId")
        let legacy = try JSONDecoder().decode(Tree.self,from: JSONSerialization.data(withJSONObject: old))
        XCTAssertNil(legacy.dbhMeasurementId); XCTAssertNil(legacy.heightMeasurementId); XCTAssertEqual(legacy.dbhCm,edited.dbhCm)
        let intent = try FieldInventoryUndoIntent(id: UUID().uuidString,saved: edited,original: original,measurementIds: [original.dbhMeasurementId!,original.heightMeasurementId!])
        XCTAssertEqual(try FieldInventoryUndoIntent.decode(intent.encoded()),intent)
    }
    func testUnattributedChangesClearOnlyTheirKindAndUUIDCaseCannotBypassIt() throws {
        let original = tree(); var changed = original
        changed.dbhCm += 1; changed.dbhMeasurementId = original.dbhMeasurementId!.uppercased()
        let dbh = try FieldTreeMeasurementIdentity.prepare(changed,previous: original)
        XCTAssertNil(dbh.dbhMeasurementId); XCTAssertEqual(dbh.heightMeasurementId,original.heightMeasurementId); XCTAssertEqual(dbh.dbhCm,changed.dbhCm)
        changed = original; changed.heightSigmaM = (original.heightSigmaM ?? 0)+1
        let height = try FieldTreeMeasurementIdentity.prepare(changed,previous: original)
        XCTAssertNil(height.heightMeasurementId); XCTAssertEqual(height.dbhMeasurementId,original.dbhMeasurementId)
    }
    func testExplicitNewCaptureAndUndoKeepBoundUUIDsAndMalformedNewStampIsRefused() throws {
        let original = tree(); var next = original; next.dbhCm += 2; next.dbhMeasurementId = UUID().uuidString
        XCTAssertEqual(try FieldTreeMeasurementIdentity.prepare(next,previous: original),next)
        XCTAssertEqual(try FieldTreeMeasurementIdentity.prepare(original,previous: next),original)
        var invalid = next; invalid.dbhMeasurementId = "invalid"
        XCTAssertThrowsError(try FieldTreeMeasurementIdentity.prepare(invalid,previous: original)); XCTAssertEqual(original.heightM,20)
    }
    func testDBHRemeasurementPreservesIndependentHeightUUIDAndTreeCSVExportsBoth() throws {
        let original = tree(); var measurement = original
        measurement.dbhCm += 2; measurement.dbhMeasurementId = UUID().uuidString; measurement.heightMeasurementId = UUID().uuidString
        let merged = try FieldCruiseTreeUpdate.dbh(previous: original,measurement: measurement,speciesOverride: nil)
        XCTAssertEqual(merged.dbhMeasurementId,measurement.dbhMeasurementId); XCTAssertEqual(merged.heightMeasurementId,original.heightMeasurementId)
        let csv = CSVExporter.treesCSV(trees: [merged]); XCTAssertTrue(csv.contains("dbh_measurement_id,height_measurement_id,species_common_name,species_scientific_name\r\n"))
        XCTAssertTrue(csv.contains(","+merged.dbhMeasurementId!+","+merged.heightMeasurementId!+",,\r\n"))
    }
    func testCruiseExportRequiresActualPerKindUUIDEvenWhenValuesMatch() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("forestix-tree-id-export-"+UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let a = FieldStudyArchive(root: root.appendingPathComponent("field")); var c = FieldStudyConfiguration(); c.enabled = true; c.studyId = "SYNTHETIC"; c.operatorId = "OP-1"
        try a.saveConfiguration(c,developerMode: true); let row = UUID().uuidString
        var r = FieldMeasurementRecord(sessionId: UUID().uuidString,treeId: "unassigned",kind: .dbh,attemptNumber: 1,configuration: c,runtimeSettings: [:])
        r.outcome = .result; r.selection = .accepted; r.liveValue = 25; r.resultRecordId = row; r.resultStore = "cruise"; try a.createMeasurement(r)
        func build(_ id: String?,_ kind: FieldStudyKind = .dbh) throws -> FieldStudyExportArtifact {
            try FieldStudyExport.build(archive: a,rawRoot: root.appendingPathComponent("raw"),results: [.init(id: row,store: "cruise",kind: kind,value: 25,measurementId: id)],outputDirectory: root.appendingPathComponent("exports"))
        }
        XCTAssertTrue(try build(r.id).report.integrityPassed)
        XCTAssertTrue(try build(nil).report.issues.contains { $0.code == "application_measurement_identity_unrecorded" })
        XCTAssertTrue(try build(UUID().uuidString).report.issues.contains { $0.code == "application_measurement_identity_mismatch" })
        XCTAssertTrue(try build(r.id,.height).report.issues.contains { $0.code == "application_without_measurement" })
        XCTAssertEqual(try a.measurement(id: r.id),r)
    }
}
