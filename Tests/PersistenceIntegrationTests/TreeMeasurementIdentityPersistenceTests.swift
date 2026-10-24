import XCTest
import CoreData
import Models
import Persistence

final class TreeMeasurementIdentityPersistenceTests: XCTestCase {
    private func root() -> URL {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("forestix-tree-id-store-"+UUID().uuidString)
        addTeardownBlock { try? FileManager.default.removeItem(at: root) }; return root
    }
    private func tree(number: Int = 7) -> Tree {
        .init(id: UUID(),plotId: UUID(),treeNumber: number,treeName: "00\(number)",speciesCode: "DF",status: .deadStanding,
              dbhCm: 25.125,dbhMethod: .manualCaliper,dbhSigmaMm: .init(bitPattern: 0x3f800001),dbhRmseMm: 2,dbhCoverageDeg: 90,dbhNInliers: 60,
              dbhConfidence: .yellow,dbhIsIrregular: true,dbhCaptureMode: "manual",dbhEstimatorEpoch: 7,
              heightM: 21,heightMethod: .manualEntry,heightSource: "measured",heightSigmaM: 0.2,heightDHM: 13.625,
              heightAlphaTopDeg: 50,heightAlphaBaseDeg: -5,heightConfidence: .green,bearingFromCenterDeg: 25,distanceFromCenterM: 4,
              boundaryCall: "in",crownClass: "dominant",damageCodes: ["x,y","line\n🌲"],isMultistem: true,parentTreeId: UUID(),
              notes: "synthetic,\n\"original\"",photoPath: "old.jpg",rawScanPath: "old.raw",createdAt: Date(timeIntervalSinceReferenceDate: 1234.5678912345),
              updatedAt: Date(timeIntervalSinceReferenceDate: 1235.987654321),deletedAt: nil,latitude: 45.12345678901234,longitude: -123.1234567890123)
    }
    private func close(_ stack: CoreDataStack) throws {
        for store in stack.container.persistentStoreCoordinator.persistentStores { try stack.container.persistentStoreCoordinator.remove(store) }
    }
    func testInferredSQLiteV6ToV7MigrationPreservesAllAttributesOf100LegacyTrees() throws {
        let root = root(); try FileManager.default.createDirectory(at: root,withIntermediateDirectories: true)
        let oldModel = try TestModelLoader.loadTimberCruisingModel(version: "TimberCruising 6"),newModel = try TestModelLoader.loadTimberCruisingModel()
        let oldKeys = Set(try XCTUnwrap(oldModel.entitiesByName["TreeEntity"]).attributesByName.keys)
        let newKeys = Set(try XCTUnwrap(newModel.entitiesByName["TreeEntity"]).attributesByName.keys)
        XCTAssertEqual(newKeys.subtracting(oldKeys),["dbhMeasurementId","heightMeasurementId"])
        for entity in oldModel.entities where entity.name != "TreeEntity" {
            XCTAssertEqual(entity.versionHash,newModel.entitiesByName[entity.name!]!.versionHash)
        }
        let staging = try CoreDataStack(configuration: .inMemory,model: newModel),repository = CoreDataTreeRepository(stack: staging)
        var originals: [UUID:Tree] = [:]
        for number in 1...100 { let value = tree(number: number); originals[value.id] = try repository.createIfNumberFree(value) }
        let stagingContext = staging.newBackgroundContext()
        let originalAttributes: [[String:Any]] = try stagingContext.performAndWait {
            try stagingContext.fetch(NSFetchRequest<NSManagedObject>(entityName: "TreeEntity")).map { $0.dictionaryWithValues(forKeys: Array(oldKeys)) }
        }
        let oldURL = root.appendingPathComponent("v6.sqlite"),newURL = root.appendingPathComponent("v7.sqlite")
        let legacy = try CoreDataStack(configuration: .sqlite(url: oldURL),model: oldModel)
        let ctx = legacy.newBackgroundContext()
        try ctx.performAndWait {
            for attributes in originalAttributes { NSEntityDescription.insertNewObject(forEntityName: "TreeEntity",into: ctx).setValuesForKeys(attributes) }
            try ctx.save()
        }
        try close(legacy)
        let mapping = try NSMappingModel.inferredMappingModel(forSourceModel: oldModel,destinationModel: newModel)
        let migration = NSMigrationManager(sourceModel: oldModel,destinationModel: newModel)
        try migration.migrateStore(from: oldURL,sourceType: NSSQLiteStoreType,options: [NSReadOnlyPersistentStoreOption:true],with: mapping,
                                   toDestinationURL: newURL,destinationType: NSSQLiteStoreType,destinationOptions: nil)
        let migrated = try CoreDataStack(configuration: .sqlite(url: newURL),model: newModel),current = CoreDataTreeRepository(stack: migrated)
        for original in originals.values {
            let row = try XCTUnwrap(current.readCurrent(id: original.id,includeDeleted: true))
            XCTAssertEqual(row,original); XCTAssertNil(row.dbhMeasurementId); XCTAssertNil(row.heightMeasurementId)
        }
        let one = try XCTUnwrap(originals.values.first); var captured = one
        captured.dbhMeasurementId = UUID().uuidString; captured.heightMeasurementId = UUID().uuidString
        _ = try current.replaceIfUnchanged(captured,expected: one); try close(migrated)
        let reopened = try CoreDataStack(configuration: .sqlite(url: newURL),model: newModel)
        XCTAssertEqual(try CoreDataTreeRepository(stack: reopened).readCurrent(id: one.id,includeDeleted: true),captured)
    }
    func testSQLiteAtomicWritesMetadataEditsAndUndoPreservePerKindIdentity() throws {
        let root = root(),model = try TestModelLoader.loadTimberCruisingModel()
        try FileManager.default.createDirectory(at: root,withIntermediateDirectories: true)
        let url = root.appendingPathComponent("current.sqlite"),stack = try CoreDataStack(configuration: .sqlite(url: url),model: model)
        let repo = CoreDataTreeRepository(stack: stack); var initial = tree()
        initial.dbhMeasurementId = UUID().uuidString.lowercased(); initial.heightMeasurementId = UUID().uuidString.lowercased()
        let original = try repo.createIfNumberFree(initial); XCTAssertEqual(original,initial)
        var newCapture = original; newCapture.dbhCm = 30; newCapture.dbhMeasurementId = UUID().uuidString
        let captured = try repo.replaceIfUnchanged(newCapture,expected: original)
        var metadata = captured; metadata.notes = "metadata only"
        let edited = try repo.update(metadata); XCTAssertEqual(edited.dbhMeasurementId,captured.dbhMeasurementId); XCTAssertEqual(edited.heightMeasurementId,captured.heightMeasurementId)
        var modified = edited; modified.dbhCm = 32; modified.dbhMeasurementId = edited.dbhMeasurementId!.lowercased()
        let unattributed = try repo.update(modified); XCTAssertNil(unattributed.dbhMeasurementId); XCTAssertEqual(unattributed.heightMeasurementId,original.heightMeasurementId)
        XCTAssertEqual(try repo.replaceIfUnchanged(original,expected: unattributed),original)
        var invalid = original; invalid.dbhCm = 99; invalid.dbhMeasurementId = "invalid"
        XCTAssertThrowsError(try repo.replaceIfUnchanged(invalid,expected: original)); XCTAssertEqual(try repo.readCurrent(id: original.id),original)
        try close(stack)
        let reopened = try CoreDataStack(configuration: .sqlite(url: url),model: model)
        XCTAssertEqual(try CoreDataTreeRepository(stack: reopened).readCurrent(id: original.id),original)
    }
    func testSameValueDifferentUUIDRefusesStaleUndoWithoutChangingResult() throws {
        let stack = try CoreDataStack(configuration: .inMemory,model: TestModelLoader.loadTimberCruisingModel()),repo = CoreDataTreeRepository(stack: stack)
        var one = tree(); one.dbhMeasurementId = UUID().uuidString
        let original = try repo.createIfNumberFree(one); var sameValueRetake = original; sameValueRetake.dbhMeasurementId = UUID().uuidString
        let latest = try repo.replaceIfUnchanged(sameValueRetake,expected: original)
        XCTAssertThrowsError(try repo.deleteIfUnchanged(original)); XCTAssertThrowsError(try repo.replaceIfUnchanged(original,expected: original))
        XCTAssertEqual(try repo.readCurrent(id: one.id),latest)
    }
}
