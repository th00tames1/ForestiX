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
/// VIEW pixels → DEPTH pixels, as a 2D affine.
///
/// WHY THIS EXISTS (field report, commit a3a2a91 follow-up). The camera
/// image is aspect-FILLED into the ARView: a 4:3 sensor in a ~19.5:9
/// portrait view shows only about 60 % of the image's short axis. A centred
/// crop preserves the CENTRE, which is why every depth consumer that only
/// reads the middle pixel (the auto chord path's `cx = width/2`) has always
/// been correct without a transform. It does NOT preserve a SPAN — and the
/// ADJUST bracket is a span, taken from the screen and previously applied to
/// the depth grid as if the two were the same scale. That over-read every
/// bracketed diameter by the crop factor, silently, with a green tier,
/// because a fixed bracket agrees frame-to-frame and so carries a tiny σ.
///
/// Mirror of the Android `ArDepthFrame.depthFromViewAffine`, which has
/// always built this from ARCore's `transformCoordinates2d`. Here it comes
/// from `ARFrame.displayTransform(for:viewportSize:)` inverted.
public struct DepthViewMapping: Sendable, Equatable {
    /// Row-major [a b tx ; c d ty].
    public let a: Double, b: Double, tx: Double
    public let c: Double, d: Double, ty: Double

    public init(a: Double, b: Double, tx: Double,
                c: Double, d: Double, ty: Double) {
        self.a = a; self.b = b; self.tx = tx
        self.c = c; self.d = d; self.ty = ty
    }

    public func viewToDepth(x: Double, y: Double) -> SIMD2<Double> {
        SIMD2(a * x + b * y + tx, c * x + d * y + ty)
    }

    /// The six coefficients in the raw-capture manifest's `view_to_depth`
    /// order — the same order Android writes, so a pooled corpus reads one
    /// way.
    public var flattened: [Double] { [a, b, tx, c, d, ty] }
}

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
    /// VIEW px → DEPTH px for the viewport this frame was displayed in.
    ///
    /// nil when the viewport was never reported (no AR view on screen yet)
    /// or the transform was degenerate. Consumers that need it MUST FAIL
    /// CLOSED — a bracket with no mapping is not a measurement, and there is
    /// no safe default: the identity is exactly the wrong answer this field
    /// exists to stop.
    public let viewMapping: DepthViewMapping?

    public init(
        width: Int,
        height: Int,
        depth: [Float],
        confidence: [UInt8],
        intrinsics: simd_float3x3,
        cameraPoseWorld: simd_float4x4,
        timestamp: TimeInterval,
        viewMapping: DepthViewMapping? = nil
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
        self.viewMapping = viewMapping
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
    ///
    /// nil WHILE TRACKING IS `.notAvailable`. ARKit still hands out a camera
    /// transform then, but it is not a position in the session's world frame
    /// — it collapses toward the identity — and publishing it made every
    /// reader compute a confident distance from a place the device never was.
    /// That is the height walk-off's "walked distance goes back to zero"
    /// (field round 9): the anchoring pose sits near the world origin, so the
    /// displacement from an identity transform to it is ~0. `.limited` is
    /// deliberately NOT gated: ARKit keeps a continuous, usable pose through
    /// `.limited(.excessiveMotion)` and `.limited(.insufficientFeatures)`,
    /// which is most of a real walk-off under canopy, and treating that as
    /// "no position" would refuse nearly every honest measurement (it is the
    /// same mistake the estimator's old `.limited` latch made).
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
    ///
    /// `lastDepthConversion` is the ARFrame timestamp of the last frame we
    /// actually converted — see `depthMinIntervalSec`.
    private struct StreamGate {
        var depth: Bool
        var features: Bool
        var lastDepthConversion: TimeInterval = 0
    }
    private let streamGate = OSAllocatedUnfairLock(
        initialState: StreamGate(depth: true, features: true))

    /// Floor on the interval between depth conversions, in ARFrame time.
    ///
    /// FIELD REPORT 9 — heavy lag and freezes on the scan screens. ARKit
    /// delivers 60 frames a second and `convert` was running on every one of
    /// them, but NOTHING consumes depth at 60 Hz: the DBH live preview is
    /// throttled to 10 Hz, the height walk hint to 10 Hz, and a capture
    /// sub-sample closes on 0.5 s of frames with a floor of 5. At 30 Hz the
    /// burst still sees ~15 frames per sub-sample window — three times its
    /// floor — while the per-frame buffer copy, the two array allocations
    /// and the main-actor publish all halve.
    ///
    /// This is a RATE floor, never a value substitution: a frame that is
    /// skipped is not published at all, so no consumer ever reads an
    /// invented or interpolated depth.
    private static let depthMinIntervalSec: TimeInterval = 1.0 / 30.0

    /// The AR view's size and interface orientation, reported by
    /// `ARCameraView` whenever its bounds change. Read by the (nonisolated)
    /// session delegate to build each frame's view→depth mapping, so it
    /// lives behind the same lock as the stream gate.
    ///
    /// Starts EMPTY on purpose. Until a real AR view has reported its
    /// bounds there is no honest mapping, and `DepthViewMapping` has no
    /// safe default — see the note on `ARDepthFrame.viewMapping`.
    private struct Viewport { var size: CGSize; var orientation: UIInterfaceOrientation }
    private let viewport = OSAllocatedUnfairLock(
        initialState: Viewport(size: .zero, orientation: .portrait))

    /// Called by `ARCameraView` on layout. Cheap and idempotent.
    public nonisolated func reportViewport(size: CGSize,
                                           orientation: UIInterfaceOrientation) {
        guard size.width > 1, size.height > 1 else { return }
        viewport.withLock { $0 = Viewport(size: size, orientation: orientation) }
    }

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

    /// Live world position of an anchor, but ONLY while ARKit's camera
    /// tracking is `.normal` — otherwise nil.
    ///
    /// An anchor transform is a coordinate in the world frame ARKit held
    /// when it was read, and `.limited` (relocalizing, excessive motion,
    /// insufficient features) is precisely when that frame is being
    /// re-jigged underneath it. Reading through it produces a point that
    /// looks fine and is metres from the physical spot, which is how the
    /// sampling plot ended up drawn beside a cruiser standing outside it.
    /// Callers that draw world geometry — the plot ring and pillar — use
    /// this one and show nothing when it refuses. `worldAnchorPosition`
    /// stays for callers that only need "is this anchor still here?".
    public func trackedWorldAnchorPosition(id: UUID) -> SIMD3<Float>? {
        guard let frame = session.currentFrame,
              case .normal = frame.camera.trackingState,
              let anchor = frame.anchors.first(where: { $0.identifier == id })
        else { return nil }
        let c = anchor.transform.columns.3
        return SIMD3<Float>(c.x, c.y, c.z)
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
        // method is on screen. Screens that DO read depth get it at
        // `depthMinIntervalSec`, not at ARKit's 60 Hz.
        let gate = streamGate.withLock { g -> (depth: Bool, features: Bool) in
            let convert = g.depth
                && frame.timestamp - g.lastDepthConversion >= Self.depthMinIntervalSec
            if convert { g.lastDepthConversion = frame.timestamp }
            return (convert, g.features)
        }
        let vp = viewport.withLock { $0 }
        let converted = gate.depth
            ? Self.convert(frame: frame,
                           viewportSize: vp.size,
                           orientation: vp.orientation)
            : nil
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
            self.currentCameraWorldPosition = status == .notAvailable ? nil : camPos
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

    /// VIEW px → DEPTH px for this frame, or nil when it cannot be built
    /// honestly.
    ///
    /// `displayTransform(for:viewportSize:)` maps NORMALISED IMAGE space to
    /// NORMALISED VIEW space, so the mapping wanted here is its inverse,
    /// pre-scaled by the viewport and post-scaled by the depth grid:
    ///
    ///     view px --(1/viewport)--> norm view --(dt⁻¹)--> norm image
    ///             --(× depth size)--> depth px
    ///
    /// The depth map and the captured image share an orientation and an
    /// aspect, so normalised image coordinates land on the depth grid by a
    /// plain scale.
    private nonisolated static func viewMapping(
        frame: ARFrame,
        viewportSize: CGSize,
        orientation: UIInterfaceOrientation,
        depthWidth: Int,
        depthHeight: Int
    ) -> DepthViewMapping? {
        guard viewportSize.width > 1, viewportSize.height > 1,
              depthWidth > 0, depthHeight > 0 else { return nil }
        let dt = frame.displayTransform(for: orientation,
                                        viewportSize: viewportSize)
        // A singular transform would silently collapse the bracket to zero
        // width; refuse it rather than publish a mapping that cannot be
        // inverted.
        guard abs(dt.a * dt.d - dt.b * dt.c) > 1e-9 else { return nil }
        let m = CGAffineTransform(scaleX: 1 / viewportSize.width,
                                  y: 1 / viewportSize.height)
            .concatenating(dt.inverted())
            .concatenating(CGAffineTransform(scaleX: CGFloat(depthWidth),
                                             y: CGFloat(depthHeight)))
        // b AND c CROSS. CGAffineTransform applies x' = a·x + c·y + tx and
        // y' = b·x + d·y + ty, while `DepthViewMapping.viewToDepth` reads
        // its second coefficient as the y term of the FIRST row. Extracting
        // them in field order transposes the linear part — and in portrait
        // the display transform is anti-diagonal, so a transpose negates it
        // and throws every mapped point clean off the depth grid. Nothing
        // measures through this today, but it is written into every
        // raw-capture manifest as `view_to_depth`, which is the artefact
        // kept precisely so the screen-space-versus-depth-space question can
        // be settled later against a tape. A transposed affine would settle
        // it wrong.
        let coeffs = [m.a, m.c, m.tx, m.b, m.d, m.ty].map(Double.init)
        guard coeffs.allSatisfy({ $0.isFinite }) else { return nil }
        return DepthViewMapping(a: coeffs[0], b: coeffs[1], tx: coeffs[2],
                                c: coeffs[3], d: coeffs[4], ty: coeffs[5])
    }

    private nonisolated static func convert(
        frame: ARFrame,
        viewportSize: CGSize = .zero,
        orientation: UIInterfaceOrientation = .portrait
    ) -> ARDepthFrame? {
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

        // Row-wise memcpy, not element-wise assignment (field report 9).
        // Both buffers are tightly packed within a row, so the only reason
        // to walk pixels was the row stride — and the strided rows can be
        // bulk-copied one at a time. The old scalar loops ran ~98 k bounds-
        // checked subscript writes per frame on the main sensor path.
        var depth = [Float](repeating: 0, count: width * height)
        let depthRowBytes = width * MemoryLayout<Float>.size
        depth.withUnsafeMutableBytes { dst in
            guard let dstBase = dst.baseAddress else { return }
            if depthStride == depthRowBytes {
                memcpy(dstBase, depthBase, depthRowBytes * height)
            } else {
                for row in 0..<height {
                    memcpy(dstBase.advanced(by: row * depthRowBytes),
                           depthBase.advanced(by: row * depthStride),
                           depthRowBytes)
                }
            }
        }

        var confidence = [UInt8](repeating: 0, count: width * height)
        if let cm = confidenceMap {
            CVPixelBufferLockBaseAddress(cm, .readOnly)
            defer { CVPixelBufferUnlockBaseAddress(cm, .readOnly) }
            if let cBase = CVPixelBufferGetBaseAddress(cm) {
                let cStride = CVPixelBufferGetBytesPerRow(cm)
                confidence.withUnsafeMutableBytes { dst in
                    guard let dstBase = dst.baseAddress else { return }
                    if cStride == width {
                        memcpy(dstBase, cBase, width * height)
                    } else {
                        for row in 0..<height {
                            memcpy(dstBase.advanced(by: row * width),
                                   cBase.advanced(by: row * cStride),
                                   width)
                        }
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
            timestamp: frame.timestamp,
            viewMapping: viewMapping(frame: frame,
                                     viewportSize: viewportSize,
                                     orientation: orientation,
                                     depthWidth: width,
                                     depthHeight: height)
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
    public func trackedWorldAnchorPosition(id: UUID) -> SIMD3<Float>? { nil }
    public func currentCameraImageJPEG(quality: Double = 0.8) -> Data? { nil }
    public var trackingStayedNormal: Bool { false }
    public func beginTrackingWatch() {}
    public var trackingStayedNormalSinceWatch: Bool { false }
}

#endif
