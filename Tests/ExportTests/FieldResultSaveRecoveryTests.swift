import XCTest
import Foundation
import Models
import Export

final class FieldResultSaveRecoveryTests: XCTestCase {
    private func root() throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("forestix-result-save-"+UUID().uuidString)
        try FileManager.default.createDirectory(at: url,withIntermediateDirectories: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: url) }; return url
    }
    private func session(_ kind: FieldStudyKind = .dbh) throws -> FieldMeasurementSession {
        var c = FieldStudyConfiguration(); c.enabled = true; c.studyId = "SYNTHETIC"; c.operatorId = "TEST"
        var s = FieldMeasurementSession(now: 0)
        try s.begin(id: UUID().uuidString,configuration: c,target: nil,kind: kind,runtime: [:],references: [],rawRequested: true,aiEnabled: false,typed: false,now: 1)
        return s
    }
    private func finish(_ session: inout FieldMeasurementSession,_ store: String = "quick_history") throws -> FieldResultSaveIntent {
        let id = try XCTUnwrap(session.currentId)
        try session.finish(id: id,value: 25,sigma: 1,valid: true,reason: nil,ai: .init(),now: 3)
        return try .init(snapshot: XCTUnwrap(session.current),expected: .init(id: UUID().uuidString,store: store,kind: session.current!.kind,value: 25,measurementId: id))
    }
    func testRestartAfterNativeSaveRestoresEntireAttemptWithoutInventingAcceptanceTime() throws {
        let dir = try root(); var s = try session(.height); let intent = try finish(&s)
        try FieldStudyArchive(root: dir).prepareResultSave(intent)
        let reopened = FieldStudyArchive(root: dir)
        XCTAssertThrowsError(try reopened.recoverResultSave(id: intent.id,results: []))
        XCTAssertThrowsError(try reopened.measurement(id: intent.id))
        try reopened.recoverResultSave(id: intent.id,results: [intent.expected])
        let saved = try reopened.measurement(id: intent.id)
        XCTAssertEqual(saved.selection,.accepted); XCTAssertEqual(saved.targetSnapshot,intent.snapshot.targetSnapshot)
        XCTAssertEqual(saved.configuration,intent.snapshot.configuration); XCTAssertEqual(saved.attemptNumber,1)
        XCTAssertEqual(saved.liveValue,25); XCTAssertEqual(saved.timing?.firstValidResultElapsedSeconds,3)
        XCTAssertNil(saved.timing?.acceptedElapsedSeconds); XCTAssertNil(saved.uiFlowTiming?.atAccepted)
        XCTAssertTrue(saved.events.contains { $0.action == "result_save_recovered" && $0.detail == "acceptance_time_unrecorded" })
        XCTAssertTrue(try XCTUnwrap(reopened.scanResultSaves().values.first).completed)
        try reopened.recoverResultSave(id: intent.id,results: [])
        XCTAssertEqual(try reopened.measurement(id: intent.id),saved)
    }
    func testPreparedFullSnapshotSurvivesStartedOnlyJournalAndPreservesRawWriterEvidence() throws {
        let a = FieldStudyArchive(root: try root()); var s = try session()
        try a.createMeasurement(XCTUnwrap(s.current)); let intent = try finish(&s,"cruise")
        try a.prepareResultSave(intent)
        try a.updateRawEvidence(id: intent.id,status: .saved,reason: nil,replayValue: 24.9)
        XCTAssertEqual(try a.recoverInterruptedAttempts(),0)
        try a.recoverResultSave(id: intent.id,results: [intent.expected])
        let saved = try a.measurement(id: intent.id)
        XCTAssertEqual(saved.rawStatus,.saved); XCTAssertEqual(saved.replayValue,24.9); XCTAssertEqual(saved.outcome,.result)
        XCTAssertEqual(saved.selection,.accepted)
        XCTAssertThrowsError(try a.mergeSessionSnapshot(intent.snapshot)) // stale queue cannot downgrade recovery
        XCTAssertEqual(try a.measurement(id: intent.id),saved)
    }
    func testExactNativeProofRejectsSameValueWrongIdentityKindStoreDeletedOrDuplicate() throws {
        let a = FieldStudyArchive(root: try root()); var s = try session(); let intent = try finish(&s)
        try a.prepareResultSave(intent); try a.createMeasurement(intent.snapshot)
        let e = intent.expected
        let wrong: [[FieldExportApplicationResult]] = [[],[.init(id: e.id,store: e.store,kind: e.kind,value: e.value)],
            [.init(id: UUID().uuidString,store: e.store,kind: e.kind,value: e.value,measurementId: e.measurementId)],
            [.init(id: e.id,store: "cruise",kind: e.kind,value: e.value,measurementId: e.measurementId)],
            [.init(id: e.id,store: e.store,kind: .height,value: e.value,measurementId: e.measurementId)],
            [.init(id: e.id,store: e.store,kind: e.kind,value: 26,measurementId: e.measurementId)],
            [.init(id: e.id,store: e.store,kind: e.kind,value: e.value,measurementId: UUID().uuidString)],
            [.init(id: e.id,store: e.store,kind: e.kind,value: e.value,measurementId: e.measurementId,deleted: true)],[e,e]]
        for proof in wrong {
            XCTAssertThrowsError(try a.recoverResultSave(id: intent.id,results: proof))
            XCTAssertEqual(try a.measurement(id: intent.id),intent.snapshot)
            XCTAssertFalse(try XCTUnwrap(a.scanResultSaves().values.first).completed)
        }
    }
    func testNormalConfirmationPreservesRealTimingAndRetiredAttemptIsNeverResurrected() throws {
        let a = FieldStudyArchive(root: try root()); var s = try session(); let intent = try finish(&s,"cruise")
        try a.prepareResultSave(intent)
        try s.accept(id: intent.id,resultRecordId: intent.expected.id,resultStore: "cruise",now: 6)
        try a.mergeSessionSnapshot(XCTUnwrap(s.current))
        // The accepted journal is already durable, so recovery can finish a
        // torn completion receipt even if the row was subsequently removed.
        try a.recoverResultSave(id: intent.id,results: [])
        XCTAssertEqual(try a.measurement(id: intent.id).timing?.acceptedElapsedSeconds,6)
        var other = try session(); let retired = try finish(&other)
        try a.prepareResultSave(retired); try other.abandon(id: retired.id,reason: "user_discarded",now: 4)
        try a.mergeSessionSnapshot(XCTUnwrap(other.current))
        XCTAssertThrowsError(try a.recoverResultSave(id: retired.id,results: [retired.expected]))
        XCTAssertEqual(try a.measurement(id: retired.id).selection,.discarded)
    }
    func testPreparationIsImmutableAndCorruptIntentRemainsVisibleAndUnmodified() throws {
        let dir = try root(),a = FieldStudyArchive(root: dir); var s = try session(); let intent = try finish(&s)
        try a.prepareResultSave(intent); try a.prepareResultSave(intent)
        let changed = try FieldResultSaveIntent(snapshot: intent.snapshot,expected: .init(id: UUID().uuidString,store: "quick_history",kind: .dbh,value: 25,measurementId: intent.id))
        XCTAssertThrowsError(try a.prepareResultSave(changed))
        let bytes = try FieldStudyJSON.encoder().encode(intent)
        XCTAssertEqual(try FieldStudyJSON.decoder().decode(FieldResultSaveIntent.self,from: bytes),intent)
        var invalid = try XCTUnwrap(JSONSerialization.jsonObject(with: bytes) as? [String:Any]); invalid["completed"] = "false"
        XCTAssertThrowsError(try FieldStudyJSON.decoder().decode(FieldResultSaveIntent.self,from: JSONSerialization.data(withJSONObject: invalid)))
        let bad = dir.appendingPathComponent("result-saves/"+UUID().uuidString+".json"),data = Data("{partial".utf8)
        try data.write(to: bad); XCTAssertEqual(a.scanResultSaves().issues.count,1); XCTAssertEqual(try Data(contentsOf: bad),data)
    }
    func testExportRetainsPreparedReceiptAndReportsUnresolvedSave() throws {
        let dir = try root(),a = FieldStudyArchive(root: dir.appendingPathComponent("field")); var s = try session(); let intent = try finish(&s)
        try a.saveConfiguration(intent.snapshot.configuration,developerMode: true); try a.prepareResultSave(intent)
        let artifact = try FieldStudyExport.build(archive: a,rawRoot: dir.appendingPathComponent("raw"),results: [],outputDirectory: dir.appendingPathComponent("exports"))
        XCTAssertTrue(artifact.report.issues.contains { $0.code == "unresolved_application_save" && $0.measurementId == intent.id })
        XCTAssertFalse(artifact.report.integrityPassed); XCTAssertEqual(a.scanResultSaves().values,[intent])
    }
    func testCruiseRecoveryFinishesExactRowSupersessionEvenAfterAcceptedJournalWasWritten() throws {
        let a = FieldStudyArchive(root: try root()); var s = try session(); let first = try finish(&s,"cruise")
        var old = first.snapshot
        old.runtimeSettings = ["application.mode":"cruise","application.plot_id":UUID().uuidString,"application.tree_number":"7"]
        old.selection = .accepted; old.resultRecordId = first.expected.id; old.resultStore = "cruise"
        try a.createMeasurement(old)
        var next = old; next.id = UUID().uuidString; next.sessionId = UUID().uuidString; next.treeId = "unassigned:"+next.id
        next.rawBundleId = next.id; next.selection = .pending; next.resultRecordId = nil; next.resultStore = nil
        let intent = try FieldResultSaveIntent(snapshot: next,expected: .init(id: first.expected.id,store: "cruise",kind: .dbh,value: 25,measurementId: next.id))
        try a.prepareResultSave(intent)
        next.selection = .accepted; next.resultRecordId = first.expected.id; next.resultStore = "cruise"
        try a.mergeSessionSnapshot(next)
        try a.recoverResultSave(id: next.id,results: [])
        XCTAssertEqual(try a.measurement(id: old.id).selection,.superseded)
        XCTAssertEqual(try a.measurement(id: next.id).selection,.accepted)
        XCTAssertTrue(try XCTUnwrap(a.scanResultSaves().values.first).completed)
    }
}
