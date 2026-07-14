// CRUISE MODE — the map IS the cruise. Built to
// design/forestix-redesign-v3-cruise.html (screens ① cruise mode,
// ② plot peek, ③ tally loop, ④ tree peek, ⑤ project sheet), Phase A.
//
// Entered from the map home's Cruise side-circle; the floating back
// returns home. Same satellite basemap stack as the home, but the pins
// here are CRUISE data only: plots as ring markers (accent = active,
// green = closed; the dashed "planned" style ships for Phase B) and
// cruise trees as teardrop pins. Quick-measure pins never appear here,
// and cruise pins never appear on the home — the two worlds stay
// separate.
//
// The single primary action is the state-morphing (+):
//   • no active plot  → "Start plot" — the existing sampling-ring AR
//     component places centre + radius; Save creates a cruise `Plot`
//     in the current project (auto-numbered "Plot N", centre from the
//     GPS fix) and leaves `ActiveSamplingPlot` placed so the scan
//     screens overlay the ring;
//   • active plot     → "Add tree · Plot N" — the existing DBH→Height
//     full-measurement chain, routed through the Accept path with the
//     project's REAL calibration, so the cruise Tree inherits value +
//     σ + species/damage metadata + GPS + auto-photo. Tree numbers
//     auto-increment within the plot; zero typing per record.
//
// Tapping a plot ring peeks the tally card (live TREES/BA/TPA/QMD via
// the InventoryEngine, Add tree, Close plot, Details); tapping a tree
// pin peeks the v2-anatomy card (photo, metric rows, chips, Edit
// details — post-hoc, never a gate). The project chip opens the
// project sheet: switcher, one-time naming, Stand summary, Export,
// advanced Cruise setup, and the relocated hub tools (Field log ·
// Reference · Settings · Classic view).

import SwiftUI
import Common
import Models
import Persistence
import InventoryEngine
import Sensors
import Positioning
import Geo
import Basemap

// MARK: - Screen

public struct CruiseMapScreen: View {

    @EnvironmentObject private var environment: AppEnvironment
    @EnvironmentObject private var settings: AppSettings
    @EnvironmentObject private var history: QuickMeasureHistory
    @Environment(\.dismiss) private var dismiss

    /// Own LocationService — the GPS chip and plot-centre stamping must
    /// be live from the moment cruise mode opens.
    @StateObject private var location = LocationService()

    /// Peavy Hall (OSU College of Forestry) fallback — same as home.
    private static let fallbackCamera = BasemapCamera(
        latitude: 44.56417, longitude: -123.28556, zoom: 16)

    @State private var camera = CruiseMapScreen.fallbackCamera
    @State private var cameraInitialised = false
    @State private var awaitingFirstFix = false
    @State private var selectedPinID: String?

    // Cruise data snapshot (reloaded from the repositories).
    @State private var projects: [Project] = []
    @State private var plots: [Plot] = []
    @State private var treesByPlot: [UUID: [Tree]] = [:]
    @State private var speciesByCode: [String: SpeciesConfig] = [:]

    // Sheets / covers / pushes.
    @State private var presentingProjectSheet = false
    @State private var presentingPlotSetup = false
    @State private var presentingDBHScan = false
    @State private var presentingHeightScan = false
    @State private var pushed: CruiseDestination?
    @State private var pendingDestination: CruiseDestination?
    @State private var closePlotCandidateID: UUID?

    // Add-tree chain scope (mirrors MapHomeScreen's chain plumbing).
    @State private var chainPlotID: UUID?
    @State private var chainTreeNumber: Int = 1
    @State private var chainTreeID: UUID?
    @State private var chainHeightPending = false

    // Project sheet "New project" one-time naming.
    @State private var namingNewProject = false
    @State private var newProjectName = ""

    public init() {}

    // MARK: Derived state

    /// The project the chip is scoped to: the persisted pick when it
    /// still exists, else the most recently updated project.
    private var currentProject: Project? {
        if let id = settings.currentCruiseProjectID,
           let hit = projects.first(where: { $0.id == id }) {
            return hit
        }
        return projects.max(by: { $0.updatedAt < $1.updatedAt })
    }

    /// The ACTIVE plot — the open (not-closed) plot started last.
    private var activePlot: Plot? {
        plots.filter { $0.closedAt == nil }
            .max(by: { $0.startedAt < $1.startedAt })
    }

    private func liveTrees(in plotID: UUID) -> [Tree] {
        (treesByPlot[plotID] ?? []).filter { $0.deletedAt == nil }
    }

    private func nextTreeNumber(in plotID: UUID) -> Int {
        (liveTrees(in: plotID).map(\.treeNumber).max() ?? 0) + 1
    }

    public var body: some View {
        ZStack {
            map
            attributionBadge
            VStack(spacing: ForestixSpace.xs) {
                topChrome
                Spacer()
            }
            VStack {
                Spacer()
                if let plot = selectedPlot {
                    plotPeekCard(for: plot)
                } else if let tree = selectedTree {
                    treePeekCard(for: tree)
                } else {
                    actionCluster
                }
            }
        }
        .background(ForestixPalette.canvas.ignoresSafeArea())
        .onAppear {
            startUp()
            reload()
        }
        .onDisappear { location.stop() }
        .onChange(of: location.latestSnapshot) { _, snap in
            recenterOnFirstFix(snap)
        }
        // Returning from a pushed editor (Tree detail, Plot summary…)
        // re-reads the repositories so pins/peeks reflect the edits.
        .onChange(of: pushed) { _, value in
            if value == nil { reload() }
        }
        #if os(iOS)
        .toolbar(.hidden, for: .navigationBar)
        .fullScreenCover(isPresented: $presentingPlotSetup,
                         onDismiss: { reload() }) { plotSetupCover }
        .fullScreenCover(isPresented: $presentingDBHScan,
                         onDismiss: continueChainAfterDBH) { dbhCover }
        .fullScreenCover(isPresented: $presentingHeightScan,
                         onDismiss: {
                             chainHeightPending = false
                             reload()
                         }) { heightCover }
        #endif
        .sheet(isPresented: $presentingProjectSheet, onDismiss: {
            namingNewProject = false
            newProjectName = ""
            if let destination = pendingDestination {
                pendingDestination = nil
                pushed = destination
            }
        }) { projectSheet }
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

    // MARK: Data

    private func reload() {
        projects = (try? environment.projectRepository.list()) ?? []
        if speciesByCode.isEmpty {
            let species = (try? environment.speciesRepository.list()) ?? []
            speciesByCode = Dictionary(
                uniqueKeysWithValues: species.map { ($0.code, $0) })
        }
        guard let project = currentProject else {
            plots = []
            treesByPlot = [:]
            return
        }
        plots = (try? environment.plotRepository.listByProject(project.id)) ?? []
        var byPlot: [UUID: [Tree]] = [:]
        for plot in plots {
            byPlot[plot.id] =
                (try? environment.treeRepository.listByPlot(plot.id,
                                                            includeDeleted: false)) ?? []
        }
        treesByPlot = byPlot
    }

    private func startUp() {
        location.requestAuthorization()
        location.start()
        guard !cameraInitialised else { return }
        cameraInitialised = true
        if let fix = LocationService.lastGlobalFix ?? location.latestSnapshot {
            camera = BasemapCamera(latitude: fix.latitude,
                                   longitude: fix.longitude, zoom: 16)
        } else if let project = currentProject,
                  let plot = (try? environment.plotRepository
                      .listByProject(project.id))?.first {
            camera = BasemapCamera(latitude: plot.centerLat,
                                   longitude: plot.centerLon, zoom: 16)
        } else {
            camera = Self.fallbackCamera
            awaitingFirstFix = true
        }
    }

    private func recenterOnFirstFix(_ snap: CLLocationSnapshot?) {
        guard awaitingFirstFix, let snap else { return }
        awaitingFirstFix = false
        withAnimation(.easeOut(duration: 0.3)) {
            camera = BasemapCamera(latitude: snap.latitude,
                                   longitude: snap.longitude, zoom: 16)
        }
    }

    // MARK: Map + markers

    private var baseTileCache: TileCache? {
        try? TileCache(rootURL: TileCache.defaultBasemapRoot(),
                       provider: .esriWorldImagery)
    }

    private var overlayTileCache: TileCache? {
        guard settings.overlayEnabled,
              settings.providerUsageAcknowledged,
              let template = settings.tileURLTemplate
        else { return nil }
        return try? TileCache(rootURL: TileCache.defaultBasemapRoot(),
                              provider: .fromUserTemplate(template))
    }

    private var map: some View {
        BasemapMapView(
            camera: $camera,
            baseTileCache: baseTileCache,
            overlayTileCache: overlayTileCache,
            markers: markers,
            selectedMarkerID: selectedPinID,
            youLocation: location.latestSnapshot.map {
                CoordinateConversions.LatLon(latitude: $0.latitude,
                                             longitude: $0.longitude)
            },
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
            })
        .ignoresSafeArea()
        .accessibilityIdentifier("cruiseMap.map")
    }

    /// Plot ring markers + cruise tree teardrops. Quick-measure entries
    /// are deliberately NOT read here.
    private var markers: [BasemapMarker] {
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

    private var selectedPlot: Plot? {
        guard let id = selectedPinID, id.hasPrefix("plot-") else { return nil }
        let raw = String(id.dropFirst("plot-".count))
        return plots.first { $0.id.uuidString == raw }
    }

    private var selectedTree: Tree? {
        guard let id = selectedPinID, id.hasPrefix("ctree-") else { return nil }
        let raw = String(id.dropFirst("ctree-".count))
        for trees in treesByPlot.values {
            if let hit = trees.first(where: { $0.id.uuidString == raw }) {
                return hit
            }
        }
        return nil
    }

    /// Esri attribution — required whenever the base layer draws.
    private var attributionBadge: some View {
        Text(TileCache.ProviderConfig.esriWorldImageryAttribution)
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

    // MARK: Top chrome (mock ① — back · project chip · GPS chip)

    private var topChrome: some View {
        HStack(spacing: ForestixSpace.xs) {
            Button {
                dismiss()
            } label: {
                Image(systemName: "chevron.backward")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(ForestixPalette.textSecondary)
                    .frame(width: 44, height: 44)
                    .background(Circle().fill(ForestixPalette.surface))
                    .overlay(Circle().stroke(ForestixPalette.divider, lineWidth: 1))
            }
            .buttonStyle(CruisePressableStyle())
            .accessibilityLabel("Back to map home")
            .accessibilityIdentifier("cruiseMap.back")

            Button {
                presentingProjectSheet = true
            } label: {
                HStack(spacing: 6) {
                    Text(currentProject?.name ?? "New project")
                        .font(.system(size: 12, weight: .bold))
                        .foregroundStyle(ForestixPalette.textPrimary)
                        .lineLimit(1)
                    Text("▾")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(ForestixPalette.textTertiary)
                }
                .padding(.horizontal, 11)
                .padding(.vertical, 12)
                .background(
                    RoundedRectangle(cornerRadius: ForestixRadius.control,
                                     style: .continuous)
                        .fill(ForestixPalette.surface))
                .overlay(
                    RoundedRectangle(cornerRadius: ForestixRadius.control,
                                     style: .continuous)
                        .stroke(ForestixPalette.divider, lineWidth: 1))
            }
            .buttonStyle(CruisePressableStyle())
            .accessibilityLabel("Project: \(currentProject?.name ?? "New project")")
            .accessibilityIdentifier("cruiseMap.projectChip")

            gpsChip

            Spacer()
        }
        .padding(.horizontal, 14)
        .padding(.top, ForestixSpace.xs)
    }

    private static let staleFixAge: TimeInterval = 5
    private static let lostFixAge: TimeInterval = 60

    /// Compact GPS chip (mock's "GPS ±3 m"): freshness dot + horizontal
    /// accuracy. The home keeps its full X/Y/Z chip; cruise chrome must
    /// leave room for the project chip.
    private var gpsChip: some View {
        TimelineView(.periodic(from: .now, by: 1)) { context in
            let snap = location.latestSnapshot ?? LocationService.lastGlobalFix
            let age = snap.map { max(0, context.date.timeIntervalSince($0.timestamp)) }
            let dot: Color = {
                guard snap != nil, let age else { return ForestixPalette.confidenceBad }
                if age > Self.lostFixAge { return ForestixPalette.confidenceBad }
                if age > Self.staleFixAge { return ForestixPalette.confidenceWarn }
                return ForestixPalette.confidenceOk
            }()
            HStack(spacing: 6) {
                Circle().fill(dot).frame(width: 7, height: 7)
                Text(snap.map { String(format: "±%.0f m", $0.horizontalAccuracyM) }
                     ?? "no fix")
                    .font(.system(size: 11, weight: .semibold, design: .monospaced))
                    .foregroundStyle(snap == nil ? ForestixPalette.textTertiary
                                                 : ForestixPalette.textPrimary)
            }
            .padding(.horizontal, 11)
            .padding(.vertical, 12)
            .background(
                RoundedRectangle(cornerRadius: ForestixRadius.control,
                                 style: .continuous)
                    .fill(ForestixPalette.surface))
            .overlay(
                RoundedRectangle(cornerRadius: ForestixRadius.control,
                                 style: .continuous)
                    .stroke(ForestixPalette.divider, lineWidth: 1))
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(snap.map {
                String(format: "GPS accuracy %.0f metres", $0.horizontalAccuracyM)
            } ?? "No GPS fix")
            .accessibilityIdentifier("cruiseMap.gps")
        }
    }

    // MARK: Bottom action cluster — the state-morphing (+) (mock ①)

    private var actionCluster: some View {
        VStack(spacing: 6) {
            if let plot = activePlot {
                tallyPill(for: plot)
                    .padding(.bottom, 6)
            }
            Button {
                primaryAction()
            } label: {
                ZStack {
                    Circle().fill(ForestixPalette.primary)
                    Image(systemName: "plus")
                        .font(.system(size: 30, weight: .semibold))
                        .foregroundStyle(ForestixPalette.primaryInk)
                }
                .frame(width: 74, height: 74)
                .overlay(Circle().stroke(ForestixPalette.surface, lineWidth: 4))
                // Scoped state (active plot): accent halo like the
                // mock's `.capture.scoped`.
                .overlay {
                    if activePlot != nil {
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
            .buttonStyle(CruisePressableStyle())
            .accessibilityLabel(primaryLabel)
            .accessibilityIdentifier("cruiseMap.primary")

            clusterLabel(primaryLabel)
        }
        .padding(.bottom, ForestixSpace.sm)
        .transition(.move(edge: .bottom).combined(with: .opacity))
    }

    /// LOCKED strings: "Start plot" / "Add tree · Plot N".
    private var primaryLabel: String {
        if let plot = activePlot {
            return "Add tree · Plot \(plot.plotNumber)"
        }
        return "Start plot"
    }

    private func primaryAction() {
        if let plot = activePlot {
            startAddTree(in: plot)
        } else {
            presentingPlotSetup = true
        }
    }

    /// LOCKED string: "N trees".
    private func tallyPill(for plot: Plot) -> some View {
        HStack(spacing: 7) {
            Circle().fill(ForestixPalette.accent).frame(width: 7, height: 7)
            Text("\(liveTrees(in: plot.id).count) trees")
                .font(.system(size: 11, weight: .bold, design: .monospaced))
                .foregroundStyle(ForestixPalette.textPrimary)
        }
        .padding(.horizontal, 13)
        .padding(.vertical, 7)
        .background(Capsule().fill(ForestixPalette.surface))
        .overlay(Capsule().stroke(ForestixPalette.divider, lineWidth: 1))
        .shadow(color: Color.black.opacity(0.14), radius: 5, y: 2)
        .accessibilityIdentifier("cruiseMap.tallyPill")
    }

    /// Dark-glass pill so the label stays legible over imagery (same
    /// rationale as the home's cluster labels).
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

    // MARK: Plot creation (Start plot → sampling-ring AR component)

    #if os(iOS)
    private var plotSetupCover: some View {
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
    private func createCruisePlot(radiusM: Double) {
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
        _ = try? environment.plotRepository.create(plot)
        reload()
    }

    /// One-time naming is the project sheet's job; a cruiser who taps
    /// "Start plot" before naming anything still gets a project —
    /// auto-named, renameable later.
    private func autoCreateProject() -> Project? {
        createProject(named: "Project \(projects.count + 1)")
    }

    @discardableResult
    private func createProject(named name: String) -> Project? {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        let now = Date()
        // Same defaults as HomeViewModel.create — calibration starts at
        // the spec's identity values until the cruiser runs Calibration.
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
        reload()
        return created
    }

    // MARK: Add-tree chain (DBH → Height through the Accept path)

    private func startAddTree(in plot: Plot) {
        chainPlotID = plot.id
        chainTreeNumber = nextTreeNumber(in: plot.id)
        chainTreeID = nil
        chainHeightPending = false
        withAnimation(.easeOut(duration: 0.18)) { selectedPinID = nil }
        presentingDBHScan = true
    }

    /// DBH cover dismissed. An accepted DBH hands straight off to the
    /// Height cover for the SAME tree — the chain; closing without
    /// accepting cancels it. (Same two-step pattern as the map home.)
    private func continueChainAfterDBH() {
        reload()
        if chainHeightPending {
            chainHeightPending = false
            presentingHeightScan = true
        }
    }

    /// The project's REAL calibration — the cruise chain must never run
    /// on `.identity` when the project has wall/cylinder fits.
    private func calibration(from project: Project?) -> ProjectCalibration {
        guard let project else { return .identity }
        return ProjectCalibration(
            depthNoiseMm: project.depthNoiseMm,
            dbhCorrectionAlpha: project.dbhCorrectionAlpha,
            dbhCorrectionBeta: project.dbhCorrectionBeta,
            vioDriftFraction: project.vioDriftFraction)
    }

    #if os(iOS)
    private var dbhCover: some View {
        NavigationStack {
            DBHScanScreen(
                viewModel: DBHScanViewModel(
                    calibration: calibration(from: currentProject)),
                onAccept: { result, meta in
                    saveChainDBH(result, meta: meta)
                    chainHeightPending = true
                    presentingDBHScan = false
                })
            .environmentObject(settings)
        }
    }

    private var heightCover: some View {
        NavigationStack {
            HeightScanScreen(
                viewModel: HeightScanViewModel(
                    calibration: calibration(from: currentProject)),
                onAccept: { result, meta in
                    saveChainHeight(result, meta: meta)
                    presentingHeightScan = false
                })
            .environmentObject(history)
            .environmentObject(settings)
        }
    }

    /// Accepted DBH → create the cruise Tree. Species defaults to the
    /// plot's most recent species when the meta sheet was skipped
    /// (zero-typing rule); GPS + auto-photo arrive on the metadata.
    private func saveChainDBH(_ result: DBHResult,
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

    /// Accepted Height → update the tree the DBH step just created.
    private func saveChainHeight(_ result: HeightResult,
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

    private func lastSpeciesCode(in plotID: UUID) -> String? {
        liveTrees(in: plotID)
            .sorted { $0.createdAt > $1.createdAt }
            .first { !$0.speciesCode.isEmpty }?
            .speciesCode
    }

    /// Horizontal metres between the capture fix and the plot centre —
    /// the tally sheet's hand-typed field, auto-computed.
    private func distanceFromPlotCenter(plotID: UUID,
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

    private static let peekTimeFormatter: DateFormatter = {
        let df = DateFormatter()
        df.locale = Locale(identifier: "en_US_POSIX")
        df.dateFormat = "HH:mm"
        return df
    }()

    private static let peekDateFormatter: DateFormatter = {
        let df = DateFormatter()
        df.locale = Locale(identifier: "en_US_POSIX")
        df.dateFormat = "d MMM · HH:mm"
        return df
    }()

    private func plotRadiusM(_ plot: Plot) -> Double {
        sqrt(Double(plot.plotAreaAcres) * 4046.8564224 / .pi)
    }

    private func plotPeekCard(for plot: Plot) -> some View {
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
                            Self.peekTimeFormatter.string(from: plot.startedAt)))
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
    private func statusChip(closed: Bool) -> some View {
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

    private func statCell(_ label: String, _ value: String,
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
    /// comes from the plot's own recorded area.
    private func plotStats(for plot: Plot, trees: [Tree]) -> PlotStats {
        PlotStatsCalculator.compute(
            plot: plot,
            cruiseDesign: effectiveDesign(),
            trees: trees,
            species: speciesByCode,
            volumeEquations: [:],
            hdFits: [:])
    }

    private func effectiveDesign() -> CruiseDesign {
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
    private func closePlot(id: UUID) {
        guard var plot = plots.first(where: { $0.id == id }) else { return }
        plot.closedAt = Date()
        plot.closedBy = "field"
        _ = try? environment.plotRepository.update(plot)
        reload()
    }

    // MARK: Tree peek (mock ④)

    private func treePeekCard(for tree: Tree) -> some View {
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

    private func peekTreeSubtitle(_ tree: Tree, plot: Plot?) -> String {
        var parts: [String] = []
        if let plot { parts.append("Plot \(plot.plotNumber)") }
        parts.append(Self.peekDateFormatter.string(from: tree.createdAt))
        return parts.joined(separator: " · ")
    }

    private func statusLabel(_ status: TreeStatus) -> String {
        switch status {
        case .live:         return "Live"
        case .deadStanding: return "Dead standing"
        case .deadDown:     return "Dead down"
        case .cull:         return "Cull"
        }
    }

    private func metricRow(label: String, value: String, sigma: String?,
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
            tierChip(tier)
        }
        .padding(.vertical, 6)
        .overlay(alignment: .bottom) {
            if divided {
                Rectangle().fill(ForestixPalette.divider).frame(height: 0.5)
            }
        }
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

    private func detailChip(_ label: String, _ value: String) -> some View {
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

    private func cruisePhotoThumb(_ name: String?) -> some View {
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

    // MARK: Project sheet (mock ⑤)

    private var projectSheet: some View {
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
                    "Export",
                    subtitle: "PDF · CSV ×5 · GeoJSON ×2 · SHP ×3",
                    icon: "square.and.arrow.up",
                    accessibilityID: "cruiseMap.project.export",
                    disabled: currentProject == nil
                ) {
                    pendingDestination = .export
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
                    pendingDestination = .cruiseSetup
                    presentingProjectSheet = false
                }

                // Relocated hub tools — small footer links.
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
                    // Phase A bridge — the old hub stays reachable until
                    // Phase B retires it.
                    footerTool("Classic view") {
                        pendingDestination = .classicHub
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
    }

    private var standSummarySubtitle: String {
        let closed = plots.filter { $0.closedAt != nil }.count
        return "Mean ± CI · \(closed) closed plot\(closed == 1 ? "" : "s")"
    }

    private func projectRow(_ project: Project) -> some View {
        let isCurrent = project.id == currentProject?.id
        let projectPlots = (try? environment.plotRepository
            .listByProject(project.id)) ?? []
        let treeCount = projectPlots.reduce(0) { sum, plot in
            sum + ((try? environment.treeRepository
                .listByPlot(plot.id, includeDeleted: false))?.count ?? 0)
        }
        return Button {
            settings.currentCruiseProjectID = project.id
            reload()
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

    private static let projectDateFormatter: DateFormatter = {
        let df = DateFormatter()
        df.locale = Locale(identifier: "en_US_POSIX")
        df.dateFormat = "d MMM"
        return df
    }()

    /// One-time naming — the ONLY thing a project ever asks for. Unit
    /// system comes from Settings; plots and trees auto-number.
    private var newProjectRow: some View {
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

    private func sheetChoiceRow(_ title: String,
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

    private func footerTool(_ title: String,
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

    private enum CruiseDestination: Hashable, Identifiable {
        case plotDetails(UUID)
        case treeDetails(UUID)
        case standSummary
        case export
        case cruiseSetup
        case fieldLog
        case reference
        case settings
        case classicHub
        var id: Self { self }
    }

    @ViewBuilder
    private func destinationView(_ destination: CruiseDestination) -> some View {
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
        case .cruiseSetup:
            if let project = currentProject {
                CruiseDesignScreen(project: project)
            }
        case .fieldLog:
            FieldLogScreen()
        case .reference:
            ReferenceLibraryScreen()
        case .settings:
            SettingsScreen()
        case .classicHub:
            TimberCruisingHubScreen()
        }
    }
}

// MARK: - Pressed feedback (same language as the map home)

private struct CruisePressableStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .opacity(configuration.isPressed ? 0.78 : 1)
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .animation(.easeOut(duration: 0.15), value: configuration.isPressed)
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
