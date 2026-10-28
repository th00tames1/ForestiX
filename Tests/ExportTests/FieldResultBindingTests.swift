import XCTest
import Models

final class FieldResultBindingTests: XCTestCase {
    private var a: FieldStudyTarget { .init(studyId: "SYNTHETIC",treeId: "0003",label: "003") }
    private var b: FieldStudyTarget { .init(studyId: "SYNTHETIC",treeId: "0004",label: "004") }
    private func record(target: FieldStudyTarget? = nil,unassigned: Bool = false,round: Int = 1,operatorId: String = "TEST",
                        version: String = "1",kind: FieldStudyKind = .dbh,store: String = "quick_history",row: String? = nil,
                        accept: Bool = true,runtime: [String:String] = [:],id: String = UUID().uuidString) throws -> FieldMeasurementRecord {
        let t = target ?? a
        var c = FieldStudyConfiguration(); c.enabled = true; c.studyId = t.studyId; c.operatorId = operatorId; c.plannedRepeat = round; c.protocolVersion = version
        var s = FieldMeasurementSession(now: 0)
        try s.begin(id: id,configuration: c,target: unassigned ? nil : t,kind: kind,runtime: runtime,references: [],rawRequested: false,aiEnabled: false,typed: false,now: 1)
        try s.finish(id: id,value: 25,sigma: 1,valid: true,reason: nil,ai: .init(),now: 2)
        if accept { try s.accept(id: id,resultRecordId: row ?? id,resultStore: store,now: 3) }
        return try XCTUnwrap(s.current)
    }
    private func resolve(_ ids: [String],_ records: [FieldMeasurementRecord],store: String = "quick_history",targets: [FieldStudyTarget]? = nil) throws -> FieldStudyTarget? {
        try FieldResultTargetBinding.resolve(resultIds: ids,resultStore: store,studyId: a.studyId,records: records,targets: targets ?? [a,b])
    }
    func testReverseQuickLookupUsesResultUUIDNotCurrentSelectionOrTreeNumber() throws {
        let r = try record()
        XCTAssertEqual(try resolve([r.id.lowercased()],[r]),a)
        XCTAssertNil(try resolve([UUID().uuidString],[r])); XCTAssertNil(try resolve([],[r]))
        var pending = r; pending.selection = .pending
        XCTAssertNil(try resolve([r.id],[pending]))
    }
    func testCruiseSharedRowAcrossKindsResolvesUniqueTargetButRejectsConflictingOwners() throws {
        let row = UUID().uuidString,d = try record(store: "cruise",row: row),h = try record(kind: .height,store: "cruise",row: row)
        XCTAssertEqual(try resolve([row],[d,h],store: "cruise"),a)
        let conflicting = try record(target: b,kind: .height,store: "cruise",row: row)
        XCTAssertThrowsError(try resolve([row],[d,conflicting],store: "cruise"))
    }
    func testForeignStudyMissingCatalogAndMisboundQuickUUIDCannotFallThroughToAGlobalTarget() throws {
        let other = FieldStudyTarget(studyId: "OTHER",treeId: "0003",label: "003"),foreign = try record(target: other)
        XCTAssertThrowsError(try resolve([foreign.id],[foreign]))
        let r = try record(); XCTAssertThrowsError(try resolve([r.id],[r],targets: [b]))
        XCTAssertThrowsError(try resolve([r.id,foreign.id],[r,foreign]))
        let row = UUID().uuidString,wrong = try record(row: row)
        XCTAssertThrowsError(try resolve([row],[wrong]))
    }
    func testCurrentCatalogRevisionIsUsedAfterExactOwnerResolution() throws {
        let r = try record(); var updated = a; updated.label = "Updated catalog label"
        XCTAssertEqual(try resolve([r.id],[r],targets: [updated]),updated)
    }
    func testAcceptedSameScopeReplacementIsRequiredAndOnlyItRetiresOldRecord() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("forestix-replacement-"+UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: dir) }
        let archive = FieldStudyArchive(root: dir),old = try record(),newId = UUID().uuidString
        try archive.mergeSessionSnapshot(old)
        XCTAssertThrowsError(try archive.supersedeAcceptedMeasurement(id: old.id,by: newId))
        var session = FieldMeasurementSession(now: 0)
        try session.begin(id: newId,configuration: old.configuration,target: a,kind: .dbh,runtime: [:],references: [],
            rawRequested: false,aiEnabled: false,typed: false,now: 1)
        try session.finish(id: newId,value: 25,sigma: 1,valid: true,reason: nil,ai: .init(),now: 2)
        try archive.mergeSessionSnapshot(XCTUnwrap(session.current))
        XCTAssertThrowsError(try archive.supersedeAcceptedMeasurement(id: old.id,by: newId))
        try session.accept(id: newId,resultRecordId: newId,resultStore: "quick_history",now: 3)
        let new = try XCTUnwrap(session.current)
        try archive.mergeSessionSnapshot(new)
        XCTAssertFalse(try archive.supersedeAcceptedMeasurement(id: old.id,by: old.id.lowercased()))
        XCTAssertTrue(try archive.supersedeAcceptedMeasurement(id: old.id,by: new.id))
        XCTAssertEqual(try archive.measurement(id: old.id).selection,.superseded)
        XCTAssertEqual(try archive.measurement(id: old.id).liveValue,25)
    }
    func testOtherRoundOperatorProtocolTargetKindStudyAndStoreRemainAccepted() throws {
        let variants = [try record(round: 2),try record(operatorId: "ANOTHER"),try record(version: "2"),
            try record(target: b),try record(kind: .height),try record(store: "cruise"),
            try record(target: .init(studyId: "OTHER",treeId: "0003",label: "003"))]
        for new in variants {
            let dir = FileManager.default.temporaryDirectory.appendingPathComponent("forestix-retain-repeat-"+UUID().uuidString)
            defer { try? FileManager.default.removeItem(at: dir) }
            let archive = FieldStudyArchive(root: dir),old = try record()
            try archive.mergeSessionSnapshot(old); try archive.mergeSessionSnapshot(new)
            XCTAssertFalse(try archive.supersedeAcceptedMeasurement(id: old.id,by: new.id))
            let retained = try archive.measurement(id: old.id)
            XCTAssertEqual(retained.selection,.accepted); XCTAssertEqual(retained.events.last?.action,"application_row_replaced_retained")
            XCTAssertEqual(retained.events.last?.detail,new.id)
        }
    }
    func testUnassignedReplacementNeedsExplicitMatchingApplicationScope() throws {
        let old = try record(unassigned: true),new = try record(unassigned: true)
        XCTAssertFalse(FieldMeasurementReplacementScope.matches(old,new))
        let context = ["application.mode":"quick","application.plot_id":UUID().uuidString,"application.tree_number":"7"]
        let bound = try record(unassigned: true,runtime: context),same = try record(unassigned: true,runtime: context)
        XCTAssertTrue(FieldMeasurementReplacementScope.matches(bound,same))
        var moved = context; moved["application.tree_number"] = "8"
        XCTAssertFalse(FieldMeasurementReplacementScope.matches(bound,try record(unassigned: true,runtime: moved)))
    }
}
