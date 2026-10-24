import XCTest
import Foundation
import Models

final class FieldRuntimeSafetyTests: XCTestCase {
    func testRetentionOnlyDeletesOldRecognisedZIPsAndPreservesSharingGraceSourcesAndLinks() throws {
        let fm = FileManager.default,root = fm.temporaryDirectory.appendingPathComponent("forestix-cache-test-"+UUID().uuidString)
        try fm.createDirectory(at: root,withIntermediateDirectories: true)
        defer { try? fm.removeItem(at: root) }
        let now = Date(),sentinel = root.appendingPathComponent("manifest.json")
        try Data("synthetic evidence".utf8).write(to: sentinel)
        var zips: [URL] = []
        for age in [60.0,7200,8000,9000] {
            let zip = root.appendingPathComponent("forestix-field-study-"+UUID().uuidString+".zip")
            try Data([1,2,3]).write(to: zip)
            try fm.setAttributes([.modificationDate: now.addingTimeInterval(-age)],ofItemAtPath: zip.path)
            zips.append(zip)
        }
        let unknown = root.appendingPathComponent("forestix-field-study-not-a-uuid.zip")
        try Data([7]).write(to: unknown)
        let link = root.appendingPathComponent("forestix-field-study-"+UUID().uuidString+".zip")
        try fm.createSymbolicLink(at: link,withDestinationURL: sentinel)
        let directory = root.appendingPathComponent("forestix-field-study-"+UUID().uuidString+".zip")
        try fm.createDirectory(at: directory,withIntermediateDirectories: true)
        try FieldExportRetention.prune(directory: root,now: now)
        XCTAssertTrue(fm.fileExists(atPath: zips[0].path)); XCTAssertTrue(fm.fileExists(atPath: zips[1].path))
        XCTAssertFalse(fm.fileExists(atPath: zips[2].path)); XCTAssertFalse(fm.fileExists(atPath: zips[3].path))
        for url in [sentinel,unknown,link,directory] { XCTAssertTrue(fm.fileExists(atPath: url.path)) }
        // A newly shared third export remains protected even with keepNewest=0.
        try FieldExportRetention.prune(directory: root,now: now,keepNewest: 0)
        XCTAssertTrue(fm.fileExists(atPath: zips[0].path))
        XCTAssertEqual(try String(contentsOf: sentinel,encoding: .utf8),"synthetic evidence")
    }
    func testLinearSnapshotMergePreservesConcurrentNewIDsAndNewerRevisionsOnly() {
        func record(_ id: String,_ revision: Int) -> FieldMeasurementRecord {
            var r = FieldMeasurementRecord(id: id,sessionId: UUID().uuidString,treeId: "SYNTHETIC",kind: .dbh,
                attemptNumber: 1,configuration: .init(),runtimeSettings: [:])
            r.revision = revision; return r
        }
        let old = record(UUID().uuidString,1),new = record(UUID().uuidString,0),removed = record(UUID().uuidString,0)
        var updated = old; updated.revision = 2
        let merged = FieldMeasurementSnapshot.merge(scanned: [old],current: [updated,new,removed],baselineIds: [old.id,removed.id])
        XCTAssertEqual(merged,[updated,new])
        let large = (0..<5000).map { _ in record(UUID().uuidString,1) }
        XCTAssertEqual(FieldMeasurementSnapshot.merge(scanned: large,current: large,baselineIds: Set(large.map(\.id))),large)
    }
}
