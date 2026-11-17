import XCTest
import simd
import Models
@testable import Sensors

final class RawInertialRecordingTests: XCTestCase {
    private func buffer(samples: Int = 40_000,duration: Double = 120,available: Bool = true) -> RawIMUBuffer {
        let b = RawIMUBuffer(platform: "ios",timeUnit: "seconds",sensorClock: "synthetic",hostClock: "synthetic",arTimeUnit: "seconds",maximumSamples: samples,maximumDuration: duration)
        b.arm(streams: ["accelerometer","gyroscope"].map {
            RawIMUStream(name: $0,source: "synthetic",columns: ["x","y","z"],units: ["1","1","1"],available: available,requestedIntervalSeconds: 0.01)
        }); return b
    }
    private func append(_ b: RawIMUBuffer,_ t: Double,_ stream: String = "accelerometer") {
        b.append(.init(stream: stream,timestamp: t.description,received: t.description,values: [1,2,3]),hostSeconds: t)
    }
    private func begin(_ b: RawIMUBuffer,_ t: Double = 10) {
        b.begin(id: "synthetic-tree",hostSeconds: t,event: .init(event: "anchor",hostTimestamp: t.description,arTimestamp: t.description))
    }
    private func complete(_ b: RawIMUBuffer) {
        append(b,10.01); append(b,10.02,"gyroscope")
        b.mark(.init(event: "base",hostTimestamp: "11")); b.mark(.init(event: "top",hostTimestamp: "12"))
    }
    func testPreRollRetainsOnlyLastTwoSecondsAndCorrectIdentity() throws {
        let b = buffer(); append(b,1); append(b,8); append(b,9); begin(b)
        complete(b); let r = try XCTUnwrap(b.finish())
        XCTAssertEqual(r.samples.map(\.timestamp),["8.0","9.0","10.01","10.02"])
        XCTAssertEqual(r.measurementId,"synthetic-tree"); XCTAssertEqual(r.status,"captured")
        XCTAssertFalse(r.includesCameraInInertialSamples)
    }
    func testPauseStopsAppendingAndIsExplicitlyPartial() throws {
        let b = buffer(); begin(b); complete(b)
        b.pause(host: "12",reason: "background"); append(b,13)
        b.resume(host: "14"); append(b,14)
        let r = try XCTUnwrap(b.finish()); XCTAssertEqual(r.interruptions,1)
        XCTAssertEqual(r.events.map(\.event),["anchor","base","top","pause","resume"])
        XCTAssertEqual(r.samples.count,3); XCTAssertEqual(r.status,"partial")
    }
    func testInFlightPreRollCallbackDoesNotCapNewMeasurement() throws {
        let b = buffer(); begin(b); append(b,9.999); complete(b)
        let r = try XCTUnwrap(b.finish()); XCTAssertFalse(r.capped); XCTAssertEqual(r.samples.count,3)
        XCTAssertEqual(r.status,"captured")
    }
    func testTimeLimitAndSampleLimitCannotGrowWithoutBound() throws {
        for b in [buffer(samples: 2),buffer(duration: 0.015)] {
            begin(b); append(b,10.01); append(b,10.02); append(b,10.03)
            let r = try XCTUnwrap(b.finish()); XCTAssertTrue(r.capped)
            XCTAssertLessThanOrEqual(r.samples.count,2); XCTAssertEqual(r.status,"partial")
        }
    }
    func testUnavailableIsNotFabricatedZeroData() throws {
        let b = buffer(available: false); begin(b); append(b,10.01)
        let r = try XCTUnwrap(b.finish()); XCTAssertEqual(r.status,"unavailable"); XCTAssertTrue(r.samples.isEmpty)
    }
    func testInvalidSamplesAreCountedAndNeverSerializedAsNaN() throws {
        let b = buffer(); begin(b)
        b.append(.init(stream: "accelerometer",timestamp: "10",received: "10",values: [.nan,0,0]),hostSeconds: 10)
        b.append(.init(stream: "accelerometer",timestamp: "10",received: "10",values: [0]),hostSeconds: 10)
        let r = try XCTUnwrap(b.finish()); XCTAssertEqual(r.droppedSamples,2); XCTAssertTrue(r.samples.isEmpty)
        XCTAssertNoThrow(try JSONEncoder().encode(r))
    }
    func testFinishedRecordingIsImmutableAndCanBeRearmedForNextTree() throws {
        let b = buffer(); begin(b); complete(b); let first = try XCTUnwrap(b.finish())
        append(b,13); b.resume(host: "13"); XCTAssertNil(b.finish()); XCTAssertTrue(b.needsFreshArm)
        XCTAssertEqual(first.samples.count,2)
    }
    func testMeasuredCadenceAndClockDiscontinuitiesAreReported() throws {
        let b = buffer(); begin(b); append(b,10.01); append(b,10.02); append(b,10.02); append(b,10.52)
        let r = try XCTUnwrap(b.finish()), s = try XCTUnwrap(r.streamStatistics["accelerometer"])
        XCTAssertEqual(s.count,4); XCTAssertEqual(s.nonIncreasingTimestamps,1)
        XCTAssertEqual(try XCTUnwrap(s.maximumGapSeconds),0.5,accuracy: 1e-9)
    }
    func testSidecarChecksumIdentityAndRoundTrip() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: dir,withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let b = buffer(); begin(b); complete(b); let r = try XCTUnwrap(b.finish())
        XCTAssertThrowsError(try r.write(id: "other-tree",directory: dir))
        let a = try r.write(id: "synthetic-tree",directory: dir)
        let bytes = try a.file.read(in: dir)
        let decoded = try JSONDecoder().decode(RawInertialRecording.self,from: bytes)
        XCTAssertEqual(decoded.samples.count,2); XCTAssertEqual(a.status,"captured")
        let obj = try JSONSerialization.jsonObject(with: bytes) as! [String:Any]
        XCTAssertNotNil(obj["streamStatistics"])
        try Data([0]).write(to: dir.appendingPathComponent("imu.json"))
        XCTAssertThrowsError(try a.file.read(in: dir))
    }
    func testHistoricalHeightAnchorStillDecodesWithoutNewEvidence() throws {
        let bytes = Data(#"{"world":[0,0,0],"hit_type":"synthetic","distance_m":1,"camera_pose":[]}"#.utf8)
        let a = try JSONDecoder().decode(RawCaptureManifest.HeightBundle.Anchor.self,from: bytes)
        XCTAssertNil(a.frame); XCTAssertNil(a.image)
    }
    func testNativeObservationKeepsTrackingThermalAndBothTransforms() throws {
        let b = buffer(); begin(b); complete(b)
        let camera = Array(repeating: 1.25,count: 16), anchor = Array(repeating: 2.5,count: 16)
        b.observe(.init(arTimestamp: "10.001",observedHostTimestamp: "10.01",cameraPose: camera,anchorPose: anchor,
            trackingState: "limited",trackingReason: "excessiveMotion",thermalState: "serious",
            deliveredArTimestamp: "9.99",deliveryHostTimestamp: "10.005",nativeFrameIntervalSeconds: 1.0/60))
        let r = try XCTUnwrap(b.finish())
        let decoded = try JSONDecoder().decode(RawInertialRecording.self,from: JSONEncoder().encode(r))
        let o = try XCTUnwrap(decoded.observations.first)
        XCTAssertEqual(o.cameraPose,camera); XCTAssertEqual(o.anchorPose,anchor)
        XCTAssertEqual(o.arTimestamp,"10.001"); XCTAssertEqual(o.trackingReason,"excessiveMotion"); XCTAssertEqual(o.thermalState,"serious")
    }
    func testObservationLimitIsExplicitAndBounded() throws {
        let b = buffer(); begin(b)
        let missing = RawHeightObservation(arTimestamp: nil,observedHostTimestamp: "11",cameraPose: nil,anchorPose: nil,
            trackingState: "frame_unavailable",trackingReason: nil,thermalState: "unknown",deliveredArTimestamp: nil,
            deliveryHostTimestamp: nil,nativeFrameIntervalSeconds: nil)
        for _ in 0..<800 { b.observe(missing) }
        let r = try XCTUnwrap(b.finish()); XCTAssertEqual(r.observations.count,700); XCTAssertTrue(r.capped)
        XCTAssertEqual(r.status,"partial"); XCTAssertNil(r.observations.first?.cameraPose)
    }
    func testEventOverflowIsNotSilentlyCalledComplete() throws {
        let b = buffer(); begin(b); complete(b)
        for _ in 0..<200 { b.mark(.init(event: "sensor_accuracy",hostTimestamp: "11")) }
        let r = try XCTUnwrap(b.finish()); XCTAssertEqual(r.events.count,128)
        XCTAssertTrue(r.capped); XCTAssertEqual(r.status,"partial")
    }
    func testConcurrentCallbacksRespectBound() throws {
        let b = buffer(samples: 500); begin(b)
        DispatchQueue.concurrentPerform(iterations: 2000) { _ in
            b.append(.init(stream: "accelerometer",timestamp: "10.01",received: "10.01",values: [0,0,0]),hostSeconds: 10.01)
        }
        let r = try XCTUnwrap(b.finish()); XCTAssertEqual(r.samples.count,500); XCTAssertTrue(r.capped)
    }
    func testHeightWriterAttachesAnchorAndIMUWithoutChangingHeightReplay() throws {
        let id = UUID().uuidString, dir = RawCaptureStore.bundleDirectory(id: id)
        defer { try? FileManager.default.removeItem(at: dir) }
        let b = buffer()
        b.begin(id: id,hostSeconds: 10,event: .init(event: "anchor",hostTimestamp: "10"))
        complete(b)
        let f = ARDepthFrame(width: 2,height: 2,depth: [1,1.25,1.5,1.75],confidence: [2,2,2,2],
            intrinsics: matrix_identity_float3x3,cameraPoseWorld: matrix_identity_float4x4,timestamp: 10,
            viewMapping: .init(a: 1,b: 0,tx: 0,c: 0,d: 1,ty: 0))
        let image = RawCapturedImage(role: "anchor",file: nil,width: 2,height: 2,cameraFrameSeconds: 10,
            imageSensorSeconds: nil,timeSource: "synthetic",intrinsics: [1,0,0,0,1,0,0,0,1],
            cameraPose: RawCaptureMatrix.flat(matrix_identity_float4x4),viewToImage: nil,coordinateSpace: "camera_pixels_native")
        let outcome = RawCaptureRecorder.recordHeight(id: id,anchorWorld: .zero,anchorHitType: "synthetic",anchorDistanceM: 1,
            anchorPose: matrix_identity_float4x4,basePitchRad: -0.1,baseStanding: .init(10,1.5,0),
            baseRotationPose: matrix_identity_float4x4,topPitchRad: 0.6,topPose: matrix_identity_float4x4,dHM: 10,
            poseSamples: [],trackingDropped: false,calibration: .identity,context: .quick,gps: nil,
            inertial: b.finish(),anchorFrame: f,anchorJPEG: Data([0xff,0xd8,0xff,0xd9]),anchorImageMetadata: image)
        guard case .saved = outcome else { return XCTFail("Height evidence write failed: \(outcome)") }
        let m = try XCTUnwrap(RawCaptureStore.loadManifest(id: id))
        XCTAssertEqual(m.height?.imu?.status,"captured")
        let proof = try XCTUnwrap(m.height?.imu?.file); _ = try proof.read(in: dir)
        let original = try XCTUnwrap(m.height?.anchor.frame)
        XCTAssertEqual(try RawCaptureEvidenceIO.readFrame(original,directory: dir).depth,f.depth)
        XCTAssertEqual(m.height?.anchor.image?.file?.file,"image_anchor.jpg")
        XCTAssertEqual(m.resultLive.frameCount,0) // Additional anchor is not a base/top aim.
        XCTAssertEqual(m.replaySelfcheck.status,"pass")
        XCTAssertEqual(try XCTUnwrap(RawCaptureReplay.rerunHeight(manifest: m)).result.heightM,10 * (tan(Float(0.6))-tan(Float(-0.1))),accuracy: 0.0001)
    }
}
