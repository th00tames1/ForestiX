import Foundation
import Models

public struct RawYoloMaskGeometry: Codable, Sendable, Equatable {
    public var score: Double
    public var box: [Double]
    public var width: Int, height: Int
    public var cropX: Int, cropY: Int, cropWidth: Int, cropHeight: Int
    enum CodingKeys: String, CodingKey {
        case score,box,width,height
        case cropX = "crop_x",cropY = "crop_y",cropWidth = "crop_width",cropHeight = "crop_height"
    }
}

/// The prepared RGB tensor, not the later reference JPEG, is the authoritative network input.
public struct RawAIInference: Codable, Sendable {
    public var version = 1
    public var inferenceId: String
    public var clock: String
    public var startedSeconds: Double
    public var completedSeconds: Double
    public var modelFile: String
    public var modelSHA256: String?
    public var runtime: String
    public var runtimeVersion: String?
    public var cpuThreads: Int
    public var preprocessing: [String:String]
    public var tensor: RawCapturedFile
    public var tensorShape = [1,3,640,640]
    public var tensorFormat = "float32_le_nchw_rgb_0_1"
    public var sourceImage: RawCapturedImage?
    public var sourceDepth: RawCapturedFrame
    public var maskLogits: RawCapturedFile?
    public var maskGeometry: RawYoloMaskGeometry?
    public var viewMaskWidth: Int?
    public var viewMaskHeight: Int?
    public var viewMaskRuns: [[Int]]?
    public var viewportSize: [Double]
    public var guideFractions: [Double]?
    public var decoder = "yolo26_oneclass_center_v1"
    public var confidenceThreshold = 0.25
    public var nmsIoUThreshold = 0.45
    public var maskLogitThreshold = 0.0
    enum CodingKeys: String, CodingKey {
        case version,clock,runtime,preprocessing,tensor,decoder
        case inferenceId = "inference_id",startedSeconds = "started_seconds",completedSeconds = "completed_seconds"
        case modelFile = "model_file",modelSHA256 = "model_sha256",runtimeVersion = "runtime_version",cpuThreads = "cpu_threads"
        case tensorShape = "tensor_shape",tensorFormat = "tensor_format",sourceImage = "source_image",sourceDepth = "source_depth"
        case maskLogits = "mask_logits",maskGeometry = "mask_geometry",viewMaskWidth = "view_mask_width",viewMaskHeight = "view_mask_height"
        case viewMaskRuns = "view_mask_runs",viewportSize = "viewport_size",guideFractions = "guide_fractions"
        case confidenceThreshold = "confidence_threshold",nmsIoUThreshold = "nms_iou_threshold",maskLogitThreshold = "mask_logit_threshold"
    }
    public func verifiedTensor(in directory: URL) throws -> Data {
        guard version == 1,tensorShape == [1,3,640,640],tensorFormat == "float32_le_nchw_rgb_0_1",
              tensor.bytes == 3*640*640*4 else { throw FieldStudyError.invalid("AI tensor shape/format") }
        return try tensor.read(in: directory)
    }
    public func restoredMask(in directory: URL) throws -> YoloAlignmentMask.Mask? {
        guard let file = maskLogits,let geometry = maskGeometry else {
            guard maskLogits == nil,maskGeometry == nil else { throw FieldStudyError.invalid("AI mask pairing") }
            return nil
        }
        let data = try file.read(in: directory)
        guard data.count == 160*160*4 else { throw FieldStudyError.invalid("AI logit shape") }
        let values = data.withUnsafeBytes { b in (0..<25600).map {
            Float(bitPattern: UInt32(littleEndian: b.loadUnaligned(fromByteOffset: $0*4,as: UInt32.self)))
        } }
        return try YoloAlignmentMask.restore(geometry: geometry,logits: values)
    }
}

public protocol RawAIRecordSource: Sendable {
    var inferenceId: String { get }
    func write(to directory: URL) throws -> RawAIInference
}

/// At most two raw writers may retain inference inputs. This never waits for a
/// camera worker; a busy/missing recording is explicit and cannot block DBH.
public final class RawAIWriteRequest: @unchecked Sendable {
    private static let budgetLock = NSLock()
    nonisolated(unsafe) private static var pending = 0
    private let lock = NSLock()
    private var source: (any RawAIRecordSource)?
    private var permit = false
    private var consumed = false
    public let status: String
    public let reason: String?
    public init(requested: Bool, ai: FieldAIProvenance,source: (any RawAIRecordSource)?) {
        guard requested else { status = "not_requested"; reason = nil; return }
        guard ai.inferenceRan else { status = "no_inference"; reason = nil; return }
        guard let source else { status = "missing"; reason = "input_not_retained_at_inference"; return }
        guard source.inferenceId == ai.inferenceId else { status = "missing"; reason = "inference_identity_mismatch"; return }
        Self.budgetLock.lock()
        if Self.pending < 2 { Self.pending += 1; permit = true }
        Self.budgetLock.unlock()
        guard permit else { status = "busy"; reason = "two_pending_AI_recordings"; return }
        self.source = source; status = "pending"; reason = nil
    }
    deinit { cancel() }
    public func cancel() {
        lock.lock()
        // A running writer owns the permit until its finally block.
        if source != nil { source = nil; releaseLocked() }
        lock.unlock()
    }
    private func releaseLocked() {
        if permit { permit = false; Self.budgetLock.lock(); Self.pending -= 1; Self.budgetLock.unlock() }
    }
    public func write(to directory: URL) -> (inference: RawAIInference?,status: String,reason: String?) {
        lock.lock()
        guard !consumed else { lock.unlock(); return (nil,"failed","AI_request_already_consumed") }
        consumed = true; let current = source; source = nil; lock.unlock()
        guard let current else { return (nil,status == "pending" ? "cancelled" : status,reason) }
        defer { lock.lock(); releaseLocked(); lock.unlock() }
        do {
            let value = try current.write(to: directory)
            guard value.inferenceId == current.inferenceId else { throw FieldStudyError.invalid("written AI identity") }
            _ = try value.verifiedTensor(in: directory); _ = try value.restoredMask(in: directory)
            _ = try RawCaptureEvidenceIO.readFrame(value.sourceDepth,directory: directory)
            return (value,"saved",value.modelSHA256 == nil ? "model_hash_unavailable" : nil)
        } catch { return (nil,"failed",error.localizedDescription) }
    }
}
