// Spec §7.1 ingestion + §8 (Sensors/ARKitSessionManager). Owns the
// ARSession configured with LiDAR scene depth, mesh reconstruction, and
// gravity+heading world alignment (REQ-CTR-002 compass, REQ-DBH §7.1).
//
// Lifecycle: `run()` starts the session; `pause()` stops it. DBH screens
// call run on enter / pause on exit to keep LiDAR idle otherwise (NFR
// battery budget).
//
// Publishers:
//   - `trackingState`  current ARKit camera tracking state
//   - `depthFrame`     latest ARDepthFrame (downsampled sceneDepth)
//
// On non-iOS hosts (Swift test on macOS, simulator) the implementation
// compiles to no-ops so the rest of the `Sensors` module stays testable.

import Foundation
import Combine
import simd

#if canImport(ARKit) && os(iOS)
import ARKit
#endif

// MARK: - ARDepthFrame (platform-independent shape)

/// Shape matches §7.1 DBHScanInput: per-frame depth + confidence grid,
/// camera intrinsics, pose in world frame, timestamp. Stored in the
/// depth map's native orientation (landscape for iOS), which is the
/// basis for all coordinate math in §7.1 Steps 3–4.
public struct ARDepthFrame: Sendable {
    public let width: Int
    public let height: Int
    /// Row-major `height · width` depths in metres.
    public let depth: [Float]
    /// Row-major confidence, 0/1/2 (low/medium/high) per Apple convention.
    public let confidence: [UInt8]
    /// Camera intrinsic matrix (pixel units), column-major simd.
    public let intrinsics: simd_float3x3
    /// T_world_camera (homogeneous, column-major).
    public let cameraPoseWorld: simd_float4x4
    public let timestamp: TimeInterval

    public init(
        width: Int,
        height: Int,
        depth: [Float],
        confidence: [UInt8],
        intrinsics: simd_float3x3,
        cameraPoseWorld: simd_float4x4,
        timestamp: TimeInterval
    ) {
        precondition(depth.count == width * height)
        precondition(confidence.count == width * height)
        self.width = width
        self.height = height
        self.depth = depth
        self.confidence = confidence
        self.intrinsics = intrinsics
        self.cameraPoseWorld = cameraPoseWorld
        self.timestamp = timestamp
    }

    @inlinable
    public func depth(atX x: Int, y: Int) -> Float {
        depth[y * width + x]
    }

    @inlinable
    public func confidence(atX x: Int, y: Int) -> UInt8 {
        confidence[y * width + x]
    }
}

// MARK: - Tracking state (platform-independent)

public enum TrackingStatus: Sendable, Equatable {
    case notAvailable
    case limited
    case normal
}

// MARK: - Session manager

#if canImport(ARKit) && os(iOS)

@MainActor
public final class ARKitSessionManager: NSObject, ObservableObject, ARSessionDelegate {

    @Published public private(set) var trackingStatus: TrackingStatus = .notAvailable
    @Published public private(set) var latestDepthFrame: ARDepthFrame?
    @Published public private(set) var isRunning = false
    /// Live camera position in ARKit world space (column 3 of the
    /// camera transform). Used by Offset-from-Opening / VIOChain to
    /// snapshot where the user is standing at each confirmation.
    @Published public private(set) var currentCameraWorldPosition: SIMD3<Float>?

    public static var supportsLiDAR: Bool {
        ARWorldTrackingConfiguration.supportsFrameSemantics(.sceneDepth)
    }

    /// Exposed read-only so a SwiftUI `ARViewContainer` can share the
    /// same session for camera-feed rendering — without it, the scan
    /// screens render only their overlay chrome over a black background
    /// and the cruiser can't see what they're aiming at.
    public let session: ARSession
    private var trackedStateWasAlwaysNormal = true

    public override init() {
        self.session = ARSession()
        super.init()
        self.session.delegate = self
    }

    public func run() {
        guard ARWorldTrackingConfiguration.isSupported else { return }
        let config = ARWorldTrackingConfiguration()
        config.worldAlignment = .gravityAndHeading
        if ARWorldTrackingConfiguration.supportsFrameSemantics(.sceneDepth) {
            config.frameSemantics.insert(.sceneDepth)
        }
        if ARWorldTrackingConfiguration.supportsSceneReconstruction(.mesh) {
            config.sceneReconstruction = .mesh
        }
        trackedStateWasAlwaysNormal = true
        session.run(config, options: [.removeExistingAnchors, .resetTracking])
        isRunning = true
    }

    public func pause() {
        session.pause()
        isRunning = false
    }

    /// True if every frame observed since the last `run()` reported
    /// `.normal` tracking — used by §7.2 height measurement guard.
    public var trackingStayedNormal: Bool { trackedStateWasAlwaysNormal }

    // MARK: ARSessionDelegate

    public nonisolated func session(_ session: ARSession, didUpdate frame: ARFrame) {
        let converted = Self.convert(frame: frame)
        let status = Self.mapTrackingState(frame.camera.trackingState)
        let t = frame.camera.transform
        let camPos = SIMD3<Float>(t.columns.3.x, t.columns.3.y, t.columns.3.z)
        Task { @MainActor [weak self] in
            guard let self else { return }
            if status != .normal { self.trackedStateWasAlwaysNormal = false }
            self.trackingStatus = status
            self.currentCameraWorldPosition = camPos
            if let converted { self.latestDepthFrame = converted }
        }
    }

    private nonisolated static func mapTrackingState(
        _ state: ARCamera.TrackingState
    ) -> TrackingStatus {
        switch state {
        case .normal: return .normal
        case .notAvailable: return .notAvailable
        case .limited: return .limited
        }
    }

    private nonisolated static func convert(frame: ARFrame) -> ARDepthFrame? {
        guard let sceneDepth = frame.sceneDepth ?? frame.smoothedSceneDepth
        else { return nil }
        let depthMap = sceneDepth.depthMap
        let confidenceMap = sceneDepth.confidenceMap

        let width  = CVPixelBufferGetWidth(depthMap)
        let height = CVPixelBufferGetHeight(depthMap)

        CVPixelBufferLockBaseAddress(depthMap, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(depthMap, .readOnly) }
        guard let depthBase = CVPixelBufferGetBaseAddress(depthMap)
        else { return nil }
        let depthStride = CVPixelBufferGetBytesPerRow(depthMap)

        var depth = [Float](repeating: 0, count: width * height)
        for row in 0..<height {
            let rowPtr = depthBase.advanced(by: row * depthStride)
                .assumingMemoryBound(to: Float.self)
            for col in 0..<width {
                depth[row * width + col] = rowPtr[col]
            }
        }

        var confidence = [UInt8](repeating: 0, count: width * height)
        if let cm = confidenceMap {
            CVPixelBufferLockBaseAddress(cm, .readOnly)
            defer { CVPixelBufferUnlockBaseAddress(cm, .readOnly) }
            if let cBase = CVPixelBufferGetBaseAddress(cm) {
                let cStride = CVPixelBufferGetBytesPerRow(cm)
                for row in 0..<height {
                    let rowPtr = cBase.advanced(by: row * cStride)
                        .assumingMemoryBound(to: UInt8.self)
                    for col in 0..<width {
                        confidence[row * width + col] = rowPtr[col]
                    }
                }
            }
        }

        return ARDepthFrame(
            width: width,
            height: height,
            depth: depth,
            confidence: confidence,
            intrinsics: frame.camera.intrinsics,
            cameraPoseWorld: frame.camera.transform,
            timestamp: frame.timestamp
        )
    }
}

#else

/// macOS / non-ARKit stand-in. Exposes the same surface as the iOS
/// implementation so the rest of the Sensors module and the UI layer
/// compile cleanly on developer macs and in tests.
@MainActor
public final class ARKitSessionManager: ObservableObject {

    @Published public private(set) var trackingStatus: TrackingStatus = .notAvailable
    @Published public private(set) var latestDepthFrame: ARDepthFrame?
    @Published public private(set) var isRunning = false
    @Published public private(set) var currentCameraWorldPosition: SIMD3<Float>?

    public static var supportsLiDAR: Bool { false }

    public init() {}
    public func run() {}
    public func pause() {}
    public var trackingStayedNormal: Bool { false }
}

#endif
