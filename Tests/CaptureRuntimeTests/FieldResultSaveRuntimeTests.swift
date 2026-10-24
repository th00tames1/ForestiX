import XCTest
import Foundation
import Models
#if canImport(UI)
@testable import UI
#else
@testable import CaptureRuntime
#endif

final class FieldResultSaveRuntimeTests: XCTestCase {
    @MainActor func testInventoryStartupRecoversOlderReceiptWithoutRetiringNewerResult() async throws {
        let dir = root(); let a = FieldStudyArchive(root: dir)
        var c = FieldStudyConfiguration(); c.enabled = true; c.studyId = "SYNTHETIC"; c.operatorId = "TEST"
        try a.saveConfiguration(c,developerMode: true)
        let row = UUID(),plot = UUID(),oldId = UUID().uuidString,newId = UUID().uuidString
        let tree = Tree(id: row,plotId: plot,treeNumber: 7,speciesCode: "DF",status: .live,dbhCm: 25.12345,dbhMethod: .manualVisual,
            dbhSigmaMm: nil,dbhRmseMm: nil,dbhCoverageDeg: nil,dbhNInliers: nil,dbhConfidence: .yellow,dbhIsIrregular: false,
            heightM: nil,heightMethod: nil,heightSource: nil,heightSigmaM: nil,heightDHM: nil,heightAlphaTopDeg: nil,heightAlphaBaseDeg: nil,heightConfidence: nil,
            bearingFromCenterDeg: nil,distanceFromCenterM: nil,boundaryCall: nil,crownClass: nil,damageCodes: [],isMultistem: false,parentTreeId: nil,
            notes: "SYNTHETIC",photoPath: nil,rawScanPath: nil,createdAt: Date(),updatedAt: Date(),deletedAt: nil,dbhMeasurementId: oldId)
        let t = FieldStudyTarget(studyId: c.studyId,treeId: "0007",label: "7"); try a.saveTargets([t])
        func snapshot(_ id: String,_ value: Double) -> FieldMeasurementRecord {
            var r = FieldMeasurementRecord(id: id,sessionId: UUID().uuidString,treeId: t.treeId,targetId: t.id,targetSnapshot: t,kind: .dbh,attemptNumber: 1,configuration: c,runtimeSettings: [:])
            r.outcome = .result; r.liveValue = value; return r
        }
        let old = snapshot(oldId,Double(tree.dbhCm)),newer = snapshot(newId,28)
        try a.prepareResultSave(.init(snapshot: old,expected: .init(id: row.uuidString,store: "cruise",kind: .dbh,value: old.liveValue!,measurementId: oldId)))
        let current = FieldExportApplicationResult(id: row.uuidString,store: "cruise",kind: .dbh,value: 28,measurementId: newId)
        try a.prepareResultSave(.init(snapshot: newer,expected: current,inventoryReplacement: .init(tree: tree)))
        let store = FieldStudyStore(root: dir); store.attachResultSaveReader { _ in [current] }
        await store.reload(recoverInterrupted: true)
        XCTAssertTrue(store.issues.isEmpty,"\(store.issues)")
        XCTAssertEqual(try a.measurement(id: oldId).selection,.superseded)
        XCTAssertEqual(try a.measurement(id: newId).selection,.accepted)
        XCTAssertTrue(a.scanResultSaves().values.allSatisfy(\.completed))
        XCTAssertNil(try a.measurement(id: oldId).timing?.acceptedElapsedSeconds)
    }
    private func root() -> URL {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("forestix-result-runtime-"+UUID().uuidString)
        addTeardownBlock { try? FileManager.default.removeItem(at: dir) }; return dir
    }
    @MainActor func testReceiptIsPreparedBeforeSaveAndOrderedConfirmationCompletesIt() async throws {
        let store = FieldStudyStore(root: root())
        for _ in 0..<1000 { if store.loaded { break }; try await Task.sleep(for: .milliseconds(2)) }
        var c = FieldStudyConfiguration(); c.enabled = true; c.studyId = "SYNTHETIC"; c.operatorId = "TEST"
        try await store.saveConfiguration(c,developerMode: true)
        var now = 0.0; let coordinator = FieldCaptureCoordinator(clock: { now }),id = UUID()
        coordinator.configure(store: store,kind: .dbh,settings: .ephemeral(),calibration: .identity,context: [:])
        now = 1; coordinator.handle(.started(id: id.uuidString,rawRequested: false,aiEnabled: false,typed: false))
        now = 3; coordinator.handle(.result(id: id.uuidString,value: 25,sigma: 1,valid: true,reason: nil,ai: .init()))
        let receipt = coordinator.receipt(id: id.uuidString)
        try receipt.prepare(recordId: id,store: "quick_history",value: 25)
        XCTAssertEqual(store.archive.scanResultSaves().values.count,1)
        XCTAssertFalse(store.archive.scanResultSaves().values[0].completed)
        now = 6; receipt.confirm(recordId: id,store: "quick_history")
        await store.awaitPendingWrites(); XCTAssertNil(coordinator.error)
        XCTAssertTrue(store.archive.scanResultSaves().values[0].completed)
        XCTAssertEqual(try store.archive.measurement(id: id.uuidString).timing?.acceptedElapsedSeconds,6)
    }
    @MainActor func testStartupRecoveryUsesDurableQuickJournalNotStaleDefaultsCache() async throws {
        let dir = root(); try FileManager.default.createDirectory(at: dir,withIntermediateDirectories: true)
        let suite = "forestix-stale-proof-"+UUID().uuidString,defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let journal = dir.appendingPathComponent("quick.jsonl"),history = QuickMeasureHistory(defaults: defaults,sidecarURL: journal)
        let id = UUID(),entry = QuickMeasureEntry(id: id,kind: .height,value: 20,sigma: nil,confidenceRaw: "green",method: "manualEntry")
        try history.saveCapturedReading(entry)
        defaults.set(try JSONEncoder().encode([QuickMeasureEntry]()),forKey: QuickMeasureHistory.Keys.entries)
        let reopenedHistory = QuickMeasureHistory(defaults: defaults,sidecarURL: journal)
        XCTAssertEqual(reopenedHistory.entries.map(\.id),[id]); XCTAssertEqual(reopenedHistory.entries.first?.value,20)
        var c = FieldStudyConfiguration(); c.enabled = true; c.studyId = "SYNTHETIC"; c.operatorId = "TEST"
        var s = FieldMeasurementSession(now: 0)
        try s.begin(id: id.uuidString,configuration: c,target: nil,kind: .height,runtime: [:],references: [],rawRequested: false,aiEnabled: false,typed: true,now: 1)
        try s.finish(id: id.uuidString,value: 20,sigma: nil,valid: true,reason: nil,ai: .init(),now: 3)
        let fieldRoot = dir.appendingPathComponent("field"),a = FieldStudyArchive(root: fieldRoot)
        try a.saveConfiguration(c,developerMode: true)
        try a.prepareResultSave(.init(snapshot: XCTUnwrap(s.current),expected: .init(id: id.uuidString,store: "quick_history",kind: .height,value: 20,measurementId: id.uuidString)))
        let store = FieldStudyStore(root: fieldRoot)
        store.attachResultSaveReader { intent in try FieldQuickResultProofReader.read(url: journal,recordId: intent.expected.id) }
        for _ in 0..<1000 { if store.loaded { break }; try await Task.sleep(for: .milliseconds(2)) }
        XCTAssertEqual(try store.archive.measurement(id: id.uuidString).selection,.accepted)
        XCTAssertNil(try store.archive.measurement(id: id.uuidString).timing?.acceptedElapsedSeconds)
        XCTAssertTrue(store.issues.isEmpty)
    }
    func testQuickProofReaderRefusesCorruptionDuplicatesAndNeverRewritesOriginal() throws {
        let dir = root(); try FileManager.default.createDirectory(at: dir,withIntermediateDirectories: true)
        let journal = dir.appendingPathComponent("quick.jsonl"),id = UUID()
        let entry = try QuickMeasureEntry(id: id,kind: .dbh,value: 25,sigma: nil,confidenceRaw: "green",method: "manualVisual").identifyingMeasurement()
        var data = Data("#v 8\n".utf8); let row = try JSONEncoder().encode(entry)
        data.append(row); data.append(10); data.append(row); data.append(10); try data.write(to: journal)
        XCTAssertEqual(try FieldQuickResultProofReader.read(url: journal,recordId: id.uuidString).count,2)
        data.append(Data("{partial".utf8)); try data.write(to: journal)
        XCTAssertThrowsError(try FieldQuickResultProofReader.read(url: journal,recordId: id.uuidString))
        XCTAssertEqual(try Data(contentsOf: journal),data)
    }
}
