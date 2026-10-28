import Foundation
import Models
import Common

/// Capture identity checks do not claim simultaneous hardware exposures.
/// They prove that RGB and depth were acquired from the same AR frame.
public enum DBHSnapshotIdentity {
    public static func matches(frame: ARDepthFrame, image: RawCapturedImage) -> Bool {
        let id = "ar_frame_time_bits:" + String(frame.timestamp.bitPattern, radix: 16)
        return frame.timestamp.isFinite && image.cameraFrameSeconds == frame.timestamp && image.sourceFrameId == id &&
            frame.viewMapping != nil && image.viewToImage?.count == 6 && image.viewToImage!.allSatisfy(\.isFinite)
    }
}

#if canImport(CoreImage) && canImport(CoreVideo)
import CoreImage
import CoreVideo
import ImageIO

/// One owned RGB copy and one owned depth copy. No ARKit pool buffer escapes.
public struct DBHCameraSnapshot: @unchecked Sendable {
    public let frame: ARDepthFrame
    public let buffer: CVPixelBuffer
    public let image: RawCapturedImage

    public init?(frame: ARDepthFrame, buffer: CVPixelBuffer, image: RawCapturedImage) {
        guard DBHSnapshotIdentity.matches(frame: frame, image: image), let copy = Self.copy(buffer) else { return nil }
        self.frame = frame; self.buffer = copy; self.image = image
    }

    /// Encode this owned input, never re-read the current live camera.
    public func referenceJPEG() -> RawCameraJPEG? {
        let context = CIContext()
        let key = CIImageRepresentationOption(rawValue: kCGImageDestinationLossyCompressionQuality as String)
        guard let bytes = context.jpegRepresentation(of: CIImage(cvPixelBuffer: buffer),
            colorSpace: CGColorSpaceCreateDeviceRGB(), options: [key: 0.8]) else { return nil }
        return RawCameraJPEG(data: bytes, metadata: image)
    }

    public static func copy(_ source: CVPixelBuffer) -> CVPixelBuffer? {
        var output: CVPixelBuffer?
        let attributes = [kCVPixelBufferIOSurfacePropertiesKey: [:]] as CFDictionary
        guard CVPixelBufferCreate(kCFAllocatorDefault, CVPixelBufferGetWidth(source), CVPixelBufferGetHeight(source),
            CVPixelBufferGetPixelFormatType(source), attributes, &output) == kCVReturnSuccess, let target = output else { return nil }
        guard CVPixelBufferLockBaseAddress(source, .readOnly) == kCVReturnSuccess else { return nil }
        defer { CVPixelBufferUnlockBaseAddress(source, .readOnly) }
        guard CVPixelBufferLockBaseAddress(target, []) == kCVReturnSuccess else { return nil }
        defer { CVPixelBufferUnlockBaseAddress(target, []) }
        let planar = CVPixelBufferGetPlaneCount(source) > 0
        for plane in 0..<max(1, CVPixelBufferGetPlaneCount(source)) {
            let src = planar ? CVPixelBufferGetBaseAddressOfPlane(source, plane) : CVPixelBufferGetBaseAddress(source)
            let dst = planar ? CVPixelBufferGetBaseAddressOfPlane(target, plane) : CVPixelBufferGetBaseAddress(target)
            let rows = planar ? CVPixelBufferGetHeightOfPlane(source, plane) : CVPixelBufferGetHeight(source)
            let sr = planar ? CVPixelBufferGetBytesPerRowOfPlane(source, plane) : CVPixelBufferGetBytesPerRow(source)
            let dr = planar ? CVPixelBufferGetBytesPerRowOfPlane(target, plane) : CVPixelBufferGetBytesPerRow(target)
            guard let src, let dst else { return nil }
            for row in 0..<rows { memcpy(dst.advanced(by: row*dr), src.advanced(by: row*sr), min(sr, dr)) }
        }
        CVBufferPropagateAttachments(source, target)
        return target
    }
}

/// Separates acquisition (main actor) from immutable worker input processing.
/// Synthetic services exercise the real capture state machine without ARKit.
public struct DBHSnapshotCaptureService: Sendable {
    private static let liveWorker = DeadlineWorkExecutor()
    public let acquire: @MainActor @Sendable () -> DBHCameraSnapshot?
    public let infer: @Sendable (DBHCameraSnapshot, CGSize, String, Bool, Double) throws -> YoloTrackedCapture
    public let worker: DeadlineWorkExecutor
    public let processingTimeout: TimeInterval
    public init(acquire: @escaping @MainActor @Sendable () -> DBHCameraSnapshot?,
                processingTimeout: TimeInterval = AutoSafetyPolicy.captureProcessingSeconds,
                worker: DeadlineWorkExecutor = DeadlineWorkExecutor(),
                infer: @escaping @Sendable (DBHCameraSnapshot, CGSize, String, Bool, Double) throws -> YoloTrackedCapture) {
        self.acquire = acquire; self.infer = infer
        self.processingTimeout = processingTimeout; self.worker = worker
    }
    @MainActor public static func live(session: ARKitSessionManager) -> Self {
        Self(acquire: { session.captureDBHSnapshot() }, worker: liveWorker, infer: { input,viewport,id,recordAI,started in
            #if canImport(OnnxRuntimeBindings)
            return try YoloBoundaryAligner.withShared { model in
                if recordAI {
                    return try model.trackWithCapture(buffer: input.buffer,frame: input.frame,
                        viewSize: viewport,id: id,sourceImage: input.image,started: started)
                }
                return YoloTrackedCapture(observation: try model.track(buffer: input.buffer,frame: input.frame,viewSize: viewport))
            }
            #else
            throw NSError(domain: "ForestiX",code: 1,userInfo: [NSLocalizedDescriptionKey: "AI capture is unavailable. Use Adjust."])
            #endif
        })
    }
}

/// Only already-produced evidence is read by a deadline callback. Never
/// encode another image or close a buffer that a native worker still owns.
public final class DBHSnapshotProgress: @unchecked Sendable {
    private let lock = NSLock()
    private var image: RawCameraJPEG?
    public init() {}
    public func setReference(_ value: RawCameraJPEG?) { lock.lock(); image = value; lock.unlock() }
    public var reference: RawCameraJPEG? { lock.lock(); defer { lock.unlock() }; return image }
}
#endif
