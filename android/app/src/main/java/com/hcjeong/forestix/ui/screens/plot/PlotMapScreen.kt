// Port of iOS Screens/PlotMapScreen.swift + ViewModels/PlotMapViewModel.swift.
// Spec §3.1 REQ-PRJ-004 "Planned plots appear on project map."
//
// iOS renders MapKit `Map { MapPolygon / Marker }`; Android draws the same
// overlays on the basemap MapView (offline slippy-map Canvas) using the
// tile template configured in Settings.

package com.hcjeong.forestix.ui.screens.plot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.hcjeong.forestix.AppEnvironment
import com.hcjeong.forestix.LocalAppEnvironment
import com.hcjeong.forestix.basemap.MapMarker
import com.hcjeong.forestix.basemap.MapPolygonOverlay
import com.hcjeong.forestix.basemap.MapView
import com.hcjeong.forestix.data.cruise.PlannedPlot
import com.hcjeong.forestix.data.cruise.PlannedPlotRepository
import com.hcjeong.forestix.data.cruise.Project
import com.hcjeong.forestix.data.cruise.StratumRepository
import com.hcjeong.forestix.geo.CoordinateConversions
import com.hcjeong.forestix.ui.screens.ForestixScaffold
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixSpace
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

// MARK: - View model (port of iOS PlotMapViewModel)

/// Spec §3.1 REQ-PRJ-004 — render stratum polygons + planned plot markers.
class PlotMapViewModel(val project: Project) {

    data class StratumShape(
        val id: UUID,
        val name: String,
        val rings: List<List<CoordinateConversions.LatLon>>,
    )

    private val _strataShapes = MutableStateFlow<List<StratumShape>>(emptyList())
    val strataShapes: StateFlow<List<StratumShape>> = _strataShapes.asStateFlow()

    private val _plannedPlots = MutableStateFlow<List<PlannedPlot>>(emptyList())
    val plannedPlots: StateFlow<List<PlannedPlot>> = _plannedPlots.asStateFlow()

    val errorMessage = MutableStateFlow<String?>(null)

    private var stratumRepository: StratumRepository? = null
    private var plannedPlotRepository: PlannedPlotRepository? = null

    fun configure(environment: AppEnvironment) {
        if (stratumRepository == null) stratumRepository = environment.stratumRepository
        if (plannedPlotRepository == null) {
            plannedPlotRepository = environment.plannedPlotRepository
        }
    }

    suspend fun refresh() {
        val stratumRepository = stratumRepository ?: return
        val plannedPlotRepository = plannedPlotRepository ?: return
        try {
            val strata = stratumRepository.listByProject(project.id)
            _strataShapes.value = strata.map { s ->
                StratumShape(
                    id = s.id,
                    name = s.name,
                    rings = parseRings(s.polygonGeoJSON))
            }
            _plannedPlots.value = plannedPlotRepository.listByProject(project.id)
        } catch (e: Exception) {
            errorMessage.value = "Failed to load map data: $e"
        }
    }

    val centroid: CoordinateConversions.LatLon?
        get() {
            val points = _strataShapes.value.flatMap { it.rings.firstOrNull() ?: emptyList() }
            if (points.isEmpty()) {
                val plot = _plannedPlots.value.firstOrNull()
                if (plot != null) {
                    return CoordinateConversions.LatLon(
                        latitude = plot.plannedLat, longitude = plot.plannedLon)
                }
                return null
            }
            val lat = points.sumOf { it.latitude } / points.size
            val lon = points.sumOf { it.longitude } / points.size
            return CoordinateConversions.LatLon(latitude = lat, longitude = lon)
        }

    // MARK: - Helpers

    /// Parse the outer + inner rings of a GeoJSON `Polygon` geometry string.
    /// Any unrecognized shape yields an empty list, matching the iOS
    /// `(try? parseRings(...)) ?? []` fallback.
    private fun parseRings(geojson: String): List<List<CoordinateConversions.LatLon>> {
        return try {
            val obj = JSONObject(geojson)
            if (obj.optString("type") != "Polygon") return emptyList()
            val coords = obj.optJSONArray("coordinates") ?: return emptyList()
            val rings = mutableListOf<List<CoordinateConversions.LatLon>>()
            for (r in 0 until coords.length()) {
                val ring = coords.optJSONArray(r) ?: continue
                val points = mutableListOf<CoordinateConversions.LatLon>()
                for (i in 0 until ring.length()) {
                    val pair = ring.optJSONArray(i) ?: continue
                    if (pair.length() < 2) continue
                    points.add(CoordinateConversions.LatLon(
                        latitude = pair.getDouble(1),
                        longitude = pair.getDouble(0)))
                }
                rings.add(points)
            }
            rings
        } catch (_: Exception) {
            emptyList()
        }
    }
}

// MARK: - Screen

/// Marker tints matching the iOS `.tint(plot.visited ? .gray : .blue)`.
private val VisitedGray = Color(0xFF8E8E93)
private val PlannedBlue = Color(0xFF007AFF)

/// Suggested route: ProjectFlowRoutes.PLOT_MAP ("plotMap/{projectId}").
@Composable
fun PlotMapScreen(nav: NavController, projectId: String) {
    val env = LocalAppEnvironment.current
    val colors = Forestix.colors
    val type = Forestix.type

    var project by remember { mutableStateOf<Project?>(null) }
    LaunchedEffect(projectId) {
        project = env.projectRepository.read(UUID.fromString(projectId))
    }
    val loaded = project
    if (loaded == null) {
        ForestixScaffold(nav, title = "Plot Map") { padding ->
            Box(
                Modifier.padding(padding).fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }
        return
    }

    val viewModel = remember(loaded.id) { PlotMapViewModel(loaded) }
    val settings by env.settings.state.collectAsStateWithLifecycle()
    val strataShapes by viewModel.strataShapes.collectAsStateWithLifecycle()
    val plannedPlots by viewModel.plannedPlots.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()

    // iOS `.task { viewModel.configure(with:); viewModel.refresh() }`.
    LaunchedEffect(loaded.id) {
        viewModel.configure(env)
        viewModel.refresh()
    }

    ForestixScaffold(nav, title = "Plot Map") { padding ->
        val centroid = viewModel.centroid
        if (centroid != null) {
            val polygons = strataShapes.mapNotNull { shape ->
                shape.rings.firstOrNull()?.let { outer -> MapPolygonOverlay(ring = outer) }
            }
            val markers = plannedPlots.map { plot ->
                MapMarker(
                    coordinate = CoordinateConversions.LatLon(
                        latitude = plot.plannedLat,
                        longitude = plot.plannedLon),
                    title = "#${plot.plotNumber}",
                    tint = if (plot.visited) VisitedGray else PlannedBlue)
            }
            MapView(
                center = centroid,
                modifier = Modifier.padding(padding).fillMaxSize(),
                // iOS uses a 0.03° span — roughly slippy-map zoom 13.
                initialZoom = 13.0,
                tileURLTemplate = settings.tileURLTemplate,
                polygons = polygons,
                markers = markers,
                attribution = settings.tileProviderLabel)
        } else {
            // MARK: - Empty state
            Box(
                Modifier.padding(padding).fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(ForestixSpace.sm),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Filled.Map,
                        contentDescription = null,
                        tint = colors.textSecondary,
                        modifier = Modifier.size(40.dp))
                    Text("No map data yet", style = type.bodyBold, color = colors.textPrimary)
                    Text(
                        "Import strata and generate planned plots to see them on the map.",
                        style = type.caption,
                        color = colors.textSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = ForestixSpace.xl))
                }
            }
        }
    }

    // MARK: - Error alert (iOS "Something went wrong")
    if (errorMessage != null) {
        AlertDialog(
            onDismissRequest = { viewModel.errorMessage.value = null },
            title = { Text("Something went wrong") },
            text = { Text(errorMessage ?: "") },
            confirmButton = {
                TextButton(onClick = { viewModel.errorMessage.value = null }) { Text("OK") }
            },
        )
    }
}
