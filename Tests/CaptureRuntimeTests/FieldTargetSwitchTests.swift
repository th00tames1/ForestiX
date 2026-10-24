import XCTest
import Models
import Sensors
#if canImport(UI)
@testable import UI
#else
@testable import CaptureRuntime
#endif

final class FieldTargetSwitchTests: XCTestCase {
    @MainActor func testStaleConfirmationDismissalCannotEraseTheNewFailureAlert() async {
        let switcher = FieldTargetSwitchCoordinator()
        switcher.request(targetId: "a",label: "003",requiresConfirmation: true) { throw FieldStudyError.missing("synthetic") }
        let original = switcher.presentationGeneration
        switcher.confirm(); await switcher.waitForCurrentSwitch()
        XCTAssertNotNil(switcher.error)
        switcher.dismissPresentation(generation: original)
        XCTAssertNotNil(switcher.error)
        switcher.dismissPresentation(generation: switcher.presentationGeneration)
        XCTAssertNil(switcher.error)
    }
    @MainActor func testConfirmationDismissalDoesNotCancelTheConfirmedSwitch() async {
        let switcher = FieldTargetSwitchCoordinator(); var ran = 0
        switcher.request(targetId: "target-a",label: "0003",requiresConfirmation: true) { ran += 1 }
        XCTAssertEqual(switcher.pending?.label,"0003"); XCTAssertFalse(switcher.busy); XCTAssertEqual(ran,0)
        switcher.dismissConfirmation(); switcher.confirm(); XCTAssertEqual(ran,0)
        switcher.request(targetId: "target-a",label: "0003",requiresConfirmation: true) { ran += 1 }
        switcher.confirm(); XCTAssertTrue(switcher.busy)
        switcher.dismissConfirmation() // SwiftUI writes isPresented=false after the action.
        await switcher.waitForCurrentSwitch()
        XCTAssertEqual(ran,1); XCTAssertFalse(switcher.busy); XCTAssertNil(switcher.pending); XCTAssertNil(switcher.error)
    }
    @MainActor func testCancellationAndObsoleteCompletionCannotApplyOrClearAnotherSwitch() async throws {
        let switcher = FieldTargetSwitchCoordinator(); var events: [String] = []
        var first: CheckedContinuation<Void,Never>?,second: CheckedContinuation<Void,Never>?
        switcher.request(targetId: "a",label: "003",requiresConfirmation: false) {
            await withCheckedContinuation { first = $0 }; try Task.checkCancellation(); events.append("a")
        }
        for _ in 0..<100 where first == nil { await Task.yield() }
        let resumeFirst = try XCTUnwrap(first)
        switcher.request(targetId: "ignored",label: "duplicate",requiresConfirmation: false) { events.append("ignored") }
        switcher.cancel(); XCTAssertFalse(switcher.busy)
        switcher.request(targetId: "b",label: "004",requiresConfirmation: false) {
            await withCheckedContinuation { second = $0 }; try Task.checkCancellation(); events.append("b")
        }
        for _ in 0..<100 where second == nil { await Task.yield() }
        let resumeSecond = try XCTUnwrap(second)
        resumeFirst.resume(); for _ in 0..<10 { await Task.yield() }
        XCTAssertTrue(switcher.busy); XCTAssertTrue(events.isEmpty); XCTAssertNil(switcher.error)
        resumeSecond.resume(); await switcher.waitForCurrentSwitch()
        XCTAssertEqual(events,["b"]); XCTAssertFalse(switcher.busy)
    }
    @MainActor func testPreparationFailureLeavesErrorAndRetryCanSucceed() async {
        let switcher = FieldTargetSwitchCoordinator();var ran = 0
        switcher.request(targetId: "a",label: "003",requiresConfirmation: false) { throw FieldStudyError.missing("synthetic target") }
        await switcher.waitForCurrentSwitch(); XCTAssertNotNil(switcher.error); XCTAssertFalse(switcher.busy)
        switcher.request(targetId: "b",label: "004",requiresConfirmation: false) { ran += 1 }
        await switcher.waitForCurrentSwitch(); XCTAssertEqual(ran,1); XCTAssertNil(switcher.error)
    }
    @MainActor func testActualQuickRoutePreparationKeepsIdentityPlotAndMetadataTogetherWithoutMutations() async throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("forestix-quick-retarget-"+UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: dir) }
        let store = FieldStudyStore(root: dir),history = QuickMeasureHistory.ephemeral()
        for _ in 0..<1000 { if store.loaded { break }; try await Task.sleep(for: .milliseconds(2)) }
        var config = FieldStudyConfiguration(); config.enabled = true; config.studyId = "SYNTHETIC"; config.operatorId = "TEST"
        try await store.saveConfiguration(config,developerMode: true)
        let a = FieldStudyTarget(studyId: config.studyId,treeId: "0003",label: "003"),b = FieldStudyTarget(studyId: config.studyId,treeId: "0004",label: "004")
        try await store.saveTargets([a,b]); try store.selectTarget(a.id)
        let plot = try XCTUnwrap(history.activePlotID)
        history.append(.init(kind: .dbh,value: 25,sigma: 1,confidenceRaw: "green",method: "synthetic",
            treeNumber: 7,treeName: "Old tree",plotID: plot,speciesCode: "OTHER"))
        let fresh = try await FieldQuickTargetRoute.prepare(targetId: b.id,plotID: plot,study: store,history: history)
        XCTAssertEqual(fresh.treeNumber,8); XCTAssertEqual(fresh.treeName,"0004"); XCTAssertNil(fresh.speciesCode)
        XCTAssertEqual(fresh.target,b); XCTAssertEqual(fresh.plotID,plot)
        let id = UUID(),capture = FieldCaptureCoordinator()
        history.append(.init(id: id,kind: .dbh,value: 30,sigma: 1,confidenceRaw: "green",method: "synthetic",
            treeNumber: 8,treeName: "Bound tree",plotID: plot,speciesCode: "BOUND"))
        capture.configure(store: store,kind: .dbh,settings: .ephemeral(),calibration: .identity,
            context: ["application.plot_id":plot.uuidString,"application.tree_number":"8"],targetOverride: b)
        capture.handle(.started(id: id.uuidString,rawRequested: false,aiEnabled: false,typed: false))
        capture.handle(.result(id: id.uuidString,value: 30,sigma: 1,valid: true,reason: nil,ai: .init()))
        capture.resultSaved(id: id.uuidString,resultRecordId: id.uuidString,resultStore: "quick_history")
        // The real helper must wait for pending journal writes, not infer by local label.
        let existing = try await FieldQuickTargetRoute.prepare(targetId: b.id,plotID: plot,study: store,history: history)
        XCTAssertEqual(existing.treeNumber,8); XCTAssertEqual(existing.treeName,"Bound tree"); XCTAssertEqual(existing.speciesCode,"BOUND")
        XCTAssertEqual(existing.target,b); XCTAssertEqual(existing.plotID,plot)
        XCTAssertEqual(store.selectedTargetId,a.id); XCTAssertEqual(history.entries.count,2)
    }
    @MainActor func testInvalidOrCancelledQuickPreparationNeverFallsThroughToAnotherTarget() async throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("forestix-quick-refusal-"+UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: dir) }
        let store = FieldStudyStore(root: dir),history = QuickMeasureHistory.ephemeral()
        for _ in 0..<1000 { if store.loaded { break }; try await Task.sleep(for: .milliseconds(2)) }
        var config = FieldStudyConfiguration(); config.enabled = true; config.studyId = "SYNTHETIC"; config.operatorId = "TEST"
        try await store.saveConfiguration(config,developerMode: true)
        let target = FieldStudyTarget(studyId: config.studyId,treeId: "0003",label: "003")
        try await store.saveTargets([target]); try store.selectTarget(target.id)
        do { _ = try await FieldQuickTargetRoute.prepare(targetId: "unknown",plotID: nil,study: store,history: history); XCTFail("missing target accepted") }
        catch { XCTAssertTrue(error is FieldStudyError) }
        do { _ = try await FieldQuickTargetRoute.prepare(targetId: target.id,plotID: UUID(),study: store,history: history); XCTFail("unknown plot accepted") }
        catch { XCTAssertTrue(error is FieldStudyError) }
        let task = Task { @MainActor in try await FieldQuickTargetRoute.prepare(targetId: target.id,plotID: nil,study: store,history: history) }
        task.cancel()
        do { _ = try await task.value; XCTFail("cancelled route applied") } catch is CancellationError { } catch { XCTFail("wrong cancellation error: \(error)") }
        XCTAssertEqual(store.selectedTargetId,target.id); XCTAssertTrue(history.entries.isEmpty)
    }
}
