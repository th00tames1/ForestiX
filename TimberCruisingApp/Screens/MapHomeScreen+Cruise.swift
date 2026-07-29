// CRUISE MODE — the map IS the cruise. Built to
// design/forestix-redesign-v3-cruise.html (screens ①–⑧), Phases A + B,
// hosted as a MODE of the map home (one map, two modes — the former
// standalone CruiseMapScreen folded into MapHomeScreen; the mode
// toggle circle flips `tc.mapMode`, and camera/zoom are shared).
//
// This file is the cruise half of MapHomeScreen: everything the mode
// renders over the shared map plus its data and actions. The stored
// state lives in MapHomeScreen.swift (SwiftUI state can't live in
// extensions); the chrome differences are owned there too — no back
// button (cruise is a mode, not a push), a tappable project strip
// rides above the (+), and the (+) turns cruiseAccent-blue.
//
// The pins here are CRUISE data only: plots as ring markers (hollow
// dashed = planned, accent = active, green = closed) and cruise trees
// as teardrop pins. Quick-measure pins never appear in this mode, and
// cruise pins never appear in measure mode — the two worlds stay
// separate.
//
// Phase B pieces, all inline over the one map:
//   • Cruise setup (project sheet row) → CruiseSetupSheet, three
//     defaulted fields + optional boundary; "Generate plots" drops
//     hollow dashed ring pins.
//   • Tapping a planned pin peeks "Plot N (planned)" with a live
//     distance · bearing row; Navigate toggles the dashed map guide
//     line + floating distance chip (arrival <5 m pulses a haptic and
//     clears it) — there is no separate navigation screen.
//   • "Start plot now" (field report 17) converts the planned pin into
//     a real active plot on the fix that is live at that instant — one
//     tap, no window to sit out, measuring available immediately.
//   • "Set plot centre (GPS)" → RecordCentreSheet (inline 60 s GPS
//     averaging ring; offset fallback one line away); saving converts
//     the planned pin into a real active plot. Kept, and now a choice
//     rather than the only way past the planned pin.
//   • The project sheet exports with one primary "Export all" (full
//     bundle + share sheet, progress inline); "Choose files…" keeps
//     the per-file ExportScreen reachable.
//
// The single primary action is the state-morphing (+):
//   • no active plot  → "Start plot" — the existing sampling-ring AR
//     component places centre + radius; Save creates a cruise `Plot`
//     in the current project (auto-numbered "Plot N", centre from the
//     GPS fix) and leaves `ActiveSamplingPlot` placed so the scan
//     screens overlay the ring;
//   • active plot     → "Add tree · Plot N" — the DIAMETER LOOP: each
//     DBH Accept saves a cruise Tree through the Accept path with the
//     project's REAL calibration (value + σ + species/damage metadata +
//     GPS + auto-photo), auto-increments the tree number, and resets
//     the scan for the next trunk (Undo toast, 3 s). The floating back
//     exits the loop. Heights are on demand: tree peek "Measure height"
//     or the plot peek's HEIGHTS SHEET (pooled H–D curve estimates the
//     rest once ≥3 pairs exist). Zero typing per record.
//
// Tapping a plot ring peeks the tally card (live TREES/BA/TPA/QMD via
// the InventoryEngine, Add tree, Close plot, Details); tapping a tree
// pin peeks the v2-anatomy card (photo, metric rows, chips, Edit
// details — post-hoc, never a gate). The project chip opens the
// project sheet: switcher, one-time naming, Stand summary, advanced
// Cruise setup, Export all + Choose files…, and the relocated hub
// tools (Field log · Reference · Settings).

import SwiftUI
import Common
import Models
import Persistence
import InventoryEngine
import Sensors
import Positioning
import Geo
import Basemap
import Export

// MARK: - Cruise pushed destinations

/// Screens cruise mode pushes onto the home's NavigationStack.
/// Internal (not nested-private) because MapHomeScreen.swift declares
/// the `pushed` / `pendingDestination` state with this type.
enum CruiseDestination: Hashable, Identifiable {
    case plotDetails(UUID)
    case treeDetails(UUID)
    case standSummary
    case export
    case fieldLog
    case reference
    case settings
    var id: Self { self }
}

/// Identifiable URL wrapper for the export share sheet's `.sheet(item:)`.
/// Internal for the same reason as CruiseDestination.
struct ExportShareURL: Identifiable {
    let url: URL
    var id: URL { url }
}

/// Plot whose HEIGHTS SHEET is presented (plot peek → "Heights · N
/// measured"). Internal because MapHomeScreen.swift owns the state.
struct HeightsSheetTarget: Identifiable {
    let plotID: UUID
    var id: UUID { plotID }
}

/// Height screen scoped to one existing tree (tree peek / heights sheet
/// "Measure height") — staged across the sheet dismissal, same two-step
/// pattern as `pendingDestination`.
struct ScopedHeightRequest {
    let plotID: UUID
    let treeID: UUID
}

/// A cruise tree's auto-photo presented full-screen from the tree-peek
/// thumbnail. Internal because MapHomeScreen.swift owns the `@State`.
struct CruisePhotoContext: Identifiable {
    let photoPath: String
    let title: String
    let subtitle: String
    var id: String { photoPath }
}

// MARK: - Cruise mode

extension MapHomeScreen {

    // MARK: Derived state

    /// The project the chip is scoped to: the persisted pick when it
    /// still exists, else the most recently updated project.
    var currentProject: Project? {
        if let id = settings.currentCruiseProjectID,
           let hit = projects.first(where: { $0.id == id }) {
            return hit
        }
        return projects.max(by: { $0.updatedAt < $1.updatedAt })
    }

    /// The ACTIVE plot — the open (not-closed) plot started last.
    var activePlot: Plot? {
        plots.filter { $0.closedAt == nil }
            .max(by: { $0.startedAt < $1.startedAt })
    }

    func liveTrees(in plotID: UUID) -> [Tree] {
        (treesByPlot[plotID] ?? []).filter { $0.deletedAt == nil }
    }

    func nextTreeNumber(in plotID: UUID) -> Int {
        (liveTrees(in: plotID).map(\.treeNumber).max() ?? 0) + 1
    }

    /// Tree number of the tree the SCOPED HEIGHT session is measuring — the
    /// raw-capture join key for a height bundle. Read from the tree row
    /// (`chainTreeID`), not from `chainTreeNumber`, which is the diameter
    /// loop's NEXT target and would mislabel the bundle.
    var chainHeightTreeNumber: Int? {
        guard let plotID = chainPlotID, let treeID = chainTreeID else { return nil }
        return liveTrees(in: plotID).first(where: { $0.id == treeID })?.treeNumber
    }

    /// The unvisited planned plot the (+) "Set plot centre" targets:
    /// nearest to the current fix, or — with no fix — the lowest-numbered
    /// one. nil when none exist. (`plannedPlots` is already filtered to
    /// unvisited in `reloadCruise`.)
    func nearestUnvisitedPlannedPlot() -> PlannedPlot? {
        // Skipped plots (documented as inaccessible) are excluded from the
        // (+) "nearest unvisited" target — they stay visible on the map but
        // navigation passes over them.
        let candidates = plannedPlots.filter { !$0.skipped }
        guard !candidates.isEmpty else { return nil }
        guard let fix = location.latestSnapshot ?? LocationService.lastGlobalFix
        else {
            return candidates.min(by: { $0.plotNumber < $1.plotNumber })
        }
        return candidates.min(by: { a, b in
            GeoMath.distanceM(fromLat: fix.latitude, fromLon: fix.longitude,
                              toLat: a.plannedLat, toLon: a.plannedLon)
            < GeoMath.distanceM(fromLat: fix.latitude, fromLon: fix.longitude,
                                toLat: b.plannedLat, toLon: b.plannedLon)
        })
    }

    // MARK: Data

    func reloadCruise() {
        projects = (try? environment.projectRepository.list()) ?? []
        if speciesByCode.isEmpty {
            let species = (try? environment.speciesRepository.list()) ?? []
            speciesByCode = Dictionary(
                uniqueKeysWithValues: species.map { ($0.code, $0) })
        }
        guard let project = currentProject else {
            plots = []
            plannedPlots = []
            navTargetPlannedID = nil
            treesByPlot = [:]
            // No project ⇒ no plot the ring could still belong to.
            reconcileSamplingRing()
            return
        }
        plots = (try? environment.plotRepository.listByProject(project.id)) ?? []
        // Unvisited planned plots render as hollow dashed pins; visited
        // ones already have a real Plot ring standing on them.
        plannedPlots = ((try? environment.plannedPlotRepository
            .listByProject(project.id)) ?? []).filter { !$0.visited }
        if let target = navTargetPlannedID,
           !plannedPlots.contains(where: { $0.id == target }) {
            navTargetPlannedID = nil
        }
        var byPlot: [UUID: [Tree]] = [:]
        for plot in plots {
            byPlot[plot.id] =
                (try? environment.treeRepository.listByPlot(plot.id,
                                                            includeDeleted: false)) ?? []
        }
        treesByPlot = byPlot
        reconcileSamplingRing()
    }

    // MARK: The AR ring's lifetime (field report 7)

    /// FIELD REPORT 7 — "the plot edge is still visible long after leaving
    /// it". The AR ring was dropped only by Reset, by a new placement, by
    /// tracking loss, or by the app dying: NOTHING dropped it when the
    /// cruiser closed a plot and walked to the next one. Plot 1 → Close plot
    /// → walk → Start plot opened the setup screen already "placed", on plot
    /// 1's ring, tens of metres away — no crosshair, no shutter, and a
    /// boundary drawn around the wrong ground.
    ///
    /// The rule: once the ring is linked to a cruise plot it IS that plot's
    /// boundary, and it lives exactly as long as the plot is one the cruiser
    /// can still be measuring — i.e. OPEN, and in the current project.
    /// Closed, deleted, or left behind by a project switch ⇒ dropped, anchor
    /// and all.
    ///
    /// It runs from `reloadCruise`, which is the funnel EVERY plot mutation
    /// already goes through — the map peek's Close and Delete, the plot
    /// summary screen's Close / Reopen / Delete (via the `pushed` change),
    /// the setup cover's dismiss, a project switch — so a new way to close a
    /// plot cannot be added without this seeing it. Patching the two paths
    /// the report named would have left the summary screen's Close still
    /// leaking the ring.
    ///
    /// A ring linked to NOTHING is untouched: it is a quick-measure sampling
    /// ring, or one just placed and not yet saved, and no cruise plot's
    /// lifetime governs it. `armPlotSetup` handles that half.
    ///
    /// An unreadable plot list lands here as an empty one and drops the ring.
    /// That is the right way to be wrong: a boundary we can no longer prove
    /// belongs to an open plot is exactly the boundary the cruiser must not
    /// be shown.
    ///
    /// Android runs the same test in `CruiseModeEffects`' cruise-data effect,
    /// alongside the stale active-plot guard it already had.
    func reconcileSamplingRing() {
        guard let linked = samplingPlot.linkedCruisePlotID else { return }
        // `plots` is this project's, so a ring from another project fails
        // this test too — which is right: it is not a boundary here.
        if plots.contains(where: { $0.id == linked && $0.closedAt == nil }) {
            return
        }
        samplingPlot.drop()
    }

    /// Open the plot-setup cover, dropping a ring that belongs to a DIFFERENT
    /// cruise plot first. `editing` names the plot the session will rewrite,
    /// or nil for a fresh "Start plot".
    ///
    /// Two things go wrong when a foreign ring survives into this screen.
    /// The visible one is the report: the screen opens already "placed", so
    /// there is no crosshair and no shutter, and the cruiser is shown the
    /// previous plot's boundary while standing in the new one. The invisible
    /// one is worse — `editCruisePlot` reads "ring placed but not linked to
    /// THIS plot" as "the cruiser re-placed the centre", so a radius-only
    /// edit made with plot 1's ring still up would re-stamp plot 2's centre
    /// at wherever the cruiser happened to be standing.
    ///
    /// On a CREATE there is no plot yet, so ANY ring is foreign — including
    /// an unlinked one, which on this path is the ring left over from a plot
    /// whose Save was refused for want of a GPS fix. The cruiser asked to
    /// place a new centre; the screen owes them a crosshair.
    func armPlotSetup(editing plotID: UUID?) {
        if let plotID {
            samplingPlot.dropIfLinkedElsewhere(than: plotID)
        } else if samplingPlot.plot != nil {
            samplingPlot.drop()
        }
    }

    // MARK: Crash recovery (resume an in-progress plot)

    /// Once-per-launch scan for OPEN plots (closedAt == nil) across every
    /// project whose last activity falls inside the last 24 h. Populates the
    /// resume-prompt candidate list and emits `crashRecoveryPrompted` for the
    /// most-recent one when the prompt is about to appear. Guarded so a
    /// dismiss/Discard never re-prompts within the same launch.
    func scanForCrashRecovery() {
        guard !didScanForCrashRecovery else { return }
        didScanForCrashRecovery = true
        let candidates = (try? CrashRecoveryService.openPlotsWithinLast(
            86400,
            projectRepo: environment.projectRepository,
            plotRepo: environment.plotRepository,
            treeRepo: environment.treeRepository)) ?? []
        guard let first = candidates.first else { return }
        crashRecoveryCandidates = candidates
        ForestixLogger.log(.crashRecoveryPrompted(
            projectId: first.plot.projectId, plotId: first.plot.id))
    }

    /// Confirmation-dialog title — names the plot when there is a single
    /// candidate, else a generic prompt covering the surfaced set.
    var crashRecoveryTitle: String {
        guard let first = crashRecoveryCandidates.first else { return "" }
        return crashRecoveryCandidates.count == 1
            ? "Resume plot \(first.plot.plotNumber)?"
            : "Resume an in-progress plot?"
    }

    /// Resume a crash-recovery candidate: scope the cruise to the plot's
    /// project, flip to cruise mode, and enter the plot's active tally loop
    /// via the SAME route the (+) and plot-peek "Add tree" use
    /// (`startAddTree`). Emits `plotOpened` on entry. Discards NOTHING.
    func resumeCrashRecovery(_ candidate: ResumeCandidate) {
        let plot = candidate.plot
        crashRecoveryCandidates = []            // dismiss the prompt
        settings.currentCruiseProjectID = plot.projectId
        if settings.mapMode != "cruise" { settings.mapMode = "cruise" }
        reloadCruise()
        ForestixLogger.log(.plotOpened(plotId: plot.id,
                                       projectId: plot.projectId))
        startAddTree(in: plot)
    }

    // MARK: Markers

    /// Plot ring markers + cruise tree teardrops. Quick-measure entries
    /// are deliberately NOT read here. Status colours are UNCHANGED by
    /// the mode merge: accent-amber active, ok-green closed, dashed
    /// grey planned.
    var cruiseMarkers: [BasemapMarker] {
        // A plot whose centre was cleared by the map overlay's "Remove
        // plot" reads as (0, 0) — it has no place on the map, and a ring
        // pin at null island would be worse than no pin at all. The plot
        // and its trees are untouched; only the drawing goes.
        var out: [BasemapMarker] = plots
            .filter(\.hasCentre)
            .map { plot in
                BasemapMarker(
                    id: "plot-\(plot.id.uuidString)",
                    latitude: plot.centerLat,
                    longitude: plot.centerLon,
                    title: "P\(plot.plotNumber)",
                    tint: plot.closedAt == nil ? ForestixPalette.accent
                                               : ForestixPalette.confidenceOk,
                    shape: .ring(dashed: false))
            }
        // Planned plots — the mock's hollow dashed "planned" ring style.
        // Skipped (inaccessible) plots keep the dashed ring but switch to the
        // warn tint and carry a "SKIP" badge so they read distinctly from
        // both plain-planned (grey) and visited (real accent/green rings).
        for planned in plannedPlots {
            out.append(BasemapMarker(
                id: "pplot-\(planned.id.uuidString)",
                latitude: planned.plannedLat,
                longitude: planned.plannedLon,
                title: "P\(planned.plotNumber)",
                tint: planned.skipped ? ForestixPalette.confidenceWarn
                                      : ForestixPalette.textTertiary,
                badge: planned.skipped ? "SKIP" : nil,
                shape: .ring(dashed: true)))
        }
        for plot in plots {
            for tree in liveTrees(in: plot.id) {
                guard let lat = tree.latitude, let lon = tree.longitude
                else { continue }
                out.append(BasemapMarker(
                    id: "ctree-\(tree.id.uuidString)",
                    latitude: lat,
                    longitude: lon,
                    title: "T\(tree.treeNumber)",
                    tint: ForestixPalette.primary))
            }
        }
        return out
    }

    var selectedPlot: Plot? {
        guard let id = selectedPinID, id.hasPrefix("plot-") else { return nil }
        let raw = String(id.dropFirst("plot-".count))
        return plots.first { $0.id.uuidString == raw }
    }

    var selectedPlannedPlot: PlannedPlot? {
        guard let id = selectedPinID, id.hasPrefix("pplot-") else { return nil }
        let raw = String(id.dropFirst("pplot-".count))
        return plannedPlots.first { $0.id.uuidString == raw }
    }

    var selectedTree: Tree? {
        guard let id = selectedPinID, id.hasPrefix("ctree-") else { return nil }
        let raw = String(id.dropFirst("ctree-".count))
        for trees in treesByPlot.values {
            if let hit = trees.first(where: { $0.id.uuidString == raw }) {
                return hit
            }
        }
        return nil
    }

    // MARK: The state-morphing (+) (label + action; the button itself
    // is the home's shared 74 pt circle)

    /// LOCKED strings: "Add tree · Plot N" (active plot), "Set plot
    /// centre" (unvisited planned plots waiting, none active), else
    /// "Start plot" (ad-hoc). The (+) caption slot already absorbs the
    /// variable width, so the cluster stays pixel-invariant.
    var cruisePrimaryLabel: String {
        if let plot = activePlot {
            return "Add tree · Plot \(plot.plotNumber)"
        }
        // Only when a NON-skipped planned plot remains — mirrors the now-nil
        // nearestUnvisitedPlannedPlot() so the label never promises a target
        // the (+) can't reach.
        if plannedPlots.contains(where: { !$0.skipped }) {
            return "Set plot centre"
        }
        return "Start plot"
    }

    func cruisePrimaryAction() {
        if let plot = activePlot {
            startAddTree(in: plot)
        } else if let planned = nearestUnvisitedPlannedPlot() {
            // Unvisited planned plots exist: the (+) drives the nearest
            // one's Set-plot-centre flow instead of an ad-hoc start —
            // exactly as tapping that dashed pin → "Set plot centre (GPS)".
            withAnimation(.easeOut(duration: 0.18)) { selectedPinID = nil }
            recordingTarget = planned
        } else {
            // A fresh plot: whatever ring is up belongs to somewhere else.
            armPlotSetup(editing: nil)
            presentingPlotSetup = true
        }
    }

    /// CRUISE PROJECT STRIP text — "<project> · Plot N · M trees" while a
    /// plot is active, else "<project> · No active plot". Folds in the
    /// live tree count the standalone tally pill used to show ("M trees"
    /// unpluralised, matching the retired pill).
    var projectStripText: String {
        let name = currentProject?.name ?? "New project"
        if let plot = activePlot {
            return "\(name) · Plot \(plot.plotNumber) · \(liveTrees(in: plot.id).count) trees"
        }
        return "\(name) · No active plot"
    }

    /// The dark PROJECT pill floating above the (+): tappable into the
    /// project sheet, mono/semibold, one line. Dark-glass chrome (fixed
    /// colours — it sits over satellite imagery, like the cluster
    /// captions) so it reads distinctly from measure's light "Log".
    var projectStrip: some View {
        Button {
            presentingProjectSheet = true
        } label: {
            Text(projectStripText)
                .font(.system(size: 11, weight: .semibold, design: .monospaced))
                .foregroundStyle(Color(red: 0.949, green: 0.961, blue: 0.953)) // #F2F5F3
                .lineLimit(1)
                .padding(.horizontal, 13)
                .padding(.vertical, 7)
                .background(
                    Capsule().fill(Color(red: 6 / 255, green: 9 / 255, blue: 10 / 255)
                        .opacity(0.72)))
                .overlay(Capsule().stroke(Color.white.opacity(0.14), lineWidth: 1))
                .shadow(color: Color.black.opacity(0.18), radius: 5, y: 2)
                .contentShape(Capsule())
        }
        .buttonStyle(CruisePressableStyle())
        .accessibilityLabel("Project: \(currentProject?.name ?? "New project")")
        .accessibilityIdentifier("cruiseMap.projectStrip")
    }

    // MARK: Presentation host

    /// All cruise-mode sheets / covers / pushes / dialogs, attached to
    /// the home's map stack in one place. Presentation FLAGS are only
    /// ever set from cruise-mode UI, so these are inert in measure mode.
    func cruisePresentations<Content: View>(over content: Content) -> some View {
        content
        #if os(iOS)
            .fullScreenCover(isPresented: $presentingPlotSetup,
                             onDismiss: {
                                 // The map-overlay edit target is scoped to
                                 // ONE setup session; leaving it set would
                                 // turn the next "Start plot" into an edit.
                                 editingMapPlotID = nil
                                 reloadCruise()
                             }) { plotSetupCover }
            // Cruise tally loop — the DBH cover saves tree after tree and
            // only closes via the floating back; dismissal refreshes the
            // map's pins + tally.
            .fullScreenCover(isPresented: $presentingCruiseDBH,
                             onDismiss: { reloadCruise() }) { cruiseDBHCover }
            .fullScreenCover(isPresented: $presentingCruiseHeight,
                             onDismiss: { reloadCruise() }) { cruiseHeightCover }
            // Tree-peek thumbnail → full-screen photo viewer.
            .fullScreenCover(item: $cruisePhotoContext) { context in
                CruiseTreePhotoView(context: context)
            }
        #endif
            // HEIGHTS SHEET (plot peek → "Heights · N measured") — the
            // scoped Height cover launches from onDismiss so the two
            // presentations never fight.
            .sheet(item: $heightsSheetTarget, onDismiss: {
                if let request = pendingScopedHeight {
                    pendingScopedHeight = nil
                    chainPlotID = request.plotID
                    chainTreeID = request.treeID
                    presentingCruiseHeight = true
                }
            }) { target in
                if let plot = plots.first(where: { $0.id == target.plotID }) {
                    PlotHeightsSheet(
                        plot: plot,
                        trees: liveTrees(in: plot.id),
                        onMeasure: { tree in
                            pendingScopedHeight = ScopedHeightRequest(
                                plotID: plot.id, treeID: tree.id)
                            heightsSheetTarget = nil
                        })
                    .environmentObject(settings)
                }
            }
            .sheet(isPresented: $presentingProjectSheet, onDismiss: {
                namingNewProject = false
                newProjectName = ""
                if let destination = pendingDestination {
                    pendingDestination = nil
                    pushed = destination
                } else if pendingCruiseSetup {
                    pendingCruiseSetup = false
                    presentingCruiseSetup = true
                }
            }) { projectSheet }
            // Simplified cruise setup (mock ⑥) — a defaulted bottom
            // sheet, not a pushed screen.
            .sheet(isPresented: $presentingCruiseSetup,
                   onDismiss: { reloadCruise() }) {
                if let project = currentProject {
                    CruiseSetupSheet(
                        project: project,
                        mapCentre: CoordinateConversions.LatLon(
                            latitude: camera.latitude,
                            longitude: camera.longitude),
                        onGenerated: { reloadCruise() })
                    .environmentObject(environment)
                }
            }
            // Inline GPS-averaging sheet (mock ⑧) — planned pin → real plot.
            .sheet(item: $recordingTarget) { planned in
                if let project = currentProject {
                    RecordCentreSheet(
                        plannedPlot: planned,
                        project: project,
                        onSaved: { plot in
                            if navTargetPlannedID == planned.id {
                                navTargetPlannedID = nil
                            }
                            withAnimation(.easeOut(duration: 0.18)) {
                                selectedPinID = "plot-\(plot.id.uuidString)"
                            }
                            reloadCruise()
                        })
                    .environmentObject(environment)
                }
            }
            // Returning from a pushed editor (Tree detail, Plot summary…)
            // re-reads the repositories so pins/peeks reflect the edits.
            .onChange(of: pushed) { _, value in
                if value == nil { reloadCruise() }
            }
            .navigationDestination(item: $pushed) { destination in
                destinationView(destination)
            }
            .confirmationDialog(
                "Close this plot?",
                isPresented: Binding(
                    get: { closePlotCandidateID != nil },
                    set: { if !$0 { closePlotCandidateID = nil } }),
                titleVisibility: .visible
            ) {
                Button("Close plot", role: .destructive) {
                    if let id = closePlotCandidateID { closePlot(id: id) }
                    closePlotCandidateID = nil
                }
                Button("Keep tallying", role: .cancel) {
                    closePlotCandidateID = nil
                }
            } message: {
                if let id = closePlotCandidateID,
                   let plot = plots.first(where: { $0.id == id }) {
                    let n = liveTrees(in: plot.id).count
                    Text("Plot \(plot.plotNumber) has \(n) tree\(n == 1 ? "" : "s"). Closing stamps it done — you can reopen from Details.")
                }
            }
            // Delete plot (peek) — cascades trees + photos, then the plot.
            .alert("Delete plot?",
                   isPresented: Binding(
                       get: { deletePlotCandidateID != nil },
                       set: { if !$0 { deletePlotCandidateID = nil } }),
                   presenting: deletePlotCandidateID) { id in
                Button("Delete plot", role: .destructive) {
                    deletePlot(id: id)
                    deletePlotCandidateID = nil
                }
                Button("Cancel", role: .cancel) { deletePlotCandidateID = nil }
            } message: { id in
                if let plot = plots.first(where: { $0.id == id }) {
                    let n = liveTrees(in: plot.id).count
                    Text("Delete Plot \(plot.plotNumber) and its \(n) tree\(n == 1 ? "" : "s")? This can't be undone.")
                }
            }
            // Delete tree (peek) — the tree row + its photo.
            .alert("Delete tree?",
                   isPresented: Binding(
                       get: { deleteTreeCandidateID != nil },
                       set: { if !$0 { deleteTreeCandidateID = nil } }),
                   presenting: deleteTreeCandidateID) { id in
                Button("Delete tree", role: .destructive) {
                    deleteTree(id: id)
                    deleteTreeCandidateID = nil
                }
                Button("Cancel", role: .cancel) { deleteTreeCandidateID = nil }
            } message: { id in
                if let tree = treesByPlot.values.joined()
                    .first(where: { $0.id == id }) {
                    Text("Delete Tree \(tree.treeNumber)? This removes it and its photo from the map. This can't be undone.")
                }
            }
    }

    // MARK: Plot creation (Start plot → sampling-ring AR component)

    #if os(iOS)
    var plotSetupCover: some View {
        NavigationStack {
            SamplingPlotScreen(onSaveCruisePlot: { radiusM in
                // FIELD REPORT F11 — the same screen now serves BOTH "place
                // the first plot" and "come back and change it". Which one
                // it is depends on whether the tally already has a plot, so
                // re-opening from the mini-map can never mint a duplicate
                // Plot N+1 out from under the cruiser.
                //
                // The map's plot overlay (MapHomeScreen+Plot.swift) is a
                // THIRD way in, and it means the same thing: `editingMapPlotID`
                // names the plot being edited, so Save must not mint a
                // duplicate here either.
                // Every RE-OPENED path has to be listed here. A path missing
                // from this test falls through to createCruisePlot and mints
                // a duplicate Plot N+1 out from under the cruiser —
                // `heightPlotSetup` (FIELD REPORT 12) is the newest one.
                //
                // The result is the REFUSAL the plot screen must show, or nil
                // when the save landed and it may close. See `editCruisePlot`
                // for why the refusal travels back here instead of raising the
                // map's `plotSaveRefusal` alert.
                if chainingPlotSetup || heightPlotSetup || editingMapPlotID != nil,
                   editableCruisePlot != nil {
                    return editCruisePlot(radiusM: radiusM)
                } else {
                    return createCruisePlot(radiusM: radiusM)
                }
            })
            .environmentObject(history)
            .environmentObject(settings)
        }
    }
    #endif

    /// The plot a re-opened setup session edits: the one the MAP's plot
    /// overlay named, else the one the tally is measuring into, else the
    /// active plot. nil ⇒ there is nothing to edit and Save creates
    /// instead.
    var editableCruisePlot: Plot? {
        if let id = editingMapPlotID, let hit = plots.first(where: { $0.id == id }) {
            return hit
        }
        if let id = chainPlotID, let hit = plots.first(where: { $0.id == id }) {
            return hit
        }
        return activePlot
    }

    /// FIELD REPORT F11 — apply a re-opened setup session to the EXISTING
    /// plot instead of creating a new one.
    ///
    /// Radius always applies. The CENTRE deliberately does not, unless the
    /// cruiser actually re-placed the ring: a radius-only edit that also
    /// re-stamped the centre would silently teleport the plot to wherever
    /// the cruiser happened to be standing, which is the worst kind of data
    /// loss — invisible. `ActiveSamplingPlot.place(...)` clears
    /// `linkedCruisePlotID`, so "still linked to this plot" is exactly the
    /// test for "the centre was not re-placed".
    ///
    /// RETURNS the refusal to show ON THE PLOT SCREEN, or nil when the save
    /// landed and that screen may close. It deliberately does NOT raise
    /// `plotSaveRefusal`: that alert is attached to the MAP body, and the plot
    /// setup screen is a cover over the map — reached from the cruise scan
    /// screens it is a cover over ANOTHER cover. A host that is still
    /// presenting a modal cannot show an alert, so from the two scan doors
    /// (`chainingPlotSetup`, `heightPlotSetup`) the refusal was raised and
    /// never seen: the radius was written, the centre was correctly refused,
    /// and the cruiser was told nothing. Android's `CruiseStartPlotScreen`
    /// has always kept this message on the plot screen itself and stayed up
    /// so it can be read and retried; this is the same shape, same words.
    /// `plotSaveRefusal` remains for `startPlannedPlotNow`, which is raised
    /// from the map with no cover in the way.
    func editCruisePlot(radiusM: Double) -> String? {
        guard var plot = editableCruisePlot else {
            return createCruisePlot(radiusM: radiusM)
        }
        plot.plotAreaAcres = Float(.pi * radiusM * radiusM / Units.squareMetersPerAcre)

        let store = ActiveSamplingPlot.shared
        let recentred = store.plot != nil && store.linkedCruisePlotID != plot.id
        // FRESHNESS-GATED, exactly as `createCruisePlot` below. A re-centre
        // writes a plot centre, so it is the same act and it answers to the
        // same rule: `latestSnapshot` is never cleared and
        // `LocationService.lastGlobalFix` is a static that outlives the
        // screen, so ungated they hand back the last fix that ever got
        // through — an hour old, a valley away — and this path would stamp
        // it as a real single fix. Moving a plot centre to yesterday's
        // position is the same invisible data loss the `recentred` test
        // exists to prevent, just arrived at from the other side.
        let fix = recentred
            ? FixFreshness.usable(
                location.latestSnapshot ?? LocationService.lastGlobalFix)
            : nil
        if let fix {
            plot.centerLat = fix.latitude
            plot.centerLon = fix.longitude
            // ONE fix, named as one. This path never opened an averaging
            // window, so it may not wear `gpsAveraged`, and the tier comes
            // from the single-fix rule rather than from `classify` with a
            // spread of zero it never measured.
            plot.positionSource = .gpsSingle
            plot.gpsNSamples = 1
            plot.gpsMedianHAccuracyM = Float(fix.horizontalAccuracyM)
            plot.gpsSampleStdXyM = 0
            // Still stored, still exported — just never shown (F9).
            plot.positionTier = GPSAveraging.classifySingleFix(
                horizontalAccuracyM: Float(fix.horizontalAccuracyM))
        }
        let refusedRecentre = recentred && fix == nil

        // The write is the whole point of the button, so a failed one is
        // reported in the cruiser's own words rather than swallowed by a
        // `try?` — same sentence as Android's `save()` catch.
        var storeError: String?
        do {
            _ = try environment.plotRepository.update(plot)
        } catch {
            storeError = "Couldn't save the plot: \(error.localizedDescription)"
        }
        let stored = storeError == nil
        if stored, !refusedRecentre {
            // Re-link so the mini-map trusts the AR-anchor path for YOU
            // against the ring the cruiser is actually looking at — but ONLY
            // when that ring is genuinely this plot's centre. On a refused
            // re-centre the stored centre stayed where it was, so linking
            // would draw YOU against a ring the plot was never moved to.
            // Left unlinked, the mini-map falls through to the GPS path
            // measured from the centre the plot actually has.
            ActiveSamplingPlot.shared.link(cruisePlotID: plot.id)
        }
        reloadCruise()
        // Nothing was written at all — say that, and say nothing about a
        // radius that did not land.
        if let storeError { return storeError }
        // A button that did not do what it looked like it did has to say so.
        // The radius edit landed; the re-placed ring did not become the new
        // centre, and without this the cruiser walks away believing it did.
        // Only reached when `stored`, so the sentence about the radius is only
        // said when the radius really was written.
        if refusedRecentre {
            return "No GPS fix — the plot keeps its recorded "
                + "centre. The radius was saved. Step out for sky and "
                + "try again."
        }
        return nil
    }

    /// Persist the placed ring as a cruise `Plot` in the current
    /// project (creating "Project N" on the fly if none exists — the
    /// benchmark's measure-first rule: setup never blocks measuring).
    /// The new plot is open, so it becomes the ACTIVE plot and the (+)
    /// morphs to "Add tree · Plot N". `ActiveSamplingPlot` stays placed
    /// (the sampling screen anchored it), so DBH/Height overlay the
    /// ring while measuring inside it.
    ///
    /// RETURNS the refusal to show ON THE PLOT SCREEN, or nil when the plot
    /// was created and that screen may close — see `editCruisePlot` for why
    /// the map's alert is not the place for it.
    func createCruisePlot(radiusM: Double) -> String? {
        let project = currentProject ?? autoCreateProject()
        guard let project else {
            return "Couldn't save the plot: there is no project to put it in."
        }
        // FRESHNESS-GATED, and REFUSED when there is nothing usable.
        //
        // This used to fall through to the MAP CAMERA position, which is
        // worse than an obviously-wrong coordinate: it is a real place, near
        // enough to look right, and every tree tallied into the plot
        // inherits it. A plot centre is the anchor for everything measured
        // in the plot, so the only honest answer when the app does not know
        // where it is, is to refuse. Android now refuses for the same
        // reason (it used to save (0, 0) tagged as the best GPS tier).
        guard let fix = FixFreshness.usable(
            location.latestSnapshot ?? LocationService.lastGlobalFix)
        else {
            return "No GPS fix — the plot centre would be saved "
                + "in the wrong place. Step out for sky and try again."
        }
        let number = ((try? environment.plotRepository
            .listByProject(project.id))?.map(\.plotNumber).max() ?? 0) + 1
        let areaAcres = Float(.pi * radiusM * radiusM / Units.squareMetersPerAcre)
        // ONE fix, named as one — see `PositionSource.gpsSingle`. Nothing on
        // this path opens an averaging window, so neither the source nor the
        // tier may be borrowed from the one that does.
        let tier = GPSAveraging.classifySingleFix(
            horizontalAccuracyM: Float(fix.horizontalAccuracyM))
        let plot = Plot(
            id: UUID(),
            projectId: project.id,
            plannedPlotId: nil,
            plotNumber: number,
            centerLat: fix.latitude,
            centerLon: fix.longitude,
            positionSource: .gpsSingle,
            positionTier: tier,
            gpsNSamples: 1,
            gpsMedianHAccuracyM: Float(fix.horizontalAccuracyM),
            gpsSampleStdXyM: 0,
            offsetWalkM: nil,
            slopeDeg: 0,
            aspectDeg: 0,
            plotAreaAcres: areaAcres,
            startedAt: Date(),
            closedAt: nil,
            closedBy: nil,
            notes: "",
            coverPhotoPath: nil,
            panoramaPath: nil)
        // The write is the whole point of the button, so a failed one is
        // reported rather than swallowed by a `try?` — same sentence as
        // Android's `save()` catch. Without this a storage error looked
        // exactly like a saved plot: the screen closed and the cruiser
        // started tallying into a plot that does not exist.
        var storeError: String?
        do {
            _ = try environment.plotRepository.create(plot)
            // Stamp the placed AR ring as THIS cruise plot's centre so
            // the scan screens' mini-map may use the anchor path (the
            // accurate one) for YOU while measuring into this plot.
            ActiveSamplingPlot.shared.link(cruisePlotID: plot.id)
        } catch {
            storeError = "Couldn't save the plot: \(error.localizedDescription)"
        }
        reloadCruise()
        return storeError
    }

    /// FIELD REPORT 17 — open a PLANNED plot on the fix that is live right
    /// now, with no averaging window in the way. Same conversion the
    /// averaging sheet's "Save centre" runs (so the planned pin is marked
    /// visited and the plot opens exactly as it always did); the only
    /// difference is in the position stamp the result carries, which says
    /// `gpsSingle` / one sample rather than borrowing the averaged label.
    ///
    /// REFUSES rather than guesses. With no fix the app still believes,
    /// there is nothing to put in `centerLat`/`centerLon` — and a plot
    /// centre is the anchor for every tree tallied into the plot, so the
    /// wrong one is worse than none. Same refusal, word for word, as the
    /// AR "Start plot" path.
    func startPlannedPlotNow(_ planned: PlannedPlot) {
        guard let project = currentProject else { return }
        guard let result = singleFixCentre(location.latestSnapshot) else {
            plotSaveRefusal = "No GPS fix — the plot centre would be saved "
                + "in the wrong place. Step out for sky and try again."
            return
        }
        do {
            let created = try convertPlannedToActivePlot(
                environment: environment,
                project: project,
                planned: planned,
                result: result)
            if navTargetPlannedID == planned.id { navTargetPlannedID = nil }
            HapticFeedback.play(.success)
            withAnimation(.easeOut(duration: 0.18)) {
                selectedPinID = "plot-\(created.id.uuidString)"
            }
        } catch {
            plotSaveRefusal =
                "Storage error: \(error.localizedDescription). The centre was not saved — try again."
        }
        reloadCruise()
    }

    /// One-time naming is the project sheet's job; a cruiser who taps
    /// "Start plot" before naming anything still gets a project —
    /// auto-named, renameable later.
    func autoCreateProject() -> Project? {
        createProject(named: "Project \(projects.count + 1)")
    }

    @discardableResult
    func createProject(named name: String) -> Project? {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        let now = Date()
        // Calibration starts at the spec's identity values until the
        // cruiser runs Calibration.
        let project = Project(
            id: UUID(),
            name: trimmed,
            description: "",
            owner: "",
            createdAt: now,
            updatedAt: now,
            units: settings.unitSystem,
            breastHeightConvention: .uphill,
            slopeCorrection: true,
            lidarBiasMm: 0,
            depthNoiseMm: 5,
            dbhCorrectionAlpha: 0,
            dbhCorrectionBeta: 1,
            vioDriftFraction: 0.02)
        guard let created = try? environment.projectRepository.create(project)
        else { return nil }
        settings.currentCruiseProjectID = created.id
        reloadCruise()
        return created
    }

    // MARK: Add-tree quick-tally loop (diameter loop through the Accept path)

    /// "Add tree" enters the DIAMETER LOOP: the DBH cover saves a tree
    /// on every Accept, auto-increments the target number, and resets
    /// itself for the next trunk — no Height chain, no return to the
    /// map until the floating back. Per-tree height on demand lives on
    /// the tree peek / heights sheet instead.
    func startAddTree(in plot: Plot) {
        // The tally is switching to THIS plot, so a ring belonging to any
        // other one stops being the boundary being measured — and the DBH /
        // Height screens draw whatever ring is placed as their subdued
        // overlay. An "Add tree" from a plot peek can target an OLDER open
        // plot than the last-placed ring, which is exactly the case that
        // survives `reconcileSamplingRing` (both plots are open).
        samplingPlot.dropIfLinkedElsewhere(than: plot.id)
        chainPlotID = plot.id
        chainTreeNumber = nextTreeNumber(in: plot.id)
        chainTreeID = nil
        withAnimation(.easeOut(duration: 0.18)) { selectedPinID = nil }
        presentingCruiseDBH = true
    }

    /// Height screen scoped to one EXISTING tree (tree peek / heights
    /// sheet). Reuses the tally plumbing: `chainTreeID` is the tree the
    /// accepted height lands on.
    func startScopedHeight(plotID: UUID, treeID: UUID) {
        chainPlotID = plotID
        chainTreeID = treeID
        withAnimation(.easeOut(duration: 0.18)) { selectedPinID = nil }
        presentingCruiseHeight = true
    }

    /// Tally-toast Undo: delete the just-saved tree row (and its photo
    /// via the store) and step the auto number back.
    func undoLastTally() {
        guard let id = chainTreeID,
              let tree = try? environment.treeRepository.read(id: id)
        else { return }
        if let photo = tree.photoPath {
            MeasurePhotoStore.delete(photo)
        }
        try? environment.treeRepository.hardDelete(id: id)
        chainTreeID = nil
        reloadCruise()
        if let plotID = chainPlotID {
            chainTreeNumber = nextTreeNumber(in: plotID)
        }
    }

    /// The project's REAL calibration — the cruise chain must never run
    /// on `.identity` when the project has wall/cylinder fits.
    func calibration(from project: Project?) -> ProjectCalibration {
        guard let project else { return .identity }
        return ProjectCalibration(
            depthNoiseMm: project.depthNoiseMm,
            dbhCorrectionAlpha: project.dbhCorrectionAlpha,
            dbhCorrectionBeta: project.dbhCorrectionBeta,
            vioDriftFraction: project.vioDriftFraction)
    }

    /// Plot mini-map payload for the plot the tally loop is measuring
    /// into: plot number + radius + centre fix, and the live trees
    /// tinted by confidence (warn for any yellow/red DBH or Height
    /// tier). The loop's Accept reloads the snapshot before advancing,
    /// so the widget gains each new dot immediately.
    ///
    /// EVERY live tree is handed over, including ones with no position
    /// at all. Filtering them out here is what used to make an unplaced
    /// tree indistinguishable from a plot with fewer trees in it; the
    /// map decides what it can draw (`PlotMiniMapInfo.placedTrees`) and
    /// the enlarged view tells the cruiser how many it had to leave out.
    func cruiseMiniMapInfo(plotID: UUID?) -> PlotMiniMapInfo? {
        guard let plotID,
              let plot = plots.first(where: { $0.id == plotID })
        else { return nil }
        let trees = liveTrees(in: plotID)
        let dots: [PlotMiniMapInfo.TreeDot] = trees.map { tree in
            let heightWarn = tree.heightConfidence.map { $0 != .green } ?? false
            return PlotMiniMapInfo.TreeDot(
                number: tree.treeNumber,
                latitude: tree.latitude,
                longitude: tree.longitude,
                bearingFromCenterDeg: tree.bearingFromCenterDeg.map(Double.init),
                distanceFromCenterM: tree.distanceFromCenterM.map(Double.init),
                warn: tree.dbhConfidence != .green || heightWarn)
        }
        // No centre (cleared by the map overlay's "Remove plot") means no
        // ENU origin: the card falls back to the AR-anchor path for YOU
        // and drops the GPS-positioned tree dots, rather than laying the
        // plot out around null island.
        let hasCentre = plot.hasCentre
        return PlotMiniMapInfo(
            plotID: plot.id,
            plotNumber: plot.plotNumber,
            radiusM: plotRadiusM(plot),
            centerLat: hasCentre ? plot.centerLat : nil,
            centerLon: hasCentre ? plot.centerLon : nil,
            treeCount: trees.count,
            trees: dots,
            unitSystem: settings.unitSystem)
    }

    #if os(iOS)
    /// The cruise DIAMETER LOOP cover. Accept saves the tree through the
    /// existing path (calibration / GPS / photo / metadata), reloads the
    /// snapshot so the mini-map + border chip track the new dot, and
    /// advances the target number — the screen resets itself and shows
    /// the Undo toast. The floating back exits the loop.
    ///
    /// FIELD REPORT F10 — with `measureHeightAfterDiameter` on (the default)
    /// the accept ALSO opens Height for the tree just saved, presented over
    /// this screen so the tally survives underneath. Height's Accept and its
    /// Skip both come straight back here, already targeting the next tree.
    /// With the setting off the loop behaves exactly as it did.
    ///
    /// The top-right mini-map opens the enlarged plot view, whose "Edit
    /// plot" button lands here — also nested, so radius / centre stay
    /// editable after the first placement.
    ///
    /// WHY NESTED, not the dismiss-then-present two-step the quick-measure
    /// "Full measurement" chain uses: that chain ENDS at the map, this one
    /// has to come BACK to the tally. Re-presenting the tally cover would
    /// rebuild `DBHScanViewModel` and restart AR for every single tree.
    /// Nesting is safe because there is ONE AR session and it is refcounted:
    /// `ARKitSessionManager.attach` applies the incoming screen's
    /// configuration while the tally stays attached, and `detach` re-applies
    /// the survivor's — so closing Height (or plot setup) puts `.dbhScan`
    /// straight back. Each view model holds its own client token precisely
    /// for this overlap.
    var cruiseDBHCover: some View {
        NavigationStack {
            DBHScanScreen(
                viewModel: DBHScanViewModel(
                    calibration: calibration(from: currentProject)),
                onAccept: { result, meta in
                    // The save decides everything downstream. A FAILED create
                    // reports false, and the scan screen keeps the reading on
                    // screen under its "NOT saved" banner instead of the loop
                    // advancing as if a tree existed.
                    let stored = saveChainDBH(result, meta: meta)
                    reloadCruise()
                    if let plotID = chainPlotID {
                        chainTreeNumber = nextTreeNumber(in: plotID)
                    }
                    // Chain into Height for the tree that was just written.
                    // Gated on THIS save succeeding, not merely on
                    // `chainTreeID` being non-nil: that id is stale after a
                    // failure, and passing it on is how a height was recorded
                    // against the previous tree.
                    if stored, settings.measureHeightAfterDiameter,
                       chainTreeID != nil {
                        chainingHeight = true
                    }
                    return stored
                },
                cruisePlotInfo: cruiseMiniMapInfo(plotID: chainPlotID),
                tallyTreeNumber: chainTreeNumber,
                onUndoTally: { undoLastTally() },
                projectID: currentProject?.id.uuidString,
                onEditPlot: {
                    // The setup session about to open rewrites the plot the
                    // tally is measuring into — a ring linked to any other
                    // plot is not this plot's centre and must not be read as
                    // a re-placement (see `armPlotSetup`).
                    armPlotSetup(editing: chainPlotID)
                    chainingPlotSetup = true
                })
            .environmentObject(settings)
            .fullScreenCover(isPresented: $chainingHeight,
                             onDismiss: { reloadCruise() }) {
                cruiseChainedHeightCover
            }
            .fullScreenCover(isPresented: $chainingPlotSetup,
                             onDismiss: { reloadCruise() }) { plotSetupCover }
        }
    }

    /// Height for the tree the tally just saved (F10). Distinct from
    /// `cruiseHeightCover` only in how it closes: this one is nested over
    /// the diameter loop, so both Accept and Skip clear `chainingHeight`
    /// and drop back onto the tally rather than onto the map.
    var cruiseChainedHeightCover: some View {
        NavigationStack {
            HeightScanScreen(
                viewModel: HeightScanViewModel(
                    calibration: calibration(from: currentProject)),
                onAccept: { result, meta in
                    let stored = saveChainHeight(result, meta: meta)
                    if stored { chainingHeight = false }
                    return stored
                },
                onSkip: { chainingHeight = false },
                cruisePlotInfo: cruiseMiniMapInfo(plotID: chainPlotID),
                // Raw-capture join keys: the height session measures a KNOWN
                // tree, so its bundle must carry that tree's number.
                projectID: currentProject?.id.uuidString,
                treeNumber: chainHeightTreeNumber,
                onEditPlot: {
                    armPlotSetup(editing: chainPlotID)
                    heightPlotSetup = true
                })
            .environmentObject(history)
            .environmentObject(settings)
            .fullScreenCover(isPresented: $heightPlotSetup,
                             onDismiss: { reloadCruise() }) { plotSetupCover }
        }
    }

    var cruiseHeightCover: some View {
        NavigationStack {
            HeightScanScreen(
                viewModel: HeightScanViewModel(
                    calibration: calibration(from: currentProject)),
                onAccept: { result, meta in
                    // Dismiss ONLY when the height actually landed on the
                    // tree row. This used to pop unconditionally, so a
                    // dropped reading looked identical to a saved one and
                    // the tree peek kept showing DBH alone.
                    let stored = saveChainHeight(result, meta: meta)
                    if stored { presentingCruiseHeight = false }
                    return stored
                },
                cruisePlotInfo: cruiseMiniMapInfo(plotID: chainPlotID),
                // Raw-capture join keys: the height session measures a KNOWN
                // tree, so its bundle must carry that tree's number.
                projectID: currentProject?.id.uuidString,
                treeNumber: chainHeightTreeNumber,
                onEditPlot: {
                    armPlotSetup(editing: chainPlotID)
                    heightPlotSetup = true
                })
            .environmentObject(history)
            .environmentObject(settings)
            .fullScreenCover(isPresented: $heightPlotSetup,
                             onDismiss: { reloadCruise() }) { plotSetupCover }
        }
    }

    /// Accepted DBH → create the cruise Tree. Species defaults to the
    /// plot's most recent species when the meta sheet was skipped
    /// (zero-typing rule); GPS + auto-photo arrive on the metadata.
    ///
    /// Returns TRUE only when the row actually reached the repository, and
    /// `chainTreeID` is left pointing at the created row ONLY then. FIELD FIX:
    /// the create was a bare `try?` with no else, so a failed write left the
    /// PREVIOUS tree's id in `chainTreeID` — harmless until the DBH → Height
    /// chain started reading it. The chain would then open Height on the older
    /// tree, and both `saveChainHeight` and the raw-capture join key
    /// `chainHeightTreeNumber` resolved to it, silently recording a height
    /// against the wrong tree. A failed diameter save must never open Height.
    @discardableResult
    func saveChainDBH(_ result: DBHResult,
                      meta: DBHScanScreen.ScanMetadata) -> Bool {
        guard let plotID = chainPlotID else {
            chainTreeID = nil
            return false
        }
        let now = Date()
        let species = meta.speciesCode ?? lastSpeciesCode(in: plotID) ?? ""
        let tree = Tree(
            id: UUID(),
            plotId: plotID,
            treeNumber: chainTreeNumber,
            speciesCode: species,
            status: .live,
            dbhCm: result.diameterCm,
            dbhMethod: result.method,
            dbhSigmaMm: result.sigmaRmm,
            dbhRmseMm: result.rmseMm,
            dbhCoverageDeg: result.arcCoverageDeg,
            dbhNInliers: result.nInliers,
            dbhConfidence: result.confidence,
            dbhIsIrregular: false,
            // Which estimator produced this diameter. Without it a corpus
            // mixing bracket and auto fits cannot be split at analysis time,
            // and the bracket is now the default path.
            dbhCaptureMode: meta.captureMode,
            heightM: nil,
            heightMethod: nil,
            heightSource: nil,
            heightSigmaM: nil,
            heightDHM: nil,
            heightAlphaTopDeg: nil,
            heightAlphaBaseDeg: nil,
            heightConfidence: nil,
            // PLOT-LOCAL POSITION, both halves. Everything that draws a
            // tree relative to its plot (the mini-map card, the enlarged
            // plot view) PREFERS this pair over the tree's own GPS fix —
            // it is already in the plot's frame. Writing the distance and
            // leaving the bearing nil made that preferred source dead
            // code: the pair is only usable together, so every tree fell
            // through to the GPS branch. Both come off the same two
            // coordinates (the capture fix and the plot centre), which
            // are both in hand right here.
            bearingFromCenterDeg: bearingFromPlotCenter(plotID: plotID, meta: meta),
            distanceFromCenterM: distanceFromPlotCenter(plotID: plotID, meta: meta),
            boundaryCall: nil,
            crownClass: nil,
            damageCodes: meta.damageCodes,
            isMultistem: false,
            parentTreeId: nil,
            notes: meta.note,
            photoPath: meta.photoPath,
            rawScanPath: result.rawPointsPath,
            createdAt: now,
            updatedAt: now,
            deletedAt: nil,
            latitude: meta.latitude,
            longitude: meta.longitude)
        do {
            let created = try environment.treeRepository.create(tree)
            chainTreeID = created.id
            return true
        } catch {
            // The scoped tree is whatever this save wrote — and it wrote
            // nothing. Leaving the previous id in place is what let a height
            // land on the wrong tree.
            chainTreeID = nil
            return false
        }
    }

    /// Accepted Height → update the scoped tree (tree peek / heights
    /// sheet target).
    ///
    /// Returns TRUE only when the reading actually reached the row. FIELD FIX:
    /// this used to return Void and had three silent exits — no scoped tree
    /// (`chainTreeID` nil, e.g. after `undoLastTally()` or `startAddTree`), a
    /// row the repository could not read, and a `try?`-swallowed update error.
    /// The caller dismissed the cover straight afterwards regardless, so every
    /// one of those looked exactly like a successful save: the map came back,
    /// the tree peek still showed DBH alone, and nothing anywhere said the
    /// height had been thrown away. Each exit is now reported, and a
    /// successful write refreshes the snapshot the peek reads before the
    /// cover's own `onDismiss` gets a chance to race it.
    @discardableResult
    func saveChainHeight(_ result: HeightResult,
                         meta: HeightScanScreen.ScanMetadata) -> Bool {
        guard let id = chainTreeID else { return false }
        guard var tree = try? environment.treeRepository.read(id: id)
        else { return false }
        tree.heightM = result.heightM
        tree.heightMethod = result.method
        tree.heightSource = "measured"
        tree.heightSigmaM = result.sigmaHm
        tree.heightDHM = result.dHm
        tree.heightAlphaTopDeg = result.alphaTopRad * 180 / .pi
        tree.heightAlphaBaseDeg = result.alphaBaseRad * 180 / .pi
        tree.heightConfidence = result.confidence
        if tree.photoPath == nil { tree.photoPath = meta.photoPath }
        if tree.latitude == nil {
            tree.latitude = meta.latitude
            tree.longitude = meta.longitude
        }
        tree.updatedAt = Date()
        do {
            _ = try environment.treeRepository.update(tree)
        } catch {
            return false
        }
        // Re-read the plot's trees NOW so the tree peek can never render the
        // pre-write snapshot of this tree.
        reloadCruise()
        return true
    }
    #endif

    func lastSpeciesCode(in plotID: UUID) -> String? {
        liveTrees(in: plotID)
            .sorted { $0.createdAt > $1.createdAt }
            .first { !$0.speciesCode.isEmpty }?
            .speciesCode
    }

    /// Horizontal metres between the capture fix and the plot centre —
    /// the tally sheet's hand-typed field, auto-computed.
    func distanceFromPlotCenter(plotID: UUID,
                                meta: DBHScanScreen.ScanMetadata) -> Float? {
        guard let lat = meta.latitude, let lon = meta.longitude,
              let plot = plots.first(where: { $0.id == plotID }),
              plot.hasCentre
        else { return nil }
        let d = CoordinateConversions.haversineMeters(
            CoordinateConversions.LatLon(latitude: lat, longitude: lon),
            CoordinateConversions.LatLon(latitude: plot.centerLat,
                                         longitude: plot.centerLon))
        return Float(d)
    }

    /// Compass bearing FROM the plot centre TO the capture fix, degrees
    /// clockwise from true north — the other half of the plot-local
    /// position stored on a tree, and the direction the mini-map and the
    /// enlarged plot view lay the tree's dot out along.
    ///
    /// Same two coordinates as `distanceFromPlotCenter`, same nil
    /// conditions: without a capture fix — or without a plot centre to
    /// measure from — there is no bearing to record, and a guessed one
    /// would place a stem somewhere it never stood.
    func bearingFromPlotCenter(plotID: UUID,
                               meta: DBHScanScreen.ScanMetadata) -> Float? {
        guard let lat = meta.latitude, let lon = meta.longitude,
              let plot = plots.first(where: { $0.id == plotID }),
              plot.hasCentre
        else { return nil }
        let bearing = CoordinateConversions.initialBearingDegrees(
            from: CoordinateConversions.LatLon(latitude: plot.centerLat,
                                               longitude: plot.centerLon),
            to: CoordinateConversions.LatLon(latitude: lat, longitude: lon))
        guard bearing.isFinite else { return nil }
        return Float(bearing)
    }

    // MARK: Plot peek (mock ②)

    static let cruisePeekTimeFormatter: DateFormatter = {
        let df = DateFormatter()
        df.locale = Locale(identifier: "en_US_POSIX")
        df.dateFormat = "HH:mm"
        return df
    }()

    static let cruisePeekDateFormatter: DateFormatter = {
        let df = DateFormatter()
        df.locale = Locale(identifier: "en_US_POSIX")
        df.dateFormat = "d MMM · HH:mm"
        return df
    }()

    func plotRadiusM(_ plot: Plot) -> Double {
        sqrt(Double(plot.plotAreaAcres) * Units.squareMetersPerAcre / .pi)
    }

    func plotPeekCard(for plot: Plot) -> some View {
        let trees = liveTrees(in: plot.id)
        let stats = plotStats(for: plot, trees: trees)
        let isClosed = plot.closedAt != nil
        // Density basis follows the active unit system (imperial per acre,
        // metric per hectare) so the manual Units toggle wins; the engine
        // computes per acre, so scale for display.
        let areaUnit = settings.unitSystem.areaUnit
        let densityFactor = areaUnit.perAcreDensityFactor
        return VStack(spacing: 0) {
            RoundedRectangle(cornerRadius: 2)
                .fill(ForestixPalette.divider)
                .frame(width: 36, height: 4)
                .padding(.bottom, 10)

            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text("Plot \(plot.plotNumber)")
                    .font(.system(size: 16, weight: .bold))
                    .foregroundStyle(ForestixPalette.textPrimary)
                statusChip(closed: isClosed)
                Spacer(minLength: 4)
                Text(String(format: "%.1f m radius · %@",
                            plotRadiusM(plot),
                            Self.cruisePeekTimeFormatter.string(from: plot.startedAt)))
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundStyle(ForestixPalette.textTertiary)
                    .lineLimit(1)
            }
            .padding(.bottom, 10)

            // Live stats strip — the old PlotTally strip folded into
            // the peek. Per-acre expansion via the InventoryEngine.
            HStack(spacing: 0) {
                // Labels say what the number is. "BA"/"TPA"/"QMD" are index
                // abbreviations a cruiser is never taught anywhere in the
                // app; basal area is kept forestry vocabulary, so it is
                // spelled, not renamed. TPA also silently became TPH with
                // the units setting — the label now names the unit outright.
                statCell("TREES", "\(stats.liveTreeCount)", nil, divided: true)
                statCell("BASAL AREA", String(format: "%.1f", Double(stats.baPerAcreM2) * densityFactor),
                         areaUnit.densityLabel("m²"), divided: true)
                statCell(areaUnit == .hectare ? "TREES/HA" : "TREES/AC",
                         String(format: "%.0f", Double(stats.tpa) * densityFactor),
                         areaUnit.densitySuffix, divided: true)
                statCell("MEAN DBH", String(format: "%.1f", stats.qmdCm),
                         "cm", divided: false)
            }
            .background(
                RoundedRectangle(cornerRadius: ForestixRadius.card,
                                 style: .continuous)
                    .stroke(ForestixPalette.divider, lineWidth: 1))

            VStack(spacing: ForestixSpace.xs) {
                if !isClosed {
                    Button {
                        startAddTree(in: plot)
                    } label: {
                        Text("Add tree · Tree \(nextTreeNumber(in: plot.id))")
                            .font(.system(size: 15, weight: .bold))
                            .foregroundStyle(ForestixPalette.primaryInk)
                            .frame(maxWidth: .infinity, minHeight: 54)
                            .background(
                                RoundedRectangle(cornerRadius: ForestixRadius.card,
                                                 style: .continuous)
                                    .fill(ForestixPalette.primary))
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(CruisePressableStyle())
                    .accessibilityIdentifier("cruiseMap.plotPeek.addTree")
                }

                // PLOT SAMPLE HEIGHTS — full-width secondary into the
                // heights sheet: the plot's measured (tree, DBH, height)
                // pairs + on-demand height measurement + the pooled-curve
                // status. LOCKED string "Heights · N measured".
                Button {
                    heightsSheetTarget = HeightsSheetTarget(plotID: plot.id)
                } label: {
                    Text("Heights · \(plotHeightsPairCount(in: plot.id)) measured")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(ForestixPalette.textPrimary)
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .background(
                            RoundedRectangle(cornerRadius: ForestixRadius.control,
                                             style: .continuous)
                                .stroke(ForestixPalette.divider, lineWidth: 1))
                        .contentShape(Rectangle())
                }
                .buttonStyle(CruisePressableStyle())
                .accessibilityIdentifier("cruiseMap.plotPeek.heights")

                HStack(spacing: ForestixSpace.xs) {
                    if !isClosed {
                        Button {
                            closePlotCandidateID = plot.id
                        } label: {
                            Text("Close plot")
                                .font(.system(size: 14, weight: .semibold))
                                .foregroundStyle(ForestixPalette.textPrimary)
                                .frame(maxWidth: .infinity, minHeight: 44)
                                .background(
                                    RoundedRectangle(cornerRadius: ForestixRadius.control,
                                                     style: .continuous)
                                        .stroke(ForestixPalette.divider, lineWidth: 1))
                                .contentShape(Rectangle())
                        }
                        .buttonStyle(CruisePressableStyle())
                        .accessibilityIdentifier("cruiseMap.plotPeek.close")
                    }

                    Button {
                        pushed = .plotDetails(plot.id)
                    } label: {
                        Text("Details")
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundStyle(ForestixPalette.textPrimary)
                            .frame(maxWidth: .infinity, minHeight: 44)
                            .background(
                                RoundedRectangle(cornerRadius: ForestixRadius.control,
                                                 style: .continuous)
                                    .stroke(ForestixPalette.divider, lineWidth: 1))
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(CruisePressableStyle())
                    .accessibilityIdentifier("cruiseMap.plotPeek.details")
                }

                // Hard removal from the map (confirmed) — cascades the
                // plot's trees + photos, then the plot. Explicit button.
                Button(role: .destructive) {
                    deletePlotCandidateID = plot.id
                } label: {
                    Text("Delete plot")
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
                .buttonStyle(CruisePressableStyle())
                .accessibilityIdentifier("cruiseMap.plotPeek.delete")
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
        .accessibilityIdentifier("cruiseMap.plotPeek")
    }

    /// One shared status colour everywhere (mock rule 5): active =
    /// accent, done = ok green — identical on pin, chip, list.
    func statusChip(closed: Bool) -> some View {
        let color = closed ? ForestixPalette.confidenceOk : ForestixPalette.accent
        return HStack(spacing: 4) {
            Circle().fill(color).frame(width: 6, height: 6)
            Text(closed ? "CLOSED" : "ACTIVE")
                .font(.system(size: 10, weight: .bold))
                .tracking(0.6)
        }
        .foregroundStyle(color)
        .padding(.horizontal, 7)
        .padding(.vertical, 2)
        .background(
            RoundedRectangle(cornerRadius: ForestixRadius.chip)
                .fill(color.opacity(0.12)))
    }

    func statCell(_ label: String, _ value: String,
                  _ unit: String?, divided: Bool) -> some View {
        VStack(spacing: 2) {
            Text(label)
                .font(.system(size: 9, weight: .bold))
                .tracking(0.7)
                .foregroundStyle(ForestixPalette.textTertiary)
                // Spelled-out labels ("BASAL AREA", "TREES/HA") are wider
                // than the old initialisms; scale rather than wrap so a
                // four-cell strip stays one line on a 360 pt phone.
                .lineLimit(1)
                .allowsTightening(true)
                .minimumScaleFactor(0.7)
            HStack(alignment: .firstTextBaseline, spacing: 2) {
                Text(value)
                    .font(.system(size: 14.5, weight: .bold, design: .monospaced))
                    .foregroundStyle(ForestixPalette.textPrimary)
                if let unit {
                    Text(unit)
                        .font(.system(size: 9, weight: .semibold, design: .monospaced))
                        .foregroundStyle(ForestixPalette.textTertiary)
                }
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 9)
        .overlay(alignment: .trailing) {
            if divided {
                Rectangle().fill(ForestixPalette.divider).frame(width: 0.5)
            }
        }
    }

    /// Live per-acre stats via the engine. Projects that skipped the
    /// formal cruise design get a synthesized fixed-area design — the
    /// engine only consults plotType/baf, and the expansion factor
    /// comes from the plot's own recorded area. Missing heights are
    /// estimated from the pooled H–D relation (plot pairs first, stand
    /// fallback) so the volume terms track the height sampling.
    func plotStats(for plot: Plot, trees: [Tree]) -> PlotStats {
        PlotStatsCalculator.compute(
            plot: plot,
            cruiseDesign: effectiveDesign(),
            trees: trees,
            species: speciesByCode,
            volumeEquations: [:],
            hdFits: pooledHDFits(plotTrees: trees))
    }

    // MARK: Pooled H–D relation (heights sheet + volume imputation)

    /// Fit-eligible (DBH, height) pairs of a plot — the heights sheet's
    /// list and the "Heights · N measured" count (matches Android's
    /// PooledHeights.pairs gate).
    func plotHeightsPairCount(in plotID: UUID) -> Int {
        Self.hdPairs(liveTrees(in: plotID)).count
    }

    /// Fit-eligible (DBH, height) pairs — the same cleaning the engine
    /// applies (positive DBH, height above breast height).
    static func hdPairs(_ trees: [Tree]) -> [(dbhCm: Float, heightM: Float)] {
        trees.compactMap { tree in
            guard tree.deletedAt == nil, tree.dbhCm > 0,
                  let h = tree.heightM, h > 1.3 else { return nil }
            return (dbhCm: tree.dbhCm, heightM: h)
        }
    }

    /// POOLED Näslund fit via the existing engine (`HDModel.fit`), no
    /// species dimension: this plot's pairs when ≥3, else all loaded
    /// plots' pairs (stand fallback) when ≥3, else nil. The §7.4
    /// per-species machinery (n ≥ 8, persisted on plot close) is
    /// unchanged — this pooled relation is the early-tally bridge.
    func pooledHDFit(plotTrees: [Tree]) -> HDModel.Fit? {
        let plotPairs = Self.hdPairs(plotTrees)
        if plotPairs.count >= 3,
           let fit = try? HDModel.fit(observations: plotPairs, minN: 3) {
            return fit
        }
        let standPairs = Self.hdPairs(treesByPlot.values.flatMap { $0 })
        if standPairs.count >= 3,
           let fit = try? HDModel.fit(observations: standPairs, minN: 3) {
            return fit
        }
        return nil
    }

    /// Effective fit map for `PlotStatsCalculator`: the persisted §7.4
    /// per-species fits win; the pooled fit fills every species present
    /// on the plot (including the blank code) that lacks one — the
    /// Android PooledHeights.overlay contract.
    func pooledHDFits(plotTrees: [Tree]) -> [String: HDModel.Fit] {
        var out: [String: HDModel.Fit] = [:]
        if let project = currentProject,
           let persisted = try? environment.hdFitRepository
               .listByProject(project.id) {
            for fit in persisted {
                if let f = HDModel.Fit.fromCoefficients(
                    fit.coefficients, nObs: fit.nObs, rmse: fit.rmse) {
                    out[fit.speciesCode] = f
                }
            }
        }
        if let pooled = pooledHDFit(plotTrees: plotTrees) {
            for code in Set(plotTrees.map(\.speciesCode)) where out[code] == nil {
                out[code] = pooled
            }
        }
        return out
    }

    func effectiveDesign() -> CruiseDesign {
        if let project = currentProject,
           let design = (try? environment.cruiseDesignRepository
               .forProject(project.id))?.first {
            return design
        }
        return CruiseDesign(
            id: UUID(),
            projectId: currentProject?.id ?? UUID(),
            plotType: .fixedArea,
            plotAreaAcres: nil,
            baf: nil,
            samplingScheme: .manual,
            gridSpacingMeters: nil)
    }

    /// Toggle a planned plot's `skipped` flag (inaccessible — cliff, water,
    /// private land). Mirrors RecordCentreSheet's mutate → persist → refresh:
    /// a skipped plot stays visible (still in `plannedPlots`, since skipped is
    /// not visited) but is excluded from the (+) nearest-unvisited navigation
    /// and renders with the warn tint + "SKIP" badge.
    func setPlannedSkipped(_ planned: PlannedPlot, _ value: Bool) {
        var p = planned
        p.skipped = value
        _ = try? environment.plannedPlotRepository.update(p)
        reloadCruise()
    }

    /// Stamp closedAt — the ring turns done-green. Validation lives in
    /// Details (PlotSummaryScreen); the peek's close is one confirm.
    func closePlot(id: UUID) {
        guard var plot = plots.first(where: { $0.id == id }) else { return }
        plot.closedAt = Date()
        plot.closedBy = "field"
        _ = try? environment.plotRepository.update(plot)
        reloadCruise()
    }

    /// Hard-remove a plot AND its trees from the map (peek "Delete plot").
    /// Cascades every tree row (including soft-deleted, so no orphans) and
    /// its photo, then the plot row. The pin/peek dismiss after.
    func deletePlot(id: UUID) {
        let trees = (try? environment.treeRepository
            .listByPlot(id, includeDeleted: true)) ?? []
        for tree in trees {
            if let photo = tree.photoPath { MeasurePhotoStore.delete(photo) }
            try? environment.treeRepository.hardDelete(id: tree.id)
        }
        try? environment.plotRepository.delete(id: id)
        withAnimation(.easeOut(duration: 0.18)) { selectedPinID = nil }
        reloadCruise()
    }

    /// Hard-remove a single cruise tree (tree peek "Delete tree") and its
    /// photo — the user wants it gone from the map, not soft-deleted. Pin/
    /// peek dismiss after.
    func deleteTree(id: UUID) {
        if let tree = try? environment.treeRepository.read(id: id,
                                                           includeDeleted: true),
           let photo = tree.photoPath {
            MeasurePhotoStore.delete(photo)
        }
        try? environment.treeRepository.hardDelete(id: id)
        withAnimation(.easeOut(duration: 0.18)) { selectedPinID = nil }
        reloadCruise()
    }

    // MARK: Tree peek (mock ④)

    func treePeekCard(for tree: Tree) -> some View {
        let system = settings.unitSystem
        let plot = plots.first { $0.id == tree.plotId }
        return VStack(spacing: 0) {
            RoundedRectangle(cornerRadius: 2)
                .fill(ForestixPalette.divider)
                .frame(width: 36, height: 4)
                .padding(.bottom, 10)

            HStack(alignment: .firstTextBaseline, spacing: 10) {
                Text(tree.speciesCode.isEmpty
                     ? "Tree \(tree.treeNumber)"
                     : "Tree \(tree.treeNumber) · \(RegionalSpecies.name(forCode: tree.speciesCode))")
                    .font(.system(size: 16, weight: .bold))
                    .foregroundStyle(ForestixPalette.textPrimary)
                    .lineLimit(1)
                Spacer(minLength: 4)
                Text(peekTreeSubtitle(tree, plot: plot))
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundStyle(ForestixPalette.textTertiary)
                    .lineLimit(1)
            }
            .padding(.bottom, 10)

            HStack(alignment: .top, spacing: ForestixSpace.sm) {
                // Tappable thumbnail → full-screen viewer (reuses the
                // MeasurePhotoStore path); disabled with no photo.
                Button {
                    if let path = tree.photoPath {
                        cruisePhotoContext = CruisePhotoContext(
                            photoPath: path,
                            title: tree.speciesCode.isEmpty
                                ? "Tree \(tree.treeNumber)"
                                : "Tree \(tree.treeNumber) · \(RegionalSpecies.name(forCode: tree.speciesCode))",
                            subtitle: peekTreeSubtitle(tree, plot: plot))
                    }
                } label: {
                    cruisePhotoThumb(tree.photoPath)
                }
                .buttonStyle(CruisePressableStyle())
                .disabled(tree.photoPath == nil)
                .accessibilityLabel("View photo")
                .accessibilityIdentifier("cruiseMap.treePeek.photoThumb")
                // DBH + HEIGHT only — there is deliberately no CROWN row.
                // The quick-measure peek has one because QuickMeasureEntry
                // carries a `.crown` kind; the cruise Tree has no crown
                // width/height fields, and both platforms hide "Measure
                // crown" inside a cruise-scoped Height session for exactly
                // that reason, so a crown can never exist for a cruise tree.
                // Android's cruise peek matches (DBH + HEIGHT).
                VStack(spacing: 0) {
                    metricRow(
                        label: "DBH",
                        value: MeasurementFormatter.diameter(cm: Double(tree.dbhCm),
                                                             in: system),
                        tier: tree.dbhConfidence.rawValue,
                        divided: tree.heightM != nil)
                    if let h = tree.heightM {
                        metricRow(
                            label: "HEIGHT",
                            value: MeasurementFormatter.height(m: Double(h),
                                                               in: system),
                            tier: tree.heightConfidence?.rawValue ?? "green",
                            divided: false)
                    }
                }
                .frame(maxWidth: .infinity)
            }

            // Post-hoc detail chips — read-only here; "Edit this tree" is
            // the editor. Never a gate.
            HStack(spacing: 6) {
                detailChip("SPECIES", tree.speciesCode.isEmpty
                           ? "—" : RegionalSpecies.name(forCode: tree.speciesCode))
                detailChip("STATUS", statusLabel(tree.status))
                detailChip("DAMAGE", tree.damageCodes.isEmpty
                           ? "None" : tree.damageCodes.joined(separator: ","))
                Spacer(minLength: 0)
            }
            .padding(.top, 11)

            VStack(spacing: ForestixSpace.xs) {
                HStack(spacing: ForestixSpace.xs) {
                    // Per-tree height on demand — opens the Height screen
                    // scoped to THIS tree (the tally loop records diameters
                    // only).
                    Button {
                        startScopedHeight(plotID: tree.plotId, treeID: tree.id)
                    } label: {
                        Text("Measure height")
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundStyle(ForestixPalette.textPrimary)
                            .frame(maxWidth: .infinity, minHeight: 44)
                            .background(
                                RoundedRectangle(cornerRadius: ForestixRadius.control,
                                                 style: .continuous)
                                    .stroke(ForestixPalette.divider, lineWidth: 1))
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(CruisePressableStyle())
                    .accessibilityIdentifier("cruiseMap.treePeek.measureHeight")

                    Button {
                        pushed = .treeDetails(tree.id)
                    } label: {
                        Text("Edit this tree")
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundStyle(ForestixPalette.primaryInk)
                            .frame(maxWidth: .infinity, minHeight: 44)
                            .background(
                                RoundedRectangle(cornerRadius: ForestixRadius.control,
                                                 style: .continuous)
                                    .fill(ForestixPalette.primary))
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(CruisePressableStyle())
                    .accessibilityIdentifier("cruiseMap.treePeek.edit")
                }

                // Hard removal from the map (confirmed) — the tree row +
                // its photo. Explicit button, never long-press.
                Button(role: .destructive) {
                    deleteTreeCandidateID = tree.id
                } label: {
                    Text("Delete tree")
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
                .buttonStyle(CruisePressableStyle())
                .accessibilityIdentifier("cruiseMap.treePeek.delete")
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
        .accessibilityIdentifier("cruiseMap.treePeek")
    }

    func peekTreeSubtitle(_ tree: Tree, plot: Plot?) -> String {
        var parts: [String] = []
        if let plot { parts.append("Plot \(plot.plotNumber)") }
        parts.append(Self.cruisePeekDateFormatter.string(from: tree.createdAt))
        return parts.joined(separator: " · ")
    }

    func statusLabel(_ status: TreeStatus) -> String {
        switch status {
        case .live:         return "Live"
        case .deadStanding: return "Dead standing"
        case .deadDown:     return "Dead down"
        case .cull:         return "Cull"
        }
    }

    // Peek display carries the value only; ±σ is deliberately NOT shown
    // here (the tree record / CSV / FieldLog keep it).
    func metricRow(label: String, value: String,
                   tier: String, divided: Bool) -> some View {
        HStack(spacing: ForestixSpace.xs) {
            Text(label)
                .font(.system(size: 10, weight: .bold))
                .tracking(0.7)
                .foregroundStyle(ForestixPalette.textTertiary)
                .frame(width: 52, alignment: .leading)
            Text(value)
                .font(.system(size: 14.5, weight: .semibold, design: .monospaced))
                .foregroundStyle(ForestixPalette.textPrimary)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
            Spacer(minLength: 4)
            cruiseTierChip(tier)
        }
        .padding(.vertical, 6)
        .overlay(alignment: .bottom) {
            if divided {
                Rectangle().fill(ForestixPalette.divider).frame(height: 0.5)
            }
        }
    }

    func cruiseTierChip(_ raw: String) -> some View {
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

    func detailChip(_ label: String, _ value: String) -> some View {
        HStack(spacing: 5) {
            Text(label)
                .font(.system(size: 9, weight: .bold))
                .tracking(0.5)
                .foregroundStyle(ForestixPalette.textTertiary)
            Text(value)
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(ForestixPalette.textPrimary)
                .lineLimit(1)
        }
        .padding(.horizontal, 9)
        .padding(.vertical, 5)
        .background(
            RoundedRectangle(cornerRadius: 7, style: .continuous)
                .stroke(ForestixPalette.divider, lineWidth: 1))
    }

    func cruisePhotoThumb(_ name: String?) -> some View {
        ZStack {
            RoundedRectangle(cornerRadius: ForestixRadius.card, style: .continuous)
                .fill(ForestixPalette.surfaceRaised)
            if let name {
                CruisePhotoThumbnail(name: name)
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
    }

    // MARK: Planned-plot peek + map navigation (mock ⑦)

    /// The dashed you→plot guide — non-nil only while navigating AND
    /// holding a fix the app still believes. The map draws the line; the
    /// home floats the live distance chip over it.
    ///
    /// The line STARTS AT THE CRUISER, so its near end is a drawn
    /// position and goes through the same `FixFreshness` gate as the
    /// you-dot and the inside/outside verdict. With a stale fix the whole
    /// guide disappears rather than anchoring itself — and printing a
    /// live distance from — a place the cruiser left ten minutes ago.
    var navGuide: BasemapGuideLine? {
        guard let id = navTargetPlannedID,
              let planned = plannedPlots.first(where: { $0.id == id }),
              let fix = FixFreshness.usable(location.latestSnapshot)
        else { return nil }
        return BasemapGuideLine(
            from: CoordinateConversions.LatLon(latitude: fix.latitude,
                                               longitude: fix.longitude),
            to: CoordinateConversions.LatLon(latitude: planned.plannedLat,
                                             longitude: planned.plannedLon),
            color: ForestixPalette.accent)
    }

    /// Floating live distance chip pinned to the guide line's midpoint
    /// (clamped into the viewport so it stays readable when the plot is
    /// off-screen). Updates with every fix and camera change.
    var distanceChipOverlay: some View {
        GeometryReader { geo in
            if let guide = navGuide {
                let a = BasemapMapView.screenPoint(
                    latitude: guide.from.latitude,
                    longitude: guide.from.longitude,
                    camera: camera, viewportSize: geo.size)
                let b = BasemapMapView.screenPoint(
                    latitude: guide.to.latitude,
                    longitude: guide.to.longitude,
                    camera: camera, viewportSize: geo.size)
                let mid = CGPoint(
                    x: min(max((a.x + b.x) / 2, 60), geo.size.width - 60),
                    y: min(max((a.y + b.y) / 2, 130), geo.size.height - 130))
                let metres = CoordinateConversions.haversineMeters(guide.from,
                                                                   guide.to)
                Text(Self.distanceLabel(metres))
                    .font(.system(size: 11, weight: .heavy, design: .monospaced))
                    .foregroundStyle(ForestixPalette.textPrimary)
                    .padding(.horizontal, 11)
                    .padding(.vertical, 5)
                    .background(Capsule().fill(ForestixPalette.surface))
                    .overlay(Capsule().stroke(ForestixPalette.divider,
                                              lineWidth: 1))
                    .shadow(color: Color.black.opacity(0.18), radius: 4, y: 2)
                    .position(mid)
                    .accessibilityLabel("Distance to plot \(Self.distanceLabel(metres))")
                    .accessibilityIdentifier("cruiseMap.navChip")
            }
        }
        // Match the map's coordinate space — it draws edge-to-edge, so
        // the projection maths must use the same full-screen viewport.
        .ignoresSafeArea()
        .allowsHitTesting(false)
    }

    static func distanceLabel(_ metres: Double) -> String {
        metres < 995 ? String(format: "%.0f m", metres)
                     : String(format: "%.1f km", metres / 1000)
    }

    /// Arrival = within 5 m of the navigated plot: one haptic pulse
    /// (the same arrival pattern the retired navigation screen used),
    /// then the guide clears itself.
    func checkNavArrival(_ snap: CLLocationSnapshot?) {
        guard let snap,
              let id = navTargetPlannedID,
              let planned = plannedPlots.first(where: { $0.id == id })
        else { return }
        let d = GeoMath.distanceM(
            fromLat: snap.latitude, fromLon: snap.longitude,
            toLat: planned.plannedLat, toLon: planned.plannedLon)
        if d <= 5 {
            HapticFeedback.play(.arrival)
            withAnimation(.easeOut(duration: 0.25)) {
                navTargetPlannedID = nil
            }
        }
    }

    /// Peek for a hollow dashed pin: live distance · bearing from the
    /// current fix, "Start plot now" (opens the plot on that fix, field
    /// report 17), "Set plot centre (GPS)" (→ inline averaging sheet) and
    /// "Navigate" (toggles the map guide). Replaces NavigationScreen.
    func plannedPeekCard(for planned: PlannedPlot) -> some View {
        let navigating = navTargetPlannedID == planned.id
        return VStack(spacing: 0) {
            RoundedRectangle(cornerRadius: 2)
                .fill(ForestixPalette.divider)
                .frame(width: 36, height: 4)
                .padding(.bottom, 10)

            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text("Plot \(planned.plotNumber) (planned)")
                    .font(.system(size: 16, weight: .bold))
                    .foregroundStyle(ForestixPalette.textPrimary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
                if planned.skipped { skippedChip } else { plannedChip }
                Spacer(minLength: 4)
            }
            .padding(.bottom, 6)

            HStack(spacing: ForestixSpace.xs) {
                Text("FROM YOU")
                    .font(.system(size: 10, weight: .bold))
                    .tracking(0.7)
                    .foregroundStyle(ForestixPalette.textTertiary)
                    .frame(width: 72, alignment: .leading)
                Text(plannedRangeText(planned))
                    .font(.system(size: 14.5, weight: .semibold,
                                  design: .monospaced))
                    .foregroundStyle(ForestixPalette.textPrimary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                Spacer(minLength: 4)
            }
            .padding(.vertical, 6)

            VStack(spacing: ForestixSpace.xs) {
                // FIELD REPORT 17 — the plot opens on the fix that is live
                // right now, with no window to sit out. The FROM YOU row
                // directly above is the check that makes this safe: it is
                // the cruiser's own reading of whether they are standing at
                // the plot or looking at it from the far side of a draw.
                Button {
                    startPlannedPlotNow(planned)
                } label: {
                    Text("Start plot now")
                        .font(.system(size: 15, weight: .bold))
                        .foregroundStyle(ForestixPalette.primaryInk)
                        .frame(maxWidth: .infinity, minHeight: 54)
                        .background(
                            RoundedRectangle(cornerRadius: ForestixRadius.card,
                                             style: .continuous)
                                .fill(ForestixPalette.primary))
                        .contentShape(Rectangle())
                }
                .buttonStyle(CruisePressableStyle())
                .accessibilityIdentifier("cruiseMap.plannedPeek.startNow")

                Text("Records one GPS fix, not an average.")
                    .font(.system(size: 11))
                    .foregroundStyle(ForestixPalette.textTertiary)
                    .frame(maxWidth: .infinity, alignment: .center)

                // The averaged centre — kept, one tap away, no longer a gate.
                Button {
                    withAnimation(.easeOut(duration: 0.18)) {
                        selectedPinID = nil
                    }
                    recordingTarget = planned
                } label: {
                    Text("Set plot centre (GPS)")
                        .font(.system(size: 15, weight: .bold))
                        .foregroundStyle(ForestixPalette.primary)
                        .frame(maxWidth: .infinity, minHeight: 54)
                        .background(
                            RoundedRectangle(cornerRadius: ForestixRadius.card,
                                             style: .continuous)
                                .stroke(ForestixPalette.primary, lineWidth: 1.5))
                        .contentShape(Rectangle())
                }
                .buttonStyle(CruisePressableStyle())
                .accessibilityIdentifier("cruiseMap.plannedPeek.record")

                Button {
                    withAnimation(.easeOut(duration: 0.2)) {
                        navTargetPlannedID = navigating ? nil : planned.id
                    }
                } label: {
                    Text("Navigate")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(navigating ? ForestixPalette.accent
                                                    : ForestixPalette.textPrimary)
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .background(
                            RoundedRectangle(cornerRadius: ForestixRadius.control,
                                             style: .continuous)
                                .stroke(navigating ? ForestixPalette.accent
                                                   : ForestixPalette.divider,
                                        lineWidth: navigating ? 1.5 : 1))
                        .contentShape(Rectangle())
                }
                .buttonStyle(CruisePressableStyle())
                .accessibilityLabel(navigating ? "Stop navigating"
                                               : "Navigate to plot")
                .accessibilityIdentifier("cruiseMap.plannedPeek.navigate")

                // Skip toggle — an EXPLICIT cruiser action (unlike `visited`,
                // which flips implicitly on recording a centre). "Mark
                // unreachable" documents a cliff/water/private-land plot so
                // navigation passes over it; "Restore plot" undoes it, mirroring
                // PlotSummaryScreen's Reopen affordance. "Set plot centre (GPS)"
                // stays available even when skipped — a cruiser who later
                // reaches the plot can still record it (which sets visited).
                Button {
                    if planned.skipped {
                        setPlannedSkipped(planned, false)
                    } else {
                        setPlannedSkipped(planned, true)
                        withAnimation(.easeOut(duration: 0.18)) {
                            selectedPinID = nil
                        }
                    }
                } label: {
                    Label(planned.skipped ? "Restore plot" : "Mark unreachable",
                          systemImage: planned.skipped ? "lock.open" : "slash.circle")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(planned.skipped ? ForestixPalette.textPrimary
                                                         : ForestixPalette.confidenceWarn)
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .background(
                            RoundedRectangle(cornerRadius: ForestixRadius.control,
                                             style: .continuous)
                                .stroke(planned.skipped ? ForestixPalette.divider
                                                        : ForestixPalette.confidenceWarn.opacity(0.5),
                                        lineWidth: 1))
                        .contentShape(Rectangle())
                }
                .buttonStyle(CruisePressableStyle())
                .accessibilityIdentifier("cruiseMap.plannedPeek.skip")
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
        .accessibilityIdentifier("cruiseMap.plannedPeek")
    }

    /// Shared status token, planned flavour: hollow-grey like the pin.
    var plannedChip: some View {
        HStack(spacing: 4) {
            Circle()
                .stroke(ForestixPalette.textTertiary,
                        style: StrokeStyle(lineWidth: 1.5, dash: [2, 2]))
                .frame(width: 6, height: 6)
            Text("PLANNED")
                .font(.system(size: 10, weight: .bold))
                .tracking(0.6)
        }
        .foregroundStyle(ForestixPalette.textTertiary)
        .padding(.horizontal, 7)
        .padding(.vertical, 2)
        .background(
            RoundedRectangle(cornerRadius: ForestixRadius.chip)
                .fill(ForestixPalette.surfaceRaised))
    }

    /// Shared status token, skipped flavour: warn-coloured, mirroring the
    /// map pin's warn tint + "SKIP" badge for an inaccessible planned plot.
    var skippedChip: some View {
        HStack(spacing: 4) {
            Circle()
                .stroke(ForestixPalette.confidenceWarn,
                        style: StrokeStyle(lineWidth: 1.5, dash: [2, 2]))
                .frame(width: 6, height: 6)
            Text("SKIPPED")
                .font(.system(size: 10, weight: .bold))
                .tracking(0.6)
        }
        .foregroundStyle(ForestixPalette.confidenceWarn)
        .padding(.horizontal, 7)
        .padding(.vertical, 2)
        .background(
            RoundedRectangle(cornerRadius: ForestixRadius.chip)
                .fill(ForestixPalette.confidenceWarn.opacity(0.12)))
    }

    /// LOCKED row format: "X m · bearing Y°" from the current fix.
    ///
    /// The fix must be FRESH — this row is a walking instruction, and a
    /// minutes-old position sends the cruiser off on a heading measured from
    /// somewhere they no longer are. Same rule as the plot verdict and the
    /// drawn you-dot, so nothing on the map claims a position the app has
    /// stopped trusting; when it lapses the row says so instead.
    func plannedRangeText(_ planned: PlannedPlot) -> String {
        guard let fix = FixFreshness.usable(location.latestSnapshot)
        else { return "no GPS fix" }
        let d = GeoMath.distanceM(
            fromLat: fix.latitude, fromLon: fix.longitude,
            toLat: planned.plannedLat, toLon: planned.plannedLon)
        let b = GeoMath.bearingDeg(
            fromLat: fix.latitude, fromLon: fix.longitude,
            toLat: planned.plannedLat, toLon: planned.plannedLon)
        return String(format: "%.0f m · bearing %.0f°", d,
                      (b + 360).truncatingRemainder(dividingBy: 360))
    }

    // MARK: Project sheet (mock ⑤)

    var projectSheet: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                Text("PROJECT")
                    .font(.system(size: 13, weight: .heavy))
                    .tracking(1.0)
                    .foregroundStyle(ForestixPalette.textTertiary)
                    .padding(.top, ForestixSpace.md)
                    .padding(.bottom, ForestixSpace.xs)

                ForEach(projects) { project in
                    projectRow(project)
                }

                newProjectRow

                Rectangle().fill(ForestixPalette.divider).frame(height: 0.5)

                sheetChoiceRow(
                    "Stand summary",
                    subtitle: standSummarySubtitle,
                    icon: "chart.bar",
                    accessibilityID: "cruiseMap.project.standSummary",
                    disabled: currentProject == nil
                ) {
                    pendingDestination = .standSummary
                    presentingProjectSheet = false
                }
                sheetChoiceRow(
                    "Cruise setup",
                    subtitle: "Lay out plots on a grid, set plot size, draw a boundary",
                    icon: "squareshape.split.3x3",
                    accessibilityID: "cruiseMap.project.setup",
                    disabled: currentProject == nil,
                    trailingChip: "ADVANCED"
                ) {
                    pendingCruiseSetup = true
                    presentingProjectSheet = false
                }

                // Export collapse (mock ⑤): ONE primary button runs the
                // full bundle right here; the per-file picker folds into
                // a small "Choose files…" line.
                exportCluster

                // Relocated hub tools — small footer links. (The Phase A
                // "Classic view" bridge is retired.)
                HStack(spacing: ForestixSpace.xs) {
                    footerTool("Field log") {
                        pendingDestination = .fieldLog
                        presentingProjectSheet = false
                    }
                    footerTool("Reference") {
                        pendingDestination = .reference
                        presentingProjectSheet = false
                    }
                    footerTool("Settings") {
                        pendingDestination = .settings
                        presentingProjectSheet = false
                    }
                }
                .padding(.top, ForestixSpace.sm)

                Spacer(minLength: ForestixSpace.lg)
            }
            .padding(.horizontal, ForestixSpace.md)
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .presentationBackground(ForestixPalette.surface)
        #if os(iOS)
        // Share sheet pops over the project sheet once the bundle lands.
        .sheet(item: $exportShareURL) { wrapper in
            CruiseShareSheet(url: wrapper.url)
        }
        #endif
        .alert("Export failed",
               isPresented: Binding(
                   get: { exportErrorMessage != nil },
                   set: { if !$0 { exportErrorMessage = nil } })
        ) {
            Button("OK", role: .cancel) { exportErrorMessage = nil }
        } message: {
            Text(exportErrorMessage ?? "")
        }
    }

    /// "Export all" primary + inline progress + "Choose files…" link.
    var exportCluster: some View {
        VStack(spacing: ForestixSpace.xs) {
            Button {
                exportAll()
            } label: {
                HStack(spacing: 8) {
                    if isExportingAll {
                        ProgressView()
                            .controlSize(.small)
                            .tint(ForestixPalette.primaryInk)
                    }
                    Text("Export all")
                        .font(.system(size: 15, weight: .bold))
                        .foregroundStyle(ForestixPalette.primaryInk)
                }
                .frame(maxWidth: .infinity, minHeight: 54)
                .background(
                    RoundedRectangle(cornerRadius: ForestixRadius.card,
                                     style: .continuous)
                        .fill(ForestixPalette.primary))
                .contentShape(Rectangle())
            }
            .buttonStyle(CruisePressableStyle())
            .disabled(currentProject == nil || isExportingAll)
            .opacity(currentProject == nil ? 0.45 : 1)
            .accessibilityIdentifier("cruiseMap.project.exportAll")

            if isExportingAll {
                VStack(alignment: .leading, spacing: 3) {
                    ProgressView(value: exportProgress)
                        .tint(ForestixPalette.primary)
                    Text(exportLabel)
                        .font(.system(size: 10.5, design: .monospaced))
                        .foregroundStyle(ForestixPalette.textTertiary)
                }
            }

            Button {
                pendingDestination = .export
                presentingProjectSheet = false
            } label: {
                Text("Choose files…")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(ForestixPalette.textTertiary)
                    .frame(maxWidth: .infinity, minHeight: 32)
                    .contentShape(Rectangle())
            }
            .buttonStyle(CruisePressableStyle())
            .disabled(currentProject == nil)
            .accessibilityIdentifier("cruiseMap.project.chooseFiles")
        }
        .padding(.top, ForestixSpace.sm)
    }

    /// The existing full-bundle path (ExportBundleBuilder →
    /// FullCruiseExporter), run inline with progress; projects that
    /// never ran Cruise setup export against the synthesized fixed-area
    /// design instead of erroring.
    func exportAll() {
        guard let project = currentProject, !isExportingAll else { return }
        isExportingAll = true
        exportProgress = 0
        exportLabel = "Preparing…"
        Task { @MainActor in
            do {
                let source = FallbackDesignExportDataSource(
                    base: RepositoryExportDataSource(project: project,
                                                     env: environment),
                    fallbackDesign: effectiveDesign())
                let bundle = try ExportBundleBuilder.build(using: source)
                let base = try FileManager.default.url(
                    for: .documentDirectory, in: .userDomainMask,
                    appropriateFor: nil, create: true)
                let result = try FullCruiseExporter.write(
                    bundle: bundle,
                    into: base,
                    localization: PDFLocalization.forProject(
                        units: project.units,
                        species: bundle.species,
                        trees: bundle.trees),
                    progress: { done, total, label in
                        exportProgress = total == 0
                            ? 1 : Double(done) / Double(total)
                        exportLabel = label
                    })
                isExportingAll = false
                exportProgress = 1
                exportLabel = "Done"
                exportShareURL = ExportShareURL(url: result.folder)
            } catch {
                isExportingAll = false
                exportProgress = 0
                exportErrorMessage = error.localizedDescription
            }
        }
    }

    var standSummarySubtitle: String {
        let closed = plots.filter { $0.closedAt != nil }.count
        return "Averages across \(closed) closed plot\(closed == 1 ? "" : "s")"
    }

    func projectRow(_ project: Project) -> some View {
        let isCurrent = project.id == currentProject?.id
        let projectPlots = (try? environment.plotRepository
            .listByProject(project.id)) ?? []
        let treeCount = projectPlots.reduce(0) { sum, plot in
            sum + ((try? environment.treeRepository
                .listByPlot(plot.id, includeDeleted: false))?.count ?? 0)
        }
        return Button {
            settings.currentCruiseProjectID = project.id
            reloadCruise()
        } label: {
            HStack(spacing: 12) {
                Circle()
                    .stroke(isCurrent ? ForestixPalette.primary
                                      : ForestixPalette.divider,
                            lineWidth: 2)
                    .frame(width: 20, height: 20)
                    .overlay {
                        if isCurrent {
                            Circle()
                                .fill(ForestixPalette.primary)
                                .padding(4.5)
                        }
                    }
                VStack(alignment: .leading, spacing: 2) {
                    Text(project.name)
                        .font(.system(size: 15, weight: .bold))
                        .foregroundStyle(ForestixPalette.textPrimary)
                        .lineLimit(1)
                    Text("Plots \(projectPlots.count) · Trees \(treeCount) · \(Self.projectDateFormatter.string(from: project.createdAt))")
                        .font(.system(size: 10.5, design: .monospaced))
                        .foregroundStyle(ForestixPalette.textTertiary)
                        .lineLimit(1)
                }
                Spacer(minLength: 4)
                if isCurrent {
                    HStack(spacing: 4) {
                        Circle().fill(ForestixPalette.accent)
                            .frame(width: 6, height: 6)
                        Text("IN USE")
                            .font(.system(size: 10, weight: .bold))
                            .tracking(0.6)
                    }
                    .foregroundStyle(ForestixPalette.accent)
                    .padding(.horizontal, 7)
                    .padding(.vertical, 2)
                    .background(
                        RoundedRectangle(cornerRadius: ForestixRadius.chip)
                            .fill(ForestixPalette.accent.opacity(0.12)))
                }
            }
            .padding(.vertical, 12)
            .frame(minHeight: 56)
            .contentShape(Rectangle())
        }
        .buttonStyle(CruisePressableStyle())
        .overlay(alignment: .bottom) {
            Rectangle().fill(ForestixPalette.divider).frame(height: 0.5)
        }
    }

    static let projectDateFormatter: DateFormatter = {
        let df = DateFormatter()
        df.locale = Locale(identifier: "en_US_POSIX")
        df.dateFormat = "d MMM"
        return df
    }()

    /// One-time naming — the ONLY thing a project ever asks for. Unit
    /// system comes from Settings; plots and trees auto-number.
    var newProjectRow: some View {
        VStack(alignment: .leading, spacing: 0) {
            if namingNewProject {
                HStack(spacing: 12) {
                    TextField("Project name", text: $newProjectName)
                        .font(.system(size: 15, weight: .semibold))
                        .textFieldStyle(.plain)
                        .padding(.horizontal, 12)
                        .frame(minHeight: 44)
                        .background(
                            RoundedRectangle(cornerRadius: ForestixRadius.control,
                                             style: .continuous)
                                .fill(ForestixPalette.surfaceRaised))
                        .accessibilityIdentifier("cruiseMap.project.nameField")
                    Button {
                        if createProject(named: newProjectName) != nil {
                            namingNewProject = false
                            newProjectName = ""
                        }
                    } label: {
                        Text("Create")
                            .font(.system(size: 14, weight: .bold))
                            .foregroundStyle(ForestixPalette.primaryInk)
                            .padding(.horizontal, 18)
                            .frame(minHeight: 44)
                            .background(
                                RoundedRectangle(cornerRadius: ForestixRadius.control,
                                                 style: .continuous)
                                    .fill(ForestixPalette.primary))
                    }
                    .buttonStyle(CruisePressableStyle())
                    .disabled(newProjectName.trimmingCharacters(
                        in: .whitespacesAndNewlines).isEmpty)
                    .accessibilityIdentifier("cruiseMap.project.create")
                }
                .padding(.vertical, 12)
            } else {
                Button {
                    namingNewProject = true
                } label: {
                    HStack(spacing: 12) {
                        Circle()
                            .stroke(ForestixPalette.divider,
                                    style: StrokeStyle(lineWidth: 2, dash: [3, 3]))
                            .frame(width: 20, height: 20)
                        VStack(alignment: .leading, spacing: 2) {
                            Text("New project")
                                .font(.system(size: 15, weight: .bold))
                                .foregroundStyle(ForestixPalette.textSecondary)
                            Text("Name it once — everything else is automatic")
                                .font(.system(size: 10.5, design: .monospaced))
                                .foregroundStyle(ForestixPalette.textTertiary)
                        }
                        Spacer(minLength: 0)
                    }
                    .padding(.vertical, 12)
                    .frame(minHeight: 56)
                    .contentShape(Rectangle())
                }
                .buttonStyle(CruisePressableStyle())
                .accessibilityIdentifier("cruiseMap.project.new")
            }
        }
    }

    func sheetChoiceRow(_ title: String,
                        subtitle: String,
                        icon: String,
                        accessibilityID: String,
                        disabled: Bool,
                        trailingChip: String? = nil,
                        action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 14) {
                ZStack {
                    RoundedRectangle(cornerRadius: 9, style: .continuous)
                        .fill(ForestixPalette.primaryMuted)
                        .frame(width: 44, height: 44)
                    Image(systemName: icon)
                        .font(.system(size: 20, weight: .medium))
                        .foregroundStyle(ForestixPalette.primary)
                }
                VStack(alignment: .leading, spacing: 1) {
                    HStack(spacing: 7) {
                        Text(title)
                            .font(.system(size: 15.5, weight: .bold))
                            .foregroundStyle(ForestixPalette.textPrimary)
                        if let trailingChip {
                            Text(trailingChip)
                                .font(.system(size: 9, weight: .bold))
                                .tracking(0.6)
                                .foregroundStyle(ForestixPalette.textTertiary)
                                .padding(.horizontal, 6)
                                .padding(.vertical, 2)
                                .background(
                                    RoundedRectangle(cornerRadius: ForestixRadius.chip)
                                        .fill(ForestixPalette.surfaceRaised))
                        }
                    }
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
            .frame(minHeight: 56)
            .contentShape(Rectangle())
        }
        .buttonStyle(CruisePressableStyle())
        .disabled(disabled)
        .opacity(disabled ? 0.45 : 1)
        .accessibilityIdentifier(accessibilityID)
        .overlay(alignment: .bottom) {
            Rectangle().fill(ForestixPalette.divider).frame(height: 0.5)
        }
    }

    func footerTool(_ title: String,
                    action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(ForestixPalette.textSecondary)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
                .frame(maxWidth: .infinity, minHeight: 38)
                .background(
                    RoundedRectangle(cornerRadius: ForestixRadius.control,
                                     style: .continuous)
                        .stroke(ForestixPalette.divider, lineWidth: 1))
                .contentShape(Rectangle())
        }
        .buttonStyle(CruisePressableStyle())
        .accessibilityIdentifier("cruiseMap.project.tool.\(title)")
    }

    // MARK: Pushed destinations

    @ViewBuilder
    func destinationView(_ destination: CruiseDestination) -> some View {
        switch destination {
        case .plotDetails(let id):
            if let project = currentProject,
               let plot = plots.first(where: { $0.id == id }) {
                PlotSummaryScreen(viewModel: PlotSummaryViewModel(
                    project: project,
                    design: effectiveDesign(),
                    plot: plot,
                    plotRepo: environment.plotRepository,
                    treeRepo: environment.treeRepository,
                    speciesRepo: environment.speciesRepository,
                    volRepo: environment.volumeEquationRepository,
                    hdFitRepo: environment.hdFitRepository),
                    areaUnit: settings.unitSystem.areaUnit)
            }
        case .treeDetails(let id):
            if let tree = treesByPlot.values.joined()
                .first(where: { $0.id == id }) {
                TreeDetailScreen(viewModel: TreeDetailViewModel(
                    tree: tree,
                    treeRepo: environment.treeRepository))
            }
        case .standSummary:
            if let project = currentProject {
                StandSummaryScreen(viewModel: StandSummaryViewModel(
                    project: project,
                    design: effectiveDesign(),
                    plotRepo: environment.plotRepository,
                    treeRepo: environment.treeRepository,
                    speciesRepo: environment.speciesRepository,
                    volRepo: environment.volumeEquationRepository,
                    hdFitRepo: environment.hdFitRepository,
                    stratumRepo: environment.stratumRepository,
                    plannedRepo: environment.plannedPlotRepository),
                    volumePending: settings.country.volumeStandard.isPending,
                    areaUnit: settings.unitSystem.areaUnit)
            }
        case .export:
            if let project = currentProject {
                ExportScreen(project: project)
            }
        case .fieldLog:
            FieldLogScreen()
        case .reference:
            ReferenceLibraryScreen()
        case .settings:
            SettingsScreen()
        }
    }
}

// MARK: - Export-all plumbing

/// The repository-backed export source, with one difference: a project
/// that skipped the optional Cruise setup has no CruiseDesign row, and
/// the informal path must still export — fall back to the synthesized
/// fixed-area design instead of throwing `designNotFound`.
private struct FallbackDesignExportDataSource: ExportDataSource {
    let base: RepositoryExportDataSource
    let fallbackDesign: CruiseDesign

    func project() throws -> Project { try base.project() }

    func cruiseDesign(forProjectId id: UUID) throws -> CruiseDesign {
        (try? base.cruiseDesign(forProjectId: id)) ?? fallbackDesign
    }

    func strata(forProjectId id: UUID) throws -> [Stratum] {
        try base.strata(forProjectId: id)
    }

    func plannedPlots(forProjectId id: UUID) throws -> [PlannedPlot] {
        try base.plannedPlots(forProjectId: id)
    }

    func plots(forProjectId id: UUID) throws -> [Plot] {
        try base.plots(forProjectId: id)
    }

    func trees(forPlotId id: UUID) throws -> [Tree] {
        try base.trees(forPlotId: id)
    }

    func species() throws -> [SpeciesConfig] { try base.species() }

    func volumeEquations() throws -> [Models.VolumeEquation] {
        try base.volumeEquations()
    }

    func hdFits(forProjectId id: UUID) throws -> [HeightDiameterFit] {
        try base.hdFits(forProjectId: id)
    }
}

#if os(iOS)
/// Plain UIActivityViewController wrapper for the export-all share.
private struct CruiseShareSheet: UIViewControllerRepresentable {
    let url: URL
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: [url],
                                 applicationActivities: nil)
    }
    func updateUIViewController(_: UIActivityViewController,
                                context: Context) {}
}
#endif

// MARK: - Pressed feedback (same language as the measure mode)

private struct CruisePressableStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .opacity(configuration.isPressed ? 0.78 : 1)
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .animation(.easeOut(duration: 0.15), value: configuration.isPressed)
    }
}

// MARK: - Heights sheet (plot sample heights, pooled)

/// The plot's measured (tree #, DBH, height) pairs + on-demand height
/// measurement. Stage 1 lists the fit-eligible pairs with the primary
/// "Measure height"; tapping it swaps in a compact horizontal tree-
/// number picker (default = the last tallied tree) whose confirm hands
/// the chosen tree to the host, which opens the scoped Height screen.
/// The LOCKED pooled-curve caption appears once ≥3 pairs exist.
/// Structure mirrors Android's PlotHeightsSheet.
struct PlotHeightsSheet: View {
    let plot: Plot
    /// Live trees of the plot (already filtered by the host).
    let trees: [Tree]
    let onMeasure: (Tree) -> Void

    @EnvironmentObject private var settings: AppSettings
    @State private var picking = false
    @State private var selectedTreeID: UUID?

    /// Picker candidates — every live tree, tally order.
    private var live: [Tree] {
        trees.sorted { $0.treeNumber < $1.treeNumber }
    }

    /// List rows — the fit-eligible (DBH, height) pairs, tally order
    /// (same gate as the pooled fit and the "N measured" count).
    private var withHeights: [Tree] {
        live.filter { ($0.heightM ?? 0) > 1.3 && $0.dbhCm > 0 }
    }

    /// Default picker selection: the LAST TALLIED tree (newest row) —
    /// the cruiser usually heights the tree just measured.
    private var lastTallied: Tree? {
        trees.max { $0.createdAt < $1.createdAt }
    }

    private var selected: Tree? {
        live.first { $0.id == selectedTreeID }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                Text("HEIGHTS · PLOT \(plot.plotNumber)")
                    .font(.system(size: 13, weight: .heavy))
                    .tracking(1.0)
                    .foregroundStyle(ForestixPalette.textTertiary)
                    .padding(.top, ForestixSpace.md)
                    .padding(.bottom, ForestixSpace.xs)

                // Measured (tree #, DBH, height) pairs — pooled, no
                // species dimension.
                if withHeights.isEmpty {
                    Text("No heights measured yet")
                        .font(ForestixType.caption)
                        .foregroundStyle(ForestixPalette.textTertiary)
                        .padding(.vertical, 12)
                }
                ForEach(withHeights) { tree in
                    heightRow(tree)
                }

                // LOCKED caption — the pooled curve is live and volume
                // estimation imputes the plot's unmeasured heights.
                if withHeights.count >= 3 {
                    Text("Height curve active — other trees estimated")
                        .font(ForestixType.caption)
                        .foregroundStyle(ForestixPalette.textSecondary)
                        .padding(.top, ForestixSpace.xs)
                        .accessibilityIdentifier("cruiseMap.heightsSheet.curve")
                }

                Spacer(minLength: 0).frame(height: ForestixSpace.sm)

                if picking {
                    pickerStage
                } else {
                    // 54 pt primary — LOCKED "Measure height".
                    primaryButton("Measure height", enabled: !live.isEmpty) {
                        if !live.isEmpty {
                            selectedTreeID = lastTallied?.id
                            picking = true
                        }
                    }
                    .accessibilityIdentifier("cruiseMap.heightsSheet.measure")
                }

                Spacer(minLength: ForestixSpace.lg)
            }
            .padding(.horizontal, ForestixSpace.md)
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .presentationBackground(ForestixPalette.surface)
        .accessibilityIdentifier("cruiseMap.heightsSheet")
    }

    private func heightRow(_ tree: Tree) -> some View {
        let system = settings.unitSystem
        return HStack(spacing: ForestixSpace.xs) {
            Text("Tree \(tree.treeNumber)")
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(ForestixPalette.textPrimary)
                .frame(width: 76, alignment: .leading)
            Text(MeasurementFormatter.diameter(cm: Double(tree.dbhCm),
                                               in: system))
                .font(.system(size: 12.5, weight: .medium,
                              design: .monospaced))
                .foregroundStyle(ForestixPalette.textSecondary)
            Spacer(minLength: 4)
            Text(tree.heightM.map {
                MeasurementFormatter.height(m: Double($0), in: system)
            } ?? "—")
                .font(.system(size: 13.5, weight: .semibold,
                              design: .monospaced))
                .foregroundStyle(ForestixPalette.textPrimary)
        }
        .padding(.vertical, 9)
        .padding(.horizontal, 2)
        .overlay(alignment: .bottom) {
            Rectangle().fill(ForestixPalette.divider).frame(height: 0.5)
        }
    }

    // MARK: Stage 2 — compact tree-number picker

    @ViewBuilder
    private var pickerStage: some View {
        Text("TREE")
            .font(.system(size: 10, weight: .bold))
            .tracking(0.7)
            .foregroundStyle(ForestixPalette.textTertiary)
            .padding(.bottom, 6)

        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                ForEach(live) { tree in
                    numberChip(tree)
                }
            }
        }
        .accessibilityIdentifier("cruiseMap.heightsSheet.picker")

        primaryButton(selected.map { "Measure Tree \($0.treeNumber)" }
                        ?? "Measure height",
                      enabled: selected != nil) {
            if let tree = selected { onMeasure(tree) }
        }
        .padding(.top, ForestixSpace.sm)
        .accessibilityIdentifier("cruiseMap.heightsSheet.confirm")
    }

    /// One pickable tree chip — "Tree N · DBH" plus a check when it
    /// already carries a height.
    private func numberChip(_ tree: Tree) -> some View {
        let isSelected = tree.id == selectedTreeID
        let label = "Tree \(tree.treeNumber) · "
            + MeasurementFormatter.diameter(cm: Double(tree.dbhCm),
                                            in: settings.unitSystem)
            + (tree.heightM != nil ? " ✓" : "")
        return Button {
            selectedTreeID = tree.id
        } label: {
            Text(label)
                .font(.system(size: 13.5,
                              weight: isSelected ? .bold : .medium,
                              design: .monospaced))
                .foregroundStyle(isSelected ? ForestixPalette.primary
                                            : ForestixPalette.textPrimary)
                .padding(.horizontal, 10)
                .frame(minWidth: 44, minHeight: 44)
                .background(
                    RoundedRectangle(cornerRadius: ForestixRadius.control,
                                     style: .continuous)
                        .fill(isSelected ? ForestixPalette.primaryMuted
                                         : ForestixPalette.surface))
                .overlay(
                    RoundedRectangle(cornerRadius: ForestixRadius.control,
                                     style: .continuous)
                        .stroke(isSelected ? ForestixPalette.primary
                                           : ForestixPalette.divider,
                                lineWidth: isSelected ? 1.5 : 1))
                .contentShape(Rectangle())
        }
        .buttonStyle(CruisePressableStyle())
    }

    /// 54 pt primary — dimmed to a raised surface while unusable.
    private func primaryButton(_ title: String,
                               enabled: Bool,
                               action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: 15, weight: .bold))
                .foregroundStyle(enabled ? ForestixPalette.primaryInk
                                         : ForestixPalette.textTertiary)
                .frame(maxWidth: .infinity, minHeight: 54)
                .background(
                    RoundedRectangle(cornerRadius: ForestixRadius.card,
                                     style: .continuous)
                        .fill(enabled ? ForestixPalette.primary
                                      : ForestixPalette.surfaceRaised))
                .contentShape(Rectangle())
        }
        .buttonStyle(CruisePressableStyle())
        .disabled(!enabled)
    }
}

// MARK: - Cruise tree photo viewer (tree peek thumbnail → full screen)

#if os(iOS)
/// Full-screen viewer for a cruise tree's auto-photo, reached by tapping
/// the tree-peek thumbnail. Reuses the MeasurePhotoStore path; the chrome
/// is fixed dark (it sits on a photograph), matching the measure-mode
/// photo detail's language.
private struct CruiseTreePhotoView: View {
    let context: CruisePhotoContext
    @Environment(\.dismiss) private var dismiss
    @State private var image: UIImage?

    private let ink = Color(red: 0.949, green: 0.961, blue: 0.953)      // #F2F5F3
    private let inkDim = Color(red: 0.647, green: 0.682, blue: 0.659)   // #A5AEA8
    private let glass = Color(red: 6 / 255, green: 9 / 255, blue: 10 / 255) // #06090A

    var body: some View {
        ZStack {
            Color(red: 0.039, green: 0.051, blue: 0.043).ignoresSafeArea() // #0A0D0B

            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFit()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ProgressView().tint(ink)
            }

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
                    .accessibilityIdentifier("cruiseMap.photo.close")
                }
                .padding(.horizontal, 14)
                Spacer()
            }

            VStack {
                Spacer()
                VStack(alignment: .leading, spacing: 4) {
                    Text(context.title)
                        .font(.system(size: 17, weight: .heavy))
                        .foregroundStyle(ink)
                    Text(context.subtitle)
                        .font(.system(size: 12, design: .monospaced))
                        .foregroundStyle(inkDim)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 20)
                .padding(.top, 40)
                .padding(.bottom, 30)
                .background(
                    LinearGradient(colors: [glass.opacity(0), glass.opacity(0.92)],
                                   startPoint: .top, endPoint: .bottom))
            }
        }
        .task {
            let url = MeasurePhotoStore.url(for: context.photoPath)
            let data = await Task.detached { try? Data(contentsOf: url) }.value
            if let data { image = UIImage(data: data) }
        }
    }
}
#endif

// MARK: - Photo thumbnail loader

/// Loads a MeasurePhotoStore JPEG off the main thread — the cruise
/// tree's auto-photo shares the quick-measure photo store.
private struct CruisePhotoThumbnail: View {
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
