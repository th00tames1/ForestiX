// Port of iOS ViewModels/StratumDrawViewModel.swift.
// Phase 7.4 — "draw stratum on map" view model. Lets the cruiser tap points
// on a map to outline the stratum, closes the ring automatically, computes
// area with the existing spherical-excess math, and persists via
// StratumRepository.

package com.hcjeong.forestix.ui.screens.project

import com.hcjeong.forestix.AppEnvironment
import com.hcjeong.forestix.data.cruise.Stratum
import com.hcjeong.forestix.data.cruise.StratumRepository
import com.hcjeong.forestix.geo.CoordinateConversions
import com.hcjeong.forestix.data.cruise.Project
import com.hcjeong.forestix.geo.GeoJSONImporter
import java.util.UUID
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class StratumDrawViewModel(val project: Project) {

    // MARK: - Editable state

    /// Ordered list of vertices the cruiser has tapped. The closing edge is
    /// implicit — the polygon is closed when the cruiser taps Save.
    private val _vertices = MutableStateFlow<List<CoordinateConversions.LatLon>>(emptyList())
    val vertices: StateFlow<List<CoordinateConversions.LatLon>> = _vertices.asStateFlow()

    val name = MutableStateFlow("")

    // MARK: - Derived / display

    private val _areaAcres = MutableStateFlow(0.0)
    val areaAcres: StateFlow<Double> = _areaAcres.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    val errorMessage = MutableStateFlow<String?>(null)
    val didSave = MutableStateFlow(false)

    private var stratumRepository: StratumRepository? = null

    fun configure(environment: AppEnvironment) {
        if (stratumRepository == null) {
            stratumRepository = environment.stratumRepository
        }
    }

    // MARK: - Editing

    /// Append a vertex from a map tap. Coordinates are WGS84.
    fun addVertex(coord: CoordinateConversions.LatLon) {
        _vertices.value = _vertices.value + coord
        recomputeArea()
    }

    /// Remove the most recently-added vertex ("undo").
    fun removeLast() {
        if (_vertices.value.isEmpty()) return
        _vertices.value = _vertices.value.dropLast(1)
        recomputeArea()
    }

    fun clear() {
        _vertices.value = emptyList()
        _areaAcres.value = 0.0
        name.value = ""
        errorMessage.value = null
        didSave.value = false
    }

    /// Whether the cruiser has enough vertices for a valid polygon.
    val canSave: Boolean
        get() = _vertices.value.size >= 3 &&
            name.value.trim().isNotEmpty() &&
            !_isSaving.value

    val vertexCountLabel: String
        get() = when (_vertices.value.size) {
            0 -> "Tap the corners on the map"
            1 -> "1 point — need at least 3"
            2 -> "2 points — one more to go"
            else -> "${_vertices.value.size} points · area closed"
        }

    // MARK: - Save

    suspend fun save() {
        val repo = stratumRepository
        if (repo == null) {
            errorMessage.value = "Repository not connected. Restart the app and try again."
            return
        }
        if (!canSave) {
            errorMessage.value = "At least 3 points and a stratum name are required."
            return
        }
        _isSaving.value = true
        try {
            val ring = _vertices.value
            // Close the ring if the first and last points differ.
            val closedRing = if (ring.isNotEmpty() && ring.first() != ring.last()) {
                ring + ring.first()
            } else ring

            val geoJSON = GeoJSONImporter.serialise(rings = listOf(closedRing))
            val areaM2 = abs(
                GeoJSONImporter.signedPolygonAreaMetersSquared(rings = listOf(closedRing)))
            val acres = GeoJSONImporter.metersSquaredToAcres(areaM2)

            val stratum = Stratum(
                id = UUID.randomUUID(),
                projectId = project.id,
                name = name.value.trim(),
                areaAcres = acres.toFloat(),
                polygonGeoJSON = geoJSON,
            )
            repo.create(stratum)
            didSave.value = true
        } catch (e: Exception) {
            errorMessage.value =
                "Save failed: ${e.message ?: e}. Check available storage and try again."
        } finally {
            _isSaving.value = false
        }
    }

    // MARK: - Helpers

    private fun recomputeArea() {
        val ring = _vertices.value
        if (ring.size < 3) {
            _areaAcres.value = 0.0
            return
        }
        val closed = if (ring.first() != ring.last()) ring + ring.first() else ring
        val m2 = abs(GeoJSONImporter.signedPolygonAreaMetersSquared(rings = listOf(closed)))
        _areaAcres.value = GeoJSONImporter.metersSquaredToAcres(m2)
    }
}
