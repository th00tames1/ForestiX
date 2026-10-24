import XCTest
import Models
import Common

final class FieldCruiseBindingTests: XCTestCase {
    private let plot = UUID().uuidString
    private let target = FieldStudyTarget(studyId: "SYNTHETIC",treeId: "0003",label: "003")
    private let other = FieldStudyTarget(studyId: "SYNTHETIC",treeId: "0004",label: "004")
    private func row(_ number: Int = 7,plot: String? = nil,deleted: Bool = false,id: String = UUID().uuidString) -> FieldInventoryRow {
        .init(id: id,plotId: plot ?? self.plot,treeNumber: number,deleted: deleted)
    }
    private func record(_ row: FieldInventoryRow,kind: FieldStudyKind = .dbh,target: FieldStudyTarget? = nil) throws -> FieldMeasurementRecord {
        let t = target ?? self.target,id = UUID().uuidString
        var c = FieldStudyConfiguration(); c.enabled = true; c.studyId = t.studyId; c.operatorId = "TEST"
        var s = FieldMeasurementSession(now: 0)
        try s.begin(id: id,configuration: c,target: t,kind: kind,runtime: [:],references: [],rawRequested: false,aiEnabled: false,typed: false,now: 1)
        try s.finish(id: id,value: 25,sigma: 1,valid: true,reason: nil,ai: .init(),now: 2)
        try s.accept(id: id,resultRecordId: row.id,resultStore: "cruise",now: 3)
        return try XCTUnwrap(s.current)
    }
    private func resolve(_ rows: [FieldInventoryRow],_ records: [FieldMeasurementRecord]) throws -> FieldCruiseTargetScope {
        try FieldCruiseTargetBinding.resolve(target: target,plotId: plot,rows: rows,records: records)
    }
    func testDistinctDbhAndHeightAttemptIDsShareOneInventoryRow() throws {
        let r = row(id: UUID().uuidString.lowercased()),d = try record(r),h = try record(r,kind: .height)
        let result = try resolve([r],[d,h])
        XCTAssertNotEqual(d.id,h.id); XCTAssertNotEqual(d.id,r.id)
        XCTAssertEqual(result.existingRowId,r.id); XCTAssertEqual(result.treeNumber,7)
        XCTAssertEqual(Set(result.sourceMeasurementIds),Set([d.id.lowercased(),h.id.lowercased()]))
        XCTAssertEqual(result,try FieldCruiseTargetBinding.resolve(target: target,plotId: plot.lowercased(),rows: [r],records: [d,h]))
    }
    func testExternalTreeIDDoesNotBecomeInventoryNumberAndDeletedNumbersStayReserved() throws {
        let r = row(12),deleted = row(15,deleted: true),foreign = row(800,plot: UUID().uuidString)
        let result = try resolve([r,deleted,foreign],[])
        XCTAssertEqual(result.treeNumber,16); XCTAssertNil(result.existingRowId); XCTAssertEqual(target.treeId,"0003")
        XCTAssertTrue(result.sourceMeasurementIds.isEmpty)
    }
    func testMissingDeletedAndWrongPlotRowsNeverSupplyExistingBinding() throws {
        let r = row(),record = try record(r)
        XCTAssertNil(try resolve([],[record]).existingRowId)
        let deleted = row(7,deleted: true,id: r.id)
        XCTAssertNil(try resolve([deleted],[record]).existingRowId)
        let moved = row(7,plot: UUID().uuidString,id: r.id)
        XCTAssertNil(try resolve([moved],[record]).existingRowId)
    }
    func testPendingFailedDiscardedAndQuickRecordsDoNotBindCruise() throws {
        let r = row(),record = try record(r)
        var pending = record; pending.selection = .pending
        var failed = record; failed.outcome = .failed
        var discarded = record; discarded.selection = .discarded
        var quick = record; quick.resultStore = "quick_history"
        for ignored in [pending,failed,discarded,quick] { XCTAssertNil(try resolve([r],[ignored]).existingRowId) }
        var superseded = record; superseded.selection = .superseded
        XCTAssertEqual(try resolve([r],[superseded]).existingRowId,r.id.lowercased())
    }
    func testConflictingRowsAndOwnersAreRefused() throws {
        let a = row(2),b = row(8),ar = try record(a),br = try record(b)
        XCTAssertThrowsError(try resolve([a,b],[ar,br]))
        let foreign = try record(a,target: other)
        XCTAssertThrowsError(try resolve([a],[ar,foreign]))
        let unassignedDuplicate = row(2)
        XCTAssertThrowsError(try resolve([a,unassignedDuplicate],[ar]))
        let deletedDuplicate = row(2,deleted: true)
        XCTAssertEqual(try resolve([a,deletedDuplicate],[ar]).existingRowId,a.id.lowercased())
    }
    func testDuplicateUUIDMalformedIDsAndNumberCapacityAreRefused() throws {
        let a = row(),ar = try record(a)
        XCTAssertThrowsError(try resolve([a,a],[ar]))
        XCTAssertThrowsError(try resolve([row(id: "not-a-uuid")],[]))
        XCTAssertThrowsError(try resolve([row(plot: "not-a-uuid")],[]))
        XCTAssertThrowsError(try resolve([row(2_147_483_647)],[]))
        let zero = row(0); XCTAssertThrowsError(try resolve([zero],[try record(zero)]))
        var malformed = ar; malformed.id = "not-a-uuid"
        XCTAssertThrowsError(try resolve([a],[malformed]))
    }
    private func tree() -> Tree {
        .init(id: UUID(),plotId: UUID(),treeNumber: 7,treeName: "P1-T07",speciesCode: "DF",status: .deadStanding,
              dbhCm: 30,dbhMethod: .manualCaliper,dbhSigmaMm: 1,dbhRmseMm: 2,dbhCoverageDeg: 90,dbhNInliers: 60,
              dbhConfidence: .green,dbhIsIrregular: true,dbhCaptureMode: "typed",dbhEstimatorEpoch: 1,
              heightM: 21,heightMethod: .manualEntry,heightSource: "measured",heightSigmaM: 0.2,heightDHM: 10,
              heightAlphaTopDeg: 50,heightAlphaBaseDeg: -5,heightConfidence: .yellow,
              bearingFromCenterDeg: 25,distanceFromCenterM: 4,boundaryCall: "in",crownClass: "dominant",
              damageCodes: ["OLD"],isMultistem: true,parentTreeId: UUID(),notes: "prior note",photoPath: "old.jpg",rawScanPath: "old.raw",
              createdAt: Date(timeIntervalSince1970: 100),updatedAt: Date(timeIntervalSince1970: 101),deletedAt: nil,latitude: 45,longitude: -123)
    }
    func testDbhReplacementChangesOnlyPedigreeAndExplicitNewMetadata() throws {
        let old = tree(); var measurement = tree(); measurement.plotId = old.plotId
        measurement.dbhCm = 42; measurement.dbhMethod = .lidarChordSilhouette; measurement.dbhSigmaMm = 3
        measurement.dbhRmseMm = nil; measurement.dbhCoverageDeg = 100; measurement.dbhNInliers = 80
        measurement.dbhConfidence = .yellow; measurement.dbhIsIrregular = false; measurement.dbhCaptureMode = "auto"; measurement.dbhEstimatorEpoch = 7
        measurement.heightM = nil; measurement.notes = "new note"; measurement.damageCodes = ["OLD","NEW","NEW"]
        measurement.photoPath = "new.jpg"; measurement.rawScanPath = nil; measurement.latitude = 0; measurement.longitude = 0
        measurement.updatedAt = Date(timeIntervalSince1970: 200)
        let next = try FieldCruiseTreeUpdate.dbh(previous: old,measurement: measurement,speciesOverride: nil)
        var expected = old
        expected.dbhCm = 42; expected.dbhMethod = .lidarChordSilhouette; expected.dbhSigmaMm = 3
        expected.dbhRmseMm = nil; expected.dbhCoverageDeg = 100; expected.dbhNInliers = 80
        expected.dbhConfidence = .yellow; expected.dbhIsIrregular = false; expected.dbhCaptureMode = "auto"; expected.dbhEstimatorEpoch = 7
        expected.notes += "\nnew note"; expected.damageCodes += ["NEW"]; expected.photoPath = "new.jpg"; expected.rawScanPath = nil; expected.updatedAt = measurement.updatedAt
        XCTAssertEqual(next,expected); XCTAssertEqual(old.notes,"prior note"); XCTAssertEqual(old.damageCodes,["OLD"])
    }
    func testEmptyMetadataNeverErasesAndOnlyFullyMissingPositionCanBeFilled() throws {
        var old = tree(),measurement = tree(); measurement.plotId = old.plotId
        measurement.notes = ""; measurement.damageCodes = []; measurement.photoPath = nil
        XCTAssertEqual(try FieldCruiseTreeUpdate.dbh(previous: old,measurement: measurement,speciesOverride: nil).photoPath,"old.jpg")
        XCTAssertEqual(try FieldCruiseTreeUpdate.dbh(previous: old,measurement: measurement,speciesOverride: "WH").speciesCode,"WH")
        measurement.notes = old.notes
        XCTAssertEqual(try FieldCruiseTreeUpdate.dbh(previous: old,measurement: measurement,speciesOverride: nil).notes,old.notes)
        old.longitude = nil; old.distanceFromCenterM = nil; measurement.latitude = 2; measurement.longitude = 3
        let partial = try FieldCruiseTreeUpdate.dbh(previous: old,measurement: measurement,speciesOverride: nil)
        XCTAssertEqual(partial.latitude,45); XCTAssertNil(partial.longitude); XCTAssertNil(partial.distanceFromCenterM)
        old.latitude = nil; old.bearingFromCenterDeg = nil
        let missing = try FieldCruiseTreeUpdate.dbh(previous: old,measurement: measurement,speciesOverride: nil)
        XCTAssertEqual(missing.latitude,2); XCTAssertEqual(missing.longitude,3); XCTAssertEqual(missing.distanceFromCenterM,4)
    }
    func testMovedOrDeletedRowCannotBeRemeasured() throws {
        var old = tree(); let measurement = tree()
        XCTAssertThrowsError(try FieldCruiseTreeUpdate.dbh(previous: old,measurement: measurement,speciesOverride: nil))
        old.plotId = measurement.plotId; old.deletedAt = Date()
        XCTAssertThrowsError(try FieldCruiseTreeUpdate.dbh(previous: old,measurement: measurement,speciesOverride: nil))
    }
}
