import XCTest
import Foundation
import CoreVideo
import simd
import Common
import Models
@testable import Sensors
#if canImport(UI)
@testable import UI
#else
@testable import CaptureRuntime
#endif

/// Owned synthetic pixels, uniform synthetic depth, deterministic inference.
/// Exercises the real ViewModel tap/finalize pipeline without an AR device.
final class DBHSnapshotCaptureTests: XCTestCase {
    private let guide = StemExtent(leftFraction: 0.3,rightFraction: 0.7,score: 0.9,maskPixels: 100)
    private func input(_ z:Float = 1.25, rotated: Bool = false) throws -> DBHCameraSnapshot {
        let frame = ARDepthFrame(width: 120,height: 90,depth: .init(repeating: z,count: 10800),
            confidence: .init(repeating: 2,count: 10800),
            intrinsics: simd_float3x3(columns: (SIMD3(100,0,0),SIMD3(0,100,0),SIMD3(60,45,1))),
            cameraPoseWorld: matrix_identity_float4x4,timestamp: 12,
            viewMapping: rotated ? .init(a: 0,b: 1,tx: 0,c: -1,d: 0,ty: 90) : .init(a: 1,b: 0,tx: 0,c: 0,d: 1,ty: 0))
        var buffer:CVPixelBuffer?
        XCTAssertEqual(CVPixelBufferCreate(kCFAllocatorDefault,4,4,kCVPixelFormatType_32BGRA,nil,&buffer),kCVReturnSuccess)
        let pixels = try XCTUnwrap(buffer)
        CVPixelBufferLockBaseAddress(pixels,[])
        memset(CVPixelBufferGetBaseAddress(pixels),128,CVPixelBufferGetDataSize(pixels))
        CVPixelBufferUnlockBaseAddress(pixels,[])
        let image = RawCapturedImage(role: "inference_source",file: nil,width: 4,height: 4,
            cameraFrameSeconds: 12,imageSensorSeconds: nil,timeSource: "ar_frame_image_association",
            intrinsics: [1,0,0,0,1,0,2,2,1],cameraPose: RawCaptureMatrix.flat(matrix_identity_float4x4),
            viewToImage: [4.0/120,0,0,0,4.0/90,0],coordinateSpace: "camera_pixels_native",
            sourceFrameId: "ar_frame_time_bits:"+String(Double(12).bitPattern,radix: 16))
        return try XCTUnwrap(DBHCameraSnapshot(frame: frame,buffer: pixels,image: image))
    }
    private func observation() -> LiveStemObservation {
        .init(extent: nil,mask: .sample(viewWidth: 120,viewHeight: 90) { x,_ in x >= 0.3 && x < 0.7 },guideExtent: guide)
    }
    @MainActor private func waitForCapture(_ vm:DBHScanViewModel) async {
        for _ in 0..<2500 { if vm.state != .capturing { return };try? await Task.sleep(for: .milliseconds(2)) }
        XCTFail("Synthetic capture did not finish")
    }
    @MainActor func testShutterNeedsNoLivePreviewLockAndUsesOnlyFrozenDepth() async throws {
        let frozen = try input(),observation = observation()
        let service = DBHSnapshotCaptureService(acquire: {frozen},infer: {input,_,_,_,_ in
            XCTAssertEqual(input.frame.timestamp,12)
            return .init(observation: observation)
        })
        let vm = DBHScanViewModel(calibration: .identity,snapshotCaptureService: service)
        vm.viewSize = CGSize(width: 120,height: 90);vm.requestAutoAlignment()
        XCTAssertNil(vm.previewDbhCm)
        vm.tap(at: SIMD2(60,45));await waitForCapture(vm)
        let expected = try XCTUnwrap(DBHEstimator.bracketChordEstimate(frames: [frozen.frame],guideAxis: .row(y: 45),
            leftFraction: guide.leftFraction,rightFraction: guide.rightFraction,calibration: .identity))
        XCTAssertEqual(vm.state,.fitted);XCTAssertEqual(vm.result?.diameterCm,expected.diameterCm)
        XCTAssertEqual(vm.resultCaptureMode,"segmented");XCTAssertEqual(vm.snapshotGuide,guide)
        XCTAssertEqual(vm.snapshotReference?.metadata.sourceFrameId,frozen.image.sourceFrameId)
        XCTAssertEqual(vm.snapshotReference?.metadata.cameraFrameSeconds,12)
    }
    @MainActor func testNoStemNeverUsesOldPreviewOrStoredResult() async throws {
        let frozen = try input()
        let service = DBHSnapshotCaptureService(acquire: {frozen},infer: {_,_,_,_,_ in .init(observation: nil)})
        let vm = DBHScanViewModel(calibration: .identity,snapshotCaptureService: service)
        vm.viewSize = CGSize(width: 120,height: 90);vm.requestAutoAlignment()
        vm.tap(at: SIMD2(60,45));await waitForCapture(vm)
        XCTAssertEqual(vm.state,.aligning);XCTAssertNil(vm.result);XCTAssertNil(vm.snapshotGuide)
        XCTAssertNotNil(vm.captureRefusalReason);XCTAssertEqual(vm.resultGeneration,0)
    }
    @MainActor func testTimedOutInferenceRecoversToAdjustAndNeverPublishesLateResult() async throws {
        let frozen = try input(), observation = observation(), finish = DispatchSemaphore(value: 0)
        let began = expectation(description: "snapshot inference began")
        let service = DBHSnapshotCaptureService(acquire: {frozen},processingTimeout: 0.04,infer: {_,_,_,_,_ in
            began.fulfill(); finish.wait(); return .init(observation: observation)
        })
        let vm = DBHScanViewModel(calibration: .identity,snapshotCaptureService: service)
        vm.viewSize = CGSize(width: 120,height: 90);vm.requestAutoAlignment()
        vm.tap(at: SIMD2(60,45))
        await fulfillment(of: [began],timeout: 1); await waitForCapture(vm)
        XCTAssertEqual(vm.state,.aligning);XCTAssertTrue(vm.edgeAdjustActive);XCTAssertFalse(vm.segmentationEnabled)
        XCTAssertNil(vm.result);XCTAssertEqual(vm.resultGeneration,0)
        XCTAssertEqual(vm.captureRefusalReason,AutoSafetyPolicy.captureTimeoutMessage)
        // Retrying Auto must not queue another native worker behind the old one.
        vm.requestAutoAlignment();vm.tap(at: SIMD2(60,45));await waitForCapture(vm)
        XCTAssertEqual(vm.captureRefusalReason,AutoSafetyPolicy.captureBusyMessage)
        finish.signal();try await Task.sleep(for: .milliseconds(50))
        XCTAssertNil(vm.result);XCTAssertEqual(vm.resultGeneration,0)
    }
    @MainActor func testDuplicateTapStartsOnlyOneCapture() async throws {
        let frozen = try input(),observation = observation()
        var acquisitions = 0
        let service = DBHSnapshotCaptureService(acquire: {acquisitions += 1;return frozen},infer: {_,_,_,_,_ in
            Thread.sleep(forTimeInterval: 0.05);return .init(observation: observation)
        })
        let vm = DBHScanViewModel(calibration: .identity,snapshotCaptureService: service)
        vm.viewSize = CGSize(width: 120,height: 90);vm.requestAutoAlignment()
        vm.tap(at: SIMD2(60,45));let id = vm.measurementId
        vm.tap(at: SIMD2(60,45));await waitForCapture(vm)
        XCTAssertEqual(acquisitions,1);XCTAssertEqual(vm.measurementId,id);XCTAssertEqual(vm.resultGeneration,1)
    }
    @MainActor func testOneUnavailableFrameDoesNotRequireAnotherTap() async throws {
        let frozen = try input(),observation = observation()
        var acquisitions = 0
        let service = DBHSnapshotCaptureService(acquire: {
            acquisitions += 1;return acquisitions == 1 ? nil : frozen
        },infer: {_,_,_,_,_ in .init(observation: observation)})
        let vm = DBHScanViewModel(calibration: .identity,snapshotCaptureService: service)
        vm.viewSize = CGSize(width: 120,height: 90);vm.requestAutoAlignment()
        vm.tap(at: SIMD2(60,45));await waitForCapture(vm)
        XCTAssertEqual(acquisitions,2);XCTAssertEqual(vm.state,.fitted)
    }
    @MainActor func testNoPairedInputTimesOutWithoutMeasuringCachedData() async throws {
        let service = DBHSnapshotCaptureService(acquire: {nil},infer: {_,_,_,_,_ in
            XCTFail("Inference cannot run without a paired input");return .init(observation: nil)
        })
        let vm = DBHScanViewModel(calibration: .identity,snapshotCaptureService: service)
        vm.viewSize = CGSize(width: 120,height: 90);vm.requestAutoAlignment()
        vm.tap(at: SIMD2(60,45));await waitForCapture(vm)
        XCTAssertEqual(vm.state,.aligning);XCTAssertNil(vm.result)
        XCTAssertNotNil(vm.captureRefusalReason);XCTAssertEqual(vm.resultGeneration,0)
    }
    @MainActor func testLeavingDuringInferenceCannotPublishLaterResult() async throws {
        let frozen = try input(),observation = observation()
        let service = DBHSnapshotCaptureService(acquire: {frozen},infer: {_,_,_,_,_ in
            Thread.sleep(forTimeInterval: 0.05);return .init(observation: observation)
        })
        let vm = DBHScanViewModel(calibration: .identity,snapshotCaptureService: service)
        vm.viewSize = CGSize(width: 120,height: 90);vm.requestAutoAlignment()
        vm.tap(at: SIMD2(60,45));vm.onDisappear()
        try await Task.sleep(for: .milliseconds(250))
        XCTAssertEqual(vm.state,.aligning);XCTAssertNil(vm.result);XCTAssertEqual(vm.resultGeneration,0)
    }
    @MainActor func testInvalidFrozenDepthCannotBecomeValidBecauseMaskExists() async throws {
        let frozen = try input(0),observation = observation()
        let service = DBHSnapshotCaptureService(acquire: {frozen},infer: {_,_,_,_,_ in .init(observation: observation)})
        let vm = DBHScanViewModel(calibration: .identity,snapshotCaptureService: service)
        vm.viewSize = CGSize(width: 120,height: 90);vm.requestAutoAlignment()
        vm.tap(at: SIMD2(60,45));await waitForCapture(vm)
        XCTAssertEqual(vm.state,.rejected);XCTAssertNil(vm.result)
        XCTAssertEqual(vm.resultGeneration,1)
    }
    @MainActor func testPortraitColumnCaptureRemainsHorizontalInView() async throws {
        let frozen = try input(rotated: true),observation = observation()
        let service = DBHSnapshotCaptureService(acquire: {frozen},infer: {_,_,_,_,_ in .init(observation: observation)})
        let vm = DBHScanViewModel(calibration: .identity,snapshotCaptureService: service)
        vm.viewSize = CGSize(width: 90,height: 120);vm.requestAutoAlignment()
        vm.tap(at: SIMD2(60,45));await waitForCapture(vm)
        let geometry = try XCTUnwrap(DBHEstimator.bracketDepthGeometry(frame: frozen.frame,
            leftFraction: guide.leftFraction,rightFraction: guide.rightFraction,viewSize: vm.viewSize))
        XCTAssertEqual(geometry.axis,.col(x: 60))
        let expected = try XCTUnwrap(DBHEstimator.bracketChordEstimate(frames: [frozen.frame],guideAxis: geometry.axis,
            leftFraction: geometry.left,rightFraction: geometry.right,calibration: .identity))
        XCTAssertEqual(vm.result?.diameterCm,expected.diameterCm)
    }
    @MainActor func testRawBundleKeepsShutterTreeIdentityAndExactInputPair() async throws {
        let frozen = try input(),observation = observation()
        let service = DBHSnapshotCaptureService(acquire: {frozen},infer: {_,_,_,_,_ in .init(observation: observation)})
        let vm = DBHScanViewModel(calibration: .identity,snapshotCaptureService: service)
        vm.rawCaptureEnabled = true;vm.rawCapturePreserveAIInputs = false
        vm.rawCaptureContext = .init(treeNumber: 3)
        vm.viewSize = CGSize(width: 120,height: 90);vm.requestAutoAlignment()
        vm.tap(at: SIMD2(60,45))
        let id = try XCTUnwrap(vm.measurementId)
        defer { try? FileManager.default.removeItem(at: RawCaptureStore.bundleDirectory(id: id)) }
        vm.rawCaptureContext = .init(treeNumber: 4) // A later screen context cannot relabel the frozen input.
        await waitForCapture(vm);await PendingWorkRegistry.rawCapture.waitUntilIdle()
        guard case .saved = vm.lastCaptureOutcome else { return XCTFail("Frozen raw capture did not save") }
        let manifest = try XCTUnwrap(RawCaptureStore.loadManifest(id: id))
        XCTAssertEqual(manifest.measurementId,id);XCTAssertEqual(manifest.context.treeNumber,3)
        let evidence = try XCTUnwrap(manifest.captureEvidence)
        XCTAssertEqual(evidence.frames.first?.sourceFrameId,frozen.image.sourceFrameId)
        XCTAssertEqual(evidence.images?.first?.sourceFrameId,frozen.image.sourceFrameId)
        XCTAssertEqual(evidence.ai.reason,"shutter_rgb_depth_v1")
        XCTAssertEqual(evidence.aiEvidenceStatus,"not_requested")
        XCTAssertEqual(evidence.replayStatus,"pass")
    }
}
