import XCTest
import simd
import Models
import Sensors
import Common
#if canImport(UI)
@testable import UI
#else
@testable import CaptureRuntime
#endif

/// Actual recorder, result sidecar and CSV; explicitly synthetic inputs.
/// This does not replace camera/AR or physical-device verification.
final class FieldRecordingMatrixTests: XCTestCase {
    func testCompleteExportPreservesAnEntireCorruptOnlyCorpusAndReportsItsAudit() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("forestix-corrupt-export-"+UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let id = UUID().uuidString,raw = root.appendingPathComponent("raw"),bundle = raw.appendingPathComponent(id)
        try FileManager.default.createDirectory(at: bundle,withIntermediateDirectories: true)
        let manifest = Data("{truncated synthetic manifest".utf8),payload = Data([1,2,3,4,5])
        try manifest.write(to: bundle.appendingPathComponent("manifest.json")); try payload.write(to: bundle.appendingPathComponent("depth_0.bin"))
        XCTAssertTrue(RawCaptureExportAvailability.canExportRaw(.init(directories: 1,parsed: 0),isExporting: false))
        let output = try FieldStudyExport.build(archive: .init(root: root.appendingPathComponent("field")),rawRoot: raw,results: [],outputDirectory: root.appendingPathComponent("exports"))
        XCTAssertEqual(output.report.rawBundleCount,1); XCTAssertFalse(output.report.integrityPassed)
        XCTAssertTrue(output.report.issues.contains { $0.code == "raw_manifest_corrupt" })
        let bytes = try Data(contentsOf: output.url)
        let files = try Dictionary(uniqueKeysWithValues: Common.ZipReader.entries(in: bytes).map { ($0.name,try Common.ZipReader.extract($0,from: bytes)) })
        XCTAssertEqual(files["raw-captures/"+id+"/manifest.json"],manifest)
        XCTAssertEqual(files["raw-captures/"+id+"/depth_0.bin"],payload)
        XCTAssertEqual(try Data(contentsOf: bundle.appendingPathComponent("manifest.json")),manifest)
        XCTAssertEqual(try Data(contentsOf: bundle.appendingPathComponent("depth_0.bin")),payload)
    }
    @MainActor private func scenario(kind: FieldStudyKind,study: Bool,raw: Bool,timing: Bool) async throws {
        let id = UUID(),root = FileManager.default.temporaryDirectory.appendingPathComponent("forestix-recording-matrix-"+UUID().uuidString)
        let rawDirectory = RawCaptureStore.bundleDirectory(id: id.uuidString)
        let suite = "forestix-recording-matrix-"+UUID().uuidString,defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer {
            defaults.removePersistentDomain(forName: suite)
            try? FileManager.default.removeItem(at: root)
            try? FileManager.default.removeItem(at: rawDirectory) // This test's fresh, exact UUID only.
        }
        let store = FieldStudyStore(root: root.appendingPathComponent("field"))
        for _ in 0..<1000 { if store.loaded { break }; try await Task.sleep(for: .milliseconds(2)) }
        XCTAssertTrue(store.loaded)
        var c = FieldStudyConfiguration(); c.enabled = study; c.studyId = "SYNTHETIC"; c.operatorId = "TEST"
        c.plannedRepetitions = 3; c.plannedRepeat = 2; c.recordTiming = timing
        try await store.saveConfiguration(c,developerMode: true)
        let target = FieldStudyTarget(studyId: c.studyId,treeId: "0003",label: "3")
        try await store.saveTargets([target]); try store.selectTarget(target.id)
        var now = 0.0; let registry = FieldUIFlowRegistry(); registry.owner("app",active: true,now: now)
        let capture = FieldCaptureCoordinator(clock: { now },uiFlowRegistry: registry)
        capture.configure(store: store,kind: kind,settings: .ephemeral(),calibration: .identity,
            context: ["application.tree_number":"3","application.mode":"quick"])
        capture.appeared(); now = 2
        capture.handle(.started(id: id.uuidString,rawRequested: raw,aiEnabled: false,typed: false))
        let frame = ARDepthFrame(width: 128,height: 96,depth: .init(repeating: 1.25,count: 128*96),
            confidence: .init(repeating: 2,count: 128*96),intrinsics: RawCaptureMatrix.intrinsics(fx: 100,fy: 100,cx: 64,cy: 48),
            cameraPoseWorld: matrix_identity_float4x4,timestamp: 1,viewMapping: .init(a: 1,b: 0,tx: 0,c: 0,d: 1,ty: 0))
        let value: Double
        if kind == .dbh {
            value = Double(try XCTUnwrap(DBHEstimator.bracketChordEstimate(frames: [frame],guideAxis: .row(y: 48),leftFraction: 0.25,rightFraction: 0.75,calibration: .identity)).diameterCm)
        } else {
            value = Double(HeightEstimator.estimate(input: .init(anchorPointWorld: .zero,standingPointWorld: SIMD3(10,1.5,0),
                alphaTopRad: 0.6,alphaBaseRad: -0.1,trackingStateWasNormalThroughout: true,projectCalibration: .identity)).heightM)
        }
        XCTAssertGreaterThan(value,0); now = 4
        capture.handle(.result(id: id.uuidString,value: value,sigma: nil,valid: true,reason: nil,ai: .init()))
        if raw {
            let evidence = RawCaptureEvidence(measurementId: id.uuidString,resultSeconds: 4,
                displayedResult: .init(value: value,sigma: nil,tier: "yellow",method: "synthetic",valid: true))
            let outcome: RawCaptureOutcome
            if kind == .dbh {
                outcome = RawCaptureRecorder.recordDBH(id: id.uuidString,frames: [frame],tapPixel: SIMD2(64,48),calibration: .identity,
                    algorithm: .chord,bracket: .init(enabled: true,left: 0.25,right: 0.75),guideAxis: .row(y: 48),captureManual: true,
                    context: .quick,referenceJPEG: nil,gps: nil,captureEvidence: evidence)
            } else {
                outcome = RawCaptureRecorder.recordHeight(id: id.uuidString,anchorWorld: .zero,anchorHitType: "synthetic",anchorDistanceM: 10,
                    anchorPose: matrix_identity_float4x4,basePitchRad: -0.1,baseStanding: SIMD3(10,1.5,0),baseRotationPose: matrix_identity_float4x4,
                    topPitchRad: 0.6,topPose: matrix_identity_float4x4,dHM: 10,poseSamples: [],trackingDropped: false,calibration: .identity,
                    context: .quick,gps: nil,baseFrame: frame,topFrame: frame,captureEvidence: evidence)
            }
            guard case .saved = outcome else { return XCTFail("Synthetic raw recording failed: \(outcome)") }
            let manifest = try XCTUnwrap(RawCaptureStore.loadManifest(id: id.uuidString)),captured = try XCTUnwrap(manifest.captureEvidence)
            XCTAssertEqual(manifest.measurementId,id.uuidString); XCTAssertEqual(captured.measurementId,id.uuidString)
            XCTAssertEqual(captured.displayedResult.value,value); XCTAssertEqual(captured.replayValue,value)
            XCTAssertEqual(captured.replayStatus,"pass"); XCTAssertFalse(captured.ai.inferenceRan)
            capture.handle(.raw(id: id.uuidString,status: .saved,reason: nil,replayValue: captured.replayValue))
        } else { XCTAssertFalse(FileManager.default.fileExists(atPath: rawDirectory.appendingPathComponent("manifest.json").path)) }
        let journal = root.appendingPathComponent("quick.jsonl"),history = QuickMeasureHistory(defaults: defaults,sidecarURL: journal)
        let entry = QuickMeasureEntry(id: id,kind: kind == .dbh ? .dbh : .height,value: value,sigma: nil,confidenceRaw: "yellow",method: "synthetic",treeNumber: 3)
        let receipt = capture.receipt(id: id.uuidString)
        try receipt.prepare(recordId: id,store: "quick_history",value: value)
        try history.saveCapturedReading(entry); now = 7; receipt.confirm(recordId: id,store: "quick_history")
        await store.awaitPendingWrites(); XCTAssertNil(capture.error)
        let reopened = QuickMeasureHistory(defaults: defaults,sidecarURL: journal)
        let saved = try XCTUnwrap(reopened.entries.first)
        XCTAssertEqual(saved.id,id); XCTAssertEqual(saved.measurementId,id.uuidString); XCTAssertEqual(saved.value,value)
        let csv = try String(contentsOf: XCTUnwrap(reopened.exportCSV(directory: root.appendingPathComponent("exports"))),encoding: .utf8)
        XCTAssertTrue(csv.contains("\"measurement_id\"")); XCTAssertTrue(csv.contains("\""+id.uuidString+"\""))
        if study {
            let record = try store.archive.measurement(id: id.uuidString)
            XCTAssertEqual(record.id,saved.measurementId); XCTAssertEqual(record.resultRecordId,id.uuidString)
            XCTAssertEqual(record.kind,kind); XCTAssertEqual(record.treeId,"0003"); XCTAssertEqual(record.operatorId,"TEST"); XCTAssertEqual(record.plannedRepeat,2)
            XCTAssertEqual(record.selection,.accepted); XCTAssertEqual(record.liveValue,value)
            XCTAssertEqual(record.rawStatus,raw ? .saved : .notRequested)
            if timing { XCTAssertEqual(record.uiFlowTiming?.atResult?.elapsedSeconds,4); XCTAssertEqual(record.uiFlowTiming?.atAccepted?.elapsedSeconds,7) }
            else { XCTAssertNil(record.timing); XCTAssertNil(record.uiFlowTiming) }
        } else {
            XCTAssertNil(receipt.measurementId)
            XCTAssertTrue(store.archive.scanMeasurements().values.isEmpty); XCTAssertTrue(store.archive.scanResultSaves().values.isEmpty)
        }
    }
    @MainActor func testRawOnlyKeepsResultCSVAndRawUUIDWithoutInventingStudyObservations() async throws {
        for kind in [FieldStudyKind.dbh,.height] { try await scenario(kind: kind,study: false,raw: true,timing: true) }
    }
    @MainActor func testStudyRawAndTimingSwitchesRemainIndependentForBothKinds() async throws {
        for kind in [FieldStudyKind.dbh,.height] { for raw in [false,true] { for timing in [false,true] {
            try await scenario(kind: kind,study: true,raw: raw,timing: timing)
        } } }
    }
}
