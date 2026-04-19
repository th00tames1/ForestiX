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

    /// Last sample count folded into α_top / α_base for diagnostics
    /// (REQ-HGT-004: "sample count logged").
    @Published public private(set) var alphaTopSampleCount: Int = 0
    @Published public private(set) var alphaBaseSampleCount: Int = 0

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

    /// Read the current ARKit camera translation, if any frame has
    /// landed. Returns nil on platforms without ARKit or before the
    /// first frame.
    private func currentCameraTranslation() -> SIMD3<Float>? {
        guard let frame = session.latestDepthFrame else { return nil }
        let c = frame.cameraPoseWorld.columns.3
        return SIMD3<Float>(c.x, c.y, c.z)
    }

    // MARK: - §4.4 transitions

    /// Step (a) — user touches phone to tree base and taps "Anchor Here".
    /// Caller supplies the current ARKit camera position so the anchor
    /// is placed in world frame.
    public func anchorHere(standingPointWorld: SIMD3<Float>) {
        anchorPointWorld = standingPointWorld
        alphaTopRad = nil
        alphaBaseRad = nil
        standingPointWorldAtAimTop = nil
        trackingDroppedDuringMeasurement = false
        state = .anchorSet
        state = .walking
        updateLiveHint(standingPointWorld: standingPointWorld)
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
    public func captureTop(at tapTime: TimeInterval,
                           standingPointWorld: SIMD3<Float>) {
        guard state == .aimTopArmed, anchorPointWorld != nil else { return }
        guard let median = pitchBuffer.medianPitch(centeredOn: tapTime) else { return }
        alphaTopRad = Float(median)
        alphaTopSampleCount = pitchBuffer.sampleCount(centeredOn: tapTime)
        standingPointWorldAtAimTop = standingPointWorld
        state = .aimTopCaptured
        state = .aimBaseArmed
    }

    /// Step (d) — Aim Base tap. α_base = median pitch over ±200 ms.
    /// Triggers estimation.
    public func captureBase(at tapTime: TimeInterval) {
        guard state == .aimBaseArmed, anchorPointWorld != nil,
              alphaTopRad != nil, standingPointWorldAtAimTop != nil
        else { return }
        guard let median = pitchBuffer.medianPitch(centeredOn: tapTime) else { return }
        alphaBaseRad = Float(median)
        alphaBaseSampleCount = pitchBuffer.sampleCount(centeredOn: tapTime)
        compute()
    }

    /// Button-handler entry for the Anchor Here tap. Pulls the current
    /// camera position from the ARKit session; no-op if no frame yet.
    public func anchorHereNow() {
        guard let p = currentCameraTranslation() else { return }
        anchorHere(standingPointWorld: p)
    }

    /// Button-handler entry for Aim Top. Uses the current camera pose +
    /// the current timestamp to drive the IMU median.
    public func captureTopNow() {
        guard let p = currentCameraTranslation() else { return }
        captureTop(at: Date().timeIntervalSinceReferenceDate,
                   standingPointWorld: p)
    }

    /// Button-handler entry for Aim Base. Uses the current timestamp.
    public func captureBaseNow() {
        captureBase(at: Date().timeIntervalSinceReferenceDate)
    }

    /// Test/preview hook: push α_top directly, skipping the IMU buffer.
    public func captureTopDirect(alphaTopRad: Float,
                                 standingPointWorld: SIMD3<Float>) {
        guard state == .aimTopArmed, anchorPointWorld != nil else { return }
        self.alphaTopRad = alphaTopRad
        standingPointWorldAtAimTop = standingPointWorld
        state = .aimTopCaptured
        state = .aimBaseArmed
    }

    /// Test/preview hook: push α_base directly, skipping the IMU buffer.
    public func captureBaseDirect(alphaBaseRad: Float) {
        guard state == .aimBaseArmed, anchorPointWorld != nil,
              alphaTopRad != nil, standingPointWorldAtAimTop != nil
        else { return }
        self.alphaBaseRad = alphaBaseRad
        compute()
    }

    public func retake() {
        anchorPointWorld = nil
        alphaTopRad = nil
        alphaBaseRad = nil
        standingPointWorldAtAimTop = nil
        result = nil
        dhMeters = 0
        walkHintMeters = 0
        alphaTopSampleCount = 0
        alphaBaseSampleCount = 0
        trackingDroppedDuringMeasurement = false
        state = .idle
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
