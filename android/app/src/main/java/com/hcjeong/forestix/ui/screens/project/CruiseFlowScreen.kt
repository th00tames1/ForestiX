// Port of iOS Screens/CruiseFlowScreen.swift — cruise-flow coordinator.
//
//   Project dashboard → [Go Cruise]
//        ↓
//   CruiseFlowScreen (planned-plot picker, this file)
//        ↓
//   NavigationScreen  (compass to target)
//        ↓ onArrival
//   CruiseRecordCenterScreen (this file, hosts PlotCenterScreen)
//        ↓ onAccept                          ↓ onTryOffset
//   PlotTallyScreen                          OffsetFlowScreen
//        ↓ Add tree / AR boundary / Close plot ...
//
// iOS drives the leaf screens through a NavigationStack `path` of
// `CruiseStep` values owned by `CruiseFlowCoordinator`. On Android each
// step is a NavHost route (CruiseStepRoutes) and the coordinator keeps the
// data responsibilities: loading the planned/visited plot lists, resolving
// plots by id, and persisting the accepted plot centre (acceptCenter).
//
// The step routes for tally / add-tree / tree-detail / AR-boundary /
// summaries are navigated to by name; the integrator wires them to the
// plot-cluster screens in ForestixRoot.kt.

package com.hcjeong.forestix.ui.screens.project

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.hcjeong.forestix.AppEnvironment
import com.hcjeong.forestix.LocalAppEnvironment
import com.hcjeong.forestix.data.cruise.CruiseDesign
import com.hcjeong.forestix.data.cruise.PlannedPlot
import com.hcjeong.forestix.data.cruise.Plot
import com.hcjeong.forestix.data.cruise.PlotCenterResult
import com.hcjeong.forestix.data.cruise.Project
import com.hcjeong.forestix.ui.clickableNoRipple
import com.hcjeong.forestix.ui.screens.ForestixScaffold
import com.hcjeong.forestix.ui.screens.plot.PlotCenterScreen
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixSpace
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// MARK: - Step routes (Android analogue of the iOS `CruiseStep` path enum)

object CruiseStepRoutes {
    const val NAVIGATE = "cruiseNavigate/{projectId}/{plannedPlotId}"
    const val RECORD_CENTER = "cruiseRecordCenter/{projectId}/{plannedPlotId}"
    const val OFFSET = "cruiseOffset/{projectId}/{plannedPlotId}"
    const val TALLY = "plotTally/{projectId}/{plotId}"
    const val ADD_TREE = "addTree/{projectId}/{plotId}"
    const val TREE_DETAIL = "treeDetail/{treeId}"
    const val AR_BOUNDARY = "cruiseArBoundary/{plotId}"
    const val SUMMARIZE = "plotSummary/{projectId}/{plotId}"
    const val STAND_SUMMARY = "standSummary/{projectId}"

    fun navigate(projectId: String, plannedPlotId: String) =
        "cruiseNavigate/$projectId/$plannedPlotId"
    fun recordCenter(projectId: String, plannedPlotId: String) =
        "cruiseRecordCenter/$projectId/$plannedPlotId"
    fun offset(projectId: String, plannedPlotId: String) =
        "cruiseOffset/$projectId/$plannedPlotId"
    fun tally(projectId: String, plotId: String) = "plotTally/$projectId/$plotId"
    fun addTree(projectId: String, plotId: String) = "addTree/$projectId/$plotId"
    fun treeDetail(treeId: String) = "treeDetail/$treeId"
    fun arBoundary(plotId: String) = "cruiseArBoundary/$plotId"
    fun summarize(projectId: String, plotId: String) = "plotSummary/$projectId/$plotId"
    fun standSummary(projectId: String) = "standSummary/$projectId"
}

// MARK: - Coordinator (port of iOS CruiseFlowCoordinator)

class CruiseFlowCoordinator(val project: Project, val design: CruiseDesign) {

    private val _unvisitedPlots = MutableStateFlow<List<PlannedPlot>>(emptyList())
    val unvisitedPlots: StateFlow<List<PlannedPlot>> = _unvisitedPlots.asStateFlow()

    private val _visitedPlots = MutableStateFlow<List<PlannedPlot>>(emptyList())
    val visitedPlots: StateFlow<List<PlannedPlot>> = _visitedPlots.asStateFlow()

    val errorMessage = MutableStateFlow<String?>(null)

    private var env: AppEnvironment? = null
    private var plotsById: MutableMap<UUID, Plot> = mutableMapOf()

    fun configure(environment: AppEnvironment) {
        this.env = environment
    }

    suspend fun refresh() {
        val env = env ?: return
        try {
            val planned = env.plannedPlotRepository.listByProject(project.id)
                .sortedBy { it.plotNumber }
            _unvisitedPlots.value = planned.filter { !it.visited }
            _visitedPlots.value = planned.filter { it.visited }

            val plots = env.plotRepository.listByProject(project.id)
            plotsById = plots.associateBy { it.id }.toMutableMap()
        } catch (e: Exception) {
            errorMessage.value = "Couldn't load plots: ${e.message ?: e}. " +
                "Try refreshing, or go back and retry."
        }
    }

    // MARK: - Lookups

    fun plannedPlot(id: UUID): PlannedPlot? =
        (_unvisitedPlots.value + _visitedPlots.value).firstOrNull { it.id == id }

    fun plot(id: UUID): Plot? = plotsById[id]

    /// The Plot row already opened for this planned plot, if any — drives
    /// the iOS `continueVisited` resume-vs-restart decision.
    fun plotForPlanned(plannedPlotId: UUID): Plot? =
        plotsById.values.firstOrNull { it.plannedPlotId == plannedPlotId }

    // MARK: - Accept centre (iOS acceptCenter)

    /// Persist a Plot row for the accepted centre and mark the PlannedPlot
    /// visited. Returns the created Plot, or null on failure (errorMessage
    /// is set).
    suspend fun acceptCenter(plannedId: UUID, result: PlotCenterResult): Plot? {
        val env = env ?: return null
        return try {
            val plotNumber = plannedPlot(plannedId)?.plotNumber ?: 0
            val areaAcres = design.plotAreaAcres ?: 0.1f
            val plot = Plot(
                id = UUID.randomUUID(), projectId = project.id,
                plannedPlotId = plannedId,
                plotNumber = plotNumber,
                centerLat = result.lat, centerLon = result.lon,
                positionSource = result.source,
                positionTier = result.tier,
                gpsNSamples = result.nSamples,
                gpsMedianHAccuracyM = result.medianHAccuracyM,
                gpsSampleStdXyM = result.sampleStdXyM,
                offsetWalkM = result.offsetWalkM,
                slopeDeg = 0f, aspectDeg = 0f,
                plotAreaAcres = areaAcres,
                startedAt = System.currentTimeMillis(),
                closedAt = null, closedBy = null,
                notes = "", coverPhotoPath = null, panoramaPath = null)
            env.plotRepository.create(plot)
            plotsById[plot.id] = plot

            plannedPlot(plannedId)?.let { pp ->
                pp.visited = true
                env.plannedPlotRepository.update(pp)
            }
            refresh()
            plot
        } catch (e: Exception) {
            errorMessage.value = "Couldn't save the plot centre: ${e.message ?: e}. " +
                "Check storage, then re-run the 60-second GPS fix."
            null
        }
    }
}

// MARK: - Root: planned-plot picker

@Composable
fun CruiseFlowScreen(nav: NavController, projectId: String) {
    val env = LocalAppEnvironment.current
    var project by remember { mutableStateOf<Project?>(null) }
    var design by remember { mutableStateOf<CruiseDesign?>(null) }
    var loadedOnce by remember { mutableStateOf(false) }
    LaunchedEffect(projectId) {
        val id = UUID.fromString(projectId)
        project = env.projectRepository.read(id)
        design = env.cruiseDesignRepository.forProject(id).firstOrNull()
        loadedOnce = true
    }
    val loadedProject = project
    val loadedDesign = design
    if (loadedProject == null || loadedDesign == null) {
        ForestixScaffold(nav, title = "Cruise") { padding ->
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                if (!loadedOnce) {
                    CircularProgressIndicator()
                } else {
                    Text(
                        "No cruise design yet. Go back to the project dashboard and run \"Cruise design\" first.",
                        style = Forestix.type.body,
                        color = Forestix.colors.textSecondary,
                        modifier = Modifier.padding(ForestixSpace.lg))
                }
            }
        }
        return
    }
    CruiseFlowContent(nav, loadedProject, loadedDesign)
}

@Composable
private fun CruiseFlowContent(nav: NavController, project: Project, design: CruiseDesign) {
    val env = LocalAppEnvironment.current
    val scope = rememberCoroutineScope()
    val colors = Forestix.colors
    val type = Forestix.type
    val flow = remember(project.id) { CruiseFlowCoordinator(project, design) }

    val unvisitedPlots by flow.unvisitedPlots.collectAsState()
    val visitedPlots by flow.visitedPlots.collectAsState()
    val errorMessage by flow.errorMessage.collectAsState()

    // iOS `.task` — also re-runs when popping back from a leaf step, so
    // the visited/unvisited split reflects the plot just opened or closed.
    LaunchedEffect(Unit) {
        flow.configure(env)
        flow.refresh()
    }

    ForestixScaffold(
        nav,
        title = "Cruise — ${project.name}",
        actions = {
            // Stand-in for iOS pull-to-refresh (`.refreshable`).
            IconButton(onClick = { scope.launch { flow.refresh() } }) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh",
                    tint = colors.primary)
            }
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ForestixSpace.md),
            verticalArrangement = Arrangement.spacedBy(ForestixSpace.md),
        ) {
            if (unvisitedPlots.isEmpty() && visitedPlots.isEmpty()) {
                Text(
                    "No planned plots yet. Go back to the project dashboard and run \"Design cruise\" first.",
                    style = type.body,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(vertical = ForestixSpace.sm))
            }
            if (unvisitedPlots.isNotEmpty()) {
                FormSection(header = "To do") {
                    unvisitedPlots.forEach { pp ->
                        PlotRow(pp, visited = false) {
                            // startNavigation(to:)
                            nav.navigate(
                                CruiseStepRoutes.navigate(
                                    project.id.toString(), pp.id.toString()))
                        }
                    }
                }
            }
            if (visitedPlots.isNotEmpty()) {
                FormSection(header = "Already visited") {
                    visitedPlots.forEach { pp ->
                        PlotRow(pp, visited = true) {
                            // continueVisited(plannedPlot:) — resume the
                            // tally if a Plot row exists, otherwise restart
                            // from the centre-recording step.
                            val plot = flow.plotForPlanned(pp.id)
                            if (plot != null) {
                                nav.navigate(
                                    CruiseStepRoutes.tally(
                                        project.id.toString(), plot.id.toString()))
                            } else {
                                nav.navigate(
                                    CruiseStepRoutes.recordCenter(
                                        project.id.toString(), pp.id.toString()))
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(ForestixSpace.xl))
        }
    }

    if (errorMessage != null) {
        AlertDialog(
            onDismissRequest = { flow.errorMessage.value = null },
            title = { Text("Couldn't start this plot") },
            text = { Text(errorMessage ?: "") },
            confirmButton = {
                TextButton(onClick = { flow.errorMessage.value = null }) { Text("OK") }
            },
        )
    }
}

// MARK: - Plot row

@Composable
private fun PlotRow(pp: PlannedPlot, visited: Boolean, onClick: () -> Unit) {
    val colors = Forestix.colors
    val type = Forestix.type
    Row(
        Modifier
            .fillMaxWidth()
            .clickableNoRipple(onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ForestixSpace.sm),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Plot ${pp.plotNumber}", style = type.bodyBold, color = colors.textPrimary)
            Text(coordinatesCaption(pp), style = type.dataSmall, color = colors.textTertiary)
        }
        if (visited) {
            Icon(Icons.Filled.CheckCircle, contentDescription = "Visited",
                tint = colors.confidenceOk, modifier = Modifier.size(20.dp))
        }
    }
}

/// Compact lat/lon line for the plot row. 4 decimals = ~11 m precision —
/// enough for the cruiser to recognise a plot they've already seen on a
/// paper map.
private fun coordinatesCaption(pp: PlannedPlot): String =
    String.format(Locale.US, "%.4f, %.4f", pp.plannedLat, pp.plannedLon)

// MARK: - Record-centre step (iOS recordCenterScreen + acceptCenter wiring)

/// Hosts the existing PlotCenterScreen and gives it the coordinator's
/// accept/offset transitions: onAccept persists the Plot row + marks the
/// PlannedPlot visited, then replaces this step with the tally (matching
/// the iOS path.removeLast() + push semantics); onTryOffset replaces this
/// step with the Offset-from-Opening flow.
@Composable
fun CruiseRecordCenterScreen(nav: NavController, projectId: String, plannedPlotId: String) {
    val env = LocalAppEnvironment.current
    val scope = rememberCoroutineScope()
    var flow by remember { mutableStateOf<CruiseFlowCoordinator?>(null) }
    LaunchedEffect(projectId) {
        val id = UUID.fromString(projectId)
        val project = env.projectRepository.read(id) ?: return@LaunchedEffect
        val design = env.cruiseDesignRepository.forProject(id).firstOrNull()
            ?: return@LaunchedEffect
        val coordinator = CruiseFlowCoordinator(project, design)
        coordinator.configure(env)
        coordinator.refresh()
        flow = coordinator
    }

    val coordinator = flow
    if (coordinator == null) {
        ForestixScaffold(nav, title = "Record plot centre") { padding ->
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        return
    }

    val errorMessage by coordinator.errorMessage.collectAsState()
    val plannedId = remember(plannedPlotId) { UUID.fromString(plannedPlotId) }

    PlotCenterScreen(
        nav = nav,
        onAccept = { result ->
            scope.launch {
                val plot = coordinator.acceptCenter(plannedId, result) ?: return@launch
                nav.navigate(
                    CruiseStepRoutes.tally(projectId, plot.id.toString())) {
                    popUpTo(CruiseStepRoutes.RECORD_CENTER) { inclusive = true }
                }
            }
        },
        onTryOffset = { _ ->
            nav.navigate(CruiseStepRoutes.offset(projectId, plannedPlotId)) {
                popUpTo(CruiseStepRoutes.RECORD_CENTER) { inclusive = true }
            }
        },
    )

    if (errorMessage != null) {
        AlertDialog(
            onDismissRequest = { coordinator.errorMessage.value = null },
            title = { Text("Couldn't start this plot") },
            text = { Text(errorMessage ?: "") },
            confirmButton = {
                TextButton(onClick = { coordinator.errorMessage.value = null }) { Text("OK") }
            },
        )
    }
}
