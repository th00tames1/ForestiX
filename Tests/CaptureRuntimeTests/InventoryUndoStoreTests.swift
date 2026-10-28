import XCTest
import Models
import Persistence
#if canImport(UI)
@testable import UI
#else
@testable import CaptureRuntime
#endif

final class InventoryUndoStoreTests: XCTestCase {
    /// Recovery sees an already-restored row, or an independent edit. Any
    /// attempt to mutate this repository fails the test through a thrown error.
    private struct ReadOnlyTrees: TreeRepository {
        let row: Tree
        func create(_ t: Tree) throws -> Tree { throw FieldStudyError.invalid("Unexpected write") }
        func read(id: UUID,includeDeleted: Bool) throws -> Tree? { row.id == id && (includeDeleted || row.deletedAt == nil) ? row : nil }
        func update(_ t: Tree) throws -> Tree { throw FieldStudyError.invalid("Unexpected write") }
        func delete(id: UUID,at: Date) throws { throw FieldStudyError.invalid("Unexpected write") }
        func hardDelete(id: UUID) throws { throw FieldStudyError.invalid("Unexpected write") }
        func listByPlot(_ plotId: UUID,includeDeleted: Bool) throws -> [Tree] { row.plotId == plotId ? [row] : [] }
        func bySpeciesInProject(_ projectId: UUID,speciesCode: String,includeDeleted: Bool) throws -> [Tree] { [] }
        func recentSpeciesCodes(projectId: UUID,limit: Int) throws -> [String] { [] }
    }
    private func tree() -> Tree {
        .init(id: UUID(),plotId: UUID(),treeNumber: 7,speciesCode: "DF",status: .live,dbhCm: 30,dbhMethod: .manualCaliper,
              dbhSigmaMm: nil,dbhRmseMm: nil,dbhCoverageDeg: nil,dbhNInliers: nil,dbhConfidence: .green,dbhIsIrregular: false,
              heightM: 21,heightMethod: .manualEntry,heightSource: "measured",heightSigmaM: nil,heightDHM: 12,
              heightAlphaTopDeg: nil,heightAlphaBaseDeg: nil,heightConfidence: .green,bearingFromCenterDeg: nil,distanceFromCenterM: nil,
              boundaryCall: nil,crownClass: nil,damageCodes: [],isMultistem: false,parentTreeId: nil,notes: "old",photoPath: "old.jpg",rawScanPath: nil,
              createdAt: Date(timeIntervalSinceReferenceDate: 100),updatedAt: Date(timeIntervalSinceReferenceDate: 101),deletedAt: nil)
    }
    private func record(row: UUID,kind: FieldStudyKind,configuration: FieldStudyConfiguration,target: FieldStudyTarget) throws -> FieldMeasurementRecord {
        var s = FieldMeasurementSession(now: 0); let id = UUID().uuidString
        try s.begin(id: id,configuration: configuration,target: target,kind: kind,runtime: [:],references: [],rawRequested: false,aiEnabled: false,typed: true,now: 1)
        try s.finish(id: id,value: kind == .dbh ? 44 : 21,sigma: nil,valid: true,reason: nil,ai: .init(),now: 2)
        try s.accept(id: id,resultRecordId: row.uuidString,resultStore: "cruise",now: 3); return try XCTUnwrap(s.current)
    }
    @MainActor private func fixture(conflict: Bool,withRepository: Bool = true,corruptIntent: Bool = false) async throws -> (URL,FieldStudyStore,[FieldMeasurementRecord]) {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("forestix-undo-store-"+UUID().uuidString),a = FieldStudyArchive(root: root)
        var c = FieldStudyConfiguration(); c.enabled = true; c.studyId = "SYNTHETIC"; c.operatorId = "TEST"; c.hideCompletedTargets = true
        let target = FieldStudyTarget(studyId: c.studyId,treeId: "0003",label: "003")
        try a.saveConfiguration(c,developerMode: true); try a.saveTargets([target])
        let old = tree(); var saved = old; saved.dbhCm = 44; saved.updatedAt = Date(timeIntervalSinceReferenceDate: 102)
        let d = try record(row: old.id,kind: .dbh,configuration: c,target: target),h = try record(row: old.id,kind: .height,configuration: c,target: target)
        try a.createMeasurement(d); try a.createMeasurement(h)
        _ = try a.prepareInventoryUndo(id: UUID().uuidString,saved: saved,original: old,measurementIds: [d.id,h.id])
        if corruptIntent {
            try Data("{invalid".utf8).write(to: root.appendingPathComponent("inventory-undos/"+UUID().uuidString+".json"))
        }
        var actual = old; if conflict { actual = saved; actual.notes = "independent edit" }
        let store = FieldStudyStore(root: root)
        if withRepository { store.attachInventoryUndoRepository(ReadOnlyTrees(row: actual)) }
        for _ in 0..<1500 { if store.loaded { break }; try await Task.sleep(for: .milliseconds(2)) }
        XCTAssertTrue(store.loaded); return (root,store,[d,h])
    }
    @MainActor func testStartupRecoversBeforePublishingAdoptedMeasurements() async throws {
        let (root,store,records) = try await fixture(conflict: false); defer { try? FileManager.default.removeItem(at: root) }
        XCTAssertNil(store.error); XCTAssertTrue(store.issues.isEmpty); XCTAssertTrue(store.unresolvedUndoMeasurementIds.isEmpty)
        XCTAssertEqual(store.archive.scanInventoryUndoIntents().values.first?.phase,.complete)
        XCTAssertTrue(store.completedTargetIds.isEmpty); XCTAssertEqual(store.overlayTargets.count,1)
        for record in records { XCTAssertTrue(try store.archive.measurement(id: record.id).wasApplicationUndone) }
    }
    @MainActor func testRefusedRecoveryKeepsTargetVisibleAndDoesNotDisableMeasurement() async throws {
        let (root,store,records) = try await fixture(conflict: true); defer { try? FileManager.default.removeItem(at: root) }
        XCTAssertNil(store.error); XCTAssertFalse(store.issues.isEmpty); XCTAssertEqual(store.unresolvedUndoMeasurementIds.count,2)
        XCTAssertTrue(store.completedTargetIds.isEmpty); XCTAssertEqual(store.overlayTargets.count,1)
        for record in records { XCTAssertEqual(try store.archive.measurement(id: record.id).selection,.accepted) }
        let capture = FieldCaptureCoordinator(),id = UUID().uuidString
        capture.configure(store: store,kind: .dbh,settings: .ephemeral(),calibration: .identity,context: [:])
        capture.handle(.started(id: id,rawRequested: false,aiEnabled: false,typed: true))
        capture.handle(.result(id: id,value: 25,sigma: nil,valid: true,reason: nil,ai: .init()))
        await store.awaitPendingWrites()
        XCTAssertEqual(capture.displayRecord?.liveValue,25); XCTAssertNil(capture.error)
        XCTAssertEqual(try store.archive.measurement(id: id).outcome,.result)
    }
    @MainActor func testUnreadableUndoNeverHidesPotentiallyUnfinishedTarget() async throws {
        let (root,store,records) = try await fixture(conflict: false,corruptIntent: true)
        defer { try? FileManager.default.removeItem(at: root) }
        XCTAssertNil(store.error); XCTAssertFalse(store.inventoryUndoInventoryVerified); XCTAssertFalse(store.issues.isEmpty)
        XCTAssertTrue(store.completedTargetIds.isEmpty); XCTAssertEqual(store.overlayTargets.count,1)
        for record in records { XCTAssertEqual(try store.archive.measurement(id: record.id).selection,.accepted) }
    }
}
