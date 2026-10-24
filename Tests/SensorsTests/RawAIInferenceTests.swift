import XCTest
import Models
import simd
@testable import Sensors
#if canImport(CoreImage) && canImport(CoreVideo)
import CoreImage
import CoreVideo
import ImageIO
#endif

final class RawAIInferenceTests: XCTestCase {
    private final class FailingSource: RawAIRecordSource, @unchecked Sendable {
        let inferenceId = "synthetic-inference"
        func write(to directory: URL) throws -> RawAIInference { throw FieldStudyError.invalid("synthetic write failure") }
    }
    private final class BlockingSource: RawAIRecordSource, @unchecked Sendable {
        let inferenceId = "synthetic-inference"
        let entered = DispatchSemaphore(value: 0),finish = DispatchSemaphore(value: 0)
        func write(to directory: URL) throws -> RawAIInference {
            entered.signal(); _ = finish.wait(timeout: .now()+5)
            throw FieldStudyError.invalid("synthetic write failure")
        }
    }
    private func ai(_ id: String = "synthetic-inference") -> FieldAIProvenance {
        var value = FieldAIProvenance(); value.inferenceRan = true; value.inferenceId = id; return value
    }
    private func directory() throws -> URL {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("forestix-ai-proof-"+UUID().uuidString)
        try FileManager.default.createDirectory(at: dir,withIntermediateDirectories: true); return dir
    }
    private func frame() -> ARDepthFrame {
        ARDepthFrame(width: 4,height: 3,depth: [Float](repeating: 1.25,count: 12),confidence: [UInt8](repeating: 2,count: 12),
            intrinsics: matrix_identity_float3x3,cameraPoseWorld: matrix_identity_float4x4,timestamp: 123.25,
            viewMapping: .init(a: 1,b: 0,tx: 0,c: 0,d: 1,ty: 0),viewportRevision: 3)
    }
    func testDisabledMissingAndMismatchedRequestsNeverWrite() throws {
        let dir = try directory(); defer { try? FileManager.default.removeItem(at: dir) }
        let source = FailingSource()
        let off = RawAIWriteRequest(requested: false,ai: ai(),source: source)
        XCTAssertEqual(off.write(to: dir).status,"not_requested")
        let none = RawAIWriteRequest(requested: true,ai: .init(),source: source)
        XCTAssertEqual(none.write(to: dir).status,"no_inference")
        let missing = RawAIWriteRequest(requested: true,ai: ai(),source: nil)
        XCTAssertEqual(missing.write(to: dir).reason,"input_not_retained_at_inference")
        let wrong = RawAIWriteRequest(requested: true,ai: ai("another-input"),source: source)
        XCTAssertEqual(wrong.write(to: dir).reason,"inference_identity_mismatch")
        XCTAssertTrue(try FileManager.default.contentsOfDirectory(atPath: dir.path).isEmpty)
    }
    func testTwoWriterLimitCancellationFailureAndDeinitReleaseBudget() throws {
        let dir = try directory(); defer { try? FileManager.default.removeItem(at: dir) }
        let source = FailingSource()
        let first = RawAIWriteRequest(requested: true,ai: ai(),source: source)
        let second = RawAIWriteRequest(requested: true,ai: ai(),source: source)
        defer { first.cancel(); second.cancel() }
        XCTAssertEqual(first.status,"pending"); XCTAssertEqual(second.status,"pending")
        XCTAssertEqual(RawAIWriteRequest(requested: true,ai: ai(),source: source).status,"busy")
        first.cancel(); first.cancel()
        XCTAssertEqual(first.write(to: dir).status,"cancelled")
        var replacement: RawAIWriteRequest? = RawAIWriteRequest(requested: true,ai: ai(),source: source)
        XCTAssertEqual(replacement?.status,"pending"); replacement = nil
        let afterDeinit = RawAIWriteRequest(requested: true,ai: ai(),source: source)
        defer { afterDeinit.cancel() }
        XCTAssertEqual(afterDeinit.status,"pending")
        XCTAssertEqual(second.write(to: dir).status,"failed")
        XCTAssertEqual(second.write(to: dir).reason,"AI_request_already_consumed")
        let afterFailure = RawAIWriteRequest(requested: true,ai: ai(),source: source)
        defer { afterFailure.cancel() }; XCTAssertEqual(afterFailure.status,"pending")
    }
    func testBoundaryProvenanceKeepsOlderAppliedInputAndAdjustOrigin() {
        let applied = AIBoundaryProvenance.snapshot(enabled: true,usingBracket: true,lastInferenceId: "new-miss",lastHadMask: false,
            boundaryInferenceId: "visible-old-mask",userEdited: false)
        XCTAssertTrue(applied.boundaryApplied); XCTAssertTrue(applied.maskDetected); XCTAssertEqual(applied.inferenceId,"visible-old-mask")
        let adjust = AIBoundaryProvenance.snapshot(enabled: false,usingBracket: true,lastInferenceId: "new-miss",lastHadMask: false,
            boundaryInferenceId: "visible-old-mask",userEdited: false)
        XCTAssertFalse(adjust.enabled); XCTAssertTrue(adjust.boundaryApplied); XCTAssertEqual(adjust.inferenceId,"visible-old-mask")
        let edited = AIBoundaryProvenance.snapshot(enabled: false,usingBracket: true,lastInferenceId: "new-miss",lastHadMask: false,
            boundaryInferenceId: "visible-old-mask",userEdited: true)
        XCTAssertFalse(edited.boundaryApplied); XCTAssertTrue(edited.userAdjustedBoundary); XCTAssertEqual(edited.inferenceId,"new-miss")
        XCTAssertFalse(AIBoundaryProvenance.snapshot(enabled: false,usingBracket: true,lastInferenceId: nil,lastHadMask: false,
            boundaryInferenceId: nil,userEdited: false).inferenceRan)
    }
    func testCancelDoesNotReleaseRunningWriterPermitPrematurely() throws {
        let dir = try directory(); defer { try? FileManager.default.removeItem(at: dir) }
        let source = BlockingSource(),done = expectation(description: "writer returned")
        let running = RawAIWriteRequest(requested: true,ai: ai(),source: source)
        let other = RawAIWriteRequest(requested: true,ai: ai(),source: FailingSource())
        defer { source.finish.signal(); running.cancel(); other.cancel() }
        DispatchQueue.global(qos: .utility).async { _ = running.write(to: dir); done.fulfill() }
        XCTAssertEqual(source.entered.wait(timeout: .now()+3),.success)
        running.cancel()
        XCTAssertEqual(RawAIWriteRequest(requested: true,ai: ai(),source: FailingSource()).status,"busy")
        source.finish.signal(); wait(for: [done],timeout: 5)
        let after = RawAIWriteRequest(requested: true,ai: ai(),source: FailingSource())
        defer { after.cancel() }; XCTAssertEqual(after.status,"pending")
    }

    #if canImport(CoreImage) && canImport(CoreVideo)
    private func buffer(_ width: Int = 20,_ height: Int = 12,seed: Int = 19) throws -> CVPixelBuffer {
        var result: CVPixelBuffer?
        let rc = CVPixelBufferCreate(kCFAllocatorDefault,width,height,kCVPixelFormatType_32BGRA,
            [kCVPixelBufferIOSurfacePropertiesKey: [:]] as CFDictionary,&result)
        XCTAssertEqual(rc,kCVReturnSuccess)
        let pixel = try XCTUnwrap(result)
        CVPixelBufferLockBaseAddress(pixel,[]); defer { CVPixelBufferUnlockBaseAddress(pixel,[]) }
        let bytes = try XCTUnwrap(CVPixelBufferGetBaseAddress(pixel)).assumingMemoryBound(to: UInt8.self)
        let stride = CVPixelBufferGetBytesPerRow(pixel)
        for y in 0..<height { for x in 0..<width { for c in 0..<4 {
            bytes[y*stride+x*4+c] = c == 3 ? 255 : UInt8((y*73+x*29+c*41+seed)%256)
        } } }
        return pixel
    }
    private func mask(width: Int = 20,height: Int = 12) throws -> YoloAlignmentMask.Mask {
        var head = [Float](repeating: 0,count: 37*8400)
        head[0] = 320; head[8400] = 320; head[2*8400] = 160; head[3*8400] = 640
        head[4*8400] = 0.9; head[5*8400] = 1
        var prototypes = [Float](repeating: 0,count: 32*25600)
        for i in 0..<25600 { prototypes[i] = i%160 < 80 ? -1 : 1 }
        return try XCTUnwrap(YoloAlignmentMask.select(head: head,prototypes: prototypes,width: width,height: height))
    }
    private func source(_ pixel: CVPixelBuffer,turns: Int = 0,workspace: YoloInferenceWorkspace = .init(),
                        mask: YoloAlignmentMask.Mask? = nil) -> YoloOwnedInference {
        let g = YoloImagePreparation.Geometry(rawWidth: CVPixelBufferGetWidth(pixel),rawHeight: CVPixelBufferGetHeight(pixel),quarterTurns: turns)
        YoloImagePreparation.prepare(pixel,geometry: g,context: CIContext(),workspace: workspace)
        let viewMask = LiveStemMask.sample(viewWidth: 200,viewHeight: 300) { x,_ in x > 0.4 && x < 0.6 }
        let guide = LiveStemMask.guideExtent(score: 0.9) { x,_ in x > 0.4 && x < 0.6 }
        return YoloOwnedInference(id: "synthetic-inference",buffer: pixel,geometry: g,inputHash: YoloImagePreparation.inputHash(workspace),
            frame: frame(),sourceImage: nil,mask: mask,observation: .init(extent: nil,mask: viewMask,guideExtent: guide),
            viewSize: CGSize(width: 200,height: 300),started: 123.25,completed: 123.3,modelSHA: "synthetic-model-sha",runtimeVersion: "test")
    }
    func testSharedActualPreparationMatchesIndependentOriginalPipelineEveryBit() throws {
        let context = CIContext(),workspace = YoloInferenceWorkspace()
        for (w,h) in [(20,12),(11,21)] { let pixel = try buffer(w,h)
            for turns in 0..<4 {
                let g = YoloImagePreparation.Geometry(rawWidth: w,rawHeight: h,quarterTurns: turns)
                YoloImagePreparation.prepare(pixel,geometry: g,context: context,workspace: workspace)
                // Frozen original pipeline; deliberately does not call new Geometry or prepare.
                let uw = turns%2 == 0 ? w:h,uh = turns%2 == 0 ? h:w
                let scale = 640.0/Double(max(uw,uh))
                let nw = Int(Double(uw)*scale+0.5),nh = Int(Double(uh)*scale+0.5)
                let px = Int(((640-Double(nw))/2-0.1).rounded()),py = Int(((640-Double(nh))/2-0.1).rounded())
                let orientation: CGImagePropertyOrientation = [.up,.right,.down,.left][turns]
                var ci = CIImage(cvPixelBuffer: pixel).oriented(orientation)
                ci = ci.transformed(by: CGAffineTransform(translationX: -ci.extent.minX,y: -ci.extent.minY))
                ci = ci.transformed(by: CGAffineTransform(scaleX: Double(nw)/Double(uw),y: Double(nh)/Double(uh)))
                var rgba = [UInt8](repeating: 0,count: nw*nh*4)
                context.render(ci,toBitmap: &rgba,rowBytes: nw*4,bounds: CGRect(x: 0,y: 0,width: nw,height: nh),format: .RGBA8,colorSpace: CGColorSpaceCreateDeviceRGB())
                var expected = [Float](repeating: 114.0/255.0,count: 3*640*640)
                for y in 0..<nh { for x in 0..<nw { for c in 0..<3 {
                    expected[c*409600+(y+py)*640+x+px] = Float(rgba[((nh-1-y)*nw+x)*4+c])/255.0
                } } }
                let actual = UnsafeBufferPointer(start: workspace.inputData.bytes.assumingMemoryBound(to: Float.self),count: expected.count)
                XCTAssertEqual(Array(actual).map(\.bitPattern),expected.map(\.bitPattern))
            }
        }
    }
    func testOwnedSourceSurvivesWorkspaceReuseAndMaskRestoresEveryPixel() throws {
        let dir = try directory(); defer { try? FileManager.default.removeItem(at: dir) }
        let workspace = YoloInferenceWorkspace(),pixel = try buffer(),originalMask = try mask()
        let owned = source(pixel,workspace: workspace,mask: originalMask)
        let expectedHash = YoloImagePreparation.inputHash(workspace)
        _ = source(try buffer(seed: 99),workspace: workspace)
        XCTAssertNotEqual(YoloImagePreparation.inputHash(workspace),expectedHash)
        let request = RawAIWriteRequest(requested: true,ai: ai(),source: owned)
        let result = request.write(to: dir); XCTAssertEqual(result.status,"saved",result.reason ?? "")
        let value = try XCTUnwrap(result.inference)
        XCTAssertEqual(RawCaptureEvidenceIO.hash(try value.verifiedTensor(in: dir)),expectedHash)
        let restored = try XCTUnwrap(value.restoredMask(in: dir))
        for y in 0..<12 { for x in 0..<20 { XCTAssertEqual(restored.contains(x: x,y: y),originalMask.contains(x: x,y: y)) } }
        let decoded = try JSONDecoder().decode(RawAIInference.self,from: JSONEncoder().encode(value))
        XCTAssertEqual(decoded.inferenceId,owned.inferenceId); XCTAssertEqual(decoded.sourceDepth.cameraFrameSeconds,123.25)
        XCTAssertNotNil(decoded.viewMaskRuns); XCTAssertNotNil(decoded.guideFractions)
        var e = RawCaptureEvidence(measurementId: UUID().uuidString,resultSeconds: 124,
            displayedResult: .init(value: 25.5,sigma: nil,tier: "yellow",method: "synthetic",valid: true))
        e.aiInference = value; e.aiEvidenceStatus = result.status
        let roundtrip = try JSONDecoder().decode(RawCaptureEvidence.self,from: JSONEncoder().encode(e))
        XCTAssertEqual(roundtrip.displayedResult.value,25.5); XCTAssertNil(roundtrip.aiRecordingWarning)
    }
    func testCorruptionPairingAndInputMutationFailInsteadOfSavingWrongEvidence() throws {
        let dir = try directory(); defer { try? FileManager.default.removeItem(at: dir) }
        let pixel = try buffer(),owned = source(pixel,mask: try mask())
        let value = try owned.write(to: dir)
        var bytes = try Data(contentsOf: dir.appendingPathComponent(value.tensor.file)); bytes[0] ^= 1
        try bytes.write(to: dir.appendingPathComponent(value.tensor.file))
        XCTAssertThrowsError(try value.verifiedTensor(in: dir))
        var badPair = value; badPair.maskGeometry = nil
        XCTAssertThrowsError(try badPair.restoredMask(in: dir))
        CVPixelBufferLockBaseAddress(pixel,[])
        CVPixelBufferGetBaseAddress(pixel)!.assumingMemoryBound(to: UInt8.self)[0] ^= 127
        CVPixelBufferUnlockBaseAddress(pixel,[])
        XCTAssertEqual(RawAIWriteRequest(requested: true,ai: ai(),source: owned).write(to: dir).status,"failed")
    }
    func testFailedAttemptBundleCannotBecomeAValidZeroMeasurement() throws {
        let id = UUID().uuidString
        // The recorder owns only this fresh UUID bundle. Never touch an existing capture.
        let target = RawCaptureStore.bundleDirectory(id: id)
        XCTAssertFalse(FileManager.default.fileExists(atPath: target.path))
        defer { try? FileManager.default.removeItem(at: target) }
        var e = RawCaptureEvidence(measurementId: id,resultSeconds: 124,
            displayedResult: .init(value: nil,sigma: nil,tier: "red",method: "not_measured",valid: false))
        e.attemptFailureReason = "synthetic_no_depth"
        let request = RawAIWriteRequest(requested: true,ai: ai(),source: source(try buffer()))
        let outcome = RawCaptureRecorder.recordRefusedAttempt(id: id,calibration: .identity,
            context: .init(mode: "quick",projectID: nil,plotID: nil,treeNumber: nil,units: "metric"),gps: nil,evidence: e,aiRequest: request)
        if case .saved(let savedID,let frames) = outcome { XCTAssertEqual(savedID,id); XCTAssertEqual(frames,0) }
        else { XCTFail("Input-only recording failed: \(outcome)") }
        let stored = try XCTUnwrap(RawCaptureStore.loadManifest(id: id))
        XCTAssertEqual(stored.measurementId,id); XCTAssertNil(stored.captureEvidence?.displayedResult.value)
        XCTAssertEqual(stored.captureEvidence?.displayedResult.valid,false); XCTAssertEqual(stored.captureEvidence?.aiEvidenceStatus,"saved")
        XCTAssertEqual(stored.settings.captureMode,"attempt_only"); XCTAssertFalse(stored.resultLive.accepted)
        XCTAssertNil(stored.truth.value); XCTAssertNil(stored.dbh)
    }
    #endif
}
