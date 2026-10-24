import XCTest
import Foundation
import Models
#if canImport(UI)
@testable import UI
#else
@testable import CaptureRuntime
#endif

final class QuickHistoryRetentionTests: XCTestCase {
    private func fixture() throws -> (URL,UserDefaults,URL) {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("forestix-retain-"+UUID().uuidString)
        try FileManager.default.createDirectory(at: root,withIntermediateDirectories: true)
        let suite = "forestix-retain-"+UUID().uuidString,ud = try XCTUnwrap(UserDefaults(suiteName: suite))
        addTeardownBlock { try? FileManager.default.removeItem(at: root); ud.removePersistentDomain(forName: suite) }
        return (root,ud,root.appendingPathComponent("quick.jsonl"))
    }
    private func reading(_ number: Int,id: UUID = UUID()) throws -> QuickMeasureEntry {
        try QuickMeasureEntry(id: id,kind: .dbh,value: 25+Double(number)/1000,sigma: 1,confidenceRaw: "green",method: "manualVisual",
            treeNumber: number,treeName: "00\(number)",plotID: UUID(uuidString: "00000000-0000-4000-8000-000000000007"),note: "synthetic,\n🌲",truth: 26.5).identifyingMeasurement()
    }
    @MainActor func test1001CapturedRowsSurviveSmallCacheThresholdReopenAndReplacementRewrite() throws {
        let (_,ud,url) = try fixture(); let h = QuickMeasureHistory(defaults: ud,capacity: 3,sidecarURL: url)
        var originals: [QuickMeasureEntry] = []
        for i in 1...1001 { let row = try reading(i); originals.append(row); try h.saveCapturedReading(row) }
        XCTAssertEqual(h.entries.count,1001); XCTAssertTrue(h.isNearCapacity)
        XCTAssertEqual(try FieldQuickResultProofReader.entries(url: url).count,1001)
        let cache = try JSONDecoder().decode([QuickMeasureEntry].self,from: XCTUnwrap(ud.data(forKey: QuickMeasureHistory.Keys.entries)))
        XCTAssertEqual(cache.count,3)
        let reopened = QuickMeasureHistory(defaults: ud,capacity: 3,sidecarURL: url)
        XCTAssertEqual(Set(reopened.entries.map(\.id)),Set(originals.map(\.id)))
        let replacement = try reading(1)
        let plan = try reopened.captureReplacementSnapshots(replacement,replacing: true)
        XCTAssertEqual(plan.count,1)
        let old = try JSONDecoder().decode(QuickMeasureEntry.self,from: Data(plan[0].payloadJSON.utf8))
        XCTAssertEqual(old,originals[0]); XCTAssertEqual(old.truth,26.5)
        XCTAssertEqual(try reopened.saveCapturedReading(replacement,replacing: true,expectedReplacements: plan),[originals[0].id])
        let all = try FieldQuickResultProofReader.entries(url: url)
        XCTAssertEqual(all.count,1001)
        for original in originals.dropFirst() { XCTAssertEqual(all.first { $0.id == original.id },original) }
    }
    @MainActor func testLegacyAppendAndReplaceAlsoRetainUnrelatedRowsBeyondThreshold() throws {
        let (_,ud,url) = try fixture(); let h = QuickMeasureHistory(defaults: ud,capacity: 2,sidecarURL: url)
        let rows = try (1...8).map { try reading($0) }; rows.forEach(h.append)
        XCTAssertEqual(h.entries.count,8)
        h.replaceReading(try reading(1))
        XCTAssertEqual(h.entries.count,8)
        let reopened = QuickMeasureHistory(defaults: ud,capacity: 2,sidecarURL: url)
        for old in rows.dropFirst() { XCTAssertEqual(reopened.entries.first { $0.id == old.id },old) }
    }
    @MainActor func testReplacementCASRejectsIndependentMetadataEditWithoutChangingJournal() throws {
        let (_,ud,url) = try fixture(); let h = QuickMeasureHistory(defaults: ud,capacity: 2,sidecarURL: url)
        let old = try reading(7),new = try reading(7); try h.saveCapturedReading(old)
        let plan = try h.captureReplacementSnapshots(new,replacing: true)
        var fields = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(old)) as? [String:Any]); fields["note"] = "independent edit"
        let edited = try JSONDecoder().decode(QuickMeasureEntry.self,from: JSONSerialization.data(withJSONObject: fields)); h.update(edited)
        let bytes = try Data(contentsOf: url)
        XCTAssertThrowsError(try h.saveCapturedReading(new,replacing: true,expectedReplacements: plan))
        XCTAssertEqual(try Data(contentsOf: url),bytes); XCTAssertEqual(h.entries,[edited])
    }
    @MainActor func testStartupRecoversThreeTornReplacementsWithoutTimeOrValueMatching() async throws {
        let (root,ud,url) = try fixture(),h = QuickMeasureHistory(defaults: ud,capacity: 2,sidecarURL: url)
        let field = root.appendingPathComponent("field"),a = FieldStudyArchive(root: field)
        var c = FieldStudyConfiguration(); c.enabled = true; c.studyId = "SYNTHETIC"; c.operatorId = "TEST"
        try a.saveConfiguration(c,developerMode: true)
        var ids: [String] = []
        for i in 0..<3 {
            let id = UUID(),entry = try reading(7,id: id),plan = try h.captureReplacementSnapshots(entry,replacing: i>0)
            var session = FieldMeasurementSession(now: 0)
            try session.begin(id: id.uuidString,configuration: c,target: nil,kind: .dbh,
                runtime: ["application.mode":"quick","application.plot_id":"SYNTHETIC-PLOT","application.tree_number":"7"],references: [],rawRequested: false,aiEnabled: false,typed: true,now: 1)
            try a.createMeasurement(XCTUnwrap(session.current))
            try session.finish(id: id.uuidString,value: entry.value,sigma: entry.sigma,valid: true,reason: nil,ai: .init(),now: 3)
            try a.prepareResultSave(.init(snapshot: XCTUnwrap(session.current),expected: .init(id: id.uuidString,store: "quick_history",kind: .dbh,value: entry.value,measurementId: id.uuidString),replacements: plan))
            try h.saveCapturedReading(entry,replacing: i>0,expectedReplacements: plan)
            ids.append(id.uuidString)
        }
        let store = FieldStudyStore(root: field)
        store.attachResultSaveReader { intent in try FieldQuickResultProofReader.read(url: url,recordIds: [intent.expected.id]+intent.replacementSnapshots.map { $0.result.id }) }
        for _ in 0..<2000 { if store.loaded { break }; try await Task.sleep(for: .milliseconds(2)) }
        XCTAssertTrue(store.issues.isEmpty,String(describing: store.issues))
        XCTAssertTrue(store.archive.scanResultSaves().values.allSatisfy(\.completed))
        for id in ids.dropLast() { XCTAssertEqual(try store.archive.measurement(id: id).selection,.superseded) }
        let latest = try store.archive.measurement(id: XCTUnwrap(ids.last))
        XCTAssertEqual(latest.selection,.accepted); XCTAssertNil(latest.timing?.acceptedElapsedSeconds)
        XCTAssertEqual(h.entries.count,1)
        let parents = store.archive.scanResultSaves().values.flatMap(\.replacementSnapshots)
        XCTAssertEqual(parents.count,2); XCTAssertTrue(parents.allSatisfy { $0.payloadJSON.contains("synthetic") && $0.payloadJSON.contains("26.5") })
    }
}
