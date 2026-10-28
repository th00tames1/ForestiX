import Foundation
import Models
#if canImport(CoreImage) && canImport(CoreVideo)
import CoreImage
import CoreVideo
import ImageIO

/// Shared by actual inference and raw regeneration. Pixel arithmetic is unchanged.
enum YoloImagePreparation {
    struct Geometry: Sendable {
        let rawWidth: Int,rawHeight: Int,quarterTurns: Int
        let uprightWidth: Int,uprightHeight: Int,width: Int,height: Int,padX: Int,padY: Int
        init(rawWidth: Int,rawHeight: Int,quarterTurns: Int) {
            self.rawWidth = rawWidth; self.rawHeight = rawHeight; self.quarterTurns = quarterTurns
            uprightWidth = quarterTurns % 2 == 0 ? rawWidth : rawHeight
            uprightHeight = quarterTurns % 2 == 0 ? rawHeight : rawWidth
            let scale = 640.0/Double(max(uprightWidth,uprightHeight))
            width = Int(Double(uprightWidth)*scale+0.5); height = Int(Double(uprightHeight)*scale+0.5)
            padX = Int(((640-Double(width))/2-0.1).rounded()); padY = Int(((640-Double(height))/2-0.1).rounded())
        }
        var fields: [String:String] { ["version":"ios_ci_rgba8_flip_v1","raw_width":"\(rawWidth)","raw_height":"\(rawHeight)",
            "quarter_turns":"\(quarterTurns)","upright_width":"\(uprightWidth)","upright_height":"\(uprightHeight)",
            "resized_width":"\(width)","resized_height":"\(height)","pad_x":"\(padX)","pad_y":"\(padY)",
            "mask_projection":"view_to_depth_centered_rgb_pixel_round_v1","authoritative_input":"prepared_rgb_tensor"] }
    }
    static func prepare(_ buffer: CVPixelBuffer,geometry g: Geometry,context: CIContext,workspace: YoloInferenceWorkspace) {
        let orientation: CGImagePropertyOrientation = [.up,.right,.down,.left][g.quarterTurns]
        var ci = CIImage(cvPixelBuffer: buffer).oriented(orientation)
        ci = ci.transformed(by: CGAffineTransform(translationX: -ci.extent.minX,y: -ci.extent.minY))
        ci = ci.transformed(by: CGAffineTransform(scaleX: Double(g.width)/Double(g.uprightWidth),y: Double(g.height)/Double(g.uprightHeight)))
        workspace.prepareInput(width: g.width,height: g.height,padX: g.padX,padY: g.padY) { rgba in
            context.render(ci,toBitmap: rgba,rowBytes: g.width*4,bounds: CGRect(x: 0,y: 0,width: g.width,height: g.height),
                format: .RGBA8,colorSpace: CGColorSpaceCreateDeviceRGB())
        }
    }
    static func inputHash(_ workspace: YoloInferenceWorkspace) -> String {
        // Hash synchronously under the inference lock without a second 5MB copy.
        RawCaptureEvidenceIO.hash(Data(bytesNoCopy: workspace.inputData.mutableBytes,count: workspace.inputData.length,deallocator: .none))
    }
}

/// The buffer MUST be an owned copy, never an ARKit camera-pool buffer.
/// Immutable inputs survive workspace reuse; regeneration occurs once at raw write.
final class YoloOwnedInference: RawAIRecordSource, @unchecked Sendable {
    let inferenceId: String
    private let buffer: CVPixelBuffer
    private let geometry: YoloImagePreparation.Geometry
    private let expectedInputHash: String
    private let frame: ARDepthFrame
    private let sourceImage: RawCapturedImage?
    private let mask: YoloAlignmentMask.Mask?
    private let observation: LiveStemObservation?
    private let viewSize: CGSize
    private let started: Double,completed: Double
    private let modelSHA: String?,runtimeVersion: String?
    init(id: String,buffer: CVPixelBuffer,geometry: YoloImagePreparation.Geometry,inputHash: String,
         frame: ARDepthFrame,sourceImage: RawCapturedImage?,mask: YoloAlignmentMask.Mask?,observation: LiveStemObservation?,
         viewSize: CGSize,started: Double,completed: Double,modelSHA: String?,runtimeVersion: String?) {
        inferenceId = id; self.buffer = buffer; self.geometry = geometry; expectedInputHash = inputHash
        self.frame = frame; self.sourceImage = sourceImage; self.mask = mask; self.observation = observation
        self.viewSize = viewSize; self.started = started; self.completed = completed; self.modelSHA = modelSHA; self.runtimeVersion = runtimeVersion
    }
    func write(to directory: URL) throws -> RawAIInference {
        let workspace = YoloInferenceWorkspace()
        YoloImagePreparation.prepare(buffer,geometry: geometry,context: CIContext(),workspace: workspace)
        guard YoloImagePreparation.inputHash(workspace) == expectedInputHash else { throw FieldStudyError.invalid("AI input regeneration hash mismatch") }
        // One copy per recorded capture, not per live inference.
        let bytes = Data(bytes: workspace.inputData.bytes,count: workspace.inputData.length)
        let tensor = try RawCaptureEvidenceIO.write(bytes,name: "ai_tensor.f32",directory: directory)
        var maskFile: RawCapturedFile?
        if let mask {
            let words = mask.logits.map { $0.bitPattern.littleEndian }
            maskFile = try RawCaptureEvidenceIO.write(words.withUnsafeBytes { Data($0) },name: "ai_mask_logits.f32",directory: directory)
        }
        return try RawAIInference(inferenceId: inferenceId,clock: "system_uptime_seconds",startedSeconds: started,completedSeconds: completed,
            modelFile: "yolo26_alignment.onnx",modelSHA256: modelSHA,runtime: "onnxruntime_cpu",runtimeVersion: runtimeVersion,cpuThreads: 2,
            preprocessing: geometry.fields,tensor: tensor,sourceImage: sourceImage,
            sourceDepth: RawCaptureEvidenceIO.writeFrame(frame,role: "ai_source",directory: directory),maskLogits: maskFile,maskGeometry: mask?.captureGeometry(),
            viewMaskWidth: observation?.mask?.width,viewMaskHeight: observation?.mask?.height,
            viewMaskRuns: observation?.mask?.runs.map { [$0.row,$0.start,$0.end] },viewportSize: [Double(viewSize.width),Double(viewSize.height)],
            guideFractions: observation?.guideExtent.map { [$0.leftFraction,$0.rightFraction] })
    }
}

/// A transient read from one ARFrame; the caller must deep-copy the buffer immediately.
public struct RawCameraInput: @unchecked Sendable {
    public let buffer: CVPixelBuffer
    public let metadata: RawCapturedImage
}
#endif

public struct YoloTrackedCapture: Sendable {
    public let observation: LiveStemObservation?
    public let source: (any RawAIRecordSource)?
    public init(observation: LiveStemObservation?,source: (any RawAIRecordSource)? = nil) { self.observation = observation; self.source = source }
}
