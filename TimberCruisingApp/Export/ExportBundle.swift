// Phase 6 shared aggregator.
//
// Every Phase 6 exporter (CSV, GeoJSON, Shapefile, PDF) wants the same
// denormalized read-through of the project: strata, planned plots,
// measured plots, trees per plot, species catalogue, volume equations,
// H-D fits, per-plot PlotStats, and the three stand-level StandStats.
//
// Building those pieces is non-trivial (see StandSummaryViewModel) so
// this file hoists the repo-traversal into a single value type the
// view-model can compute once per export session and hand to each
// exporter. Keeping the plumbing in Export/ also means
// PersistenceIntegrationTests can exercise the bundle end-to-end without
// routing through a UI view model.

import Foundation
import Models
import InventoryEngine

public struct ExportBundle {
    public let project: Project
    public let design: CruiseDesign
    public let strata: [Stratum]
    public let plannedPlots: [PlannedPlot]
    public let plots: [Plot]
    public let trees: [Tree]                   // all trees, including soft-deleted
    public let species: [SpeciesConfig]
    public let volumeEquationRecords: [Models.VolumeEquation]
    public let hdFits: [HeightDiameterFit]
    public let plotStatsByPlot: [UUID: PlotStats]
    public let tpaStand: StandStat
    public let baStand: StandStat
    public let volStand: StandStat
    /// Stratum name lookup keyed by `Stratum.id.uuidString`. Includes a
    /// sentinel `"__unstratified__"` → "Unstratified" entry when at least
    /// one plot falls outside a defined stratum.
    public let stratumNamesByKey: [String: String]
    public let generatedAt: Date
}

/// Pluggable lookup surface for ExportBundle.build — allows tests and
/// integration suites to feed in-memory arrays directly rather than
/// routing through repositories.
public protocol ExportDataSource {
    func project() throws -> Project
    func cruiseDesign(forProjectId: UUID) throws -> CruiseDesign
    func strata(forProjectId: UUID) throws -> [Stratum]
    func plannedPlots(forProjectId: UUID) throws -> [PlannedPlot]
    func plots(forProjectId: UUID) throws -> [Plot]
    func trees(forPlotId: UUID) throws -> [Tree]
    func species() throws -> [SpeciesConfig]
    func volumeEquations() throws -> [Models.VolumeEquation]
    func hdFits(forProjectId: UUID) throws -> [HeightDiameterFit]
}

public enum ExportBundleError: Error {
    case designNotFound
}

public enum ExportBundleBuilder {

    public static func build(using ds: ExportDataSource,
                             at now: Date = Date()) throws -> ExportBundle {
        let project = try ds.project()
        let design = try ds.cruiseDesign(forProjectId: project.id)
        let strata = try ds.strata(forProjectId: project.id)
        let planned = try ds.plannedPlots(forProjectId: project.id)
        let plots = try ds.plots(forProjectId: project.id)
        let species = try ds.species()
        let volRecords = try ds.volumeEquations()
        let hdFits = try ds.hdFits(forProjectId: project.id)

        // Index helpers.
        let speciesByCode = Dictionary(uniqueKeysWithValues:
            species.map { ($0.code, $0) })
        var volById: [String: any InventoryEngine.VolumeEquation] = [:]
        for r in volRecords {
            if let eq = VolumeEquationFactory.make(from: r) {
                volById[r.id] = eq
            }
        }
        var volByCode: [String: any InventoryEngine.VolumeEquation] = [:]
        for (code, sp) in speciesByCode {
            if let eq = volById[sp.volumeEquationId] { volByCode[code] = eq }
        }
        var hdFitsByCode: [String: HDModel.Fit] = [:]
        for fit in hdFits {
            if let f = HDModel.Fit.fromCoefficients(
                fit.coefficients, nObs: fit.nObs, rmse: fit.rmse) {
                hdFitsByCode[fit.speciesCode] = f
            }
        }
        let plannedById = Dictionary(uniqueKeysWithValues: planned.map { ($0.id, $0) })

        // Per-plot aggregation.
        var allTrees: [Tree] = []
        var statsByPlot: [UUID: PlotStats] = [:]
        var tpaRows: [(String, Double)] = []
        var baRows: [(String, Double)] = []
        var volRows: [(String, Double)] = []

        for plot in plots {
            let plotTrees = try ds.trees(forPlotId: plot.id)
            allTrees.append(contentsOf: plotTrees)

            // Only closed plots contribute to stand-level stats.
            guard plot.closedAt != nil else { continue }

            let stats = PlotStatsCalculator.compute(
                plot: plot,
                cruiseDesign: design,
                trees: plotTrees,
                species: speciesByCode,
                volumeEquations: volByCode,
                hdFits: hdFitsByCode)
            statsByPlot[plot.id] = stats

            let key = stratumKey(for: plot, plannedById: plannedById)
            tpaRows.append((key, Double(stats.tpa)))
            baRows.append((key, Double(stats.baPerAcreM2)))
            volRows.append((key, Double(stats.grossVolumePerAcreM3)))
        }

        let stratumAreas: [String: Double] = Dictionary(
            uniqueKeysWithValues: strata.map {
                ($0.id.uuidString, Double($0.areaAcres))
            })
        let tpaStat = StandStatsCalculator.compute(
            plotValues: tpaRows, stratumAreasAcres: stratumAreas)
        let baStat = StandStatsCalculator.compute(
            plotValues: baRows, stratumAreasAcres: stratumAreas)
        let volStat = StandStatsCalculator.compute(
            plotValues: volRows, stratumAreasAcres: stratumAreas)

        var namesByKey = Dictionary(uniqueKeysWithValues:
            strata.map { ($0.id.uuidString, $0.name) })
        if tpaRows.contains(where: { $0.0 == "__unstratified__" }) {
            namesByKey["__unstratified__"] = "Unstratified"
        }

        return ExportBundle(
            project: project, design: design,
            strata: strata, plannedPlots: planned,
            plots: plots, trees: allTrees,
            species: species, volumeEquationRecords: volRecords,
            hdFits: hdFits,
            plotStatsByPlot: statsByPlot,
            tpaStand: tpaStat, baStand: baStat, volStand: volStat,
            stratumNamesByKey: namesByKey,
            generatedAt: now)
    }

    private static func stratumKey(for plot: Plot,
                                   plannedById: [UUID: PlannedPlot]) -> String {
        if let ppid = plot.plannedPlotId,
           let pp = plannedById[ppid],
           let sid = pp.stratumId {
            return sid.uuidString
        }
        return "__unstratified__"
    }
}
