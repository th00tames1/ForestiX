// Port of iOS ViewModels/PlotTallyViewModel.swift.
// Phase 5 §5.1 PlotTallyScreen view model. REQ-TAL-005/006.
//
// Owns the in-memory tree list for an open plot + live statistics.
// Re-reads trees from the repo on demand (after add/soft-delete),
// recomputes PlotStats via the pure engine call. Preloads species,
// volume equations, and H–D fits so `addTreeCompleted()` doesn't
// re-touch the DB for the live-stats refresh.

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
import com.hcjeong.forestix.inventory.HDModel
import com.hcjeong.forestix.inventory.PlotStats
import com.hcjeong.forestix.inventory.PlotStatsCalculator
import com.hcjeong.forestix.inventory.VolumeEquation
import com.hcjeong.forestix.inventory.VolumeEquationFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

class PlotTallyViewModel(
    // Inputs
    val project: Project,
    val design: CruiseDesign,
    val plot: Plot,
    // Repositories
    private val plotRepo: PlotRepository,
    private val treeRepo: TreeRepository,
    private val speciesRepo: SpeciesConfigRepository,
    private val volRepo: VolumeEquationRepository,
    private val hdFitRepo: HeightDiameterFitRepository,
) {

    // Live state
    private val _trees = MutableStateFlow<List<Tree>>(emptyList())
    val trees: StateFlow<List<Tree>> = _trees.asStateFlow()

    private val _stats = MutableStateFlow(PlotStats.empty)
    val stats: StateFlow<PlotStats> = _stats.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // Cached lookup tables
    var speciesByCode: Map<String, SpeciesConfig> = emptyMap()
        private set
    var volumeEquations: MutableMap<String, VolumeEquation> = mutableMapOf()
        private set
    var hdFits: MutableMap<String, HDModel.Fit> = mutableMapOf()
        private set

    suspend fun refresh() {
        _isLoading.value = true
        try {
            // Include soft-deleted for UI "undelete" affordance.
            _trees.value = treeRepo.listByPlot(plot.id, includeDeleted = true)
            if (speciesByCode.isEmpty()) {
                speciesByCode = speciesRepo.list().associateBy { it.code }
            }
            if (volumeEquations.isEmpty()) {
                val records = volRepo.list()
                for (r in records) {
                    VolumeEquationFactory.make(r)?.let { volumeEquations[r.id] = it }
                }
            }
            // Rebuild H–D fits each refresh (cheap — #species × few bytes).
            hdFits.clear()
            for (fit in hdFitRepo.listByProject(project.id)) {
                HDModel.Fit.fromCoefficients(
                    fit.coefficients, nObs = fit.nObs, rmse = fit.rmse,
                )?.let { hdFits[fit.speciesCode] = it }
            }
            recomputeStats()
            _errorMessage.value = null
        } catch (e: Exception) {
            // Cancellation is not an error — rethrow so a disposed screen
            // doesn't fabricate a "Could not load" message.
            if (e is CancellationException) throw e
            _errorMessage.value =
                "Could not load plot data: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            _isLoading.value = false
        }
    }

    /// REQ-TAL-005: recompute live stats from the current `trees` list +
    /// cached lookup tables. Pure — no I/O.
    fun recomputeStats() {
        val volByTree = volumeEquationsForTrees()
        _stats.value = PlotStatsCalculator.compute(
            plot = plot,
            cruiseDesign = design,
            trees = _trees.value,
            species = speciesByCode,
            volumeEquations = volByTree,
            hdFits = hdFits)
    }

    /// Maps speciesCode → VolumeEquation instance via each species' record id.
    private fun volumeEquationsForTrees(): Map<String, VolumeEquation> {
        val out = mutableMapOf<String, VolumeEquation>()
        for ((code, sp) in speciesByCode) {
            volumeEquations[sp.volumeEquationId]?.let { out[code] = it }
        }
        return out
    }

    // MARK: - Tree CRUD

    suspend fun softDelete(treeId: UUID) {
        try {
            treeRepo.delete(treeId)
            refresh()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            _errorMessage.value = "Delete failed: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    suspend fun undelete(treeId: UUID) {
        try {
            val t = treeRepo.read(treeId, includeDeleted = true)
            if (t != null) {
                t.deletedAt = null
                t.updatedAt = System.currentTimeMillis()
                treeRepo.update(t)
                refresh()
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            _errorMessage.value = "Undelete failed: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /// Call this after a successful AddTreeFlow save — refreshes list +
    /// stats in a single pass so the header strip updates within the
    /// REQ-TAL-005 300 ms budget.
    suspend fun addTreeCompleted() {
        refresh()
    }

    /// External reset for the error alert — lets the view clear state on
    /// dismissal so repeat errors show up again.
    fun clearError() {
        _errorMessage.value = null
    }

    /// Next tree number (live count + 1). Used by AddTreeFlow to pre-fill.
    val nextTreeNumber: Int
        get() = (liveTrees.maxOfOrNull { it.treeNumber } ?: 0) + 1

    val liveTrees: List<Tree>
        get() = _trees.value.filter { it.deletedAt == null }

    val softDeletedTrees: List<Tree>
        get() = _trees.value.filter { it.deletedAt != null }
}
