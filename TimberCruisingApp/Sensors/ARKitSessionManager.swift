// Spec §7.1 ingestion + §8 (Sensors/ARKitSessionManager). Owns the
// ARSession configured with LiDAR scene depth, mesh reconstruction, and
// gravity+heading world alignment (REQ-CTR-002 compass, REQ-DBH §7.1).
//
// SHARED SESSION (field round 8): the AR measure screens (DBH / Height /
// Distance / Sampling plot) all attach to ONE app-scoped manager,
// `ARKitSessionManager.shared`, instead of constructing their own. One
// session ⇒ one ARKit world coordinate frame for the whole app run, so
// world anchors placed on one screen (the sampling-plot centre) are still
// valid on the next. Screens attach with a per-screen configuration
// (`ARScreenConfiguration`) that is applied via `session.run(config)`
// WITHOUT reset options — anchors and the accumulated world map survive
// every screen switch. When the last screen detaches (map home visible)
// the session pauses; the next attach re-runs it and ARKit relocalizes
// into the existing map. After a LONG pause (minutes, or the cruiser
// walked far away) relocalization can take a few seconds and tracking is
// degraded until ARKit re-recognises the scene — accepted trade-off for
// keeping the plot anchor alive.
//
// Lifecycle: `attach(client:configuration:)` on screen enter,
// `detach(client:)` on exit. The legacy `run()` / `pause()` pair still
// works (full configuration under an internal client token) for callers
// that own a private manager instance (calibration, cruise-flow offset).
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
import CoreImage
import ImageIO
import os
#endif

// MARK: - Per-screen session configuration

/// What an AR screen needs from the shared session. Applied on screen
/// entry via `ARSession.run(_:)` with NO reset options, so switching
/// screens never destroys anchors or the world map.
///
/// The flags exist because the expensive per-frame work is CPU-side:
/// converting the LiDAR depth map into `ARDepthFrame` copies ~50 k floats
/// per frame at 60 Hz. Screens that never read depth (sampling plot,
/// distance) turn `depthStream` off and the delegate skips the copy
/// entirely — the single biggest lag reduction on those screens.
public struct ARScreenConfiguration: Equatable, Sendable {
    /// Enable LiDAR `sceneDepth` frame semantics AND convert + publish
    /// `latestDepthFrame` every frame. Needed by the DBH depth pipeline
    /// and the Height walking readout.
    public var depthStream: Bool
    /// Publish `latestRawFeaturePoints` (VIO sparse cloud) every frame.
    /// Only the DBH AR-motion research method consumes these.
    public var featurePoints: Bool
    /// LiDAR scene reconstruction (mesh anchors) — drives the mesh
    /// raycasts every measure screen uses plus the DBH mesh overlay.
    public var sceneReconstruction: Bool

    public init(depthStream: Bool = true,
                featurePoints: Bool = true,
                sceneReconstruction: Bool = true) {
        self.depthStream = depthStream
        self.featurePoints = featurePoints
        self.sceneReconstruction = sceneReconstruction
    }

    /// Everything on — legacy `run()` behaviour.
    public static let full = ARScreenConfiguration()
    /// DBH scan: depth burst + VIO features + mesh overlay/raycasts.
    public static let dbhScan = ARScreenConfiguration(
        depthStream: true, featurePoints: true, sceneReconstruction: true)
    /// Height scan: depth (walking readout reads the depth-frame pose),
    /// mesh raycasts for anchor/base taps; no VIO feature stream.
    public static let heightScan = ARScreenConfiguration(
        depthStream: true, featurePoints: false, sceneReconstruction: true)
    /// Distance: raycasts only — no depth copies, no feature stream.
    public static let distanceMeasure = ARScreenConfiguration(
        depthStream: false, featurePoints: false, sceneReconstruction: true)
    /// Sampling plot: raycast on placement + camera pose polling — no
    /// depth copies, no feature stream (the screen's lag fix).
    /// `sceneReconstruction` stays ON here for a second reason: the
    /// sampling screen's ARView enables RealityKit scene-understanding
    /// OCCLUSION (ring/pole pass behind real trunks), which consumes
    /// the session's LiDAR mesh. Occlusion itself is a per-ARView
    /// render option, so it never carries over to the other screens'
    /// views when the shared session switches configuration.
    public static let samplingPlot = ARScreenConfiguration(
        depthStream: false, featurePoints: false, sceneReconstruction: true)
}

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

    /// App-scoped shared manager — ONE ARSession (⇒ one world coordinate
    /// frame) for every AR measure screen. See the header comment.
    public static let shared = ARKitSessionManager()

    @Published public private(set) var trackingStatus: TrackingStatus = .notAvailable
    @Published public private(set) var latestDepthFrame: ARDepthFrame?
    @Published public private(set) var isRunning = false
    /// Live camera position in ARKit world space (column 3 of the
    /// camera transform). Used by Offset-from-Opening / VIOChain to
    /// snapshot where the user is standing at each confirmation.
    @Published public private(set) var currentCameraWorldPosition: SIMD3<Float>?
    /// Latest ARKit sparse VIO feature points in world space (metric).
    /// Published every frame; the AR-motion DBH method accumulates these
    /// over a short capture window and circle-fits them. Available on every
    /// device (no LiDAR required) — these come from visual-inertial odometry.
    @Published public private(set) var latestRawFeaturePoints: [SIMD3<Float>]?

    public static var supportsLiDAR: Bool {
        ARWorldTrackingConfiguration.supportsFrameSemantics(.sceneDepth)
    }

    /// Exposed read-only so a SwiftUI `ARViewContainer` can share the
    /// same session for camera-feed rendering — without it, the scan
    /// screens render only their overlay chrome over a black background
    /// and the cruiser can't see what they're aiming at.
    public let session: ARSession
    private var trackedStateWasAlwaysNormal = true
    /// Per-window tracking latch — reset by `beginTrackingWatch()` at the
    /// start of a bounded capture (e.g. a VIO sweep) so a tracking dropout
    /// earlier in the session doesn't permanently veto the green tier.
    private var trackedNormalSinceWatch = true

    /// Attached screens, in attach order (most recent last). The session
    /// runs with the most recently attached client's configuration and
    /// pauses when the list empties. Tokens make overlapping screen
    /// transitions safe: when screen B attaches before screen A's
    /// onDisappear detaches, A's detach re-applies B's configuration
    /// instead of pausing the session under B.
    private var attachedClients: [(id: UUID, configuration: ARScreenConfiguration)] = []
    /// Client token backing the legacy `run()` / `pause()` surface.
    private let legacyClientID = UUID()
    /// Per-frame stream gate read by the (nonisolated) session delegate:
    /// which conversions/publishes the current screen actually wants.
    private struct StreamGate { var depth: Bool; var features: Bool }
    private let streamGate = OSAllocatedUnfairLock(
        initialState: StreamGate(depth: true, features: true))

    public override init() {
        self.session = ARSession()
        super.init()
        self.session.delegate = self
    }

    // MARK: Shared-session attach / detach

    /// Attach an AR screen. Applies `configuration` via `session.run`
    /// with NO reset options — world anchors (e.g. the sampling-plot
    /// centre) and the accumulated world map survive. Re-attaching an
    /// already-attached client just re-applies its configuration
    /// (idempotent; scenePhase .active re-entry uses this).
    public func attach(client id: UUID, configuration: ARScreenConfiguration) {
        attachedClients.removeAll { $0.id == id }
        attachedClients.append((id, configuration))
        applyConfiguration(configuration)
    }

    /// Detach an AR screen. If another screen is still attached (screen-
    /// transition overlap) its configuration is re-applied; otherwise the
    /// session pauses until the next attach. Detaching an unknown client
    /// is a no-op.
    public func detach(client id: UUID) {
        guard attachedClients.contains(where: { $0.id == id }) else { return }
        attachedClients.removeAll { $0.id == id }
        if let survivor = attachedClients.last {
            applyConfiguration(survivor.configuration)
        } else {
            session.pause()
            isRunning = false
        }
    }

    /// Legacy single-owner lifecycle, kept for privately-owned manager
    /// instances (calibration, cruise-flow offset). Equivalent to
    /// attaching/detaching one full-configuration client. Note: reset
    /// options are gone here too — a re-run after backgrounding resumes
    /// the existing world map instead of restarting tracking.
    public func run() {
        attach(client: legacyClientID, configuration: .full)
    }

    public func pause() {
        detach(client: legacyClientID)
    }

    private func applyConfiguration(_ cfg: ARScreenConfiguration) {
        guard ARWorldTrackingConfiguration.isSupported else { return }
        let config = ARWorldTrackingConfiguration()
        config.worldAlignment = .gravityAndHeading
        if cfg.depthStream,
           ARWorldTrackingConfiguration.supportsFrameSemantics(.sceneDepth) {
            config.frameSemantics.insert(.sceneDepth)
        }
        if cfg.sceneReconstruction,
           ARWorldTrackingConfiguration.supportsSceneReconstruction(.mesh) {
            config.sceneReconstruction = .mesh
        }
        streamGate.withLock {
            $0 = StreamGate(depth: cfg.depthStream, features: cfg.featurePoints)
        }
        // Drop stale stream values the new screen will never refresh —
        // a DBH HUD must not read a depth frame captured minutes ago.
        if !cfg.depthStream { latestDepthFrame = nil }
        if !cfg.featurePoints { latestRawFeaturePoints = nil }
        trackedStateWasAlwaysNormal = true
        // Deliberately NO reset options (.resetTracking /
        // .removeExistingAnchors): the whole point of the shared session
        // is that world anchors and the map persist across screens and
        // across pause/resume. ARKit relocalizes after a pause; a long
        // pause degrades tracking briefly until the scene is recognised.
        session.run(config)
        isRunning = true
    }

    // MARK: World anchors

    /// Add a real ARKit `ARAnchor` at a world position and return its
    /// identifier. Anchors participate in ARKit's world-map corrections
    /// (drift compensation, relocalization) — the engine-native way to
    /// pin a point like the sampling-plot centre. Returns nil only on
    /// non-AR hosts (stub) — kept optional for API parity.
    @discardableResult
    public func addWorldAnchor(at worldPosition: SIMD3<Float>,
                               name: String = "forestix.worldAnchor") -> UUID? {
        var transform = matrix_identity_float4x4
        transform.columns.3 = SIMD4<Float>(worldPosition.x,
                                           worldPosition.y,
                                           worldPosition.z, 1)
        let anchor = ARAnchor(name: name, transform: transform)
        session.add(anchor: anchor)
        return anchor.identifier
    }

    /// Remove a previously added world anchor. No-op when the anchor no
    /// longer exists (session recreated, already removed).
    public func removeWorldAnchor(id: UUID) {
        guard let anchor = session.currentFrame?.anchors
            .first(where: { $0.identifier == id }) else { return }
        session.remove(anchor: anchor)
    }

    /// Live world position of an anchor — reflects ARKit's latest
    /// world-map correction, not the position it was placed at. nil when
    /// the anchor is gone or no frame is available yet.
    public func worldAnchorPosition(id: UUID) -> SIMD3<Float>? {
        guard let anchor = session.currentFrame?.anchors
            .first(where: { $0.identifier == id }) else { return nil }
        let c = anchor.transform.columns.3
        return SIMD3<Float>(c.x, c.y, c.z)
    }

    /// Whether the anchor is still alive in the session ("tracked" for
    /// plain world anchors == present in the current frame).
    public func worldAnchorExists(id: UUID) -> Bool {
        worldAnchorPosition(id: id) != nil
    }

    /// Reference RGB of the current camera image as JPEG, for the
    /// raw-capture recorder (developer mode only). Reads the live
    /// `ARFrame.capturedImage` (YCbCr CVPixelBuffer) and encodes it via
    /// CoreImage — no UIKit, no orientation rewrite (stored sensor-native,
    /// matching the depth buffers). Returns nil when no frame is available
    /// or encoding fails. Cheap enough to call once per burst.
    public func currentCameraImageJPEG(quality: Double = 0.8) -> Data? {
        guard let pixelBuffer = session.currentFrame?.capturedImage else { return nil }
        let ci = CIImage(cvPixelBuffer: pixelBuffer)
        let ctx = CIContext(options: nil)
        let space = CGColorSpace(name: CGColorSpace.sRGB) ?? CGColorSpaceCreateDeviceRGB()
        let qualityKey = CIImageRepresentationOption(
            rawValue: kCGImageDestinationLossyCompressionQuality as String)
        return ctx.jpegRepresentation(
            of: ci,
            colorSpace: space,
            options: [qualityKey: quality])
    }

    /// True if every frame observed since the last `run()` reported
    /// `.normal` tracking — used by §7.2 height measurement guard.
    public var trackingStayedNormal: Bool { trackedStateWasAlwaysNormal }

    /// Begin a fresh per-window tracking watch. Call at the start of a
    /// bounded capture; `trackingStayedNormalSinceWatch` then reflects only
    /// frames observed since this call.
    public func beginTrackingWatch() { trackedNormalSinceWatch = (trackingStatus == .normal) }

    /// True if every frame since the last `beginTrackingWatch()` reported
    /// `.normal` tracking — the per-sweep counterpart of `trackingStayedNormal`.
    public var trackingStayedNormalSinceWatch: Bool { trackedNormalSinceWatch }

    // MARK: ARSessionDelegate

    public nonisolated func session(_ session: ARSession, didUpdate frame: ARFrame) {
        // Per-screen stream gate: the depth-map conversion below copies
        // ~50 k floats per frame — screens that never read depth
        // (sampling plot, distance) skip it entirely, and the VIO
        // feature-point array copy is skipped unless the DBH AR-motion
        // method is on screen.
        let gate = streamGate.withLock { $0 }
        let converted = gate.depth ? Self.convert(frame: frame) : nil
        let status = Self.mapTrackingState(frame.camera.trackingState)
        let t = frame.camera.transform
        let camPos = SIMD3<Float>(t.columns.3.x, t.columns.3.y, t.columns.3.z)
        let features = gate.features ? frame.rawFeaturePoints?.points : nil
        Task { @MainActor [weak self] in
            guard let self else { return }
            if status != .normal {
                self.trackedStateWasAlwaysNormal = false
                self.trackedNormalSinceWatch = false
            }
            self.trackingStatus = status
            self.currentCameraWorldPosition = camPos
            if let features { self.latestRawFeaturePoints = features }
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

        // Scale the camera intrinsics from the full-resolution captured
        // image down to the (downsampled) depth-map resolution.
        // `frame.camera.intrinsics` is calibrated for `imageResolution`
        // (~1920 wide), but the depth map is only `width`×`height` (~256
        // wide). Every depth consumer (BackProjection.worldXZ, the DBH
        // chord/silhouette diameter `width·depth/fx`, calibration) reads
        // these as depth-map-space values — so passing the full-res fx/cx
        // unscaled made DBH read ≈ 256/1920 ≈ 1/7.5 of the true diameter
        // (a 30 cm trunk showed up as ~4–5 cm, regardless of guide axis).
        let imgRes = frame.camera.imageResolution
        let sx = imgRes.width  > 0 ? Float(width)  / Float(imgRes.width)  : 1
        let sy = imgRes.height > 0 ? Float(height) / Float(imgRes.height) : 1
        var K = frame.camera.intrinsics
        K.columns.0.x *= sx   // fx
        K.columns.1.y *= sy   // fy
        K.columns.2.x *= sx   // cx
        K.columns.2.y *= sy   // cy

        return ARDepthFrame(
            width: width,
            height: height,
            depth: depth,
            confidence: confidence,
            intrinsics: K,
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

    public static let shared = ARKitSessionManager()

    @Published public private(set) var trackingStatus: TrackingStatus = .notAvailable
    @Published public private(set) var latestDepthFrame: ARDepthFrame?
    @Published public private(set) var isRunning = false
    @Published public private(set) var currentCameraWorldPosition: SIMD3<Float>?
    @Published public private(set) var latestRawFeaturePoints: [SIMD3<Float>]?

    public static var supportsLiDAR: Bool { false }

    public init() {}
    public func run() {}
    public func pause() {}
    public func attach(client id: UUID, configuration: ARScreenConfiguration) {}
    public func detach(client id: UUID) {}
    @discardableResult
    public func addWorldAnchor(at worldPosition: SIMD3<Float>,
                               name: String = "forestix.worldAnchor") -> UUID? { nil }
    public func removeWorldAnchor(id: UUID) {}
    public func worldAnchorPosition(id: UUID) -> SIMD3<Float>? { nil }
    public func worldAnchorExists(id: UUID) -> Bool { false }
    public func currentCameraImageJPEG(quality: Double = 0.8) -> Data? { nil }
    public var trackingStayedNormal: Bool { false }
    public func beginTrackingWatch() {}
    public var trackingStayedNormalSinceWatch: Bool { false }
}

#endif
