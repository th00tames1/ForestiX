import XCTest
import simd
import Models
@testable import Sensors
#if canImport(CoreVideo)
import CoreVideo
#endif

final class DBHSnapshotIdentityTests: XCTestCase {
    private func frame(_ time: Double = 12, mapping: DepthViewMapping? = .init(a: 1,b: 0,tx: 0,c: 0,d: 1,ty: 0)) -> ARDepthFrame {
        .init(width: 4,height: 4,depth: .init(repeating: 1,count: 16),confidence: .init(repeating: 2,count: 16),
            intrinsics: matrix_identity_float3x3,cameraPoseWorld: matrix_identity_float4x4,timestamp: time,viewMapping: mapping)
    }
    private func image(_ time: Double = 12) -> RawCapturedImage {
        .init(role: "inference_source",file: nil,width: 4,height: 4,cameraFrameSeconds: time,imageSensorSeconds: nil,
            timeSource: "ar_frame_image_association",intrinsics: [1,0,0,0,1,0,0,0,1],
            cameraPose: RawCaptureMatrix.flat(matrix_identity_float4x4),viewToImage: [1,0,0,0,1,0],
            coordinateSpace: "camera_pixels_native",sourceFrameId: "ar_frame_time_bits:"+String(time.bitPattern,radix: 16))
    }
    func testRGBAndDepthMustShareExactFrameIdentityAndMapping() {
        XCTAssertTrue(DBHSnapshotIdentity.matches(frame: frame(),image: image()))
        XCTAssertFalse(DBHSnapshotIdentity.matches(frame: frame(),image: image(12.001)))
        var wrong = image();wrong.sourceFrameId = "different"
        XCTAssertFalse(DBHSnapshotIdentity.matches(frame: frame(),image: wrong))
        wrong = image();wrong.viewToImage = nil
        XCTAssertFalse(DBHSnapshotIdentity.matches(frame: frame(),image: wrong))
        XCTAssertFalse(DBHSnapshotIdentity.matches(frame: frame(mapping: nil),image: image()))
    }
    #if canImport(CoreVideo) && canImport(CoreImage)
    func testSnapshotOwnsPixelsAndColourAttachmentsAfterSourceChanges() throws {
        var source: CVPixelBuffer?
        XCTAssertEqual(CVPixelBufferCreate(kCFAllocatorDefault,4,4,kCVPixelFormatType_32BGRA,nil,&source),kCVReturnSuccess)
        let original = try XCTUnwrap(source)
        CVPixelBufferLockBaseAddress(original,[])
        memset(CVPixelBufferGetBaseAddress(original),64,CVPixelBufferGetDataSize(original))
        CVPixelBufferUnlockBaseAddress(original,[])
        CVBufferSetAttachment(original,kCVImageBufferColorPrimariesKey,kCVImageBufferColorPrimaries_ITU_R_709_2,.shouldPropagate)
        let snapshot = try XCTUnwrap(DBHCameraSnapshot(frame: frame(),buffer: original,image: image()))
        CVPixelBufferLockBaseAddress(original,[])
        memset(CVPixelBufferGetBaseAddress(original),255,CVPixelBufferGetDataSize(original))
        CVPixelBufferUnlockBaseAddress(original,[])
        CVPixelBufferLockBaseAddress(snapshot.buffer,.readOnly)
        XCTAssertEqual(CVPixelBufferGetBaseAddress(snapshot.buffer)?.load(as: UInt8.self),64)
        CVPixelBufferUnlockBaseAddress(snapshot.buffer,.readOnly)
        XCTAssertNotNil(CVBufferGetAttachment(snapshot.buffer,kCVImageBufferColorPrimariesKey,nil))
        XCTAssertFalse(snapshot.buffer === original)
    }
    func testPlanarCameraCopyOwnsEveryYUVPlane() throws {
        var source:CVPixelBuffer?
        XCTAssertEqual(CVPixelBufferCreate(kCFAllocatorDefault,16,16,kCVPixelFormatType_420YpCbCr8BiPlanarFullRange,nil,&source),kCVReturnSuccess)
        let original=try XCTUnwrap(source)
        CVPixelBufferLockBaseAddress(original,[])
        for plane in 0..<CVPixelBufferGetPlaneCount(original) {
            memset(CVPixelBufferGetBaseAddressOfPlane(original,plane),Int32(64+plane),
                CVPixelBufferGetHeightOfPlane(original,plane)*CVPixelBufferGetBytesPerRowOfPlane(original,plane))
        }
        CVPixelBufferUnlockBaseAddress(original,[])
        let copy=try XCTUnwrap(DBHCameraSnapshot.copy(original))
        CVPixelBufferLockBaseAddress(original,[])
        memset(CVPixelBufferGetBaseAddressOfPlane(original,0),255,
            CVPixelBufferGetHeightOfPlane(original,0)*CVPixelBufferGetBytesPerRowOfPlane(original,0))
        CVPixelBufferUnlockBaseAddress(original,[])
        CVPixelBufferLockBaseAddress(copy,.readOnly)
        for plane in 0..<CVPixelBufferGetPlaneCount(copy) {
            XCTAssertEqual(CVPixelBufferGetBaseAddressOfPlane(copy,plane)?.load(as:UInt8.self),UInt8(64+plane))
        }
        CVPixelBufferUnlockBaseAddress(copy,.readOnly)
    }
    #endif
    func testRefusedSnapshotPreservesRGBAndDepthWhenAIInputOptionIsOff() throws {
        let id = UUID().uuidString
        defer { try? FileManager.default.removeItem(at: RawCaptureStore.bundleDirectory(id: id)) }
        var evidence = RawCaptureEvidence(measurementId: id,resultSeconds: 13,
            displayedResult: .init(value: nil,sigma: nil,tier: nil,method: nil,valid: false))
        evidence.attemptFailureReason = "no_stem"
        let jpeg = RawCameraJPEG(data: Data([0xff,0xd8,0xff,0xd9]),metadata: image())
        let outcome = RawCaptureRecorder.recordRefusedAttempt(id: id,calibration: .identity,context: .quick,gps: nil,
            evidence: evidence,aiRequest: RawAIWriteRequest(requested: false,ai: .init(),source: nil),snapshot: frame(),reference: jpeg)
        guard case .saved = outcome else { return XCTFail("Snapshot evidence must save without inventing a DBH") }
        let manifest = try XCTUnwrap(RawCaptureStore.loadManifest(id: id))
        XCTAssertNil(manifest.dbh);XCTAssertFalse(manifest.captureEvidence!.displayedResult.valid)
        XCTAssertEqual(manifest.captureEvidence!.frames.first?.sourceFrameId,image().sourceFrameId)
        XCTAssertEqual(manifest.captureEvidence!.images?.first?.sourceFrameId,image().sourceFrameId)
        XCTAssertEqual(manifest.captureEvidence!.aiEvidenceStatus,"not_requested")
    }
}
