import XCTest
import Models

final class FieldMeasurementScopeTests: XCTestCase {
    private func values() -> (FieldStudyConfiguration,FieldStudyTarget,[FieldReferenceObservation]) {
        var c = FieldStudyConfiguration(); c.enabled = true; c.studyId = "SYNTHETIC"; c.operatorId = "OP-1"; c.showReferencesDuringMeasurement = true
        let t = FieldStudyTarget(studyId: c.studyId,treeId: "0007",label: "007")
        let refs = c.referenceMethods.map { FieldReferenceObservation(targetId: t.id,method: $0,inputValue: $0.kind == .dbh ? 25 : 20,operatorId: c.operatorId) }
        return (c,t,refs)
    }
    func testBothKindsKeepOriginalGTUnitsOperatorAndRoundAcrossRoundTrip() throws {
        let (c,t,refs) = values(),scope = try FieldMeasurementScope(configuration: c,target: t,references: refs)
        let bytes = try FieldStudyJSON.encoder().encode(scope)
        let decoded = try FieldStudyJSON.decoder().decode(FieldMeasurementScope.self,from: bytes); try decoded.validate()
        XCTAssertEqual(decoded,scope)
        for kind in [FieldStudyKind.dbh,.height] {
            var session = FieldMeasurementSession(now: 0); let id = UUID().uuidString
            try session.begin(id: id,configuration: scope.configuration,target: scope.target,kind: kind,runtime: ["platform":kind == .dbh ? "ios" : "android"],
                references: scope.references(for: kind).map(\.id),rawRequested: true,aiEnabled: false,typed: false,now: 1,scope: scope)
            let record = try XCTUnwrap(session.current); try record.validate()
            let restored = try FieldStudyJSON.decoder().decode(FieldMeasurementRecord.self,from: FieldStudyJSON.encoder().encode(record))
            XCTAssertEqual(restored.measurementScope,scope); XCTAssertEqual(restored.operatorId,"OP-1"); XCTAssertEqual(restored.plannedRepeat,1)
            XCTAssertEqual(restored.referenceIds,scope.references(for: kind).map(\.id))
        }
    }
    func testWrongTargetRoundOrChangedReferenceSnapshotCannotBeApplied() throws {
        let (c,t,refs) = values(); var wrong = refs; wrong[0].targetId = "another"
        XCTAssertThrowsError(try FieldMeasurementScope(configuration: c,target: t,references: wrong))
        wrong = refs; wrong[0].plannedRepeat = 2
        XCTAssertThrowsError(try FieldMeasurementScope(configuration: c,target: t,references: wrong))
        let scope = try FieldMeasurementScope(configuration: c,target: t,references: refs)
        var s = FieldMeasurementSession(now: 0)
        XCTAssertThrowsError(try s.begin(id: UUID().uuidString,configuration: c,target: t,kind: .dbh,runtime: [:],references: [],
            rawRequested: false,aiEnabled: false,typed: true,now: 1,scope: scope))
    }
    func testReferencePreviewRequiresBothDeveloperAndVisibilityAndFiltersRevisionsKindsRounds() throws {
        var (c,t,refs) = values(); let first = refs[0]
        let revision = FieldReferenceObservation(targetId: t.id,method: first.method,inputValue: 30,operatorId: "OP-2",supersedesId: first.id)
        refs.append(revision)
        refs.append(.init(targetId: t.id,method: first.method,inputValue: 35,plannedRepeat: 2,operatorId: "OP-2"))
        let shown = FieldMeasurementReferences.preview(configuration: c,targetId: t.id,observations: refs,kind: first.method.kind,developerMode: true)
        XCTAssertEqual(shown.map(\.id),[revision.id]); XCTAssertTrue(FieldMeasurementReferences.preview(configuration: c,targetId: t.id,observations: refs,kind: .dbh,developerMode: false).isEmpty)
        c.showReferencesDuringMeasurement = false
        XCTAssertTrue(FieldMeasurementReferences.preview(configuration: c,targetId: t.id,observations: refs,kind: .dbh,developerMode: true).isEmpty)
        let scope = try FieldMeasurementScope(configuration: c,target: t,references: [])
        XCTAssertTrue(FieldMeasurementReferences.visible(scope: scope,kind: .dbh,developerMode: true).isEmpty)
    }
    func testArchiveRefusesScopeSwapEvenBeforeResultAndKeepsOriginalFile() throws {
        let (c,t,refs) = values(); let scope = try FieldMeasurementScope(configuration: c,target: t,references: refs)
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("forestix-scope-archive-"+UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }; let archive = FieldStudyArchive(root: root)
        var s = FieldMeasurementSession(now: 0); let id = UUID().uuidString
        try s.begin(id: id,configuration: c,target: t,kind: .dbh,runtime: [:],references: scope.references(for: .dbh).map(\.id),
            rawRequested: false,aiEnabled: false,typed: true,now: 1,scope: scope)
        let original = try XCTUnwrap(s.current); try archive.createMeasurement(original)
        XCTAssertThrowsError(try archive.updateMeasurement(id: id) { $0.measurementScope = nil })
        var swapped = original; swapped.measurementScope = try FieldMeasurementScope(configuration: c,target: t,references: refs)
        XCTAssertThrowsError(try archive.mergeSessionSnapshot(swapped))
        XCTAssertEqual(try archive.measurement(id: id),original)
    }
}
