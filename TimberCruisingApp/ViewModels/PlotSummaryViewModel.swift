// Phase 5 §5.4 PlotSummary view model. REQ-TAL-006, REQ-AGG-001, §7.4.
//
// Responsibilities:
//   • Run pure validators (PlotValidation.validatePlotForClose) and surface
//     errors / warnings blocking the close.
//   • Show the final PlotStats for the plot (TPA, BA/ac, QMD, V/ac).
//   • On confirm: set `plot.closedAt`, persist, then trigger a synchronous
//     H–D rolling update for every species with ≥ minN measured heights
//     across the entire project. REQ §7.4 ceiling is <500ms.

import Foundation
import Combine
import Models
import Common
import Persistence
import InventoryEngine

@MainActor
public final class PlotSummaryViewModel: ObservableObject {

    // Input
    public let project: Project
    public let design: CruiseDesign
    public private(set) var plot: Plot

    // Dependencies
    private let plotRepo: any PlotRepository
    private let treeRepo: any TreeRepository
    private let speciesRepo: any SpeciesConfigRepository
    private let volRepo: any VolumeEquationRepository
    private let hdFitRepo: any HeightDiameterFitRepository

    // State
    @Published public private(set) var trees: [Tree] = []
    @Published public private(set) var speciesByCode: [String: SpeciesConfig] = [:]
    @Published public private(set) var validation: ValidationResult = .ok
    @Published public private(set) var stats: PlotStats = .empty
    @Published public private(set) var hdFitsByProject: [String: HDModel.Fit] = [:]
    @Published public private(set) var hdFitDurationMs: Double = 0
    @Published public private(set) var closedAt: Date? = nil
    @Published public private(set) var isLoading: Bool = false
    @Published public private(set) var isClosing: Bool = false
    @Published public private(set) var errorMessage: String? = nil

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
        self.closedAt = plot.closedAt
    }

    // MARK: - Load & compute

    public func refresh() {
        isLoading = true
        defer { isLoading = false }
        do {
            trees = try treeRepo.listByPlot(plot.id, includeDeleted: false)
            speciesByCode = Dictionary(
                uniqueKeysWithValues: try speciesRepo.list().map { ($0.code, $0) })
            validation = PlotValidation.validatePlotForClose(
                plot: plot, trees: trees, speciesByCode: speciesByCode)
            recomputeStats()
            loadProjectHDFits()
            errorMessage = nil
        } catch {
            errorMessage = "Load failed: \(error.localizedDescription)"
        }
    }

    private func recomputeStats() {
        var volEquations: [String: any InventoryEngine.VolumeEquation] = [:]
        do {
            let records = try volRepo.list()
            var byId: [String: any InventoryEngine.VolumeEquation] = [:]
            for r in records {
                if let eq = VolumeEquationFactory.make(from: r) {
                    byId[r.id] = eq
                }
            }
            for (code, sp) in speciesByCode {
                if let eq = byId[sp.volumeEquationId] {
                    volEquations[code] = eq
                }
            }
        } catch {
            // Non-fatal: fall back to an empty map; live trees still aggregate
            // TPA/BA/QMD, just not volume.
        }
        stats = PlotStatsCalculator.compute(
            plot: plot,
            cruiseDesign: design,
            trees: trees,
            species: speciesByCode,
            volumeEquations: volEquations,
            hdFits: hdFitsByProject)
    }

    private func loadProjectHDFits() {
        do {
            hdFitsByProject.removeAll()
            for fit in try hdFitRepo.listByProject(project.id) {
                if let f = HDModel.Fit.fromCoefficients(
                    fit.coefficients, nObs: fit.nObs, rmse: fit.rmse) {
                    hdFitsByProject[fit.speciesCode] = f
                }
            }
        } catch {
            // Non-fatal for display.
        }
    }

    // MARK: - Close

    /// Close the plot: stamp closedAt/closedBy, persist, then run the H–D
    /// rolling update across all project species with ≥ 8 measured heights.
    /// All work is synchronous on the @MainActor — REQ §7.4 budget <500ms.
    ///
    /// Integrity note: the plot-update and the H–D fit writes are two
    /// separate Core Data transactions (see RepositoryHelpers). If the
    /// H–D rollup throws *after* the plot row has already been stamped
    /// closedAt, we roll back the closedAt on the plot so the flow
    /// isn't stranded in a half-closed state — the cruiser sees the
    /// error, hits retry, and the close works atomically next time.
    public func close(closedBy: String = "field") {
        guard validation.canClose else { return }
        guard !isClosing else { return }
        isClosing = true
        defer { isClosing = false }

        let startWall = Date()
        let originalPlot = plot
        var p = plot
        let now = Date()
        p.closedAt = now
        p.closedBy = closedBy

        do {
            plot = try plotRepo.update(p)
            closedAt = plot.closedAt
            do {
                try updateProjectHDFits(now: now)
            } catch {
                // Rollback the closedAt stamp — the HD rollup is a
                // required side-effect of closing, so a failure leaves
                // the plot logically open.
                plot = (try? plotRepo.update(originalPlot)) ?? originalPlot
                closedAt = plot.closedAt
                throw error
            }
            hdFitDurationMs = Date().timeIntervalSince(startWall) * 1000
            errorMessage = nil
        } catch {
            errorMessage = "Close failed: \(error.localizedDescription). Your trees are saved; try again when you have signal."
        }
    }

    /// External reset for the error alert.
    public func clearError() { errorMessage = nil }

    /// Loop over every species in the project, gather all measured heights
    /// from every closed plot + the just-closed plot, and run the rolling fit.
    /// Silently skips species with < minN observations.
    private func updateProjectHDFits(now: Date) throws {
        let minN = 8
        let allPlots = try plotRepo.listByProject(project.id)
        let plotIds = allPlots.map(\.id)
        var byCode: [String: [(Float, Float)]] = [:]
        for pid in plotIds {
            let plotTrees = try treeRepo.listByPlot(pid, includeDeleted: false)
            for t in plotTrees where t.heightSource == "measured" {
                if let h = t.heightM, h > 1.3, t.dbhCm > 0 {
                    byCode[t.speciesCode, default: []].append((t.dbhCm, h))
                }
            }
        }
        for (code, obs) in byCode where obs.count >= minN {
            let pairs = obs.map { (dbhCm: $0.0, heightM: $0.1) }
            _ = try hdFitRepo.recomputeForSpecies(
                projectId: project.id,
                speciesCode: code,
                observations: pairs,
                minN: minN,
                now: now)
        }
        loadProjectHDFits()
    }

    // MARK: - Preview

    public static func preview(
        trees: [Tree] = [],
        stats: PlotStats = .empty,
        validation: ValidationResult = .ok
    ) -> PlotSummaryViewModel {
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
            centerLat: 45.1, centerLon: -122.6,
            positionSource: .gpsAveraged, positionTier: .B,
            gpsNSamples: 30, gpsMedianHAccuracyM: 4.5, gpsSampleStdXyM: 2.8,
            offsetWalkM: nil, slopeDeg: 0, aspectDeg: 0,
            plotAreaAcres: 0.1,
            startedAt: Date(timeIntervalSince1970: 0),
            closedAt: nil, closedBy: nil,
            notes: "", coverPhotoPath: nil, panoramaPath: nil)
        let vm = PlotSummaryViewModel(
            project: project, design: design, plot: plot,
            plotRepo: StubSumPlotRepo(), treeRepo: StubSumTreeRepo(),
            speciesRepo: StubSumSpeciesRepo(),
            volRepo: StubSumVolRepo(),
            hdFitRepo: StubSumHDFitRepo())
        vm.trees = trees
        vm.stats = stats
        vm.validation = validation
        return vm
    }
}

// MARK: - Preview stubs

private final class StubSumPlotRepo: PlotRepository {
    func create(_ p: Plot) throws -> Plot { p }
    func read(id: UUID) throws -> Plot? { nil }
    func update(_ p: Plot) throws -> Plot { p }
    func delete(id: UUID) throws {}
    func listByProject(_ projectId: UUID) throws -> [Plot] { [] }
    func closed(projectId: UUID) throws -> [Plot] { [] }
    func byPlotNumber(projectId: UUID, plotNumber: Int) throws -> Plot? { nil }
}

private final class StubSumTreeRepo: TreeRepository {
    func create(_ t: Tree) throws -> Tree { t }
    func read(id: UUID, includeDeleted: Bool) throws -> Tree? { nil }
    func update(_ t: Tree) throws -> Tree { t }
    func delete(id: UUID, at date: Date) throws {}
    func hardDelete(id: UUID) throws {}
    func listByPlot(_ plotId: UUID, includeDeleted: Bool) throws -> [Tree] { [] }
    func bySpeciesInProject(_ projectId: UUID, speciesCode: String, includeDeleted: Bool) throws -> [Tree] { [] }
    func recentSpeciesCodes(projectId: UUID, limit: Int) throws -> [String] { [] }
}

private final class StubSumSpeciesRepo: SpeciesConfigRepository {
    func create(_ s: SpeciesConfig) throws -> SpeciesConfig { s }
    func read(code: String) throws -> SpeciesConfig? { nil }
    func update(_ s: SpeciesConfig) throws -> SpeciesConfig { s }
    func delete(code: String) throws {}
    func list() throws -> [SpeciesConfig] { [] }
}

private final class StubSumVolRepo: VolumeEquationRepository {
    func create(_ v: Models.VolumeEquation) throws -> Models.VolumeEquation { v }
    func read(id: String) throws -> Models.VolumeEquation? { nil }
    func update(_ v: Models.VolumeEquation) throws -> Models.VolumeEquation { v }
    func delete(id: String) throws {}
    func list() throws -> [Models.VolumeEquation] { [] }
}

private final class StubSumHDFitRepo: HeightDiameterFitRepository {
    func create(_ f: HeightDiameterFit) throws -> HeightDiameterFit { f }
    func read(id: UUID) throws -> HeightDiameterFit? { nil }
    func update(_ f: HeightDiameterFit) throws -> HeightDiameterFit { f }
    func delete(id: UUID) throws {}
    func forProjectAndSpecies(projectId: UUID, speciesCode: String) throws -> HeightDiameterFit? { nil }
    func listByProject(_ projectId: UUID) throws -> [HeightDiameterFit] { [] }
}
