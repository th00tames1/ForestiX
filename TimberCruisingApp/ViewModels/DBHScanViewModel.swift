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
        // The AR-caliper (arAwaitingLeft/arAwaitingRight) and AR-motion
        // (vioAiming/vioCapturing) stages were removed with their capture
        // arms — the DBH scan is the depth path plus manual entry.
    }

    // MARK: - Published surface

    @Published public private(set) var state: State = .idle
    @Published public private(set) var result: DBHResult?
    /// Bumped once per completed burst (incl. rejected) so the screen can
    /// fire research logging exactly when a new measurement is committed.
    @Published public private(set) var resultGeneration: Int = 0
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

    // MARK: - Manual edge-bracket (ADJUST) mode

    /// True while the DBH ADJUST mode is active: the live estimate and
    /// the capture burst measure the span between the two user-placed
    /// edge handles instead of the automatic edge-finder. Depth method
    /// only — the screen never enables it for the AR caliper/motion
    /// developer modes.
    @Published public var edgeAdjustActive: Bool = false {
        didSet { if !edgeAdjustActive { adjustAxisLatch = nil } }
    }
    /// Handle positions as fractions (0…1) of the on-screen walk axis —
    /// the same normalisation `PreviewFit.stripLeftFraction` uses, so
    /// screen x-fraction ↔ depth walk-axis fraction is the existing
    /// view↔depth mapping the fit-chord overlay already relies on.
    @Published public var edgeBracketLeftFraction: Double = 0.25
    @Published public var edgeBracketRightFraction: Double = 0.75
    /// True when the most recently committed result was captured in
    /// ADJUST mode — recorded as the entry's "capture_mode" tag
    /// ("manual" vs "auto").
    @Published public private(set) var resultCapturedManually: Bool = false

    /// Guide axis latched on the first ADJUST preview tick and held for
    /// the whole ADJUST session. `pickGuideAxis` votes per frame on the
    /// wider silhouette chord, which can flip row↔col on the cluttered
    /// scenes ADJUST exists for — and a flip changes the walk-axis
    /// extent the handle fractions map onto, jumping the value. One
    /// deterministic axis per session keeps the bracket stable.
    private var adjustAxisLatch: GuideAxis?
    /// Bracket state latched at the moment the capture "+" started the
    /// burst, so dragging a handle (or leaving ADJUST) mid-burst can't
    /// change what the in-flight capture measures.
    private var burstUsedBracket = false
    private var burstBracketLeft: Double = 0
    private var burstBracketRight: Double = 0
    private var burstBracketAxis: GuideAxis?

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

    // MARK: - Raw-capture recording (developer-mode research)

    /// When true (screen sets it from `developerMode && rawCaptureEnabled`),
    /// every completed depth burst — accepted, rejected, or ADJUST-bracket —
    /// serializes a raw-capture bundle. Off ⇒ zero recording cost.
    public var rawCaptureEnabled: Bool = false
    /// Context tags (mode / project / plot / tree / units) written into the
    /// manifest. Set by the scan screen on appear.
    public var rawCaptureContext: RawCaptureContext = .quick
    /// Latest GPS fix, supplied by the screen (which owns the location read).
    public var rawCaptureGPS: RawCaptureGPS?
    /// Bundle id of the most recently recorded burst — the screen patches
    /// its truth value + operator-accepted flag at Accept.
    ///
    /// MINTED SYNCHRONOUSLY at the end of the burst, BEFORE the detached
    /// writer runs. A fast Accept used to find this nil and silently drop the
    /// tape-measured truth; now the id always exists and the truth is either
    /// patched into the manifest or parked in a sidecar the writer folds in.
    @Published public private(set) var lastRecordedBundleID: String?
    /// Outcome of the most recent capture attempt — the screen renders this
    /// verbatim so a failed write can never look like a saved one.
    @Published public private(set) var lastCaptureOutcome: RawCaptureOutcome?
    /// Representative depth frames (one per sub-sample) retained across the
    /// burst for serialization. Only populated while recording is armed.
    private var recordFrames: [ARDepthFrame] = []
    /// Reference camera JPEG grabbed at burst start (developer mode only).
    private var recordReferenceJPEG: Data?

    // MARK: - Burst state

    /// Hold-steady capture: the burst is now SEVERAL sub-measurements taken
    /// over a few seconds. Each sub-sample runs the full chord/arc estimate
    /// over `sampleWindowSec` of frames; the final value keeps the 3
    /// sub-samples closest to the median and averages them (5 samples →
    /// the 2 largest deviations are trimmed). 1-based progress is published
    /// so the screen can show "Capturing k/5 — hold steady".
    public let captureSampleTotal: Int = 5
    private let sampleWindowSec: TimeInterval = 0.5
    @Published public private(set) var captureSampleIndex: Int = 0
    private var subSamples: [DBHResult] = []
    private var sampleStartTime: TimeInterval = 0
    /// Monotonic id for the current capture — lets the stall watchdog
    /// no-op when its capture has already finished or a new one started.
    private var captureGeneration: Int = 0
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
    /// far longer than a single-shot segmentation pipeline (e.g.
    /// Single-Shot SAM), which publishes on one fit. Loosened to 12 %/2-frames — still
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

    /// Shared-session client token — one per view-model instance so a
    /// mid-transition overlap (DBH cover dismissing while the Height
    /// cover attaches) can never pause the session under the new screen.
    private let arClientID = UUID()

    public init(
        calibration: ProjectCalibration,
        session: ARKitSessionManager? = nil,
        rawPointsWriter: (@Sendable ([SIMD2<Double>]) -> String?)? = nil,
        method: DBHMeasurementMethod = .chord
    ) {
        // Default to the APP-SHARED session (field round 8): one ARKit
        // world frame across all measure screens, so the sampling-plot
        // anchor placed elsewhere renders here too.
        self.session = session ?? .shared
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
        // pointing at while entering DBH manually. Attaching is internally
        // guarded against unsupported configurations, so it's safe to
        // call on any device. The DBH configuration keeps the depth
        // stream + VIO features + scene reconstruction on; applied with
        // no reset options, so world anchors survive screen entry.
        session.attach(client: arClientID, configuration: .dbhScan)
        subscribeToDepth()

        let resettable = state == .idle || state == .accepted
            || state == .rejected || state == .manualEntry
        guard resettable else { return }

        // Depth point-cloud burst — the only capture path. Falls back to
        // manual entry on a device without a depth sensor.
        state = isLiDARSupported ? .aligning : .manualEntry
    }

    public func onDisappear() {
        depthCancellable?.cancel()
        depthCancellable = nil
        // Abort any in-flight capture so a watchdog that fires while the
        // screen is backgrounded (scenePhase .inactive keeps the run loop
        // alive) can't commit a phantom partial result. Bumping the
        // generation invalidates the pending watchdog Tasks; we roll state
        // back to the aiming stage WITHOUT touching `result`.
        if state == .capturing {
            captureGeneration &+= 1
            burstBuffer.removeAll(keepingCapacity: true)
            subSamples.removeAll(keepingCapacity: true)
            recordFrames.removeAll(keepingCapacity: true)
            recordReferenceJPEG = nil
            captureSampleIndex = 0
            state = .aligning
        }
        session.detach(client: arClientID)
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
            // Close the current sub-sample once its window elapsed AND it
            // has enough frames for a chord estimate (≥5). Slow depth
            // delivery just stretches the window; the watchdog bounds it.
            let elapsed = ProcessInfo.processInfo.systemUptime - sampleStartTime
            if elapsed >= sampleWindowSec, burstBuffer.count >= 5 {
                finishSubSample()
            }
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

        // Auto-pick the across-the-trunk axis (orientation-robust) instead of
        // a fixed orientation guess, which on some devices walked the strip
        // along the trunk and under-read the diameter to a few cm.
        let axis = DBHEstimator.pickGuideAxis(
            frame: frame,
            tapPixel: SIMD2(Double(cx), Double(cy)),
            calibration: calibration)

        // ADJUST (edge-bracket) mode bypasses BOTH the automatic
        // edge-finding and the stability/EMA machinery: the live value
        // and the chord bar must track the user's handles exactly, so
        // the raw bracket fit is published on every preview tick. The
        // guide axis is latched for the whole ADJUST session (see
        // `adjustAxisLatch`).
        if edgeAdjustActive {
            if adjustAxisLatch == nil { adjustAxisLatch = axis }
            let fit = DBHEstimator.bracketChordFit(
                frame: frame,
                guideAxis: adjustAxisLatch ?? axis,
                leftFraction: edgeBracketLeftFraction,
                rightFraction: edgeBracketRightFraction)
            smoothedPreviewDbhCm = nil
            smoothedCenterWorldXZ = nil
            lastTapDepthHint = nil
            recentRawDiameters.removeAll()
            isStable = false
            consecutiveRedFrames = 0
            previewFit = fit
            previewDbhCm = fit?.diameterCm
            previewTier = fit?.tier
            previewStatusText = nil
            let pose = frame.cameraPoseWorld
            guideRowWorldY = pose.columns.3.y
            if let stem = fit?.centerWorldXZ {
                let camXZ = SIMD2<Double>(Double(pose.columns.3.x),
                                          Double(pose.columns.3.z))
                let d = stem - camXZ
                distanceToStemCenterM = Float((d.x * d.x + d.y * d.y).squareRoot())
            } else {
                distanceToStemCenterM = nil
            }
            return
        }

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
        if edgeAdjustActive {
            // ADJUST mode: the estimate is user-constrained, so the only
            // gate is that a bracket fit exists on screen. `.aligning`
            // is allowed too — centre-pixel depth stability is an
            // auto-path concept, and the bracket's own median depth
            // already validated inside `bracketChordFit`.
            guard state == .armed || state == .aligning else { return }
        } else {
            guard state == .armed else { return }
        }
        // Phase 18.4 — the tap is now the *only* way to start the
        // burst, so we keep the gate loose: any fit visible on screen
        // (red rejections excluded) is enough. Waiting for the
        // stability gate to latch before allowing a tap is what made
        // the previous auto-capture flow feel sluggish; if the cruiser
        // is committed enough to tap, the burst's own §7.1 tree will
        // catch a fit that's too noisy to record.
        guard let fit = previewFit, fit.tier != .red else { return }
        // Latch the bracket so mid-burst handle drags / mode exits can't
        // change what this capture measures.
        burstUsedBracket = edgeAdjustActive
        burstBracketLeft = edgeBracketLeftFraction
        burstBracketRight = edgeBracketRightFraction
        burstBracketAxis = adjustAxisLatch
        burstBuffer.removeAll(keepingCapacity: true)
        burstTap = tapPixel
        subSamples.removeAll(keepingCapacity: true)
        // Raw-capture arm: start a fresh representative-frame set and grab
        // the reference camera image at the burst's first moment.
        recordFrames.removeAll(keepingCapacity: true)
        recordReferenceJPEG = rawCaptureEnabled ? session.currentCameraImageJPEG() : nil
        // A new burst supersedes the previous capture's saved / NOT-saved pill.
        lastCaptureOutcome = nil
        captureSampleIndex = 1
        sampleStartTime = ProcessInfo.processInfo.systemUptime
        captureGeneration &+= 1
        let generation = captureGeneration
        state = .capturing
        // Stall watchdog — if depth frames stop arriving mid-capture the
        // sub-sample close condition never fires; finalise with whatever
        // sub-samples were collected instead of hanging in `.capturing`.
        let deadline = Double(captureSampleTotal) * sampleWindowSec + 2.5
        Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(deadline * 1_000_000_000))
            guard let self, self.state == .capturing,
                  self.captureGeneration == generation else { return }
            self.finalizeCapture()
        }
    }

    public func retake() {
        burstBuffer.removeAll()
        subSamples.removeAll(keepingCapacity: true)
        recordFrames.removeAll(keepingCapacity: true)
        recordReferenceJPEG = nil
        lastCaptureOutcome = nil
        // The previous bundle is no longer "the capture on screen". In the
        // cruise tally the loop retakes between trees, and a truth typed for
        // the NEXT tree must never land on the LAST tree's bundle. (Accept
        // applies its truth before the loop calls retake, so nothing typed
        // for the accepted tree is affected.)
        lastRecordedBundleID = nil
        captureSampleIndex = 0
        captureGeneration &+= 1
        result = nil
        state = isLiDARSupported ? .aligning : .manualEntry
    }

    public func accept() {
        guard let r = result, r.confidence != .red else { return }
        state = .accepted
        markLastBundleAccepted()
    }

    /// The host could NOT store the accepted reading. Drop back to the result
    /// panel so the diameter is still on screen and Accept is tappable again —
    /// the alternative (sitting in `.accepted`, which renders no actions at
    /// all) would hide a lost measurement behind a dead end.
    ///
    /// Mirrors `HeightScanViewModel.acceptFailed()`, including the routing:
    /// back to whichever result stage the Accept came from. `accept()` refuses
    /// a red fit, so `.rejected` is only reachable defensively.
    public func acceptFailed() {
        guard state == .accepted, let r = result else { return }
        state = (r.confidence == .red) ? .rejected : .fitted
    }

    /// Stamp `operator_accepted` on the bundle this Accept confirms. Distinct
    /// from the record-time `tier_ok` gate, and safe to call while the writer
    /// is still running (the store parks it and the writer folds it in).
    private func markLastBundleAccepted() {
        guard rawCaptureEnabled, let id = lastRecordedBundleID else { return }
        Task.detached(priority: .utility) {
            _ = RawCaptureStore.markAccepted(id: id)
        }
    }

    public func enterManualEntry() {
        state = .manualEntry
    }

    /// Unit system the manual-entry field is being typed in. The screen keeps
    /// this in sync with AppSettings — under imperial the field prompts for
    /// INCHES, and the typed number used to be stored straight into
    /// `diameterCm`, corrupting every manual imperial entry by 2.54x.
    public var manualEntryUnits: UnitSystem = .metric

    public func submitManualEntry() {
        guard let typed = TruthInput.parsePositive(manualDbhCm) else { return }
        let cm = manualEntryUnits == .imperial ? Units.inchesToCm(typed) : typed
        guard cm > 0 else { return }
        resultCapturedManually = false
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

    /// Close the current sub-sample: run the full chord/arc estimate over
    /// the window's frames, stash the result, and either open the next
    /// window or finalise the capture after the last one.
    private func finishSubSample() {
        let frames = burstBuffer
        burstBuffer.removeAll(keepingCapacity: true)
        // Retain ONE representative depth frame per sub-sample (≤5 → the
        // depth_0..4.bin bundle layout) for raw-capture replay.
        if rawCaptureEnabled, let rep = frames.first { recordFrames.append(rep) }
        if let firstFrame = frames.first {
            let axis = DBHEstimator.pickGuideAxis(
                frame: firstFrame,
                tapPixel: burstTap,
                calibration: calibration)
            // Dispatch: manual bracket (ADJUST captures, latched at tap
            // time) → chord method → original §7.1 partial-arc pipeline.
            let outcome: DBHResult?
            if burstUsedBracket {
                outcome = DBHEstimator.bracketChordEstimate(
                    frames: frames,
                    guideAxis: burstBracketAxis ?? axis,
                    leftFraction: burstBracketLeft,
                    rightFraction: burstBracketRight,
                    calibration: calibration)
            } else {
                let input = DBHScanInput(
                    frames: frames,
                    tapPixel: burstTap,
                    guideAxis: axis,
                    projectCalibration: calibration,
                    rawPointsWriter: rawPointsWriter)
                switch dbhMeasurementMethod {
                case .chord:               outcome = DBHEstimator.chordEstimate(input: input)
                case .partialArcCircleFit: outcome = DBHEstimator.estimate(input: input)
                }
            }
            if let outcome { subSamples.append(outcome) }
        }
        if captureSampleIndex >= captureSampleTotal {
            finalizeCapture()
        } else {
            captureSampleIndex += 1
            sampleStartTime = ProcessInfo.processInfo.systemUptime
        }
    }

    /// Trimmed-mean aggregation over the capture's sub-samples: the 3
    /// closest to the median diameter are averaged (5 samples → the 2
    /// largest deviations dropped). Fewer than 3 usable sub-samples means
    /// the trunk couldn't be read consistently — reject.
    private func finalizeCapture() {
        let samples = subSamples
        subSamples.removeAll(keepingCapacity: true)
        burstBuffer.removeAll(keepingCapacity: true)
        captureSampleIndex = 0
        captureGeneration &+= 1
        let outcome = DBHEstimator.aggregateSamples(samples)
        // On aggregate failure surface a red sub-sample if there was one —
        // it carries the human-readable rejection reason.
        result = outcome ?? samples.first(where: { $0.confidence == .red })
        resultCapturedManually = burstUsedBracket
        if let r = result, r.confidence != .red, outcome != nil {
            state = .fitted
        } else {
            state = .rejected
        }
        resultGeneration &+= 1
        recordRawBurstIfNeeded()
    }

    /// Serialize the raw-capture bundle for the just-finished depth burst
    /// (developer mode only). The bundle id is minted HERE, synchronously, so
    /// the truth field has a target the instant the burst ends; the estimator
    /// + IO then run detached over Sendable snapshots and report an explicit
    /// saved/failed outcome back to the main actor.
    ///
    /// The context (mode / project / plot / TREE NUMBER / units) is snapshot
    /// at record time from `rawCaptureContext`, which the screen refreshes on
    /// every capture — the cruise tally reuses one screen instance, so a
    /// context captured once at `.onAppear` froze every bundle in the plot at
    /// the first tree's number.
    private func recordRawBurstIfNeeded() {
        // A NEW capture supersedes the previous bundle on every path below,
        // the failing ones included. Leaving the id set meant a capture that
        // never wrote anything still pointed at the PREVIOUS tree's bundle,
        // so a truth typed for this tree had a live target on the wrong tree.
        // Only a freshly minted id may set it again.
        lastRecordedBundleID = nil
        guard rawCaptureEnabled else { return }
        guard !recordFrames.isEmpty else {
            lastCaptureOutcome = .failed(reason: "no depth frames in the burst")
            return
        }
        let framesToRecord = recordFrames
        recordFrames.removeAll(keepingCapacity: true)
        let tap = burstTap
        let cal = calibration
        let algo = dbhMeasurementMethod
        let bracket = RawCaptureManifest.DBHBundle.Bracket(
            enabled: burstUsedBracket, left: burstBracketLeft, right: burstBracketRight)
        let manual = burstUsedBracket
        let ctx = rawCaptureContext
        let jpeg = recordReferenceJPEG
        let gps = rawCaptureGPS
        recordReferenceJPEG = nil
        let id = UUID().uuidString
        lastRecordedBundleID = id
        lastCaptureOutcome = nil
        Task.detached(priority: .utility) { [weak self] in
            let outcome = RawCaptureRecorder.recordDBH(
                id: id,
                frames: framesToRecord, tapPixel: tap, calibration: cal,
                algorithm: algo, bracket: bracket,
                captureManual: manual, context: ctx,
                referenceJPEG: jpeg, gps: gps)
            await MainActor.run { self?.lastCaptureOutcome = outcome }
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
