// Phase 5 §5.5 StandSummary view model. REQ-AGG-003, §7.5.
//
// Aggregates every closed plot in the project into stratified stand-level
// stats (mean ± SE, 95% CI) for TPA, BA/ac, and gross V/ac. When no strata
// exist, every plot falls into a single "__unstratified__" bucket so the
// screen still renders without a stratum map.

import Foundation
import Combine
import Models
import Common
import Persistence
import InventoryEngine

@MainActor
public final class StandSummaryViewModel: ObservableObject {

    public let project: Project
    public let design: CruiseDesign

    private let plotRepo: any PlotRepository
    private let treeRepo: any TreeRepository
    private let speciesRepo: any SpeciesConfigRepository
    private let volRepo: any VolumeEquationRepository
    private let hdFitRepo: any HeightDiameterFitRepository
    private let stratumRepo: any StratumRepository
    private let plannedRepo: any PlannedPlotRepository

    @Published public private(set) var closedPlots: [Plot] = []
    @Published public private(set) var strata: [Stratum] = []
    @Published public private(set) var tpaStat: StandStat = .empty
    @Published public private(set) var baStat: StandStat = .empty
    @Published public private(set) var volStat: StandStat = .empty
    @Published public private(set) var totalLiveTreeCount: Int = 0
    @Published public private(set) var perPlotStats: [(plot: Plot, stats: PlotStats)] = []
    @Published public private(set) var isLoading: Bool = false
    @Published public private(set) var errorMessage: String?

    public init(
        project: Project,
        design: CruiseDesign,
        plotRepo: any PlotRepository,
        treeRepo: any TreeRepository,
        speciesRepo: any SpeciesConfigRepository,
        volRepo: any VolumeEquationRepository,
        hdFitRepo: any HeightDiameterFitRepository,
        stratumRepo: any StratumRepository,
        plannedRepo: any PlannedPlotRepository
    ) {
        self.project = project
        self.design = design
        self.plotRepo = plotRepo
        self.treeRepo = treeRepo
        self.speciesRepo = speciesRepo
        self.volRepo = volRepo
        self.hdFitRepo = hdFitRepo
        self.stratumRepo = stratumRepo
        self.plannedRepo = plannedRepo
    }

    public func refresh() {
        isLoading = true
        defer { isLoading = false }
        do {
            closedPlots = try plotRepo.closed(projectId: project.id)
            strata = try stratumRepo.listByProject(project.id)

            let planned = try plannedRepo.listByProject(project.id)
            let plannedById = Dictionary(uniqueKeysWithValues: planned.map { ($0.id, $0) })

            let species = try speciesRepo.list()
            let speciesByCode = Dictionary(uniqueKeysWithValues: species.map { ($0.code, $0) })

            var volById: [String: any InventoryEngine.VolumeEquation] = [:]
            for r in try volRepo.list() {
                if let eq = VolumeEquationFactory.make(from: r) {
                    volById[r.id] = eq
                }
            }
            var volByCode: [String: any InventoryEngine.VolumeEquation] = [:]
            for (code, sp) in speciesByCode {
                if let eq = volById[sp.volumeEquationId] { volByCode[code] = eq }
            }

            var hdFits: [String: HDModel.Fit] = [:]
            for fit in try hdFitRepo.listByProject(project.id) {
                if let f = HDModel.Fit.fromCoefficients(
                    fit.coefficients, nObs: fit.nObs, rmse: fit.rmse) {
                    hdFits[fit.speciesCode] = f
                }
            }

            var perPlot: [(Plot, PlotStats)] = []
            var tpaRows: [(String, Double)] = []
            var baRows: [(String, Double)] = []
            var volRows: [(String, Double)] = []
            var liveTotal = 0

            for plot in closedPlots {
                let trees = try treeRepo.listByPlot(plot.id, includeDeleted: false)
                let stats = PlotStatsCalculator.compute(
                    plot: plot,
                    cruiseDesign: design,
                    trees: trees,
                    species: speciesByCode,
                    volumeEquations: volByCode,
                    hdFits: hdFits)
                perPlot.append((plot, stats))
                liveTotal += stats.liveTreeCount

                let key = stratumKey(for: plot, plannedById: plannedById)
                tpaRows.append((key, Double(stats.tpa)))
                baRows.append((key, Double(stats.baPerAcreM2)))
                volRows.append((key, Double(stats.grossVolumePerAcreM3)))
            }

            let stratumAreas: [String: Double] = Dictionary(
                uniqueKeysWithValues: strata.map { ($0.id.uuidString, Double($0.areaAcres)) })

            tpaStat = StandStatsCalculator.compute(
                plotValues: tpaRows, stratumAreasAcres: stratumAreas)
            baStat = StandStatsCalculator.compute(
                plotValues: baRows, stratumAreasAcres: stratumAreas)
            volStat = StandStatsCalculator.compute(
                plotValues: volRows, stratumAreasAcres: stratumAreas)

            totalLiveTreeCount = liveTotal
            perPlotStats = perPlot
            errorMessage = nil
        } catch {
            errorMessage = "Load failed: \(error.localizedDescription)"
        }
    }

    private func stratumKey(for plot: Plot, plannedById: [UUID: PlannedPlot]) -> String {
        if let ppid = plot.plannedPlotId,
           let pp = plannedById[ppid],
           let sid = pp.stratumId {
            return sid.uuidString
        }
        return "__unstratified__"
    }

    public func stratumName(forKey key: String) -> String {
        if key == "__unstratified__" { return "Unstratified" }
        if let uuid = UUID(uuidString: key),
           let s = strata.first(where: { $0.id == uuid }) {
            return s.name
        }
        return key
    }

    // MARK: - Preview

    public static func preview() -> StandSummaryViewModel {
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
        return StandSummaryViewModel(
            project: project, design: design,
            plotRepo: StubStandPlotRepo(),
            treeRepo: StubStandTreeRepo(),
            speciesRepo: StubStandSpeciesRepo(),
            volRepo: StubStandVolRepo(),
            hdFitRepo: StubStandHDFitRepo(),
            stratumRepo: StubStandStratumRepo(),
            plannedRepo: StubStandPlannedRepo())
    }
}

// MARK: - Preview stubs

private final class StubStandPlotRepo: PlotRepository {
    func create(_ p: Plot) throws -> Plot { p }
    func read(id: UUID) throws -> Plot? { nil }
    func update(_ p: Plot) throws -> Plot { p }
    func delete(id: UUID) throws {}
    func listByProject(_ projectId: UUID) throws -> [Plot] { [] }
    func closed(projectId: UUID) throws -> [Plot] { [] }
    func byPlotNumber(projectId: UUID, plotNumber: Int) throws -> Plot? { nil }
}

private final class StubStandTreeRepo: TreeRepository {
    func create(_ t: Tree) throws -> Tree { t }
    func read(id: UUID, includeDeleted: Bool) throws -> Tree? { nil }
    func update(_ t: Tree) throws -> Tree { t }
    func delete(id: UUID, at date: Date) throws {}
    func hardDelete(id: UUID) throws {}
    func listByPlot(_ plotId: UUID, includeDeleted: Bool) throws -> [Tree] { [] }
    func bySpeciesInProject(_ projectId: UUID, speciesCode: String, includeDeleted: Bool) throws -> [Tree] { [] }
    func recentSpeciesCodes(projectId: UUID, limit: Int) throws -> [String] { [] }
}

private final class StubStandSpeciesRepo: SpeciesConfigRepository {
    func create(_ s: SpeciesConfig) throws -> SpeciesConfig { s }
    func read(code: String) throws -> SpeciesConfig? { nil }
    func update(_ s: SpeciesConfig) throws -> SpeciesConfig { s }
    func delete(code: String) throws {}
    func list() throws -> [SpeciesConfig] { [] }
}

private final class StubStandVolRepo: VolumeEquationRepository {
    func create(_ v: Models.VolumeEquation) throws -> Models.VolumeEquation { v }
    func read(id: String) throws -> Models.VolumeEquation? { nil }
    func update(_ v: Models.VolumeEquation) throws -> Models.VolumeEquation { v }
    func delete(id: String) throws {}
    func list() throws -> [Models.VolumeEquation] { [] }
}

private final class StubStandHDFitRepo: HeightDiameterFitRepository {
    func create(_ f: HeightDiameterFit) throws -> HeightDiameterFit { f }
    func read(id: UUID) throws -> HeightDiameterFit? { nil }
    func update(_ f: HeightDiameterFit) throws -> HeightDiameterFit { f }
    func delete(id: UUID) throws {}
    func forProjectAndSpecies(projectId: UUID, speciesCode: String) throws -> HeightDiameterFit? { nil }
    func listByProject(_ projectId: UUID) throws -> [HeightDiameterFit] { [] }
}

private final class StubStandStratumRepo: StratumRepository {
    func create(_ s: Stratum) throws -> Stratum { s }
    func read(id: UUID) throws -> Stratum? { nil }
    func update(_ s: Stratum) throws -> Stratum { s }
    func delete(id: UUID) throws {}
    func list() throws -> [Stratum] { [] }
    func listByProject(_ projectId: UUID) throws -> [Stratum] { [] }
}

private final class StubStandPlannedRepo: PlannedPlotRepository {
    func create(_ p: PlannedPlot) throws -> PlannedPlot { p }
    func read(id: UUID) throws -> PlannedPlot? { nil }
    func update(_ p: PlannedPlot) throws -> PlannedPlot { p }
    func delete(id: UUID) throws {}
    func listByProject(_ projectId: UUID) throws -> [PlannedPlot] { [] }
    func listUnvisited(projectId: UUID) throws -> [PlannedPlot] { [] }
}
