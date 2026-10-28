import XCTest
import Models
import Common
#if canImport(UI)
@testable import UI
#else
@testable import CaptureRuntime
#endif

final class FieldStudyRuntimeSafetyTests: XCTestCase {
    @MainActor func testTargetProgressCacheInvalidatesForRecordsRepeatsAndTargets() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("forestix-progress-test-"+UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = FieldStudyStore(root: root)
        for _ in 0..<1000 { if store.loaded { break }; try await Task.sleep(for: .milliseconds(2)) }
        XCTAssertTrue(store.loaded)
        var config = FieldStudyConfiguration(); config.studyId = "SYNTHETIC"; config.operatorId = "TEST"
        config.plannedRepetitions = 2; config.hideCompletedTargets = true
        try await store.saveConfiguration(config,developerMode: true)
        let target = FieldStudyTarget(studyId: config.studyId,treeId: "T1",label: "1")
        try await store.saveTargets([target]); XCTAssertEqual(store.overlayTargets,[target])
        for kind in [FieldStudyKind.dbh,.height] {
            var record = FieldMeasurementRecord(sessionId: UUID().uuidString,treeId: target.treeId,targetId: target.id,
                targetSnapshot: target,kind: kind,attemptNumber: 1,configuration: config,runtimeSettings: [:])
            record.outcome = .result; record.selection = .accepted; record.liveValue = 25
            record.resultRecordId = record.id; record.resultStore = "quick_history"
            store.reflectMeasurement(record)
        }
        XCTAssertEqual(store.overlayTargets,[])
        XCTAssertEqual(store.overlayTargets,[]) // Repeated UI reads do not rescan history.
        config.plannedRepeat = 2; try await store.saveConfiguration(config,developerMode: true)
        XCTAssertEqual(store.overlayTargets,[target])
        try await store.saveTargets([]); XCTAssertEqual(store.overlayTargets,[])
    }
}
