// Port of iOS ViewModels/PlotSummaryViewModel.swift.
// Phase 5 §5.4 PlotSummary view model. REQ-TAL-006, REQ-AGG-001, §7.4.
//
// Responsibilities:
//   • Run pure validators (PlotValidation.validatePlotForClose) and surface
//     errors / warnings blocking the close.
//   • Show the final PlotStats for the plot (TPA, BA/ac, QMD, V/ac).
//   • On confirm: set `plot.closedAt`, persist, then trigger a synchronous
//     H–D rolling update for every species with ≥ minN measured heights
//     across the entire project. REQ §7.4 ceiling is <500ms.

package com.hcjeong.forestix.ui.screens.plot

import com.hcjeong.forestix.data.cruise.CruiseDesign
import com.hcjeong.forestix.data.cruise.HeightDiameterFitRepository
import com.hcjeong.forestix.data.cruise.Plot
import com.hcjeong.forestix.data.cruise.PlotRepository
import com.hcjeong.forestix.data.cruise.Project
import com.hcjeong.forestix.data.cruise.SpeciesConfig
import com.hcjeong.forestix.data.cruise.SpeciesConfigRepository
import com.hcjeong.forestix.data.cruise.Tree
import com.hcjeong.forestix.data.cruise.TreeRepository
import com.hcjeong.forestix.data.cruise.VolumeEquationRepository
import com.hcjeong.forestix.data.cruise.recomputeForSpecies
import com.hcjeong.forestix.inventory.HDModel
import com.hcjeong.forestix.inventory.PlotStats
import com.hcjeong.forestix.inventory.PlotStatsCalculator
import com.hcjeong.forestix.inventory.PlotValidation
import com.hcjeong.forestix.inventory.ValidationResult
import com.hcjeong.forestix.inventory.VolumeEquation
import com.hcjeong.forestix.inventory.VolumeEquationFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

class PlotSummaryViewModel(
    // Input
    val project: Project,
    val design: CruiseDesign,
    plot: Plot,
    // Dependencies
    private val plotRepo: PlotRepository,
    private val treeRepo: TreeRepository,
    private val speciesRepo: SpeciesConfigRepository,
    private val volRepo: VolumeEquationRepository,
    private val hdFitRepo: HeightDiameterFitRepository,
) {

    var plot: Plot = plot
        private set

    // State
    private val _trees = MutableStateFlow<List<Tree>>(emptyList())
    val trees: StateFlow<List<Tree>> = _trees.asStateFlow()

    private val _speciesByCode = MutableStateFlow<Map<String, SpeciesConfig>>(emptyMap())
    val speciesByCode: StateFlow<Map<String, SpeciesConfig>> = _speciesByCode.asStateFlow()

    private val _validation = MutableStateFlow(ValidationResult.ok)
    val validation: StateFlow<ValidationResult> = _validation.asStateFlow()

    private val _stats = MutableStateFlow(PlotStats.empty)
    val stats: StateFlow<PlotStats> = _stats.asStateFlow()

    private val _hdFitsByProject = MutableStateFlow<Map<String, HDModel.Fit>>(emptyMap())
    val hdFitsByProject: StateFlow<Map<String, HDModel.Fit>> = _hdFitsByProject.asStateFlow()

    private val _hdFitDurationMs = MutableStateFlow(0.0)
    val hdFitDurationMs: StateFlow<Double> = _hdFitDurationMs.asStateFlow()

    private val _closedAt = MutableStateFlow(plot.closedAt)
    val closedAt: StateFlow<Long?> = _closedAt.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isClosing = MutableStateFlow(false)
    val isClosing: StateFlow<Boolean> = _isClosing.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // MARK: - Load & compute

    suspend fun refresh() {
        _isLoading.value = true
        try {
            _trees.value = treeRepo.listByPlot(plot.id, includeDeleted = false)
            _speciesByCode.value = speciesRepo.list().associateBy { it.code }
            _validation.value = PlotValidation.validatePlotForClose(
                plot = plot,
                trees = _trees.value,
                speciesByCode = _speciesByCode.value)
            recomputeStats()
            loadProjectHDFits()
            _errorMessage.value = null
        } catch (e: Exception) {
            // Cancellation is not an error — rethrow so a disposed screen
            // doesn't fabricate a "Load failed" message.
            if (e is CancellationException) throw e
            _errorMessage.value = "Load failed: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            _isLoading.value = false
        }
    }

    private suspend fun recomputeStats() {
        val volEquations = mutableMapOf<String, VolumeEquation>()
        try {
            val records = volRepo.list()
            val byId = mutableMapOf<String, VolumeEquation>()
            for (r in records) {
                VolumeEquationFactory.make(r)?.let { byId[r.id] = it }
            }
            for ((code, sp) in _speciesByCode.value) {
                byId[sp.volumeEquationId]?.let { volEquations[code] = it }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // Non-fatal: fall back to an empty map; live trees still aggregate
            // TPA/BA/QMD, just not volume.
        }
        _stats.value = PlotStatsCalculator.compute(
            plot = plot,
            cruiseDesign = design,
            trees = _trees.value,
            species = _speciesByCode.value,
            volumeEquations = volEquations,
            hdFits = _hdFitsByProject.value)
    }

    private suspend fun loadProjectHDFits() {
        try {
            val fits = mutableMapOf<String, HDModel.Fit>()
            for (fit in hdFitRepo.listByProject(project.id)) {
                HDModel.Fit.fromCoefficients(
                    fit.coefficients, nObs = fit.nObs, rmse = fit.rmse,
                )?.let { fits[fit.speciesCode] = it }
            }
            _hdFitsByProject.value = fits
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // Non-fatal for display.
        }
    }

    // MARK: - Close

    /// Close the plot: stamp closedAt/closedBy, persist, then run the H–D
    /// rolling update across all project species with ≥ 8 measured heights.
    /// REQ §7.4 budget <500ms.
    ///
    /// Integrity note: the plot-update and the H–D fit writes are two
    /// separate transactions. If the H–D rollup throws *after* the plot row
    /// has already been stamped closedAt, we roll back the closedAt on the
    /// plot so the flow isn't stranded in a half-closed state — the cruiser
    /// sees the error, hits retry, and the close works atomically next time.
    suspend fun close(closedBy: String = "field") {
        if (!_validation.value.canClose) return
        if (_isClosing.value) return
        _isClosing.value = true
        try {
            // iOS close() is synchronous on the MainActor and cannot be
            // interrupted between stamping closedAt and running the H–D
            // rollup (or its rollback). Compose launches this from a
            // disposable rememberCoroutineScope, so shield the persistence
            // section from cancellation — a back-press mid-close must not
            // strand a half-closed plot with stale H–D fits.
            withContext(NonCancellable) {
                val startWall = System.currentTimeMillis()
                val originalPlot = plot.copy()
                val now = System.currentTimeMillis()
                val p = plot.copy(closedAt = now, closedBy = closedBy)

                try {
                    plot = plotRepo.update(p)
                    _closedAt.value = plot.closedAt
                    try {
                        updateProjectHDFits(now = now)
                    } catch (e: Exception) {
                        // Rollback the closedAt stamp — the HD rollup is a
                        // required side-effect of closing, so a failure leaves
                        // the plot logically open.
                        plot = try {
                            plotRepo.update(originalPlot)
                        } catch (_: Exception) {
                            originalPlot
                        }
                        _closedAt.value = plot.closedAt
                        throw e
                    }
                    _hdFitDurationMs.value = (System.currentTimeMillis() - startWall).toDouble()
                    _errorMessage.value = null
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    _errorMessage.value = "Close failed: ${e.message ?: e.javaClass.simpleName}. " +
                        "Your trees are saved; try again when you have signal."
                }
            }
        } finally {
            _isClosing.value = false
        }
    }

    /// External reset for the error alert.
    fun clearError() {
        _errorMessage.value = null
    }

    /// Loop over every species in the project, gather all measured heights
    /// from every closed plot + the just-closed plot, and run the rolling fit.
    /// Silently skips species with < minN observations.
    private suspend fun updateProjectHDFits(now: Long) {
        val minN = 8
        val allPlots = plotRepo.listByProject(project.id)
        val plotIds = allPlots.map { it.id }
        val byCode = mutableMapOf<String, MutableList<HDModel.Observation>>()
        for (pid in plotIds) {
            val plotTrees = treeRepo.listByPlot(pid, includeDeleted = false)
            for (t in plotTrees) {
                if (t.heightSource != "measured") continue
                val h = t.heightM
                if (h != null && h > 1.3f && t.dbhCm > 0f) {
                    byCode.getOrPut(t.speciesCode) { mutableListOf() }
                        .add(HDModel.Observation(dbhCm = t.dbhCm, heightM = h))
                }
            }
        }
        for ((code, obs) in byCode) {
            if (obs.size < minN) continue
            hdFitRepo.recomputeForSpecies(
                projectId = project.id,
                speciesCode = code,
                observations = obs,
                minN = minN,
                now = now)
        }
        loadProjectHDFits()
    }
}
