import XCTest
import Models

final class FieldApplicationUndoTests: XCTestCase {
    private let row = UUID().uuidString
    private func record(kind: FieldStudyKind = .dbh,round: Int = 1) throws -> FieldMeasurementRecord {
        var c = FieldStudyConfiguration(); c.enabled = true; c.studyId = "SYNTHETIC"; c.operatorId = "TEST"; c.plannedRepetitions = 3; c.plannedRepeat = round
        let target = FieldStudyTarget(studyId: c.studyId,treeId: "0003",label: "003")
        var s = FieldMeasurementSession(now: 0); let id = UUID().uuidString,flow = FieldUIFlowClock(kind: kind)
        flow.open(now: 0,foreground: true)
        try s.begin(id: id,configuration: c,target: target,kind: kind,runtime: [:],references: [],rawRequested: true,aiEnabled: false,typed: false,now: 1)
        try s.finish(id: id,value: kind == .dbh ? 30 : 20,sigma: 1,valid: true,reason: nil,ai: .init(),now: 2)
        try s.accept(id: id,resultRecordId: row,resultStore: "cruise",now: 3)
        try s.updateUIFlow(XCTUnwrap(flow.evidence(now: 3,previous: nil,result: true,accepted: true)))
        var r = try XCTUnwrap(s.current); r.rawStatus = .saved; return r
    }
    private func archive() -> (URL,FieldStudyArchive) {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("forestix-undo-"+UUID().uuidString)
        return (dir,FieldStudyArchive(root: dir))
    }
    func testDbhHeightUndoRestoresOnlyExactSupersededRecordsAndKeepsEvidence() throws {
        let (dir,a) = archive(); defer { try? FileManager.default.removeItem(at: dir) }
        let oldD = try record(),oldH = try record(kind: .height),newD = try record(),newH = try record(kind: .height),otherRound = try record(round: 2)
        for r in [oldD,oldH,newD,newH,otherRound] { try a.createMeasurement(r) }
        XCTAssertTrue(try a.supersedeAcceptedMeasurement(id: oldD.id,by: newD.id)); XCTAssertTrue(try a.supersedeAcceptedMeasurement(id: oldH.id,by: newH.id))
        let op = UUID().uuidString
        let changed = try a.undoApplicationMeasurements(operationId: op,resultRecordId: row.lowercased(),measurementIds: [newD.id,newH.id])
        XCTAssertEqual(changed.count,4)
        for r in [newD,newH] {
            let undone = try a.measurement(id: r.id)
            XCTAssertEqual(undone.selection,.discarded); XCTAssertTrue(undone.wasApplicationUndone)
            XCTAssertEqual(undone.liveValue,r.liveValue); XCTAssertEqual(undone.rawStatus,.saved)
            XCTAssertEqual(undone.rawBundleId,r.rawBundleId); XCTAssertEqual(undone.ai,r.ai)
            XCTAssertEqual(undone.timing,r.timing); XCTAssertEqual(undone.uiFlowTiming,r.uiFlowTiming)
        }
        for r in [oldD,oldH,otherRound] { XCTAssertEqual(try a.measurement(id: r.id).selection,.accepted) }
        let revisions = changed.map(\.revision).sorted()
        XCTAssertEqual(try a.undoApplicationMeasurements(operationId: op,resultRecordId: row,measurementIds: [newH.id,newD.id]).map(\.revision).sorted(),revisions)
        XCTAssertThrowsError(try a.undoApplicationMeasurements(operationId: op,resultRecordId: UUID().uuidString,measurementIds: [newD.id,newH.id]))
    }
    func testGeneralMutationCannotForgeUndoAndWrongApplicationBindingDoesNothing() throws {
        let (dir,a) = archive(); defer { try? FileManager.default.removeItem(at: dir) }
        let current = try record(); try a.createMeasurement(current)
        XCTAssertThrowsError(try a.updateMeasurement(id: current.id) { r in r.selection = .discarded; r.events.append(.init(action: "application_undo",detail: UUID().uuidString)) })
        XCTAssertThrowsError(try a.undoApplicationMeasurements(operationId: UUID().uuidString,resultRecordId: UUID().uuidString,measurementIds: [current.id]))
        XCTAssertThrowsError(try a.undoApplicationMeasurements(operationId: UUID().uuidString,resultRecordId: row,measurementIds: [UUID().uuidString]))
        XCTAssertEqual(try a.measurement(id: current.id),current)
    }
    func testInterruptedMultiRecordJournalCompletesOnFreshArchiveRead() throws {
        let (dir,a) = archive(); defer { try? FileManager.default.removeItem(at: dir) }
        let old = try record(),new = try record(); try a.createMeasurement(old); try a.createMeasurement(new)
        _ = try a.supersedeAcceptedMeasurement(id: old.id,by: new.id)
        let batch = try FieldApplicationUndoBatch.prepare(id: UUID().uuidString,resultRecordId: row,measurementIds: [new.id],records: a.scanMeasurements().values)
        let folder = dir.appendingPathComponent("application-undos"); try FileManager.default.createDirectory(at: folder,withIntermediateDirectories: true)
        try FieldStudyJSON.encoder().encode(batch).write(to: folder.appendingPathComponent(batch.id.uppercased()+".json"),options: .atomic)
        let first = try XCTUnwrap(batch.changes.first)
        try FieldStudyJSON.encoder().encode(first.after).write(to: dir.appendingPathComponent("measurements/"+first.after.id.uppercased()+".json"),options: .atomic)
        let restarted = FieldStudyArchive(root: dir)
        XCTAssertEqual(try restarted.measurement(id: new.id).selection,.discarded)
        XCTAssertEqual(try restarted.measurement(id: old.id).selection,.accepted)
        XCTAssertTrue(restarted.scanMeasurements().issues.isEmpty)
    }
    func testLateAcceptedScreenSnapshotAndRawWriterCannotResurrectUndoneResult() throws {
        let (dir,a) = archive(); defer { try? FileManager.default.removeItem(at: dir) }
        let current = try record(); try a.createMeasurement(current)
        _ = try a.undoApplicationMeasurements(operationId: UUID().uuidString,resultRecordId: row,measurementIds: [current.id])
        try a.mergeSessionSnapshot(current); try a.updateRawEvidence(id: current.id,status: .saved,reason: nil,replayValue: 31)
        let final = try a.measurement(id: current.id)
        XCTAssertTrue(final.wasApplicationUndone); XCTAssertEqual(final.replayValue,31); XCTAssertEqual(final.uiFlowTiming?.atAccepted,current.uiFlowTiming?.atAccepted)
        XCTAssertEqual(FieldTargetProgress.completed(studyId: "SYNTHETIC",plannedRepeat: 1,records: [final]),[])
        XCTAssertEqual(try FieldStudyJSON.decoder().decode(FieldMeasurementRecord.self,from: FieldStudyJSON.encoder().encode(final)),final)
    }
    func testPriorResultSupersededByAnotherReplacementIsNeverReactivated() throws {
        let old = try record(),new = try record(),other = try record()
        var previous = old; previous.selection = .superseded; previous.events.append(.init(action: "superseded",detail: other.id))
        let batch = try FieldApplicationUndoBatch.prepare(id: UUID().uuidString,resultRecordId: row,measurementIds: [new.id],records: [previous,new,other])
        XCTAssertEqual(batch.changes.count,1); XCTAssertEqual(batch.changes[0].after.id,new.id)
    }
    func testCorruptCompletedBatchCannotRewriteOriginalCaptureValue() throws {
        let current = try record(),batch = try FieldApplicationUndoBatch.prepare(id: UUID().uuidString,resultRecordId: row,measurementIds: [current.id],records: [current])
        var object = try XCTUnwrap(JSONSerialization.jsonObject(with: FieldStudyJSON.encoder().encode(batch)) as? [String:Any])
        var changes = try XCTUnwrap(object["changes"] as? [[String:Any]])
        var after = try XCTUnwrap(changes[0]["after"] as? [String:Any]); after["live_value"] = 999
        changes[0]["after"] = after; object["changes"] = changes; object["completed"] = true
        let decoded = try FieldStudyJSON.decoder().decode(FieldApplicationUndoBatch.self,from: JSONSerialization.data(withJSONObject: object))
        XCTAssertThrowsError(try decoded.validate())
    }
}
