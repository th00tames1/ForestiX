// Phase 5 §5.1 PlotTallyScreen view model. REQ-TAL-005/006.
//
// Owns the in-memory tree list for an open plot + live statistics.
// Re-reads trees from the repo on demand (after add/soft-delete),
// recomputes PlotStats via the pure engine call. Preloads species,
// volume equations, and H–D fits so `addTreeCompleted()` doesn't
// re-touch the DB for the live-stats refresh.

import Foundation
import Combine
import Models
import Persistence
import InventoryEngine

@MainActor
public final class PlotTallyViewModel: ObservableObject {

    // Inputs
    public let project: Project
    public let design: CruiseDesign
    public private(set) var plot: Plot

    // Live state
    @Published public private(set) var trees: [Tree] = []
    @Published public private(set) var stats: PlotStats = .empty
    @Published public private(set) var isLoading: Bool = false
    @Published public private(set) var errorMessage: String?

    // Cached lookup tables
    public private(set) var speciesByCode: [String: SpeciesConfig] = [:]
    public private(set) var volumeEquations: [String: any InventoryEngine.VolumeEquation] = [:]
    public private(set) var hdFits: [String: HDModel.Fit] = [:]

    // Repositories
    private let plotRepo: any PlotRepository
    private let treeRepo: any TreeRepository
    private let speciesRepo: any SpeciesConfigRepository
    private let volRepo: any VolumeEquationRepository
    private let hdFitRepo: any HeightDiameterFitRepository

    public init(
        project: Project,
        design: CruiseDesign,
        plot: Plot,
        plotRepo: any PlotRepository,
        treeRepo: any TreeRepository,
        speciesRepo: any SpeciesConfigRepository,
        volRepo: any VolumeEquationRepository,
        hdFitRepo: any HeightDiameterFitRepository
    ) {
        self.project = project
        self.design = design
        self.plot = plot
        self.plotRepo = plotRepo
        self.treeRepo = treeRepo
        self.speciesRepo = speciesRepo
        self.volRepo = volRepo
        self.hdFitRepo = hdFitRepo
    }

    public func refresh() {
        isLoading = true
        defer { isLoading = false }
        do {
            // Include soft-deleted for UI "undelete" affordance.
            trees = try treeRepo.listByPlot(plot.id, includeDeleted: true)
            if speciesByCode.isEmpty {
                speciesByCode = Dictionary(
                    uniqueKeysWithValues: try speciesRepo.list().map { ($0.code, $0) })
            }
            if volumeEquations.isEmpty {
                let records = try volRepo.list()
                for r in records {
                    if let eq = VolumeEquationFactory.make(from: r) {
                        volumeEquations[r.id] = eq
                    }
                }
            }
            // Rebuild H–D fits each refresh (cheap — #species × few bytes).
            hdFits.removeAll()
            for fit in try hdFitRepo.listByProject(project.id) {
                if let f = HDModel.Fit.fromCoefficients(
                    fit.coefficients, nObs: fit.nObs, rmse: fit.rmse) {
                    hdFits[fit.speciesCode] = f
                }
            }
            recomputeStats()
            errorMessage = nil
        } catch {
            errorMessage = "Could not load plot data: \(error.localizedDescription)"
        }
    }

    /// REQ-TAL-005: recompute live stats from the current `trees` list +
    /// cached lookup tables. Pure — no I/O.
    public func recomputeStats() {
        let volByTree = volumeEquationsForTrees()
        stats = PlotStatsCalculator.compute(
            plot: plot,
            cruiseDesign: design,
            trees: trees,
            species: speciesByCode,
            volumeEquations: volByTree,
            hdFits: hdFits)
    }

    /// Maps speciesCode → VolumeEquation instance via each species' record id.
    private func volumeEquationsForTrees() -> [String: any InventoryEngine.VolumeEquation] {
        var out: [String: any InventoryEngine.VolumeEquation] = [:]
        for (code, sp) in speciesByCode {
            if let eq = volumeEquations[sp.volumeEquationId] {
                out[code] = eq
            }
        }
        return out
    }

    // MARK: - Tree CRUD

    public func softDelete(treeId: UUID) {
        do {
            try treeRepo.delete(id: treeId)
            refresh()
        } catch {
            errorMessage = "Delete failed: \(error.localizedDescription)"
        }
    }

    public func undelete(treeId: UUID) {
        do {
            if var t = try treeRepo.read(id: treeId, includeDeleted: true) {
                t.deletedAt = nil
                t.updatedAt = Date()
                _ = try treeRepo.update(t)
                refresh()
            }
        } catch {
            errorMessage = "Undelete failed: \(error.localizedDescription)"
        }
    }

    /// Call this after a successful AddTreeFlow save — refreshes list +
    /// stats in a single pass so the header strip updates within the
    /// REQ-TAL-005 300 ms budget.
    public func addTreeCompleted() {
        refresh()
    }

    /// Next tree number (live count + 1). Used by AddTreeFlow to pre-fill.
    public var nextTreeNumber: Int {
        (liveTrees.map(\.treeNumber).max() ?? 0) + 1
    }

    public var liveTrees: [Tree] {
        trees.filter { $0.deletedAt == nil }
    }

    public var softDeletedTrees: [Tree] {
        trees.filter { $0.deletedAt != nil }
    }

    // MARK: - Preview

    public static func preview(
        trees: [Tree] = [],
        stats: PlotStats = .empty
    ) -> PlotTallyViewModel {
        let projectId = UUID()
        let project = Project(
            id: projectId, name: "Preview", description: "",
            owner: "preview",
            createdAt: Date(timeIntervalSince1970: 0),
            updatedAt: Date(timeIntervalSince1970: 0),
            units: .metric, breastHeightConvention: .uphill,
            slopeCorrection: false,
            lidarBiasMm: 0, depthNoiseMm: 0,
            dbhCorrectionAlpha: 0, dbhCorrectionBeta: 1,
            vioDriftFraction: 0.02)
        let design = CruiseDesign(
            id: UUID(), projectId: projectId,
            plotType: .fixedArea, plotAreaAcres: 0.1,
            baf: nil, samplingScheme: .systematicGrid,
            gridSpacingMeters: 50)
        let plot = Plot(
            id: UUID(), projectId: projectId, plannedPlotId: nil,
            plotNumber: 1,
            centerLat: 45.123456, centerLon: -122.678901,
            positionSource: .gpsAveraged, positionTier: .B,
            gpsNSamples: 30, gpsMedianHAccuracyM: 4.5, gpsSampleStdXyM: 2.8,
            offsetWalkM: nil, slopeDeg: 0, aspectDeg: 0,
            plotAreaAcres: 0.1,
            startedAt: Date(timeIntervalSince1970: 0),
            closedAt: nil, closedBy: nil,
            notes: "", coverPhotoPath: nil, panoramaPath: nil)
        let vm = PlotTallyViewModel(
            project: project, design: design, plot: plot,
            plotRepo: StubPlotRepo(), treeRepo: StubTreeRepo(),
            speciesRepo: StubSpeciesRepo(),
            volRepo: StubVolRepo(), hdFitRepo: StubHDFitRepo())
        vm.trees = trees
        vm.stats = stats
        return vm
    }
}

// MARK: - Preview stubs

private final class StubPlotRepo: PlotRepository {
    func create(_ p: Plot) throws -> Plot { p }
    func read(id: UUID) throws -> Plot? { nil }
    func update(_ p: Plot) throws -> Plot { p }
    func delete(id: UUID) throws {}
    func listByProject(_ projectId: UUID) throws -> [Plot] { [] }
    func closed(projectId: UUID) throws -> [Plot] { [] }
    func byPlotNumber(projectId: UUID, plotNumber: Int) throws -> Plot? { nil }
}

private final class StubTreeRepo: TreeRepository {
    func create(_ t: Tree) throws -> Tree { t }
    func read(id: UUID, includeDeleted: Bool) throws -> Tree? { nil }
    func update(_ t: Tree) throws -> Tree { t }
    func delete(id: UUID, at date: Date) throws {}
    func hardDelete(id: UUID) throws {}
    func listByPlot(_ plotId: UUID, includeDeleted: Bool) throws -> [Tree] { [] }
    func bySpeciesInProject(_ projectId: UUID, speciesCode: String, includeDeleted: Bool) throws -> [Tree] { [] }
    func recentSpeciesCodes(projectId: UUID, limit: Int) throws -> [String] { [] }
}

private final class StubSpeciesRepo: SpeciesConfigRepository {
    func create(_ s: SpeciesConfig) throws -> SpeciesConfig { s }
    func read(code: String) throws -> SpeciesConfig? { nil }
    func update(_ s: SpeciesConfig) throws -> SpeciesConfig { s }
    func delete(code: String) throws {}
    func list() throws -> [SpeciesConfig] { [] }
}

private final class StubVolRepo: VolumeEquationRepository {
    func create(_ v: Models.VolumeEquation) throws -> Models.VolumeEquation { v }
    func read(id: String) throws -> Models.VolumeEquation? { nil }
    func update(_ v: Models.VolumeEquation) throws -> Models.VolumeEquation { v }
    func delete(id: String) throws {}
    func list() throws -> [Models.VolumeEquation] { [] }
}

private final class StubHDFitRepo: HeightDiameterFitRepository {
    func create(_ f: HeightDiameterFit) throws -> HeightDiameterFit { f }
    func read(id: UUID) throws -> HeightDiameterFit? { nil }
    func update(_ f: HeightDiameterFit) throws -> HeightDiameterFit { f }
    func delete(id: UUID) throws {}
    func forProjectAndSpecies(projectId: UUID, speciesCode: String) throws -> HeightDiameterFit? { nil }
    func listByProject(_ projectId: UUID) throws -> [HeightDiameterFit] { [] }
}
