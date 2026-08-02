// Spec §5.2 authoritative DBH scan layout + §4.3 state machine.
//
// Layout is the overlay chrome specified in §5.2:
//   • AR view (or black placeholder in previews/macOS) fills the screen.
//   • Fixed horizontal guide line at y = screen_height/2.
//   • Center crosshair, red until depth-stable then green.
//   • Status banner + result panel at the bottom.
//   • Action row: Retake / Details / Accept — shown once a result lands
//     (a "Manual" rail button under the capture "+" is offered while
//     aiming instead).
//
// Per Phase 2 decision #5, snapshot tests only render the overlay chrome;
// the AR view is Color.black so visuals are deterministic across hosts.

import SwiftUI
import Common
import Models
import Sensors
import Positioning
import AR
import simd

public struct DBHScanScreen: View {

    @StateObject private var viewModel: DBHScanViewModel
    @Environment(\.scenePhase) private var scenePhase
    @EnvironmentObject private var settings: AppSettings
    /// App-scoped active sampling plot — rendered as a subdued ring +
    /// pole under the measurement markers so the cruiser keeps the plot
    /// boundary in view while measuring. Changes only on place/reset.
    @ObservedObject private var activePlot = ActiveSamplingPlot.shared
    public var onResult: (DBHResult) -> Void = { _ in }
    /// Fired when the cruiser explicitly accepts the on-screen result
    /// (state → .accepted). Use this for flows that want to persist the
    /// reading only after the cruiser has confirmed it — Quick Measure
    /// doesn't record a measurement until Accept is tapped.
    /// `metadata` carries the species / position / damage / note the
    /// cruiser optionally attached via `ScanMetadataSheet`.
    ///
    /// RETURNS whether the reading actually reached storage — same contract as
    /// `HeightScanScreen.onAccept`. A host that could not write the tree row
    /// returns false and this screen keeps the result on screen under a loud
    /// "NOT saved" banner instead of resetting for the next tree, which is how
    /// a dropped diameter used to look identical to a saved one.
    public var onAccept: (DBHResult, ScanMetadata) -> Bool = { _, _ in true }

    public struct ScanMetadata {
        public var speciesCode: String?
        public var position: QuickMeasureEntry.StemPosition?
        public var damageCodes: [String]
        public var note: String
        /// Auto-capture at Accept (map home): window snapshot + GPS fix.
        public var photoPath: String?
        public var latitude: Double?
        public var longitude: Double?
        /// "manual" when the reading was captured in ADJUST (edge-
        /// bracket) mode, "auto" otherwise. Recorded with the entry.
        public var captureMode: String?
        /// The tape diameter typed on THIS screen for THIS capture, already
        /// converted to the metric base (cm). It goes onto the reading, not
        /// only into the raw-capture manifest: the manifest is developer
        /// plumbing that can be pruned, while the truth column the accuracy
        /// study reads is exported from the reading. nil when nothing usable
        /// was typed — never a zero, which would read as a tape that
        /// measured nothing.
        public var truth: Double?
        public init(speciesCode: String? = nil,
                    position: QuickMeasureEntry.StemPosition? = nil,
                    damageCodes: [String] = [],
                    note: String = "",
                    photoPath: String? = nil,
                    latitude: Double? = nil,
                    longitude: Double? = nil,
                    captureMode: String? = nil,
                    truth: Double? = nil) {
            self.speciesCode = speciesCode
            self.position = position
            self.damageCodes = damageCodes
            self.note = note
            self.photoPath = photoPath
            self.latitude = latitude
            self.longitude = longitude
            self.captureMode = captureMode
            self.truth = truth
        }
    }

    @State private var metaSpecies: String?
    @State private var metaPosition: QuickMeasureEntry.StemPosition? = .dbh
    @State private var metaDamage: [String] = []
    @State private var metaNote: String = ""
    @State private var presentingMetadata = false
    /// True while the measurement-moment window snapshot is being taken —
    /// hides every piece of 2D chrome so the captured JPEG shows only the AR
    /// feed and the measurement overlays (crosshair, chord, markers).
    @State private var hidingChromeForCapture = false
    /// The JPEG taken THE INSTANT THE BURST FINISHED, held here until Accept
    /// attaches it to the reading.
    ///
    /// FIELD REPORT: the shutter used to sit in the Accept handler, and by
    /// the time the cruiser has read the panel and decided, the phone is down
    /// at their side — every stored photo was leaf litter and boots. The
    /// frame worth keeping is the one where the bracket is still on the stem,
    /// so it is taken at `resultGeneration` (see `captureHeldPhoto`) and
    /// parked here. Nothing about the stored measurement changes: this is
    /// still the value that goes into `ScanMetadata.photoPath` at Accept.
    ///
    /// It is a FILE, so every path that abandons it has to delete it:
    /// `discardHeldPhoto()` on retake / a superseding capture / leaving the
    /// screen. It is released (not deleted) only once a reading has taken
    /// ownership of it.
    @State private var heldPhoto: String?
    /// True between `onDisappear` and the next `onAppear`. Read by
    /// `captureHeldPhoto` only: its 80 ms settle is an unstructured task that
    /// outlives the screen, and a shot taken after the screen is gone is both
    /// the wrong picture and an undeletable file.
    @State private var hasLeftScreen = false
    /// True once this screen has produced a fit — the switch that turns the
    /// scene-reconstruction wireframe off. See `showsScanMesh`.
    @State private var hasProducedFit = false

    /// Bridge that turns SwiftUI screen taps into world-space rays / hits
    /// against the live ARView; also the source of the camera pitch logged
    /// with every research row.
    @StateObject private var raycaster = ARCenterRaycaster()

    /// BREAST-HEIGHT GUIDE (developer mode + its own Settings toggle) — the
    /// drawn answer to "how do you know that was read at breast height?".
    /// Per-screen, because the base belongs to the tree in front of the
    /// camera. It never touches `viewModel`, the stage machine or the
    /// shutter; the capture path below is identical with it on or off.
    @StateObject private var bhGuide = BreastHeightGuide()
    /// Where the guide's "1.37 m" pill sits, in AR-VIEW coordinates. nil
    /// whenever the ring is behind the camera or nothing is drawn.
    @State private var bhLabelPoint: CGPoint?
    /// Why the last "Place base" planted nothing — one-shot, cleared by the
    /// next placement.
    @State private var bhFailure: String?

    /// "Pin centre" offer, waved off for this visit. Not persisted: the
    /// offer is an offer, and a cruiser who is measuring from outside the
    /// plot (or simply does not want the ring) should not be nagged for a
    /// whole tally. Re-entering the screen asks once more, which is right —
    /// by then they may be standing at the centre.
    @State private var pinOfferDismissed = false
    /// Why the last "Pin centre" tap planted nothing. Shown on the card.
    @State private var pinCentreFailure: String?

    /// Developer-mode research capture: the tape-measured true diameter AS
    /// TYPED, logged with the scan context to the research CSV so error can be
    /// analysed against distance / aim angle. The unit is `activeTruthUnit`
    /// below, never assumed — the field used to be named (and read) as
    /// centimetres whatever the cruiser was working in.
    /// NEVER cleared until the value is durably on the bundle (or there is
    /// no bundle to attach it to) — see `applyTypedTruth`.
    @State private var researchTrueText: String = ""
    /// The cruiser's per-entry unit choice, remembered with the unit system it
    /// was made under. Nil means "no choice yet", which falls back to the
    /// ACTIVE system — an imperial operator gets inches, not a centimetre
    /// field they type inches into. The choice sticks for the rest of this
    /// screen session (a plot is not walked switching units tree by tree) and
    /// is dropped if the project's unit system changes underneath it.
    @State private var truthUnitChoice: (unit: TruthInput.Unit, imperial: Bool)?
    /// The unit in force for the field right now: the per-entry choice, or the
    /// active system's default until one is made.
    private var activeTruthUnit: TruthInput.Unit {
        let imperial = settings.unitSystem == .imperial
        // A choice made under the OTHER system is discarded rather than
        // carried across: switching the project to imperial must not leave
        // the field sitting in centimetres.
        if let choice = truthUnitChoice, choice.imperial == imperial { return choice.unit }
        return TruthInput.defaultUnit(.diameter, imperial: imperial)
    }
    /// Non-nil when the last Accept could NOT attach the typed truth. The
    /// text stays in the field so the value isn't lost.
    @State private var truthSaveFailure: String?
    /// The bundle a kept-on-screen truth was typed FOR. Guards against the
    /// value silently landing on the next tree's bundle in the tally loop —
    /// cleared the moment the cruiser edits the field.
    @State private var truthOwnerBundleID: String?
    /// Owner sentinel for a truth kept on screen for a measurement that
    /// produced NO bundle (manual entry, or after a retake). Never a real
    /// bundle id, so the cross-tree guard in `applyTypedTruth` still fires.
    private static let noBundleOwner = "no-bundle"
    /// The bundle a typed truth is QUEUED against — parked in the bundle's
    /// sidecar while its writer is still running. Queued is NOT durable, so
    /// the field keeps the text until that capture reports SAVED.
    @State private var truthQueuedForBundleID: String?
    /// Free-space check, refreshed when the screen appears and on each
    /// capture. It drove the REC pill's "LOW STORAGE" callout, which the
    /// field report retired (F2); the sample is kept because it is cheap
    /// and a full phone still fails every capture — the per-capture
    /// outcome pill reports that.
    @State private var storageLow = false

    /// DBH sensing path. Exactly one now (`.lidarDepth`) — the AR-motion and
    /// AR-caliper arms were removed. Kept as a value so the dev HUD and the
    /// research CSV keep stamping the same `depth_source`.
    private var methodSource: DBHMethodSource { settings.dbhMethodSource }

    /// CRUISE MODE — the active cruise plot's mini-map payload (plot
    /// number, radius, centre fix, measured trees). When set, the
    /// top-right plot mini-map renders the full picture; when nil the
    /// widget falls back to the quick ActiveSamplingPlot ring (YOU +
    /// ring only) or stays hidden if no plot is active. Quick-measure
    /// call sites pass nothing — their behaviour is unchanged beyond
    /// the widget's conditional presence.
    private let cruisePlotInfo: PlotMiniMapInfo?

    /// CRUISE QUICK-TALLY LOOP — when non-nil this screen is the cruise
    /// diameter loop: each Accept saves the tree via `onAccept`, then the
    /// screen RESETS to aiming for the next tree instead of dismissing.
    /// The value is the tree number currently being aimed at (the host
    /// auto-increments it after every save) and, with `tallyTreeName`,
    /// drives the "Tree #8" target pill. Quick-measure sites pass nothing.
    private let tallyTreeNumber: Int?
    /// The name the host will save the next tallied tree under, when the
    /// cruiser has a series running in this plot. nil keeps the pill on the
    /// number alone — the zero-typing default.
    private let tallyTreeName: String?
    /// Tally pill tapped — the host opens its rename field. nil leaves the
    /// pill inert, which is what every quick-measure call site wants.
    private let onRenameTally: (() -> Void)?
    /// Undo tap on the tally toast — the host deletes the just-saved
    /// tree row (and its photo) and steps the auto number back.
    private let onUndoTally: (() -> Void)?

    /// RAW-CAPTURE JOIN KEYS — the project this measurement belongs to, and
    /// (quick-measure only) the tree number the host is targeting. Without
    /// these a stored bundle can't be joined back to the field record it
    /// documents. The cruise tally supplies its number via `tallyTreeNumber`.
    private let projectID: String?
    private let quickTreeNumber: Int?

    /// Re-open plot setup, to change radius / centre after the first
    /// placement. Reached from the ENLARGED plot view that the top-right
    /// mini-map now opens — the tap itself no longer jumps into re-setup.
    /// nil on hosts with no plot to edit, and then the card stays inert.
    private let onEditPlot: (() -> Void)?

    /// The tree number that goes into the bundle: the cruise tally's live
    /// target when looping, else the host's quick-measure target.
    private var captureTreeNumber: Int? { tallyTreeNumber ?? quickTreeNumber }

    /// What the tally chrome calls the tree being aimed at — the cruiser's
    /// name for it when there is one, else "Tree #<target>". nil outside the
    /// cruise loop, where there is no tally at all.
    private var tallyTreeTitle: String? {
        tallyTreeNumber.map { TreeLabel.title(name: tallyTreeName, number: $0) }
    }

    /// Non-nil when the host could NOT store the accepted diameter. The
    /// result stays on screen with this reason instead of the loop resetting
    /// (and, in cruise, chaining into Height) as if the tree had been written.
    @State private var dbhSaveFailure: String?

    /// Saved-tree toast state: what the just-saved tree was called, shown in
    /// "Tree #7 saved · Undo". Held as the finished LABEL rather than a
    /// number, because the host advances both the number and the name the
    /// instant the save lands — by the time the toast renders, recomputing it
    /// would name the NEXT tree instead of the one Undo would remove.
    @State private var tallyToastLabel: String?
    /// Bumps on every toast show so the 3 s auto-hide task restarts.
    @State private var tallyToastGeneration = 0

    /// Signed metres from the camera to the active cruise plot BOUNDARY
    /// (|camera→centre| − radius; negative = inside). Polled from the
    /// plot's AR anchor at 0.2 s — the sampling screen's inside/outside
    /// machinery. nil while this plot's centre is not being tracked.
    @State private var boundarySignedM: Double?

    public init(viewModel: @autoclosure @escaping () -> DBHScanViewModel,
                onResult: @escaping (DBHResult) -> Void = { _ in },
                onAccept: @escaping (DBHResult, ScanMetadata) -> Bool = { _, _ in true },
                cruisePlotInfo: PlotMiniMapInfo? = nil,
                tallyTreeNumber: Int? = nil,
                tallyTreeName: String? = nil,
                onRenameTally: (() -> Void)? = nil,
                onUndoTally: (() -> Void)? = nil,
                projectID: String? = nil,
                quickTreeNumber: Int? = nil,
                initialSpeciesCode: String? = nil,
                onEditPlot: (() -> Void)? = nil) {
        _viewModel = StateObject(wrappedValue: viewModel())
        // Seeded from the measure chooser's species control when it was used,
        // so the details chip already reads the species the cruiser picked at
        // the tree instead of asking for it a second time.
        _metaSpecies = State(initialValue: initialSpeciesCode)
        self.onResult = onResult
        self.onAccept = onAccept
        self.cruisePlotInfo = cruisePlotInfo
        self.tallyTreeNumber = tallyTreeNumber
        self.tallyTreeName = tallyTreeName
        self.onRenameTally = onRenameTally
        self.onUndoTally = onUndoTally
        self.projectID = projectID
        self.quickTreeNumber = quickTreeNumber
        self.onEditPlot = onEditPlot
    }

    /// What the top-right mini-map shows: the cruise plot when the
    /// add-tree chain supplied one, else the quick sampling ring, else
    /// nothing.
    private var miniMapInfo: PlotMiniMapInfo? {
        if let cruisePlotInfo { return cruisePlotInfo }
        guard let plot = activePlot.plot else { return nil }
        return PlotMiniMapInfo(plotID: nil,
                               plotNumber: nil,
                               radiusM: plot.radiusM,
                               centerLat: nil,
                               centerLon: nil,
                               treeCount: 0,
                               trees: [],
                               unitSystem: settings.unitSystem)
    }

    public var body: some View {
        // Field mode is LiDAR-only for DBH: without the scanner the depth
        // pipeline — now the only sensing path — can't run, so block the
        // scan UI outright instead of starting an AR session the flow can't
        // use. Developer mode still gets through to typed manual entry
        // (Android's blocker carries the same dev-mode bypass).
        Group {
            if settings.deviceSupportsLiDAR || settings.developerMode {
                scanBody
            } else {
                lidarRequiredPanel
            }
        }
        // Full-bleed AR chrome — no system nav bar; the floating back
        // button is the exit affordance for both presentation paths
        // (NavigationStack push and fullScreenCover).
        #if os(iOS)
        .toolbar(.hidden, for: .navigationBar)
        #endif
    }

    /// Full-page blocker for non-LiDAR devices with Developer mode off —
    /// shown in place of the whole scan UI so the AR session never
    /// spins up. Keeps the same floating back chrome as the scan itself.
    private var lidarRequiredPanel: some View {
        ZStack {
            VStack(spacing: ForestixSpace.sm) {
                Image(systemName: "cube.transparent")
                    .font(.system(size: 44, weight: .light))
                    .foregroundStyle(ForestixPalette.textTertiary)
                Text("DBH scanning requires a LiDAR-equipped iPhone")
                    .font(ForestixType.bodyBold)
                    .foregroundStyle(ForestixPalette.textPrimary)
                    .multilineTextAlignment(.center)
                Text("This iPhone doesn't have the LiDAR scanner the trunk scan relies on, so DBH scanning can't run here.")
                    .font(ForestixType.caption)
                    .foregroundStyle(ForestixPalette.textSecondary)
                    .multilineTextAlignment(.center)
            }
            .padding(ForestixSpace.lg)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            MeasureBackButtonRow()
        }
        .background(ForestixPalette.canvas.ignoresSafeArea())
        .accessibilityIdentifier("dbhScan.lidarRequired")
    }

    /// Whether the LiDAR scene-reconstruction wireframe is drawn over the
    /// camera feed right now.
    ///
    /// FIELD REPORT 9. `.showSceneUnderstanding` re-renders the WHOLE
    /// accumulated reconstruction every display frame, and ARKit keeps
    /// accumulating it for as long as the session lives — which here is the
    /// whole plot, because the scan screens attach to a shared session with
    /// no reset options so world anchors survive. That is the one cost on
    /// this screen whose shape matches the report exactly: fine on the first
    /// tree, heavy by the tenth, worst on the trees that take longest.
    ///
    /// It is not decoration and it is not being deleted — the cruiser asked
    /// for it as the "it's actually scanning" feedback. But that question is
    /// answered, permanently, by the first diameter this screen puts on the
    /// glass. So the wireframe runs until the first fit and then gets out of
    /// the way; a stall keeps it up, which is exactly when the cruiser is
    /// asking whether the sensor sees anything at all. Reopening the screen
    /// brings it back (in the cruise tally the screen is reused across
    /// trees, and by then the answer is in hand).
    private var showsScanMesh: Bool { !hasProducedFit }

    private var scanBody: some View {
        ZStack {
            // Live AR camera feed wired to the same ARSession the
            // DBHScanViewModel is consuming depth frames from. Snapshot
            // tests (macOS host) fall back to a black background via
            // ARCameraView's #else branch. The cylinder marker renders
            // the live single-frame fit as a translucent blue cylinder
            // at the trunk's world position — world-anchored, so it
            // stays locked to the tree as the phone moves.
            // Mesh overlay: see `showsScanMesh` — the "it's actually
            // scanning" feedback the cruiser asked for, until the first
            // diameter answers the question for good. Height keeps it off
            // entirely. The subdued sampling-plot overlay (if a plot is
            // active) renders under the cylinder.
            ARCameraView(manager: viewModel.session,
                         debugMeshOverlay: showsScanMesh,
                         sceneMarkers: plotOverlayMarkers
                             + bhGuideMarkers
                             + cylinderMarkers,
                         raycaster: raycaster)
                .ignoresSafeArea()

            GeometryReader { geo in
                ZStack {
                    // THE VIEWPORT THE BRACKET IS MEASURED AGAINST.
                    //
                    // Reported from here because this reader is
                    // UNCONDITIONAL — it renders in every state. The ADJUST
                    // overlay is not: it appears only once the bracket is up
                    // and the state is right, and the bracket needs the
                    // mapping in order to produce the fit that gets it
                    // there. Reporting from inside it was circular, which is
                    // why the diameter never appeared.
                    Color.clear
                        .onAppear { viewModel.viewSize = geo.size }
                        .onChange(of: geo.size) { _, new in
                            viewModel.viewSize = new
                        }
                    guideLine(height: geo.size.height)
                    fitChord(in: geo.size)
                    // Crosshair ring is now positioned by GeometryReader
                    // at exactly (centerX, midY) so the guide line
                    // passes through the centre of the ring, not above
                    // or below it. The TiltBadge sits above so the
                    // cruiser sees device level at the same focal
                    // point as the trunk circle they're aiming at.
                    // Both are 2D chrome, so the Accept-time snapshot
                    // hides them (the guide/chord/crosshair stay —
                    // they ARE the measurement). The live DBH/distance
                    // readouts moved to the value strip above the
                    // bottom-centre shutter; only the capture-progress
                    // pill stays under the crosshair (locked).
                    if !hidingChromeForCapture {
                        TiltBadge()
                            .position(x: geo.size.width / 2,
                                      y: geo.size.height / 2
                                           - Self.crosshairOuterRadius
                                           - 22)
                    }
                    crosshairRing
                        .position(x: geo.size.width / 2,
                                  y: geo.size.height / 2)
                    if !hidingChromeForCapture,
                       viewModel.state == .capturing {
                        captureProgressPill
                            .position(x: geo.size.width / 2,
                                      y: geo.size.height / 2
                                           + Self.crosshairOuterRadius
                                           + 28)
                    }
                    // BREAST-HEIGHT GUIDE, 2D half. Both pills ride THIS
                    // reader and not the body ZStack: it is the full-bleed
                    // rect the AR view occupies, which is the rect
                    // `ARView.project` answers in and the rect the placing
                    // raycast aims through. The body ZStack is inset by the
                    // safe area — field report 16's second pass measured that
                    // as ~12 pt in portrait and sideways in landscape.
                    if bhGuideChromeVisible {
                        // The prompt takes the Height crosshair's label
                        // position and its styling, under the ring the
                        // Diameter screen already has. No second ring is
                        // added: `crosshairRing` IS this screen's aiming
                        // instrument.
                        if let prompt = bhPromptText {
                            bhPromptPill(prompt)
                                .position(x: geo.size.width / 2,
                                          y: geo.size.height / 2
                                               + Self.bhPromptOffset)
                        }
                        // The measurement label, pinned to where breast
                        // height actually is. Pushed clear of the rim so it
                        // never sits on the band the cruiser is reading.
                        if let point = bhLabelPoint {
                            // `.position` centres a view on the point, so
                            // nudging the CENTRE 14 pt right of the ring's
                            // centre put half the pill back over the rim —
                            // the one place it must not cover, because the rim
                            // is what the cruiser is lining up on. Anchoring
                            // the pill's LEADING edge instead puts the whole
                            // of it clear, which is what Android does with its
                            // top-left `offset`.
                            bhValuePill
                                .position(x: point.x, y: point.y)
                                .offset(x: Self.bhLabelSideOffset,
                                        y: -Self.bhLabelRiseOffset)
                                .fixedSize()
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .allowsHitTesting(false)
                        }
                    }
                }
                .frame(width: geo.size.width, height: geo.size.height)
            }
            // FULL-BLEED, matching the camera view. This rect is what the
            // view→depth affine is built from and what the bracket handles
            // are fractions of, so it has to be the rect the camera actually
            // occupies. It also puts the crosshair and the guide line on the
            // AR view's true centre — which is where the automatic path
            // measures (the depth map's centre pixel) — instead of the
            // safe-area centre a dozen points above it.
            .ignoresSafeArea()
            .accessibilityElement(children: .ignore)

            // (The screen-wide tap catcher went with the AR caliper. Capture
            // is the fixed "+" button only, so a stray screen tap can never
            // fire one.)

            // ADJUST-mode edge handles. The handles are measurement chrome,
            // but they read as controls in a photo, so the Accept snapshot
            // hides them.
            if adjustOverlayVisible && !hidingChromeForCapture {
                GeometryReader { geo in
                    // Handles only. The rect they are fractions of is
                    // published by the chrome reader above, which exists in
                    // every state — see the note there.
                    adjustHandleLayer(in: geo.size)
                }
                // FULL-BLEED, matching the camera view. Without this the
                // overlay stops at the safe area while the AR view runs
                // under it, so the same fraction would mean two different
                // places and the guide row would sample the wrong height.
                .ignoresSafeArea()
                .coordinateSpace(name: Self.adjustSpaceName)
            }

            // Everything below is 2D chrome — hidden as one block while
            // the Accept-time window snapshot is captured so the JPEG
            // shows only the AR feed + measurement overlays.
            if !hidingChromeForCapture {
                VStack(spacing: 0) {
                    topStrip
                    Spacer()
                }

                // Cruise tally target — small top-centre pill naming the
                // tree the loop is aiming at, updating on every save.
                if let target = tallyTreeTitle {
                    VStack(spacing: 0) {
                        tallyTargetPill(target)
                            .padding(.top, 22)
                        Spacer()
                    }
                    .frame(maxWidth: .infinity)
                }

                // Top-centre instruction banner (U1) — the stage-guidance
                // strings moved out of the bottom panel; the orange
                // unsupported banner travels with it.
                MeasureTopBanner(topBannerText) {
                    if let banner = viewModel.unsupportedBanner {
                        bannerView(banner, tint: .orange)
                    }
                    // A "+" that refused says why. Same surface and the same
                    // tap-to-clear as the height screen's anchor-failure
                    // banner, because it is the same defect: a capture button
                    // that does nothing is indistinguishable from a broken
                    // one. It also clears itself once the tap would be
                    // honoured, so it can't sit there after the cruiser has
                    // acted on it.
                    if let refusal = viewModel.captureRefusalReason {
                        bannerView(refusal, tint: .orange,
                                   identifier: "dbhScan.captureRefusalBanner")
                            .onTapGesture { viewModel.clearCaptureRefusal() }
                    }
                    // The accepted diameter did NOT reach a tree row. Loud,
                    // and the value stays on screen so Accept can be retried.
                    if let failure = dbhSaveFailure {
                        bannerView(failure,
                                   tint: ForestixPalette.confidenceBad,
                                   identifier: "dbhScan.saveFailureBanner")
                    }
                    // FIELD REPORT 14 × 17 — a plot is being tallied but no
                    // AR anchor marks its centre, so there is no ring to
                    // draw. Say that, and offer the one act that produces a
                    // centre worth drawing. See `plotCentreNeedsPin`.
                    if showsPinCentreOffer {
                        PlotPinCentreCard(
                            failure: pinCentreFailure,
                            onPin: pinPlotCentre,
                            onDismiss: {
                                pinCentreFailure = nil
                                pinOfferDismissed = true
                            })
                    }
                }

                // Plot mini-map — top-right, same row as the GPS badge.
                // TAPPABLE in cruise: it opens the enlarged plot view, and
                // plot re-setup is a control inside that. Kept up during
                // ADJUST (it's clear of the centre handles), hidden with the
                // rest of the 2D chrome during the Accept snapshot blackout.
                // The cruise border chip renders directly under the card.
                if let info = miniMapInfo {
                    VStack(spacing: 0) {
                        HStack {
                            Spacer()
                            VStack(alignment: .trailing, spacing: 6) {
                                PlotMiniMapWidget(info: info,
                                                  onEditPlot: onEditPlot)
                                if let signed = boundarySignedM,
                                   abs(signed) <= 2.0 {
                                    borderChip(signed)
                                }
                            }
                            .padding(.trailing, ForestixSpace.md)
                        }
                        .padding(.top, 22)
                        Spacer()
                    }
                }

                // Floating back button — full-bleed chrome exit (the system
                // nav bar is hidden on the AR screens). In the cruise tally
                // loop this is also the loop exit back to the cruise map.
                MeasureBackButtonRow()

                // Bottom block (U2): while AIMING the camera-app shutter
                // row sits bottom-centre — Type left, Adjust right — with
                // the live value strip directly above it. RESULT states
                // drop the shutter and show the status/result panel
                // exactly as before. The Developer-mode method picker and
                // the ADJUST Auto pill float 12 pt above whichever block
                // is present. The undo toast (cruise tally) floats above
                // the bottom controls.
                VStack(spacing: 12) {
                    Spacer()
                    if let saved = tallyToastLabel {
                        tallyUndoToast(saved)
                    }
                    if bhGuideChromeVisible {
                        bhGuideButton
                    }
                    if adjustOverlayVisible {
                        autoPillButton
                    }
                    if isAiming {
                        liveValueStrip
                        MeasureShutterRow(
                            showsShutter: showsCaptureButton,
                            capture: captureTap,
                            leading: .init(systemImage: "keyboard",
                                           caption: "Type") {
                                viewModel.enterManualEntry()
                            },
                            trailing: showsAdjustRailButton
                                ? .init(systemImage: "arrow.left.and.right",
                                        caption: "Adjust") { enterAdjustMode() }
                                : nil)
                    } else if showsResultPanel {
                        bottomPanel
                    }
                }
            }
        }
        // FIELD REPORT F3 — the live internals HUD is NO LONGER RENDERED.
        // It covered the AR view and collided with the mini-map / border
        // chip. The component (`DevHUD` / `devHUDOverlay`) and the
        // `devHUDLines` payload below are deliberately kept so it can be
        // switched back on for a bench session; nothing else reads them.
        //
        // RECORDING IS INDEPENDENT OF THIS. The research CSV row is written
        // by `recordResearchRow(r)` from the `.accepted` state change, and
        // the raw-capture bundle by the view model's recorder (armed via
        // `configureRawCapture()` at appear / tree change / burst start),
        // neither of which ever consulted the HUD.
        // Cruise border chip — poll the camera's horizontal distance to
        // the plot's AR anchor at the sampling screen's 0.2 s cadence
        // (same inside/outside machinery, read not observed).
        .task(id: cruisePlotInfo?.plotID) {
            guard cruisePlotInfo != nil else {
                boundarySignedM = nil
                return
            }
            while !Task.isCancelled {
                boundarySignedM = liveBoundarySignedM()
                try? await Task.sleep(nanoseconds: 200_000_000)
            }
        }
        // FIELD REPORT 14 — the plot's tracked centre, refreshed off the
        // body at the sampling screen's cadence. This is what draws the
        // overlay here: the ring and pillar used to be pinned to the plot's
        // ARAnchor, and on THIS screen the ARView is built over a session
        // where that anchor already exists, so RealityKit never saw it
        // arrive and had nothing to bind them to — the overlay simply never
        // appeared. Reading the pose ourselves removes the ordering
        // dependency, and it is the same rule Android runs (see
        // ActiveSamplingPlot.refreshTrackedCentre). It also replaces the old
        // anchor-liveness poll: one gate, not two.
        .task(id: activePlot.plot?.anchorID) {
            guard activePlot.plot != nil else { return }
            while !Task.isCancelled {
                activePlot.refreshTrackedCentre(using: viewModel.session)
                try? await Task.sleep(nanoseconds: 200_000_000)
            }
        }
        // BREAST-HEIGHT GUIDE — one 5 Hz poll, doing whichever of the two
        // reads the current state needs: the aiming ghost's crosshair
        // raycast, or the anchored base's pose. 5 Hz for a centre raycast is
        // the cadence the sampling screen's ghost preview already runs at
        // (see the caller list in `ARCenterRaycaster.meshRaycastHit`), and it
        // runs ONLY while the guide is on — a cruiser with the toggle off
        // pays nothing at all.
        .task(id: bhGuide.stage) {
            guard bhGuide.stage != .off else { return }
            while !Task.isCancelled {
                switch bhGuide.stage {
                case .off:
                    return
                case .aiming:
                    // The same source choice the "Pin centre" raycast makes.
                    raycaster.preferLiDARMesh =
                        settings.measurementSource == .lidar
                    bhGuide.updateGhost(raycaster.screenCenterHit())
                case .placed:
                    bhGuide.refresh(using: viewModel.session)
                }
                try? await Task.sleep(nanoseconds: 200_000_000)
            }
        }
        // The label's screen position, at 20 Hz so it tracks the phone
        // instead of lagging behind it. This is NOT the cost field report 9
        // is about: it is a 4×4 multiply through `ARView.project` and touches
        // no reconstruction mesh. Keyed on the stage and not on the base
        // point — each tick re-reads the live point anyway, so restarting the
        // loop every time the anchor is corrected would buy nothing.
        .task(id: bhGuide.stage) {
            guard bhGuide.stage == .placed else {
                bhLabelPoint = nil
                return
            }
            while !Task.isCancelled {
                bhLabelPoint = bhGuide.ringWorldPoint
                    .flatMap { raycaster.projectToScreen($0) }
                try? await Task.sleep(nanoseconds: 50_000_000)
            }
        }
        // Tally toast auto-hide — 3 s per show; the generation id
        // restarts the clock when a new save replaces the toast.
        .task(id: tallyToastGeneration) {
            guard tallyToastLabel != nil else { return }
            try? await Task.sleep(nanoseconds: 3_000_000_000)
            if !Task.isCancelled {
                withAnimation(.easeOut(duration: 0.18)) {
                    tallyToastLabel = nil
                }
            }
        }
        .onAppear {
            hasLeftScreen = false
            // Phase 19 — pull the cruiser's chosen DBH method off
            // AppSettings every time the screen comes back into view
            // so flipping the picker in Settings takes effect on the
            // next return without leaving the scan screen.
            viewModel.dbhMeasurementMethod = settings.dbhMeasurementMethod
            viewModel.developerMode = settings.developerMode
            // ADJUST IS LIVE FROM THE FIRST FRAME when the cruiser's last
            // choice was the bracket. No Auto interlude: they chose Adjust,
            // so they get Adjust, at the width they were already using.
            //
            // This is deliberately BEFORE `viewModel.onAppear()`, which is
            // what subscribes to depth — so there is no window, not even one
            // tick, in which the automatic edge-finder owns the screen and
            // publishes a fit the bracket would then have to be re-seeded
            // from. That flicker (a couple of seconds of Auto, then a
            // bracket at whatever width the auto fit happened to find) is
            // the report this fixes: "폭이 들쭉날쭉한 상태로 Adjust 모드가
            // 실행".
            //
            // THE HISTORY, because this exact line has been wrong twice in
            // opposite directions. Arming on appear used to open the bracket
            // at a hard-coded 0.25/0.75 — half the walk axis, ~96 px of 192,
            // which against the depth-scaled focal is ≈ 59·z cm and so blew
            // the 100 cm plausibility ceiling of the day at any realistic
            // distance: no fit, no diameter, a dead "+". Two things make the
            // same arming safe now. The ceiling is 300 cm. And the width is
            // no longer a constant chosen for symmetry: it is the cruiser's
            // own remembered half-span, or on a fresh install
            // `AppSettings.defaultBracketHalfWidth`, which is derived from
            // the field corpus and is about half the old number.
            //
            // NOT a preference write. Appearing is not a choice — only the
            // Adjust rail button and the Auto pill move
            // `settings.dbhEdgeAdjustDefault`.
            if settings.dbhEdgeAdjustDefault {
                seedBracketFromRememberedWidth()
                viewModel.edgeAdjustActive = true
            }
            configureRawCapture()
            syncBreastHeightGuide()
            viewModel.onAppear()
        }
        // The wireframe's off-switch — see `showsScanMesh`. Latched, never
        // released: it answers a question that only gets asked once.
        .onChange(of: viewModel.previewFit != nil) { _, has in
            if has, !hasProducedFit { hasProducedFit = true }
        }
        .onDisappear {
            hasLeftScreen = true
            // Leaving without accepting: the held frame belongs to a
            // measurement that was never stored, so the file goes with it.
            // Anything already handed to a reading was released at Accept, so
            // this can only ever delete an orphan.
            discardHeldPhoto()
            // The guide's anchor is the screen's, not the session's: leaving
            // without dropping it would leave a base pinned in the shared
            // world map with nobody holding its id.
            bhGuide.disable(using: viewModel.session)
            bhFailure = nil
            bhLabelPoint = nil
            viewModel.onDisappear()
        }
        // CRUISE TALLY — the loop reuses this one screen and advances its
        // target without re-appearing, so the recording context has to follow
        // the number (belt-and-braces with the rebuild at burst start).
        .onChange(of: captureTreeNumber) { _, _ in
            configureRawCapture()
            // The tally loop advances the target without re-appearing, so a
            // base placed at tree 7's foot would otherwise still be drawn
            // while tree 8 is being measured — at 7's distance and 7's
            // ground. The next tree gets its own base or none.
            bhGuide.clearBase(using: viewModel.session)
            bhFailure = nil
            bhLabelPoint = nil
        }
        // Editing the truth field retires any "couldn't save" state: the text
        // is now the cruiser's current intent for the capture on screen.
        .onChange(of: researchTrueText) { _, _ in
            truthOwnerBundleID = nil
            truthSaveFailure = nil
            // The text is no longer the value that was queued.
            truthQueuedForBundleID = nil
        }
        // A QUEUED truth only becomes durable when the capture that owns it
        // reports SAVED — that is the write which folded the sidecar into the
        // manifest. NOT SAVED means the bundle and the queued value are gone.
        .onChange(of: viewModel.lastCaptureOutcome) { _, outcome in
            resolveQueuedTruth(outcome)
        }
        .onChange(of: settings.rawCaptureEnabled) { _, _ in
            configureRawCapture()
        }
        .onChange(of: settings.developerMode) { _, _ in
            configureRawCapture()
            syncBreastHeightGuide()
        }
        .onChange(of: settings.breastHeightGuide) { _, _ in
            syncBreastHeightGuide()
        }
        .onChange(of: settings.dbhMeasurementMethod) { _, m in
            viewModel.dbhMeasurementMethod = m
        }
        .onChange(of: viewModel.result?.diameterCm) { _, newValue in
            // Fire the host callback when the VM publishes a NON-red result.
            // The host (e.g. the cruise add-tree chain) records it and dismisses the
            // cover, so a red/rejected fit (diameter 0 cm) must NOT be
            // forwarded — the cruiser stays on screen to retake instead.
            if newValue != nil, let r = viewModel.result, r.confidence != .red {
                onResult(r)
            }
        }
        // THE SHUTTER — fires the instant the 5-frame burst finalises and the
        // diameter is computed, while the cruiser is still holding the bracket
        // on the stem. NOT at Accept: see `heldPhoto`.
        //
        // `resultGeneration` is bumped once per `finalizeCapture()`, so this
        // fires exactly once per burst. A failed save (`acceptFailed()`) drops
        // back to `.fitted` WITHOUT producing a new result, so the retry keeps
        // the frame this burst produced instead of photographing the ground.
        .onChange(of: viewModel.resultGeneration) { _, _ in
            // A red fit cannot be accepted (`accept()` refuses it), so no
            // reading will ever carry this photo — don't write a file whose
            // only future is deletion. Any frame held for the superseded
            // measurement goes with it.
            guard let tier = viewModel.result?.confidence, tier != .red else {
                discardHeldPhoto()
                return
            }
            // Raised HERE, in the same synchronous turn as the state change
            // that puts the result panel on screen, so the panel is never
            // composed un-blacked-out: the first frame SwiftUI commits after
            // the burst is already chrome-less, and that is the frame the shot
            // is taken from. `captureHeldPhoto` lowers it again.
            hidingChromeForCapture = true
            Task { @MainActor in await captureHeldPhoto() }
        }
        .onChange(of: viewModel.state) { _, newState in
            switch newState {
            case .idle, .aligning, .armed, .capturing, .manualEntry:
                // Retake / a fresh aim supersedes a failed save: the banner
                // must not hang over the NEXT tree. `.fitted` and `.rejected`
                // are deliberately absent — `acceptFailed()` lands on
                // `.fitted`, which is the state the warning belongs to.
                dbhSaveFailure = nil
            case .fitted, .accepted, .rejected:
                break
            }
            // Separate "accept" hook so callers that want to persist
            // only on an explicit user confirmation (Quick Measure) can
            // distinguish a fitted preview from a committed reading.
            if newState == .accepted, let r = viewModel.result {
                // Auto-capture (map home): the snapshot of the AR view + the
                // measurement overlays was already taken, at the moment the
                // burst landed (see `heldPhoto`) — Accept only attaches it,
                // together with the latest GPS fix from the badge's running
                // location service. A TYPED diameter has no such moment and
                // carries no photo: the frame at Save is the keyboard panel
                // and whatever the phone happened to be pointing at, which is
                // evidence of nothing.
                Task { @MainActor in
                    let photo = heldPhoto
                    // FRESHNESS-GATED. `lastGlobalFix` is the newest fix ANY screen ever saw,
                    // with no age check, so a red GPS chip and a green one used to produce
                    // byte-identical records: a cruiser under heavy canopy stamped every tree in
                    // the plot with the position they had when they walked in. The same rule the
                    // map, the plot verdict and the chip itself use decides here — an unusable
                    // fix stores NO position rather than a confident wrong one.
                    let fix = FixFreshness.usable(
                        LocationService.lastGlobalFix)
                    let meta = ScanMetadata(
                        speciesCode: metaSpecies,
                        position: metaPosition,
                        damageCodes: metaDamage,
                        note: metaNote,
                        photoPath: photo,
                        latitude: fix?.latitude,
                        longitude: fix?.longitude,
                        // Edge provenance. "typed" is its OWN value, not
                        // "auto": a hand-entered diameter had no edge-finder
                        // and no bracket, and filing it under "auto" put a
                        // number the sensors never produced into the same
                        // bucket the algorithm comparison draws from.
                        captureMode: r.method == .manualVisual
                            ? "typed"
                            : (viewModel.resultCapturedManually ? "manual" : "auto"),
                        // The tape value rides WITH the reading. Read before
                        // `applyTypedTruth` runs, because that call clears the
                        // field once the value is durable on the bundle.
                        truth: typedTruthForThisMeasurement)
                    // The host reports whether the reading actually reached
                    // storage. A dropped diameter used to be indistinguishable
                    // from a saved one — the loop reset for the next tree
                    // either way, and in cruise the chain then opened Height
                    // against whatever tree `chainTreeID` still pointed at.
                    let stored = onAccept(r, meta)
                    // The reading owns the file now — release it WITHOUT
                    // deleting, so the cruise tally's `retake()` below and the
                    // screen's own exit can't take the photo off a saved tree.
                    // A failed store keeps it held: Accept can be tapped again
                    // and the same measurement-moment frame goes with it.
                    if stored { heldPhoto = nil }
                    // Raw-capture (developer mode): attach the tape-measured
                    // truth to the just-recorded bundle. The bundle id is
                    // minted synchronously at burst finalize, so this works
                    // even when Accept beats the detached writer — and the
                    // field is not cleared until the value is durable.
                    recordResearchRow(r)
                    applyTypedTruth()
                    guard stored else {
                        dbhSaveFailure = tallyTreeTitle.map {
                            "Diameter NOT saved as \($0) — the tree row couldn't be written. Tap Accept again."
                        } ?? "Diameter NOT saved — the tree row couldn't be written. Tap Accept again."
                        // Back to the result panel so the value is still on
                        // screen and Accept is tappable again. Nothing is
                        // reset: no Undo toast for a tree that isn't there,
                        // and the tally target still names the missing tree.
                        viewModel.acceptFailed()
                        return
                    }
                    dbhSaveFailure = nil
                    // CRUISE QUICK-TALLY LOOP — the host saved the tree
                    // and auto-incremented its target; reset this screen
                    // (scan + per-tree metadata) to aiming for the next
                    // trunk and offer Undo. (The struct copy that built
                    // this task still carries the just-saved tree's label —
                    // the host has already advanced to the next one.)
                    if let saved = tallyTreeTitle {
                        viewModel.retake()
                        metaSpecies = nil
                        metaPosition = .dbh
                        metaDamage = []
                        metaNote = ""
                        withAnimation(.easeOut(duration: 0.18)) {
                            tallyToastLabel = saved
                        }
                        tallyToastGeneration += 1
                    }
                }
            }
        }
        .sheet(isPresented: $presentingMetadata) {
            ScanMetadataSheet(
                kind: .diameter,
                speciesCode: $metaSpecies,
                position: $metaPosition,
                damageCodes: $metaDamage,
                note: $metaNote)
        }
        .onChange(of: scenePhase) { _, phase in
            // Stop the AR session + depth subscription when the user
            // backgrounds the app — without this the camera, LiDAR,
            // and Combine chain keep running inside a screen that's
            // no longer on screen, drain battery fast, and return
            // from background with a stale tracking state.
            switch phase {
            case .active:     viewModel.onAppear()
            case .inactive, .background: viewModel.onDisappear()
            @unknown default: break
            }
        }
    }

    // MARK: - Top status strip

    /// GPS chip at leading 72 / top 22 — clear of the floating back
    /// button, same offsets as Android. TiltBadge floats right above the
    /// crosshair so the cruiser sees device level at the same focal point
    /// as the trunk circle they're aiming at.
    ///
    /// FIELD REPORT 11 — this is the map's chip, not a scan-only variant.
    /// It used to be `GPSAccuracyBadge` ("GPS good / fair / check"), a
    /// second vocabulary for the same question, shown at the exact moment
    /// a position gets written onto a stored measurement. The cruiser
    /// asked for the readout they already trust; the badge is gone.
    private var topStrip: some View {
        HStack(alignment: .top, spacing: ForestixSpace.xs) {
            VStack(alignment: .leading, spacing: 6) {
                GPSFixChip(acquiresService: true)
                // FIELD REPORT F2 — the permanent red REC pill is gone; the
                // cruiser read it as noise. The PER-CAPTURE outcome pill
                // stays, and it is the piece that actually enforces "a
                // failure never looks like a success" ("Raw capture saved"
                // vs "NOT SAVED — <reason>"). Recording itself is untouched:
                // `viewModel.rawCaptureEnabled` and the bundle writer are
                // driven by `configureRawCapture()`, not by this chrome.
                if let outcome = viewModel.lastCaptureOutcome {
                    RawCaptureOutcomePill(outcome: outcome)
                }
            }
            Spacer()
        }
        .padding(.leading, 72)
        .padding(.top, 22)
    }

    // MARK: - Tap capture

    /// True for every pre-capture aiming state — the window in which the
    /// Manual rail button is offered. Mirrors Android's `Stage.AIMING`.
    private var isAiming: Bool {
        switch viewModel.state {
        case .idle, .aligning, .armed:
            return true
        default:
            return false
        }
    }

    /// Whether the floating "+" capture button is rendered. It stays on
    /// screen for the whole aiming phase — a no-op until the crosshair
    /// arms (`viewModel.tap` requires `.armed`) — so the control layout is
    /// stable while the cruiser lines up.
    private var showsCaptureButton: Bool {
        switch viewModel.state {
        case .idle, .aligning, .armed: return true
        default:                       return false
        }
    }

    /// Capture the trunk at the depth-map centre — the crosshair the
    /// cruiser lined up on the trunk. Fired by the "+" button.
    private func captureTap() {
        // Rebuild the recording context from LIVE values at burst start. The
        // cruise tally reuses ONE screen instance and advances its tree number
        // without the screen re-appearing, so a context built only in
        // `.onAppear` stamped every bundle in the plot with the FIRST tree's
        // number. This call runs off the current body, so `tallyTreeNumber`
        // here is the tree actually being measured. (It also refreshes the GPS
        // tag, which is why the old fix-refresh line is gone.)
        configureRawCapture()
        let frame = viewModel.session.latestDepthFrame
        let width  = Double(frame?.width  ?? 256)
        let height = Double(frame?.height ?? 192)
        viewModel.tap(at: SIMD2(width / 2.0, height / 2.0))
    }

    /// Push the raw-capture recording config onto the view model (developer
    /// mode only; the VM no-ops recording when disabled). Called on appear,
    /// whenever the tally target changes, and again at every burst start.
    private func configureRawCapture() {
        viewModel.rawCaptureEnabled = settings.developerMode && settings.rawCaptureEnabled
        viewModel.rawCaptureContext = RawCaptureContext(
            mode: cruisePlotInfo != nil ? "cruise" : "quick",
            projectID: projectID,
            plotID: cruisePlotInfo?.plotID?.uuidString,
            treeNumber: captureTreeNumber,
            units: settings.unitSystem.rawValue)
        viewModel.rawCaptureGPS = Self.currentGPS()
        // Manual entry is typed in whatever the active unit system is.
        viewModel.manualEntryUnits = settings.unitSystem
        storageLow = viewModel.rawCaptureEnabled && RawCaptureStore.isStorageLow()
    }

    static func currentGPS() -> RawCaptureGPS? {
        // Same freshness gate as the accept path — a bundle must not
        // claim a position the app would refuse to draw.
        guard let fix = FixFreshness.usable(LocationService.lastGlobalFix)
        else { return nil }
        return RawCaptureGPS(lat: fix.latitude, lon: fix.longitude,
                             accM: fix.horizontalAccuracyM)
    }

    // (The AR-caliper edge taps, its distance estimator, both AR overlays and
    // the Depth | Motion | Caliper picker were removed with the AR capture
    // arms. Depth is the only sensing path now, so there is nothing to pick.)

    // MARK: - Chrome

    /// HUD payload — KEPT but NOT RENDERED (field report F3; see the note
    /// on `body`). Recording does not read any of this.
    private var devHUDLines: [(String, String)] {
        var out: [(String, String)] = [
            ("method", methodSource.shortTag),
            ("fit", viewModel.dbhMeasurementMethod.rawValue),
        ]
        if let f = viewModel.session.latestDepthFrame {
            out.append(("depthMap", "\(f.width)×\(f.height)"))
            out.append(("fx/fy", String(format: "%.0f/%.0f", f.intrinsics[0, 0], f.intrinsics[1, 1])))
            out.append(("cx/cy", String(format: "%.0f/%.0f", f.intrinsics[2, 0], f.intrinsics[2, 1])))
        }
        // Recording state + the join key that goes into the bundle, so a
        // frozen tree number is visible in the HUD instead of after the fact.
        out.append(("raw", viewModel.rawCaptureEnabled
                    ? "REC · tree \(captureTreeNumber.map(String.init) ?? "—")"
                    : "OFF — not recording"))
        out.append(("Ø live", viewModel.previewDbhCm.map { String(format: "%.1f cm", $0) } ?? "—"))
        out.append(("dist", viewModel.distanceToStemCenterM.map { String(format: "%.2f m", Double($0)) } ?? "—"))
        if let r = viewModel.result {
            out.append(("Ø saved", String(format: "%.1f ±%.0fmm", r.diameterCm, r.sigmaRmm)))
            out.append(("arc/n", String(format: "%.0f°/%d", r.arcCoverageDeg, r.nInliers)))
            out.append(("tier", r.confidence.rawValue))
        }
        return out
    }

    private func guideLine(height: CGFloat) -> some View {
        // Dual-stroke line for sun-glare readability: a thin dark halo
        // under a bright white line. On either a bright sky or dark
        // foliage background, at least one of the two strokes has
        // enough contrast to stay visible.
        ZStack {
            Rectangle()
                .fill(Color.black.opacity(0.55))
                .frame(height: 3)
            Rectangle()
                .fill(Color.white.opacity(0.9))
                .frame(height: 1.5)
        }
        .frame(height: 3)
        .position(x: UIScreenWidth() / 2, y: height / 2)
        .accessibilityIdentifier("dbhScan.guideLine")
    }

    /// Outer radius of the crosshair ring (including the dark halo).
    /// Used as a layout anchor for the live preview pills so they sit
    /// just below the ring without overlapping it.
    private static let crosshairOuterRadius: CGFloat = 36
    /// Vertical extent (above + below the chord) of the side
    /// indicators. Tall, deliberately visible bars — the cruiser
    /// asked for more pop on these so a "fit locked" reads at a
    /// glance even with the trunk only partly in frame.
    private static let chordIndicatorHalfHeight: CGFloat = 22

    /// Bright green horizontal segment spanning the trunk edges the
    /// single-frame fit identified along the guide row — a visual
    /// "this is what I'm measuring" so the cruiser can tell when the
    /// fit has locked onto the trunk vs wandered into the background.
    /// Only drawn once the crosshair has stabilised; hidden during
    /// capture / fit / accept so it doesn't distract from the result.
    /// In ADJUST mode the chord bar tracks the handles exactly instead.
    @ViewBuilder
    private func fitChord(in size: CGSize) -> some View {
        if viewModel.edgeAdjustActive {
            if adjustOverlayVisible {
                let lo = min(viewModel.edgeBracketLeftFraction,
                             viewModel.edgeBracketRightFraction)
                let hi = max(viewModel.edgeBracketLeftFraction,
                             viewModel.edgeBracketRightFraction)
                chordBar(x0: size.width * CGFloat(lo),
                         x1: size.width * CGFloat(hi),
                         in: size)
            }
        } else if let fit = viewModel.previewFit,
                  viewModel.crosshairIsStable,
                  fit.stripRightFraction > fit.stripLeftFraction {
            chordBar(x0: size.width * CGFloat(fit.stripLeftFraction),
                     x1: size.width * CGFloat(fit.stripRightFraction),
                     in: size)
        }
    }

    /// Shared chord-bar drawing — main line + the two side indicators.
    private func chordBar(x0: CGFloat, x1: CGFloat, in size: CGSize) -> some View {
        let y = size.height / 2
        let half = Self.chordIndicatorHalfHeight
        return ZStack(alignment: .topLeading) {
            // Main chord line — slightly thicker so it reads
            // even when overdrawn on top of the LiDAR mesh.
            Rectangle()
                .fill(ForestixPalette.confidenceOk.opacity(0.95))
                .frame(width: max(0, x1 - x0), height: 4)
                .position(x: (x0 + x1) / 2, y: y)
            // Left side indicator — tall vertical bar with a dark
            // halo for sun-readability. Length is 2 × half so it
            // pops well above and below the chord line.
            sideIndicator(x: x0, y: y, half: half)
            // Right side indicator
            sideIndicator(x: x1, y: y, half: half)
        }
        .accessibilityIdentifier("dbhScan.fitChord")
        .allowsHitTesting(false)
    }

    /// Trunk-side indicator: dark halo bar with a coloured bar on
    /// top, centred at (x, y) and 2×half tall.
    private func sideIndicator(x: CGFloat, y: CGFloat, half: CGFloat) -> some View {
        ZStack {
            Rectangle()
                .fill(Color.black.opacity(0.55))
                .frame(width: 5, height: 2 * half)
            Rectangle()
                .fill(ForestixPalette.confidenceOk)
                .frame(width: 3, height: 2 * half)
        }
        .position(x: x, y: y)
    }

    // MARK: - ADJUST (edge-bracket) mode chrome

    /// Named coordinate space the handle drags resolve in — the full
    /// overlay layer, so `location.x / width` is the handle fraction.
    private static let adjustSpaceName = "dbhScan.adjustSpace"
    /// Half-height of the ADJUST handle lines and the band highlight.
    private static let adjustHandleHalfHeight: CGFloat = 44
    /// Smallest allowed handle separation (fraction of view width).
    private static let adjustMinGapFraction: Double = 0.04

    /// The bracket's half-SPAN, read off the two published fractions.
    ///
    /// They stay the source of truth — the estimator and the chord overlay
    /// both consume them, and each handle moves independently — so this is
    /// not the whole bracket, only the part worth carrying to the next tree:
    /// a re-opened bracket has nothing but the crosshair to centre on, and
    /// the width is what the cruiser would otherwise re-drag.
    private var bracketHalfWidth: Double {
        (viewModel.edgeBracketRightFraction
            - viewModel.edgeBracketLeftFraction) / 2
    }

    /// Sets both handles from one half-width, mirrored about the crosshair.
    private func setBracketHalfWidth(_ half: Double) {
        let clamped = AppSettings.clampBracketHalfWidth(half)
        viewModel.edgeBracketLeftFraction  = 0.5 - clamped
        viewModel.edgeBracketRightFraction = 0.5 + clamped
    }

    /// True while the ADJUST chrome (handles, band, Auto pill, tracked
    /// chord bar) is on screen: ADJUST active and a state where the live
    /// estimate runs (plus `.capturing`, so the frozen bracket stays
    /// visible through the burst).
    private var adjustOverlayVisible: Bool {
        guard viewModel.edgeAdjustActive else { return false }
        switch viewModel.state {
        case .idle, .aligning, .armed, .capturing, .rejected: return true
        default: return false
        }
    }

    /// Two vertical draggable handles + a subtle band between them at
    /// the guide-line height. The live estimate uses exactly this span.
    @ViewBuilder
    private func adjustHandleLayer(in size: CGSize) -> some View {
        let y = size.height / 2
        let xL = size.width * CGFloat(min(viewModel.edgeBracketLeftFraction,
                                          viewModel.edgeBracketRightFraction))
        let xR = size.width * CGFloat(max(viewModel.edgeBracketLeftFraction,
                                          viewModel.edgeBracketRightFraction))
        // Subtle highlight of the bracketed span.
        Rectangle()
            .fill(Color.white.opacity(0.10))
            .frame(width: max(0, xR - xL),
                   height: 2 * Self.adjustHandleHalfHeight)
            .position(x: (xL + xR) / 2, y: y)
            .allowsHitTesting(false)
        adjustHandle(atX: xL, y: y, isLeft: true, viewWidth: size.width)
        adjustHandle(atX: xR, y: y, isLeft: false, viewWidth: size.width)
    }

    /// One draggable edge handle: white 2 pt line with a small grab
    /// circle, dark halo for sun-glare readability, ≥44 pt hit target.
    private func adjustHandle(atX x: CGFloat, y: CGFloat,
                              isLeft: Bool, viewWidth: CGFloat) -> some View {
        let lineHeight = 2 * Self.adjustHandleHalfHeight
        return ZStack {
            Rectangle()
                .fill(Color.black.opacity(0.45))
                .frame(width: 4, height: lineHeight)
            Rectangle()
                .fill(.white)
                .frame(width: 2, height: lineHeight)
            Circle()
                .fill(.white)
                .frame(width: 14, height: 14)
                .overlay(Circle().stroke(Color.black.opacity(0.35),
                                         lineWidth: 1))
        }
        .frame(width: 44, height: max(44, lineHeight))
        .contentShape(Rectangle())
        .position(x: x, y: y)
        .gesture(
            DragGesture(minimumDistance: 0,
                        coordinateSpace: .named(Self.adjustSpaceName))
                // INDEPENDENT HANDLES. Each one moves on its own.
                //
                // They were briefly symmetric about the crosshair, which is
                // what "move one side and both match" was read to mean. It
                // measured wrong. A stem is only symmetric about the
                // crosshair if the crosshair is exactly on its centre, and
                // in the stand it usually is not — so to cover the trunk the
                // cruiser had to open the bracket until BOTH edges cleared
                // it, and the span came out wider than the tree. Centre off
                // by half a radius and the symmetric span is 2·(1.5r) = 3r
                // against a true diameter of 2r: a 1.5× over-read, which is
                // what the field measured.
                //
                // Placing each edge where the edge actually is has no such
                // failure mode, and it is what worked before. The width
                // still carries over to the next tree, which was the other
                // half of the request and the part that saves real time.
                .onChanged { v in
                    guard viewWidth > 1 else { return }
                    let frac = min(max(Double(v.location.x / viewWidth),
                                       0.02), 0.98)
                    if isLeft {
                        viewModel.edgeBracketLeftFraction = min(
                            frac,
                            viewModel.edgeBracketRightFraction
                                - Self.adjustMinGapFraction)
                    } else {
                        viewModel.edgeBracketRightFraction = max(
                            frac,
                            viewModel.edgeBracketLeftFraction
                                + Self.adjustMinGapFraction)
                    }
                }
                // Persisted on release, not on every frame of the drag: the
                // next tree opens at the width this one ended on.
                .onEnded { _ in
                    settings.dbhBracketHalfWidth = bracketHalfWidth
                }
        )
        .accessibilityIdentifier(isLeft ? "dbhScan.adjustHandleLeft"
                                        : "dbhScan.adjustHandleRight")
        .accessibilityLabel(isLeft ? "Left trunk edge" : "Right trunk edge")
        // The hint used to say "Both edges move together", which is what the
        // symmetric bracket did before it was reverted for over-reading by
        // 1.5x. VoiceOver was describing a behaviour the code no longer has.
        .accessibilityHint("Drag to set this edge of the trunk. Each edge moves on its own.")
        // VoiceOver cannot drag, so the width is also reachable in steps.
        .accessibilityAdjustableAction { direction in
            // Widen / narrow by moving THIS edge outward or inward.
            let step = 0.01
            let outward = direction == .increment
            if isLeft {
                viewModel.edgeBracketLeftFraction = min(
                    max(viewModel.edgeBracketLeftFraction
                            + (outward ? -step : step), 0.02),
                    viewModel.edgeBracketRightFraction
                        - Self.adjustMinGapFraction)
            } else {
                viewModel.edgeBracketRightFraction = max(
                    min(viewModel.edgeBracketRightFraction
                            + (outward ? step : -step), 0.98),
                    viewModel.edgeBracketLeftFraction
                        + Self.adjustMinGapFraction)
            }
            settings.dbhBracketHalfWidth = bracketHalfWidth
        }
    }

    /// Way back to automatic edge-finding — black-scrim capsule pill
    /// floating just above the status panel while ADJUST is active.
    private var autoPillButton: some View {
        Button {
            viewModel.edgeAdjustActive = false
            // Remembered, so a cruiser who prefers the automatic edges is
            // not handed the bracket again on the next tree. This pill and
            // the ADJUST rail button are the whole control — the preference
            // has no Settings row of its own.
            settings.dbhEdgeAdjustDefault = false
        } label: {
            Text("Auto")
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(.white)
                .padding(.horizontal, 14)
                .padding(.vertical, 7)
                .background(Color.black.opacity(0.55), in: Capsule())
                .overlay(Capsule().stroke(.white.opacity(0.18),
                                          lineWidth: 0.5))
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("dbhScan.autoMode")
    }

    // MARK: - Breast-height guide

    /// The gate, and the ONLY place it is decided: developer mode AND the
    /// guide's own toggle. Neither key is ever read alone.
    private var bhGuideEnabled: Bool {
        settings.developerMode && settings.breastHeightGuide
    }

    /// Whether any of the guide is on screen right now — the gate plus the
    /// guide having actually been armed.
    private var bhGuideActive: Bool {
        bhGuideEnabled && bhGuide.stage != .off
    }

    /// Bring the guide's state into line with the gate. Idempotent, so it can
    /// be called from appear and from either toggle changing.
    private func syncBreastHeightGuide() {
        if bhGuideEnabled {
            bhGuide.arm()
        } else {
            bhGuide.disable(using: viewModel.session)
            bhFailure = nil
            bhLabelPoint = nil
        }
    }

    /// The guide's world geometry, or nothing.
    ///
    /// HIDDEN DURING THE ACCEPT SNAPSHOT. The held JPEG is attached to the
    /// reading and travels with the export, and the guide is a guide: it must
    /// not appear in an export, so it goes away for the frame like every
    /// other non-measurement overlay.
    private var bhGuideMarkers: [ARSceneMarker] {
        guard bhGuideActive, !hidingChromeForCapture else { return [] }
        return bhGuide.markers()
    }

    /// Prompt pill CENTRE, measured down from the crosshair centre. Exactly
    /// the Height screen's `crosshairLabelOffset` — this is the same
    /// affordance placed the same way, and the two must not drift apart.
    private static let bhPromptOffset: CGFloat = 40

    /// How far right of the ring's projected centre the value pill sits, so
    /// it never covers the band the cruiser is reading against the bark.
    /// True while the screen is still AIMING at a stem — not showing a
    /// result, not mid-burst.
    ///
    /// The guide is an aid to standing in the right place, so once a diameter
    /// is on screen it has nothing left to say and its pills and its button
    /// are competing with the result panel for the same corner. Android gates
    /// its guide chrome on `stage == Stage.AIMING`; this is that same test in
    /// this screen's own vocabulary, and it is the set `adjustOverlayVisible`
    /// already treats as "still aiming". The WORLD geometry stays drawn either
    /// way — the base is still where the cruiser put it.
    private var bhGuideChromeVisible: Bool {
        guard bhGuideActive, !hidingChromeForCapture else { return false }
        switch viewModel.state {
        case .idle, .aligning, .armed, .capturing, .rejected: return true
        default: return false
        }
    }

    private static let bhLabelSideOffset: CGFloat = 14

    /// Half a pill's height, so the label reads LEVEL with the ring rather
    /// than hanging below it. Android lifts by the same amount.
    private static let bhLabelRiseOffset: CGFloat = 12

    /// What the guide has to say under the crosshair, or nothing when it has
    /// nothing to say (a base is placed and being tracked — the world
    /// geometry speaks for itself).
    private var bhPromptText: String? {
        switch bhGuide.stage {
        case .off:
            return nil
        case .aiming:
            return "Aim at the tree base"
        case .placed:
            return bhGuide.trackingLost
                ? "Tracking lost — the guide is hidden rather than drawn in the wrong place."
                : nil
        }
    }

    /// The Height crosshair's label pill — white on black, so it reads as
    /// guidance and never as a measurement.
    private func bhPromptPill(_ text: String) -> some View {
        Text(text)
            .font(ForestixType.dataSmall)
            .foregroundStyle(.white)
            .multilineTextAlignment(.center)
            .padding(.horizontal, 8).padding(.vertical, 4)
            .background(Color.black.opacity(0.65))
            .cornerRadius(4)
            .accessibilityIdentifier("dbhScan.breastHeightPrompt")
    }

    /// "1.37 m" / "4.5 ft" at the ring. BLACK ON WHITE, deliberately the
    /// inverse of every other pill on this screen: it is read against bark
    /// and against sky in the same glance, and white-on-black loses the sky.
    private var bhValuePill: some View {
        Text(BreastHeightGuide.label(in: settings.unitSystem))
            .font(ForestixType.dataSmall)
            .foregroundStyle(.black)
            .padding(.horizontal, 8).padding(.vertical, 4)
            .background(Color.white)
            .cornerRadius(4)
            .accessibilityIdentifier("dbhScan.breastHeightLabel")
    }

    /// Place / clear the base — the guide's whole control surface. It sits in
    /// the block above the shutter, beside the Auto pill: both of the
    /// shutter's own flanks are Type and Adjust, and the guide must not take
    /// either. Same dark-glass capsule as `autoPillButton`.
    private var bhGuideButton: some View {
        VStack(spacing: 6) {
            Button {
                if bhGuide.stage == .placed {
                    bhGuide.clearBase(using: viewModel.session)
                    bhFailure = nil
                    bhLabelPoint = nil
                } else {
                    placeBreastHeightBase()
                }
            } label: {
                Text(bhGuide.stage == .placed ? "Clear base" : "Place base")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 7)
                    .background(Color.black.opacity(0.55), in: Capsule())
                    .overlay(Capsule().stroke(.white.opacity(0.18),
                                              lineWidth: 0.5))
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("dbhScan.breastHeightBase")
            if let failure = bhFailure {
                Text(failure)
                    .font(ForestixType.caption)
                    .foregroundStyle(.white)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 10).padding(.vertical, 5)
                    .background(Color.black.opacity(0.55), in: Capsule())
            }
        }
    }

    /// Anchor the guide at the ground under the crosshair.
    ///
    /// `screenCenterHit()` and deliberately NOT `screenCenterAnchorHit` — this
    /// ray is aimed DOWN at the ground, and the gated variant exists
    /// precisely to refuse ground planes. A miss refuses in the words every
    /// other crosshair-to-ground placement in the app uses, rather than
    /// planting a base in mid-air off a forward-ray fallback.
    private func placeBreastHeightBase() {
        raycaster.preferLiDARMesh = settings.measurementSource == .lidar
        guard let hit = raycaster.screenCenterHit() else {
            bhFailure = MeasurementCopy.plotGroundNotSeen
            return
        }
        guard bhGuide.place(hit: hit, using: viewModel.session) else {
            bhFailure = MeasurementCopy.plotGroundNotSeen
            return
        }
        bhFailure = nil
    }

    private var crosshairRing: some View {
        let color: Color = viewModel.crosshairIsStable
            ? ForestixPalette.confidenceOk
            : ForestixPalette.confidenceBad
        let outer = Self.crosshairOuterRadius * 2     // 72 pt total
        let inner = outer - 8                          // ring inset
        return ZStack {
            // Dual-stroke ring — dark halo underneath the coloured
            // ring so the crosshair stays visible against both sky
            // and foliage. Sized to match the cruiser's request for
            // a clearly-visible target ring on the AR feed.
            Circle()
                .strokeBorder(Color.black.opacity(0.6), lineWidth: 5)
                .frame(width: outer, height: outer)
            Circle()
                .strokeBorder(color, lineWidth: 2.5)
                .frame(width: inner, height: inner)
            // Determinate capture progress — a green arc sweeping
            // 0→360° over the ring as the 5-frame burst advances, so
            // the cruiser can see the capture running (and how much
            // hold-steady time is left) without looking away.
            if viewModel.state == .capturing {
                Circle()
                    .trim(from: 0, to: captureProgress)
                    .stroke(ForestixPalette.confidenceOk,
                            style: StrokeStyle(lineWidth: 3, lineCap: .round))
                    .rotationEffect(.degrees(-90))
                    .frame(width: inner - 2.5, height: inner - 2.5)
                    .animation(.linear(duration: 0.45), value: captureProgress)
                    .accessibilityIdentifier("dbhScan.captureProgressArc")
            }
        }
        .accessibilityIdentifier("dbhScan.crosshair")
        // Spoken to VoiceOver on the main diameter flow: "depth stable" was
        // the internal `crosshairIsStable` condition read aloud verbatim,
        // and "tap to capture" named an interaction the screen no longer
        // has — capture is the "+" shutter, not a tap on the ring.
        .accessibilityLabel(viewModel.crosshairIsStable
                            ? "Locked on the trunk — press the capture button"
                            : "Lining up — move closer or hold steadier")
    }

    /// Fraction of the capture burst completed (0…1) — drives the
    /// crosshair progress arc.
    private var captureProgress: Double {
        Double(max(1, viewModel.captureSampleIndex))
            / Double(max(1, viewModel.captureSampleTotal))
    }

    /// Floating status pill shown directly under the crosshair for the
    /// whole burst — same pill style as the Height screen's aim labels.
    /// Flips in the instant the "+" starts the capture, so the cruiser
    /// gets immediate feedback that the burst is running.
    private var captureProgressPill: some View {
        Text("Capturing \(max(1, viewModel.captureSampleIndex))/\(viewModel.captureSampleTotal) — hold steady.")
            .font(ForestixType.dataSmall)
            .foregroundStyle(.white)
            .padding(.horizontal, 8).padding(.vertical, 4)
            .background(Color.black.opacity(0.65))
            .cornerRadius(4)
            .accessibilityIdentifier("dbhScan.capturePill")
    }

    /// Compact live-value strip directly above the shutter row (U2):
    ///   • "DBH: 34.5 cm" — diameter estimate (dataLarge, primary)
    ///   • "Distance: 1.25 m" — camera-to-stem-axis distance (dataSmall)
    /// When the fit fails the §7.1 sanity tree (red) or hasn't settled
    /// yet, the numeric pill is replaced with a status string so the
    /// cruiser never reads a value the burst would later reject. Phase
    /// 14.4 made the published preview match the burst's quality bar —
    /// the value on screen is the value you can record.
    @ViewBuilder
    private var liveValueStrip: some View {
        if let cm = viewModel.previewDbhCm {
            VStack(spacing: 4) {
                // Field fix carried over: no tier chip on the live
                // value — the digit (and the lock colour on the
                // ring/chord) is the signal; tier logic stays
                // internal for gating + records.
                MeasureValuePill(
                    "DBH: " + MeasurementFormatter.diameter(
                        cm: cm, in: settings.unitSystem),
                    size: .large)
                    .accessibilityIdentifier("dbhScan.livePreview")
                if let d = viewModel.distanceToStemCenterM {
                    MeasureValuePill(
                        "Distance: " + MeasurementFormatter.distance(
                            m: Double(d), in: settings.unitSystem),
                        dimmed: true)
                        .accessibilityIdentifier("dbhScan.distanceBadge")
                }
            }
        } else if let status = viewModel.previewStatusText {
            // Phase 19 — only the legacy partial-arc method ever sets
            // `previewStatusText` (the chord method returns nil for
            // unmeasurable frames instead of producing a red fit).
            MeasureValuePill(status)
                .accessibilityIdentifier("dbhScan.previewStatus")
        }
    }

    // MARK: - Cruise tally chrome (target pill, undo toast, border chip)

    /// Top-centre target pill — which tree the tally loop is aiming at right
    /// now. Updates as the host auto-increments.
    /// (13 semibold on black 0.55 — Android parity.)
    ///
    /// TAPPABLE in the cruise loop: this is where the tree gets its name, and
    /// it is deliberately the pill rather than a step in front of the scan.
    /// The cruiser is already aiming at the trunk; the name is offered
    /// pre-filled by `TreeNameSequence` and only has to be touched when the
    /// series starts or breaks, so the loop stays zero-typing per tree.
    private func tallyTargetPill(_ title: String) -> some View {
        Button {
            onRenameTally?()
        } label: {
            Text(title)
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(.white)
                .lineLimit(1)
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(Color.black.opacity(0.55), in: Capsule())
                .overlay(Capsule().stroke(.white.opacity(0.18), lineWidth: 0.5))
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .disabled(onRenameTally == nil)
        .accessibilityLabel(onRenameTally == nil ? title : "\(title). Rename")
        .accessibilityIdentifier("dbhScan.tallyTarget")
    }

    /// "Tree #7 saved · Undo" — dark-glass toast above the bottom
    /// controls for 3 s after every tally Accept; Undo is the tappable
    /// bold segment. Metrics mirror Android's snackbar-equivalent pill.
    private func tallyUndoToast(_ title: String) -> some View {
        HStack(spacing: 0) {
            Text("\(title) saved · ")
                .font(.system(size: 14, weight: .medium))
                .foregroundStyle(.white)
                .lineLimit(1)
            Button {
                onUndoTally?()
                tallyToastLabel = nil
            } label: {
                Text("Undo")
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 8)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("dbhScan.tallyUndo")
        }
        .padding(.leading, 14)
        .padding(.trailing, 4)
        .padding(.vertical, 4)
        .background(Color.black.opacity(0.65), in: Capsule())
        .overlay(Capsule().stroke(.white.opacity(0.18), lineWidth: 0.5))
        .transition(.move(edge: .bottom).combined(with: .opacity))
        .accessibilityIdentifier("dbhScan.tallyToast")
    }

    /// Signed camera→boundary distance from the plot's AR anchor
    /// (negative = inside the ring). Reuses the sampling screen's
    /// horizontal-distance machinery; nil when the live anchor doesn't
    /// belong to THIS cruise plot (app restart, other ring placed).
    private func liveBoundarySignedM() -> Double? {
        guard let info = cruisePlotInfo else { return nil }
        let store = ActiveSamplingPlot.shared
        // The TRACKED centre, not the raw anchor pose: a border chip counted
        // off an uncorrected pose is the same lie the ring itself would be.
        guard store.plot != nil,
              store.linkedCruisePlotID == info.plotID,
              let centre = store.centreWorld,
              let cam = viewModel.session.currentCameraWorldPosition
        else { return nil }
        let dx = Double(cam.x - centre.x)
        let dz = Double(cam.z - centre.z)
        return (dx * dx + dz * dz).squareRoot() - info.radiusM
    }

    /// Dark-glass boundary pill under the mini-map: "Border 1.3 m"
    /// while inside within 2 m of the ring, "Outside plot" (warn tint)
    /// while outside within 2 m. Hidden otherwise. (12 semibold on
    /// black 0.55 — Android parity.)
    private func borderChip(_ signedM: Double) -> some View {
        let outside = signedM >= 0
        return Text(outside
                    ? "Outside plot"
                    : String(format: "Border %.1f m", -signedM))
            .font(.system(size: 12, weight: .semibold))
            .foregroundStyle(outside
                             ? ForestixPalette.confidenceWarn
                             : .white)
            .padding(.horizontal, 10)
            .padding(.vertical, 5)
            .background(Color.black.opacity(0.55), in: Capsule())
            .overlay(Capsule().stroke(.white.opacity(0.18), lineWidth: 0.5))
            .accessibilityIdentifier("dbhScan.borderChip")
    }

    // MARK: - AR markers

    /// Blue translucent cylinder rendered at the live preview fit.
    /// The cylinder is 0.30 m tall, vertically centred on the chord
    /// height (the guide-row world Y — the measure line) so it sleeves
    /// the trunk right where the reading is taken. Empty when no
    /// preview is available.
    private var cylinderMarkers: [ARSceneMarker] {
        guard let fit = viewModel.previewFit,
              let y = viewModel.guideRowWorldY
        else { return [] }
        let pos = SIMD3<Float>(
            Float(fit.centerWorldXZ.x),
            y,
            Float(fit.centerWorldXZ.y))   // SIMD2.y = world Z
        return [
            ARSceneMarker(
                id: Self.cylinderMarkerId,
                worldPosition: pos,
                shape: .cylinder(radiusM: Float(fit.radiusM), heightM: 0.30),
                colorRGBA: SIMD4(0.30, 0.65, 1.00, 0.45))
        ]
    }

    /// Stable UUID so the cylinder anchor doesn't get torn down and
    /// rebuilt on every frame — just its position / radius updates.
    /// Must be a valid hex UUID: 0-9 / a-f only, no alphabetic filler.
    /// The `?? UUID()` fallback keeps a typo from crashing the scan
    /// screen — worst case the cylinder anchor gets rebuilt each
    /// frame instead of diffed, which is ugly but not fatal.
    private static let cylinderMarkerId: UUID =
        UUID(uuidString: "00DBC415-0000-0000-0000-000000000001") ?? UUID()

    /// Subdued sampling-plot context (ring + centre pole at ~0.5 alpha, at
    /// the plot's tracked centre). Shown only while a plot is active AND
    /// ARKit is correcting its centre — after an app restart, a session
    /// reset, or a lost-tracking stretch there is no tracked centre and
    /// nothing is drawn. Non-interactive by construction (scene markers
    /// take no input) and listed before the measurement markers.
    private var plotOverlayMarkers: [ARSceneMarker] {
        guard let plot = activePlot.plot,
              let centre = activePlot.centreWorld
        else { return [] }
        return ActiveSamplingPlot.subduedOverlayMarkers(for: plot,
                                                        centre: centre)
    }

    // MARK: - "Pin centre" offer (field report 14 × 17)

    /// A cruise plot is being tallied but NO AR anchor marks its centre, so
    /// `plotOverlayMarkers` is empty and the cruiser is looking at a bare
    /// camera feed with a plot open.
    ///
    /// That is every plot opened from a planned pin ("Start plot now" — the
    /// fast route report 17 introduced) and every plot carried across an
    /// app restart, because the ARKit world
    /// map dies with the process. The linked-id test is the same one the
    /// border chip and the mini-map use: an "Add tree" can target an OLDER
    /// open plot than the last-placed ring, and that ring is not this
    /// plot's centre.
    ///
    /// Deliberately NOT gated on tracking loss. A tracking dip hides a ring
    /// that EXISTS and already says so in its own words
    /// (`plotTrackingLostHint`); this card would be a second, wrong
    /// explanation for it.
    private var plotCentreNeedsPin: Bool {
        guard let plotID = cruisePlotInfo?.plotID,
              pinnableRadiusM != nil
        else { return false }
        if activePlot.plot != nil, activePlot.linkedCruisePlotID == plotID {
            return false
        }
        return true
    }

    /// The plot's OWN radius, or nil when its stored area can't produce a
    /// sane one. There is no honest radius to invent in its place, so the
    /// offer stands down rather than drawing a ring of a made-up size.
    /// Same > 0.5 m floor Android's plot mini-map applies to the same field.
    private var pinnableRadiusM: Double? {
        guard let r = cruisePlotInfo?.radiusM, r.isFinite, r > 0.5 else {
            return nil
        }
        return r
    }

    /// Whether the offer is on screen right now.
    private var showsPinCentreOffer: Bool {
        plotCentreNeedsPin && !pinOfferDismissed && !hidingChromeForCapture
    }

    /// Plant the plot centre where the crosshair meets the ground and hand
    /// the ring to THIS cruise plot.
    ///
    /// The act is the AR "Start plot" act: the cruiser stands at the centre
    /// and pins it, so the ring is worth what that ring has always been
    /// worth. The stored `Plot.centerLat/centerLon` are deliberately NOT
    /// rewritten — the card says so — because a control that silently moved
    /// a plot centre to wherever the cruiser was standing is exactly the
    /// invisible data loss `editCruisePlot` refuses.
    ///
    /// NO forward-ray fallback, unlike the two placement screens. There the
    /// ghost preview shows the cruiser where a 3 m-forward fallback point
    /// lands before they commit; here there is no preview (a mesh raycast
    /// on a poll is the cost field report 9 was about), so a fallback would
    /// plant the centre in mid-air with nothing to warn them. Refusing is
    /// the honest half of that trade, and the message says what to do.
    private func pinPlotCentre() {
        guard let plotID = cruisePlotInfo?.plotID,
              let radiusM = pinnableRadiusM
        else { return }
        raycaster.preferLiDARMesh = settings.measurementSource == .lidar
        guard let hit = raycaster.screenCenterHit() else {
            pinCentreFailure = MeasurementCopy.plotGroundNotSeen
            return
        }
        // Replace any earlier ring's anchor rather than leaving it in the
        // session — `ActiveSamplingPlot.place` drops the reference and only
        // the caller can still name the anchor to remove.
        if let previous = activePlot.plot {
            viewModel.session.removeWorldAnchor(id: previous.anchorID)
        }
        guard let anchorID = viewModel.session.addWorldAnchor(
            at: hit, name: "forestix.samplingPlot.center")
        else {
            pinCentreFailure = MeasurementCopy.plotGroundNotSeen
            return
        }
        pinCentreFailure = nil
        // The plot's OWN radius, not whatever the sampling slider was last
        // left at — this ring stands for a saved cruise plot.
        activePlot.place(anchorID: anchorID, radiusM: radiusM)
        // `place` clears the link (a fresh ring belongs to nobody), so the
        // stamp has to come after it.
        activePlot.link(cruisePlotID: plotID)
    }

    // MARK: - Bottom panel

    /// RESULT states that render the bottom status/result panel (U2) —
    /// aiming states render the shutter row instead, and `.capturing`
    /// renders nothing at the bottom (the crosshair pill + top banner
    /// carry the progress).
    private var showsResultPanel: Bool {
        switch viewModel.state {
        case .fitted, .rejected, .manualEntry, .accepted: return true
        default: return false
        }
    }

    /// Guidance line for the top banner — the stage strings unchanged
    /// (during the burst it mirrors the under-crosshair capture pill,
    /// Android parity).
    private var topBannerText: String? { statusText }

    @ViewBuilder
    private var bottomPanel: some View {
        MeasureStatusPanel {
            if let result = viewModel.result, viewModel.state != .manualEntry {
                resultPanel(result)
            }
            if viewModel.state == .manualEntry {
                manualEntryPanel
            }
            actionRow
        }
    }

    /// True when the ADJUST flank is offered. Hidden while ADJUST is
    /// already active (the Auto pill is the way back).
    private var showsAdjustRailButton: Bool {
        !viewModel.edgeAdjustActive
    }

    /// Open the bracket at the width the LAST tree was measured at, centred
    /// on the crosshair.
    ///
    /// WHICH SPACE THE SEED IS IN, and how I know. Getting this wrong is the
    /// bug that cost three field rounds, so it is written out rather than
    /// assumed.
    ///
    /// `DBHEstimator.bracketChordFit` reads its two handle arguments as
    /// fractions of the DEPTH MAP's walk axis: `leftPx = lo · extent`, with
    /// `extent` = `frame.width` for a row walk and `frame.height` for a col
    /// walk. `DBHScanViewModel` passes `edgeBracketLeftFraction` /
    /// `…RightFraction` in UNCONVERTED, and those two are written by the
    /// drag handler as `v.location.x / viewWidth` — a fraction of the VIEW.
    /// So the app identifies view-x fraction with depth walk-axis fraction
    /// 1:1, with no affine in between, and the same identity runs in reverse
    /// when the auto fit's `stripLeftFraction` is drawn at `size.width · f`.
    ///
    /// That identity is NOT to be "corrected" here. It was checked against
    /// 48 tape-measured stems: the span the app uses is 1.028× the span the
    /// trunk actually subtends (median). Two separate derivations argued for
    /// a 1.4–1.6× aspect-crop inflation and the field data falsified both;
    /// changing the mapping would put every iOS reading out by the size of
    /// the change.
    ///
    /// The consequence for the SEED is the whole point: what gets persisted
    /// is `(right − left) / 2` read straight off those same two published
    /// fractions (see `adjustHandle`'s `onEnded`), so putting it back is an
    /// exact round trip into the space the estimator consumes. There is no
    /// conversion on the way out, so there must be none on the way in.
    ///
    /// WHY NOT SEED FROM THE AUTO FIT — it did, once. The automatic edges
    /// are the thing the cruiser reached for ADJUST to get away from, so the
    /// bracket opened already wrong and asymmetric and the first drag was
    /// spent undoing it; worse, once the screen opens on the bracket there
    /// is no auto fit to borrow from anyway, and waiting for one is the Auto
    /// interlude this round removed. A plot is walked at roughly one
    /// standing distance, so the previous tree's width is the better guess,
    /// and when it is wrong it is wrong symmetrically — one drag fixes it.
    /// This is also what the Android sibling has always done.
    ///
    /// On the very first scan of a fresh install the width comes from
    /// `AppSettings.defaultBracketHalfWidth`, which is derived there from
    /// the field corpus rather than chosen for symmetry.
    private func seedBracketFromRememberedWidth() {
        setBracketHalfWidth(settings.dbhBracketHalfWidth)
    }

    /// The Adjust rail button: same seeding, plus the preference write —
    /// this one IS a choice, so it is remembered for the next tree.
    private func enterAdjustMode() {
        seedBracketFromRememberedWidth()
        viewModel.edgeAdjustActive = true
        settings.dbhEdgeAdjustDefault = true
    }

    /// Field report 15 — what to DO when depth won't resolve. Both remedies
    /// are the cruiser's own: a small movement, or a different standing
    /// distance. Byte-identical to the Android sibling.
    static let acquisitionStallHint =
        "No depth lock yet — move the phone gently side to side, or change your distance."

    /// Whether the banner should carry the acquisition hint right now.
    ///
    /// Suppressed while an ADVICE line is up: that line is a SPECIFIC reason
    /// (the bracket's "narrow it onto the trunk", a fit's own rejection)
    /// already on screen in the value strip, and two sentences giving
    /// different advice about the same failure is worse than one.
    ///
    /// NOT suppressed by the developer-mode diagnostic. That line is numbers,
    /// not a remedy, so it competes with nothing — and developer mode is worn
    /// in the stand here, not just on the bench: the tape-truth field, the
    /// research CSV row and the typed-truth capture below are all gated on
    /// `settings.developerMode`, so the accuracy-study cruiser runs with it
    /// on all day. Treating the diagnostic as advice therefore withheld field
    /// report 15's hint from exactly the operator the study depends on, while
    /// Android (which has no developer gate on its banner at all) showed it.
    ///
    /// The other half of the bargain lives in the view model: the ADJUST
    /// branch — the default path — stops writing its bracket line once the
    /// stall interval has elapsed, and the stall ticker takes down whatever
    /// the last frame left behind when depth delivery itself stops, precisely
    /// so this hint can come through.
    private var showsAcquisitionHint: Bool {
        viewModel.acquisitionStalled
            && (viewModel.previewStatusText == nil
                || viewModel.previewStatusIsDiagnostic)
    }

    private var statusText: String {
        switch viewModel.state {
        case .idle:         return "Starting camera…"
        case .aligning:
            // Only when there is genuinely no fit: the ADJUST bracket sits
            // in `.aligning` even while it is producing a diameter, and the
            // stall flag is what tells those two apart.
            if showsAcquisitionHint { return Self.acquisitionStallHint }
            return "Align the guide to the trunk's uphill side; hold steady."
        case .armed:
            // Centre-pixel depth can be stable while no fit comes out of it;
            // "tap + to capture" would then be an instruction the tap gate
            // refuses to honour.
            if showsAcquisitionHint { return Self.acquisitionStallHint }
            return "Hold steady, then tap + to capture."
        case .capturing:
            return "Capturing \(max(1, viewModel.captureSampleIndex))/\(viewModel.captureSampleTotal) — hold steady."
        case .fitted:       return "Scan complete. Accept, retake, or add a second view."
        case .accepted:     return "Saved."
        case .rejected:     return viewModel.result?.rejectionReason
                                 ?? "Scan rejected. Try again."
        case .manualEntry:  return "Enter diameter manually in cm."
        }
    }

    /// Developer-mode research CSV row — the diagnostic context the
    /// accuracy study needs (distance, aim pitch, n, σ, tier, true value).
    private func recordResearchRow(_ r: DBHResult) {
        guard settings.developerMode else { return }
        var f: [String: String] = [
            "measure_type": "dbh",
            "method": r.method.rawValue,
            "depth_source": settings.dbhMethodSource.rawValue,
            "measured_value": String(format: "%.2f", r.diameterCm),
            "unit": "cm",
            // Blank for a typed diameter — see the note at the accept site.
            "sigma": r.method == .manualVisual
                ? "" : String(format: "%.1f", r.sigmaRmm),
            "confidence_tier": r.confidence.rawValue,
            "n_points": "\(r.nInliers)",
            "arc_deg": String(format: "%.1f", r.arcCoverageDeg),
            "rmse_mm": String(format: "%.1f", r.rmseMm),
            "species": metaSpecies ?? "",
            "note": metaNote,
        ]
        // The tree this capture is ALREADY locked to, not a box the cruiser
        // had to retype. Same value the raw-capture bundle and the saved
        // reading carry, so the three join.
        f["tree_id"] = captureTreeNumber.map(String.init) ?? ""
        if let d = viewModel.distanceToStemCenterM {
            f["distance_m"] = String(format: "%.2f", d)
        }
        if let p = raycaster.cameraPitchDeg {
            f["pitch_deg"] = String(format: "%.1f", p)
        }
        if let frame = viewModel.session.latestDepthFrame {
            f["fx"] = String(format: "%.1f", frame.intrinsics.columns.0.x)
            f["depth_w"] = "\(frame.width)"
            f["depth_h"] = "\(frame.height)"
        }
        if let t = typedTruthForThisMeasurement {
            // `true_value` and `error` are in the row's `unit` (cm) — the same
            // scale as `measured_value`, so the error column stays
            // subtractable. `truth_unit` records what was actually typed.
            f["true_value"] = String(format: "%.2f", t)
            f["error"] = String(format: "%.2f", Double(r.diameterCm) - t)
            f["truth_unit"] = activeTruthUnit.rawValue
        }
        ResearchLog.shared.record(f)
        // NOTE: the field is deliberately NOT cleared here — `applyTypedTruth`
        // clears it only once the value is durably on the bundle.
    }

    /// The typed tape diameter in the metric base (cm) when it belongs to the
    /// measurement being accepted right now, else nil. Read by BOTH consumers
    /// of the field — the reading and the research row — so they can never
    /// disagree about which capture a number was typed for.
    ///
    /// ',' is a legitimate decimal separator on the cruiser's keypad.
    ///
    /// OWNER GATE: the field is deliberately kept across trees when a truth
    /// could not be attached (queued, no bundle, or a failed save), so the text
    /// on screen may belong to an EARLIER measurement. Both owner marks are nil
    /// only while the value was typed for THIS burst — anything else would
    /// stamp the previous tree's tape reading onto this one.
    private var typedTruthForThisMeasurement: Double? {
        guard settings.developerMode,
              truthOwnerBundleID == nil,
              truthQueuedForBundleID == nil
        else { return nil }
        return TruthInput.parsePositiveBase(researchTrueText,
                                            unit: activeTruthUnit)
    }

    /// Attach the typed ground truth to the bundle this Accept confirms.
    ///
    /// The input is cleared ONLY when the value is DURABLY in a manifest. A
    /// merely QUEUED value (parked in the bundle's sidecar for the in-flight
    /// writer) keeps the text and shows a pending line, because that writer
    /// can still fail and tear the sidecar down with the bundle. On any
    /// failure the text stays put and a warning is shown, so a hand-measured
    /// value is never silently thrown away.
    private func applyTypedTruth() {
        guard settings.developerMode else { return }
        truthSaveFailure = nil
        let raw = researchTrueText
        // The unit is read once here and used for both the conversion and the
        // record, so a toggle mid-save cannot split them.
        let unit = activeTruthUnit
        guard !TruthInput.normalized(raw).isEmpty else { return }
        // Always the metric base (cm) — the conversion lives in TruthInput.
        guard let t = TruthInput.parsePositiveBase(raw, unit: unit) else {
            truthSaveFailure = "Not a number — truth not saved"
            return
        }
        // Recording is OFF for this session: the value went into the research
        // CSV row written just above, so the field can clear. The dev block
        // already carries the "Raw capture OFF" notice.
        guard viewModel.rawCaptureEnabled else {
            researchTrueText = ""
            truthOwnerBundleID = nil
            truthQueuedForBundleID = nil
            return
        }
        // Recording is ON but this measurement produced no bundle — manual
        // entry, or a retake that cleared the id. Clearing silently here threw
        // a tape measurement away with no bundle to pair it to; warn like the
        // other paths and keep it, owned by nothing so it can't drift onto the
        // next tree's bundle.
        guard let id = viewModel.lastRecordedBundleID else {
            truthSaveFailure = "No raw capture for this measurement — truth kept on screen"
            truthOwnerBundleID = Self.noBundleOwner
            truthQueuedForBundleID = nil
            return
        }
        // Left over from an earlier capture that couldn't take it: attaching
        // it here would put one tree's tape measurement on another tree's
        // bundle. Make the cruiser re-enter (or clear) it instead.
        if let owner = truthOwnerBundleID, owner != id {
            truthSaveFailure = "Unsaved truth from an earlier capture — re-type it for this tree, or clear the field"
            return
        }
        // The capture itself failed — keep the typed value on screen rather
        // than attach it to a bundle that isn't there.
        if case .failed(let reason) = viewModel.lastCaptureOutcome {
            truthSaveFailure = "Capture NOT saved (\(reason)) — truth kept on screen"
            truthOwnerBundleID = id
            truthQueuedForBundleID = nil
            return
        }
        switch RawCaptureStore.applyTruth(id: id, value: t, unit: unit) {
        case .applied:
            // In the manifest — the only state that may clear the field.
            researchTrueText = ""
            truthOwnerBundleID = nil
            truthQueuedForBundleID = nil
        case .pending:
            // QUEUED against a writer that is still running. Clearing on a
            // queued result is how a hand measurement disappeared with a
            // bundle whose write later failed — keep it and say it's pending.
            truthOwnerBundleID = id
            truthQueuedForBundleID = id
        case .failed(let reason):
            truthSaveFailure = "Truth NOT saved — \(reason)"
            truthOwnerBundleID = id
            truthQueuedForBundleID = nil
        }
    }

    /// Settle a queued truth against the outcome of the capture that owns it.
    /// SAVED means the writer folded the sidecar into the manifest, so the
    /// value is finally durable and the field can clear. NOT SAVED means the
    /// bundle — and the queued value with it — is gone: the text stays on
    /// screen with the reason so the cruiser can re-type or re-measure.
    private func resolveQueuedTruth(_ outcome: RawCaptureOutcome?) {
        guard let queued = truthQueuedForBundleID, let outcome else { return }
        switch outcome {
        case .saved(let savedID, _) where savedID == queued:
            truthQueuedForBundleID = nil
            truthOwnerBundleID = nil
            truthSaveFailure = nil
            researchTrueText = ""
        case .failed(let reason):
            truthQueuedForBundleID = nil
            truthSaveFailure = "Capture NOT saved (\(reason)) — truth kept on screen"
        default:
            break
        }
    }

    /// Live warning under the truth field: unparseable text, or a value
    /// outside the plausible DBH window. The window is judged on the CONVERTED
    /// value, so an imperial entry is checked against the same limits.
    private var truthFieldWarning: String? {
        if let failure = truthSaveFailure { return failure }
        return TruthInput.fieldWarning(researchTrueText,
                                       quantity: .diameter,
                                       unit: activeTruthUnit)
    }

    @ViewBuilder
    private func resultPanel(_ r: DBHResult) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .firstTextBaseline) {
                // Monospaced number so the value aligns with the FIELD
                // LOG on the home screen — a cruiser reading the log
                // expects the same glyph widths everywhere.
                // Field fix: the value stands alone — no ±σ text and no
                // tier chip/hint on the scan screens. σ + tier are still
                // recorded with the entry (history / CSV / FieldLog
                // unchanged); a red fit keeps its rejection reason in
                // the status banner.
                Text(MeasurementFormatter.diameter(
                    cm: Double(r.diameterCm), in: settings.unitSystem))
                    .font(ForestixType.dataLarge)
                    .foregroundStyle(.white)
                // BASAL AREA, beside the diameter it comes from. It is one
                // line of arithmetic off a number already on screen, and it is
                // the number foresters actually ask a diameter for. Same
                // function every plot and stand total is summed from
                // (`TreeComputed.basalAreaText` → `InventoryEngine`), and the
                // same square unit rule, so this and the tree form cannot
                // disagree. Reads "—" until there is a usable diameter.
                Text(TreeComputed.basalAreaText(dbhCm: Double(r.diameterCm),
                                                in: settings.unitSystem))
                    .font(ForestixType.data)
                    .foregroundStyle(.white.opacity(0.8))
                    .accessibilityIdentifier("dbhScan.basalArea")
                Spacer()
            }
            // FIELD REPORT 7 — the details chip, identical to the height
            // scan's. The sheet, the four bound values and the write into
            // `ScanMetadata` were all already here; there was simply
            // nothing that opened it, so species / stem position / damage /
            // note could only be attached to a tree the cruiser also
            // measured the HEIGHT of. That made the richer record an
            // accident of which tool was used, not a decision.
            HStack {
                Spacer()
                Button {
                    presentingMetadata = true
                } label: {
                    HStack(spacing: 4) {
                        Image(systemName: "tag")
                            .font(.system(size: 11, weight: .semibold))
                        Text(metadataChipLabel)
                            .font(ForestixType.dataSmall)
                    }
                    .padding(.horizontal, 10).padding(.vertical, 5)
                    .overlay(Capsule().stroke(.white.opacity(0.4), lineWidth: 0.5))
                    .foregroundStyle(.white)
                }
                .accessibilityIdentifier("dbhScan.editMetadata")
            }
            .padding(.top, 2)
            if settings.developerMode {
                VStack(alignment: .leading, spacing: 4) {
                    HStack(spacing: 6) {
                        // Label and unit come from the SAME value, so the field
                        // can never say cm while the app reads inches.
                        Text(TruthInput.fieldLabel(.diameter, unit: activeTruthUnit))
                            .font(ForestixType.caption)
                            .foregroundStyle(.white.opacity(0.8))
                        // ',' is accepted and normalised to '.' on submit.
                        TextField("tape", text: $researchTrueText)
                            .keyboardType(.decimalPad)
                            .scanPanelTextField()
                            .frame(width: 90)
                            .accessibilityIdentifier("dbhScan.researchTrue")
                        TruthUnitToggle(
                            unit: activeTruthUnit,
                            onToggle: {
                                truthUnitChoice = (TruthInput.toggled(activeTruthUnit),
                                                   settings.unitSystem == .imperial)
                            },
                            identifier: "dbhScan.researchTrueUnit")
                    }
                    if let warning = truthFieldWarning {
                        TruthFieldWarning(text: warning)
                    } else if truthQueuedForBundleID != nil {
                        TruthFieldPending()
                    }
                    // Developer mode on, recording off ⇒ this session is
                    // producing NO corpus. Say so where the truth is typed.
                    if !settings.rawCaptureEnabled {
                        RawCaptureOffNotice()
                    }
                }
            }
        }
        .foregroundStyle(.white)
        .accessibilityIdentifier("dbhScan.resultPanel")
    }

    private var metadataChipLabel: String {
        // Shared with the height scan (FIELD REPORT 7) — one label rule for
        // one chip, on both screens. The diameter scan is the one that also
        // carries stem position, so it passes it.
        ScanMetadataChip.label(speciesCode: metaSpecies,
                               position: metaPosition,
                               damageCodes: metaDamage,
                               note: metaNote)
    }

    @ViewBuilder
    private var manualEntryPanel: some View {
        HStack {
            TextField(settings.unitSystem == .metric
                      ? "Diameter in cm"
                      : "Diameter in inches",
                      text: $viewModel.manualDbhCm)
                .scanPanelTextField()
                #if os(iOS)
                .keyboardType(.decimalPad)
                #endif
                .accessibilityIdentifier("dbhScan.manualInput")
            Button("Save") {
                // The field prompts in the ACTIVE unit system; the view model
                // converts inches → cm on submit (it used to store the typed
                // inches straight into diameterCm, a 2.54x corruption).
                viewModel.manualEntryUnits = settings.unitSystem
                viewModel.submitManualEntry()
            }
                .buttonStyle(.forestixProminent)
                .accessibilityIdentifier("dbhScan.manualSave")
        }
    }

    // MARK: - Measurement photo

    /// Take the measurement-moment JPEG and park it in `heldPhoto`.
    ///
    /// ORDER MATTERS. The chrome blackout is up before the settle sleep — the
    /// caller raises it in the same turn the burst lands, and this re-raise is
    /// idempotent — so the frame SwiftUI has committed by the time the
    /// renderer runs carries no panels and no buttons, the result panel
    /// included. What deliberately stays is the guide line, the fit chord, the
    /// crosshair and the AR cylinder: those are the measurement, and they are
    /// the whole evidentiary value of the photo.
    ///
    /// ONLY THE RENDER BLOCKS. The store hands the filename back as soon as
    /// the picture exists in memory and finishes the JPEG on its own queue,
    /// so the screen is unresponsive for the render alone (~10-20 ms) instead
    /// of for the render plus the encode plus the write (160-250 ms) — which
    /// is what the cruiser was reporting as a freeze, once per diameter and
    /// again per height.
    @MainActor
    private func captureHeldPhoto() async {
        // A fresh capture supersedes whatever was held — never leave the
        // previous burst's file behind on disk.
        discardHeldPhoto()
        hidingChromeForCapture = true
        try? await Task.sleep(for: .milliseconds(80))
        // THE SCREEN CAN BE LEFT INSIDE THAT SLEEP (the cruiser backs out,
        // the host dismisses the cover). This task is unstructured, so it
        // would still run: it would photograph whatever replaced this screen
        // and write a file that no reading and no `onDisappear` would ever
        // delete — an orphan in the photo store. Nothing is captured instead.
        guard !hasLeftScreen else {
            hidingChromeForCapture = false
            return
        }
        let shot = MeasurePhotoStore.captureWindow()
        // Held IMMEDIATELY, before the bytes are on disk: an Accept tapped
        // while the JPEG is still encoding must attach this frame, not
        // nothing. The store keeps writing under this name regardless of who
        // ends up owning it.
        heldPhoto = shot?.name
        hidingChromeForCapture = false
        guard let shot else { return }
        // The write can still fail (a full container, a refused write). If it
        // does, drop the name rather than leave a reading pointing at a file
        // that will never exist — the same "no photo" outcome the old
        // synchronous failure produced. Only if this screen is still holding
        // THIS frame: once Accept released it to a stored reading, or a
        // Retake superseded it, `heldPhoto` no longer names it and nothing
        // here may touch it. (A reading that took the name and then lost the
        // write shows "Photo unavailable" in the viewer — it never pretends
        // to have a picture.)
        if await shot.written.value == false, heldPhoto == shot.name {
            heldPhoto = nil
        }
    }

    /// Drop the held frame AND delete the file. Called on retake, on a
    /// superseding capture, and on the way off the screen — the store keeps
    /// one file per reading (`QuickMeasureHistory` deletes a reading's photo
    /// with it), so a frame no reading will ever claim has to go here.
    @MainActor
    private func discardHeldPhoto() {
        guard let name = heldPhoto else { return }
        heldPhoto = nil
        MeasurePhotoStore.delete(name)
    }

    // MARK: - Actions

    @ViewBuilder
    private var actionRow: some View {
        switch viewModel.state {
        case .fitted, .rejected:
            // One result row for every outcome: Retake / Details / Accept.
            // Details opens the metadata sheet; Accept stays disabled for
            // a red (or missing) fit, whose escape hatch is Retake or the
            // aiming-phase Type rail button. Secondary buttons are solid
            // white (sun-glare legibility) — the green Accept stays.
            HStack(spacing: 12) {
                // The held frame captions the measurement being thrown away —
                // keeping it would put the OLD aim on the NEW diameter.
                Button("Retake") { discardHeldPhoto(); viewModel.retake() }
                    .buttonStyle(.forestixARSecondary)
                    .frame(maxWidth: .infinity)
                Button("Details") { presentingMetadata = true }
                    .buttonStyle(.forestixARSecondary)
                    .frame(maxWidth: .infinity)
                    .accessibilityIdentifier("dbhScan.editMetadata")
                Button("Accept") { viewModel.accept() }
                    .buttonStyle(.forestixProminent)
                    .frame(maxWidth: .infinity)
                    .disabled(viewModel.result == nil
                              || viewModel.result?.confidence == .red)
            }
        case .manualEntry:
            HStack(spacing: 12) {
                Button("Cancel") { discardHeldPhoto(); viewModel.retake() }
                    .buttonStyle(.forestixARSecondary)
                    .frame(maxWidth: .infinity)
            }
        case .idle, .aligning, .armed, .capturing, .accepted:
            EmptyView()
        }
    }

    private func bannerView(
        _ text: String,
        tint: Color,
        identifier: String = "dbhScan.unsupportedBanner"
    ) -> some View {
        Text(text)
            .font(.callout).bold()
            .foregroundStyle(.white)
            .padding()
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(tint.opacity(0.8))
            .cornerRadius(8)
            .accessibilityIdentifier(identifier)
    }

    // Cross-platform screen width accessor — UIScreen is iOS-only.
    private func UIScreenWidth() -> CGFloat {
        #if canImport(UIKit)
        return UIScreen.main.bounds.width
        #else
        return 400
        #endif
    }
}
