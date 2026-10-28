import XCTest
import Models

final class FieldUIFlowTimingTests: XCTestCase {
    func testLazyOriginalOpeningSurvivesRetargetAndFreezesResultAndAcceptance() throws {
        let flow = FieldUIFlowClock(kind: .dbh)
        XCTAssertNil(try flow.evidence(now: 2,previous: nil,result: false,accepted: false))
        flow.open(now: 3,foreground: true,openedAt: "2026-01-01T00:00:00Z")
        flow.foreground(false,now: 4); flow.open(now: 8,foreground: false,openedAt: "SHOULD-NOT-REPLACE")
        let midway = try XCTUnwrap(flow.evidence(now: 8,previous: nil,result: false,accepted: false))
        XCTAssertEqual(midway.current.elapsedSeconds,5); XCTAssertEqual(midway.current.inactiveSeconds,4)
        XCTAssertEqual(midway.openedAt,"2026-01-01T00:00:00Z")
        flow.foreground(true,now: 9)
        let result = try XCTUnwrap(flow.evidence(now: 12,previous: midway,result: true,accepted: false))
        let saved = try XCTUnwrap(flow.evidence(now: 14,previous: result,result: true,accepted: true))
        XCTAssertEqual(result.atResult?.elapsedSeconds,9); XCTAssertEqual(saved.atAccepted?.elapsedSeconds,11)
        XCTAssertEqual(saved.atAccepted?.foregroundSeconds,6); XCTAssertEqual(saved.atAccepted?.inactiveSeconds,5)
        flow.foreground(false,now: 16)
        let later = try XCTUnwrap(flow.evidence(now: 20,previous: saved,result: true,accepted: true))
        XCTAssertEqual(later.atResult,result.atResult); XCTAssertEqual(later.atAccepted,saved.atAccepted)
        XCTAssertEqual(later.current.elapsedSeconds,17); XCTAssertEqual(later.id,flow.id)
    }
    func testApplicationOwnersAndRouteGapsNeverResetOrInventBackgroundTime() throws {
        let registry = FieldUIFlowRegistry(),flow = FieldUIFlowClock(kind: .height)
        registry.owner("old-activity",active: true,now: 0); registry.track(flow,now: 0)
        registry.owner("new-activity",active: true,now: 2); registry.owner("old-activity",active: false,now: 3)
        XCTAssertEqual(try flow.evidence(now: 4,previous: nil,result: false,accepted: false)?.current.inactiveSeconds,0)
        registry.owner("new-activity",active: false,now: 5)
        registry.track(flow,now: 7) // New capture route appears while app is still inactive.
        registry.owner("foreground-again",active: true,now: 9)
        let t = try XCTUnwrap(flow.evidence(now: 10,previous: nil,result: false,accepted: false))
        XCTAssertEqual(t.current.elapsedSeconds,10); XCTAssertEqual(t.current.foregroundSeconds,6); XCTAssertEqual(t.current.inactiveSeconds,4)
    }
    func testInvalidClockAndMalformedOrRelabeledEvidenceCannotClaimValidTiming() throws {
        let flow = FieldUIFlowClock(kind: .dbh); flow.open(now: 0,foreground: true)
        let first = try XCTUnwrap(flow.evidence(now: 8,previous: nil,result: true,accepted: false))
        let backwards = try XCTUnwrap(flow.evidence(now: 3,previous: first,result: true,accepted: false))
        XCTAssertFalse(backwards.current.clockValid); XCTAssertEqual(backwards.current.elapsedSeconds,8)
        var wrong = first; wrong.id = UUID().uuidString; XCTAssertFalse(wrong.continues(first))
        wrong = first; wrong.current.foregroundSeconds = 9; XCTAssertThrowsError(try wrong.validate())
        wrong = first; wrong.atResult?.elapsedSeconds = 9; XCTAssertThrowsError(try wrong.validate())
        wrong = first; wrong.schema = 99; XCTAssertThrowsError(try wrong.validate())
        wrong = backwards; wrong.current.clockValid = true; XCTAssertFalse(wrong.continues(backwards))
        var rounded = first; rounded.current.foregroundSeconds -= 0.0000000001; rounded.current.inactiveSeconds += 0.0000000001
        try rounded.validate(); XCTAssertTrue(first.continues(rounded))
    }
    func testJSONRoundTripAndArchiveFreezeProtectOriginalFlowEvents() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("forestix-flow-freeze-"+UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: dir) }
        var c = FieldStudyConfiguration(); c.enabled = true; c.studyId = "SYNTHETIC"; c.operatorId = "TEST"
        var session = FieldMeasurementSession(now: 0); let id = UUID().uuidString,flow = FieldUIFlowClock(kind: .dbh)
        flow.open(now: 0,foreground: true,openedAt: "2026-01-01T00:00:00Z")
        try session.begin(id: id,configuration: c,target: nil,kind: .dbh,runtime: [:],references: [],rawRequested: false,aiEnabled: false,typed: true,now: 1)
        try session.finish(id: id,value: 25,sigma: nil,valid: true,reason: nil,ai: .init(),now: 2)
        try session.accept(id: id,resultRecordId: id,resultStore: "quick_history",now: 3)
        try session.updateUIFlow(XCTUnwrap(flow.evidence(now: 3,previous: nil,result: true,accepted: true)))
        let record = try XCTUnwrap(session.current),archive = FieldStudyArchive(root: dir)
        let bytes = try FieldStudyJSON.encoder().encode(record)
        XCTAssertTrue(String(decoding: bytes,as: UTF8.self).contains("ui_flow_timing"))
        XCTAssertEqual(try FieldStudyJSON.decoder().decode(FieldMeasurementRecord.self,from: bytes),record)
        try archive.mergeSessionSnapshot(record)
        XCTAssertThrowsError(try archive.updateMeasurement(id: id) { $0.uiFlowTiming?.id = UUID().uuidString })
        XCTAssertThrowsError(try archive.updateMeasurement(id: id) { $0.uiFlowTiming?.atAccepted = nil })
        XCTAssertThrowsError(try archive.updateMeasurement(id: id) { $0.uiFlowTiming = nil })
        var legacySnapshot = record; legacySnapshot.uiFlowTiming = nil
        try archive.mergeSessionSnapshot(legacySnapshot)
        XCTAssertEqual(try archive.measurement(id: id).uiFlowTiming,record.uiFlowTiming)
    }
}
