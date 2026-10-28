import XCTest
import CoreData
import Models
import Persistence

/// Synthetic stores only. The cut-point test closes and reopens an actual
/// on-disk Core Data SQLite store, not merely an in-memory state machine.
final class InventoryUndoRecoveryTests: XCTestCase {
    private struct World {
        let stack: CoreDataStack
        let repo: CoreDataTreeRepository
        let archive: FieldStudyArchive
        let original: Tree
        let saved: Tree
        let oldRecord: FieldMeasurementRecord
        let newRecord: FieldMeasurementRecord
    }
    private func root() -> URL { FileManager.default.temporaryDirectory.appendingPathComponent("forestix-inventory-recovery-"+UUID().uuidString) }
    private func tree() -> Tree {
        .init(id: UUID(),plotId: UUID(),treeNumber: 7,treeName: "0003",speciesCode: "DF",status: .deadStanding,
              dbhCm: 30,dbhMethod: .manualCaliper,dbhSigmaMm: 1,dbhRmseMm: 2,dbhCoverageDeg: 90,dbhNInliers: 60,
              dbhConfidence: .green,dbhIsIrregular: true,dbhCaptureMode: "typed",dbhEstimatorEpoch: 7,
              heightM: 21,heightMethod: .manualEntry,heightSource: "measured",heightSigmaM: 0.2,heightDHM: 13.625,
              heightAlphaTopDeg: 50,heightAlphaBaseDeg: -5,heightConfidence: .yellow,
              bearingFromCenterDeg: 25,distanceFromCenterM: 4,boundaryCall: "in",crownClass: "dominant",
              damageCodes: ["a,b","x\ny","🌲"],isMultistem: true,parentTreeId: UUID(),notes: "original\n\"note\"",
              photoPath: "old.jpg",rawScanPath: "old.raw",createdAt: Date(timeIntervalSinceReferenceDate: 1234.5678912345),
              updatedAt: Date(timeIntervalSinceReferenceDate: 1235.987654321),deletedAt: nil,latitude: 45.12345678901234,longitude: -123.1234567890123)
    }
    private func record(row: UUID,value: Double) throws -> FieldMeasurementRecord {
        var c = FieldStudyConfiguration(); c.enabled = true; c.studyId = "SYNTHETIC"; c.operatorId = "TEST"
        var s = FieldMeasurementSession(now: 0); let id = UUID().uuidString,flow = FieldUIFlowClock(kind: .dbh)
        flow.open(now: 0,foreground: true)
        try s.begin(id: id,configuration: c,target: .init(studyId: c.studyId,treeId: "0003",label: "003"),kind: .dbh,
                    runtime: [:],references: [],rawRequested: true,aiEnabled: false,typed: false,now: 1)
        try s.finish(id: id,value: value,sigma: 1,valid: true,reason: nil,ai: .init(),now: 2)
        try s.accept(id: id,resultRecordId: row.uuidString,resultStore: "cruise",now: 3)
        try s.updateUIFlow(XCTUnwrap(flow.evidence(now: 3,previous: nil,result: true,accepted: true)))
        var r = try XCTUnwrap(s.current); r.rawStatus = .saved; return r
    }
    private func world(_ root: URL,model: NSManagedObjectModel,onDisk: Bool = false) throws -> World {
        try FileManager.default.createDirectory(at: root,withIntermediateDirectories: true)
        let stack = try CoreDataStack(configuration: onDisk ? .sqlite(url: root.appendingPathComponent("inventory.sqlite")) : .inMemory,model: model)
        let repo = CoreDataTreeRepository(stack: stack),source = tree()
        let original = try repo.createIfNumberFree(source); XCTAssertEqual(original,source)
        var next = original; next.dbhCm = 44; next.photoPath = "new.jpg"; next.rawScanPath = "new.raw"; next.updatedAt = Date(timeIntervalSinceReferenceDate: 1238.1122334455)
        let saved = try repo.replaceIfUnchanged(next,expected: original),a = FieldStudyArchive(root: root.appendingPathComponent("study"))
        let old = try record(row: saved.id,value: 30),new = try record(row: saved.id,value: 44)
        try a.createMeasurement(old); try a.createMeasurement(new); _ = try a.supersedeAcceptedMeasurement(id: old.id,by: new.id)
        return .init(stack: stack,repo: repo,archive: a,original: original,saved: saved,oldRecord: old,newRecord: new)
    }
    func testEveryCrashCutReopensSQLiteAndRestoresExactTreeAndJournal() throws {
        let model = try TestModelLoader.loadTimberCruisingModel()
        for cut in 0...3 {
            let dir = root(); defer { try? FileManager.default.removeItem(at: dir) }
            let w = try world(dir,model: model,onDisk: true),id = UUID().uuidString
            let prepared = try w.archive.prepareInventoryUndo(id: id,saved: w.saved,original: w.original,measurementIds: [w.newRecord.id])
            XCTAssertEqual(try FieldInventoryUndoIntent.decode(prepared.encoded()),prepared)
            XCTAssertEqual(w.archive.scanInventoryUndoIntents().values.first?.saved,w.saved)
            XCTAssertEqual(w.archive.scanInventoryUndoIntents().values.first?.original?.heightDHM,13.625)
            if cut >= 1 { _ = try w.repo.replaceIfUnchanged(w.original,expected: w.saved) }
            if cut >= 2 { _ = try w.archive.transitionInventoryUndo(id: id,to: .applicationApplied) }
            if cut >= 3 { _ = try w.archive.undoApplicationMeasurements(operationId: id,resultRecordId: w.saved.id.uuidString,measurementIds: [w.newRecord.id]) }
            for store in w.stack.container.persistentStoreCoordinator.persistentStores { try w.stack.container.persistentStoreCoordinator.remove(store) }
            let reopened = try CoreDataStack(configuration: .sqlite(url: dir.appendingPathComponent("inventory.sqlite")),model: model)
            let repo = CoreDataTreeRepository(stack: reopened),archive = FieldStudyArchive(root: w.archive.root)
            XCTAssertTrue(FieldInventoryUndoCoordinator(repository: repo,archive: archive).recover().isEmpty,"cut \(cut)")
            XCTAssertEqual(try repo.readCurrent(id: w.saved.id,includeDeleted: true),w.original)
            XCTAssertEqual(try archive.measurement(id: w.oldRecord.id).selection,.accepted)
            let undone = try archive.measurement(id: w.newRecord.id)
            XCTAssertTrue(undone.wasApplicationUndone); XCTAssertEqual(undone.liveValue,44)
            XCTAssertEqual(undone.rawBundleId,w.newRecord.rawBundleId); XCTAssertEqual(undone.uiFlowTiming,w.newRecord.uiFlowTiming)
            XCTAssertEqual(archive.scanInventoryUndoIntents().values.first?.phase,.complete)
        }
    }
    func testNewRowDeletionRecoversBeforeAndAfterDeleteWithRecordingOff() throws {
        let model = try TestModelLoader.loadTimberCruisingModel()
        for alreadyDeleted in [false,true] {
            let dir = root(); defer { try? FileManager.default.removeItem(at: dir) }
            let w = try world(dir,model: model),id = UUID().uuidString
            _ = try w.archive.prepareInventoryUndo(id: id,saved: w.saved,original: nil,measurementIds: [])
            if alreadyDeleted { try w.repo.deleteIfUnchanged(w.saved) }
            let fresh = FieldStudyArchive(root: w.archive.root)
            XCTAssertTrue(FieldInventoryUndoCoordinator(repository: w.repo,archive: fresh).recover().isEmpty)
            XCTAssertNil(try w.repo.readCurrent(id: w.saved.id,includeDeleted: true))
            XCTAssertEqual(fresh.scanInventoryUndoIntents().values.first?.phase,.complete)
            // An unrecorded application action must not rewrite earlier journal entries.
            XCTAssertEqual(try fresh.measurement(id: w.newRecord.id).selection,.accepted)
        }
    }
    func testIntentWriteFailureCannotChangeInventoryOrJournal() throws {
        let dir = root(); defer { try? FileManager.default.removeItem(at: dir) }
        let w = try world(dir,model: TestModelLoader.loadTimberCruisingModel())
        try Data([1]).write(to: w.archive.root.appendingPathComponent("inventory-undos"))
        XCTAssertThrowsError(try FieldInventoryUndoCoordinator(repository: w.repo,archive: w.archive).execute(id: UUID().uuidString,saved: w.saved,original: w.original,measurementIds: [w.newRecord.id]))
        XCTAssertEqual(try w.repo.readCurrent(id: w.saved.id),w.saved)
        XCTAssertEqual(try w.archive.measurement(id: w.newRecord.id),w.newRecord)
    }
    func testIndependentEditAndSoftDeleteAreRefusedNotOverwritten() throws {
        let model = try TestModelLoader.loadTimberCruisingModel()
        for deleted in [false,true] {
            let dir = root(); defer { try? FileManager.default.removeItem(at: dir) }
            let w = try world(dir,model: model),id = UUID().uuidString
            _ = try w.archive.prepareInventoryUndo(id: id,saved: w.saved,original: w.original,measurementIds: [w.newRecord.id])
            var changed = w.saved; changed.notes = "independent edit"
            if deleted { changed.deletedAt = Date(timeIntervalSinceReferenceDate: 1250) }
            _ = try w.repo.update(changed)
            let coordinator = FieldInventoryUndoCoordinator(repository: w.repo,archive: w.archive)
            XCTAssertFalse(coordinator.recover().isEmpty)
            XCTAssertEqual(try w.repo.readCurrent(id: changed.id,includeDeleted: true),changed)
            let intent = try XCTUnwrap(w.archive.scanInventoryUndoIntents().values.first)
            XCTAssertEqual(intent.phase,.refused); XCTAssertTrue(intent.unresolvedMeasurementIds.contains(w.newRecord.id.lowercased()))
            XCTAssertEqual(try w.archive.measurement(id: w.newRecord.id).selection,.accepted)
            XCTAssertThrowsError(try w.archive.transitionInventoryUndo(id: id,to: .complete))
        }
    }
    func testCompletedRetryIsIdempotentAndCannotOverwriteLaterEditsOrReuseUUID() throws {
        let dir = root(); defer { try? FileManager.default.removeItem(at: dir) }
        let w = try world(dir,model: TestModelLoader.loadTimberCruisingModel()),id = UUID().uuidString
        let c = FieldInventoryUndoCoordinator(repository: w.repo,archive: w.archive)
        try c.execute(id: id,saved: w.saved,original: w.original,measurementIds: [w.newRecord.id])
        var edited = w.original; edited.notes = "after Undo"; _ = try w.repo.update(edited)
        try c.execute(id: id.lowercased(),saved: w.saved,original: w.original,measurementIds: [w.newRecord.id.lowercased()])
        XCTAssertTrue(c.recover().isEmpty); XCTAssertEqual(try w.repo.readCurrent(id: edited.id),edited)
        XCTAssertThrowsError(try c.execute(id: id,saved: edited,original: w.original,measurementIds: [w.newRecord.id]))
    }
    func testDuplicateOrCorruptIntentInventoryFailsClosedBeforeAnyMutation() throws {
        let model = try TestModelLoader.loadTimberCruisingModel()
        for corrupt in [false,true] {
            let dir = root(); defer { try? FileManager.default.removeItem(at: dir) }
            let w = try world(dir,model: model)
            _ = try w.archive.prepareInventoryUndo(id: UUID().uuidString,saved: w.saved,original: w.original,measurementIds: [w.newRecord.id])
            let extra = try FieldInventoryUndoIntent(id: UUID().uuidString,saved: w.saved,original: w.original,measurementIds: [w.newRecord.id])
            let data = corrupt ? Data("{invalid".utf8) : try extra.encoded()
            try data.write(to: w.archive.root.appendingPathComponent("inventory-undos/"+extra.id+".json"))
            XCTAssertFalse(FieldInventoryUndoCoordinator(repository: w.repo,archive: w.archive).recover().isEmpty)
            XCTAssertEqual(try w.repo.readCurrent(id: w.saved.id),w.saved)
            XCTAssertEqual(try w.archive.measurement(id: w.newRecord.id).selection,.accepted)
        }
    }
}
