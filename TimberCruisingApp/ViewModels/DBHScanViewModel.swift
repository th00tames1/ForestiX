// Spec §4.3 DBH state machine + §5.2 screen contract. Observes
// ARKitSessionManager for depth frames, buffers a burst on user tap,
// runs DBHEstimator, and surfaces the state transitions for the view.
//
// The view model is cross-platform. On iOS it starts a real ARKit
// session via ARKitSessionManager; on macOS the same session type
// compiles to a no-op stub so previews/snapshots compile unchanged.
// Tests drive the state machine directly through the `preview` factory.

import Foundation
import Combine
import Models
import Common
import Sensors

#if canImport(UIKit) && os(iOS)
import UIKit
#endif

@MainActor
public final class DBHScanViewModel: ObservableObject {

    // MARK: - §4.3 DBHScanState

    public enum State: Equatable, Sendable {
        case idle
        case aligning
        case armed
        case capturing
        case fitted
        case accepted
        case rejected
        case manualEntry
    }

    // MARK: - Published surface

    @Published public private(set) var state: State = .idle
    @Published public private(set) var result: DBHResult?
    @Published public private(set) var crosshairIsStable: Bool = false
    @Published public var manualDbhCm: String = ""
    @Published public private(set) var unsupportedBanner: String?
    /// Cheap single-frame DBH estimate updated in real time while the
    /// cruiser is aiming. Lets the HUD show "~ 34 cm" near the crosshair
    /// before a formal capture. nil when the strip can't be trusted.
    /// The authoritative measurement still runs the full §7.1 burst.
    @Published public private(set) var previewDbhCm: Double?
    /// Full single-frame preview fit — exposes centre + radius so the
    /// scan screen can overlay a 3D cylinder at the trunk's world
    /// position and show distance from the camera to the stem axis.
    @Published public private(set) var previewFit: DBHEstimator.PreviewFit?
    /// Horizontal distance from the camera to the preview's stem axis.
    /// Updated on every depth frame; nil when no preview is available.
    @Published public private(set) var distanceToStemCenterM: Float?
    /// World-space Y (metres) of the guide row, used by the 3D cylinder
    /// so it's rendered at DBH height instead of floating in mid-air.
    @Published public private(set) var guideRowWorldY: Float?
    /// Confidence tier of the published preview value. nil whenever
    /// `previewDbhCm` is nil (red fits suppress the value too).
    @Published public private(set) var previewTier: ConfidenceTier?
    /// HUD status string when `previewDbhCm` can't be trusted —
    /// either "Stabilizing…" while the value is still settling or the
    /// fit's rejection reason on red. nil while a green/yellow value
    /// is being shown.
    @Published public private(set) var previewStatusText: String?

    // MARK: - Dependencies

    public let session: ARKitSessionManager
    public let calibration: ProjectCalibration
    public let isLiDARSupported: Bool
    public let rawPointsWriter: (@Sendable ([SIMD2<Double>]) -> String?)?

    /// Phase 19 — which DBH algorithm the live preview + burst should
    /// run. Mutated from the screen on every appear / settings change
    /// so a cruiser flipping methods in Settings sees the new mode
    /// without leaving the scan screen. Defaults to `.chord` (the new
    /// silhouette / pixel-width method).
    @Published public var dbhMeasurementMethod: DBHMeasurementMethod = .chord

    // MARK: - Burst state

    private let burstSize: Int = 12
    private var burstBuffer: [ARDepthFrame] = []
    private var burstTap: SIMD2<Double> = .zero
    private var depthCancellable: AnyCancellable?

    /// Last time `previewFit` was recomputed (ProcessInfo uptime).
    /// Used to throttle the relatively expensive strip-extract +
    /// back-projection work to ~10 Hz so the scan HUD doesn't churn
    /// SwiftUI every ARKit frame.
    private var lastPreviewUpdate: TimeInterval = 0
    /// Minimum interval between preview recomputations. 100 ms is well
    /// below human reaction time for reading the number, and it cut
    /// the scan-screen lag dramatically on device.
    private let previewMinIntervalSec: TimeInterval = 0.1
    /// EMA smoothing for the published `previewDbhCm`. The geometric
    /// circumradius preview (Phase 14.2) is already stable, but a light
    /// EMA over consecutive valid readings absorbs any residual chord
    /// jitter so the HUD digit doesn't flicker. α = 0.3 at the 10 Hz
    /// preview rate gives ≈ 0.5 s effective smoothing window. Reset
    /// whenever the fit drops out so a re-acquisition starts fresh
    /// instead of dragging in the previous trunk's value.
    private var smoothedPreviewDbhCm: Double?
    private let previewEMAAlpha: Double = 0.3
    /// Last few raw preview diameters (cm) — used by the stability
    /// gate. The published value stays hidden until consecutive frames
    /// agree to within the stability thresholds, so the cruiser never
    /// reads a number while the fit is still settling.
    private var recentRawDiameters: [Double] = []
    private let recentRawDiameterCapacity: Int = 5
    /// Phase 16.2 hysteresis, retuned in Phase 18.2. The earlier
    /// 10 %/3-frame gate left the cruiser staring at "Stabilizing…"
    /// far longer than peer apps (ForestScanner / Single-Shot SAM
    /// publish on a single fit). Loosened to 12 %/2-frames — still
    /// rejects obvious chatter but unlocks the published value almost
    /// as soon as the cruiser steadies the phone.
    ///   • Enter stable when CoV ≤ 0.12 over 2+ frames
    ///   • Stay stable until CoV exceeds 0.20 (deadband ⇒ no flicker)
    ///   • Tolerate 1–2 transient red frames without resetting; only
    ///     `redResetCount` consecutive reds wipes the history.
    private var isStable: Bool = false
    private let stabilityEnterCoV: Double = 0.12
    private let stabilityExitCoV: Double = 0.20
    private var consecutiveRedFrames: Int = 0
    private let redResetCount: Int = 3

    /// Phase 18.1 — fit-geometry smoothing. The published diameter has
    /// always been EMA-smoothed (see `smoothedPreviewDbhCm`), but the
    /// stem-axis XZ centre that drives the on-screen distance readout
    /// and the 3D cylinder overlay was being read raw from each frame's
    /// fit. Without smoothing it jittered ± a few cm per tick even when
    /// the diameter had stabilised, and the cruiser saw the distance
    /// number flicker. EMA over the centre XZ gives a stable trunk
    /// position for both the distance HUD and the cylinder transform.
    /// α matches `previewEMAAlpha` so the two values move together.
    private var smoothedCenterWorldXZ: SIMD2<Double>?
    /// Last frame's effective tap-depth (metres) — fed back into
    /// `DBHEstimator.previewFit` as the next call's `tapDepthHint`.
    /// Anchoring the depth window stops it sliding under hand tremor,
    /// which is the upstream cause of frame-to-frame DBH variance.
    /// Reset to nil whenever the preview drops out so a re-acquisition
    /// or a new tree starts with the raw 5×5 median.
    private var lastTapDepthHint: Double?

    // MARK: - Construction

    public init(
        calibration: ProjectCalibration,
        session: ARKitSessionManager? = nil,
        rawPointsWriter: (@Sendable ([SIMD2<Double>]) -> String?)? = nil,
        method: DBHMeasurementMethod = .chord
    ) {
        self.session = session ?? ARKitSessionManager()
        self.calibration = calibration
        self.rawPointsWriter = rawPointsWriter
        self.dbhMeasurementMethod = method
        self.isLiDARSupported = ARKitSessionManager.supportsLiDAR
        if !isLiDARSupported {
            unsupportedBanner = "LiDAR not supported on this device. " +
                                "Use Manual Entry to record the diameter."
        }
    }

    // MARK: - Lifecycle

    public func onAppear() {
        // Always start the AR session — even on non-LiDAR devices we want
        // the camera feed to render so the cruiser can see what they're
        // pointing at while entering DBH manually. `session.run()` is
        // internally guarded against unsupported configurations, so it's
        // safe to call on any device.
        session.run()
        subscribeToDepth()

        if isLiDARSupported {
            if state == .idle || state == .accepted || state == .rejected {
                state = .aligning
            }
        } else {
            // No LiDAR → caliper / tape workflow. Camera still visible
            // behind the manual-entry panel so the cruiser can frame the
            // measurement.
            state = .manualEntry
        }
    }

    public func onDisappear() {
        depthCancellable?.cancel()
        depthCancellable = nil
        session.pause()
    }

    private func subscribeToDepth() {
        depthCancellable = session.$latestDepthFrame
            .compactMap { $0 }
            .sink { [weak self] frame in
                self?.handleDepthFrame(frame)
            }
    }

    private func handleDepthFrame(_ frame: ARDepthFrame) {
        // REQ-DBH-003 crosshair transitions green when center depth
        // is stable and < 3 m.
        let cx = frame.width / 2
        let cy = frame.height / 2
        let d = frame.depth(atX: cx, y: cy)
        let c = frame.confidence(atX: cx, y: cy)
        let stable = d > 0.5 && d < 3.0 && c >= 1
        crosshairIsStable = stable
        if state == .aligning, stable { state = .armed }
        if state == .armed, !stable    { state = .aligning }
        if state == .capturing {
            burstBuffer.append(frame)
            if burstBuffer.count >= burstSize { finishCapture() }
        }

        // Live preview — expensive work gated by a throttle so it runs
        // at ~10 Hz instead of ARKit's 60 Hz. State-change side effects
        // (e.g. clearing the cylinder marker when capture begins) still
        // happen immediately so we don't flash stale numbers.
        let previewable: Bool
        switch state {
        case .aligning, .armed, .rejected: previewable = true
        case .capturing, .fitted, .accepted, .manualEntry, .idle:
            previewable = false
        }

        if !previewable {
            // Immediate clear — don't wait for the throttle so the
            // cylinder overlay doesn't linger over a committed result.
            if previewFit != nil {
                previewFit = nil
                previewDbhCm = nil
                distanceToStemCenterM = nil
            }
            smoothedPreviewDbhCm = nil
            smoothedCenterWorldXZ = nil
            lastTapDepthHint = nil
            recentRawDiameters.removeAll()
            previewTier = nil
            previewStatusText = nil
            isStable = false
            consecutiveRedFrames = 0
            return
        }

        let now = ProcessInfo.processInfo.systemUptime
        guard now - lastPreviewUpdate >= previewMinIntervalSec else { return }
        lastPreviewUpdate = now

        let axis = Self.currentGuideAxis(width: frame.width, height: frame.height)
        // Phase 19 — dispatch on the user's chosen DBH method. The chord
        // method is stateless frame-to-frame (no depth-window anchoring
        // needed: median over ± 10 rows already absorbs intra-frame
        // jitter and the multi-frame median in the burst handles the
        // rest). The legacy partial-arc path keeps its tap-depth hint.
        let fit: DBHEstimator.PreviewFit?
        switch dbhMeasurementMethod {
        case .chord:
            fit = DBHEstimator.chordPreviewFit(
                frame: frame,
                tapPixel: SIMD2(Double(cx), Double(cy)),
                guideAxis: axis,
                discontinuityThresholdM: calibration.depthDiscontinuityM)
            lastTapDepthHint = nil
        case .partialArcCircleFit:
            fit = DBHEstimator.previewFit(
                frame: frame,
                tapPixel: SIMD2(Double(cx), Double(cy)),
                guideAxis: axis,
                discontinuityThresholdM: calibration.depthDiscontinuityM,
                tapDepthHint: lastTapDepthHint)
            // Phase 18.1: feed the just-used effective tap depth back
            // as the next frame's hint.
            lastTapDepthHint = fit?.effectiveTapDepth
        }

        // Stability + tier gate (Phase 16.2). Hysteresis on the CoV
        // window so a borderline frame doesn't flip the gate, and a
        // single red frame no longer clears the history — only
        // `redResetCount` consecutive reds force a reset.
        // Phase 18.5 — separate "publish" from "stable smoothing".
        //
        // Pre-18.4 the stability gate served two jobs at once: gate
        // auto-capture, and gate the on-screen number. Auto-capture is
        // gone (cruiser taps to capture), so the only remaining job is
        // smoothing the displayed digit so it doesn't jitter. The
        // displayed value is now always the latest non-red fit — raw
        // when the gate hasn't latched, EMA-smoothed once it has —
        // instead of being hidden until stability is reached.
        let publishable: Bool   // any non-red fit available
        let smoothingActive: Bool   // EMA smoothing engaged
        if let f = fit, f.tier != .red {
            consecutiveRedFrames = 0
            recentRawDiameters.append(f.diameterCm)
            if recentRawDiameters.count > recentRawDiameterCapacity {
                recentRawDiameters.removeFirst()
            }
            if recentRawDiameters.count >= 2 {
                let mean = recentRawDiameters.reduce(0, +)
                          / Double(recentRawDiameters.count)
                let lo = recentRawDiameters.min() ?? mean
                let hi = recentRawDiameters.max() ?? mean
                let cov = mean > 0 ? (hi - lo) / mean : 1
                let threshold = isStable ? stabilityExitCoV : stabilityEnterCoV
                isStable = cov <= threshold
            } else {
                isStable = false
            }
            publishable = true
            smoothingActive = isStable
            if smoothingActive {
                if let prev = smoothedPreviewDbhCm {
                    smoothedPreviewDbhCm = previewEMAAlpha * f.diameterCm
                                         + (1 - previewEMAAlpha) * prev
                } else {
                    smoothedPreviewDbhCm = f.diameterCm
                }
                // Phase 18.1: same EMA over the fit centre's XZ so the
                // distance readout and cylinder overlay don't flicker
                // even when the diameter digit has stabilised.
                if let prev = smoothedCenterWorldXZ {
                    smoothedCenterWorldXZ = SIMD2(
                        previewEMAAlpha * f.centerWorldXZ.x + (1 - previewEMAAlpha) * prev.x,
                        previewEMAAlpha * f.centerWorldXZ.y + (1 - previewEMAAlpha) * prev.y)
                } else {
                    smoothedCenterWorldXZ = f.centerWorldXZ
                }
            } else {
                // No smoothing yet — track the raw fit so the published
                // value moves with the cruiser's aim instead of being
                // pinned to a stale smoothed value from a different
                // trunk.
                smoothedPreviewDbhCm = f.diameterCm
                smoothedCenterWorldXZ = f.centerWorldXZ
            }
        } else {
            // Tolerate transient reds — keep the history and the
            // smoothed value alive so a one-off bad frame doesn't
            // restart the whole stabilisation.
            consecutiveRedFrames += 1
            if consecutiveRedFrames >= redResetCount {
                recentRawDiameters.removeAll()
                smoothedPreviewDbhCm = nil
                smoothedCenterWorldXZ = nil
                isStable = false
            }
            publishable = false
            smoothingActive = false
        }

        // Phase 18.1: publish a fit whose centre is the smoothed XZ so
        // any consumer that reads `previewFit.centerWorldXZ` (e.g. the
        // 3D cylinder overlay in DBHScanScreen) gets the same stable
        // trunk position the distance HUD is reading. Diameter on the
        // published fit also tracks the EMA-smoothed scalar so HUD
        // pieces that haven't been re-pointed at `previewDbhCm` stay
        // consistent. When smoothing isn't yet engaged we publish the
        // raw fit so the cruiser still sees the cylinder while aiming.
        if let f = fit, smoothingActive,
           let stem = smoothedCenterWorldXZ,
           let dia = smoothedPreviewDbhCm {
            previewFit = DBHEstimator.PreviewFit(
                diameterCm: dia,
                centerWorldXZ: stem,
                radiusM: dia / 200.0,    // cm → m, ÷ 2
                stripLeftFraction: f.stripLeftFraction,
                stripRightFraction: f.stripRightFraction,
                tier: f.tier,
                inlierCount: f.inlierCount,
                arcDeg: f.arcDeg,
                rmseMm: f.rmseMm,
                rejectionReason: f.rejectionReason,
                effectiveTapDepth: f.effectiveTapDepth)
        } else {
            previewFit = fit
        }

        previewDbhCm = publishable ? smoothedPreviewDbhCm : nil
        previewTier = publishable ? fit?.tier : nil
        // The status banner is now reserved for hard rejections only.
        // Cruiser sees the live digit in the badge whenever a fit
        // exists, so "Stabilizing…" is no longer useful — the digit
        // itself shows whether things are settling.
        previewStatusText = publishable ? nil : fit?.rejectionReason

        // Phase 18.4 — auto-capture removed. Field testing showed the
        // hands-free trigger fired before the cruiser was committed to
        // the trunk they were aiming at, locking in stray fits during
        // panning. We now require an explicit screen tap (handled in
        // `tap(at:)`) so the burst only starts when the cruiser is
        // actually ready to record.

        // Distance readout — camera position XZ vs stem axis XZ.
        // Uses the frame's own camera pose to stay consistent with the
        // fit's reference frame. Phase 18.1: prefer the EMA-smoothed
        // centre once the fit is publishable, so the distance number
        // and the cylinder overlay don't jitter against the stable
        // diameter digit. Before stability is reached we still surface
        // the raw centre so the cruiser can see *something* while
        // aiming — the stability gate already hides any number that
        // would mislead.
        let pose = frame.cameraPoseWorld
        guideRowWorldY = pose.columns.3.y
        let stemXZ = smoothedCenterWorldXZ ?? fit?.centerWorldXZ
        if let stem = stemXZ {
            let camXZ = SIMD2<Double>(Double(pose.columns.3.x),
                                       Double(pose.columns.3.z))
            let d = stem - camXZ
            distanceToStemCenterM = Float((d.x * d.x + d.y * d.y).squareRoot())
        } else {
            distanceToStemCenterM = nil
        }
    }

    // MARK: - User actions

    /// Called on trunk-center tap. `tapPixel` is in the depth map's
    /// coordinate space (caller converts from view coords to depth
    /// coords via the ARKit displayTransform).
    public func tap(at tapPixel: SIMD2<Double>) {
        guard state == .armed else { return }
        // Phase 18.4 — the tap is now the *only* way to start the
        // burst, so we keep the gate loose: any fit visible on screen
        // (red rejections excluded) is enough. Waiting for the
        // stability gate to latch before allowing a tap is what made
        // the previous auto-capture flow feel sluggish; if the cruiser
        // is committed enough to tap, the burst's own §7.1 tree will
        // catch a fit that's too noisy to record.
        guard let fit = previewFit, fit.tier != .red else { return }
        burstBuffer.removeAll(keepingCapacity: true)
        burstTap = tapPixel
        state = .capturing
    }

    public func retake() {
        burstBuffer.removeAll()
        result = nil
        state = isLiDARSupported ? .aligning : .manualEntry
    }

    public func accept() {
        guard let r = result, r.confidence != .red else { return }
        state = .accepted
    }

    public func enterManualEntry() {
        state = .manualEntry
    }

    public func submitManualEntry() {
        guard let cm = Double(manualDbhCm), cm > 0 else { return }
        result = DBHResult(
            diameterCm: Float(cm),
            centerXZ: SIMD2(0, 0),
            arcCoverageDeg: 0,
            rmseMm: 0,
            sigmaRmm: 0,
            nInliers: 0,
            confidence: .yellow,
            method: .manualVisual,
            rawPointsPath: nil,
            rejectionReason: nil)
        state = .accepted
    }

    /// Pick the strip-walk axis from the current UI orientation. Phase
    /// 14: iPhone is locked to portrait so the iPhone path always
    /// returns `.col`; iPad still supports landscape and falls through
    /// to `.row` whenever the active scene reports a landscape
    /// interface orientation. macOS / non-UIKit hosts default to
    /// portrait (`.col`) — they only run via tests / previews where
    /// the synthetic frames decide the axis explicitly.
    static func currentGuideAxis(width: Int, height: Int) -> GuideAxis {
        #if canImport(UIKit) && os(iOS)
        let landscape: Bool = {
            for scene in UIApplication.shared.connectedScenes {
                if let ws = scene as? UIWindowScene,
                   ws.activationState == .foregroundActive {
                    return ws.interfaceOrientation.isLandscape
                }
            }
            return false
        }()
        if landscape { return .row(y: height / 2) }
        return .col(x: width / 2)
        #else
        return .col(x: width / 2)
        #endif
    }

    private func finishCapture() {
        let frames = burstBuffer
        burstBuffer.removeAll()
        guard let firstFrame = frames.first else {
            state = .rejected
            return
        }
        let axis = Self.currentGuideAxis(width: firstFrame.width,
                                         height: firstFrame.height)
        let input = DBHScanInput(
            frames: frames,
            tapPixel: burstTap,
            guideAxis: axis,
            projectCalibration: calibration,
            rawPointsWriter: rawPointsWriter)
        // Phase 19 dispatch — chord method on the chord burst path,
        // partial-arc method on the original §7.1 pipeline.
        let outcome: DBHResult?
        switch dbhMeasurementMethod {
        case .chord:               outcome = DBHEstimator.chordEstimate(input: input)
        case .partialArcCircleFit: outcome = DBHEstimator.estimate(input: input)
        }
        result = outcome
        if let r = outcome {
            state = r.confidence == .red ? .rejected : .fitted
        } else {
            state = .rejected
        }
    }
}

// MARK: - Preview / snapshot factories

public extension DBHScanViewModel {

    /// Builds a view model in the requested state with a canned result.
    /// Used by snapshot tests and SwiftUI previews so each §4.3 state is
    /// reachable without a live ARKit session.
    static func preview(
        state: State,
        result: DBHResult? = nil,
        unsupported: Bool = false
    ) -> DBHScanViewModel {
        let vm = DBHScanViewModel(
            calibration: ProjectCalibration.identity,
            session: nil,
            rawPointsWriter: nil)
        vm.applyPreview(state: state, result: result, unsupported: unsupported)
        return vm
    }

    /// Internal test hook that forces the state and cached result. Kept
    /// separate from production transitions so nothing outside tests or
    /// previews can mutate `state` arbitrarily.
    func applyPreview(
        state: State,
        result: DBHResult?,
        unsupported: Bool
    ) {
        self.state = state
        self.result = result
        if unsupported {
            self.unsupportedBanner =
                "LiDAR not supported on this device. " +
                "Use Manual Entry to record the diameter."
        }
    }
}
