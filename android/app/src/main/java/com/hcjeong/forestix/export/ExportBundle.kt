// Port of iOS Export/ExportBundle.swift — Phase 6 shared aggregator.
//
// Every Phase 6 exporter (CSV, GeoJSON, Shapefile, PDF) wants the same
// denormalized read-through of the project: strata, planned plots,
// measured plots, trees per plot, species catalogue, volume equations,
// H-D fits, per-plot PlotStats, and the three stand-level StandStats.
//
// Building those pieces is non-trivial so this file hoists the
// repo-traversal into a single value type the view-model can compute once
// per export session and hand to each exporter. Keeping the plumbing in
// export/ also means integration tests can exercise the bundle end-to-end
// without routing through a UI view model.

package com.hcjeong.forestix.export

import com.hcjeong.forestix.data.cruise.CruiseDesign
import com.hcjeong.forestix.data.cruise.HeightDiameterFit
import com.hcjeong.forestix.data.cruise.PlannedPlot
import com.hcjeong.forestix.data.cruise.Plot
import com.hcjeong.forestix.data.cruise.Project
import com.hcjeong.forestix.data.cruise.SpeciesConfig
import com.hcjeong.forestix.data.cruise.Stratum
import com.hcjeong.forestix.data.cruise.Tree
import com.hcjeong.forestix.inventory.HDModel
import com.hcjeong.forestix.inventory.PlotStats
import com.hcjeong.forestix.inventory.PlotStatsCalculator
import com.hcjeong.forestix.inventory.StandStat
import com.hcjeong.forestix.inventory.StandStatsCalculator
import com.hcjeong.forestix.inventory.VolumeEquationFactory
import java.util.UUID

data class ExportBundle(
    val project: Project,
    val design: CruiseDesign,
    val strata: List<Stratum>,
    val plannedPlots: List<PlannedPlot>,
    val plots: List<Plot>,
    val trees: List<Tree>,                  // all trees, including soft-deleted
    val species: List<SpeciesConfig>,
    val volumeEquationRecords: List<com.hcjeong.forestix.data.cruise.VolumeEquation>,
    val hdFits: List<HeightDiameterFit>,
    val plotStatsByPlot: Map<UUID, PlotStats>,
    val tpaStand: StandStat,
    val baStand: StandStat,
    val volStand: StandStat,
    /// Stratum name lookup keyed by `Stratum.id.uuidString`. Includes a
    /// sentinel `"__unstratified__"` → "Unstratified" entry when at least
    /// one plot falls outside a defined stratum.
    val stratumNamesByKey: Map<String, String>,
    val generatedAt: Long,
)

/// Pluggable lookup surface for ExportBundleBuilder.build — allows tests and
/// integration suites to feed in-memory arrays directly rather than routing
/// through repositories.
interface ExportDataSource {
    fun project(): Project
    fun cruiseDesign(forProjectId: UUID): CruiseDesign
    fun strata(forProjectId: UUID): List<Stratum>
    fun plannedPlots(forProjectId: UUID): List<PlannedPlot>
    fun plots(forProjectId: UUID): List<Plot>
    fun trees(forPlotId: UUID): List<Tree>
    fun species(): List<SpeciesConfig>
    fun volumeEquations(): List<com.hcjeong.forestix.data.cruise.VolumeEquation>
    fun hdFits(forProjectId: UUID): List<HeightDiameterFit>
}

sealed class ExportBundleError(message: String) : Exception(message) {
    class DesignNotFound : ExportBundleError("designNotFound")
}

object ExportBundleBuilder {

    fun build(ds: ExportDataSource, now: Long = System.currentTimeMillis()): ExportBundle {
        val project = ds.project()
        val design = ds.cruiseDesign(forProjectId = project.id)
        val strata = ds.strata(forProjectId = project.id)
        val planned = ds.plannedPlots(forProjectId = project.id)
        val plots = ds.plots(forProjectId = project.id)
        val species = ds.species()
        val volRecords = ds.volumeEquations()
        val hdFits = ds.hdFits(forProjectId = project.id)

        // Index helpers.
        val speciesByCode = species.associateBy { it.code }
        val volById = mutableMapOf<String, com.hcjeong.forestix.inventory.VolumeEquation>()
        for (r in volRecords) {
            VolumeEquationFactory.make(r)?.let { volById[r.id] = it }
        }
        val volByCode = mutableMapOf<String, com.hcjeong.forestix.inventory.VolumeEquation>()
        for ((code, sp) in speciesByCode) {
            volById[sp.volumeEquationId]?.let { volByCode[code] = it }
        }
        val hdFitsByCode = mutableMapOf<String, HDModel.Fit>()
        for (fit in hdFits) {
            HDModel.Fit.fromCoefficients(fit.coefficients, nObs = fit.nObs, rmse = fit.rmse)
                ?.let { hdFitsByCode[fit.speciesCode] = it }
        }
        val plannedById = planned.associateBy { it.id }

        // Per-plot aggregation.
        val allTrees = mutableListOf<Tree>()
        val statsByPlot = mutableMapOf<UUID, PlotStats>()
        val tpaRows = mutableListOf<Pair<String, Double>>()
        val baRows = mutableListOf<Pair<String, Double>>()
        val volRows = mutableListOf<Pair<String, Double>>()

        for (plot in plots) {
            val plotTrees = ds.trees(forPlotId = plot.id)
            allTrees.addAll(plotTrees)

            // Only closed plots contribute to stand-level stats.
            if (plot.closedAt == null) continue

            val stats = PlotStatsCalculator.compute(
                plot = plot,
                cruiseDesign = design,
                trees = plotTrees,
                species = speciesByCode,
                volumeEquations = volByCode,
                hdFits = hdFitsByCode)
            statsByPlot[plot.id] = stats

            val key = stratumKey(plot, plannedById)
            tpaRows.add(key to stats.tpa.toDouble())
            baRows.add(key to stats.baPerAcreM2.toDouble())
            volRows.add(key to stats.grossVolumePerAcreM3.toDouble())
        }

        val stratumAreas: Map<String, Double> =
            strata.associate { it.id.uuidString to it.areaAcres.toDouble() }
        val tpaStat = StandStatsCalculator.compute(
            plotValues = tpaRows, stratumAreasAcres = stratumAreas)
        val baStat = StandStatsCalculator.compute(
            plotValues = baRows, stratumAreasAcres = stratumAreas)
        val volStat = StandStatsCalculator.compute(
            plotValues = volRows, stratumAreasAcres = stratumAreas)

        val namesByKey = strata.associate { it.id.uuidString to it.name }.toMutableMap()
        if (tpaRows.any { it.first == "__unstratified__" }) {
            namesByKey["__unstratified__"] = "Unstratified"
        }

        return ExportBundle(
            project = project, design = design,
            strata = strata, plannedPlots = planned,
            plots = plots, trees = allTrees,
            species = species, volumeEquationRecords = volRecords,
            hdFits = hdFits,
            plotStatsByPlot = statsByPlot,
            tpaStand = tpaStat, baStand = baStat, volStand = volStat,
            stratumNamesByKey = namesByKey,
            generatedAt = now)
    }

    private fun stratumKey(plot: Plot, plannedById: Map<UUID, PlannedPlot>): String {
        val ppid = plot.plannedPlotId
        if (ppid != null) {
            val sid = plannedById[ppid]?.stratumId
            if (sid != null) return sid.uuidString
        }
        return "__unstratified__"
    }
}
