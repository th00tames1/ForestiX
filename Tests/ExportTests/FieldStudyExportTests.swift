import XCTest
import Foundation
import CryptoKit
import Common
import Models

final class FieldStudyExportTests: XCTestCase {
    func testLegacyConfidenceReferencesArePreservedAndMissingOrMalformedFilesAreAudited() throws {
        let base = try root(); let (archive,_,_) = try setup(base)
        let id = UUID().uuidString, raw = base.appendingPathComponent("raw"), bundle = raw.appendingPathComponent(id)
        try FileManager.default.createDirectory(at: bundle,withIntermediateDirectories: true)
        try Data([0,0,128,63]).write(to: bundle.appendingPathComponent("depth.bin"))
        func manifest(_ name: String) throws {
            try JSONSerialization.data(withJSONObject: ["schema":2,"kind":"dbh","measurement_id":id,
                "dbh":["frames":[["depth_file":"depth.bin","confidence_file":name,"width":1,"height":1,"format":"f32m"]]]])
                .write(to: bundle.appendingPathComponent("manifest.json"))
        }
        func build() throws -> FieldStudyExportArtifact {
            try FieldStudyExport.build(archive: archive,rawRoot: raw,results: [],outputDirectory: base.appendingPathComponent("exports"))
        }
        try manifest("depth.conf")
        let missing = try build()
        XCTAssertTrue(missing.report.issues.contains { $0.code == "raw_file_missing" && $0.path.hasSuffix("depth.conf") })
        try Data([2,2]).write(to: bundle.appendingPathComponent("depth.conf"))
        XCTAssertTrue(try build().report.issues.contains { $0.code == "raw_confidence_size_mismatch" })
        try Data([1]).write(to: bundle.appendingPathComponent("depth.conf"))
        let valid = try build()
        XCTAssertFalse(valid.report.issues.contains { $0.path.hasSuffix("depth.conf") })
        XCTAssertEqual(try payloads(valid.url)["raw-captures/"+id+"/depth.conf"],Data([1]))
        try manifest("../depth.conf")
        XCTAssertTrue(try build().report.issues.contains { $0.code == "unsafe_raw_file_reference" })
    }
    func testActualIOSAndAndroidExportCodecsInteroperateWithScopeAndTiming() throws {
        for producer in ["ios","android"] {
            let url = try XCTUnwrap(Bundle.module.url(forResource: "field-export-scope-"+producer,withExtension: "json",subdirectory: "Fixtures"))
            let record = try FieldStudyJSON.decoder().decode(FieldMeasurementRecord.self,from: Data(contentsOf: url)); try record.validate()
            XCTAssertEqual(record.studyId,"SYNTHETIC"); XCTAssertEqual(record.operatorId,"OP-1"); XCTAssertEqual(record.treeId,"0007")
            XCTAssertEqual(record.measurementScope?.references.first?.inputValue,25); XCTAssertEqual(record.uiFlowTiming?.atAccepted?.elapsedSeconds,6)
            let again = try FieldStudyJSON.decoder().decode(FieldMeasurementRecord.self,from: FieldStudyJSON.encoder().encode(record))
            XCTAssertEqual(again,record)
        }
    }
    private func root() throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("forestix-export-test-"+UUID().uuidString)
        try FileManager.default.createDirectory(at: url,withIntermediateDirectories: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: url) }; return url
    }
    private func setup(_ root: URL) throws -> (FieldStudyArchive,FieldStudyConfiguration,FieldStudyTarget) {
        let a = FieldStudyArchive(root: root.appendingPathComponent("field"))
        var c = FieldStudyConfiguration(); c.enabled = true; c.studyId = "SYNTHETIC"; c.operatorId = "OP-1"
        try a.saveConfiguration(c,developerMode: true)
        let t = FieldStudyTarget(studyId: c.studyId,treeId: "0007",label: "007"); try a.saveTargets([t]); return (a,c,t)
    }
    private func record(_ a: FieldStudyArchive,_ c: FieldStudyConfiguration,_ t: FieldStudyTarget,outcome: FieldAttemptOutcome = .result,selection: FieldSelectionStatus = .accepted) throws -> FieldMeasurementRecord {
        var r = FieldMeasurementRecord(sessionId: UUID().uuidString,treeId: t.treeId,targetId: t.id,targetSnapshot: t,kind: .dbh,attemptNumber: 1,configuration: c,runtimeSettings: [:])
        r.outcome = outcome; r.selection = selection
        if outcome == .failed { r.failureReason = "synthetic_failure" }
        if outcome == .result { r.liveValue = 25 }
        if selection == .accepted { r.resultRecordId = r.id; r.resultStore = "quick_history" }
        try a.createMeasurement(r); return r
    }
    private func payloads(_ url: URL) throws -> [String:Data] {
        let bytes = try Data(contentsOf: url)
        return try Dictionary(uniqueKeysWithValues: Common.ZipReader.entries(in: bytes).map { ($0.name,try Common.ZipReader.extract($0,from: bytes)) })
    }
    func testAllOutcomesOriginalUnitsGTRevisionsAndChecksummedBytesLeaveTogether() throws {
        let base = try root(); let (a,c,t) = try setup(base)
        let method = FieldReferenceMethod(id: "tape",name: "Tape, original",kind: .dbh,unit: .inch)
        let gt = FieldReferenceObservation(targetId: t.id,method: method,inputValue: 10,operatorId: "OP-1")
        try a.appendReference(gt,developerMode: true)
        let revision = FieldReferenceObservation(targetId: t.id,method: method,inputValue: 11,operatorId: "OP-2",supersedesId: gt.id)
        try a.appendReference(revision,developerMode: true)
        let r = try record(a,c,t)
        for outcome in [FieldAttemptOutcome.failed,.cancelled,.started] { _ = try record(a,c,t,outcome: outcome,selection: .pending) }
        let results = [FieldExportApplicationResult(id: r.id,store: "quick_history",kind: .dbh,value: 25,measurementId: r.id)]
        let journal = base.appendingPathComponent("original-journal.csv"); try Data("original,unchanged\n".utf8).write(to: journal)
        let artifact = try FieldStudyExport.build(archive: a,rawRoot: base.appendingPathComponent("raw"),results: results,
            applicationPayloads: [("quick-ios.json",Data("original application bytes".utf8))],outputDirectory: base.appendingPathComponent("exports"),applicationFiles: [("original.csv",journal)])
        XCTAssertEqual(artifact.report.measurementCount,4); XCTAssertEqual(artifact.report.referenceCount,2)
        let files = try payloads(artifact.url)
        XCTAssertEqual(files["application/quick-ios.json"],Data("original application bytes".utf8))
        XCTAssertEqual(files["application/original.csv"],Data("original,unchanged\n".utf8))
        let inventory = try FieldStudyJSON.decoder().decode([FieldExportFileProof].self,from: XCTUnwrap(files["file_inventory.json"]))
        XCTAssertEqual(inventory.count,files.count-1)
        for proof in inventory {
            let data = try XCTUnwrap(files[proof.path]); XCTAssertEqual(UInt64(data.count),proof.bytes)
            XCTAssertEqual(SHA256.hash(data: data).map { String(format: "%02x",$0) }.joined(),proof.sha256)
        }
        let csv = String(decoding: try XCTUnwrap(files["reference_index.csv"]),as: UTF8.self)
        XCTAssertTrue(csv.contains("Tape, original")); XCTAssertTrue(csv.contains(gt.id)); XCTAssertTrue(csv.contains(revision.id)); XCTAssertTrue(csv.contains("\"in\""))
        let original = try a.measurement(id: r.id); XCTAssertEqual(original,r)
        let audit = try JSONSerialization.jsonObject(with: XCTUnwrap(files["audit.json"])) as! [String:Any]
        XCTAssertEqual(audit["integrity_passed"] as? Bool,false)
        XCTAssertTrue(artifact.report.issues.contains { $0.code == "unfinished_attempt" })
    }
    func testMissingRawMismatchDuplicateAndCorruptRecordsRemainVisibleAndOriginalBytesAreRetained() throws {
        let base = try root(); let (a,c,t) = try setup(base)
        var r = try record(a,c,t)
        r = try a.updateMeasurement(id: r.id) { $0.rawStatus = .saved; $0.rawBundleId = r.id }
        let raw = base.appendingPathComponent("raw"),bundle = raw.appendingPathComponent(r.id)
        try FileManager.default.createDirectory(at: bundle,withIntermediateDirectories: true)
        let manifest = try JSONSerialization.data(withJSONObject: ["measurement_id": UUID().uuidString,"kind":"height","dbh":["rgb_file":"missing.jpg"]])
        try manifest.write(to: bundle.appendingPathComponent("manifest.json"))
        let corrupt = a.root.appendingPathComponent("measurements/"+UUID().uuidString+".json"); try Data("broken-original".utf8).write(to: corrupt)
        let duplicate = a.root.appendingPathComponent("measurements/duplicate/"+r.id+".json")
        try FileManager.default.createDirectory(at: duplicate.deletingLastPathComponent(),withIntermediateDirectories: true)
        try Data(contentsOf: a.root.appendingPathComponent("measurements/"+r.id+".json")).write(to: duplicate)
        let output = try FieldStudyExport.build(archive: a,rawRoot: raw,results: [],outputDirectory: base.appendingPathComponent("exports"))
        let codes = Set(output.report.issues.map(\.code))
        XCTAssertTrue(codes.isSuperset(of: ["corrupt_metadata","duplicate_measurement_id","raw_identity_mismatch","raw_file_missing","measurement_raw_binding_mismatch","application_result_missing_or_duplicate"]))
        XCTAssertFalse(output.report.integrityPassed)
        let files = try payloads(output.url)
        XCTAssertEqual(files["raw-captures/"+r.id+"/manifest.json"],manifest)
        XCTAssertEqual(files["field-study/measurements/"+corrupt.lastPathComponent],Data("broken-original".utf8))
        XCTAssertEqual(try Data(contentsOf: corrupt),Data("broken-original".utf8))
    }
    func testChecksumsAndLegacyBundleAreAuditedWithoutValueTimeOrTreeNumberFallback() throws {
        let base = try root(); let (a,c,t) = try setup(base); let r = try record(a,c,t)
        let raw = base.appendingPathComponent("raw"),bundle = raw.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: bundle,withIntermediateDirectories: true)
        let bytes = Data([1,2,3]); try bytes.write(to: bundle.appendingPathComponent("input.bin"))
        let manifest = try JSONSerialization.data(withJSONObject: ["kind":"dbh","result_live":["value":25],"capture_evidence":["measurement_id":r.id,
            "frames":[["depth":["file":"input.bin","bytes":3,"sha256":String(repeating:"0",count:64)]]]]])
        try manifest.write(to: bundle.appendingPathComponent("manifest.json"))
        let result = FieldExportApplicationResult(id: UUID().uuidString,store: "quick_history",kind: .dbh,value: 25)
        let output = try FieldStudyExport.build(archive: a,rawRoot: raw,results: [result],outputDirectory: base.appendingPathComponent("exports"))
        let codes = Set(output.report.issues.map(\.code))
        XCTAssertTrue(codes.isSuperset(of: ["legacy_raw_identity_unrecorded","raw_evidence_identity_mismatch","raw_without_measurement","raw_file_checksum_mismatch","application_result_missing_or_duplicate"]))
    }
    func testSourceChangeAndSymlinkRefuseExportAndRemoveOnlyOwnedOutput() throws {
        let base = try root(); let (a,_,_) = try setup(base)
        let raw = base.appendingPathComponent("raw"),bundle = raw.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: bundle,withIntermediateDirectories: true)
        let file = bundle.appendingPathComponent("payload.bin"); try Data([1,2,3]).write(to: file)
        let exports = base.appendingPathComponent("exports")
        XCTAssertThrowsError(try FieldStudyExport.build(archive: a,rawRoot: raw,results: [],outputDirectory: exports) { _ in try Data([4,5]).write(to: file) })
        XCTAssertEqual(try Data(contentsOf: file),Data([4,5])); XCTAssertTrue(try FileManager.default.contentsOfDirectory(atPath: exports.path).isEmpty)
        try FileManager.default.createSymbolicLink(at: bundle.appendingPathComponent("outside"),withDestinationURL: base)
        XCTAssertThrowsError(try FieldStudyExport.build(archive: a,rawRoot: raw,results: [],outputDirectory: exports))
        XCTAssertTrue(try FileManager.default.contentsOfDirectory(atPath: exports.path).isEmpty)
        XCTAssertThrowsError(try FieldStudyExport.build(archive: a,rawRoot: raw,results: [],outputDirectory: raw))
    }
    func testExactLinkedEvidenceOriginalReferenceScopeAndUIFlowAreExportedWithoutReplaying() throws {
        let base = try root(); let (a,c,t) = try setup(base)
        let gt = FieldReferenceObservation(targetId: t.id,method: c.referenceMethods[0],inputValue: 25,operatorId: "OP-1")
        try a.appendReference(gt,developerMode: true)
        let scope = try FieldMeasurementScope(configuration: c,target: t,references: [gt])
        var r = FieldMeasurementRecord(sessionId: UUID().uuidString,treeId: t.treeId,targetId: t.id,targetSnapshot: t,kind: .dbh,attemptNumber: 1,configuration: c,runtimeSettings: [:])
        r.outcome = .result; r.selection = .accepted; r.liveValue = 25; r.liveSigma = 1
        r.resultRecordId = r.id; r.resultStore = "quick_history"; r.referenceIds = [gt.id]; r.measurementScope = scope
        r.rawStatus = .saved; r.rawBundleId = r.id
        let duration: (Double) -> FieldUIFlowDuration = { .init(elapsedSeconds: $0,foregroundSeconds: $0,inactiveSeconds: 0,clockValid: true) }
        r.uiFlowTiming = .init(id: UUID().uuidString,kind: .dbh,openedAt: "2026-01-01T00:00:00.000Z",current: duration(6),atResult: duration(3),atAccepted: duration(6))
        try a.createMeasurement(r)
        try a.appendReference(.init(targetId: t.id,method: gt.method,inputValue: 30,operatorId: "OP-2",supersedesId: gt.id),developerMode: true)
        let raw = base.appendingPathComponent("raw"),bundle = raw.appendingPathComponent(r.id)
        try FileManager.default.createDirectory(at: bundle,withIntermediateDirectories: true)
        let depth = Data([0,0,128,63]),confidence = Data([2])
        try depth.write(to: bundle.appendingPathComponent("depth.bin")); try confidence.write(to: bundle.appendingPathComponent("confidence.bin"))
        func proof(_ name: String,_ bytes: Data) -> [String:Any] { ["file":name,"bytes":bytes.count,"sha256":SHA256.hash(data: bytes).map { String(format:"%02x",$0) }.joined()] }
        let manifest = try JSONSerialization.data(withJSONObject: ["schema":2,"kind":"dbh","measurement_id":r.id,"context":[:],"settings":[:],"result_live":["value":25],
            "dbh":["frames":[["depth_file":"depth.bin","width":1,"height":1,"format":"f32m"]]],
            "capture_evidence":["measurement_id":r.id,"displayed_result":["value":25],"frames":[["depth":proof("depth.bin",depth),"confidence":proof("confidence.bin",confidence)]]]])
        try manifest.write(to: bundle.appendingPathComponent("manifest.json"))
        let output = try FieldStudyExport.build(archive: a,rawRoot: raw,results: [.init(id: r.id,store: "quick_history",kind: .dbh,value: 25,measurementId: r.id)],outputDirectory: base.appendingPathComponent("exports"))
        XCTAssertTrue(output.report.integrityPassed,"\(output.report.issues)")
        let files = try payloads(output.url)
        let exported = try FieldStudyJSON.decoder().decode(FieldMeasurementRecord.self,from: XCTUnwrap(files["field-study/measurements/"+r.id+".json"]))
        XCTAssertEqual(exported,r); XCTAssertEqual(exported.measurementScope?.references.first?.inputValue,25)
        let csv = String(decoding: try XCTUnwrap(files["measurement_index.csv"]),as: UTF8.self)
        XCTAssertTrue(csv.contains(r.uiFlowTiming!.id)); XCTAssertTrue(csv.contains("\"3.0\",\"6.0\"")); XCTAssertTrue(csv.contains("\"mm\""))
        if let outputPath = ProcessInfo.processInfo.environment["FORESTIX_FIELD_EXPORT_FIXTURE_OUT"] { try FileManager.default.copyItem(at: output.url,to: URL(fileURLWithPath: outputPath)) }
    }
    func testWrongTreeMissingReferenceAndRuntimeMismatchAreNotHiddenBySimilarValues() throws {
        let base = try root(); let (a,initial,t) = try setup(base)
        var c = initial; c.protocolLocked = true; c.pinnedRuntimeSettings = ["procedure":"p1"]; try a.saveConfiguration(c,developerMode: true)
        let other = FieldStudyTarget(studyId: c.studyId,treeId: "0008",label: "008"); try a.saveTargets([t,other])
        let gt = FieldReferenceObservation(targetId: other.id,method: c.referenceMethods[0],inputValue: 25,operatorId: "OP-1"); try a.appendReference(gt,developerMode: true)
        var r = FieldMeasurementRecord(sessionId: UUID().uuidString,treeId: t.treeId,targetId: t.id,targetSnapshot: t,kind: .dbh,attemptNumber: 1,configuration: c,runtimeSettings: ["procedure":"p2"])
        r.outcome = .result; r.selection = .accepted; r.liveValue = 25; r.resultRecordId = r.id; r.resultStore = "quick_history"
        r.referenceIds = [gt.id,UUID().uuidString]; try a.createMeasurement(r)
        let output = try FieldStudyExport.build(archive: a,rawRoot: base.appendingPathComponent("raw"),results: [.init(id:r.id,store:"quick_history",kind:.dbh,value:25,measurementId:r.id)],outputDirectory: base.appendingPathComponent("exports"))
        XCTAssertTrue(Set(output.report.issues.map(\.code)).isSuperset(of: ["reference_binding_mismatch","reference_missing_or_duplicate","protocol_runtime_mismatch"]))
        XCTAssertEqual(try a.measurement(id: r.id),r)
    }
    func testChangingApplicationSourceAndReservedPathsRefusePartialArchive() throws {
        let base = try root(); let (a,_,_) = try setup(base)
        let journal = base.appendingPathComponent("journal.csv"); try Data([1,2,3]).write(to: journal)
        let output = base.appendingPathComponent("exports")
        XCTAssertThrowsError(try FieldStudyExport.build(archive: a,rawRoot: base.appendingPathComponent("raw"),results: [],outputDirectory: output,applicationFiles: [("original.csv",journal)]) { _ in try Data([8,9]).write(to: journal) })
        XCTAssertEqual(try Data(contentsOf: journal),Data([8,9])); XCTAssertTrue(try FileManager.default.contentsOfDirectory(atPath: output.path).isEmpty)
        XCTAssertThrowsError(try FieldStudyExport.build(archive: a,rawRoot: base.appendingPathComponent("raw"),results: [],applicationPayloads: [("result_index.json",Data())],outputDirectory: output))
        XCTAssertTrue(try FileManager.default.contentsOfDirectory(atPath: output.path).isEmpty)
    }
}
