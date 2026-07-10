// Phase 7 hardening — cruise-flow coordinator.
//
// The audit in `docs/AUDIT_PHASE_0_TO_7.md` found that the Phase 4 / 5
// screens were written but **never wired into the navigation tree** —
// the cruiser had no way to reach PlotCenter / PlotTally / AddTreeFlow
// from HomeScreen. This file fixes that gap:
//
//   HomeScreen → Project dashboard → [Go Cruise]
//                    ↓
//                CruiseFlowScreen (planned-plot picker)
//                    ↓
//                NavigationScreen  (compass to target)
//                    ↓  onArrival
//                PlotCenterScreen  (GPS averaging, 60 s)
//                    ↓  onAccept                         ↓ onTryOffset
//                PlotTallyScreen                         OffsetFlowScreen
//                    ↓              \                         ↓ onDone
//                AddTreeFlowScreen   ARBoundaryScreen    PlotTallyScreen
//                    ↓  onSaved                               ↑
//                (back to tally)                     (same tally path)
//                    ↓  "Close plot"
//                PlotSummaryScreen
//                    ↓
//                StandSummaryScreen
//
// The coordinator owns a single `LocationService` and — because we
// construct a fresh `ARKitSessionManager` inside each sensor screen's
// view model default — sensor sessions start/stop as the cruiser
// enters / leaves each AR-using screen. AR-boundary + DBH scan are
// expected to share sessions; that optimisation lands when the AR
// team merges its session-sharing branch (flagged as 7.1 in the
// audit report).

import SwiftUI
import Models
import Common
import Persistence
import Positioning
import Sensors

public struct CruiseFlowScreen: View {

    @EnvironmentObject private var environment: AppEnvironment
    @StateObject private var flow: CruiseFlowCoordinator

    public init(project: Project, design: CruiseDesign) {
        _flow = StateObject(wrappedValue:
            CruiseFlowCoordinator(project: project, design: design))
    }

    public var body: some View {
        NavigationStack(path: $flow.path) {
            plotPicker
                .navigationDestination(for: CruiseStep.self) { step in
                    routeView(for: step)
                }
        }
        .task {
            flow.configure(with: environment)
        }
    }

    // MARK: - Root: planned-plot picker

    @ViewBuilder
    private var plotPicker: some View {
        List {
            if flow.unvisitedPlots.isEmpty && flow.visitedPlots.isEmpty {
                Text("No planned plots yet. Go back to the project dashboard and run \"Design cruise\" first.")
                    .foregroundStyle(.secondary)
                    .padding(.vertical, 12)
            }
            if !flow.unvisitedPlots.isEmpty {
                Section("To do") {
                    ForEach(flow.unvisitedPlots) { pp in
                        Button {
                            flow.startNavigation(to: pp)
                        } label: {
                            plotRow(pp, visited: false)
                        }
                    }
                }
            }
            if !flow.visitedPlots.isEmpty {
                Section("Already visited") {
                    ForEach(flow.visitedPlots) { pp in
                        Button {
                            flow.continueVisited(plannedPlot: pp)
                        } label: {
                            plotRow(pp, visited: true)
                        }
                    }
                }
            }
        }
        .navigationTitle("Cruise — \(flow.project.name)")
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .refreshable { flow.refresh() }
        .alert("Couldn't start this plot",
               isPresented: Binding(
                get: { flow.errorMessage != nil },
                set: { if !$0 { flow.errorMessage = nil } })
        ) {
            Button("OK", role: .cancel) { flow.errorMessage = nil }
        } message: {
            Text(flow.errorMessage ?? "")
        }
    }

    private func plotRow(_ pp: PlannedPlot, visited: Bool) -> some View {
        HStack(spacing: ForestixSpace.sm) {
            VStack(alignment: .leading, spacing: 2) {
                Text("Plot \(pp.plotNumber)")
                    .font(ForestixType.bodyBold)
                Text(coordinatesCaption(pp))
                    .font(ForestixType.dataSmall)
                    .foregroundStyle(ForestixPalette.textTertiary)
            }
            Spacer(minLength: 0)
            if visited {
                Image(systemName: "checkmark.circle.fill")
                    .foregroundStyle(ForestixPalette.confidenceOk)
                    .accessibilityLabel("Visited")
            }
        }
        .contentShape(Rectangle())
    }

    /// Compact lat/lon line for the plot row. 4 decimals = ~11 m
    /// precision — enough for the cruiser to recognise a plot they've
    /// already seen on a paper map, without the six-decimal-float
    /// wall-of-text the old row used to show.
    private func coordinatesCaption(_ pp: PlannedPlot) -> String {
        String(format: "%.4f, %.4f", pp.plannedLat, pp.plannedLon)
    }

    // MARK: - Route table

    @ViewBuilder
    private func routeView(for step: CruiseStep) -> some View {
        switch step {
        case .navigate(let plannedId):
            navigateScreen(plannedId: plannedId)
        case .recordCenter(let plannedId):
            recordCenterScreen(plannedId: plannedId)
        case .offset(let plannedId):
            offsetScreen(plannedId: plannedId)
        case .tally(let plotId):
            tallyScreen(plotId: plotId)
        case .addTree(let plotId):
            addTreeScreen(plotId: plotId)
        case .treeDetail(let treeId):
            treeDetailScreen(treeId: treeId)
        case .arBoundary(let plotId):
            arBoundaryScreen(plotId: plotId)
        case .summarize(let plotId):
            summaryScreen(plotId: plotId)
        case .standSummary:
            standSummaryScreen()
        }
    }

    // MARK: - Step builders (each resolves the right dependencies from
    // the shared flow coordinator / app environment, then returns the
    // leaf screen).

    @ViewBuilder
    private func navigateScreen(plannedId: UUID) -> some View {
        if let target = flow.plannedPlot(id: plannedId) {
            NavigationScreen(viewModel: NavigationViewModel(
                target: target,
                location: flow.location,
                onArrival: { flow.pushRecordCenter(target: target) }))
                .navigationTitle("Plot \(target.plotNumber)")
        } else {
            Text("Missing planned plot.").foregroundStyle(.secondary)
        }
    }

    @ViewBuilder
    private func recordCenterScreen(plannedId: UUID) -> some View {
        if flow.plannedPlot(id: plannedId) != nil {
            PlotCenterScreen(
                viewModel: PlotCenterViewModel(location: flow.location),
                onAccept: { result in
                    flow.acceptCenter(plannedId: plannedId, result: result)
                },
                onTryOffset: { _ in
                    flow.pushOffset(plannedId: plannedId)
                })
                .navigationTitle("Record plot centre")
        } else {
            Text("Missing planned plot.").foregroundStyle(.secondary)
        }
    }

    @ViewBuilder
    private func offsetScreen(plannedId: UUID) -> some View {
        OffsetFlowScreen(
            viewModel: OffsetFlowViewModel(
                location: flow.location,
                session: flow.sharedARSession),
            onDone: { result in
                flow.acceptCenter(plannedId: plannedId, result: result)
            })
            .navigationTitle("Offset-from-opening")
    }

    @ViewBuilder
    private func tallyScreen(plotId: UUID) -> some View {
        if let plot = flow.plot(id: plotId) {
            var screen = PlotTallyScreen(viewModel: PlotTallyViewModel(
                project: flow.project, design: flow.design, plot: plot,
                plotRepo: environment.plotRepository,
                treeRepo: environment.treeRepository,
                speciesRepo: environment.speciesRepository,
                volRepo: environment.volumeEquationRepository,
                hdFitRepo: environment.hdFitRepository))
            let _ = {
                screen.onAddTree = { flow.pushAddTree(plotId: plotId) }
                screen.onOpenTree = { tree in flow.pushTreeDetail(tree: tree) }
                screen.onClosePlot = { flow.pushSummary(plotId: plotId) }
            }()
            screen
                .navigationTitle("Plot \(plot.plotNumber)")
                .toolbar {
                    ToolbarItem(placement: .primaryAction) {
                        Button {
                            flow.pushARBoundary(plotId: plotId)
                        } label: {
                            Label("AR Boundary", systemImage: "circle.dashed")
                        }
                    }
                }
        } else {
            Text("Plot has been removed.").foregroundStyle(.secondary)
        }
    }

    @ViewBuilder
    private func addTreeScreen(plotId: UUID) -> some View {
        if let plot = flow.plot(id: plotId),
           let payload = flow.addTreePayload(for: plot) {
            var screen = AddTreeFlowScreen(viewModel: AddTreeFlowViewModel(
                project: flow.project,
                design: flow.design,
                plot: plot,
                existingTrees: payload.existingTrees,
                speciesByCode: payload.speciesByCode,
                treeRepo: environment.treeRepository,
                recentSpeciesCodes: payload.recentSpeciesCodes))
            let _ = {
                screen.onSaved = { _ in flow.popToTally() }
            }()
            screen
        }
    }

    @ViewBuilder
    private func treeDetailScreen(treeId: UUID) -> some View {
        if let tree = flow.tree(id: treeId) {
            TreeDetailScreen(viewModel: TreeDetailViewModel(
                tree: tree,
                treeRepo: environment.treeRepository))
                .navigationTitle("Tree #\(tree.treeNumber)")
        }
    }

    @ViewBuilder
    private func arBoundaryScreen(plotId: UUID) -> some View {
        if let plot = flow.plot(id: plotId) {
            ARBoundaryScreen(viewModel: ARBoundaryViewModel(
                session: flow.sharedARSession))
                .navigationTitle("AR boundary — plot \(plot.plotNumber)")
        }
    }

    @ViewBuilder
    private func summaryScreen(plotId: UUID) -> some View {
        if let plot = flow.plot(id: plotId) {
            var screen = PlotSummaryScreen(viewModel: PlotSummaryViewModel(
                project: flow.project, design: flow.design, plot: plot,
                plotRepo: environment.plotRepository,
                treeRepo: environment.treeRepository,
                speciesRepo: environment.speciesRepository,
                volRepo: environment.volumeEquationRepository,
                hdFitRepo: environment.hdFitRepository))
            let _ = {
                screen.onClosed = { flow.onPlotClosed() }
            }()
            screen.navigationTitle("Plot \(plot.plotNumber) summary")
        }
    }

    @ViewBuilder
    private func standSummaryScreen() -> some View {
        StandSummaryScreen(viewModel: StandSummaryViewModel(
            project: flow.project,
            design: flow.design,
            plotRepo: environment.plotRepository,
            treeRepo: environment.treeRepository,
            speciesRepo: environment.speciesRepository,
            volRepo: environment.volumeEquationRepository,
            hdFitRepo: environment.hdFitRepository,
            stratumRepo: environment.stratumRepository,
            plannedRepo: environment.plannedPlotRepository))
            .navigationTitle("Stand summary")
    }
}

// MARK: - Path enum

public enum CruiseStep: Hashable, Sendable {
    case navigate(plannedPlotId: UUID)
    case recordCenter(plannedPlotId: UUID)
    case offset(plannedPlotId: UUID)
    case tally(plotId: UUID)
    case addTree(plotId: UUID)
    case treeDetail(treeId: UUID)
    case arBoundary(plotId: UUID)
    case summarize(plotId: UUID)
    case standSummary
}

// MARK: - Coordinator

@MainActor
public final class CruiseFlowCoordinator: ObservableObject {

    @Published public var path: [CruiseStep] = []
    @Published public private(set) var unvisitedPlots: [PlannedPlot] = []
    @Published public private(set) var visitedPlots: [PlannedPlot] = []
    @Published public var errorMessage: String?

    public let project: Project
    public let design: CruiseDesign
    public let location = LocationService()
    public let sharedARSession = ARKitSessionManager()

    private var env: AppEnvironment?
    private var plotsById: [UUID: Plot] = [:]
    private var treesById: [UUID: Tree] = [:]

    public init(project: Project, design: CruiseDesign) {
        self.project = project
        self.design = design
    }

    public func configure(with environment: AppEnvironment) {
        self.env = environment
        location.requestAuthorization()
        location.start()
        refresh()
    }

    public func refresh() {
        guard let env = env else { return }
        do {
            let planned = try env.plannedPlotRepository.listByProject(project.id)
                .sorted(by: { $0.plotNumber < $1.plotNumber })
            unvisitedPlots = planned.filter { !$0.visited }
            visitedPlots   = planned.filter { $0.visited }

            let plots = try env.plotRepository.listByProject(project.id)
            plotsById = Dictionary(uniqueKeysWithValues: plots.map { ($0.id, $0) })
        } catch {
            errorMessage = "Couldn't load plots: \(error.localizedDescription). Try pulling to refresh, or go back and retry."
        }
    }

    // MARK: - Lookups

    public func plannedPlot(id: UUID) -> PlannedPlot? {
        (unvisitedPlots + visitedPlots).first(where: { $0.id == id })
    }
    public func plot(id: UUID) -> Plot? { plotsById[id] }
    public func tree(id: UUID) -> Tree? { treesById[id] }

    public struct AddTreePayload {
        public let existingTrees: [Tree]
        public let speciesByCode: [String: SpeciesConfig]
        public let recentSpeciesCodes: [String]
    }

    public func addTreePayload(for plot: Plot) -> AddTreePayload? {
        guard let env = env else { return nil }
        do {
            let trees = try env.treeRepository.listByPlot(plot.id, includeDeleted: false)
            let species = try env.speciesRepository.list()
            let recent = try env.treeRepository.recentSpeciesCodes(
                projectId: project.id, limit: 5)
            return AddTreePayload(
                existingTrees: trees,
                speciesByCode: Dictionary(uniqueKeysWithValues: species.map { ($0.code, $0) }),
                recentSpeciesCodes: recent)
        } catch {
            errorMessage = "Couldn't load tree data: \(error.localizedDescription). Pull to refresh, or retry."
            return nil
        }
    }

    // MARK: - Transitions

    public func startNavigation(to pp: PlannedPlot) {
        path.append(.navigate(plannedPlotId: pp.id))
    }

    public func continueVisited(plannedPlot pp: PlannedPlot) {
        // If a Plot row already exists for this planned plot, resume its
        // tally. Otherwise restart from the centre-recording step.
        if let plot = plotsById.values.first(where: { $0.plannedPlotId == pp.id }) {
            path.append(.tally(plotId: plot.id))
        } else {
            path.append(.recordCenter(plannedPlotId: pp.id))
        }
    }

    public func pushRecordCenter(target: PlannedPlot) {
        // Replace the top of the path (navigate → recordCenter) rather
        // than stacking: the cruiser can't "go back to navigation"
        // after arriving.
        if let last = path.last, case .navigate = last {
            path.removeLast()
        }
        path.append(.recordCenter(plannedPlotId: target.id))
    }

    public func pushOffset(plannedId: UUID) {
        if let last = path.last, case .recordCenter = last {
            path.removeLast()
        }
        path.append(.offset(plannedPlotId: plannedId))
    }

    public func acceptCenter(plannedId: UUID, result: PlotCenterResult) {
        guard let env = env else { return }
        do {
            // Persist a Plot row; mark PlannedPlot.visited.
            let plotNumber = plannedPlot(id: plannedId)?.plotNumber ?? 0
            let areaAcres = design.plotAreaAcres ?? 0.1
            let plot = Plot(
                id: UUID(), projectId: project.id,
                plannedPlotId: plannedId,
                plotNumber: plotNumber,
                centerLat: result.lat, centerLon: result.lon,
                positionSource: result.source,
                positionTier: result.tier,
                gpsNSamples: result.nSamples,
                gpsMedianHAccuracyM: result.medianHAccuracyM,
                gpsSampleStdXyM: result.sampleStdXyM,
                offsetWalkM: result.offsetWalkM,
                slopeDeg: 0, aspectDeg: 0,
                plotAreaAcres: areaAcres,
                startedAt: Date(),
                closedAt: nil, closedBy: nil,
                notes: "", coverPhotoPath: nil, panoramaPath: nil)
            _ = try env.plotRepository.create(plot)
            plotsById[plot.id] = plot

            if var pp = plannedPlot(id: plannedId) {
                pp.visited = true
                _ = try env.plannedPlotRepository.update(pp)
            }
            refresh()

            // Pop record-centre / offset, push tally.
            if let last = path.last,
               case .recordCenter = last { path.removeLast() }
            else if let last = path.last,
                    case .offset = last { path.removeLast() }
            path.append(.tally(plotId: plot.id))
            ForestixLogger.log(.plotOpened(plotId: plot.id,
                                           projectId: project.id))
        } catch {
            errorMessage = "Couldn't save the plot centre: \(error.localizedDescription). Check storage, then re-run the 60-second GPS fix."
        }
    }

    public func pushAddTree(plotId: UUID)   { path.append(.addTree(plotId: plotId)) }
    public func pushTreeDetail(tree: Tree)  {
        treesById[tree.id] = tree
        path.append(.treeDetail(treeId: tree.id))
    }
    public func pushARBoundary(plotId: UUID) { path.append(.arBoundary(plotId: plotId)) }
    public func pushSummary(plotId: UUID)    { path.append(.summarize(plotId: plotId)) }
    public func popToTally() {
        // AddTreeFlow and TreeDetail both pop back to the tally step.
        while let last = path.last, last != .tally(plotId: plotIdInPath()) {
            path.removeLast()
            if case .tally = path.last ?? .standSummary { break }
        }
    }

    private func plotIdInPath() -> UUID {
        for step in path.reversed() {
            if case .tally(let id) = step { return id }
        }
        return UUID()
    }

    public func onPlotClosed() {
        refresh()
        // Pop the summary + tally; push stand summary so the cruiser
        // can verify the roll-up before moving to the next plot.
        while !path.isEmpty, case .standSummary = path.last ?? .standSummary {
            path.removeLast()
        }
        path.removeAll()        // back to plot picker
        path.append(.standSummary)
    }
}
