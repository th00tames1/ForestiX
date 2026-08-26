// Spec §4.3 DBH state machine + §5.2 screen contract. Observes
// ARKitSessionManager for depth frames, buffers a burst on user tap,
// runs DBHEstimator, and surfaces the state transitions for the view.
//
// The view model is cross-platform. On iOS it starts a real ARKit
// session via ARKitSessionManager; on macOS the same session type
// compiles to a no-op stub so previews/snapshots compile unchanged.
// Tests drive the state machine directly through the `preview` factory.

import Foundation
#if canImport(UIKit)
import UIKit
#endif
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
    /// True when `previewStatusText` is the developer-mode BENCH DIAGNOSTIC
    /// (axis / handle fractions / depth grid) rather than advice to the
    /// cruiser. The screen suppresses the acquisition hint whenever a
    /// specific advice line is already up — two sentences telling the
    /// cruiser different things about one failure is worse than one — but a
    /// diagnostic is not advice, so it must not suppress anything.
    ///
    /// Developer mode is NOT bench-only in this repo: the tape-truth field,
    /// the research CSV row and the typed-truth capture are all gated on it,
    /// so the accuracy-study cruiser works with it ON in the stand. Letting
    /// the diagnostic suppress the hint hid field report 15's one sentence
    /// of guidance from the only operator the study depends on, and made iOS
    /// print something different from Android for the identical state.
    @Published public private(set) var previewStatusIsDiagnostic: Bool = false

    // MARK: - ADJUST live-readout settling (field round 10)

    /// True while the ADJUST bracket is spanning more than one surface, so the
    /// number on screen is the last one taken off bark rather than this tick's.
    ///
    /// THE DEFECT THIS ANSWERS is the diameter that jumps by inches with the
    /// phone held still — see `DBHEstimator.bracketCoreDepthSpreadM` for the
    /// measurement and the cause. The correction is HERE and not in the
    /// estimator because the estimator is frozen and does not need correcting:
    /// the stored value is a median of five frames and the excursions do not
    /// reach it. What they reach is the readout the cruiser is watching while
    /// they place the handles, which publishes the raw per-frame fit.
    ///
    /// WHY HOLD RATHER THAN BLANK. A bad tick lands roughly one time in ten at
    /// 10 Hz, so blanking on each one would strobe the number in and out —
    /// harder to read than the jump it replaces. Holding shows the most recent
    /// diameter actually measured across bark, which is a real reading and not
    /// an invented one, and a good tick publishes immediately, so a handle drag
    /// still tracks the handles exactly as the ADJUST design requires. Only a
    /// SUSTAINED bad run (`bracketUnsettledGraceSec`) blanks the value, and
    /// then it says why in a sentence.
    @Published public private(set) var bracketDepthUnsettled: Bool = false

    /// How long the bracket may keep reading two surfaces before the held
    /// value stops being current enough to show.
    ///
    /// 1.0 s is long enough that the intermittent excursion never reaches it
    /// (it is one or two ticks) and short enough that a cruiser who has walked
    /// the bracket off the stem is told so while they are still holding it
    /// there, rather than reading a stale number.
    private static let bracketUnsettledGraceSec: TimeInterval = 1.0

    /// Advice for a bracket that is spanning the stem AND what is behind it.
    /// Byte identical to the Android `BRACKET_TWO_SURFACES` constant.
    ///
    /// NOT "widen it", for the same reason as the sibling line below: a wider
    /// bracket takes in more background and more diameter at once.
    public static let bracketTwoSurfacesText =
        "The bracket is reading past the trunk — narrow it onto the bark, or step closer."

    /// Monotonic time the ADJUST bracket last measured a single surface, or nil
    /// before the first such tick of this aiming session.
    private var lastSettledBracketAt: TimeInterval?
    /// The most recent diameter measured across one surface — what the readout
    /// holds while an excursion passes.
    private var lastSettledBracketCm: Double?
    /// True once `acquisitionStallSec` of aiming has gone by without a
    /// single usable fit. Field report 15: when depth won't resolve, the
    /// screen said nothing and the cruiser had no way to know that a small
    /// movement — or standing at a different distance — is what unblocks
    /// it. The screen turns this into one sentence of guidance; it never
    /// affects what gets measured.
    @Published public private(set) var acquisitionStalled: Bool = false
    /// Uptime of the last preview tick that produced a fit. Seeded when
    /// aiming begins so the hint waits out the full interval rather than
    /// flashing up on the first frame.
    private var lastFitTime: TimeInterval = 0
    private let acquisitionStallSec: TimeInterval = 2.0
    /// How long without a preview tick before the line on the value strip is
    /// treated as describing a frame that no longer exists. Five missed
    /// preview intervals (the throttle is 0.1 s), so an ordinary hiccup can't
    /// trip it and a real stop is caught inside one stall interval.
    private let depthSilentSec: TimeInterval = 0.5
    /// Drives the stall clock independently of depth delivery.
    ///
    /// `updateAcquisitionStall` runs only inside `handleDepthFrame`, so on
    /// its own it measures "frames that produced no fit" — not "no fit".
    /// The states the cruiser is actually stuck in include the ones where
    /// NO frame arrives at all: a session interruption, thermal throttle,
    /// or a frame whose `sceneDepth` is nil so the manager publishes
    /// nothing. Those froze `acquisitionStalled` at whatever it last was
    /// and the hint never came up. Android ticks its stall outside the
    /// frame block for exactly this reason (DBHScanScreen.kt).
    private var stallTicker: Timer?

    // MARK: - Auto arm gate

    /// Depth window the Auto crosshair may arm in.
    ///
    /// IT IS THE ESTIMATOR'S WINDOW, not a second opinion about it. The gate
    /// was written in Phase 2 against the partial-arc pipeline, whose own
    /// tap-depth window was 0.5–3.0 m, and the two matched exactly. Phase 19
    /// widened the chord estimator to 0.3–5.0 m (`chordPreviewFit` /
    /// `chordEstimate`, both of which run this capture) and nobody moved the
    /// gate, so between 3 and 5 m the cruiser got a live diameter in the
    /// badge, a red crosshair, and a "+" that did nothing — a refusal of
    /// readings the estimator behind it would have made correctly.
    ///
    /// Widening the gate does not widen what the estimator will accept: a
    /// stem at 5 m subtends few enough pixels that the silhouette walk drops
    /// the rows (`w < 5`) and the fit fails on its own, and the plausibility
    /// ceiling is unchanged. This only stops the app refusing before the
    /// estimator has been asked.
    public static let armDepthRangeM: ClosedRange<Float> = 0.3...5.0

    /// Why the last "+" did nothing. A capture button that refuses in silence
    /// is indistinguishable from a broken one — the same defect that was
    /// fixed on the height anchor tap (`HeightScanViewModel.cameraNotReadyText`).
    /// Cleared as soon as the condition it describes lifts, so it can never
    /// outlive its own truth.
    @Published public private(set) var captureRefusalReason: String?

    /// Tap refusals. Byte identical to the Android `DBHScanScreen.kt`
    /// constants of the same names.
    ///
    /// None of them names the metres of the window: iOS arms over its
    /// estimator's 0.3–5.0 m and Android over its own 0.4–3.5 m, so a
    /// sentence quoting a number could not be the same sentence on both. The
    /// direction to walk is what the cruiser can act on anyway.
    public static let tooFarText =
        "Too far from the trunk to read depth — step closer, then tap + again."
    public static let tooCloseText =
        "Too close to the trunk to read depth — step back, then tap + again."
    public static let noTrunkLockText =
        "No trunk lock yet — hold the crosshair on the bark until a diameter shows, then tap + again."

    // MARK: - Manual edge-bracket (ADJUST) mode

    /// True while the DBH ADJUST mode is active: the live estimate and
    /// the capture burst measure the span between the two user-placed
    /// edge handles instead of the automatic edge-finder. Depth method
    /// only — the screen never enables it for the AR caliper/motion
    /// developer modes.
    @Published public var edgeAdjustActive: Bool = false {
        // The held ADJUST value goes with the latch: it was measured through
        // the bracket, and there is no bracket outside this mode.
        didSet {
            if !edgeAdjustActive {
                adjustAxisLatch = nil
                resetBracketSettling()
            }
        }
    }
    /// Handle positions as fractions (0…1) of the on-screen walk axis —
    /// the same normalisation `PreviewFit.stripLeftFraction` uses, so
    /// screen x-fraction ↔ depth walk-axis fraction is the existing
    /// view↔depth mapping the fit-chord overlay already relies on.
    ///
    /// These initial values are a PLACEHOLDER, not the first-run bracket:
    /// `DBHScanScreen.onAppear` seeds both handles from the remembered
    /// width (`AppSettings.dbhBracketHalfWidth`) before depth is
    /// subscribed, and that is the number the cruiser sees. They are
    /// written from the same constant so a unit test or a preview that
    /// drives the view model directly starts somewhere sane rather than at
    /// a bracket across half the screen.
    @Published public var edgeBracketLeftFraction: Double
        = 0.5 - AppSettings.defaultBracketHalfWidth
    @Published public var edgeBracketRightFraction: Double
        = 0.5 + AppSettings.defaultBracketHalfWidth
    /// True when the most recently committed result was captured in
    /// ADJUST mode — recorded as the entry's "capture_mode" tag
    /// ("manual" vs "auto").
    @Published public private(set) var resultCapturedManually: Bool = false

    /// WHICH HAND PLACED THE EDGES — "auto", "manual", or "segmented".
    ///
    /// `resultCapturedManually` cannot carry this: it is a Bool, and a
    /// model-placed bracket is a bracket, so it read "manual" and was
    /// indistinguishable in the record from a thumb. The whole argument for
    /// routing segmentation through the bracket was that a corpus could be
    /// split on it later, and that is only true if the record says which.
    @Published public private(set) var resultCaptureMode: String = "auto"

    // MARK: - Segmentation-driven edges

    /// THE MODEL'S ANSWER, or nil while it has none.
    ///
    /// Published so the screen can say the bracket is not the cruiser's — a
    /// bracket that moves on its own and is not marked as automatic is a
    /// bracket the cruiser will trust as their own placement.
    @Published public private(set) var segmentedExtent: StemExtent?
    /// Why segmentation is not answering, for the screen to print once.
    @Published public private(set) var segmenterAvailability: SegmenterAvailability = .noModel
    /// Turned on by the screen from `AppSettings.dbhAutoSegmentation`.
    @Published public var segmentationEnabled = false {
        didSet {
            guard segmentationEnabled != oldValue else { return }
            // OFF MEANS STOP, not "keep spinning and fail the guard".
            //
            // This used to clear the extent and nothing else, so turning the
            // setting off — or turning developer mode off, which closes the
            // same gate — left the detached loop running for the life of the
            // view model, waking eight times a second to fail a guard, with
            // the 11 MB graph still resident. The commit that added the gate
            // claimed the feed stopped on the same tick. It did not.
            if segmentationEnabled { startSegmentationFeed() }
            else { stopSegmentationFeed() }
        }
    }

    #if canImport(OnnxRuntimeBindings)
    /// Built on first use, not at init: loading an 11 MB graph costs a beat
    /// the cruiser would feel if it happened while the screen was appearing,
    /// and most sessions never turn this on.
    private var segmenter: TreeSegmenter?
    private var segmentationTask: Task<Void, Never>?

    /// Consecutive frames the model has failed to find a stem on.
    private var segmentationMisses = 0

    /// How many in a row before the bracket is handed back to the auto walk.
    /// Eight ticks at 8 Hz is one second of nothing — long enough to ride out
    /// the ordinary gaps in a 40 %-hit-rate model, short enough that a cruiser
    /// who swings off the tree is not left holding a stale bracket.
    private static let segmentationMissesBeforeRelease = 8

    /// ONE INFERENCE AT A TIME, OFF THE MAIN ACTOR, at a rate the AR session
    /// can absorb.
    ///
    /// `@MainActor` is on this whole class, so a bare `Task {}` inherits it
    /// and would run fifty to a hundred milliseconds of ONNX on the main
    /// thread eight times a second, in the middle of a live AR session. It is
    /// `Task.detached` for that reason, and everything it touches on the way
    /// in and out is hopped explicitly.
    ///
    /// 8 Hz, not per frame. The network is tens of milliseconds and ARKit
    /// delivers at 60 — chasing every frame would spend the whole device on a
    /// bracket that only has to keep up with a cruiser's hands. The feed also
    /// holds no ARFrame: the pixel buffer is read, used and dropped inside one
    /// tick, because ARKit recycles that pool and a retained buffer starves
    /// the session.
    private func startSegmentationFeed() {
        guard segmentationTask == nil else { return }
        segmentationTask = Task.detached(priority: .utility) { [weak self] in
            while !Task.isCancelled {
                guard let self else { return }
                let ready = await self.prepareSegmentationTick()
                guard let ready else {
                    try? await Task.sleep(nanoseconds: 250_000_000)
                    continue
                }
                // The only work off the actor, and the only slow part.
                let mask = ready.segmenter.segment(pixelBuffer: ready.buffer)
                await self.applySegmented(mask: mask,
                                         viewSize: ready.viewSize,
                                         mapping: ready.mapping,
                                         depthWidth: ready.depthWidth,
                                         depthHeight: ready.depthHeight)
                try? await Task.sleep(nanoseconds: 125_000_000)
            }
        }
    }

    /// Everything the tick needs off the actor, gathered on it in one hop.
    private struct SegmentationTick {
        let segmenter: TreeSegmenter
        /// A COPY. `currentCameraPixelBuffer()` says in as many words that the
        /// caller must not hold its buffer past the frame — ARKit recycles the
        /// pool and a retained one starves the session — and this struct is
        /// handed across an actor hop to a detached task that then spends tens
        /// of milliseconds in ONNX with it. Copying costs one frame's worth of
        /// memcpy on the actor and hands the pool's buffer straight back.
        let buffer: CVPixelBuffer
        let viewSize: CGSize
        let mapping: DepthViewMapping
        let depthWidth: Int
        let depthHeight: Int
    }

    private func prepareSegmentationTick() -> SegmentationTick? {
        guard segmentationEnabled else { return nil }
        if segmenter == nil {
            let made = TreeSegmenter()
            segmenter = made
            segmenterAvailability = made.availability
        }
        guard let segmenter, segmenter.availability.isReady,
              let live = session.currentCameraPixelBuffer(),
              let buffer = Self.copyPixelBuffer(live),
              let frame = session.latestDepthFrame,
              let mapping = frame.viewMapping,
              viewSize.width > 1, viewSize.height > 1
        else { return nil }
        return SegmentationTick(segmenter: segmenter, buffer: buffer,
                                viewSize: viewSize, mapping: mapping,
                                depthWidth: frame.width, depthHeight: frame.height)
    }

    /// A deep copy of one camera frame, so nothing of ARKit's outlives the
    /// call that vended it. Returns nil rather than risking a partial copy.
    private nonisolated static func copyPixelBuffer(_ src: CVPixelBuffer) -> CVPixelBuffer? {
        let w = CVPixelBufferGetWidth(src)
        let h = CVPixelBufferGetHeight(src)
        let fmt = CVPixelBufferGetPixelFormatType(src)
        var out: CVPixelBuffer?
        let attrs: [CFString: Any] = [kCVPixelBufferIOSurfacePropertiesKey: [:] as CFDictionary]
        guard CVPixelBufferCreate(kCFAllocatorDefault, w, h, fmt,
                                  attrs as CFDictionary, &out) == kCVReturnSuccess,
              let dst = out else { return nil }
        CVPixelBufferLockBaseAddress(src, .readOnly)
        CVPixelBufferLockBaseAddress(dst, [])
        defer {
            CVPixelBufferUnlockBaseAddress(dst, [])
            CVPixelBufferUnlockBaseAddress(src, .readOnly)
        }
        let planes = max(1, CVPixelBufferGetPlaneCount(src))
        if CVPixelBufferGetPlaneCount(src) == 0 {
            guard let s = CVPixelBufferGetBaseAddress(src),
                  let d = CVPixelBufferGetBaseAddress(dst) else { return nil }
            memcpy(d, s, CVPixelBufferGetDataSize(src))
            return dst
        }
        for i in 0..<planes {
            guard let s = CVPixelBufferGetBaseAddressOfPlane(src, i),
                  let d = CVPixelBufferGetBaseAddressOfPlane(dst, i) else { return nil }
            let rows = CVPixelBufferGetHeightOfPlane(src, i)
            let sBpr = CVPixelBufferGetBytesPerRowOfPlane(src, i)
            let dBpr = CVPixelBufferGetBytesPerRowOfPlane(dst, i)
            let bpr = min(sBpr, dBpr)
            for r in 0..<rows {
                memcpy(d.advanced(by: r * dBpr), s.advanced(by: r * sBpr), bpr)
            }
        }
        return dst
    }

    /// The mask's two edges become the bracket's two handles.
    ///
    /// WRITING THE BRACKET rather than adding a second width path is the
    /// whole design. `bracketChordEstimate` is shipped, field-checked and
    /// already the thing a cruiser's thumbs drive; the model is just another
    /// way of placing the same two handles, so the capture, the depth
    /// geometry, the middle-half sampling and the tier all stay exactly as
    /// they are. Nothing new measures anything.
    ///
    /// Ignored while the cruiser is in ADJUST: they have taken hold of the
    /// bracket, and a bracket that fights a thumb is worse than no bracket.
    private func applySegmented(mask: TreeSegDecode.StemMask?,
                                viewSize: CGSize,
                                mapping: DepthViewMapping,
                                depthWidth: Int,
                                depthHeight: Int) {
        let extent = mask.flatMap {
            TreeSegDecode.extentAcrossView(mask: $0, viewSize: viewSize,
                                           mapping: mapping,
                                           depthWidth: depthWidth,
                                           depthHeight: depthHeight)
        }
        // A MISS DOES NOT DROP THE BRACKET.
        //
        // The model answers on about 40 % of frames, and `segmentedExtent`
        // used to be assigned unconditionally — so it went nil on every miss,
        // `segmentationDroveTheBracket` went false with it, and the screen
        // flipped between the bracket path and the auto depth walk several
        // times a second. Two different measurement methods taking turns
        // inside one capture is not a method. A miss now HOLDS the last
        // placement for a beat; only a run of them lets go, and then the auto
        // path takes over cleanly and stays.
        if let extent {
            segmentedExtent = extent
            segmentationMisses = 0
        } else {
            segmentationMisses += 1
            if segmentationMisses >= Self.segmentationMissesBeforeRelease {
                segmentedExtent = nil
            }
        }
        guard let extent, !edgeAdjustActive else { return }
        edgeBracketLeftFraction = extent.leftFraction
        edgeBracketRightFraction = extent.rightFraction
    }

    /// Stop inferring and give the graph back. Called from `onDisappear` —
    /// without it the loop and 11 MB outlive the screen for as long as
    /// anything holds the view model.
    public func stopSegmentationFeed() {
        segmentationTask?.cancel()
        segmentationTask = nil
        segmenter = nil
        segmentedExtent = nil
        segmentationMisses = 0
    }
    #else
    private func startSegmentationFeed() {
        segmenterAvailability = .unsupportedPlatform
    }
    public func stopSegmentationFeed() { segmentedExtent = nil }
    #endif

    /// Guide axis latched on the first ADJUST preview tick and held for the
    /// whole session. `pickGuideAxis` votes per frame on the wider
    /// silhouette chord, which can flip row↔col on the cluttered scenes
    /// ADJUST exists for, and a flip changes the extent the handle fractions
    /// are read against — so the value jumps. One deterministic axis per
    /// session keeps the bracket stable.
    ///
    /// It was removed once, on the argument that a latch taken before the
    /// cruiser has aimed can fix the WRONG axis for a whole plot. That
    /// argument still stands and is worth revisiting with a device. It is
    /// not worth revisiting from a keyboard: the removal shipped alongside
    /// the mapping rewrite and the pair left the screen blank.
    ///
    /// WHAT CHANGED WITH "NO AUTO INTERLUDE" (field report, this round).
    /// ADJUST used to arm only after the automatic path had produced a fit,
    /// so the first ADJUST tick — the one that latched — was always taken on
    /// a frame with a stem in it. The screen now opens on the bracket, so
    /// the first tick lands while the phone is still coming up off the
    /// cruiser's side. Latching THAT vote is exactly the failure the
    /// paragraph above warns about, and it is silent: the wrong axis swaps
    /// the extent the fractions are read against (256 vs 192 px), which
    /// scales every diameter of the plot by 4:3 without any visible symptom.
    /// So the latch now waits for a tick that actually MEASURED something —
    /// see `handleDepthFrame`. That is the same condition the interlude used
    /// to guarantee, restored without the interlude.
    private var adjustAxisLatch: GuideAxis?

    /// Bracket state latched at the moment the capture "+" started the
    /// burst, so dragging a handle (or leaving ADJUST) mid-burst can't
    /// change what the in-flight capture measures.
    /// Whether the two handles about to be latched were placed by the model
    /// rather than by the cruiser. Segmentation only writes them while ADJUST
    /// is off, so the two can never both be true of one capture.
    var segmentationDroveTheBracket: Bool {
        segmentationEnabled && segmentedExtent != nil && !edgeAdjustActive
    }

    private var burstUsedBracket = false
    /// Latched with the bracket: was it the model that placed it?
    private var burstUsedSegmentation = false
    private var burstBracketLeft: Double = 0
    private var burstBracketRight: Double = 0
    /// Walk axis for the CURRENT burst only, derived from the mapped
    /// bracket at the moment "+" was tapped. Not a session latch — the
    /// session-scoped one is `adjustAxisLatch`, and this is deliberately
    /// separate so a burst measures the axis it was started on.
    private var burstBracketAxis: GuideAxis?
    /// Newest depth frame, kept so the tap handler can map the bracket at
    /// the instant of capture.
    private var latestFrameForBracket: ARDepthFrame?

    /// The window's interface orientation. `displayTransform` needs it, and
    /// a hard-coded `.portrait` would transpose the mapping on a landscape
    /// device.
    private static func currentInterfaceOrientation() -> UIInterfaceOrientation {
        #if canImport(UIKit) && os(iOS)
        UIApplication.shared.connectedScenes
            .compactMap { ($0 as? UIWindowScene)?.interfaceOrientation }
            .first ?? .portrait
        #else
        .portrait
        #endif
    }

    /// True while the live bracket has a usable view→depth mapping. False
    /// puts a plain-language reason on the status line instead of silently
    /// showing nothing.
    @Published public private(set) var bracketMappingReady: Bool = true

    /// Set by the scan screen from AppSettings. Only effect: the bracket's
    /// failure line carries the mapped geometry so a field report can name
    /// the numbers instead of describing a blank screen.
    public var developerMode: Bool = false

    /// The AR view's size in points, published by the scan screen. The
    /// bracket handles are fractions of THIS, and mapping them into depth
    /// pixels needs the size they are fractions of.
    ///
    /// Setting it also tells the session, because THIS is the size that must
    /// build the view→depth affine — the same one the handles are fractions
    /// of. Pushing it from `ARCameraView.updateUIView` instead looked
    /// equivalent and was not: the ARView is created at `.zero` and laid out
    /// afterwards, so the mapping could stay unbuilt and ADJUST would show
    /// no diameter at all while Auto (which needs no mapping) carried on
    /// working. A SwiftUI GeometryReader always has a real size.
    public var viewSize: CGSize = .zero {
        didSet {
            guard viewSize != oldValue,
                  viewSize.width > 1, viewSize.height > 1 else { return }
            session.reportViewport(size: viewSize,
                                   orientation: Self.currentInterfaceOrientation())
        }
    }

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

        // A fresh appearance gets a fresh acquisition clock, so the hint
        // reflects THIS tree rather than however the last one ended. The
        // refusal goes with it — it described a tap on the previous screen.
        captureRefusalReason = nil
        acquisitionStalled = false
        lastFitTime = ProcessInfo.processInfo.systemUptime
        startStallTicker()

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
        stallTicker?.invalidate()
        stallTicker = nil
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
        // Kept so the capture tap can map the bracket against the frame the
        // cruiser was actually looking at.
        latestFrameForBracket = frame
        // REQ-DBH-003 crosshair transitions green when the centre pixel
        // carries depth the estimator can work from. The window is
        // `armDepthRangeM` — see the note there for why the spec's literal
        // "< 3 m" is no longer the estimator's answer and stopped being it
        // three rounds ago.
        let cx = frame.width / 2
        let cy = frame.height / 2
        let d = frame.depth(atX: cx, y: cy)
        let c = frame.confidence(atX: cx, y: cy)
        let centrePixelUsable = Self.armDepthRangeM.contains(d) && c >= 1
        // ...OR the estimator's own verdict, which is the one the capture
        // actually runs on. This gate samples ONE pixel; `chordPreviewFit`
        // samples a 5×5 median, so a hole at dead centre held the crosshair
        // red and the "+" inert while a diameter was already on the badge.
        //
        // It cannot arm the app anywhere the estimator would not: `previewFit`
        // is nil outside the estimator's own window, so this only ever
        // recovers a frame the estimator has already measured. Auto only —
        // ADJUST does not arm from the crosshair (its tap accepts `.aligning`)
        // and its ring keeps meaning what it has always meant.
        //
        // Reading the estimator's median directly would be better still, but
        // `DBHEstimator.medianDepth` is internal to the Sensors module and
        // widening it is a change to the estimator file.
        let liveFitUsable = !(edgeAdjustActive || segmentationDroveTheBracket)
            && (previewFit.map { $0.tier != .red } ?? false)
        let stable = centrePixelUsable || liveFitUsable
        // ONLY ON CHANGE. `@Published` fires `objectWillChange` on every
        // assignment, equal value or not, and this one runs on every depth
        // frame — so writing it unconditionally re-evaluated the whole scan
        // screen (AR view included) at the depth frame rate whether or not
        // anything the cruiser can see had moved. Field report 9.
        if crosshairIsStable != stable { crosshairIsStable = stable }
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
            if previewTier != nil { previewTier = nil }
            if previewStatusText != nil { previewStatusText = nil }
            if previewStatusIsDiagnostic { previewStatusIsDiagnostic = false }
            isStable = false
            consecutiveRedFrames = 0
            // A held bracket value belongs to the aiming session that produced
            // it. Carrying it across a capture or a committed result would put
            // the previous tree's diameter under the next tree's handles.
            resetBracketSettling()
            // Not aiming ⇒ no acquisition to stall. Re-seed the clock so
            // the hint waits out a full interval when aiming resumes.
            if acquisitionStalled { acquisitionStalled = false }
            lastFitTime = ProcessInfo.processInfo.systemUptime
            return
        }

        let now = ProcessInfo.processInfo.systemUptime
        guard now - lastPreviewUpdate >= previewMinIntervalSec else { return }
        lastPreviewUpdate = now

        // Auto-pick the across-the-trunk axis (orientation-robust) instead of
        // a fixed orientation guess, which on some devices walked the strip
        // along the trunk and under-read the diameter to a few cm.
        //
        // LAZY, because it is not cheap — two strip extractions plus a
        // back-projection each — and ADJUST, which is now the default path,
        // needs it only on the tick that latches its axis. Voting it on every
        // tick and discarding the answer was pure cost on the screen the
        // cruiser spends the whole plot in. Field report 9.
        func votedGuideAxis() -> GuideAxis {
            DBHEstimator.pickGuideAxis(
                frame: frame,
                tapPixel: SIMD2(Double(cx), Double(cy)),
                calibration: calibration)
        }

        // ADJUST (edge-bracket) mode bypasses BOTH the automatic
        // edge-finding and the stability/EMA machinery: the live value
        // and the chord bar must track the user's handles exactly, so
        // the raw bracket fit is published on every preview tick. The walk
        // axis is voted per frame until the bracket first measures, then
        // latched — see `adjustAxisLatch`.
        // SEGMENTATION TAKES THE BRACKET BRANCH TOO. The capture routes a
        // model-placed reading through `bracketChordEstimate`, so the number
        // on screen has to come from the same fit — otherwise the cruiser
        // reads the auto depth-walk's diameter, taps "+", and a different one
        // is stored, with nothing saying so.
        if edgeAdjustActive || segmentationDroveTheBracket {
            // THE HANDLE FRACTIONS GO STRAIGHT IN, against a guide axis
            // latched for the session — restored verbatim from the version
            // the field used and verified.
            //
            // A view→depth affine was inserted here on the reasoning that a
            // screen fraction and a depth fraction cannot be the same
            // number under an aspect-fill crop. That reasoning produced a
            // screen with no diameter on it for three builds, and the
            // cruiser had already measured a stand with the code it
            // replaced. Field evidence outranks the derivation, so the
            // derivation goes. The mapping is still computed and recorded in
            // the raw-capture manifest, where it costs nothing and can
            // settle the question later against a tape.
            //
            // The axis is VOTED per tick until the bracket first measures
            // something, and latched from that tick onwards. Two reasons for
            // the delay, both in `adjustAxisLatch`: the screen now opens on
            // the bracket, so an unconditional latch would fix the axis off
            // whatever the phone was pointed at while being raised; and a
            // wrong axis is silent — it rescales the whole plot by 4:3.
            // Voting until then costs no more than the auto interlude it
            // replaces, which called `votedGuideAxis()` on every tick for
            // exactly the same stretch of time; once latched it costs
            // nothing, which is what field report 9 asked for.
            let bracketAxis = adjustAxisLatch ?? votedGuideAxis()
            let fit = DBHEstimator.bracketChordFit(
                frame: frame,
                guideAxis: bracketAxis,
                leftFraction: edgeBracketLeftFraction,
                rightFraction: edgeBracketRightFraction)
            // A fit means the walk crossed real depth at the guide line, so
            // this vote was taken on an aimed frame. Latch it and stop
            // voting.
            if adjustAxisLatch == nil, fit != nil { adjustAxisLatch = bracketAxis }
            bracketMappingReady = true
            smoothedPreviewDbhCm = nil
            smoothedCenterWorldXZ = nil
            lastTapDepthHint = nil
            recentRawDiameters.removeAll()
            isStable = false
            consecutiveRedFrames = 0
            // `previewFit` IS THE FIT, always and unconditionally — the
            // settling logic below decides what NUMBER to show and nothing
            // else. The capture gate, the chord bar and the cylinder overlay
            // all read this, and gating the capture on a live display tick
            // would refuse a burst whose stored median is demonstrably
            // unaffected (rho = −0.11). See `bracketDepthUnsettled`.
            previewFit = fit
            previewTier = fit?.tier
            previewDbhCm = settledBracketDiameterCm(
                frame: frame, axis: bracketAxis, fit: fit, now: now)
            // BEFORE the status line, not after: the stall flag decides
            // which of the two sentences the screen is allowed to show, so
            // it has to describe THIS tick when the line is written.
            updateAcquisitionStall(hasFit: fit != nil, now: now)
            // EVERY nil ends with a sentence on screen. Nothing on the
            // bracket path may fail silently again: three builds went out
            // with a blank strip because the only failure this line spoke
            // about was the one the code happened to be looking at.
            //
            // Two kinds of line come out of here and the screen treats them
            // differently: ADVICE (suppresses the stall hint, because two
            // remedies for one failure is worse than one) and the developer
            // DIAGNOSTIC (never suppresses anything — see
            // `previewStatusIsDiagnostic`).
            let line: String?
            let isDiagnostic: Bool
            if fit != nil {
                // A fit that measured, but not across bark: the diameter has
                // been withheld (`previewDbhCm` is nil above) because the
                // bracket has been spanning two surfaces for longer than the
                // grace, so the strip must say what happened. While the run is
                // shorter than the grace the held value is on screen and this
                // stays silent — a sentence for every one-tick excursion would
                // flicker exactly as badly as the number used to.
                line = previewDbhCm == nil ? Self.bracketTwoSurfacesText : nil
                isDiagnostic = false
            } else if developerMode {
                // The bench numbers stay on the strip even once the stall is
                // up — a long run of nil fits is exactly the failure the
                // axis/fractions/grid are being read for, so dropping them at
                // the 2 s mark would blank the diagnostic precisely when it
                // is wanted. It no longer costs the cruiser the hint: this
                // line is flagged as a diagnostic, and the banner shows the
                // stall sentence over the top of it. Developer mode is worn
                // in the stand here (tape-truth entry, research rows, typed
                // truth are all gated on it), so "bench only" was never a
                // safe assumption to hide field guidance behind.
                let ax: String
                switch bracketAxis {
                case .row(let y): ax = "row \(y)"
                case .col(let x): ax = "col \(x)"
                }
                line = String(
                    format: "no fit · %@ · %.3f–%.3f · grid %dx%d",
                    ax, edgeBracketLeftFraction, edgeBracketRightFraction,
                    frame.width, frame.height)
                isDiagnostic = true
            } else if acquisitionStalled {
                // FIELD REPORT 15 — hand the line to the banner once the
                // stall is up.
                //
                // ADJUST is the default path, and this branch used to write
                // a sentence on EVERY nil fit, which the screen reads as
                // "a specific reason is already showing" and suppresses the
                // stall hint for. The hint was therefore unreachable in the
                // mode the cruiser actually works in, and iOS printed
                // different advice from Android for the identical state.
                //
                // After `acquisitionStallSec` of nothing resolving, the
                // bracket advice below has demonstrably not worked; the
                // remedy the cruiser found (a gentle sway, or a different
                // standing distance) is the one worth printing. nil here is
                // not silence — it is what lets
                // `DBHScanScreen.acquisitionStallHint` take the banner, the
                // same sentence Android shows in its adjust branch.
                line = nil
                isDiagnostic = false
            } else {
                // NOT "widen it". A wider bracket raises the computed
                // diameter, which pushes it further past the estimator's
                // plausibility ceiling — the advice guaranteed the failure it
                // was trying to clear.
                line = "Can't read depth across the bracket — narrow it onto the trunk, or step closer."
                isDiagnostic = false
            }
            // ON CHANGE ONLY, like `crosshairIsStable` above: this runs at the
            // preview rate and an equal-value assignment to a `@Published`
            // still re-evaluates the whole scan screen.
            if previewStatusText != line { previewStatusText = line }
            if previewStatusIsDiagnostic != isDiagnostic {
                previewStatusIsDiagnostic = isDiagnostic
            }
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
            // (The stall was updated above, before the status line that
            // depends on it.)
            return
        }

        // Phase 19 — dispatch on the user's chosen DBH method. The chord
        // method is stateless frame-to-frame (no depth-window anchoring
        // needed: median over ± 10 rows already absorbs intra-frame
        // jitter and the multi-frame median in the burst handles the
        // rest). The legacy partial-arc path keeps its tap-depth hint.
        let fit: DBHEstimator.PreviewFit?
        let axis = votedGuideAxis()
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
        // A rejection reason is ADVICE, so it keeps suppressing the stall
        // hint; only the bracket path above ever writes a diagnostic.
        let autoLine = publishable ? nil : fit?.rejectionReason
        if previewStatusText != autoLine { previewStatusText = autoLine }
        if previewStatusIsDiagnostic { previewStatusIsDiagnostic = false }

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

        updateAcquisitionStall(hasFit: fit != nil, now: now)
    }

    /// The diameter the ADJUST readout should show for this tick — see
    /// `bracketDepthUnsettled` for why the raw fit is not always it.
    ///
    /// Three outcomes, in order:
    ///   • no fit at all → nil, and the existing advice lines take over;
    ///   • the bracket's middle half is ONE surface → publish this tick's raw
    ///     diameter, immediately, and remember it. This is the ordinary case
    ///     and it keeps ADJUST's contract that the number tracks the handles
    ///     with no smoothing and no lag;
    ///   • the middle half straddles two surfaces → hold the last one-surface
    ///     diameter, up to `bracketUnsettledGraceSec`, then withhold it.
    ///
    /// The spread probe is READ-ONLY and the fit is untouched on every path —
    /// what is decided here is only which number reaches the screen.
    private func settledBracketDiameterCm(
        frame: ARDepthFrame,
        axis: GuideAxis,
        fit: DBHEstimator.PreviewFit?,
        now: TimeInterval
    ) -> Double? {
        guard let fit else {
            // No fit is already a fully-explained state on this path, and it
            // is not evidence about the bark either way — leave the held value
            // and its clock alone so a one-frame depth outage mid-drag does
            // not restart the grace.
            if bracketDepthUnsettled { bracketDepthUnsettled = false }
            return nil
        }
        let spread = DBHEstimator.bracketCoreDepthSpreadM(
            frame: frame,
            guideAxis: axis,
            leftFraction: edgeBracketLeftFraction,
            rightFraction: edgeBracketRightFraction)
        // A fit exists but the probe declined to describe it (fewer than three
        // valid depths cannot happen here — the fit needs them too — so this
        // is the bounds refusal). Treat an unmeasurable spread as settled
        // rather than inventing a verdict about it.
        let settled = spread.map { $0 <= DBHEstimator.bracketCoreDepthSpreadLimitM } ?? true
        if settled {
            lastSettledBracketAt = now
            lastSettledBracketCm = fit.diameterCm
            if bracketDepthUnsettled { bracketDepthUnsettled = false }
            return fit.diameterCm
        }
        if !bracketDepthUnsettled { bracketDepthUnsettled = true }
        // Never held anything yet — the very first ticks of a session already
        // straddling two surfaces. There is nothing honest to show.
        guard let held = lastSettledBracketCm, let since = lastSettledBracketAt
        else { return nil }
        return now - since <= Self.bracketUnsettledGraceSec ? held : nil
    }

    /// Drop the held ADJUST value and its clock. Called wherever the aiming
    /// session ends, so a held diameter can never outlive the tree it was
    /// measured on.
    private func resetBracketSettling() {
        lastSettledBracketAt = nil
        lastSettledBracketCm = nil
        if bracketDepthUnsettled { bracketDepthUnsettled = false }
    }

    /// Field report 15 — say so when nothing is resolving.
    ///
    /// The stall is measured on the FIT, not on the depth frames: frames keep
    /// arriving while the cruiser stands at a range the sensor can't read the
    /// stem at, which is exactly the case that used to leave the screen
    /// showing the ordinary "align and hold steady" line forever. Published
    /// only on a change so it can't reintroduce per-tick invalidation.
    private func updateAcquisitionStall(hasFit: Bool, now: TimeInterval) {
        if hasFit {
            lastFitTime = now
            if acquisitionStalled { acquisitionStalled = false }
            return
        }
        if lastFitTime == 0 { lastFitTime = now }
        let stalled = now - lastFitTime >= acquisitionStallSec
        if acquisitionStalled != stalled { acquisitionStalled = stalled }
    }

    /// The same clock, driven by wall time instead of by depth delivery.
    ///
    /// `updateAcquisitionStall` is only ever reached from inside
    /// `handleDepthFrame`, and then only past the 100 ms preview throttle —
    /// so without this the hint measured "frames that produced no fit" and
    /// stayed silent through the cases where no frame arrives at all
    /// (interruption, thermal throttle, `frame.sceneDepth` nil so the
    /// manager publishes nothing). Those are states the cruiser is stuck in
    /// and cannot diagnose, which is the whole point of field report 15.
    ///
    /// It can only ever RAISE the flag: `lastFitTime` moves forward solely
    /// on a real fit, and that path clears the flag itself.
    private func startStallTicker() {
        stallTicker?.invalidate()
        // 0.25 s: four chances per stall interval, negligible next to the
        // depth path, and coarse enough that it can't become the reason the
        // screen re-renders.
        stallTicker = Timer.scheduledTimer(
            withTimeInterval: 0.25, repeats: true
        ) { [weak self] _ in
            Task { @MainActor in self?.tickAcquisitionStall() }
        }
    }

    private func tickAcquisitionStall() {
        // Only while the cruiser is aiming. Every other state clears the
        // flag and re-seeds the clock on its own, and a tick during a burst
        // would raise a "no depth lock" hint over a capture that is going
        // fine. Mirrors the `previewable` set in `handleDepthFrame`.
        //
        // The tap refusal is taken down from HERE, not from `handleDepthFrame`:
        // that method has four early returns (not previewable, the 100 ms
        // throttle, the ADJUST branch, no frame at all), so a clear written
        // inside it would be skipped in exactly the states the cruiser sits in
        // while reading the banner. This ticker runs on wall time and always
        // sees the current published state.
        switch state {
        case .aligning, .armed, .rejected:
            // No longer true the moment a tap would be honoured.
            if captureRefusalReason != nil, canCaptureNow { captureRefusalReason = nil }
        case .idle, .capturing, .fitted, .accepted, .manualEntry:
            // Left the aiming phase — the refusal describes a tap that no
            // longer applies to what is on screen.
            if captureRefusalReason != nil { captureRefusalReason = nil }
            return
        }
        let now = ProcessInfo.processInfo.systemUptime
        updateAcquisitionStall(hasFit: false, now: now)

        // AND TAKE DOWN THE LINE THE LAST FRAME LEFT BEHIND.
        //
        // Raising the flag is not enough to put the hint on screen:
        // `previewStatusText` is written ONLY from `handleDepthFrame`, and
        // `DBHScanScreen.showsAcquisitionHint` yields to any advice line
        // already up. So when depth stops mid-struggle — session
        // interruption, thermal throttle, nil `sceneDepth` — the bracket's
        // "narrow it onto the trunk" (or the auto path's rejection reason)
        // stayed on the strip forever, still describing a frame that no
        // longer exists, and the banner never got the hint. That stuck state
        // is the whole reason this ticker exists, and the ticker could not
        // deliver in it: the hint only came through if the LAST frame before
        // the stop happened to produce a fit.
        //
        // The diagnostic goes with it. Bench numbers frozen from a frame that
        // stopped arriving read as live and are not — same rule as everywhere
        // else here: refuse rather than show something that isn't so.
        //
        // Only once depth has actually gone quiet. While frames keep coming,
        // `handleDepthFrame` owns this line and rewrites it at 10 Hz, and
        // clearing it from a 4 Hz timer would just blink the strip.
        guard acquisitionStalled, now - lastPreviewUpdate >= depthSilentSec
        else { return }
        if previewStatusText != nil { previewStatusText = nil }
        if previewStatusIsDiagnostic { previewStatusIsDiagnostic = false }
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
            guard state == .armed || state == .aligning else {
                captureRefusalReason = Self.noTrunkLockText
                return
            }
        } else {
            // EVERY REFUSAL SPEAKS. This returned in silence, which is how
            // "cannot capture from a distance" reached the field with no
            // message about distance anywhere on the screen.
            guard state == .armed else {
                captureRefusalReason = armRefusalReason()
                return
            }
        }
        // Phase 18.4 — the tap is now the *only* way to start the
        // burst, so we keep the gate loose: any fit visible on screen
        // (red rejections excluded) is enough. Waiting for the
        // stability gate to latch before allowing a tap is what made
        // the previous auto-capture flow feel sluggish; if the cruiser
        // is committed enough to tap, the burst's own §7.1 tree will
        // catch a fit that's too noisy to record.
        guard let fit = previewFit, fit.tier != .red else {
            captureRefusalReason = Self.noTrunkLockText
            return
        }
        captureRefusalReason = nil
        // Latch the bracket so mid-burst handle drags / mode exits can't
        // change what this capture measures.
        // Latch the bracket AS DEPTH GEOMETRY. The raw-capture manifest
        // stores depth-space fractions + axis (the same schema Android
        // writes), so latching here means the recorded bundle replays
        // through exactly the code that produced the live number.
        //
        // A SEGMENTED CAPTURE IS A BRACKET CAPTURE. When the model is placing
        // the handles the reading must go down the same path a thumb's
        // placement goes down — same depth geometry, same middle-half
        // sampling, same tier — because it IS the same measurement, made
        // between two edges someone else chose. It also means the recorded
        // bundle replays through the code that produced the live number, and
        // that the entry is stamped "manual" rather than quietly filed as an
        // automatic depth-walk reading it is not.
        burstUsedSegmentation = segmentationDroveTheBracket
        burstUsedBracket = edgeAdjustActive || burstUsedSegmentation
        burstBracketLeft = edgeBracketLeftFraction
        burstBracketRight = edgeBracketRightFraction
        // VOTE THE AXIS HERE, on the frame the cruiser is aimed at right now.
        //
        // This used to copy `adjustAxisLatch`, the session-scoped preview
        // latch, which is the failure its own doc comment warns about: the
        // latch is taken on the first preview tick that measures anything —
        // while the phone is still being raised — and then every capture of
        // the plot inherits it. A wrong axis reads the handle fractions
        // against the other extent (256 px instead of 192) and multiplies the
        // diameter by 4:3, silently.
        //
        // It reached the field. Across the two validation plots the reading
        // and the bundle disagreed on 60 of 107 iOS diameters, the reading
        // running 1.38x high, and recomputing those 60 on the opposite axis
        // reproduces the reading to a median ratio of 1.0000. The bundle —
        // which votes per capture, below in `recordDBH` — is the one that
        // matches the tape.
        //
        // So: vote per capture, off `latestFrameForBracket`, and hand that
        // same axis to the recorder so the two records cannot diverge again.
        // The preview keeps its latch; it costs one vote per capture here,
        // not one per tick, which is what field report 9 asked for.
        burstBracketAxis = latestFrameForBracket.map {
            DBHEstimator.pickGuideAxis(frame: $0, tapPixel: tapPixel, calibration: calibration)
        } ?? adjustAxisLatch
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

    /// Why the Auto crosshair is not armed, in terms the cruiser can walk on.
    ///
    /// Reads the SAME centre pixel of the SAME frame the gate read, so the
    /// sentence and the refusal can never be describing different frames. A
    /// centre pixel with no return at all reads 0 m, which is not a distance
    /// to talk about — that case, and a low-confidence reading inside the
    /// window, both get the lock sentence instead.
    private func armRefusalReason() -> String {
        guard let frame = latestFrameForBracket else { return Self.noTrunkLockText }
        let d = frame.depth(atX: frame.width / 2, y: frame.height / 2)
        if d > Self.armDepthRangeM.upperBound { return Self.tooFarText }
        if d > 0, d < Self.armDepthRangeM.lowerBound { return Self.tooCloseText }
        return Self.noTrunkLockText
    }

    /// Exactly the condition `tap(at:)` requires, kept as one expression so a
    /// refusal banner cannot outlive the state it describes.
    private var canCaptureNow: Bool {
        let stageOK = edgeAdjustActive
            ? (state == .armed || state == .aligning)
            : state == .armed
        guard stageOK, let fit = previewFit, fit.tier != .red else { return false }
        return true
    }

    /// Dismiss the refusal by hand. The banner also clears itself the moment a
    /// tap would be honoured; this is for the cruiser who has read it and
    /// wants the screen back.
    public func clearCaptureRefusal() {
        if captureRefusalReason != nil { captureRefusalReason = nil }
    }

    public func retake() {
        captureRefusalReason = nil
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
        acquisitionStalled = false
        lastFitTime = ProcessInfo.processInfo.systemUptime
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

    /// The cruiser's unit system, kept in sync with AppSettings by the screen.
    /// Two uses, and they must be the same value: the manual-entry field
    /// prompts for INCHES under imperial (the typed number used to be stored
    /// straight into `diameterCm`, a 2.54x corruption), and the estimator
    /// words its recovery instructions in these units, so a cruiser who paces
    /// in feet is not told to stand "0.5-3 m" from the trunk. Mirrors
    /// `HeightScanViewModel.unitSystem`.
    public var unitSystem: UnitSystem = .metric

    public func submitManualEntry() {
        guard let typed = TruthInput.parsePositive(manualDbhCm) else { return }
        let cm = unitSystem == .imperial ? Units.inchesToCm(typed) : typed
        guard cm > 0 else { return }
        resultCapturedManually = false
        resultCaptureMode = "auto"
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
            // LAZY, for the same reason as the preview tick (field report
            // 9): `pickGuideAxis` is two strip extractions plus a
            // back-projection, this runs on the main actor five times per
            // capture, and the bracket path — ADJUST, the default — already
            // latched its axis when "+" was tapped and threw this answer
            // away. Same value wherever it is still used.
            func votedGuideAxis() -> GuideAxis {
                DBHEstimator.pickGuideAxis(
                    frame: firstFrame,
                    tapPixel: burstTap,
                    calibration: calibration)
            }
            // Dispatch: manual bracket (ADJUST captures, latched at tap
            // time) → chord method → original §7.1 partial-arc pipeline.
            let outcome: DBHResult?
            if burstUsedBracket {
                outcome = DBHEstimator.bracketChordEstimate(
                    frames: frames,
                    guideAxis: burstBracketAxis ?? votedGuideAxis(),
                    leftFraction: burstBracketLeft,
                    rightFraction: burstBracketRight,
                    calibration: calibration)
            } else {
                let input = DBHScanInput(
                    frames: frames,
                    tapPixel: burstTap,
                    guideAxis: votedGuideAxis(),
                    projectCalibration: calibration,
                    rawPointsWriter: rawPointsWriter,
                    unitSystem: unitSystem)
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
        resultCaptureMode = burstUsedSegmentation ? "segmented"
            : (burstUsedBracket ? "manual" : "auto")
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
        // The axis the READING was computed on. The recorder used to vote its
        // own, off a different frame, and a disagreement rescaled the bundle
        // against the field log by 4:3 with nothing on screen to show for it.
        // One vote, both records.
        let axis = burstBracketAxis
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
                algorithm: algo, bracket: bracket, guideAxis: axis,
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
