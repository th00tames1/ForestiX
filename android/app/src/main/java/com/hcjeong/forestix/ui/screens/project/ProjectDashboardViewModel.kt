// Port of iOS ViewModels/ProjectDashboardViewModel.swift.
// Spec §3.1 REQ-PRJ-002. Lists strata + planned plots and imports stratum
// boundaries from GeoJSON or KML files. Area for imported polygons comes
// from either the GeoJSON `areaAcres` property or a spherical-excess fallback.

package com.hcjeong.forestix.ui.screens.project

import com.hcjeong.forestix.AppEnvironment
import com.hcjeong.forestix.data.cruise.CruiseDesign
import com.hcjeong.forestix.data.cruise.CruiseDesignRepository
import com.hcjeong.forestix.data.cruise.PlannedPlot
import com.hcjeong.forestix.data.cruise.PlannedPlotRepository
import com.hcjeong.forestix.data.cruise.PlotRepository
import com.hcjeong.forestix.data.cruise.Project
import com.hcjeong.forestix.data.cruise.Stratum
import com.hcjeong.forestix.data.cruise.StratumRepository
import com.hcjeong.forestix.geo.GeoJSONImporter
import com.hcjeong.forestix.geo.ImportedPolygon
import com.hcjeong.forestix.geo.KMLImporter
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ProjectDashboardViewModel(val project: Project) {

    enum class ImportFormat { GEO_JSON, KML }

    private val _strata = MutableStateFlow<List<Stratum>>(emptyList())
    val strata: StateFlow<List<Stratum>> = _strata.asStateFlow()

    private val _plannedPlots = MutableStateFlow<List<PlannedPlot>>(emptyList())
    val plannedPlots: StateFlow<List<PlannedPlot>> = _plannedPlots.asStateFlow()

    private val _design = MutableStateFlow<CruiseDesign?>(null)
    val design: StateFlow<CruiseDesign?> = _design.asStateFlow()

    /// Count of plots that have been closed (have `closedAt != null`).
    /// Drives the step-3 "Measure in the field" progress check.
    private val _closedPlotCount = MutableStateFlow(0)
    val closedPlotCount: StateFlow<Int> = _closedPlotCount.asStateFlow()

    /// Count of any Plot rows (closed or not) that exist for this project.
    private val _totalPlotCount = MutableStateFlow(0)
    val totalPlotCount: StateFlow<Int> = _totalPlotCount.asStateFlow()

    val errorMessage = MutableStateFlow<String?>(null)
    val toastMessage = MutableStateFlow<String?>(null)

    private var stratumRepository: StratumRepository? = null
    private var plannedPlotRepository: PlannedPlotRepository? = null
    private var designRepository: CruiseDesignRepository? = null
    private var plotRepository: PlotRepository? = null

    fun configure(environment: AppEnvironment) {
        if (stratumRepository == null) stratumRepository = environment.stratumRepository
        if (plannedPlotRepository == null) plannedPlotRepository = environment.plannedPlotRepository
        if (designRepository == null) designRepository = environment.cruiseDesignRepository
        if (plotRepository == null) plotRepository = environment.plotRepository
    }

    suspend fun refresh() {
        refreshStrata()
        refreshPlannedPlots()
        refreshDesign()
        refreshPlotProgress()
    }

    /// Reloads the closed / total plot counts. Called on every dashboard
    /// appear so the step-3 progress check reflects work the cruiser did
    /// inside Go Cruise.
    suspend fun refreshPlotProgress() {
        val repo = plotRepository ?: return
        try {
            val plots = repo.listByProject(project.id)
            _totalPlotCount.value = plots.size
            _closedPlotCount.value = plots.count { it.closedAt != null }
        } catch (_: Exception) {
            // Non-fatal — dashboard progress is informational only.
        }
    }

    // MARK: - Cruise design

    suspend fun refreshDesign() {
        val repo = designRepository ?: return
        try {
            _design.value = repo.forProject(project.id).firstOrNull()
        } catch (e: Exception) {
            errorMessage.value = "Failed to load cruise design: $e"
        }
    }

    // MARK: - Strata

    suspend fun refreshStrata() {
        val repo = stratumRepository ?: return
        try {
            _strata.value = repo.listByProject(project.id).sortedBy { it.name }
        } catch (e: Exception) {
            errorMessage.value = "Failed to load strata: $e"
        }
    }

    /// Android analogue of `importStrata(fileURL:format:)` — the screen
    /// resolves the content Uri to bytes before calling in.
    suspend fun importStrata(data: ByteArray, format: ImportFormat) {
        val repo = stratumRepository ?: return
        try {
            val imported: List<ImportedPolygon> = when (format) {
                ImportFormat.GEO_JSON -> GeoJSONImporter.importStrata(data)
                ImportFormat.KML -> KMLImporter.importStrata(data)
            }
            val created = mutableListOf<Stratum>()
            for (poly in imported) {
                val stratum = Stratum(
                    id = UUID.randomUUID(),
                    projectId = project.id,
                    name = poly.name,
                    areaAcres = poly.areaAcres.toFloat(),
                    polygonGeoJSON = poly.geoJSONString,
                )
                created.add(repo.create(stratum))
            }
            toastMessage.value =
                "Imported ${created.size} stratum${if (created.size == 1) "" else "s"}."
            refreshStrata()
        } catch (e: Exception) {
            errorMessage.value = "Import failed: $e"
        }
    }

    suspend fun delete(stratumId: UUID) {
        val repo = stratumRepository ?: return
        try {
            repo.delete(stratumId)
            refreshStrata()
        } catch (e: Exception) {
            errorMessage.value = "Failed to delete stratum: $e"
        }
    }

    // MARK: - Planned plots

    suspend fun refreshPlannedPlots() {
        val repo = plannedPlotRepository ?: return
        try {
            _plannedPlots.value = repo.listByProject(project.id)
        } catch (e: Exception) {
            errorMessage.value = "Failed to load planned plots: $e"
        }
    }

    // MARK: - Totals

    val totalAcres: Double
        get() = _strata.value.sumOf { it.areaAcres.toDouble() }
}
