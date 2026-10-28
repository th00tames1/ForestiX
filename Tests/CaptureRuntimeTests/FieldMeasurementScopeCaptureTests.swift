import XCTest
import Models
import Sensors
#if canImport(UI)
@testable import UI
#else
@testable import CaptureRuntime
#endif

final class FieldMeasurementScopeCaptureTests: XCTestCase {
    @MainActor func testActualDBHRetryAndHeightHandoffKeepOperatorRoundAndOriginalReferenceRevision() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("forestix-chain-scope-"+UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }; let store = FieldStudyStore(root: root)
        for _ in 0..<1000 { if store.loaded { break }; try await Task.sleep(for: .milliseconds(2)) }
        var c = FieldStudyConfiguration(); c.enabled = true; c.studyId = "SYNTHETIC"; c.operatorId = "OP-1"; c.showReferencesDuringMeasurement = true
        try await store.saveConfiguration(c,developerMode: true)
        let target = FieldStudyTarget(studyId: c.studyId,treeId: "0007",label: "007")
        try await store.saveTargets([target]); try store.selectTarget(target.id)
        var refs: [FieldReferenceObservation] = []
        for method in c.referenceMethods {
            let ref = FieldReferenceObservation(targetId: target.id,method: method,inputValue: 25,operatorId: "OP-1")
            try await store.appendReference(ref,developerMode: true); refs.append(ref)
        }
        let dbh = FieldCaptureCoordinator(); dbh.configure(store: store,kind: .dbh,settings: .ephemeral(),calibration: .identity,context: ["application.tree_number":"7"])
        XCTAssertEqual(dbh.previewReferences(kind: .dbh,developerMode: true).count,1)
        let first = UUID().uuidString; dbh.handle(.started(id: first,rawRequested: false,aiEnabled: false,typed: true))
        dbh.handle(.result(id: first,value: 25,sigma: nil,valid: true,reason: nil,ai: .init()))
        let scope = try XCTUnwrap(dbh.measurementScope)
        c.operatorId = "OP-2"; c.plannedRepeat = 2; c.protocolVersion = "2"
        try await store.saveConfiguration(c,developerMode: true)
        let heightOld = try XCTUnwrap(refs.first { $0.method.kind == .height })
        let revised = FieldReferenceObservation(targetId: target.id,method: heightOld.method,inputValue: 30,operatorId: "OP-2",supersedesId: heightOld.id)
        try await store.appendReference(revised,developerMode: true)
        let retry = UUID().uuidString; dbh.handle(.started(id: retry,rawRequested: false,aiEnabled: false,typed: true))
        XCTAssertEqual(dbh.displayRecord?.measurementScope,scope); XCTAssertEqual(dbh.displayRecord?.operatorId,"OP-1")
        dbh.handle(.result(id: retry,value: 26,sigma: nil,valid: true,reason: nil,ai: .init()))
        dbh.resultSaved(id: retry,resultRecordId: retry,resultStore: "quick_history")
        let height = FieldCaptureCoordinator(); height.configure(store: store,kind: .height,settings: .ephemeral(),calibration: .identity,
            context: ["application.tree_number":"7"],targetOverride: target,useGlobalTarget: false,scopeOverride: scope)
        let id = UUID().uuidString; height.handle(.started(id: id,rawRequested: false,aiEnabled: false,typed: true))
        XCTAssertEqual(height.displayRecord?.operatorId,"OP-1"); XCTAssertEqual(height.displayRecord?.plannedRepeat,1)
        XCTAssertEqual(height.displayRecord?.referenceIds,[heightOld.id]); XCTAssertEqual(height.previewReferences(kind: .height,developerMode: true).first?.inputValue,25)
        XCTAssertTrue(height.previewReferences(kind: .height,developerMode: false).isEmpty)
        c.showReferencesDuringMeasurement = false
        try await store.saveConfiguration(c,developerMode: true)
        XCTAssertTrue(height.previewReferences(kind: .height,developerMode: true).isEmpty)
        XCTAssertEqual(height.measurementScope,scope)
        c.showReferencesDuringMeasurement = true
        try await store.saveConfiguration(c,developerMode: true)
        height.handle(.result(id: id,value: 20,sigma: nil,valid: true,reason: nil,ai: .init())); height.resultSaved(id: id,resultRecordId: id,resultStore: "quick_history")
        await store.awaitPendingWrites(); XCTAssertNil(dbh.error); XCTAssertNil(height.error)
        XCTAssertEqual(try store.archive.measurement(id: id).measurementScope,scope)
        dbh.configure(store: store,kind: .dbh,settings: .ephemeral(),calibration: .identity,context: ["application.tree_number":"8"])
        let next = UUID().uuidString; dbh.handle(.started(id: next,rawRequested: false,aiEnabled: false,typed: true))
        XCTAssertEqual(dbh.displayRecord?.operatorId,"OP-2"); XCTAssertEqual(dbh.displayRecord?.plannedRepeat,2)
        XCTAssertNotEqual(dbh.measurementScope?.id,scope.id); dbh.close(); await store.awaitPendingWrites()
    }
}
