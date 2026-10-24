import XCTest
import Foundation
import Models
import Common
import CryptoKit

final class FieldReplacementArchiveTests: XCTestCase {
    private func root() throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("forestix-replaced-export-"+UUID().uuidString)
        try FileManager.default.createDirectory(at: url,withIntermediateDirectories: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: url) }; return url
    }
    private func row(_ id: String,value: Double = 25,photo: String? = nil) throws -> FieldQuickReplacementSnapshot {
        var native: [String:Any] = ["id":id,"kind":"dbh","value":value,"measurementId":id,"truth":26.5,"note":"SYNTHETIC"]
        native["photoPath"] = photo
        return try .init(result: .init(id: id,store: "quick_history",kind: .dbh,value: value,measurementId: id),payloadFormat: "ios.quick_entry_json.v1",
            payloadJSON: String(decoding: JSONSerialization.data(withJSONObject: native,options: [.sortedKeys]),as: UTF8.self))
    }
    func testCompletedReplacementSnapshotKeepsDifferentRoundObservationAndItsPhotoInExport() throws {
        let root = try root(),a = FieldStudyArchive(root: root.appendingPathComponent("field"))
        var c = FieldStudyConfiguration(); c.enabled = true; c.studyId = "SYNTHETIC"; c.operatorId = "TEST"; c.plannedRepetitions = 2
        try a.saveConfiguration(c,developerMode: true)
        let oldId = UUID().uuidString,newId = UUID().uuidString,original = try row(oldId,photo: "old.jpg")
        var old = FieldMeasurementRecord(id: oldId,sessionId: UUID().uuidString,treeId: "unassigned:"+oldId,kind: .dbh,attemptNumber: 1,configuration: c,runtimeSettings: [:])
        old.outcome = .result; old.selection = .accepted; old.liveValue = 25; old.resultStore = "quick_history"; old.resultRecordId = oldId; try a.createMeasurement(old)
        c.plannedRepeat = 2
        var next = FieldMeasurementRecord(id: newId,sessionId: UUID().uuidString,treeId: "unassigned:"+newId,kind: .dbh,attemptNumber: 1,configuration: c,runtimeSettings: [:])
        next.outcome = .result; next.liveValue = 26
        let expected = FieldExportApplicationResult(id: newId,store: "quick_history",kind: .dbh,value: 26,measurementId: newId)
        try a.prepareResultSave(.init(snapshot: next,expected: expected,replacements: [original]))
        XCTAssertThrowsError(try a.recoverResultSave(id: newId,results: [expected,original.result]))
        try a.recoverResultSave(id: newId,results: [expected]); XCTAssertEqual(try a.measurement(id: oldId).selection,.accepted)
        let photos = root.appendingPathComponent("photos"); try FileManager.default.createDirectory(at: photos,withIntermediateDirectories: true)
        let bytes = Data("synthetic opaque photo evidence".utf8); try bytes.write(to: photos.appendingPathComponent("old.jpg"))
        let result = try FieldStudyExport.build(archive: a,rawRoot: root.appendingPathComponent("raw"),results: [expected],outputDirectory: root.appendingPathComponent("exports"),applicationDirectories: [("measure-photos",photos)])
        XCTAssertTrue(result.report.integrityPassed,String(describing: result.report.issues)); XCTAssertEqual(try Data(contentsOf: photos.appendingPathComponent("old.jpg")),bytes)
        let zip = try Data(contentsOf: result.url)
        let files = try Dictionary(uniqueKeysWithValues: Common.ZipReader.entries(in: zip).map { ($0.name,try Common.ZipReader.extract($0,from: zip)) })
        XCTAssertEqual(files["application/measure-photos/old.jpg"],bytes)
        let index = try XCTUnwrap(JSONSerialization.jsonObject(with: XCTUnwrap(files["application/replaced_result_index.json"])) as? [[String:Any]])
        XCTAssertEqual(index.first?["storage_state"] as? String,"replaced_snapshot"); XCTAssertEqual(index.first?["source_measurement_id"] as? String,newId)
        let proofs = try FieldStudyJSON.decoder().decode([FieldExportFileProof].self,from: XCTUnwrap(files["file_inventory.json"]))
        for proof in proofs { let data = try XCTUnwrap(files[proof.path]); XCTAssertEqual(UInt64(data.count),proof.bytes); XCTAssertEqual(SHA256.hash(data: data).map { String(format: "%02x",$0) }.joined(),proof.sha256) }
        let absent = try FieldStudyExport.build(archive: a,rawRoot: root.appendingPathComponent("raw"),results: [expected],outputDirectory: root.appendingPathComponent("exports"))
        XCTAssertTrue(absent.report.issues.contains { $0.code == "application_photo_missing" })
    }
    func testReplacementProofIsNotUsableBeforeCompletionAndPayloadTamperingIsRejected() throws {
        let root = try root(),a = FieldStudyArchive(root: root),oldId = UUID().uuidString,newId = UUID().uuidString
        let original = try row(oldId)
        var c = FieldStudyConfiguration(); c.enabled = true; c.studyId = "SYNTHETIC"; c.operatorId = "TEST"
        var r = FieldMeasurementRecord(id: newId,sessionId: UUID().uuidString,treeId: "unassigned",kind: .dbh,attemptNumber: 1,configuration: c,runtimeSettings: [:])
        r.outcome = .result; r.liveValue = 26
        let intent = try FieldResultSaveIntent(snapshot: r,expected: .init(id: newId,store: "quick_history",kind: .dbh,value: 26,measurementId: newId),replacements: [original])
        try a.prepareResultSave(intent)
        let oldIntent = try FieldResultSaveIntent(snapshot: { var v = r; v.id = oldId; v.liveValue = 25; return v }(),expected: original.result)
        XCTAssertTrue(try a.completedReplacementProofs(for: oldIntent).isEmpty)
        XCTAssertThrowsError(try FieldQuickReplacementSnapshot(result: original.result,payloadFormat: original.payloadFormat,payloadJSON: original.payloadJSON.replacingOccurrences(of: "25",with: "24")))
        XCTAssertEqual(try FieldStudyJSON.decoder().decode(FieldResultSaveIntent.self,from: FieldStudyJSON.encoder().encode(intent)),intent)
        XCTAssertThrowsError(try FieldResultSaveIntent(snapshot: r,expected: intent.expected,replacements: [original,original]))
        XCTAssertThrowsError(try FieldQuickReplacementSnapshot(result: .init(id: oldId,store: "quick_history",kind: .dbh,value: 1,measurementId: oldId),payloadFormat: original.payloadFormat,
            payloadJSON: "{\"id\":\"\(oldId)\",\"kind\":\"dbh\",\"value\":true,\"measurementId\":\"\(oldId)\"}"))
    }
    func testPhotoDirectoryMutationOrSymlinkRefusesExportWithoutModifyingOriginals() throws {
        let root = try root(),a = FieldStudyArchive(root: root.appendingPathComponent("field")),photos = root.appendingPathComponent("photos")
        try FileManager.default.createDirectory(at: photos,withIntermediateDirectories: true); let original = photos.appendingPathComponent("one.jpg")
        try Data([1,2,3]).write(to: original)
        XCTAssertThrowsError(try FieldStudyExport.build(archive: a,rawRoot: root.appendingPathComponent("raw"),results: [],outputDirectory: root.appendingPathComponent("exports"),applicationDirectories: [("measure-photos",photos)]) { name in
            if name == "application/measure-photos/one.jpg" { try Data([4]).write(to: photos.appendingPathComponent("two.jpg")) }
        })
        XCTAssertEqual(try Data(contentsOf: original),Data([1,2,3]))
        try FileManager.default.createSymbolicLink(at: photos.appendingPathComponent("link.jpg"),withDestinationURL: original)
        XCTAssertThrowsError(try FieldStudyExport.build(archive: a,rawRoot: root.appendingPathComponent("raw"),results: [],outputDirectory: root.appendingPathComponent("exports"),applicationDirectories: [("measure-photos",photos)]))
    }
}
