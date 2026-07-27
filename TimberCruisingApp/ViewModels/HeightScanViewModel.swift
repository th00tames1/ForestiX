// Spec §4.4 HeightScan state machine + §5.3 screen contract. Records the
// anchor pose (tree base), walks the cruiser out while streaming d_h
// live, captures α_top and α_base with ±200 ms median pitch (REQ-HGT-004),
// then hands the tuple to HeightEstimator.
//
// Cross-platform: on iOS the view model drives a real ARKit session +
// CMMotionManager; on macOS (for swift test / previews) both are no-op
// stubs and the state machine is exercised via `preview(state:result:)`
// + direct injection through `captureTop/captureBase(pitchRad:at:)`
// overloads that bypass the IMU buffer.

import Foundation
import Combine
import simd
import Common
import Models
import Sensors
import AR

@MainActor
public final class HeightScanViewModel: ObservableObject {

    // MARK: - §4.4 HeightState

    public enum State: Equatable, Sendable {
        case idle
        case anchorSet
        case walking
        case aimTopArmed
        case aimTopCaptured
        case aimBaseArmed
        case computed
        case accepted
        case rejected
        case manualEntry
    }

    // MARK: - Published surface

    @Published public private(set) var state: State = .idle
    @Published public private(set) var result: HeightResult?
    /// Bumped once per height computation so the screen can fire research
    /// logging exactly when a new measurement is committed.
    @Published public private(set) var resultGeneration: Int = 0

    /// Live horizontal distance from the anchor to the current standing
    /// pose (REQ-HGT-003). Updates at the ARKit frame rate.
    @Published public private(set) var dhMeters: Float = 0

    /// Camera→anchor horizontal distance frozen at the moment the anchor
    /// was captured — the walk panel's "Initial dist" line. Distinct from
    /// `dhMeters`, which keeps updating as the cruiser walks.
    @Published public private(set) var initialDistanceM: Float = 0

    /// Horizontal displacement of the camera since the anchor was
    /// captured — the walk panel's "Walked back" line. Starts at 0.00 and
    /// grows as the cruiser walks; the old panel showed `dhMeters` here,
    /// which initialises at the full camera→anchor distance and confused
    /// users who hadn't moved yet.
    @Published public private(set) var walkedBackMeters: Float = 0

    /// "Move back/forward X m" hint (REQ-HGT-003). Positive → walk back;
    /// negative → walk forward; zero → inside the sweet-spot band.
    @Published public private(set) var walkHintMeters: Float = 0

    /// Set when the cruiser tapped Anchor Here but neither the LiDAR
    /// mesh nor the plane raycast returned a world hit — happens when
    /// the device is still building scene mesh, the cruiser is too
    /// close to the trunk for an estimated plane to fit, or AR tracking
    /// hasn't fully initialised. Replaces the silent fall-back to the
    /// camera position that previously biased d_h by the cruiser-to-
    /// tree offset. Cleared once a real anchor is captured or `retake()`
    /// runs.
    @Published public private(set) var anchorFailureReason: String?

    /// Walk-back geometry target. Default 30 m per Phase 3 Decision Q4.
    /// 0.6 · H_expected ≤ d_h ≤ 1.0 · H_expected gives the sweet spot.
    @Published public var expectedHeightM: Float = 30

    /// Anchor range gate — the trunk anchor may only be captured while
    /// the anchor raycast (camera→hit) distance is at most this. Beyond
    /// it the LiDAR mesh / estimated-plane depth is unreliable, so the
    /// capture "+" goes inert and the status line asks the cruiser to
    /// move closer. Same value on Android.
    public static let anchorMaxRangeM: Float = 4.0

    /// Fallback for REQ-HGT-006. Non-empty only in `.manualEntry`.
    @Published public var manualHeightM: String = ""

    // MARK: - Dependencies

    public let session: ARKitSessionManager
    public let pitchBuffer: IMUPitchBuffer
    public let motion: IMUMotionService
    public let calibration: ProjectCalibration

    // MARK: - Captured state

    private var anchorPointWorld: SIMD3<Float>?
    private var alphaTopRad: Float?
    private var alphaBaseRad: Float?

    /// Camera world position frozen at the moment the anchor was
    /// captured — reference point for the "Walked back" displacement.
    private var cameraPositionAtAnchor: SIMD3<Float>?

    /// Standing pose at aim-top tap — §7.2 uses the same standing point
    /// for both taps, so we lock it on the first tap and reuse it.
    private var standingPointWorldAtAimTop: SIMD3<Float>?

    /// World hit point for the Aim Top / Aim Base taps, if the host
    /// supplied one (e.g. from a screen-centre raycast). Purely for
    /// marker visualisation — height math still runs on α_top / α_base
    /// + d_h, which is the spec's authoritative input.
    private var topAimedWorld: SIMD3<Float>?
    private var baseAimedWorld: SIMD3<Float>?

    /// Last sample count folded into α_top / α_base for diagnostics
    /// (REQ-HGT-004: "sample count logged").
    @Published public private(set) var alphaTopSampleCount: Int = 0
    @Published public private(set) var alphaBaseSampleCount: Int = 0

    /// World-anchored markers rendered by `ARCameraView` so the cruiser
    /// can see where the anchor / top / base points landed and find
    /// them again after panning the camera away. Rebuilt whenever one
    /// of the three reference points is set or cleared.
    @Published public private(set) var sceneMarkers: [ARSceneMarker] = []

    // Stable ids for each of the three marker roles so the RealityKit
    // anchors aren't torn down and rebuilt on every state transition.
    // Force-unwrap is avoided so a future typo can't crash the scan —
    // the `?? UUID()` fallback degrades to per-frame anchor churn
    // instead of an abort.
    private static let anchorMarkerId =
        UUID(uuidString: "00000000-A0A0-0000-0000-000000000001") ?? UUID()
    private static let topMarkerId =
        UUID(uuidString: "00000000-A0A0-0000-0000-000000000002") ?? UUID()
    private static let baseMarkerId =
        UUID(uuidString: "00000000-A0A0-0000-0000-000000000003") ?? UUID()

    private var depthCancellable: AnyCancellable?

    // MARK: - Raw-capture recording (developer-mode research)

    /// When true (screen sets it from `developerMode && rawCaptureEnabled`),
    /// every height `compute()` — accepted or not — serializes a raw-capture
    /// bundle (anchor + base/top poses + angles + d_h + 5 Hz pose track).
    public var rawCaptureEnabled: Bool = false
    public var rawCaptureContext: RawCaptureContext = .quick
    public var rawCaptureGPS: RawCaptureGPS?
    /// Minted synchronously when the compute finishes, BEFORE the detached
    /// writer runs — so a truth typed and Accepted immediately always has a
    /// bundle to attach to (see DBHScanViewModel for the same fix).
    @Published public private(set) var lastRecordedBundleID: String?
    /// Saved / NOT-saved outcome of the most recent capture attempt.
    @Published public private(set) var lastCaptureOutcome: RawCaptureOutcome?

    private var recordAnchorPose = matrix_identity_float4x4
    private var recordAnchorHitType = "unknown"
    private var recordAnchorTime: TimeInterval = 0
    private var recordBasePose = matrix_identity_float4x4
    private var recordTopPose = matrix_identity_float4x4
    private var recordPoseSamples: [(tMs: Int, pose: simd_float4x4)] = []
    private var lastPoseSampleTime: TimeInterval = 0

    // Schema-2 aim frames: the depth frame + reference RGB retained at the
    // base-aim and top-aim taps (developer mode only), so a height bundle can
    // later re-run depth-based height algorithms, not just tangent.
    private var recordBaseFrame: ARDepthFrame?
    private var recordBaseJPEG: Data?
    private var recordTopFrame: ARDepthFrame?
    private var recordTopJPEG: Data?

    // MARK: - Construction

    /// Shared-session client token — one per view-model instance so a
    /// mid-transition overlap (DBH → Height full-measurement chain)
    /// can never pause the session under the incoming screen.
    private let arClientID = UUID()

    public init(
        calibration: ProjectCalibration,
        session: ARKitSessionManager? = nil,
        pitchBuffer: IMUPitchBuffer? = nil,
        motion: IMUMotionService? = nil
    ) {
        self.calibration = calibration
        // Default to the APP-SHARED session (field round 8): one ARKit
        // world frame across all measure screens, so the sampling-plot
        // anchor placed elsewhere renders here too.
        self.session = session ?? .shared
        let buffer = pitchBuffer ?? IMUPitchBuffer()
        self.pitchBuffer = buffer
        self.motion = motion ?? IMUMotionService(buffer: buffer)
    }

    // MARK: - Lifecycle

    public func onAppear() {
        if state == .idle { /* waiting for anchor tap */ }
        // Height keeps the depth stream (the walking readout reads the
        // depth-frame camera pose) + mesh raycasts; no VIO feature
        // stream. Applied with no reset options — anchors survive.
        session.attach(client: arClientID, configuration: .heightScan)
        motion.start()
        subscribeToDepth()
    }

    public func onDisappear() {
        motion.stop()
        session.detach(client: arClientID)
        depthCancellable?.cancel()
        depthCancellable = nil
    }

    private func subscribeToDepth() {
        depthCancellable = session.$latestDepthFrame
            .compactMap { $0 }
            .sink { [weak self] frame in
                guard let self else { return }
                self.collectPoseSampleIfNeeded(frame)
                guard self.state == .walking else { return }
                let pose = frame.cameraPoseWorld
                let standing = SIMD3<Float>(pose.columns.3.x,
                                            pose.columns.3.y,
                                            pose.columns.3.z)
                self.updateLiveHint(standingPointWorld: standing)
            }
    }

    /// Accumulate 5 Hz camera poses from anchor to compute (developer mode
    /// only), so a future d_h derivation can be replayed from the walk track.
    private func collectPoseSampleIfNeeded(_ frame: ARDepthFrame) {
        guard rawCaptureEnabled, anchorPointWorld != nil else { return }
        switch state {
        case .walking, .aimBaseArmed, .aimTopArmed, .aimTopCaptured: break
        default: return
        }
        guard recordPoseSamples.count < 600 else { return }        // ~2 min cap
        guard frame.timestamp - lastPoseSampleTime >= 0.2 else { return }
        lastPoseSampleTime = frame.timestamp
        let tMs = Int((frame.timestamp - recordAnchorTime) * 1000)
        recordPoseSamples.append((tMs: tMs, pose: frame.cameraPoseWorld))
    }

    /// Retain the base-aim depth frame + reference RGB for the raw-capture
    /// bundle (developer mode only). Grabbed at the base tap so the stored
    /// aim frame matches the pitch the tangent method used.
    private func retainBaseAimFrame() {
        guard rawCaptureEnabled else { return }
        recordBaseFrame = session.latestDepthFrame
        recordBaseJPEG = session.currentCameraImageJPEG()
    }

    /// Retain the top-aim depth frame + reference RGB (developer mode only).
    private func retainTopAimFrame() {
        guard rawCaptureEnabled else { return }
        recordTopFrame = session.latestDepthFrame
        recordTopJPEG = session.currentCameraImageJPEG()
    }

    /// Read the current ARKit camera translation in world space. Falls
    /// back through two sources in order:
    ///   1. `session.currentCameraWorldPosition` — published on every
    ///      ARFrame regardless of whether LiDAR is available, so this
    ///      works on non-LiDAR devices too.
    ///   2. `session.latestDepthFrame?.cameraPoseWorld` — secondary path
    ///      kept for preview / test sessions that only exercise depth.
    /// Returns nil only before any frame has arrived.
    private func currentCameraTranslation() -> SIMD3<Float>? {
        if let p = session.currentCameraWorldPosition { return p }
        if let frame = session.latestDepthFrame {
            let c = frame.cameraPoseWorld.columns.3
            return SIMD3<Float>(c.x, c.y, c.z)
        }
        return nil
    }

    // MARK: - §4.4 transitions

    /// Step (a) — user touches phone to tree base and taps "Anchor Here".
    /// Caller supplies the current ARKit camera position so the anchor
    /// is placed in world frame.
    public func anchorHere(standingPointWorld: SIMD3<Float>) {
        anchorHere(anchorPointWorld: standingPointWorld,
                   standingPointWorld: standingPointWorld)
    }

    /// Richer overload that separates the tree-base anchor from the
    /// cruiser's current standing pose. Use this when the host has a
    /// screen-centre raycast — pass the hit world point as the anchor
    /// and the live camera pose as the standing point. The height
    /// algorithm only cares about the horizontal distance between them.
    public func anchorHere(anchorPointWorld: SIMD3<Float>,
                           standingPointWorld: SIMD3<Float>) {
        self.anchorPointWorld = anchorPointWorld
        alphaTopRad = nil
        alphaBaseRad = nil
        standingPointWorldAtAimTop = nil
        topAimedWorld = nil
        baseAimedWorld = nil
        anchorFailureReason = nil
        // Freeze the walk-panel reference values: "Initial dist" is the
        // camera→anchor horizontal distance at this instant, and the
        // camera position becomes the origin the "Walked back"
        // displacement is measured from (so it starts at 0.00).
        cameraPositionAtAnchor = standingPointWorld
        initialDistanceM = horizontalDistance(from: standingPointWorld,
                                              to: anchorPointWorld)
        walkedBackMeters = 0
        // Raw-capture arm: reset the walk-track and freeze the anchor pose /
        // clock reference (developer mode only).
        recordPoseSamples.removeAll(keepingCapacity: true)
        lastPoseSampleTime = 0
        recordBaseFrame = nil; recordBaseJPEG = nil
        recordTopFrame = nil; recordTopJPEG = nil
        recordAnchorPose = session.latestDepthFrame?.cameraPoseWorld ?? matrix_identity_float4x4
        recordAnchorTime = session.latestDepthFrame?.timestamp ?? 0
        state = .anchorSet
        state = .walking
        updateLiveHint(standingPointWorld: standingPointWorld)
        rebuildSceneMarkers()
    }

    /// Step (b) — called on every ARKit frame while the user walks back
    /// to refresh `dhMeters` and `walkHintMeters`.
    public func updateLiveHint(standingPointWorld: SIMD3<Float>) {
        guard let anchor = anchorPointWorld else { return }
        let dx = standingPointWorld.x - anchor.x
        let dz = standingPointWorld.z - anchor.z
        dhMeters = sqrt(dx * dx + dz * dz)
        walkHintMeters = computeWalkHint(dh: dhMeters,
                                         expectedH: expectedHeightM)
        // Horizontal, like every other distance in the walk panel, so
        // Initial dist + Walked back ≈ Total distance on a straight
        // walk-off.
        if let origin = cameraPositionAtAnchor {
            walkedBackMeters = horizontalDistance(from: standingPointWorld,
                                                  to: origin)
        }
    }

    /// User decides they've walked far enough and tapped Continue.
    /// Base-first: from eye level you tilt DOWN to the base, then sweep UP
    /// to the top in one continuous motion — less device rotation than
    /// top-then-base, so we arm the BASE aim first.
    public func continueToAimTop() {
        guard state == .walking, anchorPointWorld != nil else { return }
        state = .aimBaseArmed
    }

    /// Step (c) — Aim BASE tap (FIRST). α_base = median pitch over ±200 ms.
    /// Locks the standing pose here (both angles must be measured from the
    /// same spot — see §7.2). `aimedAtWorld` seeds the base marker.
    public func captureBase(at tapTime: TimeInterval,
                            standingPointWorld: SIMD3<Float>,
                            aimedAtWorld: SIMD3<Float>? = nil) {
        guard state == .aimBaseArmed, anchorPointWorld != nil else { return }
        guard let median = resilientMedianPitch(tapTime: tapTime) else { return }
        alphaBaseRad = Float(median)
        alphaBaseSampleCount = pitchBuffer.sampleCount(centeredOn: tapTime)
        standingPointWorldAtAimTop = standingPointWorld
        baseAimedWorld = aimedAtWorld
        recordBasePose = session.latestDepthFrame?.cameraPoseWorld ?? matrix_identity_float4x4
        retainBaseAimFrame()
        state = .aimTopArmed
        rebuildSceneMarkers()
    }

    /// Step (d) — Aim TOP tap (SECOND). α_top = median pitch over ±200 ms.
    /// Triggers estimation. `aimedAtWorld` seeds the top marker.
    public func captureTop(at tapTime: TimeInterval,
                           aimedAtWorld: SIMD3<Float>? = nil) {
        guard state == .aimTopArmed, anchorPointWorld != nil,
              alphaBaseRad != nil, standingPointWorldAtAimTop != nil
        else { return }
        guard let median = resilientMedianPitch(tapTime: tapTime) else { return }
        alphaTopRad = Float(median)
        alphaTopSampleCount = pitchBuffer.sampleCount(centeredOn: tapTime)
        topAimedWorld = aimedAtWorld
        recordTopPose = session.latestDepthFrame?.cameraPoseWorld ?? matrix_identity_float4x4
        retainTopAimFrame()
        compute()
        rebuildSceneMarkers()
    }

    /// Tries the strict 400 ms window first (matches the spec), then
    /// falls back to a wider 1200 ms window, then to the most recent
    /// sample regardless of age. Returns nil only if the buffer is
    /// completely empty — which means the IMU never delivered anything,
    /// at which point we genuinely can't compute a height.
    private func resilientMedianPitch(tapTime: TimeInterval) -> Double? {
        if let m = pitchBuffer.medianPitch(centeredOn: tapTime) { return m }
        if let m = pitchBuffer.medianPitch(centeredOn: tapTime,
                                           windowMs: 1200) { return m }
        return pitchBuffer.mostRecentPitch()
    }

    /// Button-handler entry for the Anchor Here tap. The cruiser stands
    /// 1–3 m from the tree, aims the screen-centre crosshair at the
    /// trunk at EYE LEVEL (≈ chest height on the trunk), and taps. The
    /// LiDAR mesh raycast (`screenCenterHit`) returns the 3D world
    /// position of that trunk point — that becomes the anchor. The
    /// cruiser then walks back further to do Aim Top / Aim Base.
    ///
    /// Why eye level (not the trunk base):
    ///   • A horizontal ray hits the trunk surface near-perpendicular,
    ///     giving the LiDAR its highest depth confidence.
    ///   • No risk of the ray skimming past the trunk and hitting the
    ///     ground / leaf litter / undergrowth instead.
    ///   • Mesh density is consistently good on the upright stem;
    ///     trunk-ground transitions are noisier (vegetation, buttress).
    ///   • IMU pitch noise (~0.3°) translates to vertical-only error
    ///     for a level ray, so d_h is unaffected.
    /// Only the anchor's XZ is used in d_h — the Y is dropped — so
    /// aiming at any height on the trunk gives the same horizontal
    /// distance once the cruiser walks back. Eye level is just the
    /// cleanest LiDAR target.
    ///
    /// Why this beats the older "touch phone to bark" interaction:
    ///   • No bending. Cruiser stays standing for the entire flow.
    ///   • Works on slope, in vegetation, and around buttresses where
    ///     physically reaching the trunk is awkward.
    ///   • Uses the LiDAR data we already have. The raycast is robust
    ///     at 1–3 m distance (the previous concern about the ray
    ///     hitting the back of the trunk was a point-blank-only issue).
    ///
    /// Anchor range gate: the tap is an inert no-op — matching the other
    /// pre-armed inert "+" states — whenever the raycast missed (no LiDAR
    /// mesh / plane in that direction yet, sky, tracking not ready) or
    /// the hit is beyond `anchorMaxRangeM`, where depth is unreliable.
    /// The anchor-stage status line continuously shows "Move closer —
    /// anchor within 4 m of the trunk." in exactly those conditions, so
    /// no banner fires here. Refusing the anchor is much better than
    /// silently substituting the camera position, which would leave the
    /// trunk-to-cruiser offset baked into d_h as a systematic bias.
    public func anchorHereNow(screenCenterHit: SIMD3<Float>? = nil,
                              hitType: String = "unknown") {
        guard let cam = currentCameraTranslation(),
              let anchor = screenCenterHit,
              simd_distance(cam, anchor) <= Self.anchorMaxRangeM
        else { return }
        recordAnchorHitType = hitType
        anchorHere(anchorPointWorld: anchor,
                   standingPointWorld: cam)
    }

    /// Lets the host clear `anchorFailureReason` from a banner-dismiss
    /// gesture without driving a full `retake()`.
    public func clearAnchorFailure() {
        anchorFailureReason = nil
    }

    /// Button-handler entry for Aim Top. Uses the current camera pose +
    /// the current timestamp to drive the IMU median. `screenCenterHit`,
    /// when non-nil, seeds the top-marker position.
    ///
    /// Timestamp MUST match the CMDeviceMotion clock — those samples are
    /// stamped with `ProcessInfo.systemUptime` (seconds since boot), NOT
    /// `Date().timeIntervalSinceReferenceDate` (seconds since 2001).
    ///
    /// Bails with a banner if the ARKit camera pose isn't available.
    /// The previous `?? .zero` fallback would silently set the standing
    /// point to the world origin — anchor.xz could be tens of metres
    /// from the origin in any non-fresh session, which inflated d_h by
    /// 10–100× and produced absurd heights (e.g. a desk at 2 m showing
    /// up as 100 m+) instead of an honest "tracking not ready" message.
    public func captureTopNow(screenCenterHit: SIMD3<Float>? = nil) {
        captureTop(at: nowForPitchBuffer(),
                   aimedAtWorld: screenCenterHit)
    }

    /// Button-handler entry for Aim Base (the FIRST aim). Captures the
    /// standing pose used for both angles. Same clock convention as
    /// `captureTopNow()`.
    public func captureBaseNow(screenCenterHit: SIMD3<Float>? = nil) {
        guard let p = currentCameraTranslation() else {
            anchorFailureReason =
                "The camera hasn't got its bearings yet — hold still for a second, then tap + again."
            return
        }
        captureBase(at: nowForPitchBuffer(),
                    standingPointWorld: p,
                    aimedAtWorld: screenCenterHit)
    }

    /// Monotonic clock matching `CMDeviceMotion.timestamp`. Keep one
    /// source so every tap and every sample agree.
    private func nowForPitchBuffer() -> TimeInterval {
        ProcessInfo.processInfo.systemUptime
    }

    /// Test/preview hook: push α_base directly (FIRST), skipping the IMU.
    public func captureBaseDirect(alphaBaseRad: Float,
                                  standingPointWorld: SIMD3<Float>) {
        guard state == .aimBaseArmed, anchorPointWorld != nil else { return }
        self.alphaBaseRad = alphaBaseRad
        standingPointWorldAtAimTop = standingPointWorld
        recordBasePose = session.latestDepthFrame?.cameraPoseWorld ?? matrix_identity_float4x4
        retainBaseAimFrame()
        state = .aimTopArmed
        rebuildSceneMarkers()
    }

    /// Test/preview hook: push α_top directly (SECOND), skipping the IMU.
    public func captureTopDirect(alphaTopRad: Float) {
        guard state == .aimTopArmed, anchorPointWorld != nil,
              alphaBaseRad != nil, standingPointWorldAtAimTop != nil
        else { return }
        self.alphaTopRad = alphaTopRad
        recordTopPose = session.latestDepthFrame?.cameraPoseWorld ?? matrix_identity_float4x4
        retainTopAimFrame()
        compute()
        rebuildSceneMarkers()
    }

    public func retake() {
        anchorPointWorld = nil
        alphaTopRad = nil
        alphaBaseRad = nil
        standingPointWorldAtAimTop = nil
        topAimedWorld = nil
        baseAimedWorld = nil
        result = nil
        dhMeters = 0
        walkHintMeters = 0
        cameraPositionAtAnchor = nil
        initialDistanceM = 0
        walkedBackMeters = 0
        alphaTopSampleCount = 0
        alphaBaseSampleCount = 0
        anchorFailureReason = nil
        recordPoseSamples.removeAll(keepingCapacity: true)
        lastPoseSampleTime = 0
        recordBaseFrame = nil; recordBaseJPEG = nil
        recordTopFrame = nil; recordTopJPEG = nil
        lastCaptureOutcome = nil
        // The previous bundle is no longer "the capture on screen" — a truth
        // typed after a retake must not land on the abandoned measurement.
        lastRecordedBundleID = nil
        state = .idle
        rebuildSceneMarkers()
    }

    /// A computed height is ALWAYS acceptable, whatever the tier. It used to
    /// be gated on `confidence != .red`, which meant a red fit could never be
    /// committed at all — the cruiser was left with Retake and Manual and no
    /// way to keep a number the geometry had actually produced, and (developer
    /// mode) no way to attach a typed truth to exactly the hard cases the
    /// algorithm comparison needs. The tier is NOT laundered by this: `result`
    /// keeps its red, and so do Tree.heightConfidence, the research CSV row
    /// and the raw-capture manifest.
    ///
    /// What is still refused is a result that is not a measurement — see
    /// `HeightEstimator.canAccept`.
    public func accept() {
        guard let r = result, HeightEstimator.canAccept(r) else { return }
        state = .accepted
        markLastBundleAccepted()
    }

    /// Whether the result on screen may be committed — drives the Accept
    /// button's enabled state in both result stages.
    public var canAcceptResult: Bool {
        guard let r = result else { return false }
        return HeightEstimator.canAccept(r)
    }

    /// The host could NOT store the accepted reading. Drop back to the result
    /// panel so the value is still on screen and Accept is tappable again —
    /// the alternative (sitting in `.accepted`, which renders no actions)
    /// would hide a lost measurement behind a dead end.
    ///
    /// Returns to whichever result stage the Accept came from, mirroring
    /// `compute()`'s routing: a red fit must land back on `.rejected`, which
    /// is the stage carrying Manual alongside Retake and Accept.
    public func acceptFailed() {
        guard state == .accepted, let r = result else { return }
        state = (r.confidence == .red) ? .rejected : .computed
    }

    public func enterManualEntry() {
        state = .manualEntry
    }

    /// The unit system the cruiser is working in, kept in sync by the screen.
    ///
    /// Two uses, and they must be the same value: the manual-entry field
    /// prompts for FEET under imperial (the typed number used to be stored
    /// straight into `heightM`, a 3.28x corruption), and the estimator quotes
    /// its ± band in these units so a band never arrives in metres beside a
    /// height in feet.
    public var unitSystem: UnitSystem = .metric

    public func submitManualEntry() {
        guard let typed = TruthInput.parsePositive(manualHeightM) else { return }
        let metres = unitSystem == .imperial
            ? Units.feetToMeters(typed) : typed
        // SAME floor as the AR path (`HeightEstimator.minHMeters`, 0.3 m),
        // read from the estimator so the two can never drift apart again.
        // This used to be a hard-coded 1.3 m, which survived the round that
        // dropped the AR floor to 0.3 m — leaving a 0.8 m seedling that the
        // walk-off flow measures happily but that the cruiser could not type
        // in, with the Save button simply doing nothing and no explanation.
        // Imperial input is still converted to metres BEFORE the comparison,
        // so the floor means the same physical height in either unit system.
        guard metres >= Double(HeightEstimator.minHMeters) else { return }
        let m = Float(metres)
        result = HeightResult(
            heightM: m,
            dHm: 0,
            alphaTopRad: 0,
            alphaBaseRad: 0,
            // A typed height has NO propagated σ — there is no baseline and
            // no aim angles to propagate. Recording nil says exactly that;
            // the 0 that used to sit here claimed the cruiser's guess was
            // exact, in the same field the tangent σ_H is stored in.
            sigmaHm: nil,
            confidence: .yellow,
            method: .manualEntry,
            rejectionReason: nil)
        state = .accepted
    }

    // MARK: - Estimation

    private func compute() {
        guard let anchor = anchorPointWorld,
              let at = alphaTopRad,
              let ab = alphaBaseRad,
              let standing = standingPointWorldAtAimTop
        else { return }
        let input = HeightMeasureInput(
            anchorPointWorld: anchor,
            standingPointWorld: standing,
            alphaTopRad: at,
            alphaBaseRad: ab,
            // Field fix: the `.limited`-tracking latch that used to feed
            // this flag fired on nearly every real walk-off (canopy +
            // walking flap ARKit tracking constantly) and red-rejected
            // good measurements. The Height flow now always reports
            // normal tracking; the remaining guards (d_h, aim angles,
            // H range, σ ratio) still gate quality.
            trackingStateWasNormalThroughout: true,
            projectCalibration: calibration,
            // Display units only — the estimator quotes its ± band in them.
            // Nothing computed or stored changes.
            unitSystem: unitSystem)
        let r = HeightEstimator.estimate(input: input)
        result = r
        // `.rejected` is the RED-TIER result stage, not a dead end: it
        // renders the same result panel (value + inline reason + the
        // developer truth field) and its action row offers Accept
        // alongside Retake and Manual. The tier itself is untouched.
        state = (r.confidence == .red) ? .rejected : .computed
        recordRawHeightIfNeeded(result: r, anchor: anchor, standing: standing,
                                alphaTop: at, alphaBase: ab)
    }

    /// Serialize the raw-capture bundle for this height compute (developer
    /// mode only). Off the main actor — pure estimator + IO over Sendable
    /// snapshots; only the bundle id returns to the main actor.
    private func recordRawHeightIfNeeded(result r: HeightResult,
                                         anchor: SIMD3<Float>,
                                         standing: SIMD3<Float>,
                                         alphaTop: Float,
                                         alphaBase: Float) {
        // A NEW compute supersedes the previous bundle on every path below,
        // the failing ones included — never leave the id pointing at the
        // PREVIOUS tree's bundle (DBH sibling carries the same guard).
        lastRecordedBundleID = nil
        guard rawCaptureEnabled else { return }
        let anchorPose = recordAnchorPose
        let hitType = recordAnchorHitType
        let distance = initialDistanceM
        let basePose = recordBasePose
        let topPose = recordTopPose
        let dh = r.dHm
        let samples = recordPoseSamples
        let cal = calibration
        let ctx = rawCaptureContext
        let gps = rawCaptureGPS
        let baseFrame = recordBaseFrame
        let baseJPEG = recordBaseJPEG
        let topFrame = recordTopFrame
        let topJPEG = recordTopJPEG
        let id = UUID().uuidString
        lastRecordedBundleID = id
        lastCaptureOutcome = nil
        Task.detached(priority: .utility) { [weak self] in
            let outcome = RawCaptureRecorder.recordHeight(
                id: id,
                anchorWorld: anchor, anchorHitType: hitType,
                anchorDistanceM: distance, anchorPose: anchorPose,
                basePitchRad: alphaBase, baseStanding: standing,
                baseRotationPose: basePose,
                topPitchRad: alphaTop, topPose: topPose,
                dHM: dh, poseSamples: samples,
                calibration: cal, context: ctx, gps: gps,
                baseFrame: baseFrame, baseJPEG: baseJPEG,
                topFrame: topFrame, topJPEG: topJPEG)
            await MainActor.run { self?.lastCaptureOutcome = outcome }
        }
    }

    /// Stamp `operator_accepted` on the bundle the cruiser just confirmed.
    /// Safe before the writer has finished — the store parks it.
    private func markLastBundleAccepted() {
        guard rawCaptureEnabled, let id = lastRecordedBundleID else { return }
        Task.detached(priority: .utility) {
            _ = RawCaptureStore.markAccepted(id: id)
        }
    }

    // MARK: - Scene marker geometry

    /// Rebuilds the three world-anchored markers that visualise the
    /// measurement. Called on every state transition that adds or
    /// clears one of the reference points.
    ///
    /// Geometry:
    /// • Anchor marker — exact `anchorPointWorld` (the tree-base pose
    ///   captured when the cruiser touched the phone to the stem).
    /// • Top marker — same XZ as the anchor, elevated by
    ///   `standing.y + d_h · tan(α_top)`. The top of the tree sits
    ///   directly above the base, so the two share a horizontal
    ///   position; only the Y differs by the instrument-triangle rise.
    /// • Base marker — same shape, using `α_base`. Lands near ground
    ///   level when the cruiser correctly aimed at the tree base.
    private func rebuildSceneMarkers() {
        var markers: [ARSceneMarker] = []

        if let anchor = anchorPointWorld {
            markers.append(ARSceneMarker(
                id: Self.anchorMarkerId,
                worldPosition: anchor,
                shape: .sphere(radiusM: 0.08),
                colorRGBA: SIMD4(1.00, 0.30, 0.30, 1.00),
                scalesWithDistance: true))  // red
        }

        // Prefer the raycast hit if the host supplied one — that's the
        // exact pixel the cruiser was pointing at. Otherwise compute a
        // fallback position on the anchor's vertical axis at the
        // α-derived height (still visible, just less pixel-accurate).
        if let alphaTop = alphaTopRad {
            let position: SIMD3<Float>? = {
                if let hit = topAimedWorld { return hit }
                if let anchor = anchorPointWorld,
                   let standing = standingPointWorldAtAimTop {
                    let dh = horizontalDistance(from: standing, to: anchor)
                    let y = standing.y + dh * tan(alphaTop)
                    return SIMD3<Float>(anchor.x, y, anchor.z)
                }
                return nil
            }()
            if let p = position {
                markers.append(ARSceneMarker(
                    id: Self.topMarkerId,
                    worldPosition: p,
                    shape: .sphere(radiusM: 0.08),
                    colorRGBA: SIMD4(1.00, 0.85, 0.15, 1.00),
                    scalesWithDistance: true))  // yellow
            }
        }

        if let alphaBase = alphaBaseRad {
            let position: SIMD3<Float>? = {
                if let hit = baseAimedWorld { return hit }
                if let anchor = anchorPointWorld,
                   let standing = standingPointWorldAtAimTop {
                    let dh = horizontalDistance(from: standing, to: anchor)
                    let y = standing.y + dh * tan(alphaBase)
                    return SIMD3<Float>(anchor.x, y, anchor.z)
                }
                return nil
            }()
            if let p = position {
                markers.append(ARSceneMarker(
                    id: Self.baseMarkerId,
                    worldPosition: p,
                    shape: .sphere(radiusM: 0.08),
                    colorRGBA: SIMD4(0.25, 0.85, 0.35, 1.00),
                    scalesWithDistance: true))  // green
            }
        }

        sceneMarkers = markers
    }

    private func horizontalDistance(from a: SIMD3<Float>,
                                    to b: SIMD3<Float>) -> Float {
        let dx = a.x - b.x
        let dz = a.z - b.z
        return (dx * dx + dz * dz).squareRoot()
    }

    // MARK: - Walk-hint geometry

    /// Returns the signed distance the user should still travel. Aims for
    /// the §7.2 sweet spot `0.6·H ≤ d_h ≤ 1.0·H`. When inside the band we
    /// return 0 so the UI can render "You're set" rather than "move X m".
    static func computeWalkHint(dh: Float, expectedH: Float) -> Float {
        let lo = 0.6 * expectedH
        let hi = 1.0 * expectedH
        if dh < lo { return lo - dh }
        if dh > hi { return hi - dh }           // negative → walk forward
        return 0
    }

    private func computeWalkHint(dh: Float, expectedH: Float) -> Float {
        Self.computeWalkHint(dh: dh, expectedH: expectedH)
    }
}

// MARK: - Preview / snapshot factories

public extension HeightScanViewModel {

    static func preview(
        state: State,
        result: HeightResult? = nil,
        dhMeters: Float = 0,
        walkHintMeters: Float = 0,
        expectedHeightM: Float = 30,
        initialDistanceM: Float = 0,
        walkedBackMeters: Float = 0
    ) -> HeightScanViewModel {
        let vm = HeightScanViewModel(calibration: ProjectCalibration.identity)
        vm.applyPreview(
            state: state,
            result: result,
            dhMeters: dhMeters,
            walkHintMeters: walkHintMeters,
            expectedHeightM: expectedHeightM,
            initialDistanceM: initialDistanceM,
            walkedBackMeters: walkedBackMeters)
        return vm
    }

    func applyPreview(
        state: State,
        result: HeightResult?,
        dhMeters: Float,
        walkHintMeters: Float,
        expectedHeightM: Float,
        initialDistanceM: Float = 0,
        walkedBackMeters: Float = 0
    ) {
        self.state = state
        self.result = result
        self.dhMeters = dhMeters
        self.walkHintMeters = walkHintMeters
        self.expectedHeightM = expectedHeightM
        self.initialDistanceM = initialDistanceM
        self.walkedBackMeters = walkedBackMeters
    }
}
