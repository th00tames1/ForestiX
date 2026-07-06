// Port of iOS ViewModels/StandSummaryViewModel.swift.
// Phase 5 §5.5 StandSummary view model. REQ-AGG-003, §7.5.
//
// Aggregates every closed plot in the project into stratified stand-level
// stats (mean ± SE, 95% CI) for TPA, BA/ac, and gross V/ac. When no strata
// exist, every plot falls into a single "__unstratified__" bucket so the
// screen still renders without a stratum map.

package com.hcjeong.forestix.ui.screens.stand

import com.hcjeong.forestix.data.cruise.CruiseDesign
import com.hcjeong.forestix.data.cruise.HeightDiameterFitRepository
import com.hcjeong.forestix.data.cruise.PlannedPlot
import com.hcjeong.forestix.data.cruise.PlannedPlotRepository
import com.hcjeong.forestix.data.cruise.Plot
import com.hcjeong.forestix.data.cruise.PlotRepository
import com.hcjeong.forestix.data.cruise.Project
import com.hcjeong.forestix.data.cruise.SpeciesConfigRepository
import com.hcjeong.forestix.data.cruise.StratumRepository
import com.hcjeong.forestix.data.cruise.Stratum
import com.hcjeong.forestix.data.cruise.TreeRepository
import com.hcjeong.forestix.data.cruise.VolumeEquationRepository
import com.hcjeong.forestix.inventory.HDModel
import com.hcjeong.forestix.inventory.PlotStats
import com.hcjeong.forestix.inventory.PlotStatsCalculator
import com.hcjeong.forestix.inventory.StandStat
import com.hcjeong.forestix.inventory.StandStatsCalculator
import com.hcjeong.forestix.inventory.VolumeEquation
import com.hcjeong.forestix.inventory.VolumeEquationFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

class StandSummaryViewModel(
    val project: Project,
    val design: CruiseDesign,
    private val plotRepo: PlotRepository,
    private val treeRepo: TreeRepository,
    private val speciesRepo: SpeciesConfigRepository,
    private val volRepo: VolumeEquationRepository,
    private val hdFitRepo: HeightDiameterFitRepository,
    private val stratumRepo: StratumRepository,
    private val plannedRepo: PlannedPlotRepository,
) {

    /// Swift models this as the tuple `(plot: Plot, stats: PlotStats)`.
    data class PerPlotStat(val plot: Plot, val stats: PlotStats)

    private val _closedPlots = MutableStateFlow<List<Plot>>(emptyList())
    val closedPlots: StateFlow<List<Plot>> = _closedPlots.asStateFlow()

    private val _strata = MutableStateFlow<List<Stratum>>(emptyList())
    val strata: StateFlow<List<Stratum>> = _strata.asStateFlow()

    private val _tpaStat = MutableStateFlow(StandStat.empty)
    val tpaStat: StateFlow<StandStat> = _tpaStat.asStateFlow()

    private val _baStat = MutableStateFlow(StandStat.empty)
    val baStat: StateFlow<StandStat> = _baStat.asStateFlow()

    private val _volStat = MutableStateFlow(StandStat.empty)
    val volStat: StateFlow<StandStat> = _volStat.asStateFlow()

    private val _totalLiveTreeCount = MutableStateFlow(0)
    val totalLiveTreeCount: StateFlow<Int> = _totalLiveTreeCount.asStateFlow()

    private val _perPlotStats = MutableStateFlow<List<PerPlotStat>>(emptyList())
    val perPlotStats: StateFlow<List<PerPlotStat>> = _perPlotStats.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    suspend fun refresh() {
        _isLoading.value = true
        try {
            val closed = plotRepo.closed(project.id)
            _closedPlots.value = closed
            val strataList = stratumRepo.listByProject(project.id)
            _strata.value = strataList

            val planned = plannedRepo.listByProject(project.id)
            val plannedById = planned.associateBy { it.id }

            val species = speciesRepo.list()
            val speciesByCode = species.associateBy { it.code }

            val volById = mutableMapOf<String, VolumeEquation>()
            for (r in volRepo.list()) {
                VolumeEquationFactory.make(r)?.let { volById[r.id] = it }
            }
            val volByCode = mutableMapOf<String, VolumeEquation>()
            for ((code, sp) in speciesByCode) {
                volById[sp.volumeEquationId]?.let { volByCode[code] = it }
            }

            val hdFits = mutableMapOf<String, HDModel.Fit>()
            for (fit in hdFitRepo.listByProject(project.id)) {
                HDModel.Fit.fromCoefficients(
                    fit.coefficients, nObs = fit.nObs, rmse = fit.rmse,
                )?.let { hdFits[fit.speciesCode] = it }
            }

            val perPlot = mutableListOf<PerPlotStat>()
            val tpaRows = mutableListOf<Pair<String, Double>>()
            val baRows = mutableListOf<Pair<String, Double>>()
            val volRows = mutableListOf<Pair<String, Double>>()
            var liveTotal = 0

            for (plot in closed) {
                val trees = treeRepo.listByPlot(plot.id, includeDeleted = false)
                val stats = PlotStatsCalculator.compute(
                    plot = plot,
                    cruiseDesign = design,
                    trees = trees,
                    species = speciesByCode,
                    volumeEquations = volByCode,
                    hdFits = hdFits)
                perPlot.add(PerPlotStat(plot, stats))
                liveTotal += stats.liveTreeCount

                val key = stratumKey(plot, plannedById)
                tpaRows.add(Pair(key, stats.tpa.toDouble()))
                baRows.add(Pair(key, stats.baPerAcreM2.toDouble()))
                volRows.add(Pair(key, stats.grossVolumePerAcreM3.toDouble()))
            }

            val stratumAreas: Map<String, Double> =
                strataList.associate { it.id.toString() to it.areaAcres.toDouble() }

            _tpaStat.value = StandStatsCalculator.compute(
                plotValues = tpaRows, stratumAreasAcres = stratumAreas)
            _baStat.value = StandStatsCalculator.compute(
                plotValues = baRows, stratumAreasAcres = stratumAreas)
            _volStat.value = StandStatsCalculator.compute(
                plotValues = volRows, stratumAreasAcres = stratumAreas)

            _totalLiveTreeCount.value = liveTotal
            _perPlotStats.value = perPlot
            _errorMessage.value = null
        } catch (e: Exception) {
            _errorMessage.value = "Load failed: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            _isLoading.value = false
        }
    }

    private fun stratumKey(plot: Plot, plannedById: Map<UUID, PlannedPlot>): String {
        val ppid = plot.plannedPlotId
        if (ppid != null) {
            val sid = plannedById[ppid]?.stratumId
            if (sid != null) return sid.toString()
        }
        return "__unstratified__"
    }

    fun stratumName(forKey: String): String {
        if (forKey == "__unstratified__") return "Unstratified"
        return try {
            val uuid = UUID.fromString(forKey)
            _strata.value.firstOrNull { it.id == uuid }?.name ?: forKey
        } catch (_: IllegalArgumentException) {
            forKey
        }
    }
}
