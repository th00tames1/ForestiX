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
    /// Phase 16.2 hysteresis. The earlier 8 % gate flickered on real
    /// device tests because typical LiDAR + RANSAC variance is 5–12 %
    /// even when the cruiser stands still, and a single red frame
    /// cleared the history and reset the gate. New scheme:
    ///   • Enter stable when CoV ≤ 0.10 over 3+ frames
    ///   • Stay stable until CoV exceeds 0.18 (deadband ⇒ no flicker)
    ///   • Tolerate 1–2 transient red frames without resetting; only
    ///     `redResetCount` consecutive reds wipes the history.
    private var isStable: Bool = false
    private let stabilityEnterCoV: Double = 0.10
    private let stabilityExitCoV: Double = 0.18
    private var consecutiveRedFrames: Int = 0
    private let redResetCount: Int = 3

    // MARK: - Construction

    public init(
        calibration: ProjectCalibration,
        session: ARKitSessionManager? = nil,
        rawPointsWriter: (@Sendable ([SIMD2<Double>]) -> String?)? = nil
    ) {
        self.session = session ?? ARKitSessionManager()
        self.calibration = calibration
        self.rawPointsWriter = rawPointsWriter
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
        let fit = DBHEstimator.previewFit(
            frame: frame,
            tapPixel: SIMD2(Double(cx), Double(cy)),
            guideAxis: axis,
            discontinuityThresholdM: calibration.depthDiscontinuityM)
        previewFit = fit

        // Stability + tier gate (Phase 16.2). Hysteresis on the CoV
        // window so a borderline frame doesn't flip the gate, and a
        // single red frame no longer clears the history — only
        // `redResetCount` consecutive reds force a reset.
        let publishable: Bool
        let stabilityNote: String?
        if let f = fit, f.tier != .red {
            consecutiveRedFrames = 0
            recentRawDiameters.append(f.diameterCm)
            if recentRawDiameters.count > recentRawDiameterCapacity {
                recentRawDiameters.removeFirst()
            }
            if recentRawDiameters.count >= 3 {
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
            publishable = isStable
            stabilityNote = publishable ? nil : "Stabilizing…"
            if publishable {
                if let prev = smoothedPreviewDbhCm {
                    smoothedPreviewDbhCm = previewEMAAlpha * f.diameterCm
                                         + (1 - previewEMAAlpha) * prev
                } else {
                    smoothedPreviewDbhCm = f.diameterCm
                }
            } else {
                smoothedPreviewDbhCm = nil
            }
        } else {
            // Tolerate transient reds — keep the history and the
            // smoothed value alive so a one-off bad frame doesn't
            // restart the whole stabilisation.
            consecutiveRedFrames += 1
            if consecutiveRedFrames >= redResetCount {
                recentRawDiameters.removeAll()
                smoothedPreviewDbhCm = nil
                isStable = false
            }
            publishable = false
            stabilityNote = nil
        }

        previewDbhCm = publishable ? smoothedPreviewDbhCm : nil
        previewTier = publishable ? fit?.tier : nil
        previewStatusText = publishable
            ? nil
            : (stabilityNote ?? fit?.rejectionReason)

        // Phase 16.3 auto-capture. Tap-to-capture was impractical on
        // device — both hands hold the phone, so a finger tap on the
        // screen breaks the aim. The stability gate already requires
        // 3+ consistent frames before flipping `publishable`, so when
        // it does we trust that long enough to start the burst on its
        // own. Manual `tap()` stays as an override; if the cruiser
        // wants to commit early they still can.
        if state == .armed && publishable {
            burstBuffer.removeAll(keepingCapacity: true)
            burstTap = SIMD2(Double(cx), Double(cy))
            state = .capturing
        }

        // Distance readout — camera position XZ vs stem axis XZ.
        // Uses the frame's own camera pose to stay consistent with the
        // fit's reference frame.
        let pose = frame.cameraPoseWorld
        guideRowWorldY = pose.columns.3.y
        if let f = fit {
            let camXZ = SIMD2<Double>(Double(pose.columns.3.x),
                                       Double(pose.columns.3.z))
            let d = f.centerWorldXZ - camXZ
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
        // Phase 14.4: only let the burst start when the live preview
        // is publishable (not red, not still stabilising). Otherwise
        // the cruiser would tap "Capture" on a fit that the burst's
        // §7.1 sanity tree is going to reject anyway, eroding trust
        // in the on-screen number. The status badge already explains
        // why the tap didn't take ("Stabilizing…" or the rejection
        // reason), so the cruiser knows what to do next.
        guard previewDbhCm != nil else { return }
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
        let outcome = DBHEstimator.estimate(input: input)
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
