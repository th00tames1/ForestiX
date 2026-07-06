// Port of iOS ViewModels/CruiseDesignViewModel.swift.
// Spec §3.1 REQ-PRJ-003/004. Configure cruise design (plot type, plot size
// or BAF, sampling scheme, grid spacing) and generate PlannedPlots via the
// Geo SamplingGenerator. Persists the design record and replaces all
// previously-generated PlannedPlots for this project.

package com.hcjeong.forestix.ui.screens.project

import com.hcjeong.forestix.AppEnvironment
import com.hcjeong.forestix.data.cruise.CruiseDesign
import com.hcjeong.forestix.data.cruise.CruiseDesignRepository
import com.hcjeong.forestix.data.cruise.PlannedPlotRepository
import com.hcjeong.forestix.data.cruise.PlotType
import com.hcjeong.forestix.data.cruise.Project
import com.hcjeong.forestix.data.cruise.SamplingScheme
import com.hcjeong.forestix.data.cruise.SpeciesConfig
import com.hcjeong.forestix.data.cruise.SpeciesConfigRepository
import com.hcjeong.forestix.data.cruise.StratumRepository
import com.hcjeong.forestix.data.cruise.VolumeEquation
import com.hcjeong.forestix.data.cruise.VolumeEquationRepository
import com.hcjeong.forestix.geo.CoordinateConversions
import com.hcjeong.forestix.geo.SamplingGenerator
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

class CruiseDesignViewModel(val project: Project) {

    // MARK: - Editable form state

    val plotType = MutableStateFlow(PlotType.FIXED_AREA)
    val plotAreaAcresString = MutableStateFlow("0.1")
    val bafString = MutableStateFlow("20")
    val samplingScheme = MutableStateFlow(SamplingScheme.SYSTEMATIC_GRID)
    val gridSpacingMetersString = MutableStateFlow("150")
    val nPerStratumString = MutableStateFlow("10")
    val seedString = MutableStateFlow("1")

    // MARK: - Feedback

    private val _plannedCount = MutableStateFlow(0)
    val plannedCount: StateFlow<Int> = _plannedCount.asStateFlow()

    private val _availableSpecies = MutableStateFlow<List<SpeciesConfig>>(emptyList())
    val availableSpecies: StateFlow<List<SpeciesConfig>> = _availableSpecies.asStateFlow()

    private val _volumeEquationsById = MutableStateFlow<Map<String, VolumeEquation>>(emptyMap())
    val volumeEquationsById: StateFlow<Map<String, VolumeEquation>> =
        _volumeEquationsById.asStateFlow()

    val errorMessage = MutableStateFlow<String?>(null)
    val toastMessage = MutableStateFlow<String?>(null)

    private var stratumRepository: StratumRepository? = null
    private var plannedPlotRepository: PlannedPlotRepository? = null
    private var designRepository: CruiseDesignRepository? = null
    private var speciesRepository: SpeciesConfigRepository? = null
    private var volumeEquationRepository: VolumeEquationRepository? = null

    fun configure(environment: AppEnvironment) {
        if (stratumRepository == null) stratumRepository = environment.stratumRepository
        if (plannedPlotRepository == null) plannedPlotRepository = environment.plannedPlotRepository
        if (designRepository == null) designRepository = environment.cruiseDesignRepository
        if (speciesRepository == null) speciesRepository = environment.speciesConfigRepository
        if (volumeEquationRepository == null) {
            volumeEquationRepository = environment.volumeEquationRepository
        }
    }

    // MARK: - Validation

    val isValid: Boolean get() = validationMessage == null

    /// Recomputed from the current field values on every read; the screen
    /// re-reads it during recomposition so it tracks keystrokes like the
    /// iOS computed property.
    val validationMessage: String?
        get() {
            if (plotType.value == PlotType.FIXED_AREA) {
                val area = plotAreaAcresString.value.toDoubleOrNull()
                if (area == null || area <= 0) {
                    return "Plot area must be a positive number of acres."
                }
            } else {
                val baf = bafString.value.toDoubleOrNull()
                if (baf == null || baf <= 0) {
                    return "Basal area factor must be a positive number."
                }
            }
            when (samplingScheme.value) {
                SamplingScheme.SYSTEMATIC_GRID -> {
                    val spacing = gridSpacingMetersString.value.toDoubleOrNull()
                    if (spacing == null || spacing <= 0) {
                        return "Grid spacing must be a positive number of metres."
                    }
                }
                SamplingScheme.STRATIFIED_RANDOM -> {
                    val n = nPerStratumString.value.toIntOrNull()
                    if (n == null || n <= 0) {
                        return "Plots per stratum must be a positive integer."
                    }
                }
                SamplingScheme.MANUAL -> return null
            }
            return null
        }

    // MARK: - Actions

    suspend fun generatePlannedPlots() {
        if (!isValid) {
            errorMessage.value = validationMessage
            return
        }
        val stratumRepo = stratumRepository ?: return
        val plotRepo = plannedPlotRepository ?: return
        val designRepo = designRepository ?: return

        try {
            val strata = stratumRepo.listByProject(project.id)
            if (strata.isEmpty()) {
                errorMessage.value = "Add at least one stratum before generating plots."
                return
            }

            val inputs = strata.map { s ->
                SamplingGenerator.StratumInput(
                    stratumId = s.id,
                    rings = parseRings(s.polygonGeoJSON),
                )
            }

            val seed = seedString.value.toULongOrNull() ?: 1uL
            val options = SamplingGenerator.GenerationOptions(
                projectId = project.id,
                scheme = samplingScheme.value,
                gridSpacingMeters = if (samplingScheme.value == SamplingScheme.SYSTEMATIC_GRID) {
                    gridSpacingMetersString.value.toDoubleOrNull()
                } else null,
                nPerStratum = if (samplingScheme.value == SamplingScheme.STRATIFIED_RANDOM) {
                    nPerStratumString.value.toIntOrNull()
                } else null,
                seed = seed,
            )
            val plots = SamplingGenerator.generate(strata = inputs, options = options)

            // Replace existing planned plots for this project.
            val existing = plotRepo.listByProject(project.id)
            for (p in existing) plotRepo.delete(p.id)
            for (plot in plots) plotRepo.create(plot)

            // Persist the design record (one per project).
            val existingDesigns = designRepo.forProject(project.id)
            val design = CruiseDesign(
                id = existingDesigns.firstOrNull()?.id ?: UUID.randomUUID(),
                projectId = project.id,
                plotType = plotType.value,
                plotAreaAcres = if (plotType.value == PlotType.FIXED_AREA) {
                    plotAreaAcresString.value.toFloatOrNull()
                } else null,
                baf = if (plotType.value == PlotType.VARIABLE_RADIUS) {
                    bafString.value.toFloatOrNull()
                } else null,
                samplingScheme = samplingScheme.value,
                gridSpacingMeters = if (samplingScheme.value == SamplingScheme.SYSTEMATIC_GRID) {
                    gridSpacingMetersString.value.toFloatOrNull()
                } else null,
            )
            if (existingDesigns.firstOrNull() != null) {
                designRepo.update(design)
            } else {
                designRepo.create(design)
            }

            _plannedCount.value = plots.size
            toastMessage.value =
                "Generated ${plots.size} planned plot${if (plots.size == 1) "" else "s"}."
        } catch (e: Exception) {
            errorMessage.value = "Plot generation failed: $e"
        }
    }

    suspend fun refresh() {
        val plotRepo = plannedPlotRepository ?: return
        val designRepo = designRepository ?: return
        try {
            _plannedCount.value = plotRepo.listByProject(project.id).size
            val existing = designRepo.forProject(project.id).firstOrNull()
            if (existing != null) {
                plotType.value = existing.plotType
                existing.plotAreaAcres?.let { plotAreaAcresString.value = it.toString() }
                existing.baf?.let { bafString.value = it.toString() }
                samplingScheme.value = existing.samplingScheme
                existing.gridSpacingMeters?.let { gridSpacingMetersString.value = it.toString() }
            }
            speciesRepository?.let { repo ->
                _availableSpecies.value = repo.list().sortedBy { it.commonName }
            }
            volumeEquationRepository?.let { repo ->
                _volumeEquationsById.value = repo.list().associateBy { it.id }
            }
        } catch (e: Exception) {
            errorMessage.value = "Failed to load cruise design: $e"
        }
    }

    // MARK: - GeoJSON → rings

    private fun parseRings(geojson: String): List<List<CoordinateConversions.LatLon>> {
        val obj = try {
            JSONObject(geojson)
        } catch (_: Exception) {
            throw IllegalArgumentException("Invalid stored polygon JSON")
        }
        if (obj.optString("type") != "Polygon") {
            throw IllegalArgumentException("Stored geometry is not a Polygon")
        }
        val coords = obj.optJSONArray("coordinates")
            ?: throw IllegalArgumentException("Stored geometry is not a Polygon")
        val rings = mutableListOf<List<CoordinateConversions.LatLon>>()
        for (ri in 0 until coords.length()) {
            val ringArray = coords.getJSONArray(ri)
            val ring = mutableListOf<CoordinateConversions.LatLon>()
            for (pi in 0 until ringArray.length()) {
                val pair = ringArray.getJSONArray(pi)
                if (pair.length() >= 2) {
                    ring.add(
                        CoordinateConversions.LatLon(
                            latitude = pair.getDouble(1),
                            longitude = pair.getDouble(0),
                        )
                    )
                }
            }
            rings.add(ring)
        }
        return rings
    }
}
