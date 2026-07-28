// MAP-FIRST HOME — the app's root screen, built to
// design/forestix-redesign-v2-maphome.html (screens ① map home,
// ② pin selected, ③ measure sheet, ⑤ photo detail).
//
// ONE MAP, TWO MODES. The screen owns a single BasemapMapView and
// renders one of two modes over it, flipped by the left side-circle
// and persisted as `tc.mapMode`:
//   • MEASURE (default) — quick measure, exactly as always: every
//     located quick-measure entry is a tree pin (D/H/C badges), the
//     green (+) opens the measure chooser, peek cards slide up.
//   • CRUISE — the map IS the cruise (the retired CruiseMapScreen's
//     content, hosted here from MapHomeScreen+Cruise.swift): plot
//     rings + cruise tree pins, the (+) turns cruiseAccent-blue and
//     morphs Start plot / Add tree, a tappable project strip rides
//     above the (+), planned-plot navigation draws the guide line.
// Camera and zoom are SHARED across the toggle — switching modes never
// snaps the map. Mode content stays separate: quick pins only in
// measure, cruise pins only in cruise.
//
// The map is the home because a cruiser's data IS spatial: the pulsing
// blue dot is you, and the single primary action is the big (+)
// capture button. Tapping a pin slides up a peek card — the map never
// disappears under the cruiser.
//
// Tiles come in layers, bottom to top: the built-in BASE (satellite —
// Esri World Imagery — or normal — OpenStreetMap standard — chosen in
// Map settings › Map type and persisted as `tc.mapType`), then the
// optional user XYZ overlay from Settings (contour / forest-service
// tiles), then the imported survey boundary, then this screen's own
// pins/rings/you-dot. The canvas colour + faint grid only show through
// where a base tile is neither cached nor fetchable. The bottom-left
// attribution label swaps with the base layer — it is a licence term,
// not decoration. MapSettingsSheet (Screens/MapSettingsSheet.swift)
// owns map type, boundary import/remove and offline download.
//
// Measurement flows are fullScreenCovers — Accept persists into QuickMeasureHistory
// with the ScanMetadata GPS fix + auto-photo, which is exactly what
// feeds the pins and peek cards here.

import SwiftUI
import Common
import Models
import Sensors
import Positioning
import Geo
import Basemap

// MARK: - Shared tile-cache factories

/// Built-in base layer — always available, no setup and no
/// acknowledgement gate (it ships with the app). WHICH base is decided
/// by Map settings › Map type (`tc.mapType`): satellite (Esri World
/// Imagery, the default) or normal (OpenStreetMap standard). Each
/// provider gets its own cache subdirectory, so switching type never
/// mixes or discards the other type's downloaded tiles.
/// nil only if the cache directory itself cannot be created.
@MainActor
func makeBaseTileCache(settings: AppSettings) -> TileCache? {
    try? TileCache(rootURL: TileCache.defaultBasemapRoot(),
                   provider: settings.mapType.provider)
}

/// User overlay layer from Settings, drawn ON TOP of the satellite
/// base. nil until the cruiser has pasted a template AND acknowledged
/// the provider's usage policy. (The layers-sheet on/off toggle is
/// applied at the call sites, not here, so the sheet can still show
/// status + cache stats for a toggled-off overlay.)
@MainActor
func makeOverlayTileCache(settings: AppSettings) -> TileCache? {
    guard settings.providerUsageAcknowledged,
          let template = settings.tileURLTemplate
    else { return nil }
    return try? TileCache(rootURL: TileCache.defaultBasemapRoot(),
                          provider: .fromUserTemplate(template))
}

// MARK: - Screen

public struct MapHomeScreen: View {

    // Shared with the cruise-mode extension (MapHomeScreen+Cruise.swift),
    // so internal rather than private.
    @EnvironmentObject var environment: AppEnvironment
    @EnvironmentObject var settings: AppSettings
    @EnvironmentObject var history: QuickMeasureHistory

    /// The app-shared LocationService (subscriber-refcounted): the GPS
    /// chip is live from the moment the screen appears — acquired in
    /// `startUp()`, released in `onDisappear`. Shared by both modes —
    /// cruise plot-centre stamping uses it too.
    @ObservedObject var location = LocationService.shared

    /// The one imported survey boundary — shared with Map settings, so
    /// an import lands on the map the moment the sheet closes.
    @ObservedObject var surveyBoundary = SurveyBoundaryModel.shared

    /// The app-scoped AR sampling ring. Observed here only so "Remove
    /// plot" can drop the ring standing in for a centre it just cleared
    /// (MapHomeScreen+Plot.swift).
    @ObservedObject var samplingPlot = ActiveSamplingPlot.shared

    /// Peavy Hall (OSU College of Forestry) fallback — used only when
    /// there is no fix and no located reading.
    private static let fallbackCamera = BasemapCamera(
        latitude: 44.56417, longitude: -123.28556, zoom: 16)

    /// SHARED camera — one map serves both modes, so flipping the mode
    /// toggle never snaps position or zoom.
    @State var camera = MapHomeScreen.fallbackCamera
    @State private var cameraInitialised = false
    /// True while the camera still sits on the hardcoded fallback — the
    /// first real GPS fix recenters exactly once.
    @State private var awaitingFirstFix = false
    @State private var visibleRegion: BasemapRegion?
    /// Shared selection — measure ids ("tree-…"/"entry-…") and cruise
    /// ids ("plot-…"/"pplot-…"/"ctree-…") are prefix-disjoint, and the
    /// toggle clears it, so a selection never leaks across modes.
    @State var selectedPinID: String?

    // Sheets / covers.
    @State private var presentingChooser = false
    @State private var presentingLayers = false
    /// First-run region picker — hosted here (the app's root screen) so
    /// it auto-presents right after the splash hands off.
    @State private var presentingRegionPicker = false
    /// Settings, opened from the gear in the top-right chrome.
    @State private var presentingSettings = false
    @State private var photoViewer: PhotoViewerContext?
    /// Quick-peek "Edit this tree" — the entry the compact edit sheet is
    /// editing (the pin's primary reading). nil = sheet closed.
    @State private var editingEntry: QuickMeasureEntry?
    /// Chooser row picked — launched from the sheet's onDismiss so the
    /// fullScreenCover doesn't fight the sheet dismissal animation.
    @State private var pendingChoice: MeasureChoice?

    // Measurement covers.
    @State private var presentingDBHScan = false
    @State private var presentingHeightScan = false
    @State private var presentingDistance = false
    @State private var presentingSampling = false
    /// Plot-overlay tap menu (Edit / Remove) and the Remove confirmation
    /// that always precedes an actual removal. Both name the plot, so a
    /// reload that changes the active plot can never redirect them.
    @State var plotMenuPlotID: UUID?
    @State var confirmingPlotRemovalID: UUID?
    @State private var pendingTreeNumber: Int?
    /// Full-measurement chain (the chooser's first row): ONE tree number,
    /// DBH first, then the Height cover auto-opens on DBH Accept. No
    /// continuation prompt in this mode — the chain IS the answer.
    @State private var fullMeasurementChain = false
    /// Set when a chained DBH is accepted; the DBH cover's onDismiss
    /// consumes it to present the Height cover after the dismissal has
    /// settled (same two-step pattern as `pendingChoice`).
    @State private var chainHeightPending = false
    /// Peek-card scoping — "Measure this tree" opens the chooser locked
    /// to that pin's tree number instead of the next free one. Cleared
    /// whenever the chooser dismisses so the plain (+) stays unscoped.
    @State private var chooserTreeOverride: Int?
    /// Far-GPS guard — set when the peek primary button is tapped while
    /// the cruiser stands > 30 m from the pin; the alert asks before the
    /// scoped chooser opens.
    @State private var farTreeWarning: FarTreeWarning?

    /// Whether the last fix has aged out — see `FixFreshness`.
    ///
    /// This exists so the plot's INSIDE / OUTSIDE verdict AND every
    /// position this screen draws can expire on their own. Both are
    /// recomputed from the fix's age on every render, which is exact, but
    /// nothing re-renders this screen once the fixes stop arriving — and
    /// "the fixes stopped arriving" is the entire case the age gate is
    /// for. Without a pulse the banner would simply freeze on the last
    /// verdict it drew and go on asserting "Inside" under canopy
    /// indefinitely, with the you-dot still painted inside the ring to
    /// agree with it.
    ///
    /// It is a TRIGGER, not a value: nothing reads it. It is written
    /// only when the verdict actually flips, so a map with live GPS
    /// redraws no more often than it already did.
    @State private var fixIsStale = false

    /// One-second pulse behind `fixIsStale`. Static so it is a single
    /// publisher rather than a new one per body evaluation (which would
    /// restart the countdown forever and never fire); `autoconnect`
    /// still starts it only when a map is on screen and stops it when
    /// the last one leaves.
    private static let fixAgeTicker =
        Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    // MARK: Cruise-mode state
    //
    // Stored here because SwiftUI state can't live in extensions; every
    // view/function that touches it is in MapHomeScreen+Cruise.swift
    // (the extracted CruiseMapScreen content). Internal, not private,
    // so the cross-file extension can reach it.

    // Cruise data snapshot (reloaded from the repositories).
    @State var projects: [Project] = []
    @State var plots: [Plot] = []
    @State var plannedPlots: [PlannedPlot] = []
    @State var treesByPlot: [UUID: [Tree]] = [:]
    @State var speciesByCode: [String: SpeciesConfig] = [:]

    // Cruise sheets / covers / pushes.
    @State var presentingProjectSheet = false
    @State var presentingPlotSetup = false
    /// The cruise plot the MAP's plot-overlay Edit is editing. Set just
    /// before the setup cover opens and cleared on its dismissal, so the
    /// cover knows to EDIT that plot rather than create a new one.
    @State var editingMapPlotID: UUID?
    @State var presentingCruiseDBH = false
    @State var presentingCruiseHeight = false
    @State var pushed: CruiseDestination?
    @State var pendingDestination: CruiseDestination?
    @State var closePlotCandidateID: UUID?

    // Crash-recovery resume prompt — open plots (closedAt == nil) whose last
    // activity is within the last 24 h, surfaced ONCE per launch on the home's
    // first appear. `didScanForCrashRecovery` enforces once-per-launch (and so
    // a dismiss/Discard never re-prompts); a non-empty list drives the dialog.
    @State var crashRecoveryCandidates: [ResumeCandidate] = []
    @State var didScanForCrashRecovery = false

    // Map-peek destructive-delete targets (confirmed via .alert) and the
    // cruise tree photo viewer opened from the tree-peek thumbnail.
    @State var deletePlotCandidateID: UUID?
    @State var deleteTreeCandidateID: UUID?
    @State var cruisePhotoContext: CruisePhotoContext?

    // Planned-plot navigation + centre recording + setup.
    @State var navTargetPlannedID: UUID?
    @State var recordingTarget: PlannedPlot?
    @State var presentingCruiseSetup = false
    @State var pendingCruiseSetup = false

    // One-button Export all, run inline in the project sheet.
    @State var isExportingAll = false
    @State var exportProgress: Double = 0
    @State var exportLabel = ""
    @State var exportShareURL: ExportShareURL?
    @State var exportErrorMessage: String?

    // Cruise tally-loop scope: the plot being tallied, the tree number
    // being aimed at (auto-increments per save), and the LAST saved /
    // scoped tree — the Undo target and the scoped-height target.
    @State var chainPlotID: UUID?
    @State var chainTreeNumber: Int = 1
    @State var chainTreeID: UUID?

    // FIELD REPORT F10 / F11 — two covers presented FROM INSIDE the cruise
    // diameter tally rather than from the map. Nesting matters: the tally
    // screen stays alive underneath, so Height (or plot setup) closes back
    // onto the loop instantly instead of flashing the map and rebuilding
    // the AR session.
    //   • chainingHeight    — Height for the tree whose diameter was just
    //                         accepted (`AppSettings.measureHeightAfterDiameter`).
    //   • chainingPlotSetup — plot setup re-opened from the mini-map.
    @State var chainingHeight = false
    @State var chainingPlotSetup = false

    // Heights sheet (plot peek → "Heights · N measured") + the scoped
    // Height request staged across its dismissal.
    @State var heightsSheetTarget: HeightsSheetTarget?
    @State var pendingScopedHeight: ScopedHeightRequest?

    // Project sheet "New project" one-time naming.
    @State var namingNewProject = false
    @State var newProjectName = ""

    public init() {}

    // MARK: Mode toggle

    /// `tc.mapMode` — the persisted mode the screen renders.
    private var isCruiseMode: Bool { settings.mapMode == "cruise" }

    /// Flip modes (the left side-circle). Camera stays put — the map is
    /// shared — and the selection clears so a peek never leaks across
    /// the mode boundary. Same easeOut 0.18 as the peek transitions.
    private func toggleMapMode() {
        let enteringCruise = !isCruiseMode
        withAnimation(.easeOut(duration: 0.18)) {
            selectedPinID = nil
            settings.mapMode = enteringCruise ? "cruise" : "measure"
        }
        if enteringCruise { reloadCruise() }
    }

    private enum MeasureChoice {
        case fullMeasurement, dbh, height, distance, sampling
    }

    /// Payload for the far-GPS confirmation alert.
    private struct FarTreeWarning: Identifiable {
        let treeNumber: Int
        let distanceM: Int
        var id: Int { treeNumber }
    }

    /// Peek "Measure this tree" beyond this distance from the pin asks
    /// for confirmation first — measuring a tree you're not standing at
    /// is usually a mis-tap on the wrong pin.
    private static let farTreeWarnDistanceM: Double = 30

    public var body: some View {
        NavigationStack {
            cruisePresentations(over: ZStack {
                map
                if isCruiseMode, navGuide != nil { distanceChipOverlay }
                attributionBadge
                VStack(spacing: ForestixSpace.xs) {
                    topChrome
                    // The plot's own status line: INSIDE / OUTSIDE (or
                    // "no position"), the radius, and the live distance
                    // from the centre — all in the cruiser's units.
                    // Absent entirely when no plot is drawn.
                    if isCruiseMode, let plot = cruisePlotOverlay {
                        cruisePlotBanner(plot)
                    }
                    Spacer()
                }
                VStack {
                    Spacer()
                    if isCruiseMode {
                        // Cruise peeks — plot ring, planned ring, tree pin.
                        if let plot = selectedPlot {
                            plotPeekCard(for: plot)
                        } else if let planned = selectedPlannedPlot {
                            plannedPeekCard(for: planned)
                        } else if let tree = selectedTree {
                            treePeekCard(for: tree)
                        } else {
                            actionCluster
                        }
                    } else if let pin = selectedPin {
                        peekCard(for: pin)
                    } else {
                        actionCluster
                    }
                }
                // The drawn plot's Edit / Remove menu (M2) — a dialog
                // over the map, above the peeks, so the plot stays in
                // view behind it.
                if let id = plotMenuPlotID,
                   let plot = plots.first(where: { $0.id == id }) {
                    plotOverlayMenu(for: plot)
                }
            })
            .background(ForestixPalette.canvas.ignoresSafeArea())
            .onAppear {
                startUp()
                if isCruiseMode { reloadCruise() }
            }
            .onDisappear { location.release() }
            .onChange(of: location.latestSnapshot) { _, snap in
                recenterOnFirstFix(snap)
                if isCruiseMode { checkNavArrival(snap) }
            }
            // Lets the plot's inside/outside verdict AND the drawn
            // you-dot time out on their own when the fixes stop — see
            // `fixIsStale`. Both read the same `FixFreshness` rule, so
            // one pulse retires both together.
            .onReceive(Self.fixAgeTicker) { now in
                let stale = FixFreshness.usable(location.latestSnapshot,
                                                asOf: now) == nil
                if stale != fixIsStale { fixIsStale = stale }
            }
            // A boundary imported from Map settings frames itself — a
            // boundary you cannot see is indistinguishable from one that
            // failed to import. Only fires on a NEW import: the stored
            // one is already loaded before this screen appears.
            .onChange(of: surveyBoundary.boundary?.importedAt) { _, _ in
                frameImportedBoundary()
            }
            .task {
                // First-launch UX: auto-present the region picker once,
                // after the splash has settled — but never re-prompt a
                // cruiser who already has a region. A returning cruiser
                // (region already seen) instead gets the crash-recovery
                // scan, so the two never present over each other.
                if !settings.regionPickerSeen {
                    presentingRegionPicker = true
                } else {
                    scanForCrashRecovery()
                }
            }
            #if os(iOS)
            .navigationBarHidden(true)
            .fullScreenCover(isPresented: $presentingDBHScan,
                             onDismiss: continueChainAfterDBH) { dbhCover }
            .fullScreenCover(isPresented: $presentingHeightScan,
                             onDismiss: { fullMeasurementChain = false }) { heightCover }
            .fullScreenCover(isPresented: $presentingDistance) {
                NavigationStack {
                    DistanceMeasureScreen()
                        .environmentObject(history)
                        .environmentObject(settings)
                }
            }
            .fullScreenCover(isPresented: $presentingSampling) {
                NavigationStack {
                    SamplingPlotScreen()
                        .environmentObject(history)
                        .environmentObject(settings)
                }
            }
            .fullScreenCover(item: $photoViewer) { context in
                MeasurePhotoDetailView(context: context,
                                       unitSystem: settings.unitSystem)
            }
            #endif
            .sheet(isPresented: $presentingChooser,
                   onDismiss: launchPendingChoice) { measureChooser }
            // Quick-peek "Edit this tree" — the compact per-entry editor
            // (value / species / note + confirmed delete).
            .sheet(item: $editingEntry) { entry in
                QuickEntryEditSheet(entry: entry, history: history)
            }
            // Far-GPS guard — confirm before measuring a tree whose pin
            // is > 30 m from the current fix (usually a wrong-pin tap).
            .alert(farTreeWarning.map {
                       "Tree \($0.treeNumber) is \($0.distanceM) m away"
                   } ?? "",
                   isPresented: Binding(
                       get: { farTreeWarning != nil },
                       set: { if !$0 { farTreeWarning = nil } }),
                   presenting: farTreeWarning) { warning in
                Button("Measure anyway") {
                    openChooser(scopedTo: warning.treeNumber)
                }
                .keyboardShortcut(.defaultAction)
                Button("Cancel", role: .cancel) {}
            } message: { warning in
                Text("Your current GPS position is about \(warning.distanceM) m from this tree's pin. Measure it anyway?")
            }
            .sheet(isPresented: $presentingLayers) {
                MapSettingsSheet(visibleRegion: visibleRegion)
                    .environmentObject(settings)
            }
            // REMOVE, step two. The one destructive step on the plot
            // overlay, and it is spelled out: what goes is the centre,
            // and everything measured in the plot stays.
            .alert(plotMenuRemovalPlot.map(plotRemovalTitle) ?? "",
                   isPresented: Binding(
                       get: { confirmingPlotRemovalID != nil },
                       set: { if !$0 { confirmingPlotRemovalID = nil } }),
                   presenting: plotMenuRemovalPlot) { plot in
                Button("Remove", role: .destructive) {
                    removePlotFromMap(plot)
                }
                .accessibilityIdentifier("mapHome.plotRemove.confirm")
                Button("Cancel", role: .cancel) { confirmingPlotRemovalID = nil }
            } message: { plot in
                Text(plotRemovalMessage(plot))
            }
            // ANY dismissal — Skip, row pick, or a plain swipe-down —
            // stamps regionPickerSeen so the picker never nags again.
            .sheet(isPresented: $presentingRegionPicker,
                   onDismiss: { settings.regionPickerSeen = true }) {
                LocaleSetupSheet()
                    .environmentObject(settings)
            }
            .sheet(isPresented: $presentingSettings) {
                NavigationStack {
                    SettingsScreen()
                        .toolbar {
                            ToolbarItem(placement: .cancellationAction) {
                                Button("Close") { presentingSettings = false }
                            }
                        }
                }
                .environmentObject(environment)
                .environmentObject(settings)
            }
            // CRASH RECOVERY — resume an in-progress plot from a recent
            // session. The summary (plot #, tree count, last-edited) is folded
            // into each Resume row, so tapping shows the "View" detail inline
            // before committing. Discard just dismisses — nothing is deleted
            // and the plot stays reachable from the project dashboard.
            .confirmationDialog(
                crashRecoveryTitle,
                isPresented: Binding(
                    get: { !crashRecoveryCandidates.isEmpty },
                    set: { if !$0 { crashRecoveryCandidates = [] } }),
                titleVisibility: .visible,
                presenting: crashRecoveryCandidates
            ) { candidates in
                ForEach(candidates.prefix(3)) { candidate in
                    Button("Resume \(candidate.summary)") {
                        resumeCrashRecovery(candidate)
                    }
                }
                Button("Discard", role: .cancel) {
                    crashRecoveryCandidates = []
                }
            } message: { candidates in
                Text(candidates.count == 1
                     ? "A plot from an earlier session is still open. Resume it, or dismiss this reminder. Nothing is deleted."
                     : "\(candidates.count) plots from earlier sessions are still open. Resume one, or dismiss this reminder. Nothing is deleted.")
            }
        }
    }

    // MARK: Map + pins

    /// Move the camera onto a freshly imported boundary. The zoom is
    /// derived from the boundary's lat/lon span against a typical phone
    /// viewport — precise enough to put the whole stand on screen, and
    /// clamped to the renderer's fetchable range.
    private func frameImportedBoundary() {
        guard let box = surveyBoundary.boundary?.boundingBox else { return }
        let lonSpan = max(box.maxLon - box.minLon, 1e-6)
        let latSpan = max(box.maxLat - box.minLat, 1e-6)
        let zoomX = log2(360.0 / lonSpan * (360.0 / 256.0))
        let zoomY = log2(180.0 / latSpan * (640.0 / 256.0))
        let zoom = min(max(min(zoomX, zoomY) - 0.4, 3), BasemapMapView.maxTileZoom)
        withAnimation(.easeOut(duration: 0.35)) {
            camera = BasemapCamera(latitude: (box.minLat + box.maxLat) / 2,
                                   longitude: (box.minLon + box.maxLon) / 2,
                                   zoom: zoom)
        }
    }

    private var baseTileCache: TileCache? {
        makeBaseTileCache(settings: settings)
    }

    private var overlayTileCache: TileCache? {
        settings.overlayEnabled ? makeOverlayTileCache(settings: settings) : nil
    }

    /// ONE map for both modes — the pins swap with the mode (quick pins
    /// in measure, plot rings + cruise trees in cruise) but the camera,
    /// tiles and gestures are the same view all along.
    private var map: some View {
        BasemapMapView(
            camera: $camera,
            baseTileCache: baseTileCache,
            overlayTileCache: overlayTileCache,
            // Imported survey boundary — above both tile layers, below
            // every pin/ring/you-dot (those are views over the Canvas,
            // so the boundary can never intercept a tap meant for one).
            boundary: surveyBoundary.overlay,
            // THE SAMPLING PLOT — one layer above the imported boundary,
            // still under every pin. Cruise-only, like every other piece
            // of cruise content on this shared map.
            plotOverlay: isCruiseMode ? cruisePlotOverlay?.overlay : nil,
            markers: isCruiseMode ? cruiseMarkers : markers,
            selectedMarkerID: selectedPinID,
            // THE YOU-DOT GOES THROUGH THE SAME GATE AS THE VERDICT.
            // It used to be handed the raw `latestSnapshot`, which is
            // written on ingest and never cleared — so while the banner
            // said "No position" and the plot circle went grey, the map
            // still painted a confident blue dot from the very fix those
            // two had just refused, quite often sitting inside a plot the
            // app had declined to vouch for. A rejected fix now means NO
            // dot at all: the honest picture of "I don't know where you
            // are" is an empty one, and a greyed last-known dot would
            // still be a position drawn on ground the app cannot stand
            // behind. `fixAgeTicker` retires it on the same pulse that
            // retires the verdict, so the two never disagree even when
            // the fixes simply stop arriving.
            youLocation: FixFreshness.usable(location.latestSnapshot).map {
                CoordinateConversions.LatLon(latitude: $0.latitude,
                                             longitude: $0.longitude)
            },
            guideLine: isCruiseMode ? navGuide : nil,
            style: BasemapStyle(
                canvas: ForestixPalette.canvas,
                grid: ForestixPalette.divider.opacity(0.55),
                pinStroke: ForestixPalette.surface,
                pinInk: ForestixPalette.primaryInk,
                badgeBackground: ForestixPalette.surface,
                badgeBorder: ForestixPalette.divider,
                badgeText: ForestixPalette.textSecondary,
                selectionHalo: ForestixPalette.primaryMuted),
            onMarkerTap: { id in
                withAnimation(.easeOut(duration: 0.18)) {
                    selectedPinID = (selectedPinID == id) ? nil : id
                }
            },
            onMapTap: {
                withAnimation(.easeOut(duration: 0.18)) { selectedPinID = nil }
            },
            // A tap ON the drawn plot's boundary raises its small Edit /
            // Remove menu (M2) — the plot's own pin still owns the centre.
            onPlotTap: { id in openPlotMenu(id) },
            onCameraChange: { _, region in
                visibleRegion = region
            })
        .ignoresSafeArea()
        .accessibilityIdentifier("mapHome.map")
    }

    /// One pin per tree number with at least one located reading —
    /// anchored at the newest located entry but carrying ALL of the
    /// tree's entries (including no-fix readings); tree-less located
    /// entries stand alone, titled by their kind letter.
    private struct MapPin: Identifiable {
        let id: String
        let treeNumber: Int?
        let title: String
        /// Newest-first, mirroring `history.entries` order.
        let entries: [QuickMeasureEntry]
        let latitude: Double
        let longitude: Double
    }

    private var pins: [MapPin] {
        var byTree: [Int: [QuickMeasureEntry]] = [:]
        var singles: [QuickMeasureEntry] = []
        for entry in history.entries {
            guard entry.latitude != nil, entry.longitude != nil else { continue }
            if let n = entry.treeNumber {
                byTree[n, default: []].append(entry)
            } else {
                singles.append(entry)
            }
        }
        var out: [MapPin] = byTree.keys.sorted().map { number in
            // Anchor at the newest LOCATED reading, but the pin
            // represents the WHOLE tree: badges, warn tint and the peek
            // card include readings captured without a fix.
            let anchor = byTree[number]![0]
            let all = history.entries.filter { $0.treeNumber == number }
            return MapPin(id: "tree-\(number)",
                          treeNumber: number,
                          title: "T\(number)",
                          entries: all,
                          latitude: anchor.latitude ?? 0,
                          longitude: anchor.longitude ?? 0)
        }
        out += singles.map { entry in
            MapPin(id: "entry-\(entry.id.uuidString)",
                   treeNumber: nil,
                   title: kindLetter(entry.kind),
                   entries: [entry],
                   latitude: entry.latitude ?? 0,
                   longitude: entry.longitude ?? 0)
        }
        return out
    }

    private var markers: [BasemapMarker] {
        pins.map { pin in
            let needsAttention = pin.entries.contains {
                $0.confidenceRaw == "red" || $0.confidenceRaw == "yellow"
            }
            return BasemapMarker(
                id: pin.id,
                latitude: pin.latitude,
                longitude: pin.longitude,
                title: pin.title,
                tint: needsAttention ? ForestixPalette.confidenceWarn
                                     : ForestixPalette.primary,
                badge: pin.treeNumber == nil ? nil : badgeLetters(pin.entries))
        }
    }

    private var selectedPin: MapPin? {
        guard let id = selectedPinID else { return nil }
        return pins.first { $0.id == id }
    }

    /// D/H/C — which of the three tree metrics this tree already has.
    private func badgeLetters(_ entries: [QuickMeasureEntry]) -> String? {
        var letters = ""
        if entries.contains(where: { $0.kind == .dbh }) { letters += "D" }
        if entries.contains(where: { $0.kind == .height }) { letters += "H" }
        if entries.contains(where: { $0.kind == .crown }) { letters += "C" }
        return letters.isEmpty ? nil : letters
    }

    /// Single-entry pin letter — distance is "L" and plot "P" so neither
    /// collides with the DBH badge's "D".
    private func kindLetter(_ kind: QuickMeasureEntry.Kind) -> String {
        switch kind {
        case .dbh:          return "D"
        case .height:       return "H"
        case .crown:        return "C"
        case .distance:     return "L"
        case .samplingPlot: return "P"
        }
    }

    // MARK: Lifecycle

    private func startUp() {
        location.requestAuthorization()
        location.acquire()
        guard !cameraInitialised else { return }
        cameraInitialised = true
        if let fix = LocationService.lastGlobalFix ?? location.latestSnapshot {
            camera = BasemapCamera(latitude: fix.latitude,
                                   longitude: fix.longitude, zoom: 16)
        } else if let entry = history.entries.first(where: {
            $0.latitude != nil && $0.longitude != nil
        }), let lat = entry.latitude, let lon = entry.longitude {
            camera = BasemapCamera(latitude: lat, longitude: lon, zoom: 16)
        } else {
            camera = Self.fallbackCamera
            awaitingFirstFix = true
        }
    }

    /// Fresh install in the field: nothing to centre on at launch, so
    /// the first GPS fix pulls the camera home — once.
    private func recenterOnFirstFix(_ snap: CLLocationSnapshot?) {
        guard awaitingFirstFix, let snap else { return }
        awaitingFirstFix = false
        withAnimation(.easeOut(duration: 0.3)) {
            camera = BasemapCamera(latitude: snap.latitude,
                                   longitude: snap.longitude, zoom: 16)
        }
    }

    // MARK: Top chrome

    private var topChrome: some View {
        HStack(spacing: ForestixSpace.xs) {
            // The GPS status line takes the leading width and TRUNCATES
            // (never wraps) when the fix string runs long — the fixed
            // round buttons on the right always stay on screen. Shared by
            // both modes; cruise carries no separate project chip here
            // anymore (the project lives on the bottom cluster now).
            //
            // THE SAME CHIP the two measurement screens show. It used to be
            // inlined here, which is how the scan screens ended up with a
            // different GPS widget saying a different thing.
            GPSFixChip()
            Spacer(minLength: ForestixSpace.xs)
            // My-location — jump the camera back to the newest fix (this
            // screen's live service, else the last fix any screen saved).
            // No fix yet: the button dims and the tap is a no-op.
            let locateFix = location.latestSnapshot ?? LocationService.lastGlobalFix
            Button {
                guard let fix = locateFix else { return }
                withAnimation(.easeOut(duration: 0.3)) {
                    camera = BasemapCamera(latitude: fix.latitude,
                                           longitude: fix.longitude,
                                           zoom: max(camera.zoom, 16))
                }
            } label: {
                chromeButtonGlyph("location.fill")
            }
            .buttonStyle(MapPressableStyle())
            .opacity(locateFix == nil ? 0.45 : 1)
            .accessibilityLabel("My location")
            .accessibilityIdentifier("mapHome.locate")

            Button {
                presentingLayers = true
            } label: {
                chromeButtonGlyph("square.stack.3d.up")
            }
            .buttonStyle(MapPressableStyle())
            .accessibilityLabel("Map settings")
            .accessibilityIdentifier("mapHome.layers")

            // Settings — rightmost of the top-right group, both modes.
            // Reuses the existing SettingsScreen sheet.
            Button {
                presentingSettings = true
            } label: {
                chromeButtonGlyph("gearshape")
            }
            .buttonStyle(MapPressableStyle())
            .accessibilityLabel("Settings")
            .accessibilityIdentifier("mapHome.settings")
        }
        .padding(.horizontal, 14)
        .padding(.top, ForestixSpace.xs)
    }

    /// Shared 44 pt round chrome button glyph — surfaceRaised circle,
    /// divider ring, 18 pt semibold textSecondary icon (locate / layers /
    /// settings all render identically).
    private func chromeButtonGlyph(_ icon: String) -> some View {
        Image(systemName: icon)
            .font(.system(size: 18, weight: .semibold))
            .foregroundStyle(ForestixPalette.textSecondary)
            .frame(width: 44, height: 44)
            .background(Circle().fill(ForestixPalette.surfaceRaised))
            .overlay(Circle().stroke(ForestixPalette.divider, lineWidth: 1))
    }

    /// Base-layer attribution — a licence obligation, not decoration, so
    /// it is always on AND it SWAPS with the selected map type: Esri ·
    /// Maxar · Earthstar Geographics for satellite, "© OpenStreetMap
    /// contributors" (ODbL) for normal. Fixed colours on purpose: it sits
    /// on map tiles, not on an app surface (same rationale as the photo
    /// viewer's dark chrome).
    private var attributionBadge: some View {
        Text(settings.mapType.attribution)
            .font(.system(size: 9, design: .monospaced))
            .foregroundStyle(Color.white.opacity(0.78))
            .padding(.horizontal, 6)
            .padding(.vertical, 2.5)
            .background(
                RoundedRectangle(cornerRadius: 4, style: .continuous)
                    .fill(Color.black.opacity(0.28)))
            .padding(.leading, 10)
            .padding(.bottom, 2)
            .frame(maxWidth: .infinity, maxHeight: .infinity,
                   alignment: .bottomLeading)
            .allowsHitTesting(false)
            .accessibilityHidden(true)
    }

    // MARK: Bottom action cluster (mock ①)

    private var actionCluster: some View {
        HStack(alignment: .bottom, spacing: 26) {
            // Mode toggle (left circle, where CRUISE used to push) —
            // shows the CURRENT mode; tapping flips it. Everything else
            // in the cluster keeps its exact position: the caption slots
            // are fixed-width and the cruise-only tally pill is a
            // non-layout overlay, so flipping the mode can never change
            // the cluster's measured size — the (+), side circle and LOG
            // circle sit on identical pixels in both modes.
            modeToggleCircle

            VStack(spacing: 6) {
                Button {
                    if isCruiseMode {
                        cruisePrimaryAction()
                    } else {
                        presentingChooser = true
                    }
                } label: {
                    ZStack {
                        Circle().fill(isCruiseMode ? ForestixPalette.cruiseAccent
                                                   : ForestixPalette.primary)
                        Image(systemName: "plus")
                            .font(.system(size: 30, weight: .semibold))
                            .foregroundStyle(isCruiseMode ? ForestixPalette.cruiseAccentInk
                                                          : ForestixPalette.primaryInk)
                    }
                    .frame(width: 74, height: 74)
                    .overlay(Circle().stroke(ForestixPalette.surface, lineWidth: 4))
                    // Cruise scoped state (active plot): accent halo like
                    // the mock's `.capture.scoped` — status colour, so it
                    // matches the active plot ring.
                    .overlay {
                        if isCruiseMode && activePlot != nil {
                            Circle()
                                .inset(by: -5)
                                .stroke(ForestixPalette.accent.opacity(0.35),
                                        lineWidth: 4)
                            Circle()
                                .inset(by: -1)
                                .stroke(ForestixPalette.accent, lineWidth: 1.5)
                        }
                    }
                    .shadow(color: Color.black.opacity(0.28), radius: 10, y: 6)
                }
                .buttonStyle(MapPressableStyle())
                .accessibilityLabel(isCruiseMode ? cruisePrimaryLabel
                                                 : "New measurement")
                .accessibilityIdentifier(isCruiseMode ? "cruiseMap.primary"
                                                      : "mapHome.measure")
                clusterCaption(isCruiseMode ? cruisePrimaryLabel : "Measure",
                               slots: ["Measure"])
            }
            // CRUISE PROJECT STRIP — a tappable dark pill floating ABOVE
            // the (+) as a layout-NEUTRAL overlay so it never participates
            // in the cluster's measurement (pixel-invariant geometry is
            // preserved). It opens the project sheet and folds in the live
            // tree count, replacing measure's standalone tally pill.
            .overlay(alignment: .top) {
                if isCruiseMode {
                    projectStrip
                        .fixedSize()
                        // Pill bottom 12 pt above the circle — the gap the
                        // old in-column tally pill had (6 pt padding + 6 pt
                        // VStack spacing).
                        .alignmentGuide(.top) { $0[.bottom] + 12 }
                }
            }

            rightClusterCircle
        }
        .padding(.bottom, ForestixSpace.sm)
        .transition(.move(edge: .bottom).combined(with: .opacity))
    }

    /// The left side-circle — same size and position the CRUISE push
    /// circle had, now the mode toggle. Shows the CURRENT mode: tree
    /// glyph + MEASURE, target-reticle glyph + CRUISE (matches Android's
    /// Adjust icon; tinted with the cruise accent while cruising).
    private var modeToggleCircle: some View {
        VStack(spacing: 5) {
            Button {
                toggleMapMode()
            } label: {
                ZStack {
                    Circle().fill(ForestixPalette.surface)
                    Image(systemName: isCruiseMode ? "target" : "tree")
                        .font(.system(size: 22, weight: .medium))
                        .foregroundStyle(isCruiseMode ? ForestixPalette.cruiseAccent
                                                      : ForestixPalette.textPrimary)
                }
                .frame(width: 54, height: 54)
                .overlay(Circle().stroke(ForestixPalette.divider, lineWidth: 1))
                .shadow(color: Color.black.opacity(0.18), radius: 6, y: 3)
            }
            .buttonStyle(MapPressableStyle())
            .accessibilityLabel(isCruiseMode ? "Switch to measure mode"
                                             : "Switch to cruise mode")
            .accessibilityIdentifier("mapHome.modeToggle")
            clusterCaption(isCruiseMode ? "Cruise" : "Measure",
                           slots: ["Measure", "Cruise"])
        }
    }

    /// The RIGHT cluster circle — mode-dependent, same size/position the
    /// "Log" circle always had. MEASURE: "Log" pushes FieldLog
    /// (list.bullet). CRUISE: "Project" opens the project sheet (folder).
    /// The caption slot carries BOTH captions so its width is identical
    /// across the mode flip — only the glyph, caption and action differ,
    /// keeping the cluster pixel-invariant.
    private var rightClusterCircle: some View {
        VStack(spacing: 5) {
            if isCruiseMode {
                Button {
                    presentingProjectSheet = true
                } label: {
                    clusterCircleGlyph("folder")
                }
                .buttonStyle(MapPressableStyle())
                .accessibilityLabel("Project")
                .accessibilityIdentifier("cruiseMap.projectCircle")
            } else {
                NavigationLink {
                    FieldLogScreen()
                } label: {
                    clusterCircleGlyph("list.bullet")
                }
                .buttonStyle(MapPressableStyle())
                .accessibilityLabel("Log")
                .accessibilityIdentifier("mapHome.log")
            }
            clusterCaption(isCruiseMode ? "Project" : "Log",
                           slots: ["Log", "Project"])
        }
    }

    /// The 54 pt surface circle shared by the right cluster circle —
    /// icon centred, divider ring, soft drop shadow.
    private func clusterCircleGlyph(_ icon: String) -> some View {
        ZStack {
            Circle().fill(ForestixPalette.surface)
            Image(systemName: icon)
                .font(.system(size: 22, weight: .medium))
                .foregroundStyle(ForestixPalette.textPrimary)
        }
        .frame(width: 54, height: 54)
        .overlay(Circle().stroke(ForestixPalette.divider, lineWidth: 1))
        .shadow(color: Color.black.opacity(0.18), radius: 6, y: 3)
    }

    /// Fixed-width caption slot under a cluster circle — the live
    /// caption draws centred over hidden sizing copies of every caption
    /// the slot can show, so swapping captions on the mode toggle never
    /// changes the column's measured width (mode-toggle layout
    /// invariance). Captions longer than the slot — the cruise (+)
    /// captions, "Add tree · Plot N" — overflow it symmetrically
    /// instead of pushing the circles apart.
    private func clusterCaption(_ active: String, slots: [String]) -> some View {
        ZStack {
            ForEach(slots, id: \.self) { clusterLabel($0).hidden() }
            clusterLabel(active)
                .fixedSize()
                .frame(width: 0)
        }
    }

    /// Dark-glass pill so the label stays legible over satellite
    /// imagery. Fixed colours on purpose — the backdrop is photography
    /// regardless of the app theme (same rationale as the attribution
    /// badge and the photo viewer's chrome).
    private func clusterLabel(_ text: String) -> some View {
        Text(text.uppercased())
            .font(.system(size: 10.5, weight: .semibold, design: .monospaced))
            .tracking(0.6)
            .foregroundStyle(Color(red: 0.949, green: 0.961, blue: 0.953)) // #F2F5F3
            .padding(.horizontal, 8)
            .padding(.vertical, 3)
            .background(
                RoundedRectangle(cornerRadius: 5, style: .continuous)
                    .fill(Color(red: 6 / 255, green: 9 / 255, blue: 10 / 255)
                        .opacity(0.65)))
    }

    // MARK: Peek card (mock ②)

    private func peekCard(for pin: MapPin) -> some View {
        let photos = pin.entries.compactMap(\.photoPath)
        return VStack(spacing: 0) {
            RoundedRectangle(cornerRadius: 2)
                .fill(ForestixPalette.divider)
                .frame(width: 36, height: 4)
                .padding(.bottom, 10)

            HStack(alignment: .firstTextBaseline, spacing: 10) {
                Text(peekTitle(pin))
                    .font(.system(size: 16, weight: .bold))
                    .foregroundStyle(ForestixPalette.textPrimary)
                    .lineLimit(1)
                Spacer(minLength: 4)
                Text(peekSubtitle(pin))
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundStyle(ForestixPalette.textTertiary)
                    .lineLimit(1)
            }
            .padding(.bottom, 10)

            HStack(alignment: .top, spacing: ForestixSpace.sm) {
                // The thumbnail itself is now the photo affordance —
                // tapping it opens the full-screen viewer (the old
                // "View photo" button is replaced by "Edit this tree").
                Button {
                    openPhotoViewer(pin)
                } label: {
                    photoThumb(photos: photos)
                }
                .buttonStyle(MapPressableStyle())
                .disabled(photos.isEmpty)
                .accessibilityLabel("View photo")
                .accessibilityIdentifier("mapHome.peek.photoThumb")
                VStack(spacing: 0) {
                    let rows = peekRows(pin)
                    ForEach(Array(rows.enumerated()), id: \.element.id) { index, row in
                        measurementRow(row)
                            .overlay(alignment: .bottom) {
                                if index < rows.count - 1 {
                                    Rectangle()
                                        .fill(ForestixPalette.divider)
                                        .frame(height: 0.5)
                                }
                            }
                    }
                }
                .frame(maxWidth: .infinity)
            }

            HStack(spacing: ForestixSpace.xs) {
                Button {
                    editingEntry = primaryEntry(for: pin)
                } label: {
                    Text("Edit this tree")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(ForestixPalette.textPrimary)
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .background(
                            RoundedRectangle(cornerRadius: ForestixRadius.control,
                                             style: .continuous)
                                .stroke(ForestixPalette.divider, lineWidth: 1))
                        .contentShape(Rectangle())
                }
                .buttonStyle(MapPressableStyle())
                .accessibilityIdentifier("mapHome.peek.edit")

                Button {
                    measureAgain(pin)
                } label: {
                    Text(pin.treeNumber != nil ? "Measure this tree"
                                               : "New measurement")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(ForestixPalette.primaryInk)
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .background(
                            RoundedRectangle(cornerRadius: ForestixRadius.control,
                                             style: .continuous)
                                .fill(ForestixPalette.primary))
                        .contentShape(Rectangle())
                }
                .buttonStyle(MapPressableStyle())
                .accessibilityIdentifier("mapHome.peek.measureAgain")
            }
            .padding(.top, ForestixSpace.sm)
        }
        .padding(14)
        .background(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .fill(ForestixPalette.surface))
        .overlay(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .stroke(ForestixPalette.divider, lineWidth: 1))
        .shadow(color: Color.black.opacity(0.22), radius: 14, y: -4)
        .padding(.horizontal, ForestixSpace.sm)
        .padding(.bottom, 20)
        .transition(.move(edge: .bottom).combined(with: .opacity))
        .accessibilityIdentifier("mapHome.peek")
    }

    private static let peekDateFormatter: DateFormatter = {
        let df = DateFormatter()
        df.locale = Locale(identifier: "en_US_POSIX")
        df.dateFormat = "d MMM · HH:mm"
        return df
    }()

    private func peekTitle(_ pin: MapPin) -> String {
        let species = pin.entries.compactMap(\.speciesCode).first
        let base: String
        if let n = pin.treeNumber {
            base = "Tree \(n)"
        } else {
            base = kindLabel(pin.entries[0].kind)
        }
        if let species, !species.isEmpty {
            return "\(base) · \(RegionalSpecies.name(forCode: species))"
        }
        return base
    }

    private func peekSubtitle(_ pin: MapPin) -> String {
        let latest = pin.entries[0]
        var parts = [Self.peekDateFormatter.string(from: latest.createdAt)]
        if let plotID = latest.plotID,
           let plot = history.plot(id: plotID),
           !plot.isDefault {
            parts.append(plot.name)
        }
        return parts.joined(separator: " · ")
    }

    private func kindLabel(_ kind: QuickMeasureEntry.Kind) -> String {
        switch kind {
        case .dbh:          return "DBH"
        case .height:       return "Height"
        case .crown:        return "Crown"
        case .distance:     return "Distance"
        case .samplingPlot: return "Plot"
        }
    }

    // One row per measurement kind — the LATEST entry of that kind.
    // Peek display carries the value only; ±σ is deliberately NOT shown
    // here (internal storage / CSV / FieldLog keep it).
    private struct PeekRow: Identifiable {
        let id: String
        let label: String
        let value: String
        let confidenceRaw: String
    }

    private func peekRows(_ pin: MapPin) -> [PeekRow] {
        let order: [QuickMeasureEntry.Kind] = [.dbh, .height, .crown,
                                               .distance, .samplingPlot]
        let system = settings.unitSystem
        return order.compactMap { kind in
            guard let entry = pin.entries.first(where: { $0.kind == kind })
            else { return nil }
            let value: String
            switch kind {
            case .dbh:
                value = MeasurementFormatter.diameter(cm: entry.value, in: system)
            case .height:
                value = MeasurementFormatter.height(m: entry.value, in: system)
            case .crown:
                value = String(format: "%.1f × %.1f m",
                               entry.value, entry.secondaryValue ?? 0)
            case .distance:
                value = MeasurementFormatter.distance(m: entry.value, in: system)
            case .samplingPlot:
                let area = entry.secondaryValue
                    ?? (.pi * entry.value * entry.value)
                value = String(format: "%.1f m radius · %.0f m²", entry.value, area)
            }
            return PeekRow(id: kind.rawValue,
                           label: peekRowLabel(kind),
                           value: value,
                           confidenceRaw: entry.confidenceRaw)
        }
    }

    private func peekRowLabel(_ kind: QuickMeasureEntry.Kind) -> String {
        switch kind {
        case .dbh:          return "DBH"
        case .height:       return "HEIGHT"
        case .crown:        return "CROWN"
        case .distance:     return "DIST"
        case .samplingPlot: return "PLOT"
        }
    }

    private func measurementRow(_ row: PeekRow) -> some View {
        HStack(spacing: ForestixSpace.xs) {
            Text(row.label)
                .font(.system(size: 10, weight: .bold))
                .tracking(0.7)
                .foregroundStyle(ForestixPalette.textTertiary)
                .frame(width: 52, alignment: .leading)
            Text(row.value)
                .font(.system(size: 14.5, weight: .semibold, design: .monospaced))
                .foregroundStyle(ForestixPalette.textPrimary)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
            Spacer(minLength: 4)
            tierChip(row.confidenceRaw)
        }
        .padding(.vertical, 6)
    }

    private func tierChip(_ raw: String) -> some View {
        let descriptor = ConfidenceStyle.descriptor(for: raw)
        return HStack(spacing: 4) {
            Circle().fill(descriptor.color).frame(width: 6, height: 6)
            Text(descriptor.label.uppercased())
                .font(.system(size: 10, weight: .bold))
                .tracking(0.6)
        }
        .foregroundStyle(descriptor.color)
        .padding(.horizontal, 7)
        .padding(.vertical, 2)
        .background(
            RoundedRectangle(cornerRadius: ForestixRadius.chip)
                .fill(descriptor.color.opacity(0.12)))
    }

    private func photoThumb(photos: [String]) -> some View {
        ZStack {
            RoundedRectangle(cornerRadius: ForestixRadius.card, style: .continuous)
                .fill(ForestixPalette.surfaceRaised)
            if let name = photos.first {
                MeasurePhotoThumbnail(name: name)
            } else {
                Image(systemName: "photo")
                    .font(.system(size: 22, weight: .regular))
                    .foregroundStyle(ForestixPalette.textTertiary)
            }
        }
        .frame(width: 96, height: 96)
        .clipShape(RoundedRectangle(cornerRadius: ForestixRadius.card,
                                    style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: ForestixRadius.card, style: .continuous)
                .stroke(ForestixPalette.divider, lineWidth: 1))
        .overlay(alignment: .topTrailing) {
            if photos.count > 1 {
                Text("×\(photos.count)")
                    .font(.system(size: 9, weight: .bold, design: .monospaced))
                    .foregroundStyle(Color(red: 0.949, green: 0.961, blue: 0.953))
                    .padding(.horizontal, 5)
                    .padding(.vertical, 2)
                    .background(
                        RoundedRectangle(cornerRadius: 4)
                            .fill(Color(red: 6 / 255, green: 9 / 255, blue: 10 / 255)
                                .opacity(0.70)))
                    .padding(4)
            }
        }
    }

    private func openPhotoViewer(_ pin: MapPin) {
        guard let entry = pin.entries.first(where: { $0.photoPath != nil })
        else { return }
        photoViewer = PhotoViewerContext(entry: entry, title: peekTitle(pin))
    }

    /// The pin's representative reading for "Edit this tree" — DBH first,
    /// then height / crown / distance / plot, matching the peek row
    /// order. Single-entry pins just return their one reading.
    private func primaryEntry(for pin: MapPin) -> QuickMeasureEntry {
        let order: [QuickMeasureEntry.Kind] = [.dbh, .height, .crown,
                                               .distance, .samplingPlot]
        for kind in order {
            if let entry = pin.entries.first(where: { $0.kind == kind }) {
                return entry
            }
        }
        return pin.entries[0]
    }

    /// Peek primary button. "Measure this tree" opens the measure
    /// chooser SCOPED to the pin's tree number — after a far-GPS
    /// confirmation when the current fix sits > 30 m from the pin (no
    /// fix at all skips the check). A tree-less pin ("New measurement")
    /// gets the plain chooser, same as the big (+).
    private func measureAgain(_ pin: MapPin) {
        guard let tree = pin.treeNumber else {
            chooserTreeOverride = nil
            presentingChooser = true
            return
        }
        if let fix = location.latestSnapshot ?? LocationService.lastGlobalFix {
            let distanceM = CoordinateConversions.haversineMeters(
                CoordinateConversions.LatLon(latitude: fix.latitude,
                                             longitude: fix.longitude),
                CoordinateConversions.LatLon(latitude: pin.latitude,
                                             longitude: pin.longitude))
            if distanceM > Self.farTreeWarnDistanceM {
                farTreeWarning = FarTreeWarning(
                    treeNumber: tree,
                    distanceM: Int(distanceM.rounded()))
                return
            }
        }
        openChooser(scopedTo: tree)
    }

    /// Present the measure chooser locked to `tree` — the Full / DBH /
    /// Height rows record against it instead of the next free number.
    private func openChooser(scopedTo tree: Int) {
        chooserTreeOverride = tree
        presentingChooser = true
    }

    // MARK: Measure chooser (mock ③)

    /// Tree number the chooser's tree rows lock the reading to — the
    /// peek-card override when set, else the next free number.
    private var chooserTargetTree: Int {
        chooserTreeOverride ?? history.suggestedNextTreeNumber
    }

    private var measureChooser: some View {
        VStack(alignment: .leading, spacing: 0) {
            // Scoped from a tree pin the header drops "(NEXT)" — this is
            // THAT tree, not the next free number.
            Text(chooserTreeOverride == nil
                 ? "MEASURE · TREE \(chooserTargetTree) (NEXT)"
                 : "MEASURE · TREE \(chooserTargetTree)")
                .font(.system(size: 13, weight: .heavy))
                .tracking(1.0)
                .foregroundStyle(ForestixPalette.textTertiary)
                .padding(.top, ForestixSpace.md)
                .padding(.bottom, ForestixSpace.xs)

            chooserRow("Full measurement", "DBH → Height, one tree",
                       icon: "tree", accessibilityID: "mapHome.choose.full",
                       divided: true, emphasized: true) {
                pendingTreeNumber = chooserTargetTree
                pendingChoice = .fullMeasurement
                presentingChooser = false
            }
            // Subtitles describe what the tool DOES. The old ones listed
            // internal capture-arm names, and two of them — AR motion and
            // AR caliper — were removed from the app (Models/Tree.swift
            // keeps them decode-only), so the sheet was advertising
            // measurements Forestix can no longer take.
            chooserRow("Diameter (DBH)", "Scan the trunk with the camera",
                       icon: "ruler", accessibilityID: "mapHome.choose.dbh",
                       divided: true) {
                pendingTreeNumber = chooserTargetTree
                pendingChoice = .dbh
                presentingChooser = false
            }
            chooserRow("Height", "Walk back, aim at the base and the top",
                       icon: "arrow.up.and.down", accessibilityID: "mapHome.choose.height",
                       divided: true) {
                pendingTreeNumber = chooserTargetTree
                pendingChoice = .height
                presentingChooser = false
            }
            chooserRow("Distance", "Point at a target, or tap two points",
                       icon: "arrow.left.and.right", accessibilityID: "mapHome.choose.distance",
                       divided: true) {
                pendingChoice = .distance
                presentingChooser = false
            }
            chooserRow("Sampling plot", "Centre stake · boundary ring",
                       icon: "scope", accessibilityID: "mapHome.choose.sampling",
                       divided: false) {
                pendingChoice = .sampling
                presentingChooser = false
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, ForestixSpace.md)
        .presentationDetents([.height(470)])
        .presentationDragIndicator(.visible)
        .presentationBackground(ForestixPalette.surface)
    }

    private func chooserRow(_ title: String,
                            _ subtitle: String,
                            icon: String,
                            accessibilityID: String,
                            divided: Bool,
                            emphasized: Bool = false,
                            action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 14) {
                ZStack {
                    // Emphasized (the Full-measurement hero row): solid
                    // primary tile + ink glyph, same treatment as the big
                    // (+) capture button — reads as "the" workflow.
                    RoundedRectangle(cornerRadius: 9, style: .continuous)
                        .fill(emphasized ? ForestixPalette.primary
                                         : ForestixPalette.primaryMuted)
                        .frame(width: 44, height: 44)
                    Image(systemName: icon)
                        .font(.system(size: 20, weight: .medium))
                        .foregroundStyle(emphasized ? ForestixPalette.primaryInk
                                                    : ForestixPalette.primary)
                }
                VStack(alignment: .leading, spacing: 1) {
                    Text(title)
                        .font(.system(size: 15.5, weight: .bold))
                        .foregroundStyle(ForestixPalette.textPrimary)
                    Text(subtitle)
                        .font(ForestixType.caption)
                        .foregroundStyle(ForestixPalette.textSecondary)
                }
                Spacer()
                Image(systemName: "chevron.forward")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(ForestixPalette.textTertiary)
            }
            .padding(.vertical, 13)
            .padding(.horizontal, 6)
            .frame(minHeight: 56)
            .contentShape(Rectangle())
        }
        .buttonStyle(MapPressableStyle())
        .accessibilityIdentifier(accessibilityID)
        .overlay(alignment: .bottom) {
            if divided {
                Rectangle().fill(ForestixPalette.divider).frame(height: 0.5)
            }
        }
    }

    /// Runs from the chooser sheet's onDismiss so the cover presents
    /// only after the sheet has fully gone.
    private func launchPendingChoice() {
        // The tree rows copied any peek-card override into
        // pendingTreeNumber before dismissing — clear it here (EVERY
        // dismissal path) so the next plain (+) chooser is unscoped.
        chooserTreeOverride = nil
        guard let choice = pendingChoice else { return }
        pendingChoice = nil
        fullMeasurementChain = (choice == .fullMeasurement)
        switch choice {
        case .fullMeasurement,
             .dbh:      presentingDBHScan = true
        case .height:   presentingHeightScan = true
        case .distance: presentingDistance = true
        case .sampling: presentingSampling = true
        }
    }

    /// DBH cover dismissed. In the full-measurement chain an accepted
    /// DBH hands straight off to the Height cover locked to the SAME
    /// tree number (`pendingTreeNumber` is left untouched) — no
    /// continuation dialog in this mode. Closing the DBH cover without
    /// accepting cancels the chain.
    private func continueChainAfterDBH() {
        if chainHeightPending {
            chainHeightPending = false
            presentingHeightScan = true
        } else {
            fullMeasurementChain = false
        }
    }

    // MARK: Measurement covers

    #if os(iOS)
    private var dbhCover: some View {
        NavigationStack {
            DBHScanScreen(
                viewModel: DBHScanViewModel(calibration: .identity),
                onAccept: { result, meta in
                    history.append(QuickMeasureEntry(
                        kind: .dbh,
                        value: Double(result.diameterCm),
                        sigma: Double(result.sigmaRmm),
                        confidenceRaw: result.confidence.rawValue,
                        method: result.method.rawValue,
                        treeNumber: pendingTreeNumber,
                        plotID: history.activePlotID,
                        speciesCode: meta.speciesCode,
                        position: meta.position ?? .dbh,
                        damageCodes: meta.damageCodes,
                        note: meta.note.isEmpty ? nil : meta.note,
                        latitude: meta.latitude,
                        longitude: meta.longitude,
                        photoPath: meta.photoPath,
                        captureMode: meta.captureMode))
                    // Full-measurement chain: an accepted DBH arms the
                    // Height cover; onDismiss presents it for the same
                    // tree number.
                    if fullMeasurementChain { chainHeightPending = true }
                    presentingDBHScan = false
                    // Quick measure is an in-memory/JSON append that cannot
                    // report a row-level failure — the reading is on the
                    // history the moment this returns.
                    return true
                },
                // Raw-capture join keys: without these a stored bundle can't
                // be paired back to the tree (and its truth) it documents.
                projectID: currentProject?.id.uuidString,
                quickTreeNumber: pendingTreeNumber)
        }
    }

    private var heightCover: some View {
        NavigationStack {
            HeightScanScreen(
                viewModel: HeightScanViewModel(calibration: .identity),
                onAccept: { result, meta in
                    history.append(QuickMeasureEntry(
                        kind: .height,
                        value: Double(result.heightM),
                        // `QuickMeasureEntry.sigma` is already optional —
                        // a reading with no propagated σ (typed manual
                        // entry) records none, rather than ±0.
                        sigma: result.sigmaHm.map(Double.init),
                        confidenceRaw: result.confidence.rawValue,
                        method: result.method.rawValue,
                        treeNumber: pendingTreeNumber,
                        plotID: history.activePlotID,
                        speciesCode: meta.speciesCode,
                        damageCodes: meta.damageCodes,
                        note: meta.note.isEmpty ? nil : meta.note,
                        latitude: meta.latitude,
                        longitude: meta.longitude,
                        photoPath: meta.photoPath))
                    presentingHeightScan = false
                    // Quick measure is an in-memory/JSON append that cannot
                    // report a row-level failure — the reading is on the
                    // history the moment this returns.
                    return true
                },
                onCrown: { widthM, heightM in
                    // Crown is measured inside the Height session — it
                    // reuses the walk-off distance d_h so the canopy points
                    // land at real scale. Logged against the same tree.
                    history.append(QuickMeasureEntry(
                        kind: .crown,
                        value: widthM,
                        secondaryValue: heightM,
                        sigma: nil,
                        confidenceRaw: "green",
                        method: "ar.crown.dh",
                        treeNumber: pendingTreeNumber,
                        plotID: history.activePlotID))
                },
                // Raw-capture join keys — height bundles used to be anonymous
                // (tree + project hardcoded nil), so they couldn't be paired
                // with the tree's DBH bundle or its hand-measured truth.
                projectID: currentProject?.id.uuidString,
                treeNumber: pendingTreeNumber)
            .environmentObject(history)
            .environmentObject(settings)
        }
    }
    #endif
}

// MARK: - Pressed feedback

/// Shared pressed treatment for the map chrome — same opacity + timing
/// language as ForestixProminentButtonStyle, applied to arbitrary labels
/// (circles, chips, rows) instead of the full-width green fill.
private struct MapPressableStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .opacity(configuration.isPressed ? 0.78 : 1)
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .animation(.easeOut(duration: 0.15), value: configuration.isPressed)
    }
}

// MARK: - Photo thumbnail loader

/// Loads a MeasurePhotoStore JPEG off the main thread, then hands the
/// decoded image to SwiftUI. Grey placeholder shows through until then.
private struct MeasurePhotoThumbnail: View {
    let name: String

    #if canImport(UIKit)
    @State private var image: UIImage?

    var body: some View {
        Group {
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
            } else {
                Color.clear
            }
        }
        .task(id: name) {
            let url = MeasurePhotoStore.url(for: name)
            let data = await Task.detached { try? Data(contentsOf: url) }.value
            if let data { image = UIImage(data: data) }
        }
    }
    #else
    var body: some View { Color.clear }
    #endif
}

// MARK: - Quick entry edit sheet (map peek → "Edit this tree")

/// Compact editor for one QuickMeasureEntry reached from the quick peek.
/// Edits the measured value (native unit — cm for DBH, m otherwise), the
/// species code and the note, then persists via QuickMeasureHistory
/// `update`. A confirmed destructive Delete removes the entry AND its
/// photo (through `delete`, which calls MeasurePhotoStore). The measure
/// math is untouched — only the primary value the cruiser typed changes.
private struct QuickEntryEditSheet: View {
    let entry: QuickMeasureEntry
    @ObservedObject var history: QuickMeasureHistory
    @Environment(\.dismiss) private var dismiss

    @State private var valueText: String
    @State private var speciesText: String
    @State private var noteText: String
    @State private var confirmingDelete = false

    init(entry: QuickMeasureEntry, history: QuickMeasureHistory) {
        self.entry = entry
        _history = ObservedObject(wrappedValue: history)
        _valueText = State(initialValue: Self.formatValue(entry.value))
        _speciesText = State(initialValue: entry.speciesCode ?? "")
        _noteText = State(initialValue: entry.note ?? "")
    }

    var body: some View {
        VStack(alignment: .leading, spacing: ForestixSpace.sm) {
            Text(headerText)
                .font(.system(size: 13, weight: .heavy))
                .tracking(1.0)
                .foregroundStyle(ForestixPalette.textTertiary)
                .padding(.top, ForestixSpace.md)

            // Measured value — native unit (cm for DBH, m otherwise).
            fieldLabel(kindTitle.uppercased())
            HStack(spacing: ForestixSpace.xs) {
                TextField("0.0", text: $valueText)
                    .font(.system(size: 16, weight: .semibold, design: .monospaced))
                    .textFieldStyle(.plain)
                    #if os(iOS)
                    .keyboardType(.decimalPad)
                    #endif
                    .padding(.horizontal, 12)
                    .frame(minHeight: 44)
                    .background(fieldBackground)
                    .accessibilityIdentifier("mapHome.editSheet.value")
                Text(entry.valueUnit)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(ForestixPalette.textSecondary)
                    .frame(width: 32, alignment: .leading)
            }

            // Species — the short FIA code (free text; uppercased on save).
            fieldLabel("SPECIES")
            TextField("e.g. DF", text: $speciesText)
                .font(.system(size: 15, weight: .semibold))
                .textFieldStyle(.plain)
                #if os(iOS)
                .textInputAutocapitalization(.characters)
                .autocorrectionDisabled(true)
                #endif
                .padding(.horizontal, 12)
                .frame(minHeight: 44)
                .background(fieldBackground)
                .accessibilityIdentifier("mapHome.editSheet.species")

            // Read-only resolution of the typed code → common name, so the
            // cruiser can confirm what "DF" maps to. Hidden for blank or
            // unknown (free-typed) codes that don't resolve.
            if let resolved = resolvedSpeciesName {
                Text(resolved)
                    .font(.system(size: 13))
                    .foregroundStyle(ForestixPalette.textSecondary)
                    .padding(.horizontal, 12)
                    .accessibilityIdentifier("mapHome.editSheet.speciesName")
            }

            // Note.
            fieldLabel("NOTE")
            TextField("Optional note", text: $noteText, axis: .vertical)
                .font(.system(size: 15))
                .textFieldStyle(.plain)
                .lineLimit(1...4)
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .frame(minHeight: 44, alignment: .top)
                .background(fieldBackground)
                .accessibilityIdentifier("mapHome.editSheet.note")

            Spacer(minLength: ForestixSpace.xs)

            Button {
                save()
            } label: {
                Text("Save changes")
                    .font(.system(size: 15, weight: .bold))
                    .foregroundStyle(ForestixPalette.primaryInk)
                    .frame(maxWidth: .infinity, minHeight: 54)
                    .background(
                        RoundedRectangle(cornerRadius: ForestixRadius.card,
                                         style: .continuous)
                            .fill(ForestixPalette.primary))
                    .contentShape(Rectangle())
            }
            .buttonStyle(MapPressableStyle())
            .accessibilityIdentifier("mapHome.editSheet.save")

            Button(role: .destructive) {
                confirmingDelete = true
            } label: {
                Text("Delete")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(ForestixPalette.confidenceBad)
                    .frame(maxWidth: .infinity, minHeight: 44)
                    .background(
                        RoundedRectangle(cornerRadius: ForestixRadius.control,
                                         style: .continuous)
                            .stroke(ForestixPalette.confidenceBad.opacity(0.5),
                                    lineWidth: 1))
                    .contentShape(Rectangle())
            }
            .buttonStyle(MapPressableStyle())
            .accessibilityIdentifier("mapHome.editSheet.delete")
        }
        .padding(.horizontal, ForestixSpace.md)
        .padding(.bottom, ForestixSpace.md)
        .frame(maxHeight: .infinity, alignment: .top)
        .presentationDetents([.height(430)])
        .presentationDragIndicator(.visible)
        .presentationBackground(ForestixPalette.surface)
        .alert("Delete this reading?", isPresented: $confirmingDelete) {
            Button("Delete", role: .destructive) {
                history.delete(id: entry.id)
                dismiss()
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This removes the \(kindTitle) reading and its photo. This can't be undone.")
        }
    }

    /// Common name for the currently-typed code, or nil when the field is
    /// blank or the code is free-typed / unknown (name == code).
    private var resolvedSpeciesName: String? {
        let code = speciesText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !code.isEmpty else { return nil }
        let name = RegionalSpecies.name(forCode: code)
        return name.caseInsensitiveCompare(code) == .orderedSame ? nil : name
    }

    private func save() {
        let trimmedSpecies = speciesText
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedNote = noteText
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let newValue = Double(valueText.replacingOccurrences(of: ",", with: "."))
            ?? entry.value
        history.update(QuickMeasureEntry(
            id: entry.id,
            kind: entry.kind,
            value: newValue,
            secondaryValue: entry.secondaryValue,
            sigma: entry.sigma,
            confidenceRaw: entry.confidenceRaw,
            method: entry.method,
            createdAt: entry.createdAt,
            treeNumber: entry.treeNumber,
            plotID: entry.plotID,
            speciesCode: trimmedSpecies.isEmpty ? nil : trimmedSpecies.uppercased(),
            position: entry.position,
            damageCodes: entry.damageCodes,
            note: trimmedNote.isEmpty ? nil : trimmedNote,
            latitude: entry.latitude,
            longitude: entry.longitude,
            photoPath: entry.photoPath,
            captureMode: entry.captureMode))
        dismiss()
    }

    private var headerText: String {
        if let n = entry.treeNumber { return "EDIT · TREE \(n)" }
        return "EDIT · \(kindTitle.uppercased())"
    }

    private var kindTitle: String {
        switch entry.kind {
        case .dbh:          return "DBH"
        case .height:       return "Height"
        case .crown:        return "Crown"
        case .distance:     return "Distance"
        case .samplingPlot: return "Plot radius"
        }
    }

    private func fieldLabel(_ text: String) -> some View {
        Text(text)
            .font(.system(size: 10, weight: .bold))
            .tracking(0.7)
            .foregroundStyle(ForestixPalette.textTertiary)
    }

    private var fieldBackground: some View {
        RoundedRectangle(cornerRadius: ForestixRadius.control, style: .continuous)
            .fill(ForestixPalette.surfaceRaised)
    }

    /// Show the stored value compactly: integers with no decimals, else
    /// up to two decimals with a lone trailing zero trimmed.
    private static func formatValue(_ v: Double) -> String {
        if v == v.rounded() { return String(format: "%.0f", v) }
        return String(format: "%.2f", v)
            .replacingOccurrences(of: "0$", with: "", options: .regularExpression)
    }
}

// MARK: - Photo detail (mock ⑤)

private struct PhotoViewerContext: Identifiable {
    let entry: QuickMeasureEntry
    let title: String
    var id: UUID { entry.id }
}

/// Full-screen AR-snapshot viewer: the photo (feed + overlay, captured
/// at Accept) with the reading's identity along the bottom. The chrome
/// is fixed dark regardless of appearance — it sits on a photograph.
private struct MeasurePhotoDetailView: View {
    let context: PhotoViewerContext
    let unitSystem: UnitSystem

    @Environment(\.dismiss) private var dismiss
    #if canImport(UIKit)
    @State private var image: UIImage?
    #endif

    private let ink = Color(red: 0.949, green: 0.961, blue: 0.953)      // #F2F5F3
    private let inkDim = Color(red: 0.647, green: 0.682, blue: 0.659)   // #A5AEA8
    /// Meta-cell labels — dark-appearance textSecondary, fixed because
    /// the chrome sits on a photograph regardless of the app theme.
    private let labelDim = Color(red: 0.718, green: 0.753, blue: 0.729) // #B7C0BA
    /// Dark-glass chrome base (mock `rgba(6,9,10,…)`).
    private let glass = Color(red: 6 / 255, green: 9 / 255, blue: 10 / 255) // #06090A

    var body: some View {
        ZStack {
            Color(red: 0.039, green: 0.051, blue: 0.043).ignoresSafeArea() // #0A0D0B

            #if canImport(UIKit)
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFit()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ProgressView().tint(ink)
            }
            #endif

            VStack {
                HStack {
                    Spacer()
                    Button {
                        dismiss()
                    } label: {
                        Image(systemName: "xmark")
                            .font(.system(size: 16, weight: .semibold))
                            .foregroundStyle(ink)
                            .frame(width: 44, height: 44)
                            .background(Circle().fill(glass.opacity(0.70)))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Close photo")
                    .accessibilityIdentifier("mapHome.photo.close")
                }
                .padding(.horizontal, 14)
                Spacer()
            }

            VStack {
                Spacer()
                meta
            }
        }
        #if canImport(UIKit)
        .task {
            let url = MeasurePhotoStore.url(for: context.entry.photoPath ?? "")
            let data = await Task.detached { try? Data(contentsOf: url) }.value
            if let data { image = UIImage(data: data) }
        }
        #endif
    }

    private var meta: some View {
        let entry = context.entry
        let descriptor = ConfidenceStyle.descriptor(for: entry.confidenceRaw)
        return VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .firstTextBaseline, spacing: 10) {
                Text(bigValue)
                    .font(.system(size: 30, weight: .heavy, design: .monospaced))
                    .foregroundStyle(ink)
                Text([sigmaText, descriptor.label]
                    .compactMap { $0 }.joined(separator: " · "))
                    .font(.system(size: 13, design: .monospaced))
                    .foregroundStyle(inkDim)
            }
            HStack(alignment: .top, spacing: 18) {
                metaCell("TREE", treeText)
                metaCell("METHOD", methodText)
                metaCell("GPS", gpsText)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 20)
        .padding(.top, 40)
        .padding(.bottom, 30)
        .background(
            LinearGradient(colors: [glass.opacity(0),
                                    glass.opacity(0.92)],
                           startPoint: .top, endPoint: .bottom))
    }

    private func metaCell(_ label: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(label)
                .font(.system(size: 11.5, design: .monospaced))
                .foregroundStyle(labelDim)
            Text(value)
                .font(.system(size: 13, weight: .bold, design: .monospaced))
                .foregroundStyle(ink)
                .lineLimit(1)
        }
    }

    private var bigValue: String {
        let entry = context.entry
        switch entry.kind {
        case .dbh:
            // The peek card this viewer opens from labels these two
            // values "DBH" and "HEIGHT"; Ø and H were the only place
            // they appeared as symbols.
            return "DBH " + MeasurementFormatter.diameter(cm: entry.value, in: unitSystem)
        case .height:
            return "Height " + MeasurementFormatter.height(m: entry.value, in: unitSystem)
        case .crown:
            return String(format: "%.1f × %.1f m",
                          entry.value, entry.secondaryValue ?? 0)
        case .distance:
            return MeasurementFormatter.distance(m: entry.value, in: unitSystem)
        case .samplingPlot:
            return String(format: "%.1f m radius", entry.value)
        }
    }

    /// Plain-language name for the stored capture method. `entry.method`
    /// holds the raw enum/tag string that goes into the CSV export
    /// ("lidarChordSilhouette", "vioWalkoffTangent", "two-point.lidar",
    /// …); printing it verbatim put camelCase identifiers on a cruiser's
    /// screen. The raw value is unchanged in storage and export — only
    /// this readout is translated, and an unknown tag degrades to a
    /// generic phrase rather than leaking the identifier.
    private var methodText: String {
        let raw = context.entry.method
        switch raw {
        case "lidarChordSilhouette", "lidarPartialArcSingleView",
             "lidarPartialArcDualView", "lidarIrregular":
            return "Trunk scan"
        case "arCaliper", "arVioCircleFit":
            return "Trunk scan (earlier app version)"
        case "manualCaliper":       return "Measured by hand"
        case "manualVisual":        return "Estimated by eye"
        case "vioWalkoffTangent":   return "Walked back and sighted"
        case "tapeTangent":         return "Tape and angle"
        case "manualEntry":         return "Typed in"
        case "imputedHD":           return "Estimated from the height curve"
        case "ar.crown.dh":         return "Crown edges tapped"
        case "ar.tap":              return "Centre dropped on the ground"
        default:
            if raw.hasPrefix("live.")      { return "Pointed at a target" }
            if raw.hasPrefix("two-point.") { return "Two points on screen" }
            return "Measured in Forestix"
        }
    }

    private var sigmaText: String? {
        let entry = context.entry
        guard let sigma = entry.sigma, sigma > 0 else { return nil }
        switch entry.kind {
        case .dbh:
            return MeasurementFormatter.diameterSigma(mm: sigma, in: unitSystem)
        case .height:
            return MeasurementFormatter.heightSigma(m: sigma, in: unitSystem)
        case .crown, .distance, .samplingPlot:
            return String(format: "±%.2f m", sigma)
        }
    }

    private var treeText: String {
        let entry = context.entry
        var parts: [String] = []
        if let n = entry.treeNumber { parts.append("T\(n)") }
        if let species = entry.speciesCode, !species.isEmpty {
            parts.append(RegionalSpecies.name(forCode: species))
        }
        return parts.isEmpty ? "—" : parts.joined(separator: " · ")
    }

    /// The entry stores the fix itself (not its accuracy), so the GPS
    /// cell shows the coordinates the reading was anchored to.
    private var gpsText: String {
        guard let lat = context.entry.latitude,
              let lon = context.entry.longitude else { return "—" }
        return String(format: "%.5f, %.5f", lat, lon)
    }
}
