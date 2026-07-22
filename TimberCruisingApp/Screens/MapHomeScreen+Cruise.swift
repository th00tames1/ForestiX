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
//   • "Set plot centre (GPS)" → RecordCentreSheet (inline 60 s GPS
//     averaging ring; offset fallback one line away); saving converts
//     the planned pin into a real active plot.
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
    }

    // MARK: Markers

    /// Plot ring markers + cruise tree teardrops. Quick-measure entries
    /// are deliberately NOT read here. Status colours are UNCHANGED by
    /// the mode merge: accent-amber active, ok-green closed, dashed
    /// grey planned.
    var cruiseMarkers: [BasemapMarker] {
        var out: [BasemapMarker] = plots.map { plot in
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
        for planned in plannedPlots {
            out.append(BasemapMarker(
                id: "pplot-\(planned.id.uuidString)",
                latitude: planned.plannedLat,
                longitude: planned.plannedLon,
                title: "P\(planned.plotNumber)",
                tint: ForestixPalette.textTertiary,
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

    /// LOCKED strings: "Start plot" / "Add tree · Plot N".
    var cruisePrimaryLabel: String {
        if let plot = activePlot {
            return "Add tree · Plot \(plot.plotNumber)"
        }
        return "Start plot"
    }

    func cruisePrimaryAction() {
        if let plot = activePlot {
            startAddTree(in: plot)
        } else {
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
                             onDismiss: { reloadCruise() }) { plotSetupCover }
            // Cruise tally loop — the DBH cover saves tree after tree and
            // only closes via the floating back; dismissal refreshes the
            // map's pins + tally.
            .fullScreenCover(isPresented: $presentingCruiseDBH,
                             onDismiss: { reloadCruise() }) { cruiseDBHCover }
            .fullScreenCover(isPresented: $presentingCruiseHeight,
                             onDismiss: { reloadCruise() }) { cruiseHeightCover }
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
    }

    // MARK: Plot creation (Start plot → sampling-ring AR component)

    #if os(iOS)
    var plotSetupCover: some View {
        NavigationStack {
            SamplingPlotScreen(onSaveCruisePlot: { radiusM in
                createCruisePlot(radiusM: radiusM)
            })
            .environmentObject(history)
            .environmentObject(settings)
        }
    }
    #endif

    /// Persist the placed ring as a cruise `Plot` in the current
    /// project (creating "Project N" on the fly if none exists — the
    /// benchmark's measure-first rule: setup never blocks measuring).
    /// The new plot is open, so it becomes the ACTIVE plot and the (+)
    /// morphs to "Add tree · Plot N". `ActiveSamplingPlot` stays placed
    /// (the sampling screen anchored it), so DBH/Height overlay the
    /// ring while measuring inside it.
    func createCruisePlot(radiusM: Double) {
        let project = currentProject ?? autoCreateProject()
        guard let project else { return }
        let fix = location.latestSnapshot ?? LocationService.lastGlobalFix
        let number = ((try? environment.plotRepository
            .listByProject(project.id))?.map(\.plotNumber).max() ?? 0) + 1
        let areaAcres = Float(.pi * radiusM * radiusM / 4046.8564224)
        let tier: PositionTier = fix.map {
            GPSAveraging.classify(medianHAccuracyM: Float($0.horizontalAccuracyM),
                                  sampleStdXyM: 0)
        } ?? .D
        let plot = Plot(
            id: UUID(),
            projectId: project.id,
            plannedPlotId: nil,
            plotNumber: number,
            centerLat: fix?.latitude ?? camera.latitude,
            centerLon: fix?.longitude ?? camera.longitude,
            positionSource: fix == nil ? .manual : .gpsAveraged,
            positionTier: tier,
            gpsNSamples: fix == nil ? 0 : 1,
            gpsMedianHAccuracyM: Float(fix?.horizontalAccuracyM ?? 0),
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
        if (try? environment.plotRepository.create(plot)) != nil {
            // Stamp the placed AR ring as THIS cruise plot's centre so
            // the scan screens' mini-map may use the anchor path (the
            // accurate one) for YOU while measuring into this plot.
            ActiveSamplingPlot.shared.link(cruisePlotID: plot.id)
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
    /// into: plot number + radius + centre fix, and the live trees'
    /// fixes tinted by confidence (warn for any yellow/red DBH or
    /// Height tier). The loop's Accept reloads the snapshot before
    /// advancing, so the widget gains each new dot immediately.
    func cruiseMiniMapInfo(plotID: UUID?) -> PlotMiniMapInfo? {
        guard let plotID,
              let plot = plots.first(where: { $0.id == plotID })
        else { return nil }
        let trees = liveTrees(in: plotID)
        let dots: [PlotMiniMapInfo.TreeDot] = trees.compactMap { tree in
            guard let lat = tree.latitude, let lon = tree.longitude
            else { return nil }
            let heightWarn = tree.heightConfidence.map { $0 != .green } ?? false
            return PlotMiniMapInfo.TreeDot(
                latitude: lat,
                longitude: lon,
                warn: tree.dbhConfidence != .green || heightWarn)
        }
        return PlotMiniMapInfo(
            plotID: plot.id,
            plotNumber: plot.plotNumber,
            radiusM: plotRadiusM(plot),
            centerLat: plot.centerLat,
            centerLon: plot.centerLon,
            treeCount: trees.count,
            trees: dots)
    }

    #if os(iOS)
    /// The cruise DIAMETER LOOP cover. Accept saves the tree through the
    /// existing path (calibration / GPS / photo / metadata), reloads the
    /// snapshot so the mini-map + border chip track the new dot, and
    /// advances the target number — the screen resets itself and shows
    /// the Undo toast. No Height hand-off, no continuation sheet; the
    /// floating back exits the loop.
    var cruiseDBHCover: some View {
        NavigationStack {
            DBHScanScreen(
                viewModel: DBHScanViewModel(
                    calibration: calibration(from: currentProject)),
                onAccept: { result, meta in
                    saveChainDBH(result, meta: meta)
                    reloadCruise()
                    if let plotID = chainPlotID {
                        chainTreeNumber = nextTreeNumber(in: plotID)
                    }
                },
                cruisePlotInfo: cruiseMiniMapInfo(plotID: chainPlotID),
                tallyTreeNumber: chainTreeNumber,
                onUndoTally: { undoLastTally() })
            .environmentObject(settings)
        }
    }

    var cruiseHeightCover: some View {
        NavigationStack {
            HeightScanScreen(
                viewModel: HeightScanViewModel(
                    calibration: calibration(from: currentProject)),
                onAccept: { result, meta in
                    saveChainHeight(result, meta: meta)
                    presentingCruiseHeight = false
                },
                cruisePlotInfo: cruiseMiniMapInfo(plotID: chainPlotID))
            .environmentObject(history)
            .environmentObject(settings)
        }
    }

    /// Accepted DBH → create the cruise Tree. Species defaults to the
    /// plot's most recent species when the meta sheet was skipped
    /// (zero-typing rule); GPS + auto-photo arrive on the metadata.
    func saveChainDBH(_ result: DBHResult,
                      meta: DBHScanScreen.ScanMetadata) {
        guard let plotID = chainPlotID else { return }
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
            heightM: nil,
            heightMethod: nil,
            heightSource: nil,
            heightSigmaM: nil,
            heightDHM: nil,
            heightAlphaTopDeg: nil,
            heightAlphaBaseDeg: nil,
            heightConfidence: nil,
            bearingFromCenterDeg: nil,
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
        if let created = try? environment.treeRepository.create(tree) {
            chainTreeID = created.id
        }
    }

    /// Accepted Height → update the scoped tree (tree peek / heights
    /// sheet target).
    func saveChainHeight(_ result: HeightResult,
                         meta: HeightScanScreen.ScanMetadata) {
        guard let id = chainTreeID,
              var tree = try? environment.treeRepository.read(id: id)
        else { return }
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
        _ = try? environment.treeRepository.update(tree)
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
              let plot = plots.first(where: { $0.id == plotID })
        else { return nil }
        let d = CoordinateConversions.haversineMeters(
            CoordinateConversions.LatLon(latitude: lat, longitude: lon),
            CoordinateConversions.LatLon(latitude: plot.centerLat,
                                         longitude: plot.centerLon))
        return Float(d)
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
        sqrt(Double(plot.plotAreaAcres) * 4046.8564224 / .pi)
    }

    func plotPeekCard(for plot: Plot) -> some View {
        let trees = liveTrees(in: plot.id)
        let stats = plotStats(for: plot, trees: trees)
        let isClosed = plot.closedAt != nil
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
                Text(String(format: "r %.1f m · %@",
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
                statCell("TREES", "\(stats.liveTreeCount)", nil, divided: true)
                statCell("BA", String(format: "%.1f", stats.baPerAcreM2),
                         "m²/ac", divided: true)
                statCell("TPA", String(format: "%.0f", stats.tpa),
                         "/ac", divided: true)
                statCell("QMD", String(format: "%.1f", stats.qmdCm),
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

    /// Stamp closedAt — the ring turns done-green. Validation lives in
    /// Details (PlotSummaryScreen); the peek's close is one confirm.
    func closePlot(id: UUID) {
        guard var plot = plots.first(where: { $0.id == id }) else { return }
        plot.closedAt = Date()
        plot.closedBy = "field"
        _ = try? environment.plotRepository.update(plot)
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
                     : "Tree \(tree.treeNumber) · \(tree.speciesCode.uppercased())")
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
                cruisePhotoThumb(tree.photoPath)
                VStack(spacing: 0) {
                    metricRow(
                        label: "DBH",
                        value: MeasurementFormatter.diameter(cm: Double(tree.dbhCm),
                                                             in: system),
                        sigma: tree.dbhSigmaMm.map {
                            MeasurementFormatter.diameterSigma(mm: Double($0),
                                                               in: system)
                        },
                        tier: tree.dbhConfidence.rawValue,
                        divided: tree.heightM != nil)
                    if let h = tree.heightM {
                        metricRow(
                            label: "HEIGHT",
                            value: MeasurementFormatter.height(m: Double(h),
                                                               in: system),
                            sigma: tree.heightSigmaM.map {
                                MeasurementFormatter.heightSigma(m: Double($0),
                                                                 in: system)
                            },
                            tier: tree.heightConfidence?.rawValue ?? "green",
                            divided: false)
                    }
                }
                .frame(maxWidth: .infinity)
            }

            // Post-hoc detail chips — read-only here; Edit details is
            // the editor. Never a gate.
            HStack(spacing: 6) {
                detailChip("SPECIES", tree.speciesCode.isEmpty
                           ? "—" : tree.speciesCode.uppercased())
                detailChip("STATUS", statusLabel(tree.status))
                detailChip("DAMAGE", tree.damageCodes.isEmpty
                           ? "None" : tree.damageCodes.joined(separator: ","))
                Spacer(minLength: 0)
            }
            .padding(.top, 11)

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
                    Text("Edit details")
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

    func metricRow(label: String, value: String, sigma: String?,
                   tier: String, divided: Bool) -> some View {
        HStack(spacing: ForestixSpace.xs) {
            Text(label)
                .font(.system(size: 10, weight: .bold))
                .tracking(0.7)
                .foregroundStyle(ForestixPalette.textTertiary)
                .frame(width: 52, alignment: .leading)
            HStack(spacing: 0) {
                Text(value)
                    .font(.system(size: 14.5, weight: .semibold, design: .monospaced))
                    .foregroundStyle(ForestixPalette.textPrimary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                if let sigma {
                    Text(" " + sigma)
                        .font(.system(size: 11, design: .monospaced))
                        .foregroundStyle(ForestixPalette.textTertiary)
                        .lineLimit(1)
                }
            }
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
    /// holding a fix. The map draws the line; the home floats the live
    /// distance chip over it.
    var navGuide: BasemapGuideLine? {
        guard let id = navTargetPlannedID,
              let planned = plannedPlots.first(where: { $0.id == id }),
              let fix = location.latestSnapshot
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
    /// current fix, "Set plot centre (GPS)" (→ inline averaging sheet) and
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
                plannedChip
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
                Button {
                    withAnimation(.easeOut(duration: 0.18)) {
                        selectedPinID = nil
                    }
                    recordingTarget = planned
                } label: {
                    Text("Set plot centre (GPS)")
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

    /// LOCKED row format: "X m · bearing Y°" from the current fix.
    func plannedRangeText(_ planned: PlannedPlot) -> String {
        guard let fix = location.latestSnapshot
            ?? LocationService.lastGlobalFix else { return "no GPS fix" }
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
                    subtitle: "Grid plots · strata · prism/BAF — optional",
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
        return "Mean ± CI · \(closed) closed plot\(closed == 1 ? "" : "s")"
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
                    hdFitRepo: environment.hdFitRepository))
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
                    plannedRepo: environment.plannedPlotRepository))
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
