import XCTest
import simd
import Models
@testable import Sensors

final class RawCaptureEvidenceTests: XCTestCase {
    private func frame() -> ARDepthFrame {
        var pose = matrix_identity_float4x4; pose.columns.3 = .init(1.25,-2.5,3.75,1)
        let depth: [Float] = [0,-0.0,Float(bitPattern: 0x7fc01234),.infinity,1.0,1.25,2.5,8,9,-1,0.001,3]
        return ARDepthFrame(width: 4,height: 3,depth: depth,confidence: [0,0,0,0,1,2,0,2,1,0,2,2],
            intrinsics: simd_float3x3(SIMD3(123.25,0,0),SIMD3(0,124.5,0),SIMD3(1.5,2.5,1)),
            cameraPoseWorld: pose,timestamp: 123.25,viewMapping: .init(a: 0.25,b: 0,tx: -1,c: 0,d: 0.5,ty: -2),viewportRevision: 9)
    }
    func testOriginalFrameRoundTripIsBitExactAndKeepsAllGeometry() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("forestix-evidence-"+UUID().uuidString)
        try FileManager.default.createDirectory(at: dir,withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let f = frame(); let meta = try RawCaptureEvidenceIO.writeFrame(f,role: "dbh_0",directory: dir)
        let decoded = try JSONDecoder().decode(RawCapturedFrame.self,from: JSONEncoder().encode(meta))
        let r = try RawCaptureEvidenceIO.readFrame(decoded,directory: dir)
        XCTAssertEqual(r.depth.map(\.bitPattern),f.depth.map(\.bitPattern)); XCTAssertEqual(r.confidence,f.confidence)
        XCTAssertEqual(r.intrinsics,f.intrinsics); XCTAssertEqual(r.cameraPoseWorld,f.cameraPoseWorld)
        XCTAssertEqual(r.viewMapping,f.viewMapping); XCTAssertEqual(r.timestamp,f.timestamp); XCTAssertEqual(r.viewportRevision,9)
        XCTAssertNil(meta.depthSensorSeconds) // ARFrame association is not a measured LiDAR sensor time.
        XCTAssertEqual(meta.sourceFrameId,"ar_frame_time_bits:"+String(f.timestamp.bitPattern,radix: 16))
    }
    func testCorruptMissingAndEscapedFilesCannotPassAsOriginalInput() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("forestix-evidence-"+UUID().uuidString)
        try FileManager.default.createDirectory(at: dir,withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let m = try RawCaptureEvidenceIO.writeFrame(frame(),role: "dbh_0",directory: dir)
        var corrupt = try Data(contentsOf: dir.appendingPathComponent(m.confidence.file)); corrupt[4] = 2
        try corrupt.write(to: dir.appendingPathComponent(m.confidence.file))
        XCTAssertThrowsError(try RawCaptureEvidenceIO.readFrame(m,directory: dir))
        var escaped = m; escaped.depth.file = "../outside.f32"
        XCTAssertThrowsError(try RawCaptureEvidenceIO.readFrame(escaped,directory: dir))
        var missing = m; missing.depth.file = "absent.f32"
        XCTAssertThrowsError(try RawCaptureEvidenceIO.readFrame(missing,directory: dir))
    }
    func testActualDisplayedValueIsNeverReplacedByCanonicalOrReplayValue() throws {
        let id = UUID().uuidString
        var e = RawCaptureEvidence(measurementId: id,resultSeconds: 120,
            displayedResult: .init(value: 25.123456,sigma: nil,tier: "yellow",method: "manualVisual",valid: true))
        e.noteReplay(24.75)
        let d = try JSONDecoder().decode(RawCaptureEvidence.self,from: JSONEncoder().encode(e))
        XCTAssertEqual(d.displayedResult.value,25.123456); XCTAssertNil(d.displayedResult.sigma)
        XCTAssertEqual(d.replayValue,24.75); XCTAssertEqual(d.replayStatus,"mismatch")
        XCTAssertEqual(d.replayDelta!,24.75-25.123456,accuracy: 0)
    }
    func testImageBytesAndSourceGeometryStayTogetherEvenWithoutDepth() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("forestix-image-proof-"+UUID().uuidString)
        try FileManager.default.createDirectory(at: dir,withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let image = RawCapturedImage(role: "reference",file: nil,width: 4,height: 3,cameraFrameSeconds: 123.25,
            imageSensorSeconds: nil,timeSource: "ar_frame_image_association",intrinsics: [1,0,0,0,1,0,2,1,1],
            cameraPose: RawCaptureMatrix.flat(matrix_identity_float4x4),viewToImage: nil,coordinateSpace: "camera_pixels_native")
        let bytes = Data([0xff,0xd8,0xff,0xd9]) // Synthetic byte fixture, not a field photograph.
        let attached = try XCTUnwrap(RawCaptureEvidenceIO.attachImage(image,data: bytes,file: nil,role: "base",directory: dir))
        XCTAssertEqual(try attached.file?.read(in: dir),bytes); XCTAssertEqual(attached.cameraFrameSeconds,123.25)
        XCTAssertNil(attached.imageSensorSeconds); XCTAssertEqual(attached.role,"base")
        let d = try JSONDecoder().decode(RawCapturedImage.self,from: JSONEncoder().encode(attached))
        XCTAssertEqual(d,attached)
    }
    func testFailedWriteKeepsEveryPartialPayloadAndQueuedTruthByte() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("forestix-partial-"+UUID().uuidString)
        try FileManager.default.createDirectory(at: dir,withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let id = UUID().uuidString
        let values: [String: Data] = ["depth_0.bin": Data([1,2,3,4]),"rgb_0.jpg": Data([0xff,0xd8,0xff]),
            "manifest.json": Data("{truncated".utf8),
            "pending_patch.json": Data("{\"truth\":{\"value\":30,\"truth_unit\":\"cm\"},\"operator_accepted\":true}".utf8)]
        for (name,bytes) in values { try bytes.write(to: dir.appendingPathComponent(name)) }
        let message = RawCaptureStore.preserveFailedBundle(id: id,directory: dir)
        XCTAssertTrue(message.contains("retained")); XCTAssertTrue(message.contains("not committed"))
        for (name,bytes) in values { XCTAssertEqual(try Data(contentsOf: dir.appendingPathComponent(name)),bytes) }
        let marker = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: dir.appendingPathComponent("write_failure.json"))) as? [String: Any])
        XCTAssertEqual(marker["measurement_id"] as? String,id); XCTAssertEqual(marker["status"] as? String,"partial_write_failed")
        XCTAssertEqual(RawCaptureStore.reapOrphanBundles(),0)
    }
    func testFailureNoteWriteFailureCannotEraseExistingEvidence() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("forestix-partial-"+UUID().uuidString)
        try FileManager.default.createDirectory(at: dir.appendingPathComponent("write_failure.json"),withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let bytes = Data([9,8,7]),file = dir.appendingPathComponent("original.f32")
        try bytes.write(to: file)
        XCTAssertTrue(RawCaptureStore.preserveFailedBundle(id: UUID().uuidString,directory: dir).contains("retained"))
        XCTAssertEqual(try Data(contentsOf: file),bytes)
    }
    func testInvalidReplayIsMissingNotAZeroMeasurement() throws {
        for value in [0,-1,Double.nan,Double.infinity] {
            var evidence = RawCaptureEvidence(measurementId: UUID().uuidString,resultSeconds: 2,
                displayedResult: .init(value: 25,sigma: nil,tier: "green",method: "synthetic",valid: true))
            evidence.noteReplay(value)
            XCTAssertNil(evidence.replayValue); XCTAssertNil(evidence.replayDelta); XCTAssertEqual(evidence.replayStatus,"no_result")
            XCTAssertEqual(evidence.displayedResult.value,25)
        }
    }
}
