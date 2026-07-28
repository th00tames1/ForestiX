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
        public init(speciesCode: String? = nil,
                    position: QuickMeasureEntry.StemPosition? = nil,
                    damageCodes: [String] = [],
                    note: String = "",
                    photoPath: String? = nil,
                    latitude: Double? = nil,
                    longitude: Double? = nil,
                    captureMode: String? = nil) {
            self.speciesCode = speciesCode
            self.position = position
            self.damageCodes = damageCodes
            self.note = note
            self.photoPath = photoPath
            self.latitude = latitude
            self.longitude = longitude
            self.captureMode = captureMode
        }
    }

    @State private var metaSpecies: String?
    @State private var metaPosition: QuickMeasureEntry.StemPosition? = .dbh
    @State private var metaDamage: [String] = []
    @State private var metaNote: String = ""
    @State private var presentingMetadata = false
    /// True while the Accept-time window snapshot is being taken — hides
    /// every piece of 2D chrome so the captured JPEG shows only the AR
    /// feed and the measurement overlays (crosshair, chord, markers).
    @State private var hidingChromeForCapture = false

    /// Bridge that turns SwiftUI screen taps into world-space rays / hits
    /// against the live ARView; also the source of the camera pitch logged
    /// with every research row.
    @StateObject private var raycaster = ARCenterRaycaster()

    /// Developer-mode research capture: tape-measured true diameter (cm)
    /// typed before Accept; logged with the scan context to the research
    /// CSV so error can be analysed against distance / aim angle.
    /// NEVER cleared until the value is durably on the bundle (or there is
    /// no bundle to attach it to) — see `applyTypedTruth`.
    @State private var researchTrueCm: String = ""
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
    /// auto-increments it after every save) and drives the "Tree 8"
    /// target pill. Quick-measure call sites pass nothing.
    private let tallyTreeNumber: Int?
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

    /// Non-nil when the host could NOT store the accepted diameter. The
    /// result stays on screen with this reason instead of the loop resetting
    /// (and, in cruise, chaining into Height) as if the tree had been written.
    @State private var dbhSaveFailure: String?

    /// Saved-tree toast state: number shown in "Tree 7 saved · Undo".
    @State private var tallyToastNumber: Int?
    /// Bumps on every toast show so the 3 s auto-hide task restarts.
    @State private var tallyToastGeneration = 0

    /// Signed metres from the camera to the active cruise plot BOUNDARY
    /// (|camera→centre| − radius; negative = inside). Polled from the
    /// plot's AR anchor at 0.2 s — the sampling screen's inside/outside
    /// machinery. nil while no anchor for THIS plot is alive.
    @State private var boundarySignedM: Double?

    public init(viewModel: @autoclosure @escaping () -> DBHScanViewModel,
                onResult: @escaping (DBHResult) -> Void = { _ in },
                onAccept: @escaping (DBHResult, ScanMetadata) -> Bool = { _, _ in true },
                cruisePlotInfo: PlotMiniMapInfo? = nil,
                tallyTreeNumber: Int? = nil,
                onUndoTally: (() -> Void)? = nil,
                projectID: String? = nil,
                quickTreeNumber: Int? = nil,
                onEditPlot: (() -> Void)? = nil) {
        _viewModel = StateObject(wrappedValue: viewModel())
        self.onResult = onResult
        self.onAccept = onAccept
        self.cruisePlotInfo = cruisePlotInfo
        self.tallyTreeNumber = tallyTreeNumber
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

    private var scanBody: some View {
        ZStack {
            // Live AR camera feed wired to the same ARSession the
            // DBHScanViewModel is consuming depth frames from. Snapshot
            // tests (macOS host) fall back to a black background via
            // ARCameraView's #else branch. The cylinder marker renders
            // the live single-frame fit as a translucent blue cylinder
            // at the trunk's world position — world-anchored, so it
            // stays locked to the tree as the phone moves.
            // Mesh overlay ON for DBH (field fix): the scene-reconstruction
            // wireframe is the "it's actually scanning" feedback cruisers
            // asked for. Height keeps it off. The subdued sampling-plot
            // overlay (if a plot is active) renders under the cylinder.
            ARCameraView(manager: viewModel.session,
                         debugMeshOverlay: true,
                         sceneMarkers: plotOverlayMarkers + cylinderMarkers,
                         raycaster: raycaster)
                .ignoresSafeArea()

            GeometryReader { geo in
                ZStack {
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
                }
                .frame(width: geo.size.width, height: geo.size.height)
            }
            .accessibilityElement(children: .ignore)

            // (The screen-wide tap catcher went with the AR caliper. Capture
            // is the fixed "+" button only, so a stray screen tap can never
            // fire one.)

            // ADJUST-mode edge handles. The handles are measurement chrome,
            // but they read as controls in a photo, so the Accept snapshot
            // hides them.
            if adjustOverlayVisible && !hidingChromeForCapture {
                GeometryReader { geo in
                    adjustHandleLayer(in: geo.size)
                        // The handles are fractions of THIS width, and the
                        // estimator needs the width to map them into depth
                        // pixels. Published on every layout so a rotation or
                        // a split-view resize cannot leave the mapping
                        // reading against a stale viewport.
                        .onAppear { viewModel.viewSize = geo.size }
                        .onChange(of: geo.size) { _, new in
                            viewModel.viewSize = new
                        }
                }
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
                if let target = tallyTreeNumber {
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
                    // The accepted diameter did NOT reach a tree row. Loud,
                    // and the value stays on screen so Accept can be retried.
                    if let failure = dbhSaveFailure {
                        bannerView(failure,
                                   tint: ForestixPalette.confidenceBad,
                                   identifier: "dbhScan.saveFailureBanner")
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
                    if let saved = tallyToastNumber {
                        tallyUndoToast(saved)
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
        // Tally toast auto-hide — 3 s per show; the generation id
        // restarts the clock when a new save replaces the toast.
        .task(id: tallyToastGeneration) {
            guard tallyToastNumber != nil else { return }
            try? await Task.sleep(nanoseconds: 3_000_000_000)
            if !Task.isCancelled {
                withAnimation(.easeOut(duration: 0.18)) {
                    tallyToastNumber = nil
                }
            }
        }
        .onAppear {
            // Phase 19 — pull the cruiser's chosen DBH method off
            // AppSettings every time the screen comes back into view
            // so flipping the picker in Settings takes effect on the
            // next return without leaving the scan screen.
            viewModel.dbhMeasurementMethod = settings.dbhMeasurementMethod
            // FIELD REPORT 4 — the screen opens on the edge bracket unless
            // the cruiser last chose Auto. Automatic edge-finding was the
            // default and the cruiser's verdict on it was that it jumped
            // left and right on a real stem badly enough to be unusable in
            // the stand; the bracket is one drag and holds still.
            if settings.dbhEdgeAdjustDefault {
                setBracketHalfWidth(settings.dbhBracketHalfWidth)
                viewModel.edgeAdjustActive = true
            }
            configureRawCapture()
            viewModel.onAppear()
        }
        .onDisappear { viewModel.onDisappear() }
        // CRUISE TALLY — the loop reuses this one screen and advances its
        // target without re-appearing, so the recording context has to follow
        // the number (belt-and-braces with the rebuild at burst start).
        .onChange(of: captureTreeNumber) { _, _ in
            configureRawCapture()
        }
        // Editing the truth field retires any "couldn't save" state: the text
        // is now the cruiser's current intent for the capture on screen.
        .onChange(of: researchTrueCm) { _, _ in
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
                // Auto-capture (map home): snapshot the AR view + the
                // measurement overlays as evidence of what was measured,
                // plus the latest GPS fix from the badge's running
                // location service. The 2D chrome is hidden first — a
                // short sleep lets SwiftUI commit the chrome-less frame —
                // so the JPEG doesn't show buttons/panels that read as
                // live controls in the photo viewer. The entry must get
                // its photoPath before it is appended, hence the await
                // before onAccept.
                Task { @MainActor in
                    hidingChromeForCapture = true
                    try? await Task.sleep(for: .milliseconds(80))
                    let photo = MeasurePhotoStore.captureWindow()
                    hidingChromeForCapture = false
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
                            : (viewModel.resultCapturedManually ? "manual" : "auto"))
                    // The host reports whether the reading actually reached
                    // storage. A dropped diameter used to be indistinguishable
                    // from a saved one — the loop reset for the next tree
                    // either way, and in cruise the chain then opened Height
                    // against whatever tree `chainTreeID` still pointed at.
                    let stored = onAccept(r, meta)
                    // Raw-capture (developer mode): attach the tape-measured
                    // truth to the just-recorded bundle. The bundle id is
                    // minted synchronously at burst finalize, so this works
                    // even when Accept beats the detached writer — and the
                    // field is not cleared until the value is durable.
                    recordResearchRow(r)
                    applyTypedTruth()
                    guard stored else {
                        dbhSaveFailure = tallyTreeNumber.map {
                            "Diameter NOT saved as Tree \($0) — the tree row couldn't be written. Tap Accept again."
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
                    // this task still carries the just-saved number.)
                    if let saved = tallyTreeNumber {
                        viewModel.retake()
                        metaSpecies = nil
                        metaPosition = .dbh
                        metaDamage = []
                        metaNote = ""
                        withAnimation(.easeOut(duration: 0.18)) {
                            tallyToastNumber = saved
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

    /// The bracket's half-width, read off the two published fractions.
    ///
    /// They stay the source of truth — the estimator and the chord overlay
    /// both consume them — but since FIELD REPORT 4 they are always
    /// symmetric about 0.5, so this one number describes the whole bracket.
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
                // SYMMETRIC — dragging either handle moves BOTH, mirrored
                // about the crosshair (FIELD REPORT 4).
                //
                // The two handles used to move independently, which made
                // fitting a trunk a two-handed job: drag the left edge on,
                // drag the right edge on, then find that the crosshair no
                // longer sat on the stem centre and the chord had picked up
                // whatever was behind the tree on one side. A stem is
                // symmetric about where it is aimed at, so one drag is
                // enough — the cruiser sets the WIDTH and the app keeps the
                // centre.
                .onChanged { v in
                    guard viewWidth > 1 else { return }
                    let frac = Double(v.location.x / viewWidth)
                    setBracketHalfWidth(abs(frac - 0.5))
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
        .accessibilityHint("Drag to set the trunk width. Both edges move together.")
        // VoiceOver cannot drag, so the width is also reachable in steps.
        .accessibilityAdjustableAction { direction in
            let step = 0.01
            switch direction {
            case .increment: setBracketHalfWidth(bracketHalfWidth + step)
            case .decrement: setBracketHalfWidth(bracketHalfWidth - step)
            @unknown default: break
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
                    large: true)
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

    /// Top-centre target pill — which tree number the tally loop is
    /// aiming at right now. Updates as the host auto-increments.
    /// (13 semibold on black 0.55 — Android parity.)
    private func tallyTargetPill(_ number: Int) -> some View {
        Text("Tree \(number)")
            .font(.system(size: 13, weight: .semibold))
            .foregroundStyle(.white)
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .background(Color.black.opacity(0.55), in: Capsule())
            .overlay(Capsule().stroke(.white.opacity(0.18), lineWidth: 0.5))
            .accessibilityIdentifier("dbhScan.tallyTarget")
    }

    /// "Tree 7 saved · Undo" — dark-glass toast above the bottom
    /// controls for 3 s after every tally Accept; Undo is the tappable
    /// bold segment. Metrics mirror Android's snackbar-equivalent pill.
    private func tallyUndoToast(_ number: Int) -> some View {
        HStack(spacing: 0) {
            Text("Tree \(number) saved · ")
                .font(.system(size: 14, weight: .medium))
                .foregroundStyle(.white)
            Button {
                onUndoTally?()
                tallyToastNumber = nil
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
        guard let plot = store.plot,
              store.linkedCruisePlotID == info.plotID,
              let centre = viewModel.session.worldAnchorPosition(id: plot.anchorID),
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

    /// Subdued sampling-plot context (ring + centre pole at ~0.5 alpha,
    /// pinned to the plot's ARAnchor). Shown only while a plot is active
    /// AND its anchor is still alive in the shared session — after an
    /// app restart or a session reset there is no anchor, so nothing is
    /// drawn. Non-interactive by construction (scene markers take no
    /// input) and listed before the measurement markers.
    private var plotOverlayMarkers: [ARSceneMarker] {
        guard let plot = activePlot.plot,
              viewModel.session.worldAnchorExists(id: plot.anchorID)
        else { return [] }
        return ActiveSamplingPlot.subduedOverlayMarkers(for: plot)
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
    /// FIELD REPORT 4 — this used to seed from the automatic fit's edges
    /// when it had any. That sounded helpful and was not: the automatic
    /// edges are the thing the cruiser reached for ADJUST to get away from,
    /// so the bracket opened already wrong and asymmetric, and the first
    /// drag was spent undoing it. A plot is walked at roughly one standing
    /// distance, so the previous tree's width is the better guess — and
    /// when it is wrong it is wrong symmetrically, which one drag fixes.
    ///
    /// On the very first scan of a fresh install the stored width is 0.25,
    /// i.e. the ±25 % this used to fall back to.
    private func enterAdjustMode() {
        setBracketHalfWidth(settings.dbhBracketHalfWidth)
        viewModel.edgeAdjustActive = true
        settings.dbhEdgeAdjustDefault = true
    }

    private var statusText: String {
        switch viewModel.state {
        case .idle:         return "Starting camera…"
        case .aligning:     return "Align the guide to the trunk's uphill side; hold steady."
        case .armed:        return "Hold steady, then tap + to capture."
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
        if !settings.researchTreeId.isEmpty {
            f["tree_id"] = settings.researchTreeId   // repeat auto-filled by record()
        }
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
        // ',' is a legitimate decimal separator on the cruiser's keypad.
        //
        // OWNER GATE: the field is deliberately kept across trees when a truth
        // could not be attached (queued, no bundle, or a failed save), so the
        // text on screen may belong to an EARLIER measurement. Both owner marks
        // are nil only while the value was typed for THIS burst — anything else
        // would stamp the previous tree's tape reading onto this row.
        let truthIsForThisMeasurement =
            truthOwnerBundleID == nil && truthQueuedForBundleID == nil
        if truthIsForThisMeasurement,
           let t = TruthInput.parsePositive(researchTrueCm) {
            f["true_value"] = String(format: "%.2f", t)
            f["error"] = String(format: "%.2f", Double(r.diameterCm) - t)
        }
        ResearchLog.shared.record(f)
        // NOTE: the field is deliberately NOT cleared here — `applyTypedTruth`
        // clears it only once the value is durably on the bundle.
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
        let raw = researchTrueCm
        guard !TruthInput.normalized(raw).isEmpty else { return }
        guard let t = TruthInput.parsePositive(raw) else {
            truthSaveFailure = "Not a number — truth not saved"
            return
        }
        // Recording is OFF for this session: the value went into the research
        // CSV row written just above, so the field can clear. The dev block
        // already carries the "Raw capture OFF" notice.
        guard viewModel.rawCaptureEnabled else {
            researchTrueCm = ""
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
        switch RawCaptureStore.applyTruth(id: id, value: t) {
        case .applied:
            // In the manifest — the only state that may clear the field.
            researchTrueCm = ""
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
            researchTrueCm = ""
        case .failed(let reason):
            truthQueuedForBundleID = nil
            truthSaveFailure = "Capture NOT saved (\(reason)) — truth kept on screen"
        default:
            break
        }
    }

    /// Live warning under the truth field: unparseable text, or a value
    /// outside the plausible DBH window.
    private var truthFieldWarning: String? {
        if let failure = truthSaveFailure { return failure }
        if TruthInput.isUnparseable(researchTrueCm) { return "Not a number" }
        guard let v = TruthInput.parsePositive(researchTrueCm) else { return nil }
        return TruthInput.dbhWarning(cm: v)
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
                        Text("Target")
                            .font(ForestixType.caption)
                            .foregroundStyle(.white.opacity(0.8))
                        TextField("T1", text: Binding(
                            get: { settings.researchTreeId },
                            set: { settings.researchTreeId = $0 }))
                            .scanPanelTextField()
                            .frame(width: 70)
                            .accessibilityIdentifier("dbhScan.researchTarget")
                        Text("True Ø (cm)")
                            .font(ForestixType.caption)
                            .foregroundStyle(.white.opacity(0.8))
                        // ',' is accepted and normalised to '.' on submit.
                        TextField("tape", text: $researchTrueCm)
                            .keyboardType(.decimalPad)
                            .scanPanelTextField()
                            .frame(width: 90)
                            .accessibilityIdentifier("dbhScan.researchTrue")
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
                Button("Retake") { viewModel.retake() }
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
                Button("Cancel") { viewModel.retake() }
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
