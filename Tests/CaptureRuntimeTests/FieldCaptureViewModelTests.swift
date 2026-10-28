import XCTest
import Models
import Common
import Sensors
#if canImport(UI)
@testable import UI
#else
@testable import CaptureRuntime
#endif

final class FieldCaptureViewModelTests: XCTestCase {
    @MainActor func testRecordingOffDoesNotRetireEarlierRecordedResultOrReportMissingReplacement() async throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("forestix-unrecorded-replacement-"+UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: dir) }
        let store = FieldStudyStore(root: dir)
        for _ in 0..<1000 { if store.loaded { break }; try await Task.sleep(for: .milliseconds(2)) }
        var c = FieldStudyConfiguration(); c.enabled = true; c.studyId = "SYNTHETIC"; c.operatorId = "TEST"
        try await store.saveConfiguration(c,developerMode: true)
        let capture = FieldCaptureCoordinator(),old = UUID(),next = UUID()
        capture.configure(store: store,kind: .dbh,settings: .ephemeral(),calibration: .identity,context: [:])
        capture.handle(.started(id: old.uuidString,rawRequested: false,aiEnabled: false,typed: false))
        capture.handle(.result(id: old.uuidString,value: 25,sigma: 1,valid: true,reason: nil,ai: .init()))
        XCTAssertEqual(capture.receipt(id: old.uuidString).measurementId,old.uuidString)
        capture.receipt(id: old.uuidString).confirm(recordId: old,store: "quick_history")
        await store.awaitPendingWrites()
        c.enabled = false; try await store.saveConfiguration(c,developerMode: true)
        capture.handle(.started(id: next.uuidString,rawRequested: false,aiEnabled: false,typed: false))
        capture.handle(.result(id: next.uuidString,value: 26,sigma: 1,valid: true,reason: nil,ai: .init()))
        XCTAssertNil(capture.receipt(id: next.uuidString).measurementId)
        capture.receipt(id: next.uuidString).confirm(recordId: next,store: "quick_history",superseded: [old])
        await store.awaitPendingWrites()
        XCTAssertNil(capture.error)
        XCTAssertEqual(try store.archive.measurement(id: old.uuidString).selection,.accepted)
        XCTAssertThrowsError(try store.archive.measurement(id: next.uuidString))
    }
    @MainActor func testKnownUnassignedScopeNeverInheritsAnotherGlobalTarget() async throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("forestix-unassigned-scope-"+UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: dir) }
        let store = FieldStudyStore(root: dir)
        for _ in 0..<1000 { if store.loaded { break }; try await Task.sleep(for: .milliseconds(2)) }
        var c = FieldStudyConfiguration(); c.enabled = true; c.studyId = "SYNTHETIC"; c.operatorId = "TEST"
        try await store.saveConfiguration(c,developerMode: true)
        let a = FieldStudyTarget(studyId: c.studyId,treeId: "0003",label: "003")
        let b = FieldStudyTarget(studyId: c.studyId,treeId: "0004",label: "004")
        try await store.saveTargets([a,b]); try store.selectTarget(a.id)
        let capture = FieldCaptureCoordinator(),id = UUID().uuidString
        capture.configure(store: store,kind: .height,settings: .ephemeral(),calibration: .identity,
            context: ["application.tree_number":"3"],useGlobalTarget: false)
        XCTAssertFalse(capture.usesGlobalTargetSelection); XCTAssertNil(capture.scopedTarget)
        capture.handle(.started(id: id,rawRequested: false,aiEnabled: false,typed: false))
        XCTAssertNil(capture.displayRecord?.targetId)
        try store.selectTarget(b.id)
        XCTAssertEqual(capture.currentId,id); XCTAssertNil(capture.displayRecord?.targetId)
        capture.handle(.result(id: id,value: 20,sigma: 1,valid: true,reason: nil,ai: .init()))
        capture.resultSaved(id: id,resultRecordId: id,resultStore: "quick_history")
        await store.awaitPendingWrites()
        let saved = try store.archive.measurement(id: id)
        XCTAssertEqual(saved.selection,.accepted); XCTAssertNil(saved.targetId); XCTAssertNil(saved.targetSnapshot)
        XCTAssertEqual(store.selectedTargetId,b.id)
    }
    @MainActor func testChainedHeightPinsSavedTargetAfterTallyAdvancesAndGlobalSelectionChanges() async throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("forestix-chain-target-"+UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: dir) }
        let store = FieldStudyStore(root: dir)
        for _ in 0..<1000 { if store.loaded { break }; try await Task.sleep(for: .milliseconds(2)) }
        var c = FieldStudyConfiguration(); c.enabled = true; c.studyId = "SYNTHETIC"; c.operatorId = "TEST"
        try await store.saveConfiguration(c,developerMode: true)
        let a = FieldStudyTarget(studyId: c.studyId,treeId: "0003",label: "003"),b = FieldStudyTarget(studyId: c.studyId,treeId: "0004",label: "004")
        try await store.saveTargets([a,b]); try store.selectTarget(a.id)
        let dbh = FieldCaptureCoordinator(),row = UUID().uuidString,id = UUID().uuidString
        dbh.configure(store: store,kind: .dbh,settings: .ephemeral(),calibration: .identity,context: ["application.tree_number":"3"])
        dbh.handle(.started(id: id,rawRequested: false,aiEnabled: false,typed: false))
        dbh.handle(.result(id: id,value: 25,sigma: 1,valid: true,reason: nil,ai: .init()))
        dbh.resultSaved(id: id,resultRecordId: row,resultStore: "cruise")
        let pinned = try XCTUnwrap(dbh.displayRecord?.targetSnapshot)
        dbh.configure(store: store,kind: .dbh,settings: .ephemeral(),calibration: .identity,context: ["application.tree_number":"4"])
        XCTAssertNil(store.selectedTargetId)
        try store.selectTarget(b.id)
        let height = FieldCaptureCoordinator(),heightId = UUID().uuidString
        height.configure(store: store,kind: .height,settings: .ephemeral(),calibration: .identity,
            context: ["application.tree_number":"3"],targetOverride: pinned)
        height.handle(.started(id: heightId,rawRequested: false,aiEnabled: false,typed: false))
        XCTAssertEqual(height.displayRecord?.targetSnapshot,a); XCTAssertEqual(height.scopedTarget,a)
        height.handle(.result(id: heightId,value: 20,sigma: 1,valid: true,reason: nil,ai: .init()))
        height.resultSaved(id: heightId,resultRecordId: row,resultStore: "cruise")
        await store.awaitPendingWrites()
        XCTAssertEqual(try store.archive.measurement(id: heightId).targetSnapshot,a)
        XCTAssertEqual(try store.archive.measurement(id: heightId).resultRecordId,row)
        XCTAssertTrue(store.completedTargetIds.contains(a.id)); XCTAssertEqual(store.selectedTargetId,b.id)
    }
    @MainActor func testInvalidScopedStudyCannotExposePreviousAcceptedRecordAsANewAttempt() async throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("forestix-invalid-scope-"+UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: dir) }
        let store = FieldStudyStore(root: dir)
        for _ in 0..<1000 { if store.loaded { break }; try await Task.sleep(for: .milliseconds(2)) }
        var c = FieldStudyConfiguration(); c.enabled = true; c.studyId = "SYNTHETIC"; c.operatorId = "TEST"
        try await store.saveConfiguration(c,developerMode: true)
        let a = FieldStudyTarget(studyId: c.studyId,treeId: "0003",label: "003")
        try await store.saveTargets([a])
        let capture = FieldCaptureCoordinator(),first = UUID().uuidString
        capture.configure(store: store,kind: .dbh,settings: .ephemeral(),calibration: .identity,context: [:],targetOverride: a)
        capture.handle(.started(id: first,rawRequested: false,aiEnabled: false,typed: false))
        capture.handle(.result(id: first,value: 25,sigma: 1,valid: true,reason: nil,ai: .init()))
        capture.resultSaved(id: first,resultRecordId: first,resultStore: "quick_history")
        await store.awaitPendingWrites()
        c.studyId = "OTHER"; try await store.saveConfiguration(c,developerMode: true)
        let next = UUID().uuidString
        capture.handle(.started(id: next,rawRequested: false,aiEnabled: false,typed: false))
        capture.handle(.result(id: next,value: 26,sigma: 1,valid: true,reason: nil,ai: .init()))
        XCTAssertEqual(capture.currentId,next); XCTAssertNil(capture.displayRecord); XCTAssertNotNil(capture.error)
        XCTAssertNil(FieldScanIdentity.snapshot(configuration: c,selected: a,record: nil,kind: .dbh)?.treeId)
        await store.awaitPendingWrites()
        XCTAssertEqual(try store.archive.measurement(id: first).selection,.accepted)
        XCTAssertThrowsError(try store.archive.measurement(id: next))
    }
    @MainActor func testNextApplicationTreeClearsOnlyInheritedSelectionNotAnExplicitNewTarget() async throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("forestix-next-target-"+UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: dir) }
        let store = FieldStudyStore(root: dir)
        for _ in 0..<1000 { if store.loaded { break }; try await Task.sleep(for: .milliseconds(2)) }
        var c = FieldStudyConfiguration(); c.enabled = true; c.studyId = "SYNTHETIC"; c.operatorId = "TEST"
        try await store.saveConfiguration(c,developerMode: true)
        let a = FieldStudyTarget(studyId: c.studyId,treeId: "0003",label: "003")
        let b = FieldStudyTarget(studyId: c.studyId,treeId: "0004",label: "004")
        try await store.saveTargets([a,b]); try store.selectTarget(a.id)
        let capture = FieldCaptureCoordinator()
        capture.configure(store: store,kind: .dbh,settings: .ephemeral(),calibration: .identity,context: ["application.tree_number":"3"])
        let first = UUID().uuidString
        capture.handle(.started(id: first,rawRequested: false,aiEnabled: false,typed: false))
        capture.handle(.result(id: first,value: 25,sigma: 1,valid: true,reason: nil,ai: .init()))
        capture.configure(store: store,kind: .dbh,settings: .ephemeral(),calibration: .identity,context: ["application.tree_number":"4"])
        XCTAssertNil(store.selectedTargetId); XCTAssertNil(capture.displayRecord)
        try store.selectTarget(b.id)
        capture.handle(.started(id: UUID().uuidString,rawRequested: false,aiEnabled: false,typed: false))
        try store.selectTarget(a.id)
        capture.configure(store: store,kind: .dbh,settings: .ephemeral(),calibration: .identity,context: ["application.tree_number":"5"])
        XCTAssertEqual(store.selectedTargetId,a.id); XCTAssertNil(capture.displayRecord)
        await store.awaitPendingWrites()
    }
    @MainActor func testDbhNoDepthRefusalsHaveNewAttemptIDsAndTypedResultIsExact() async throws {
        let vm = DBHScanViewModel.preview(state: .aligning)
        var events: [FieldCaptureEvent] = []; vm.onFieldCaptureEvent = { events.append($0) }
        vm.tap(at: .init(128,96)); let first = try XCTUnwrap(vm.measurementId)
        XCTAssertEqual(events.count,2)
        if case .started(let id,_,_,_) = events[0] { XCTAssertEqual(id,first) } else { XCTFail("missing start") }
        if case .refused(let id,_,_) = events[1] { XCTAssertEqual(id,first) } else { XCTFail("missing refusal") }
        vm.tap(at: .init(128,96)); XCTAssertNotEqual(vm.measurementId,first)
        vm.unitSystem = .metric; vm.manualDbhCm = "10.123456789"; vm.submitManualEntry()
        let typed = try XCTUnwrap(vm.measurementId)
        if case .result(let id,let value,let sigma,let valid,_,let ai) = try XCTUnwrap(events.last) {
            XCTAssertEqual(id,typed); XCTAssertEqual(value,vm.result.map { Double($0.diameterCm) }); XCTAssertNil(sigma)
            XCTAssertTrue(valid); XCTAssertFalse(ai.enabled); XCTAssertFalse(ai.inferenceRan); XCTAssertFalse(ai.boundaryApplied)
        } else { XCTFail("missing typed result") }
        vm.retake(); XCTAssertNil(vm.measurementId)
    }
    @MainActor func testHeightFailedAnchorAndDirectGeometryKeepCorrectAttemptIDs() async throws {
        let vm = HeightScanViewModel.preview(state: .idle)
        var events: [FieldCaptureEvent] = []; vm.onFieldCaptureEvent = { events.append($0) }
        vm.anchorHereNow(screenCenterHit: nil)
        XCTAssertNil(vm.measurementId); XCTAssertEqual(events.count,2)
        let failed: String
        if case .started(let id,_,_,_) = events[0] { failed = id } else { return XCTFail("missing failed attempt") }
        vm.anchorHere(anchorPointWorld: .zero,standingPointWorld: .init(1,0,0))
        let id = try XCTUnwrap(vm.measurementId); XCTAssertNotEqual(id,failed)
        vm.continueToAimTop()
        vm.captureBaseDirect(alphaBaseRad: -0.1,standingPointWorld: .init(10,0,0))
        vm.captureTopDirect(alphaTopRad: 0.6)
        XCTAssertEqual(vm.measurementId,id); XCTAssertNotNil(vm.result)
        if case .result(let resultID,let value,_,let valid,_,_) = try XCTUnwrap(events.last) {
            XCTAssertEqual(resultID,id); XCTAssertEqual(value,vm.result.map { Double($0.heightM) }); XCTAssertTrue(valid)
        } else { XCTFail("missing height result") }
        vm.retake(); XCTAssertNil(vm.measurementId)
        vm.unitSystem = .metric; vm.manualHeightM = "20.123456789"; vm.submitManualEntry()
        if case .result(_,let value,let sigma,let valid,_,_) = try XCTUnwrap(events.last) {
            XCTAssertEqual(value,vm.result.map { Double($0.heightM) }); XCTAssertNil(sigma); XCTAssertTrue(valid)
        } else { XCTFail("missing typed height") }
    }
    @MainActor func testActualViewModelEventsReachJournalAndAcceptedSaveIsSeparate() async throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("forestix-capture-runtime-"+UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: dir) }
        let store = FieldStudyStore(root: dir)
        for _ in 0..<1000 { if store.loaded { break }; try await Task.sleep(for: .milliseconds(2)) }
        XCTAssertTrue(store.loaded); XCTAssertNil(store.error)
        var c = FieldStudyConfiguration(); c.enabled = true; c.studyId = "SYNTHETIC"; c.operatorId = "TEST"
        try await store.saveConfiguration(c,developerMode: true)
        let target = FieldStudyTarget(studyId: c.studyId,treeId: "0003",label: "0003")
        try await store.saveTargets([target]); try store.selectTarget(target.id)
        let vm = DBHScanViewModel.preview(state: .manualEntry); let coordinator = FieldCaptureCoordinator()
        coordinator.configure(store: store,kind: .dbh,settings: .ephemeral(),calibration: .identity,context: ["application.tree_number":"3"])
        vm.onFieldCaptureEvent = { coordinator.handle($0) }; coordinator.appeared()
        vm.unitSystem = .metric; vm.manualDbhCm = "25.123456"; vm.submitManualEntry()
        let id = try XCTUnwrap(vm.measurementId)
        coordinator.resultSaveFailed(id: id,reason: "synthetic_failure")
        await store.awaitPendingWrites()
        let pending = try store.archive.measurement(id: id)
        XCTAssertEqual(pending.outcome,.result); XCTAssertEqual(pending.selection,.pending)
        XCTAssertNil(pending.timing?.acceptedElapsedSeconds)
        XCTAssertNotNil(coordinator.error) // Journal success cannot hide a failed application save.
        // Represents the capture host's receipt AFTER its durable save contract.
        coordinator.receipt(id: id).confirm(recordId: try XCTUnwrap(UUID(uuidString: id)),store: "quick_history")
        coordinator.close(); await store.awaitPendingWrites()
        let saved = try store.archive.measurement(id: id)
        XCTAssertEqual(saved.selection,.accepted); XCTAssertEqual(saved.resultRecordId,id)
        XCTAssertEqual(saved.treeId,"0003"); XCTAssertEqual(saved.targetSnapshot,target)
        XCTAssertEqual(saved.liveValue,vm.result.map { Double($0.diameterCm) })
        XCTAssertNotNil(saved.timing?.acceptedElapsedSeconds); XCTAssertEqual(saved.rawStatus,.notRequested)
        XCTAssertNil(coordinator.error)
    }
    @MainActor func testTargetChangeNeverRelabelsAnchoredAttemptAndNextTargetHasFreshSession() async throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("forestix-target-session-"+UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: dir) }
        let store = FieldStudyStore(root: dir)
        for _ in 0..<1000 { if store.loaded { break }; try await Task.sleep(for: .milliseconds(2)) }
        var c = FieldStudyConfiguration(); c.enabled = true; c.studyId = "SYNTHETIC"; c.operatorId = "TEST"
        try await store.saveConfiguration(c,developerMode: true)
        let a = FieldStudyTarget(studyId: c.studyId,treeId: "0003",label: "0003")
        let b = FieldStudyTarget(studyId: c.studyId,treeId: "0004",label: "0004")
        try await store.saveTargets([a,b]); try store.selectTarget(a.id)
        let capture = FieldCaptureCoordinator()
        capture.configure(store: store,kind: .height,settings: .ephemeral(),calibration: .identity,context: [:])
        let first = UUID().uuidString
        capture.handle(.started(id: first,rawRequested: false,aiEnabled: false,typed: false))
        try store.selectTarget(b.id)
        XCTAssertEqual(capture.displayRecord?.targetSnapshot,a)
        capture.handle(.result(id: first,value: 20,sigma: 0.5,valid: true,reason: nil,ai: .init()))
        await store.awaitPendingWrites()
        let original = try store.archive.measurement(id: first)
        XCTAssertEqual(original.targetSnapshot,a); XCTAssertEqual(capture.currentId,first)
        let second = UUID().uuidString
        capture.handle(.started(id: second,rawRequested: false,aiEnabled: false,typed: false))
        capture.handle(.result(id: second,value: 21,sigma: 0.5,valid: true,reason: nil,ai: .init()))
        await store.awaitPendingWrites()
        let next = try store.archive.measurement(id: second)
        XCTAssertEqual(capture.displayRecord?.targetSnapshot,b)
        XCTAssertEqual(next.targetSnapshot,b); XCTAssertEqual(next.attemptNumber,1); XCTAssertNotEqual(next.sessionId,original.sessionId)
        try store.selectTarget(b.id); XCTAssertEqual(capture.currentId,second)
        XCTAssertNil(capture.error)
    }
}
