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

    /// Live horizontal distance from the anchor to the current standing
    /// pose (REQ-HGT-003). Updates at the ARKit frame rate.
    @Published public private(set) var dhMeters: Float = 0

    /// "Move back/forward X m" hint (REQ-HGT-003). Positive → walk back;
    /// negative → walk forward; zero → inside the sweet-spot band.
    @Published public private(set) var walkHintMeters: Float = 0

    /// Set once if any ARKit frame reports `.limited` during the flow
    /// (REQ-HGT-005). Latched until retake().
    @Published public private(set) var trackingDroppedDuringMeasurement: Bool = false

    /// Walk-back geometry target. Default 30 m per Phase 3 Decision Q4.
    /// 0.6 · H_expected ≤ d_h ≤ 1.0 · H_expected gives the sweet spot.
    @Published public var expectedHeightM: Float = 30

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

    private var trackingCancellable: AnyCancellable?
    private var depthCancellable: AnyCancellable?

    // MARK: - Construction

    public init(
        calibration: ProjectCalibration,
        session: ARKitSessionManager? = nil,
        pitchBuffer: IMUPitchBuffer? = nil,
        motion: IMUMotionService? = nil
    ) {
        self.calibration = calibration
        self.session = session ?? ARKitSessionManager()
        let buffer = pitchBuffer ?? IMUPitchBuffer()
        self.pitchBuffer = buffer
        self.motion = motion ?? IMUMotionService(buffer: buffer)
    }

    // MARK: - Lifecycle

    public func onAppear() {
        if state == .idle { /* waiting for anchor tap */ }
        session.run()
        motion.start()
        subscribeToTracking()
        subscribeToDepth()
    }

    public func onDisappear() {
        motion.stop()
        session.pause()
        trackingCancellable?.cancel()
        trackingCancellable = nil
        depthCancellable?.cancel()
        depthCancellable = nil
    }

    private func subscribeToTracking() {
        trackingCancellable = session.$trackingStatus
            .sink { [weak self] status in
                if status == .limited { self?.trackingDroppedDuringMeasurement = true }
            }
    }

    private func subscribeToDepth() {
        depthCancellable = session.$latestDepthFrame
            .compactMap { $0 }
            .sink { [weak self] frame in
                guard let self, self.state == .walking else { return }
                let pose = frame.cameraPoseWorld
                let standing = SIMD3<Float>(pose.columns.3.x,
                                            pose.columns.3.y,
                                            pose.columns.3.z)
                self.updateLiveHint(standingPointWorld: standing)
            }
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
        trackingDroppedDuringMeasurement = false
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
    }

    /// User decides they've walked far enough and tapped Continue.
    public func continueToAimTop() {
        guard state == .walking, anchorPointWorld != nil else { return }
        state = .aimTopArmed
    }

    /// Step (c) — Aim Top tap. α_top = median pitch over ±200 ms.
    /// `aimedAtWorld` is an optional hint from a screen-centre raycast
    /// used purely for the top-marker position. If nil, the marker
    /// falls back to (anchor.xz, standing.y + d_h · tan(α_top)).
    public func captureTop(at tapTime: TimeInterval,
                           standingPointWorld: SIMD3<Float>,
                           aimedAtWorld: SIMD3<Float>? = nil) {
        guard state == .aimTopArmed, anchorPointWorld != nil else { return }
        guard let median = resilientMedianPitch(tapTime: tapTime) else { return }
        alphaTopRad = Float(median)
        alphaTopSampleCount = pitchBuffer.sampleCount(centeredOn: tapTime)
        standingPointWorldAtAimTop = standingPointWorld
        topAimedWorld = aimedAtWorld
        state = .aimTopCaptured
        state = .aimBaseArmed
        rebuildSceneMarkers()
    }

    /// Step (d) — Aim Base tap. α_base = median pitch over ±200 ms.
    /// Triggers estimation. `aimedAtWorld` is the same optional raycast
    /// hint used for the base marker.
    public func captureBase(at tapTime: TimeInterval,
                            aimedAtWorld: SIMD3<Float>? = nil) {
        guard state == .aimBaseArmed, anchorPointWorld != nil,
              alphaTopRad != nil, standingPointWorldAtAimTop != nil
        else { return }
        guard let median = resilientMedianPitch(tapTime: tapTime) else { return }
        alphaBaseRad = Float(median)
        alphaBaseSampleCount = pitchBuffer.sampleCount(centeredOn: tapTime)
        baseAimedWorld = aimedAtWorld
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

    /// Button-handler entry for the Anchor Here tap. `screenCenterHit`
    /// is an optional world point from the host's raycast — when
    /// present, it's used as the tree-base anchor. Without it we fall
    /// back to the camera position (spec's "touch phone to tree base"
    /// flow).
    public func anchorHereNow(screenCenterHit: SIMD3<Float>? = nil) {
        guard let cam = currentCameraTranslation() else { return }
        let anchor = screenCenterHit ?? cam
        anchorHere(anchorPointWorld: anchor,
                   standingPointWorld: cam)
    }

    /// Button-handler entry for Aim Top. Uses the current camera pose +
    /// the current timestamp to drive the IMU median. `screenCenterHit`,
    /// when non-nil, seeds the top-marker position.
    ///
    /// Timestamp MUST match the CMDeviceMotion clock — those samples are
    /// stamped with `ProcessInfo.systemUptime` (seconds since boot), NOT
    /// `Date().timeIntervalSinceReferenceDate` (seconds since 2001).
    public func captureTopNow(screenCenterHit: SIMD3<Float>? = nil) {
        let p = currentCameraTranslation() ?? .zero
        captureTop(at: nowForPitchBuffer(),
                   standingPointWorld: p,
                   aimedAtWorld: screenCenterHit)
    }

    /// Button-handler entry for Aim Base. Same clock convention as
    /// `captureTopNow()` — see the Aim Top docstring.
    public func captureBaseNow(screenCenterHit: SIMD3<Float>? = nil) {
        captureBase(at: nowForPitchBuffer(),
                    aimedAtWorld: screenCenterHit)
    }

    /// Monotonic clock matching `CMDeviceMotion.timestamp`. Keep one
    /// source so every tap and every sample agree.
    private func nowForPitchBuffer() -> TimeInterval {
        ProcessInfo.processInfo.systemUptime
    }

    /// Test/preview hook: push α_top directly, skipping the IMU buffer.
    public func captureTopDirect(alphaTopRad: Float,
                                 standingPointWorld: SIMD3<Float>) {
        guard state == .aimTopArmed, anchorPointWorld != nil else { return }
        self.alphaTopRad = alphaTopRad
        standingPointWorldAtAimTop = standingPointWorld
        state = .aimTopCaptured
        state = .aimBaseArmed
        rebuildSceneMarkers()
    }

    /// Test/preview hook: push α_base directly, skipping the IMU buffer.
    public func captureBaseDirect(alphaBaseRad: Float) {
        guard state == .aimBaseArmed, anchorPointWorld != nil,
              alphaTopRad != nil, standingPointWorldAtAimTop != nil
        else { return }
        self.alphaBaseRad = alphaBaseRad
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
        alphaTopSampleCount = 0
        alphaBaseSampleCount = 0
        trackingDroppedDuringMeasurement = false
        state = .idle
        rebuildSceneMarkers()
    }

    public func accept() {
        guard let r = result, r.confidence != .red else { return }
        state = .accepted
    }

    public func enterManualEntry() {
        state = .manualEntry
    }

    public func submitManualEntry() {
        guard let m = Float(manualHeightM), m > 1.3 else { return }
        result = HeightResult(
            heightM: m,
            dHm: 0,
            alphaTopRad: 0,
            alphaBaseRad: 0,
            sigmaHm: 0,
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
            trackingStateWasNormalThroughout: !trackingDroppedDuringMeasurement,
            projectCalibration: calibration)
        let r = HeightEstimator.estimate(input: input)
        result = r
        state = (r.confidence == .red) ? .rejected : .computed
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
                colorRGBA: SIMD4(1.00, 0.30, 0.30, 1.00)))  // red
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
                    colorRGBA: SIMD4(1.00, 0.85, 0.15, 1.00)))  // yellow
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
                    colorRGBA: SIMD4(0.25, 0.85, 0.35, 1.00)))  // green
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
        expectedHeightM: Float = 30
    ) -> HeightScanViewModel {
        let vm = HeightScanViewModel(calibration: ProjectCalibration.identity)
        vm.applyPreview(
            state: state,
            result: result,
            dhMeters: dhMeters,
            walkHintMeters: walkHintMeters,
            expectedHeightM: expectedHeightM)
        return vm
    }

    func applyPreview(
        state: State,
        result: HeightResult?,
        dhMeters: Float,
        walkHintMeters: Float,
        expectedHeightM: Float
    ) {
        self.state = state
        self.result = result
        self.dhMeters = dhMeters
        self.walkHintMeters = walkHintMeters
        self.expectedHeightM = expectedHeightM
    }
}
