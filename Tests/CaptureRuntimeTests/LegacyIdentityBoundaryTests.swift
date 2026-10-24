import XCTest
import Models
import Common
@testable import Sensors
#if canImport(UI)
@testable import UI
#else
@testable import CaptureRuntime
#endif

final class LegacyIdentityBoundaryTests: XCTestCase {
    private func entry(_ id: UUID = UUID(),truth: Double? = nil) -> QuickMeasureEntry {
        .init(id: id,kind: .dbh,value: 25,sigma: 1,confidenceRaw: "green",method: "silhouette",
              createdAt: Date(timeIntervalSince1970: 100),treeNumber: 7,truth: truth,truthSource: "capture")
    }
    private func summary(identified: Bool) -> RawCaptureSummary {
        let id = UUID().uuidString
        var m = RawCaptureRecorder.baseManifest(kind: "dbh",context: .quick,algorithm: "silhouette",captureMode: "manual",calibration: .identity,
            resultLive: .init(value: 25,sigma: 1,tier: "green",accepted: false,tierOk: true,frameCount: 1,perFrame: [25]),gps: nil)
        m.context.treeNumber = 7; m.truth = .init(value: 25,enteredAt: "SYNTHETIC")
        if identified { m.measurementId = id }
        return .init(id: id,manifest: m)
    }
    func testIdentityRoundTripAndAllEditingCopiesPreserveMarkerWithoutChangingMeasurement() throws {
        let old = entry(); XCTAssertTrue(old.allowsLegacyInference)
        let e = try old.identifyingMeasurement()
        let copies = [e.inPlot(UUID()),e.settingDetails(speciesCode: "DF",note: "test"),e.settingTruth(27),
                      e.settingCreatedAt(Date(timeIntervalSince1970: 200))]
        for c in copies {
            let decoded = try JSONDecoder().decode(QuickMeasureEntry.self,from: JSONEncoder().encode(c))
            XCTAssertEqual(decoded.measurementId,e.id.uuidString); XCTAssertFalse(decoded.allowsLegacyInference)
            XCTAssertEqual(decoded.value,25); XCTAssertEqual(decoded.sigma,1)
        }
        var object = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(old)) as? [String:Any])
        object["measurementId"] = "malformed"
        let malformed = try JSONDecoder().decode(QuickMeasureEntry.self,from: JSONSerialization.data(withJSONObject: object))
        XCTAssertFalse(malformed.allowsLegacyInference); XCTAssertThrowsError(try malformed.identifyingMeasurement())
    }
    func testLegacyTruthPlanningNeverUsesIdentifiedReadingsOrCaptures() throws {
        let old = entry(),modern = try old.identifyingMeasurement()
        let truth = TruthBackfill.ManifestTruth(captureID: UUID().uuidString,kind: .dbh,treeNumber: 7,value: 30,createdAt: old.createdAt)
        XCTAssertEqual(TruthBackfill.plan(manifests: [truth],entries: [old],defaultPlotID: nil,scanned: 1).attachments[old.id],30)
        XCTAssertTrue(TruthBackfill.plan(manifests: [truth],entries: [modern],defaultPlotID: nil,scanned: 1).attachments.isEmpty)
        let identified = TruthBackfill.ManifestTruth(captureID: UUID().uuidString,kind: .dbh,treeNumber: 7,value: 30,createdAt: old.createdAt,measurementId: UUID().uuidString)
        XCTAssertTrue(TruthBackfill.plan(manifests: [identified],entries: [old],defaultPlotID: nil,scanned: 1).attachments.isEmpty)
        XCTAssertTrue(TruthBackfill.recoverable(from: [summary(identified: true)]).isEmpty)
        let legacySummary = summary(identified: false)
        let p = TruthUnitRepair.plan(summaries: [legacySummary],entries: [try entry(truth: 25).identifyingMeasurement()],logRows: [],logKept: .init())
        XCTAssertTrue(p.readings.isEmpty); XCTAssertEqual(p.captures.count,1)
        XCTAssertTrue(TruthUnitRepair.plan(summaries: [summary(identified: true)],entries: [],logRows: [],logKept: .init()).captures.isEmpty)
        let identifiedAxis = CaptureReadingMatch.Capture(bundleID: UUID().uuidString,kind: .dbh,treeNumber: 7,plotID: nil,
            createdAt: old.createdAt,value: 18.75,oppositeAxisValue: 25,storedValue: 18.75,sigma: 1,measurementId: UUID().uuidString)
        XCTAssertTrue(CaptureReadingMatch.repairs(captures: [identifiedAxis],entries: [old]).isEmpty)
    }
    func testIdentifiedExportUsesExactUUIDAndNeverRebasesTruthOrFallsBackOnValueTime() throws {
        let id = UUID(),other = UUID()
        let header = ["measurement_id","measure_type","tree_id","measured_value","true_value","error","truth_unit","timestamp_iso"]
        let row = [id.uuidString,"dbh","7","25.00","30.00","-5.00","cm","1970-01-01T00:01:40Z"]
        let exact = ResearchExport.Reading(kind: "dbh",treeNumber: 99,value: 25,createdAt: .distantFuture,truth: 40,truthUnit: "cm",id: id,measurementId: id.uuidString)
        let c = try XCTUnwrap(ResearchExport.classify(records: [header,row],readings: [exact]))
        XCTAssertEqual(c.kept.single?.dropLast().map { $0 },row); XCTAssertEqual(c.counts.live,1); XCTAssertTrue(c.heldBack.isEmpty)
        let rival = ResearchExport.Reading(kind: "dbh",treeNumber: 7,value: 25,createdAt: Date(timeIntervalSince1970: 100),truth: 30,truthUnit: "cm",id: other)
        let missing = try XCTUnwrap(ResearchExport.classify(records: [header,row],readings: [rival]))
        XCTAssertEqual(missing.counts.unclassified,1); XCTAssertTrue(missing.heldBack.isEmpty)
        let duplicates = try XCTUnwrap(ResearchExport.classify(records: [header,row,row],readings: [exact]))
        XCTAssertEqual(duplicates.counts.unclassified,2); XCTAssertEqual(duplicates.kept.count,2)
        var invalid = row; invalid[0] = "not-a-uuid"
        XCTAssertEqual(ResearchExport.classify(records: [header,invalid],readings: [rival])?.counts.unclassified,1)
        var legacy = row; legacy[0] = ""
        XCTAssertEqual(ResearchExport.classify(records: [header,legacy],readings: [exact])?.counts.unclassified,1)
        XCTAssertEqual(ResearchExport.classify(records: [header,legacy],readings: [rival])?.counts.live,1)
    }
    func testLogUnitRepairLeavesIdentifiedRowsByteForByteAndRepairsLegacyOnly() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("forestix-legacy-log-"+UUID().uuidString)
        try FileManager.default.createDirectory(at: root,withIntermediateDirectories: true); defer { try? FileManager.default.removeItem(at: root) }
        let file = root.appendingPathComponent("synthetic.csv"),header = ResearchLog.columns
        func row(id: String) -> [String] { header.map { ["measurement_id":id,"measure_type":"dbh","tree_id":"7","true_value":"25","measured_value":"30","error":"5"][$0] ?? "" } }
        let modern = row(id: UUID().uuidString),malformed = row(id: "invalid")
        let data = ([header,modern,malformed,row(id: "")].map { $0.map(ResearchLog.csvEscape).joined(separator: ",") }.joined(separator: "\n")+"\n")
        try data.write(to: file,atomically: true,encoding: .utf8)
        let log = ResearchLog(fileURL: file); XCTAssertEqual(log.repairImperialTruths(apply: false).rows.count,1)
        XCTAssertEqual(try String(contentsOf: file),data)
        XCTAssertEqual(log.repairImperialTruths(apply: true).written,1)
        let rows = try XCTUnwrap(log.snapshotRecords()); XCTAssertEqual(rows[1],modern); XCTAssertEqual(rows[2],malformed)
        XCTAssertEqual(log.repairImperialTruths(apply: true).written,0)
    }
    @MainActor func testActualHistoryWriteBoundaryStampsIdentityAndRefusesLegacyMutations() throws {
        let history = QuickMeasureHistory.ephemeral(),e = entry()
        try history.saveCapturedReading(e)
        let saved = try XCTUnwrap(history.entries.first)
        XCTAssertEqual(saved.measurementId,e.id.uuidString); XCTAssertEqual(saved.value,e.value)
        XCTAssertEqual(history.backfillTruths([e.id: 30]),0)
        history.update(saved.settingTruth(25,source: "capture"))
        XCTAssertEqual(history.repairTruthUnits([e.id:(before: 25,after: 63.5,unit: "in")]),0)
        XCTAssertEqual(history.repairValuesFromCaptures([e.id:(expected: 25,corrected: 20,sigma: 1)]),0)
        XCTAssertEqual(history.entries.first?.truth,25); XCTAssertEqual(history.entries.first?.value,25)
        let exportRoot = FileManager.default.temporaryDirectory.appendingPathComponent("forestix-identity-export-"+UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: exportRoot) }
        let url = try XCTUnwrap(history.exportCSV(directory: exportRoot))
        let rows = ResearchLog.parseRecords(try String(contentsOf: url).replacingOccurrences(of: "\u{FEFF}",with: ""))
        let idx = try XCTUnwrap(rows.first?.firstIndex(of: "measurement_id")); XCTAssertEqual(rows[1][idx],e.id.uuidString)
        let height = QuickMeasureEntry.typed(kind: .height,value: 20,treeNumber: 7,plotID: nil)
        XCTAssertEqual(height.measurementId,height.id.uuidString)
        try history.saveCapturedReading(height)
        let bundle = try XCTUnwrap(history.exportBundle(directory: exportRoot))
        let files = try ZipReader.readAll(Data(contentsOf: bundle))
        for (name,id) in [("Stems.csv",e.id),("Heights.csv",height.id)] {
            let bytes = try XCTUnwrap(files.first { $0.name == name }?.data)
            let cells = ResearchLog.parseRecords(String(decoding: bytes,as: UTF8.self).replacingOccurrences(of: "\u{FEFF}",with: ""))
            let col = try XCTUnwrap(cells.first?.firstIndex(of: "measurement_id")); XCTAssertEqual(cells[1][col],id.uuidString)
        }
    }
}

private extension Array { var single: Element? { count == 1 ? first : nil } }
