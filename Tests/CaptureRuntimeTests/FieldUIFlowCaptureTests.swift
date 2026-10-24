import XCTest
import Models
import Sensors
#if canImport(UI)
@testable import UI
#else
@testable import CaptureRuntime
#endif

final class FieldUIFlowCaptureTests: XCTestCase {
    @MainActor func testActualRetargetKeepsOriginalFlowButIndependentTargetAndHeightTimes() async throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("forestix-flow-capture-"+UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: dir) }
        let store = FieldStudyStore(root: dir)
        for _ in 0..<1000 { if store.loaded { break }; try await Task.sleep(for: .milliseconds(2)) }
        var config = FieldStudyConfiguration();config.enabled = true;config.studyId = "SYNTHETIC";config.operatorId = "TEST"
        try await store.saveConfiguration(config,developerMode: true)
        let a = FieldStudyTarget(studyId: config.studyId,treeId: "0003",label: "003"),b = FieldStudyTarget(studyId: config.studyId,treeId: "0004",label: "004")
        try await store.saveTargets([a,b])
        let registry = FieldUIFlowRegistry(),flow = FieldUIFlowClock(kind: .dbh)
        var now = 0.0;registry.owner("app",active: true,now: now)
        let first = FieldCaptureCoordinator(clock: { now },uiFlowRegistry: registry)
        first.configure(store: store,kind: .dbh,settings: .ephemeral(),calibration: .identity,context: [:],targetOverride: a,uiFlow: flow)
        first.appeared();let old = UUID().uuidString
        now = 1;first.handle(.started(id: old,rawRequested: false,aiEnabled: false,typed: true))
        now = 2;first.handle(.result(id: old,value: 25,sigma: nil,valid: true,reason: nil,ai: .init()))
        now = 3;first.close(reason: "target_changed")
        now = 4;registry.owner("app",active: false,now: now)
        now = 6
        let next = FieldCaptureCoordinator(clock: { now },uiFlowRegistry: registry)
        next.configure(store: store,kind: .dbh,settings: .ephemeral(),calibration: .identity,context: [:],targetOverride: b,uiFlow: flow)
        next.appeared();next.foreground(false)
        now = 8;registry.owner("app",active: true,now: now);next.foreground(true)
        let id = UUID().uuidString
        now = 9;next.handle(.started(id: id,rawRequested: false,aiEnabled: false,typed: true))
        now = 11;next.handle(.result(id: id,value: 30,sigma: nil,valid: true,reason: nil,ai: .init()))
        now = 14;next.resultSaved(id: id,resultRecordId: id,resultStore: "quick_history")
        now = 20;next.foreground(false);next.close()
        await store.awaitPendingWrites()
        let saved = try store.archive.measurement(id: id),prior = try store.archive.measurement(id: old)
        XCTAssertEqual(saved.targetSnapshot,b);XCTAssertEqual(prior.targetSnapshot,a);XCTAssertEqual(prior.selection,.discarded)
        XCTAssertEqual(saved.uiFlowTiming?.id,prior.uiFlowTiming?.id);XCTAssertEqual(saved.uiFlowTiming?.id,flow.id)
        XCTAssertEqual(prior.uiFlowTiming?.atResult?.elapsedSeconds,2)
        XCTAssertEqual(saved.uiFlowTiming?.atResult?.elapsedSeconds,11);XCTAssertEqual(saved.uiFlowTiming?.atAccepted?.elapsedSeconds,14)
        XCTAssertEqual(saved.uiFlowTiming?.atAccepted?.foregroundSeconds,10);XCTAssertEqual(saved.uiFlowTiming?.atAccepted?.inactiveSeconds,4)
        XCTAssertEqual(saved.timing?.firstValidResultElapsedSeconds,5);XCTAssertEqual(saved.timing?.attemptFirstResultSeconds,2)
        XCTAssertEqual(saved.timing?.acceptedElapsedSeconds,8)
        now = 23
        let height = FieldCaptureCoordinator(clock: { now },uiFlowRegistry: registry),h = UUID().uuidString
        height.configure(store: store,kind: .height,settings: .ephemeral(),calibration: .identity,context: [:],targetOverride: b,uiFlow: flow)
        height.appeared();now = 24;height.handle(.started(id: h,rawRequested: false,aiEnabled: false,typed: true))
        now = 25;height.handle(.result(id: h,value: 20,sigma: nil,valid: true,reason: nil,ai: .init()))
        now = 26;height.resultSaved(id: h,resultRecordId: h,resultStore: "quick_history")
        await store.awaitPendingWrites()
        let heightRecord = try store.archive.measurement(id: h)
        XCTAssertNotEqual(heightRecord.uiFlowTiming?.id,flow.id);XCTAssertEqual(heightRecord.uiFlowTiming?.kind,.height)
        XCTAssertEqual(heightRecord.uiFlowTiming?.atAccepted?.elapsedSeconds,3)
        XCTAssertNil(first.error);XCTAssertNil(next.error);XCTAssertNil(height.error)
    }
    @MainActor func testTimingDisabledDoesNotWriteEitherTimingField() async throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("forestix-flow-off-"+UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: dir) }
        let store = FieldStudyStore(root: dir)
        for _ in 0..<1000 { if store.loaded { break };try await Task.sleep(for: .milliseconds(2)) }
        var c = FieldStudyConfiguration();c.enabled = true;c.studyId = "SYNTHETIC";c.operatorId = "TEST";c.recordTiming = false
        try await store.saveConfiguration(c,developerMode: true)
        let registry = FieldUIFlowRegistry();registry.owner("app",active: true,now: 0)
        var now = 0.0;let capture = FieldCaptureCoordinator(clock: { now },uiFlowRegistry: registry),id = UUID().uuidString
        capture.configure(store: store,kind: .dbh,settings: .ephemeral(),calibration: .identity,context: [:]);capture.appeared()
        now = 1;capture.handle(.started(id: id,rawRequested: false,aiEnabled: false,typed: true))
        now = 2;capture.handle(.result(id: id,value: 25,sigma: nil,valid: true,reason: nil,ai: .init()))
        now = 3;capture.resultSaved(id: id,resultRecordId: id,resultStore: "quick_history");await store.awaitPendingWrites()
        let saved = try store.archive.measurement(id: id)
        XCTAssertNil(saved.timing);XCTAssertNil(saved.uiFlowTiming);XCTAssertNil(capture.error)
    }
}
