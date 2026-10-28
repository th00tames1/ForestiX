import XCTest
import Models

final class FieldMeasurementSessionTests: XCTestCase {
    private func configuration() -> FieldStudyConfiguration {
        var c = FieldStudyConfiguration(); c.enabled = true; c.studyId = "SYNTHETIC"; c.operatorId = "TEST"; return c
    }
    private func begin(_ session: inout FieldMeasurementSession, id: String, at time: Double, config: FieldStudyConfiguration? = nil) throws {
        let c = config ?? configuration()
        let target = FieldStudyTarget(studyId: c.studyId,treeId: "003",label: "003")
        try session.begin(id: id,configuration: c,target: target,kind: .dbh,runtime: ["field_session_version":"1"],
            references: [],rawRequested: true,aiEnabled: false,typed: false,now: time)
    }
    func testScreenAndAttemptTimingLatchOnlyAfterSuccessfulSave() throws {
        var session = FieldMeasurementSession(now: 0); let id = UUID().uuidString
        try begin(&session,id: id,at: 2)
        session.setForeground(false,now: 4); session.setForeground(true,now: 8)
        try session.finish(id: id,value: 25,sigma: 1,valid: true,reason: nil,ai: .init(),now: 10)
        session.saveFailed(id: id,reason: "synthetic_disk_failure",now: 12)
        XCTAssertNil(session.current?.timing?.acceptedElapsedSeconds)
        try session.accept(id: id,resultRecordId: id,resultStore: "quick_history",now: 14)
        let timing = try XCTUnwrap(session.current?.timing)
        XCTAssertEqual(timing.firstValidResultElapsedSeconds,10); XCTAssertEqual(timing.attemptFirstResultSeconds,8)
        XCTAssertEqual(timing.acceptedElapsedSeconds,14); XCTAssertEqual(timing.screenElapsedSeconds,14)
        XCTAssertEqual(timing.backgroundSeconds,4); XCTAssertEqual(timing.activeElapsedSeconds,10)
        XCTAssertEqual(timing.attemptElapsedSeconds,12); XCTAssertEqual(timing.attemptActiveSeconds,8)
        XCTAssertEqual(session.current?.resultRecordId,id)
        try session.abandon(id: id,reason: "screen_closed",now: 15)
        XCTAssertEqual(session.current?.selection,.accepted)
    }
    func testRetakeKeepsScreenClockAndDistinctIDsWhileFailingCapturesCanHaveNoDepth() throws {
        var session = FieldMeasurementSession(now: 0); let first = UUID().uuidString
        try begin(&session,id: first,at: 1)
        try session.refuse(id: first,reason: "depth_unavailable",now: 2)
        XCTAssertEqual(session.current?.outcome,.failed); XCTAssertEqual(session.current?.rawStatus,.failed)
        XCTAssertTrue(session.shouldPersistCurrent)
        let second = UUID().uuidString; try begin(&session,id: second,at: 3)
        try session.finish(id: second,value: 24,sigma: 1,valid: true,reason: nil,ai: .init(),now: 6)
        try session.abandon(id: second,reason: "retake",now: 7)
        XCTAssertEqual(session.current?.selection,.discarded)
        let third = UUID().uuidString; try begin(&session,id: third,at: 8)
        try session.finish(id: third,value: 25,sigma: 1,valid: true,reason: nil,ai: .init(),now: 10)
        XCTAssertEqual(session.attemptNumber,3); XCTAssertEqual(session.currentId,third)
        XCTAssertEqual(session.current?.timing?.firstValidResultElapsedSeconds,6)
        XCTAssertEqual(session.current?.timing?.attemptFirstResultSeconds,2)
        XCTAssertEqual(session.current?.timing?.screenElapsedSeconds,10)
    }
    func testPolicyDisablingFailureRecordingOrAllStudyRecordingIsHonored() throws {
        var c = configuration(); c.recordFailedAttempts = false
        var session = FieldMeasurementSession(now: 0); let id = UUID().uuidString
        try begin(&session,id: id,at: 1,config: c); XCTAssertFalse(session.shouldPersistCurrent)
        try session.refuse(id: id,reason: "depth_unavailable",now: 2); XCTAssertFalse(session.shouldPersistCurrent)
        let next = UUID().uuidString; try begin(&session,id: next,at: 3,config: c)
        try session.finish(id: next,value: 20,sigma: nil,valid: true,reason: nil,ai: .init(),now: 4)
        XCTAssertTrue(session.shouldPersistCurrent)
        c.enabled = false; let disabled = UUID().uuidString
        try session.abandon(id: next,reason: "retake",now: 5); try begin(&session,id: disabled,at: 6,config: c)
        XCTAssertEqual(session.currentId,disabled); XCTAssertNil(session.current); XCTAssertFalse(session.shouldPersistCurrent)
    }
    func testUnassignedIdentityAndStageRefusalsAreExplicitNotGuessedFromLocalNumber() throws {
        var session = FieldMeasurementSession(now: 0); let id = UUID().uuidString
        try session.begin(id: id,configuration: configuration(),target: nil,kind: .height,runtime: ["application.tree_number":"3"],
            references: [],rawRequested: false,aiEnabled: false,typed: false,now: 1)
        session.noteStageRefusal(id: id,stage: "top",reason: "camera_not_ready",now: 2)
        XCTAssertEqual(session.current?.treeId,"unassigned:"+id); XCTAssertNil(session.current?.targetId)
        XCTAssertEqual(session.current?.outcome,.started)
        XCTAssertEqual(session.current?.events.last?.action,"stage_refused")
        XCTAssertThrowsError(try session.accept(id: id,resultRecordId: id,resultStore: "quick_history",now: 3))
    }
    func testRawCompletionSurvivesLaterSessionFlushAndSupersessionKeepsOriginalBinding() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("forestix-session-test-"+UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: dir) }
        let archive = FieldStudyArchive(root: dir); var session = FieldMeasurementSession(now: 0); let id = UUID().uuidString
        try begin(&session,id: id,at: 1); try archive.mergeSessionSnapshot(XCTUnwrap(session.current))
        try session.finish(id: id,value: 25,sigma: 1,valid: true,reason: nil,ai: .init(),now: 3)
        try archive.mergeSessionSnapshot(XCTUnwrap(session.current))
        try archive.updateRawEvidence(id: id,status: .saved,reason: nil,replayValue: 0)
        XCTAssertEqual(try archive.measurement(id: id).rawStatus,.saved)
        XCTAssertNil(try archive.measurement(id: id).replayValue)
        try archive.updateRawEvidence(id: id,status: .saved,reason: nil,replayValue: 25.01)
        try session.accept(id: id,resultRecordId: id,resultStore: "quick_history",now: 4)
        try archive.mergeSessionSnapshot(XCTUnwrap(session.current))
        XCTAssertEqual(try archive.measurement(id: id).rawStatus,.saved)
        XCTAssertEqual(try archive.measurement(id: id).replayValue,25.01)
        var replacement = FieldMeasurementSession(now: 5); let replacementId = UUID().uuidString
        try begin(&replacement,id: replacementId,at: 6)
        try replacement.finish(id: replacementId,value: 26,sigma: 1,valid: true,reason: nil,ai: .init(),now: 7)
        try replacement.accept(id: replacementId,resultRecordId: replacementId,resultStore: "quick_history",now: 8)
        try archive.mergeSessionSnapshot(XCTUnwrap(replacement.current))
        XCTAssertTrue(try archive.supersedeAcceptedMeasurement(id: id,by: replacementId))
        try archive.mergeSessionSnapshot(XCTUnwrap(session.current))
        XCTAssertEqual(try archive.measurement(id: id).selection,.superseded)
        XCTAssertEqual(try archive.measurement(id: id).resultRecordId,id)
        XCTAssertThrowsError(try archive.updateMeasurement(id: id) { $0.resultRecordId = UUID().uuidString })
    }
}
