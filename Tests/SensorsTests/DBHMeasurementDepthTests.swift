import XCTest
import simd
import Models
@testable import Sensors

final class DBHMeasurementDepthTests: XCTestCase {
    private func frame(policy:String = DBHMeasurementDepth.sensorConfidence,
                       low:Bool = false,mixed:Bool = false) -> ARDepthFrame {
        let w=80,h=5
        let depth:[Float]=(0..<(w*h)).map { mixed && $0%w >= 35 ? 2:1 }
        let confidence:[UInt8]=(0..<(w*h)).map { low || (mixed && $0%w >= 35) ? 0:2 }
        return ARDepthFrame(width:w,height:h,depth:depth,confidence:confidence,
            intrinsics:simd_float3x3(SIMD3(100,0,0),SIMD3(0,100,0),SIMD3(40,2,1)),
            cameraPoseWorld:matrix_identity_float4x4,timestamp:1,
            dbhDepthInputPolicy:policy)
    }
    private func fit(_ f:ARDepthFrame) -> DBHEstimator.PreviewFit? {
        DBHEstimator.bracketChordFit(frame:f,guideAxis:.row(y:2),leftFraction:0.25,rightFraction:0.75)
    }
    func testNewPolicyRejectsAllLowAndLowConfidenceBackgroundMajority() {
        XCTAssertNotNil(fit(frame()))
        XCTAssertNil(fit(frame(low:true)))
        XCTAssertNil(fit(frame(mixed:true)))
    }
    func testLegacyPolicyAndCleanNewPolicyKeepTheirEstablishedValue() throws {
        let clean=try XCTUnwrap(fit(frame())).diameterCm
        let legacy=try XCTUnwrap(fit(frame(policy:DBHMeasurementDepth.legacy,low:true))).diameterCm
        XCTAssertEqual(clean,legacy,accuracy:0)
        let oldMixed=try XCTUnwrap(fit(frame(policy:DBHMeasurementDepth.legacy,mixed:true))).diameterCm
        XCTAssertEqual(oldMixed,2*legacy,accuracy:1e-10)
    }
    func testUnknownAndMixedPoliciesDoNotSilentlyUseLegacy() {
        XCTAssertNil(fit(frame(policy:"unsupported_future_policy")))
        let mixed=[frame(),frame(policy:DBHMeasurementDepth.legacy)]
        XCTAssertFalse(DBHMeasurementDepth.acceptsWindow(mixed))
        XCTAssertNil(DBHEstimator.bracketChordEstimate(frames:mixed,guideAxis:.row(y:2),
            leftFraction:0.25,rightFraction:0.75,calibration:.identity))
    }
    func testActualConfidenceAndPolicyRoundTripWithoutCanonicalReplacement() throws {
        let dir=FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at:dir,withIntermediateDirectories:true)
        defer { try? FileManager.default.removeItem(at:dir) }
        let original=frame(mixed:true)
        let meta=try RawCaptureEvidenceIO.writeFrame(original,role:"dbh_0",directory:dir)
        let decoded=try JSONDecoder().decode(RawCapturedFrame.self,from:JSONEncoder().encode(meta))
        let replay=try RawCaptureEvidenceIO.readFrame(decoded,directory:dir)
        XCTAssertEqual(decoded.depthInputPolicy,DBHMeasurementDepth.sensorConfidence)
        XCTAssertEqual(replay.dbhDepthInputPolicy,original.dbhDepthInputPolicy)
        XCTAssertEqual(replay.confidence,original.confidence)
        XCTAssertEqual(replay.depth.map(\.bitPattern),original.depth.map(\.bitPattern))
        XCTAssertNil(fit(replay))
        var unknown=meta;unknown.depthInputPolicy="unsupported_future_policy"
        XCTAssertThrowsError(try RawCaptureEvidenceIO.readFrame(unknown,directory:dir))
    }
    func testColumnWalkUsesMatchingFocalAndConfidenceOrientation() throws {
        let source=frame(),w=source.width,h=source.height
        let column=ARDepthFrame(width:h,height:w,
            depth:(0..<(w*h)).map { source.depth[($0%h)*w+$0/h] },
            confidence:(0..<(w*h)).map { source.confidence[($0%h)*w+$0/h] },
            intrinsics:simd_float3x3(SIMD3(70,0,0),SIMD3(0,100,0),SIMD3(2,40,1)),
            cameraPoseWorld:source.cameraPoseWorld,timestamp:source.timestamp,
            dbhDepthInputPolicy:DBHMeasurementDepth.sensorConfidence)
        let actual=try XCTUnwrap(DBHEstimator.bracketChordFit(frame:column,guideAxis:.col(x:2),leftFraction:0.25,rightFraction:0.75))
        XCTAssertEqual(actual.diameterCm,try XCTUnwrap(fit(source)).diameterCm,accuracy:0)
    }
    func testNewRecorderReplaysTheSameActualConfidenceAndPolicy() throws {
        let root=FileManager.default.temporaryDirectory.appendingPathComponent("dbh-confidence-"+UUID().uuidString)
        defer { try? FileManager.default.removeItem(at:root) }
        try RawCaptureStore.$testingRootDirectory.withValue(root) {
            let f=frame(),id=UUID().uuidString
            let value=try XCTUnwrap(DBHEstimator.bracketChordEstimate(frames:[f],guideAxis:.row(y:2),
                leftFraction:0.25,rightFraction:0.75,calibration:.identity))
            let e=RawCaptureEvidence(measurementId:id,resultSeconds:2,
                displayedResult:.init(value:Double(value.diameterCm),sigma:nil,tier:"yellow",method:"synthetic",valid:true))
            let saved=RawCaptureRecorder.recordDBH(id:id,frames:[f],tapPixel:SIMD2(40,2),calibration:.identity,
                algorithm:.chord,bracket:.init(enabled:true,left:0.25,right:0.75),guideAxis:.row(y:2),
                captureManual:true,context:.init(),referenceJPEG:nil,gps:nil,captureEvidence:e)
            guard case .saved = saved else { return XCTFail("Synthetic save failed: \(saved)") }
            let manifest=try XCTUnwrap(RawCaptureStore.loadManifest(id:id))
            XCTAssertEqual(manifest.captureEvidence?.version,2)
            XCTAssertEqual(manifest.settings.dbhDepthInputPolicy,DBHMeasurementDepth.sensorConfidence)
            XCTAssertEqual(manifest.settings.dbhDepthCalibrationEpoch,10)
            XCTAssertEqual(manifest.replaySelfcheck.status,"pass")
            XCTAssertEqual(try RawCaptureReplay.rerunCapturedDBH(manifest:manifest,id:id),Double(value.diameterCm))
            XCTAssertEqual(RawCaptureReplay.rerunDBH(manifest:manifest,id:id)?.diameterCm,value.diameterCm)
            var unknown=manifest;unknown.dbh!.frames[0].depthInputPolicy="unsupported_future_policy"
            XCTAssertNil(RawCaptureReplay.rerunDBH(manifest:unknown,id:id))
            var downgraded=manifest;downgraded.captureEvidence!.version=1
            XCTAssertThrowsError(try RawCaptureReplay.rerunCapturedDBH(manifest:downgraded,id:id))
        }
    }
    func testPreviousGenerationCalibrationIsNotAppliedToNewDepthPolicy() throws {
        let f=frame()
        let cal=ProjectCalibration(depthNoiseMm:5,dbhCorrectionAlpha:2,dbhCorrectionBeta:1.2,dbhCalibrationEpoch:9)
        let clean=try XCTUnwrap(DBHEstimator.bracketChordEstimate(frames:[f],guideAxis:.row(y:2),
            leftFraction:0.25,rightFraction:0.75,calibration:.identity))
        let stale=try XCTUnwrap(DBHEstimator.bracketChordEstimate(frames:[f],guideAxis:.row(y:2),
            leftFraction:0.25,rightFraction:0.75,calibration:cal))
        XCTAssertEqual(clean.diameterCm,stale.diameterCm)
    }
}
