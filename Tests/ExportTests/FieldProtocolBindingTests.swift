import XCTest
import Models
import Common

final class FieldProtocolBindingTests: XCTestCase {
    private func config() -> FieldStudyConfiguration {
        var c = FieldStudyConfiguration();c.enabled = true;c.studyId = "SYNTHETIC";c.operatorId = "TEST"
        c.protocolLocked = true;c.pinnedRuntimeSettings = FieldProtocolBinding.policy;return c
    }
    private func runtime(_ mode: String = "sensor") -> [String:String] {
        ["platform":"ios","input_mode":mode,"app_version":"1.0","app_build":"17",
         "measurement_source":"arkit_scene_depth","dbh_estimator_epoch":"9","dbh_method":"chord",
         "dbh_chord_algorithm":"silhouette","capture_frame_count":"1","depth_sampling_fraction":FieldFingerprint.number(0.8),
         "depth_sampling_statistic":"median","depth_surface_correction":"cylinder_median_v1","model_sha256":String(repeating: "a",count: 64),
         "calibration.depth_noise_mm":"noise","calibration.vio_drift_fraction":"drift","calibration.alpha":"alpha",
         "calibration.beta":"beta","calibration.epoch":"0","calibration.depth_discontinuity_m":"discontinuity",
         "height_method":"vio_walkoff_tangent","height_procedure_version":"1","application.tree_number":"3"]
    }
    func testReopenNeverRepinsModelAlgorithmSamplingCalibrationOrBuild() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let c = config(),r = runtime(),a = FieldStudyArchive(root: root)
        let first = try FieldProtocolBinding(configuration: c,kind: .dbh,runtime: r)
        XCTAssertEqual(try a.bindProtocol(first),first)
        for key in ["model_sha256","dbh_method","dbh_chord_algorithm","capture_frame_count","depth_sampling_fraction","calibration.beta","app_build"] {
            var changed = r;changed[key] = key == "model_sha256" ? String(repeating: "b",count: 64) : "changed"
            let next = try FieldProtocolBinding(configuration: c,kind: .dbh,runtime: changed)
            XCTAssertEqual(next.id,first.id);XCTAssertThrowsError(try FieldStudyArchive(root: root).bindProtocol(next),key)
            XCTAssertEqual(try FieldStudyJSON.decoder().decode(FieldProtocolBinding.self,from: Data(contentsOf: root.appendingPathComponent("protocol_bindings/"+first.id+".json"))),first)
        }
    }
    func testMutableIdentityAndGpsDoNotChangeBaselineButVersionDoes() throws {
        var c = config(),r = runtime();let first = try FieldProtocolBinding(configuration: c,kind: .dbh,runtime: r)
        c.operatorId = "OTHER";c.plannedRepeat = 2;r["application.tree_number"] = "4";r["gps_accuracy"] = "9999"
        XCTAssertEqual(try FieldProtocolBinding(configuration: c,kind: .dbh,runtime: r),first)
        c.protocolVersion = "2";XCTAssertNotEqual(try FieldProtocolBinding(configuration: c,kind: .dbh,runtime: r).id,first.id)
    }
    func testProcedureFingerprintIsPlatformIndependentButSensitiveToSampling() throws {
        let c = config();var r = runtime();r["dbh_method"] = "partialArcCircleFit"
        let ios = try FieldProtocolBinding(configuration: c,kind: .dbh,runtime: r)
        r["platform"] = "android";r["measurement_source"] = "arcore_depth";r["dbh_method"] = "arc"
        r["model_sha256"] = String(repeating: "b",count: 64);r["calibration.beta"] = "other"
        let android = try FieldProtocolBinding(configuration: c,kind: .dbh,runtime: r)
        XCTAssertNotEqual(ios.id,android.id);XCTAssertEqual(ios.procedureFingerprint,android.procedureFingerprint)
        r["depth_sampling_fraction"] = FieldFingerprint.number(1)
        XCTAssertNotEqual(try FieldProtocolBinding(configuration: c,kind: .dbh,runtime: r).procedureFingerprint,ios.procedureFingerprint)
    }
    func testHeightAndTypedAreIndependentAndUnknownModelCannotPinSensors() throws {
        let c = config();var r = runtime()
        let height = try FieldProtocolBinding(configuration: c,kind: .height,runtime: r)
        r["model_sha256"] = "unavailable";r["dbh_method"] = "arc"
        XCTAssertTrue(height.matches(configuration: c,kind: .height,runtime: r))
        XCTAssertThrowsError(try FieldProtocolBinding(configuration: c,kind: .dbh,runtime: r))
        r["input_mode"] = "typed"
        let typed = try FieldProtocolBinding(configuration: c,kind: .dbh,runtime: r)
        XCTAssertEqual(typed.settings.count,4);XCTAssertNotEqual(typed.id,height.id)
        r["input_mode"] = "sensor";r["calibration.vio_drift_fraction"] = "new"
        XCTAssertFalse(height.matches(configuration: c,kind: .height,runtime: r))
    }
    func testLegacyLockNotRewrittenAndNewRecordCarriesVerifiableBaseline() throws {
        var c = config();c.pinnedRuntimeSettings = ["epoch":"9"]
        XCTAssertEqual(FieldProtocolBinding.preparing(c,from: c),c)
        var next = c;next.protocolVersion = "2"
        next = FieldProtocolBinding.preparing(next,from: c);XCTAssertEqual(next.pinnedRuntimeSettings,FieldProtocolBinding.policy)
        var r = FieldMeasurementRecord(sessionId: UUID().uuidString,treeId: "T",kind: .dbh,attemptNumber: 1,configuration: next,runtimeSettings: runtime())
        XCTAssertFalse(r.protocolConforms)
        r.protocolBinding = try FieldProtocolBinding(configuration: next,kind: .dbh,runtime: r.runtimeSettings)
        XCTAssertTrue(r.protocolConforms)
        let decoded = try FieldStudyJSON.decoder().decode(FieldMeasurementRecord.self,from: FieldStudyJSON.encoder().encode(r))
        XCTAssertEqual(decoded,r);XCTAssertTrue(decoded.protocolConforms)
        r.runtimeSettings["capture_frame_count"] = "5";XCTAssertFalse(r.protocolConforms)
    }
    func testCorruptBaselineIsNeverReplaced() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let b = try FieldProtocolBinding(configuration: config(),kind: .dbh,runtime: runtime()),archive = FieldStudyArchive(root: root)
        _ = try archive.bindProtocol(b)
        let file = root.appendingPathComponent("protocol_bindings/"+b.id+".json"),bytes = Data("partial".utf8)
        try bytes.write(to: file)
        XCTAssertThrowsError(try archive.bindProtocol(b));XCTAssertEqual(try Data(contentsOf: file),bytes)
    }
    func testGuideHeightLocksNewDbhButPreservesHistoricalAndIndependentBindings() throws {
        let c = config(),oldRuntime = runtime()
        let old = try FieldProtocolBinding(configuration: c,kind: .dbh,runtime: oldRuntime)
        let decoded = try FieldStudyJSON.decoder().decode(FieldProtocolBinding.self,from: FieldStudyJSON.encoder().encode(old))
        XCTAssertNoThrow(try decoded.validate());XCTAssertTrue(decoded.matches(configuration: c,kind: .dbh,runtime: oldRuntime))
        var current = oldRuntime;current["breast_height_guide_height_m"] = FieldFingerprint.number(1.37)
        let baseline = try FieldProtocolBinding(configuration: c,kind: .dbh,runtime: current)
        XCTAssertFalse(old.matches(configuration: c,kind: .dbh,runtime: current))
        XCTAssertNotEqual(old.procedureFingerprint,baseline.procedureFingerprint)
        XCTAssertEqual(baseline.settings["breast_height_guide_height_m"],FieldFingerprint.number(1.37))
        for meters in [1.30,1.40] {
            current["breast_height_guide_height_m"] = FieldFingerprint.number(meters)
            XCTAssertFalse(baseline.matches(configuration: c,kind: .dbh,runtime: current))
            XCTAssertNotEqual(baseline.procedureFingerprint,try FieldProtocolBinding(configuration: c,kind: .dbh,runtime: current).procedureFingerprint)
        }
        for bad in ["", "1.40", FieldFingerprint.number(0),FieldFingerprint.number(-1),FieldFingerprint.number(.infinity),FieldFingerprint.number(.nan)] {
            current["breast_height_guide_height_m"] = bad
            XCTAssertThrowsError(try FieldProtocolBinding(configuration: c,kind: .dbh,runtime: current))
        }
        XCTAssertEqual(try FieldProtocolBinding(configuration: c,kind: .height,runtime: current),try FieldProtocolBinding(configuration: c,kind: .height,runtime: oldRuntime))
        current["input_mode"] = "typed"
        XCTAssertEqual(try FieldProtocolBinding(configuration: c,kind: .dbh,runtime: current).settings.count,4)
    }
    func testCompleteExportIncludesOriginalBaselineAndAuditsMissingProof() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let archive = FieldStudyArchive(root: root.appendingPathComponent("field")),c = config(),r = runtime("typed")
        try archive.saveConfiguration(c,developerMode: true)
        let binding = try archive.bindProtocol(FieldProtocolBinding(configuration: c,kind: .dbh,runtime: r))
        var record = FieldMeasurementRecord(sessionId: UUID().uuidString,treeId: "T",kind: .dbh,attemptNumber: 1,configuration: c,runtimeSettings: r)
        record.protocolBinding = binding;record.outcome = .failed;record.failureReason = "synthetic"
        try archive.createMeasurement(record)
        let file = archive.root.appendingPathComponent("protocol_bindings/"+binding.id+".json"),bytes = try Data(contentsOf: file)
        let first = try FieldStudyExport.build(archive: archive,rawRoot: root.appendingPathComponent("raw"),results: [],outputDirectory: root.appendingPathComponent("exports"))
        XCTAssertFalse(first.report.issues.contains { $0.code.hasPrefix("protocol_") })
        let zip = try Data(contentsOf: first.url)
        let expected = "field-study/protocol_bindings/"+binding.id+".json"
        let entry = try XCTUnwrap(Common.ZipReader.entries(in: zip).first { $0.name == expected })
        XCTAssertEqual(try Common.ZipReader.extract(entry,from: zip),bytes)
        try FileManager.default.removeItem(at: file)
        let second = try FieldStudyExport.build(archive: archive,rawRoot: root.appendingPathComponent("raw"),results: [],outputDirectory: root.appendingPathComponent("exports"))
        XCTAssertTrue(second.report.issues.contains { $0.code == "protocol_baseline_missing_or_mismatch" })
        XCTAssertTrue(try archive.measurement(id: record.id).protocolConforms)
    }
}
