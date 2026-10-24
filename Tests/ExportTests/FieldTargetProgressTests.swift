import XCTest
import Models

final class FieldTargetProgressTests: XCTestCase {
    private func configuration() -> FieldStudyConfiguration {
        var c = FieldStudyConfiguration(); c.enabled = true; c.studyId = "SYNTHETIC"; c.operatorId = "TEST"; return c
    }
    private func accepted(_ kind: FieldStudyKind,_ c: FieldStudyConfiguration? = nil) throws -> FieldMeasurementRecord {
        let c = c ?? configuration(),id = UUID().uuidString
        var session = FieldMeasurementSession(now: 0)
        try session.begin(id: id,configuration: c,target: .init(studyId: c.studyId,treeId: "0003",label: "003"),kind: kind,
            runtime: [:],references: [],rawRequested: false,aiEnabled: false,typed: false,now: 1)
        try session.finish(id: id,value: 25,sigma: 1,valid: true,reason: nil,ai: .init(),now: 2)
        try session.accept(id: id,resultRecordId: id,resultStore: "quick_history",now: 3)
        return try XCTUnwrap(session.current)
    }
    func testCompletionRequiresBothAcceptedKindsInCurrentStudyAndRound() throws {
        let c = configuration(),dbh = try accepted(.dbh),height = try accepted(.height)
        XCTAssertEqual(FieldTargetProgress.completed(studyId: c.studyId,plannedRepeat: c.plannedRepeat,records: [dbh,height]),[dbh.targetId!])
        XCTAssertTrue(FieldTargetProgress.completed(studyId: c.studyId,plannedRepeat: c.plannedRepeat+1,records: [dbh,height]).isEmpty)
        XCTAssertTrue(FieldTargetProgress.completed(studyId: "OTHER",plannedRepeat: c.plannedRepeat,records: [dbh,height]).isEmpty)
        XCTAssertTrue(FieldTargetProgress.completed(studyId: c.studyId,plannedRepeat: c.plannedRepeat,records: [dbh]).isEmpty)
    }
    func testFailuresPendingSupersededOrMissingResultBindingNeverHideTarget() throws {
        let c = configuration(),dbh = try accepted(.dbh),height = try accepted(.height)
        var variants: [FieldMeasurementRecord] = []
        var row = height; row.selection = .pending; variants.append(row)
        row = height; row.selection = .superseded; variants.append(row)
        row = height; row.outcome = .failed; variants.append(row)
        row = height; row.resultRecordId = nil; variants.append(row)
        row = height; row.liveValue = nil; variants.append(row)
        for h in variants { XCTAssertTrue(FieldTargetProgress.completed(studyId: c.studyId,plannedRepeat: c.plannedRepeat,records: [dbh,h]).isEmpty) }
    }
    func testHideToggleFiltersOverlayOnlyAndNextRoundRestoresTarget() throws {
        var c = configuration(); let dbh = try accepted(.dbh),height = try accepted(.height)
        let first = try XCTUnwrap(dbh.targetSnapshot),second = FieldStudyTarget(studyId: c.studyId,treeId: "0004",label: "004")
        let targets = [first,second],records = [dbh,height]
        XCTAssertEqual(FieldTargetProgress.visible(configuration: c,targets: targets,records: records),targets)
        c.hideCompletedTargets = true
        XCTAssertEqual(FieldTargetProgress.visible(configuration: c,targets: targets,records: records),[second])
        c.plannedRepeat += 1
        XCTAssertEqual(FieldTargetProgress.visible(configuration: c,targets: targets,records: records),targets)
    }
    func testIdentityRemainsOnCapturedTargetAndRoundEvenIfSelectionChangesOrGpsIsUnavailable() throws {
        var c = configuration(); let recorded = try accepted(.height)
        let another = FieldStudyTarget(studyId: c.studyId,treeId: "0004",label: "004")
        c.plannedRepeat += 1
        let identity = try XCTUnwrap(FieldScanIdentity.snapshot(configuration: c,selected: another,record: recorded,kind: .dbh))
        XCTAssertEqual(identity.treeId,"0003"); XCTAssertEqual(identity.plannedRepeat,recorded.plannedRepeat)
        XCTAssertEqual(identity.measurementId,recorded.id); XCTAssertEqual(identity.kind,.height)
        var unassigned = recorded; unassigned.targetId = nil; unassigned.treeId = "unassigned:"+recorded.id
        XCTAssertNil(FieldScanIdentity.snapshot(configuration: c,selected: another,record: unassigned,kind: .height)?.treeId)
        var discarded = recorded; discarded.selection = .discarded
        XCTAssertEqual(FieldScanIdentity.snapshot(configuration: c,selected: another,record: discarded,kind: .height)?.treeId,"0004")
        c.enabled = false
        XCTAssertNil(FieldScanIdentity.snapshot(configuration: c,selected: nil,record: nil,kind: .dbh))
        XCTAssertEqual(FieldScanIdentity.snapshot(configuration: c,selected: another,record: nil,kind: .dbh)?.recordingEnabled,false)
    }
}
