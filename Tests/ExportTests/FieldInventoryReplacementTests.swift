import XCTest
import Models
import Common

/// Synthetic repeated-tree capture histories, not field observations.
final class FieldInventoryReplacementTests: XCTestCase {
    private func tree() -> Tree {
        .init(id: UUID(),plotId: UUID(),treeNumber: 7,treeName: "SYNTHETIC-0007",speciesCode: "DF",status: .live,
            dbhCm: 25.12345,dbhMethod: .manualVisual,dbhSigmaMm: 1,dbhRmseMm: nil,dbhCoverageDeg: nil,dbhNInliers: nil,
            dbhConfidence: .yellow,dbhIsIrregular: false,heightM: 12.34567,heightMethod: .manualEntry,heightSource: "measured",
            heightSigmaM: nil,heightDHM: nil,heightAlphaTopDeg: nil,heightAlphaBaseDeg: nil,heightConfidence: .yellow,
            bearingFromCenterDeg: 32,distanceFromCenterM: 4,boundaryCall: nil,crownClass: "D",damageCodes: ["SYNTHETIC"],
            isMultistem: false,parentTreeId: nil,notes: "Original note, with \"quote\"\nand line",photoPath: "old.jpg",rawScanPath: nil,
            createdAt: Date(timeIntervalSince1970: 100),updatedAt: Date(timeIntervalSince1970: 101),deletedAt: nil,
            latitude: 45,longitude: -123,dbhMeasurementId: UUID().uuidString,heightMeasurementId: UUID().uuidString)
    }
    private func root() throws -> URL {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("forestix-inventory-proof-"+UUID().uuidString)
        try FileManager.default.createDirectory(at: root,withIntermediateDirectories: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: root) }; return root
    }
    private func config(_ repeatNumber: Int = 1) -> FieldStudyConfiguration {
        var c = FieldStudyConfiguration(); c.enabled = true; c.studyId = "SYNTHETIC"; c.operatorId = "TEST"
        c.plannedRepetitions = 3; c.plannedRepeat = repeatNumber; return c
    }
    private let target = FieldStudyTarget(studyId: "SYNTHETIC",treeId: "0007",label: "7")
    private func record(id: String,tree: Tree,kind: FieldStudyKind,round: Int,value: Double,accepted: Bool) -> FieldMeasurementRecord {
        var r = FieldMeasurementRecord(id: id,sessionId: UUID().uuidString,treeId: target.treeId,targetId: target.id,targetSnapshot: target,
            kind: kind,attemptNumber: 1,configuration: config(round),runtimeSettings: [:])
        r.outcome = .result; r.liveValue = value
        if accepted { r.selection = .accepted; r.resultStore = "cruise"; r.resultRecordId = tree.id.uuidString }
        return r
    }
    func testNativeRowCodecPreservesBothFloatValuesIDsAndEveryOriginalField() throws {
        let original = tree(),snapshot = try FieldInventoryReplacementSnapshot(tree: original)
        XCTAssertEqual(try JSONDecoder().decode(Tree.self,from: Data(snapshot.payloadJSON.utf8)),original)
        XCTAssertEqual(snapshot.results.map(\.value),[Double(original.dbhCm),Double(original.heightM!)])
        XCTAssertEqual(snapshot.results.map(\.measurementId),[original.dbhMeasurementId,original.heightMeasurementId])
        XCTAssertEqual(try FieldStudyJSON.decoder().decode(FieldInventoryReplacementSnapshot.self,from: FieldStudyJSON.encoder().encode(snapshot)),snapshot)
    }
    func testMalformedNativePayloadIsRefusedWhileUnknownLegacyIdentitiesStayUnknown() throws {
        var original = tree(); original.dbhMeasurementId = nil; original.heightMeasurementId = "legacy-unrecorded"
        let snapshot = try FieldInventoryReplacementSnapshot(tree: original)
        XCTAssertNil(snapshot.results[0].measurementId); XCTAssertEqual(snapshot.results[1].measurementId,"legacy-unrecorded")
        let row = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(snapshot.payloadJSON.utf8)) as? [String:Any])
        for field in ["notes","plotId","heightM"] {
            var broken = row; broken.removeValue(forKey: field)
            XCTAssertThrowsError(try FieldInventoryReplacementSnapshot(results: snapshot.results,payloadFormat: snapshot.payloadFormat,
                payloadJSON: String(decoding: JSONSerialization.data(withJSONObject: broken),as: UTF8.self)))
        }
        var broken = row; broken["dbhCm"] = true
        XCTAssertThrowsError(try FieldInventoryReplacementSnapshot(results: snapshot.results,payloadFormat: snapshot.payloadFormat,
            payloadJSON: String(decoding: JSONSerialization.data(withJSONObject: broken),as: UTF8.self)))
        original.deletedAt = Date(); XCTAssertThrowsError(try FieldInventoryReplacementSnapshot(tree: original))
    }
    func testDifferentRoundKeepsBothKindsAndOriginalPhotoInVerifiedExport() throws {
        let root = try root(),a = FieldStudyArchive(root: root.appendingPathComponent("field")),original = tree()
        try a.saveConfiguration(config(),developerMode: true); try a.saveTargets([target])
        let snapshot = try FieldInventoryReplacementSnapshot(tree: original)
        for result in snapshot.results { try a.createMeasurement(record(id: result.measurementId!,tree: original,kind: result.kind,round: 1,value: result.value,accepted: true)) }
        let newId = UUID().uuidString,next = record(id: newId,tree: original,kind: .dbh,round: 2,value: Double(Float(27.34567)),accepted: false)
        let expected = FieldExportApplicationResult(id: original.id.uuidString,store: "cruise",kind: .dbh,value: next.liveValue!,measurementId: newId)
        let intent = try FieldResultSaveIntent(snapshot: next,expected: expected,inventoryReplacement: snapshot)
        try a.prepareResultSave(intent); try a.recoverResultSave(id: newId,results: [expected])
        XCTAssertEqual(try a.measurement(id: original.dbhMeasurementId!).selection,.accepted)
        XCTAssertEqual(try a.measurement(id: original.heightMeasurementId!).selection,.accepted)
        let photos = root.appendingPathComponent("photos"); try FileManager.default.createDirectory(at: photos,withIntermediateDirectories: true)
        let photo = Data("synthetic original photo bytes".utf8); try photo.write(to: photos.appendingPathComponent("old.jpg"))
        let current = [expected,snapshot.results[1]]
        let output = try FieldStudyExport.build(archive: a,rawRoot: root.appendingPathComponent("raw"),results: current,
            outputDirectory: root.appendingPathComponent("exports"),applicationDirectories: [("measure-photos",photos)])
        XCTAssertTrue(output.report.integrityPassed,"\(output.report.issues)")
        let bytes = try Data(contentsOf: output.url)
        let files = try Dictionary(uniqueKeysWithValues: Common.ZipReader.entries(in: bytes).map { ($0.name,try Common.ZipReader.extract($0,from: bytes)) })
        XCTAssertEqual(files["application/measure-photos/old.jpg"],photo)
        let index = try XCTUnwrap(JSONSerialization.jsonObject(with: XCTUnwrap(files["application/replaced_inventory_index.json"])) as? [[String:Any]])
        XCTAssertEqual((index[0]["snapshot"] as? [String:Any])?["payload_json"] as? String,snapshot.payloadJSON)
        XCTAssertEqual(try a.completedReplacementProofs(for: .init(snapshot: record(id: original.dbhMeasurementId!,tree: original,kind: .dbh,round: 1,value: Double(original.dbhCm),accepted: false),expected: snapshot.results[0])),snapshot.results.filter { $0.kind == .dbh })
    }
    func testPreparedSnapshotIsImmutableAndCannotCrossTreeKindOrMeasurement() throws {
        let root = try root(),a = FieldStudyArchive(root: root),original = tree(),snapshot = try FieldInventoryReplacementSnapshot(tree: original)
        let id = UUID().uuidString,r = record(id: id,tree: original,kind: .dbh,round: 2,value: 28,accepted: false)
        let expected = FieldExportApplicationResult(id: original.id.uuidString,store: "cruise",kind: .dbh,value: 28,measurementId: id)
        let intent = try FieldResultSaveIntent(snapshot: r,expected: expected,inventoryReplacement: snapshot); try a.prepareResultSave(intent)
        var edited = original; edited.notes += " changed"
        XCTAssertThrowsError(try a.prepareResultSave(.init(snapshot: r,expected: expected,inventoryReplacement: .init(tree: edited))))
        XCTAssertThrowsError(try FieldResultSaveIntent(snapshot: r,expected: .init(id: UUID().uuidString,store: "cruise",kind: .dbh,value: 28,measurementId: id),inventoryReplacement: snapshot))
        edited.dbhMeasurementId = id
        XCTAssertThrowsError(try FieldResultSaveIntent(snapshot: r,expected: expected,inventoryReplacement: .init(tree: edited)))
        XCTAssertTrue(try a.completedReplacementProofs(for: intent).isEmpty)
    }
    func testProofSelectionCannotHideCurrentEditsDeletionDuplicatesOrConflictingHistory() throws {
        let old = try FieldInventoryReplacementSnapshot(tree: tree()).results[0]
        let newer = FieldExportApplicationResult(id: old.id,store: old.store,kind: old.kind,value: 30,measurementId: UUID().uuidString)
        func select(_ native: [FieldExportApplicationResult],_ archived: [FieldExportApplicationResult]) -> [FieldExportApplicationResult] {
            FieldResultSaveIntent.proofCandidates(expected: old,native: native,archived: archived)
        }
        XCTAssertEqual(select([newer],[old,old]),[old])
        XCTAssertEqual(select([newer],[]),[newer])
        let edit = FieldExportApplicationResult(id: old.id,store: old.store,kind: old.kind,value: 99,measurementId: old.measurementId)
        XCTAssertEqual(select([edit],[old]),[edit]); XCTAssertEqual(select([old,old],[old]),[old,old])
        let deleted = FieldExportApplicationResult(id: newer.id,store: newer.store,kind: newer.kind,value: newer.value,measurementId: newer.measurementId,deleted: true)
        XCTAssertEqual(select([deleted],[old]),[deleted]); XCTAssertEqual(select([newer],[old,edit]).count,2)
    }
    func testMissingHistoricalNativeSnapshotIsReportedNotInventedFromValueOrTime() throws {
        let root = try root(),a = FieldStudyArchive(root: root.appendingPathComponent("field")),original = tree()
        try a.saveConfiguration(config(),developerMode: true); try a.saveTargets([target])
        let old = record(id: original.dbhMeasurementId!,tree: original,kind: .dbh,round: 1,value: Double(original.dbhCm),accepted: true)
        let id = UUID().uuidString,next = record(id: id,tree: original,kind: .dbh,round: 2,value: 28,accepted: true)
        try a.createMeasurement(old); try a.createMeasurement(next)
        let output = try FieldStudyExport.build(archive: a,rawRoot: root.appendingPathComponent("raw"),
            results: [.init(id: original.id.uuidString,store: "cruise",kind: .dbh,value: 28,measurementId: id)],outputDirectory: root.appendingPathComponent("exports"))
        XCTAssertTrue(output.report.issues.contains { $0.measurementId == old.id && ["application_measurement_identity_mismatch","application_result_mismatch"].contains($0.code) },"\(output.report.issues)")
        XCTAssertFalse(output.report.integrityPassed)
    }
    func testLateRecoveryCannotSupersedeTheNewerSameRoundAndDoesNotInventAcceptanceTime() throws {
        for round in [1,2] {
            let root = try root(),a = FieldStudyArchive(root: root),original = tree(),snapshot = try FieldInventoryReplacementSnapshot(tree: original)
            let old = record(id: original.dbhMeasurementId!,tree: original,kind: .dbh,round: 1,value: Double(original.dbhCm),accepted: false)
            let oldIntent = try FieldResultSaveIntent(snapshot: old,expected: snapshot.results[0]); try a.prepareResultSave(oldIntent)
            let id = UUID().uuidString,newer = record(id: id,tree: original,kind: .dbh,round: round,value: 28,accepted: false)
            let expected = FieldExportApplicationResult(id: original.id.uuidString,store: "cruise",kind: .dbh,value: 28,measurementId: id)
            try a.prepareResultSave(.init(snapshot: newer,expected: expected,inventoryReplacement: snapshot))
            try a.recoverResultSave(id: id,results: [expected])
            let proof = FieldResultSaveIntent.proofCandidates(expected: oldIntent.expected,native: [expected],archived: try a.completedReplacementProofs(for: oldIntent))
            try a.recoverResultSave(id: old.id,results: proof)
            XCTAssertEqual(try a.measurement(id: id).selection,.accepted)
            let recovered = try a.measurement(id: old.id)
            XCTAssertEqual(recovered.selection,round == 1 ? .superseded : .accepted)
            XCTAssertNil(recovered.timing?.acceptedElapsedSeconds)
            XCTAssertNil(recovered.uiFlowTiming?.atAccepted)
            try a.reconcileCompletedReplacements()
            XCTAssertEqual(try a.measurement(id: id).selection,.accepted)
        }
    }
    func testUndoRetainsSnapshotButDoesNotUseUndoneReplacementAsCurrentProof() throws {
        let root = try root(),a = FieldStudyArchive(root: root.appendingPathComponent("field")),original = tree()
        try a.saveConfiguration(config(),developerMode: true); try a.saveTargets([target])
        let snapshot = try FieldInventoryReplacementSnapshot(tree: original)
        for result in snapshot.results { try a.createMeasurement(record(id: result.measurementId!,tree: original,kind: result.kind,round: 1,value: result.value,accepted: true)) }
        let id = UUID().uuidString,newer = record(id: id,tree: original,kind: .dbh,round: 2,value: 28,accepted: false)
        try a.prepareResultSave(.init(snapshot: newer,expected: .init(id: original.id.uuidString,store: "cruise",kind: .dbh,value: 28,measurementId: id),inventoryReplacement: snapshot))
        try a.recoverResultSave(id: id,results: [.init(id: original.id.uuidString,store: "cruise",kind: .dbh,value: 28,measurementId: id)])
        _ = try a.undoApplicationMeasurements(operationId: UUID().uuidString,resultRecordId: original.id.uuidString,measurementIds: [id])
        let output = try FieldStudyExport.build(archive: a,rawRoot: root.appendingPathComponent("raw"),results: snapshot.results,outputDirectory: root.appendingPathComponent("exports"))
        XCTAssertTrue(output.report.integrityPassed,"\(output.report.issues)")
        XCTAssertTrue(try a.measurement(id: id).wasApplicationUndone)
    }
}
