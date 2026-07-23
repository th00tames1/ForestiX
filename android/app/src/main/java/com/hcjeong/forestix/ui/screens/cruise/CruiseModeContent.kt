// Cruise MODE of the map home — the v3 cruise map (approved mock
// design/forestix-redesign-v3-cruise.html: ① cruise map, ② plot peek,
// ④ tree peek, ⑤ project sheet, ⑥ cruise setup, ⑦ planned plot + guide,
// ⑧ inline centre record; ③ tally loop rides the shared DBH screen as a
// DIAMETER LOOP via CruiseCapture — heights on demand from the tree peek
// / plot heights sheet) absorbed into MapHomeScreen as a TOGGLED MODE (v3.1):
// one map, two modes, no navigation between them. MapHomeScreen owns the
// single MapView/camera and, while `tc.mapMode == "cruise"`, feeds it this
// file's markers/overlays and hosts this file's chrome, peeks and sheets.
//
// The map IS the cruise: plot RING pins (amber = active/in-progress,
// green = closed, HOLLOW DASHED = planned) + cruise tree teardrop pins
// are the whole workflow surface. The single primary (+) STATE-MORPHS:
// no active plot → "Start plot" (AR sampling-ring component saving a cruise
// Plot row); active plot → "Add tree · Plot N" straight into the shared
// DBH→Height chain on the next auto tree number. A planned pin peeks into
// "Set plot centre (GPS)" (inline GPS-averaging sheet) or "Navigate" (dashed
// you-dot→plot guide line + live distance chip — the map is the nav).
// Quick-measure pins NEVER appear in cruise mode and cruise pins never
// appear in measure mode — the two data worlds stay separate.

package com.hcjeong.forestix.ui.screens.cruise

import android.app.Activity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.hcjeong.forestix.AppEnvironment
import com.hcjeong.forestix.LocalAppEnvironment
import com.hcjeong.forestix.basemap.MapCameraState
import com.hcjeong.forestix.basemap.MapMarker
import com.hcjeong.forestix.basemap.MapMarkerShape
import com.hcjeong.forestix.basemap.MapPolygonOverlay
import com.hcjeong.forestix.basemap.MapPolylineOverlay
import com.hcjeong.forestix.common.AreaUnit
import com.hcjeong.forestix.common.MeasurementFormatter
import com.hcjeong.forestix.common.RegionalSpecies
import com.hcjeong.forestix.common.Units
import com.hcjeong.forestix.data.SettingsSnapshot
import com.hcjeong.forestix.data.cruise.BreastHeightConvention
import com.hcjeong.forestix.data.cruise.CruiseDesign
import com.hcjeong.forestix.data.cruise.PlannedPlot
import com.hcjeong.forestix.data.cruise.Plot
import com.hcjeong.forestix.data.cruise.PlotType
import com.hcjeong.forestix.data.cruise.Project
import com.hcjeong.forestix.data.cruise.SamplingScheme
import com.hcjeong.forestix.data.cruise.Tree
import com.hcjeong.forestix.export.FullCruiseExporter
import com.hcjeong.forestix.geo.CoordinateConversions
import com.hcjeong.forestix.inventory.HDModel
import com.hcjeong.forestix.inventory.PlotStats
import com.hcjeong.forestix.inventory.PlotStatsCalculator
import com.hcjeong.forestix.inventory.PooledHeights
import com.hcjeong.forestix.inventory.VolumeEquation
import com.hcjeong.forestix.inventory.VolumeEquationFactory
import com.hcjeong.forestix.positioning.CLLocationSnapshot
import com.hcjeong.forestix.positioning.GeoMath
import com.hcjeong.forestix.positioning.LocationService
import com.hcjeong.forestix.ui.MeasurePhotoStore
import com.hcjeong.forestix.ui.Routes
import com.hcjeong.forestix.ui.clickableNoRipple
import com.hcjeong.forestix.ui.pressableNoRipple
import com.hcjeong.forestix.ui.screens.CaptureColumn
import com.hcjeong.forestix.ui.screens.ClusterSlots
import com.hcjeong.forestix.ui.screens.ExportViewModel
import com.hcjeong.forestix.ui.screens.FullScreenImageViewer
import com.hcjeong.forestix.ui.screens.Haptics
import com.hcjeong.forestix.ui.screens.PeekActionButton
import com.hcjeong.forestix.ui.screens.PhotoThumb
import com.hcjeong.forestix.ui.screens.SideCircleButton
import com.hcjeong.forestix.ui.screens.TierChipSoft
import com.hcjeong.forestix.ui.screens.exportMimeFor
import com.hcjeong.forestix.ui.screens.plot.PlotFlowRoutes
import com.hcjeong.forestix.ui.screens.project.ProjectFlowRoutes
import com.hcjeong.forestix.ui.screens.zipFolderForShare
import com.hcjeong.forestix.ui.shareFile
import com.hcjeong.forestix.ui.softDropShadow
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixColors
import com.hcjeong.forestix.ui.theme.ForestixRadius
import com.hcjeong.forestix.ui.theme.ForestixSpace
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/// Everything cruise mode renders for the CURRENT project, loaded in one
/// pass (fresh on entry + after every mutating action). `plannedPlots` is
/// the UNVISITED plan only — visited planned plots are represented by
/// their real Plot rings.
internal data class CruiseData(
    val project: Project?,
    val plots: List<Plot>,
    val treesByPlot: Map<UUID, List<Tree>>,
    val plannedPlots: List<PlannedPlot> = emptyList(),
) {
    val trees: List<Tree> get() = treesByPlot.values.flatten()
}

// MARK: - Mode state ----------------------------------------------------------

/// Cruise-mode UI state, owned by MapHomeScreen (remembered across the mode
/// toggle so the exit crossfade renders from live data; selections are
/// cleared by the toggle itself). CruiseModeEffects wires the actions and
/// runs the data loading while cruise mode is on.
internal class CruiseModeState {
    var data by mutableStateOf(CruiseData(null, emptyList(), emptyMap()))
    /// Bump to reload after every mutating action.
    var refresh by mutableIntStateOf(0)
    /// Selected pin id ("plot-…" / "tree-…" / "planned-…") — peek card up.
    var selectedId by mutableStateOf<String?>(null)
    var projectSheetOpen by mutableStateOf(false)
    var cruiseSetupOpen by mutableStateOf(false)
    var recordCentreFor by mutableStateOf<PlannedPlot?>(null)
    /// Planned plot the dashed guide line + distance chip point at (mock ⑦).
    var navTargetId by mutableStateOf<UUID?>(null)
    /// Plot whose heights sheet is open ("Heights · N measured", B).
    var heightsSheetFor by mutableStateOf<UUID?>(null)

    /// Actions — wired by CruiseModeEffects (they need nav/scope/settings).
    var startPlot: () -> Unit = {}
    var addTree: (Plot) -> Unit = {}
    var closePlot: (Plot) -> Unit = {}
    /// Toggle a planned plot's `skipped` flag (planned-peek "Skip plot" /
    /// "Un-skip"). A skipped plot is inaccessible (cliff/water/private land)
    /// so it drops out of nearest-unvisited nav while still rendering.
    var skipPlanned: (PlannedPlot) -> Unit = {}
    /// Hard delete a plot + cascade its trees (plot peek "Delete plot").
    var deletePlot: (Plot) -> Unit = {}
    /// Hard delete a tree + its photo (tree peek "Delete tree").
    var deleteTree: (Tree) -> Unit = {}
    /// Height on demand (tree peek "Measure height" / heights sheet).
    var measureHeight: (Tree) -> Unit = {}

    val project: Project? get() = data.project

    /// The ACTIVE (open) plot the (+) scopes "Add tree · Plot N" to.
    fun activePlot(settings: SettingsSnapshot): Plot? =
        settings.cruisePlotId?.let(::uuidOrNull)
            ?.let { id -> data.plots.firstOrNull { it.id == id && it.closedAt == null } }

    fun navTarget(): PlannedPlot? =
        navTargetId?.let { id -> data.plannedPlots.firstOrNull { it.id == id } }

    fun navDistanceM(fix: CLLocationSnapshot?): Double? {
        val target = navTarget() ?: return null
        val f = fix ?: return null
        return GeoMath.distanceM(
            fromLat = f.latitude, fromLon = f.longitude,
            toLat = target.plannedLat, toLon = target.plannedLon)
    }
}

/// Non-visual half of cruise mode: data loading, navigate-mode housekeeping
/// and the action lambdas. MapHomeScreen composes this only while cruise
/// mode is on, so (re)entering the mode — or returning from the DBH→Height
/// chain — always reloads fresh.
@Composable
internal fun CruiseModeEffects(
    state: CruiseModeState,
    nav: NavController,
    settings: SettingsSnapshot,
    fix: CLLocationSnapshot?,
    /// True while the shared camera still waits for its first centring —
    /// the only window where cruise geometry may pull the camera home.
    awaitingFirstFix: Boolean,
    /// No fix yet: centre the SHARED camera once on the newest cruise
    /// geometry instead of the Peavy fallback so a returning cruiser sees
    /// their stand (the callback stamps awaitingFirstFix false).
    onCentreOnCruiseGeometry: (CoordinateConversions.LatLon) -> Unit,
) {
    val env = LocalAppEnvironment.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = remember { Haptics(context) }

    // MARK: - Cruise data (current project + plots + trees)

    LaunchedEffect(state.refresh, settings.cruiseProjectId) {
        val project = resolveCurrentProject(env, settings.cruiseProjectId)
        val plots = project?.let { env.plotRepository.listByProject(it.id) } ?: emptyList()
        val trees = plots.associate { it.id to env.treeRepository.listByPlot(it.id) }
        val planned = project?.let { p ->
            env.plannedPlotRepository.listByProject(p.id).filter { !it.visited }
        } ?: emptyList()
        state.data = CruiseData(project, plots, trees, planned)
        // Stale active-plot guard (deleted / closed / other project).
        val active = settings.cruisePlotId?.let(::uuidOrNull)
        if (active != null && plots.none { it.id == active && it.closedAt == null }) {
            env.settings.setCruisePlotId(null)
        }
        if (awaitingFirstFix && fix == null) {
            val target = trees.values.flatten()
                .sortedByDescending { it.createdAt }
                .firstOrNull { it.latitude != null && it.longitude != null }
                ?.let { CoordinateConversions.LatLon(latitude = it.latitude!!, longitude = it.longitude!!) }
                ?: plots.sortedByDescending { it.startedAt }
                    .firstOrNull { it.centerLat != 0.0 || it.centerLon != 0.0 }
                    ?.let { CoordinateConversions.LatLon(latitude = it.centerLat, longitude = it.centerLon) }
            if (target != null) onCentreOnCruiseGeometry(target)
        }
    }

    // MARK: - Navigate mode (mock ⑦ — the map is the nav)

    // Target vanished (recorded / regenerated / project switch) → clear.
    LaunchedEffect(state.navTargetId, state.data) {
        if (state.navTargetId != null && state.navTarget() == null) state.navTargetId = null
    }
    // Arrival: within 5 m fires one haptic and clears the guide (mock
    // "5 m arrival buzz"; the retired NavigationScreen's radius).
    val navDistanceM = state.navDistanceM(fix)
    LaunchedEffect(navDistanceM) {
        val d = navDistanceM ?: return@LaunchedEffect
        if (d <= 5.0) {
            haptics.warn()
            state.navTargetId = null
        }
    }

    // MARK: - Actions

    /// (+) with no active plot: ensure a CURRENT project exists (auto-named,
    /// units from settings — name-once philosophy, never a gate), then hand
    /// off to the AR sampling-ring plot creator.
    state.startPlot = {
        scope.launch {
            try {
                val p = state.project ?: createDefaultProject(env, settings).also {
                    env.settings.setCruiseProjectId(it.id.toString())
                }
                nav.navigate(CruiseRoutes.startPlot(p.id.toString()))
            } catch (_: Exception) {
                // Project auto-create failed (storage) — stay on the map;
                // the next tap retries.
            }
        }
    }

    /// (+) with an active plot / plot-peek primary: arm the cruise capture
    /// session on the next auto tree number and enter the shared DBH
    /// screen's QUICK-TALLY DIAMETER LOOP (project calibration, GPS +
    /// photo on Accept; the screen resets per save until the floating
    /// back exits).
    state.addTree = addTree@{ plot ->
        val p = state.project ?: return@addTree
        scope.launch {
            if (settings.cruisePlotId != plot.id.toString()) {
                env.settings.setCruisePlotId(plot.id.toString())
            }
            val next = (state.data.treesByPlot[plot.id].orEmpty()
                .maxOfOrNull { it.treeNumber } ?: 0) + 1
            CruiseCapture.begin(env, p, plot, next)
            nav.navigate("${Routes.DBH}?chain=true")
        }
    }

    state.closePlot = { plot ->
        scope.launch {
            try {
                plot.closedAt = System.currentTimeMillis()
                plot.closedBy = state.project?.owner?.takeIf { it.isNotBlank() }
                env.plotRepository.update(plot)
                if (settings.cruisePlotId == plot.id.toString()) {
                    env.settings.setCruisePlotId(null)
                }
            } catch (_: Exception) {
                plot.closedAt = null
                plot.closedBy = null
            }
            state.selectedId = null
            state.refresh++
        }
    }

    /// Planned peek "Skip plot (can't reach)" / "Un-skip": flip the planned
    /// plot's `skipped` flag and persist through the SAME
    /// plannedPlotRepository.update path RecordCentreSheet uses to set
    /// visited=true. A newly-skipped plot drops out of nearest-unvisited nav,
    /// so any dashed guide line armed at it is cleared. Reverts the in-memory
    /// flag on a storage error, mirroring closePlot.
    state.skipPlanned = { planned ->
        scope.launch {
            try {
                planned.skipped = !planned.skipped
                env.plannedPlotRepository.update(planned)
                if (planned.skipped && state.navTargetId == planned.id) {
                    state.navTargetId = null
                }
            } catch (_: Exception) {
                planned.skipped = !planned.skipped
            }
            state.selectedId = null
            state.refresh++
        }
    }

    /// Plot peek "Delete plot": cascade hard-delete every tree row (including
    /// soft-deleted ones) then remove the plot row, clearing the active-plot
    /// pointer if it was this one. Peek dismisses + map reloads on refresh.
    state.deletePlot = { plot ->
        scope.launch {
            try {
                env.treeRepository.listByPlot(plot.id, includeDeleted = true)
                    .forEach { env.treeRepository.hardDelete(it.id) }
                env.plotRepository.delete(plot.id)
                if (settings.cruisePlotId == plot.id.toString()) {
                    env.settings.setCruisePlotId(null)
                }
            } catch (_: Exception) {
                // Storage error — leave the plot in place; the next tap retries.
            }
            state.selectedId = null
            state.refresh++
        }
    }

    /// Tree peek "Delete tree": hard delete the row + its auto-captured photo
    /// (same MeasurePhotoStore the quick world uses). Peek dismisses + reloads.
    state.deleteTree = { tree ->
        scope.launch {
            try {
                tree.photoPath?.let { name ->
                    (context as? Activity)?.let { MeasurePhotoStore.delete(it, name) }
                }
                env.treeRepository.hardDelete(tree.id)
            } catch (_: Exception) {
                // Storage error — leave the tree in place; the next tap retries.
            }
            state.selectedId = null
            state.refresh++
        }
    }

    /// Per-tree height on demand (tree peek "Measure height" / the plot
    /// heights sheet): arm a HEIGHT-ONLY cruise session on the EXISTING
    /// tree row and enter the shared Height screen scoped to it — the
    /// accepted reading folds into that row, the screen pops back here,
    /// and the map's re-entry runs CruiseCapture.end() as always.
    state.measureHeight = measureHeight@{ tree ->
        val p = state.project ?: return@measureHeight
        val plot = state.data.plots.firstOrNull { it.id == tree.plotId }
            ?: return@measureHeight
        CruiseCapture.beginHeight(env, p, plot, tree)
        // Close the peek under the pushed screen (iOS startScopedHeight).
        state.selectedId = null
        nav.navigate("height?tree=${tree.treeNumber}")
    }
}

// MARK: - MapView inputs (the ONE map is MapHomeScreen's) ----------------------

internal fun cruiseModeMarkers(
    state: CruiseModeState,
    settings: SettingsSnapshot,
    colors: ForestixColors,
): List<MapMarker> = cruiseMarkers(
    state.data, state.activePlot(settings), state.selectedId, colors.accent,
    colors.confidenceOk, colors.confidenceWarn, colors.primary,
    colors.textTertiary)

/// The active plot's TRUE boundary circle (mock `.plotring`).
internal fun cruiseModePolygons(
    state: CruiseModeState,
    settings: SettingsSnapshot,
    accent: Color,
): List<MapPolygonOverlay> = state.activePlot(settings)
    ?.takeIf { it.centerLat != 0.0 || it.centerLon != 0.0 }
    ?.let { listOf(plotBoundaryOverlay(it, accent)) }
    ?: emptyList()

/// Navigate mode: dashed you-dot → planned-plot guide (mock ⑦).
internal fun cruiseModePolylines(
    state: CruiseModeState,
    fix: CLLocationSnapshot?,
    accent: Color,
): List<MapPolylineOverlay> {
    val target = state.navTarget() ?: return emptyList()
    val f = fix ?: return emptyList()
    return listOf(
        MapPolylineOverlay(
            points = listOf(
                CoordinateConversions.LatLon(
                    latitude = f.latitude, longitude = f.longitude),
                CoordinateConversions.LatLon(
                    latitude = target.plannedLat, longitude = target.plannedLon),
            ),
            color = accent,
            dashed = true,
        ),
    )
}

// MARK: - Overlays + bottom content hosted by MapHomeScreen --------------------

/// Navigate mode's floating live distance chip (mock `.distchip`) — pinned
/// to the guide line's midpoint, same projection as the map draw pass
/// (falls back to under-chrome centre until layout).
@Composable
internal fun BoxScope.CruiseDistanceOverlay(
    state: CruiseModeState,
    camera: MapCameraState,
    fix: CLLocationSnapshot?,
) {
    val navTarget = state.navTarget() ?: return
    val navFix = fix ?: return
    val navDistanceM = state.navDistanceM(fix) ?: return
    val a = camera.screenPoint(
        CoordinateConversions.LatLon(
            latitude = navFix.latitude, longitude = navFix.longitude))
    val b = camera.screenPoint(
        CoordinateConversions.LatLon(
            latitude = navTarget.plannedLat, longitude = navTarget.plannedLon))
    if (a != null && b != null) {
        DistanceChip(
            navDistanceLabel(navDistanceM),
            modifier = Modifier
                .offset {
                    IntOffset(
                        ((a.x + b.x) / 2f).roundToInt(),
                        ((a.y + b.y) / 2f).roundToInt(),
                    )
                }
                // Centre the chip on the midpoint (offset places the
                // top-left corner there).
                .graphicsLayer {
                    translationX = -size.width / 2f
                    translationY = -size.height / 2f
                },
        )
    } else {
        DistanceChip(
            navDistanceLabel(navDistanceM),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 72.dp),
        )
    }
}

/// Bottom half of cruise mode: the morphing (+) cluster (with the mode
/// toggle + Log circles in the map home's exact positions), or a peek card
/// when a cruise pin is up.
@Composable
internal fun CruiseModeBottomContent(
    state: CruiseModeState,
    nav: NavController,
    fix: CLLocationSnapshot?,
    onToggleMode: () -> Unit,
) {
    val env = LocalAppEnvironment.current
    val settings by env.settings.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? Activity
    val activePlot = state.activePlot(settings)

    AnimatedContent(
        targetState = state.selectedId,
        contentAlignment = Alignment.BottomCenter,
        transitionSpec = {
            val spec = tween<Float>(durationMillis = 180, easing = EaseOut)
            (slideInVertically { it } + fadeIn(spec)) togetherWith
                (slideOutVertically { it } + fadeOut(spec))
        },
        label = "cruisePeekCluster",
    ) { sel ->
        // Resolve peek data from THIS branch's id, so the exiting card
        // keeps its own content through the transition.
        val peekPlot = sel?.takeIf { it.startsWith("plot-") }
            ?.removePrefix("plot-")?.let(::uuidOrNull)
            ?.let { id -> state.data.plots.firstOrNull { it.id == id } }
        val peekTree = sel?.takeIf { it.startsWith("tree-") }
            ?.removePrefix("tree-")?.let(::uuidOrNull)
            ?.let { id -> state.data.trees.firstOrNull { it.id == id } }
        val peekPlanned = sel?.takeIf { it.startsWith("planned-") }
            ?.removePrefix("planned-")?.let(::uuidOrNull)
            ?.let { id -> state.data.plannedPlots.firstOrNull { it.id == id } }
        when {
            peekPlanned != null -> PlannedPeekCard(
                planned = peekPlanned,
                fix = fix,
                navigating = state.navTargetId == peekPlanned.id,
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 20.dp),
                onRecordCentre = {
                    state.selectedId = null
                    state.recordCentreFor = peekPlanned
                },
                onToggleNavigate = {
                    state.navTargetId =
                        if (state.navTargetId == peekPlanned.id) null else peekPlanned.id
                },
                onToggleSkip = { state.skipPlanned(peekPlanned) },
            )

            peekPlot != null -> PlotPeekCard(
                env = env,
                plot = peekPlot,
                project = state.project,
                trees = state.data.treesByPlot[peekPlot.id].orEmpty(),
                allProjectTrees = state.data.trees,
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 20.dp),
                onAddTree = { state.addTree(peekPlot) },
                onHeights = { state.heightsSheetFor = peekPlot.id },
                onClose = { state.closePlot(peekPlot) },
                onDelete = { state.deletePlot(peekPlot) },
                onDetails = {
                    nav.navigate(PlotFlowRoutes.plotSummary(peekPlot.id.toString()))
                },
            )

            peekTree != null -> TreePeekCard(
                tree = peekTree,
                plotNumber = state.data.plots.firstOrNull { it.id == peekTree.plotId }?.plotNumber,
                unitSystemMetric = state.project?.units == com.hcjeong.forestix.data.cruise.UnitSystem.METRIC,
                activity = activity,
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 20.dp),
                onMeasureHeight = { state.measureHeight(peekTree) },
                onEdit = {
                    nav.navigate(PlotFlowRoutes.treeDetail(peekTree.id.toString()))
                },
                onDelete = { state.deleteTree(peekTree) },
            )

            else -> {
                // (+) disambiguation (map-peek spec item 5): with unvisited
                // planned plots and NO active plot, the (+) sets the nearest
                // plot's centre (same flow as tapping its dashed pin →
                // RecordCentreSheet) instead of starting a fresh plot.
                // Nearest by current fix; no fix → first unvisited planned.
                // Skipped plots are inaccessible (documented), so they are
                // never nav-eligible even though they still render on the map.
                val plannedUnvisited = state.data.plannedPlots.filter { !it.skipped }
                CruiseActionCluster(
                    activePlot = activePlot,
                    hasPlannedUnvisited = plannedUnvisited.isNotEmpty(),
                    treeCount = activePlot?.let { state.data.treesByPlot[it.id]?.size } ?: 0,
                    projectName = state.project?.name ?: "New project",
                    modifier = Modifier.padding(bottom = ForestixSpace.sm),
                    onToggleMode = onToggleMode,
                    onCapture = {
                        val plot = state.activePlot(settings)
                        when {
                            plot != null -> state.addTree(plot)
                            plannedUnvisited.isNotEmpty() -> {
                                val f = fix ?: LocationService.lastGlobalFix
                                val nearest = if (f == null) {
                                    plannedUnvisited.first()
                                } else {
                                    plannedUnvisited.minByOrNull {
                                        GeoMath.distanceM(
                                            f.latitude, f.longitude,
                                            it.plannedLat, it.plannedLon)
                                    }
                                }
                                nearest?.let { state.recordCentreFor = it }
                            }
                            else -> state.startPlot()
                        }
                    },
                    onProject = { state.projectSheetOpen = true },
                )
            }
        }
    }
}

/// Cruise-mode sheets (project ⑤ / cruise setup ⑥ / inline centre record
/// ⑧), hosted by MapHomeScreen after its own measure-world sheets.
@Composable
internal fun CruiseModeSheets(
    state: CruiseModeState,
    nav: NavController,
    camera: MapCameraState,
    fallbackCentre: CoordinateConversions.LatLon,
) {
    val env = LocalAppEnvironment.current
    val settings by env.settings.state.collectAsStateWithLifecycle()

    // MARK: - Project sheet (mock ⑤)

    if (state.projectSheetOpen) {
        ProjectSheet(
            env = env,
            currentProject = state.project,
            settings = settings,
            onDismiss = { state.projectSheetOpen = false; state.refresh++ },
            onSwitch = { id ->
                env.settings.setCruiseProjectId(id)
                env.settings.setCruisePlotId(null)
                state.projectSheetOpen = false
                state.refresh++
            },
            onNavigate = { route ->
                state.projectSheetOpen = false
                nav.navigate(route)
            },
            onCruiseSetup = {
                state.projectSheetOpen = false
                state.cruiseSetupOpen = true
            },
        )
    }

    // MARK: - Cruise setup sheet (mock ⑥ — replaces CruiseDesignScreen)

    val setupProject = state.project
    if (state.cruiseSetupOpen && setupProject != null) {
        CruiseSetupSheet(
            project = setupProject,
            mapCentre = camera.center ?: fallbackCentre,
            onDismiss = {
                state.cruiseSetupOpen = false
                state.refresh++
            },
            onDrawBoundary = {
                state.cruiseSetupOpen = false
                nav.navigate(ProjectFlowRoutes.stratumDraw(setupProject.id.toString()))
            },
        )
    }

    // MARK: - Plot heights sheet (B — "Heights · N measured")

    val heightsPlot = state.heightsSheetFor
        ?.let { id -> state.data.plots.firstOrNull { it.id == id } }
    if (heightsPlot != null) {
        PlotHeightsSheet(
            plot = heightsPlot,
            trees = state.data.treesByPlot[heightsPlot.id].orEmpty(),
            unitSystemMetric = state.project?.units ==
                com.hcjeong.forestix.data.cruise.UnitSystem.METRIC,
            onDismiss = { state.heightsSheetFor = null },
            onMeasure = { tree ->
                state.heightsSheetFor = null
                state.measureHeight(tree)
            },
        )
    }

    // MARK: - Inline centre recording (mock ⑧ — replaces PlotCenterScreen)

    val recordPlanned = state.recordCentreFor
    val recordProject = state.project
    if (recordPlanned != null && recordProject != null) {
        RecordCentreSheet(
            project = recordProject,
            planned = recordPlanned,
            onDismiss = { state.recordCentreFor = null },
            onSaved = { plot ->
                state.recordCentreFor = null
                if (state.navTargetId == recordPlanned.id) state.navTargetId = null
                state.selectedId = "plot-${plot.id}"
                state.refresh++
            },
            onUseOffset = {
                state.recordCentreFor = null
                nav.navigate(
                    CruiseRoutes.offset(
                        recordProject.id.toString(), recordPlanned.id.toString()))
            },
        )
    }
}

// MARK: - Data helpers --------------------------------------------------------

private fun uuidOrNull(s: String): UUID? = try {
    UUID.fromString(s)
} catch (_: Exception) {
    null
}

/// Current project: the persisted chip choice when it still exists, else
/// the newest project, else null ("New project" chip).
private suspend fun resolveCurrentProject(
    env: AppEnvironment,
    cruiseProjectId: String?,
): Project? {
    val picked = cruiseProjectId?.let(::uuidOrNull)?.let { env.projectRepository.read(it) }
    return picked ?: env.projectRepository.list().firstOrNull()
}

/// Zero-gate fallback when (+) "Start plot" is tapped with no project yet:
/// auto-named project, units from app settings, calibration defaults
/// (identity + 5 mm depth noise — the retired project screen's create).
private suspend fun createDefaultProject(
    env: AppEnvironment,
    settings: SettingsSnapshot,
): Project {
    val now = System.currentTimeMillis()
    val n = env.projectRepository.list().size + 1
    val project = Project(
        id = UUID.randomUUID(),
        name = "Project $n",
        description = "",
        owner = "",
        createdAt = now,
        updatedAt = now,
        units = if (settings.unitSystem == com.hcjeong.forestix.common.UnitSystem.METRIC) {
            com.hcjeong.forestix.data.cruise.UnitSystem.METRIC
        } else {
            com.hcjeong.forestix.data.cruise.UnitSystem.IMPERIAL
        },
        breastHeightConvention = BreastHeightConvention.UPHILL,
        slopeCorrection = true,
        lidarBiasMm = 0f,
        depthNoiseMm = 5f,
        dbhCorrectionAlpha = 0f,
        dbhCorrectionBeta = 1f,
        vioDriftFraction = 0.02f,
    )
    return env.projectRepository.create(project)
}

/// Fixed-radius metres back out of the denormalized acre area.
private fun plotRadiusM(plot: Plot): Double =
    sqrt(Units.acresToSquareMeters(plot.plotAreaAcres.toDouble()) / PI)

/// The active plot's TRUE boundary circle on the basemap (mock `.plotring`),
/// rendered through the existing polygon overlay — accent stroke, near-clear
/// fill. 48-segment circle in local metres → lat/lon.
private fun plotBoundaryOverlay(plot: Plot, accent: Color): MapPolygonOverlay {
    val r = plotRadiusM(plot)
    val latRad = plot.centerLat * PI / 180.0
    val mPerDegLat = 111_132.0
    val mPerDegLon = 111_320.0 * cos(latRad)
    val ring = (0 until 48).map { i ->
        val a = 2.0 * PI * i / 48.0
        CoordinateConversions.LatLon(
            latitude = plot.centerLat + (r * kotlin.math.sin(a)) / mPerDegLat,
            longitude = plot.centerLon + (r * cos(a)) / mPerDegLon,
        )
    }
    return MapPolygonOverlay(
        ring = ring,
        fillColor = accent.copy(alpha = 0.07f),
        strokeColor = accent.copy(alpha = 0.8f),
    )
}

private fun isWarn(raw: String?) = raw == "yellow" || raw == "red"

/// Plot RING pins (status colour: amber in-progress / green closed /
/// HOLLOW DASHED tertiary = planned) + cruise tree teardrop pins.
private fun cruiseMarkers(
    data: CruiseData,
    activePlot: Plot?,
    selectedId: String?,
    accent: Color,
    ok: Color,
    warn: Color,
    primary: Color,
    plannedTint: Color,
): List<MapMarker> {
    val markers = mutableListOf<MapMarker>()
    // Unvisited planned plots (mock ⑦ `.plotpin.planned`): dashed hollow
    // rings in the tertiary ink — visibly "not real yet". A SKIPPED plot
    // (inaccessible) stays on the map but dims to 40% so it reads as a
    // documented decision, not a pending target.
    for (planned in data.plannedPlots) {
        val id = "planned-${planned.id}"
        markers += MapMarker(
            coordinate = CoordinateConversions.LatLon(
                latitude = planned.plannedLat, longitude = planned.plannedLon),
            title = "P${planned.plotNumber}",
            tint = if (planned.skipped) plannedTint.copy(alpha = 0.4f) else plannedTint,
            id = id,
            shape = MapMarkerShape.RING,
            selected = id == selectedId,
            dashed = true,
        )
    }
    for (plot in data.plots) {
        if (plot.centerLat == 0.0 && plot.centerLon == 0.0) continue
        val id = "plot-${plot.id}"
        markers += MapMarker(
            coordinate = CoordinateConversions.LatLon(
                latitude = plot.centerLat, longitude = plot.centerLon),
            title = "P${plot.plotNumber}",
            tint = if (plot.closedAt != null) ok else accent,
            id = id,
            shape = MapMarkerShape.RING,
            selected = id == selectedId || plot.id == activePlot?.id,
        )
    }
    for (tree in data.trees) {
        val lat = tree.latitude ?: continue
        val lon = tree.longitude ?: continue
        if (tree.deletedAt != null) continue
        val id = "tree-${tree.id}"
        // No D/H badges here — the v3 mock's cruise pins are bare drops
        // (every cruise tree has a DBH by construction).
        markers += MapMarker(
            coordinate = CoordinateConversions.LatLon(latitude = lat, longitude = lon),
            title = "T${tree.treeNumber}",
            tint = if (isWarn(tree.dbhConfidence.raw) || isWarn(tree.heightConfidence?.raw)) {
                warn
            } else {
                primary
            },
            id = id,
            shape = MapMarkerShape.PIN,
            selected = id == selectedId,
        )
    }
    return markers
}

// MARK: - Chrome pieces -------------------------------------------------------

/// Mock `.distchip` — the floating live distance readout while the dashed
/// guide line is up ("142 m"; kilometres past ~1 km, iOS parity).
private fun navDistanceLabel(metres: Double): String =
    if (metres < 995) {
        String.format(Locale.US, "%.0f m", metres)
    } else {
        String.format(Locale.US, "%.1f km", metres / 1000.0)
    }

@Composable
private fun DistanceChip(text: String, modifier: Modifier = Modifier) {
    val colors = Forestix.colors
    Text(
        text,
        style = Forestix.type.dataSmall.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold),
        color = colors.textPrimary,
        modifier = modifier
            .softDropShadow(Color.Black.copy(alpha = 0.18f), 8.dp, 2.dp, cornerRadius = 999.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(colors.surface)
            .border(1.dp, colors.divider, RoundedCornerShape(999.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

// MARK: - Planned-plot peek (mock ⑦) -------------------------------------------

/// Peek for a HOLLOW DASHED planned pin: live distance + bearing from the
/// current fix, a 54 dp "Set plot centre (GPS)" primary into the inline
/// averaging sheet, and a "Navigate" toggle for the guide line.
@Composable
private fun PlannedPeekCard(
    planned: PlannedPlot,
    fix: CLLocationSnapshot?,
    navigating: Boolean,
    modifier: Modifier = Modifier,
    onRecordCentre: () -> Unit,
    onToggleNavigate: () -> Unit,
    onToggleSkip: () -> Unit,
) {
    val colors = Forestix.colors
    val type = Forestix.type
    val shape = RoundedCornerShape(14.dp)
    // A skipped plot is documented inaccessible: the peek swaps the PLANNED
    // chip for SKIPPED and offers only "Un-skip" (no centre-set / navigate).
    val skipped = planned.skipped

    Column(
        modifier
            .fillMaxWidth()
            .softDropShadow(Color.Black.copy(alpha = 0.22f), 14.dp, (-4).dp, cornerRadius = 14.dp)
            .clip(shape)
            .background(colors.surface)
            .border(1.dp, colors.divider, shape)
            .padding(14.dp),
    ) {
        Box(
            Modifier
                .align(Alignment.CenterHorizontally)
                .padding(bottom = 10.dp)
                .size(width = 36.dp, height = 4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(colors.divider),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            // LOCKED string "Plot N (planned)".
            Text(
                "Plot ${planned.plotNumber} (planned)",
                style = type.bodyBold.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold),
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.size(8.dp))
            if (skipped) SkippedChip() else PlannedChip()
            Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.size(6.dp))
        // LOCKED live line "X m · bearing Y°" from the current fix (the
        // screen's live service, else the newest global fix).
        val rangeFix = fix ?: LocationService.lastGlobalFix
        Row(
            Modifier.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ForestixSpace.xs),
        ) {
            Text(
                "FROM YOU",
                style = type.caption.copy(
                    fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.7.sp),
                color = colors.textTertiary,
                modifier = Modifier.width(72.dp),
            )
            Text(
                if (rangeFix != null) {
                    val d = GeoMath.distanceM(
                        fromLat = rangeFix.latitude, fromLon = rangeFix.longitude,
                        toLat = planned.plannedLat, toLon = planned.plannedLon)
                    val b = GeoMath.bearingDeg(
                        fromLat = rangeFix.latitude, fromLon = rangeFix.longitude,
                        toLat = planned.plannedLat, toLon = planned.plannedLon)
                    String.format(
                        Locale.US, "%.0f m · bearing %.0f°", d, (b + 360.0).mod(360.0))
                } else {
                    "no GPS fix"
                },
                style = type.dataSmall.copy(fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold),
                color = colors.textPrimary,
                maxLines = 1,
            )
        }
        Spacer(Modifier.size(ForestixSpace.sm))
        if (!skipped) {
            // 54 dp primary — LOCKED "Set plot centre (GPS)".
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 54.dp)
                    .pressableNoRipple(onClick = onRecordCentre)
                    .clip(ForestixRadius.card)
                    .background(colors.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Set plot centre (GPS)",
                    style = type.bodyBold.copy(fontSize = 15.sp),
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
            Spacer(Modifier.size(8.dp))
            // LOCKED secondary "Navigate" — toggles the dashed guide line;
            // the active state reads in the accent outline.
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .pressableNoRipple(onClick = onToggleNavigate)
                    .clip(ForestixRadius.control)
                    .border(
                        if (navigating) 1.5.dp else 1.dp,
                        if (navigating) colors.accent else colors.divider,
                        ForestixRadius.control,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Navigate",
                    style = type.bodyBold.copy(fontSize = 14.sp),
                    color = if (navigating) colors.accent else colors.textPrimary,
                )
            }
            Spacer(Modifier.size(8.dp))
            // 44 dp outline "Skip plot (can't reach)" — marks the plot
            // inaccessible so nearest-unvisited nav passes over it. Modeled on
            // the Navigate outline button above.
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .pressableNoRipple(onClick = onToggleSkip)
                    .clip(ForestixRadius.control)
                    .border(1.dp, colors.divider, ForestixRadius.control),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Skip plot (can't reach)",
                    style = type.bodyBold.copy(fontSize = 14.sp),
                    color = colors.textSecondary,
                )
            }
        } else {
            // Documented inaccessible: no centre-set / navigate. A single
            // "Un-skip" outline reverses the decision (mirrors the
            // reopen-closed-plot affordance) and returns the plot to the
            // nearest-unvisited nav set.
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .pressableNoRipple(onClick = onToggleSkip)
                    .clip(ForestixRadius.control)
                    .border(1.dp, colors.divider, ForestixRadius.control),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Un-skip",
                    style = type.bodyBold.copy(fontSize = 14.sp),
                    color = colors.textPrimary,
                )
            }
        }
    }
}

/// Shared status token, planned flavour: hollow-dashed grey like the pin
/// (iOS plannedChip 1:1).
@Composable
private fun PlannedChip() {
    val colors = Forestix.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .clip(ForestixRadius.chip)
            .background(colors.surfaceRaised)
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        val tertiary = colors.textTertiary
        Box(
            Modifier
                .size(6.dp)
                .drawBehind {
                    drawCircle(
                        color = tertiary,
                        style = Stroke(
                            width = 1.5.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(
                                floatArrayOf(2.dp.toPx(), 2.dp.toPx())),
                        ),
                    )
                },
        )
        Text(
            "PLANNED",
            style = Forestix.type.caption.copy(
                fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp),
            color = tertiary,
        )
    }
}

/// Status token for a SKIPPED planned plot: a SOLID (not dashed) warn-tinted
/// dot + "SKIPPED" — reads as a deliberate, documented decision, visibly
/// distinct from the hollow-dashed PLANNED chip.
@Composable
private fun SkippedChip() {
    val colors = Forestix.colors
    val warn = colors.confidenceWarn
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .clip(ForestixRadius.chip)
            .background(colors.surfaceRaised)
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(warn),
        )
        Text(
            "SKIPPED",
            style = Forestix.type.caption.copy(
                fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp),
            color = warn,
        )
    }
}

// MARK: - Morphing (+) cluster (mock ① `.actioncluster`, v3.1 merged) ----------

/// Cruise flavour of the map home's action cluster: the SAME three fixed
/// ClusterSlots (mode toggle circle · 74 dp capture · PROJECT circle —
/// pixel positions identical to measure mode by construction), with the
/// toggle showing the CURRENT mode (plot-centre target ◎ + "CRUISE" in the
/// cruise accent) and the (+) morphing between "Start plot" and "Add tree ·
/// Plot N" over the LOCKED cruise-accent fill + white glyph. The RIGHT
/// circle is the PROJECT button (folder → project sheet), where measure
/// mode keeps its Log circle. The project strip floats in the shared
/// reserved zone; the scoped halo paints outside the slot — neither is ever
/// measured, so nothing shifts.
@Composable
private fun CruiseActionCluster(
    activePlot: Plot?,
    hasPlannedUnvisited: Boolean,
    treeCount: Int,
    projectName: String,
    modifier: Modifier = Modifier,
    onToggleMode: () -> Unit,
    onCapture: () -> Unit,
    onProject: () -> Unit,
) {
    val colors = Forestix.colors
    // The PROJECT STRIP is now its OWN centred row ABOVE the three circles
    // (not inside the tally zone) so a long "<project> · No active plot"
    // pill can never overlap the side circles. The cluster Row keeps the
    // fixed ClusterSlots geometry (empty reserved tally zone in both modes),
    // so pixel-invariance with measure mode is unchanged.
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ProjectStrip(
            projectName = projectName,
            activePlot = activePlot,
            treeCount = treeCount,
            onClick = onProject,
        )
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(ClusterSlots.gap),
        ) {
            SideCircleButton(
                "Cruise", Icons.Filled.Adjust,
                tint = colors.cruiseAccent,
                onClick = onToggleMode,
            )
            // 74 dp primary (+) — LOCKED cruise-accent fill + WHITE glyph;
            // accent-scoped outline while a plot is active (mock
            // `.capture.scoped`). LOCKED strings: active plot → "Add tree ·
            // Plot N"; unvisited plan waiting → "Set plot centre" (map-peek
            // spec item 5); otherwise → "Start plot".
            val captureLabel = when {
                activePlot != null -> "Add tree · Plot ${activePlot.plotNumber}"
                hasPlannedUnvisited -> "Set plot centre"
                else -> "Start plot"
            }
            CaptureColumn(
                caption = captureLabel,
                contentDescription = when {
                    activePlot != null -> "Add tree"
                    hasPlannedUnvisited -> "Set plot centre"
                    else -> "Start plot"
                },
                fill = colors.cruiseAccent,
                ink = Color.White,
                haloed = activePlot != null,
                tallyPill = null,
                onClick = onCapture,
            )
            // RIGHT circle: PROJECT (measure mode's Log slot) → project sheet.
            SideCircleButton("Project", Icons.Filled.Folder, onClick = onProject)
        }
    }
}

/// Project strip — the cruise-only pill that REPLACES the old "N trees"
/// tally pill (folding the count in): "<project> · Plot N · M trees", or
/// "<project> · No active plot". A tappable dark-glass pill in the
/// cluster's reserved tally zone as unbounded-width overflow, so its width
/// never re-measures the pixel-invariant cluster; tapping opens the same
/// project sheet as the PROJECT circle.
@Composable
private fun ProjectStrip(
    projectName: String,
    activePlot: Plot?,
    treeCount: Int,
    onClick: () -> Unit,
) {
    val colors = Forestix.colors
    val type = Forestix.type
    val text = if (activePlot != null) {
        "$projectName · Plot ${activePlot.plotNumber} · $treeCount trees"
    } else {
        "$projectName · No active plot"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        modifier = Modifier
            .padding(bottom = 12.dp)
            .wrapContentWidth(unbounded = true)
            .softDropShadow(Color.Black.copy(alpha = 0.18f), 10.dp, 2.dp, cornerRadius = 999.dp)
            .pressableNoRipple(onClick = onClick)
            .clip(RoundedCornerShape(999.dp))
            .background(Color(0xCC06090A))
            .padding(horizontal = 13.dp, vertical = 7.dp),
    ) {
        // Status dot: accent while a plot is active, dim otherwise.
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(if (activePlot != null) colors.accent else colors.textTertiary),
        )
        Text(
            text,
            style = type.dataSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
            color = Color(0xFFF2F5F3),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            // Long project names truncate instead of stretching the pill
            // across the whole screen; still layout-neutral (unbounded).
            modifier = Modifier.widthIn(max = 260.dp),
        )
    }
}

// MARK: - Plot peek (mock ②) ---------------------------------------------------

@Composable
private fun PlotPeekCard(
    env: AppEnvironment,
    plot: Plot,
    project: Project?,
    trees: List<Tree>,
    allProjectTrees: List<Tree>,
    modifier: Modifier = Modifier,
    onAddTree: () -> Unit,
    onHeights: () -> Unit,
    onClose: () -> Unit,
    onDelete: () -> Unit,
    onDetails: () -> Unit,
) {
    val colors = Forestix.colors
    val type = Forestix.type
    val shape = RoundedCornerShape(14.dp)
    val closed = plot.closedAt != null
    var confirmDelete by remember(plot.id) { mutableStateOf(false) }

    // Live stats via the SAME inventory engine PlotTallyScreen uses.
    var stats by remember(plot.id, trees.size) { mutableStateOf(PlotStats.empty) }
    LaunchedEffect(plot.id, trees.size) {
        stats = computePlotStats(env, project, plot, trees, allProjectTrees)
    }
    val nextTree = (trees.maxOfOrNull { it.treeNumber } ?: 0) + 1
    // B. Pooled sample heights — "N measured" counts the live trees
    // carrying a height (iOS measuredHeightCount parity; the pooled FIT
    // additionally applies the engine's > 1.3 m cleaning).
    val heightsCount = trees.count { it.deletedAt == null && it.heightM != null }

    Column(
        modifier
            .fillMaxWidth()
            .softDropShadow(Color.Black.copy(alpha = 0.22f), 14.dp, (-4).dp, cornerRadius = 14.dp)
            .clip(shape)
            .background(colors.surface)
            .border(1.dp, colors.divider, shape)
            .padding(14.dp),
    ) {
        Box(
            Modifier
                .align(Alignment.CenterHorizontally)
                .padding(bottom = 10.dp)
                .size(width = 36.dp, height = 4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(colors.divider),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            // LOCKED string "Plot N".
            Text(
                "Plot ${plot.plotNumber}",
                style = type.bodyBold.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold),
                color = colors.textPrimary,
            )
            Spacer(Modifier.size(8.dp))
            StatusChipSoft(
                label = if (closed) "Closed" else "In progress",
                color = if (closed) colors.confidenceOk else colors.accent,
            )
            Spacer(Modifier.weight(1f))
            Text(
                String.format(
                    Locale.US, "r %.0f m · %s",
                    plotRadiusM(plot),
                    SimpleDateFormat("HH:mm", Locale.US).format(Date(plot.startedAt)),
                ),
                style = type.dataSmall.copy(fontSize = 11.sp),
                color = colors.textTertiary,
                maxLines = 1,
            )
        }
        Spacer(Modifier.size(10.dp))
        // Stats strip (mock `.stats`): TREES / BA / TPA / QMD. Per-area basis
        // follows the project's units (US per acre, metric per hectare); the
        // engine computes per acre, so scale + relabel at display only.
        val metric = project?.units == com.hcjeong.forestix.data.cruise.UnitSystem.METRIC
        val areaUnit = if (metric) AreaUnit.HECTARE else AreaUnit.ACRE
        val densityF = areaUnit.perAcreDensityFactor
        val densitySuffix = areaUnit.densitySuffix
        Row(
            Modifier
                .fillMaxWidth()
                .clip(ForestixRadius.card)
                .border(1.dp, colors.divider, ForestixRadius.card),
        ) {
            StatsCell("TREES", "${stats.liveTreeCount}", null, Modifier.weight(1f))
            StatsDivider()
            StatsCell(
                "BA", String.format(Locale.US, "%.1f", stats.baPerAcreM2 * densityF),
                "m²$densitySuffix", Modifier.weight(1f))
            StatsDivider()
            StatsCell(
                if (metric) "TPH" else "TPA", String.format(Locale.US, "%.0f", stats.tpa * densityF),
                densitySuffix, Modifier.weight(1f))
            StatsDivider()
            StatsCell(
                "QMD", String.format(Locale.US, "%.1f", stats.qmdCm),
                "cm", Modifier.weight(1f))
        }
        // B. PLOT SAMPLE HEIGHTS — LOCKED string "Heights · N measured";
        // list-row into the heights sheet (iOS plotPeek row 1:1), shown
        // for open AND closed plots.
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .clickableNoRipple(onHeights),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Filled.Height,
                contentDescription = null,
                tint = colors.textTertiary,
                modifier = Modifier.size(16.dp),
            )
            Text(
                "Heights · $heightsCount measured",
                style = type.bodyBold.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                color = colors.textPrimary,
            )
            Spacer(Modifier.weight(1f))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.textTertiary,
                modifier = Modifier.size(14.dp),
            )
        }
        HorizontalDivider(color = colors.divider, thickness = 0.5.dp)
        Spacer(Modifier.size(ForestixSpace.sm))
        if (!closed) {
            // 54 dp full-width primary with the next auto number baked in.
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 54.dp)
                    .pressableNoRipple(onClick = onAddTree)
                    .clip(ForestixRadius.card)
                    .background(colors.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Add tree · Tree $nextTree",
                    style = type.bodyBold.copy(fontSize = 15.sp),
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
            Spacer(Modifier.size(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(ForestixSpace.xs)) {
                // LOCKED string "Close plot".
                PeekActionButton("Close plot", primary = false, modifier = Modifier.weight(1f)) {
                    onClose()
                }
                PeekActionButton("Details", primary = false, modifier = Modifier.weight(1f)) {
                    onDetails()
                }
            }
        } else {
            PeekActionButton("Details", primary = false, modifier = Modifier.fillMaxWidth()) {
                onDetails()
            }
        }
        Spacer(Modifier.size(8.dp))
        // Destructive "Delete plot" (map-peek spec item 4) — cascades to the
        // plot's trees, behind an AlertDialog confirm. Explicit button for
        // both open and closed plots; never long-press.
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .pressableNoRipple(onClick = { confirmDelete = true })
                .clip(ForestixRadius.control)
                .border(1.dp, colors.confidenceBad.copy(alpha = 0.5f), ForestixRadius.control),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Delete plot",
                style = type.bodyBold.copy(fontSize = 14.sp),
                color = colors.confidenceBad,
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = {
                Text(
                    "Delete Plot ${plot.plotNumber} and its ${trees.size} " +
                        (if (trees.size == 1) "tree?" else "trees?"),
                )
            },
            text = {
                Text("This permanently removes the plot and all its trees. This cannot be undone.")
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}

// MARK: - Plot heights sheet (B) -----------------------------------------------

/// Pooled sample heights for one plot (no species dimension): the measured
/// (tree #, DBH, height) pairs, the LOCKED "Height curve active — other
/// trees estimated" caption once ≥ 3 pairs exist, and a 54 dp "Measure
/// height" primary that opens a compact tree-number picker (default = the
/// last tallied tree) into the Height screen for that tree.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlotHeightsSheet(
    plot: Plot,
    trees: List<Tree>,
    unitSystemMetric: Boolean,
    onDismiss: () -> Unit,
    onMeasure: (Tree) -> Unit,
) {
    val colors = Forestix.colors
    val type = Forestix.type
    val unitSystem = if (unitSystemMetric) {
        com.hcjeong.forestix.common.UnitSystem.METRIC
    } else {
        com.hcjeong.forestix.common.UnitSystem.IMPERIAL
    }
    val live = remember(trees) {
        trees.filter { it.deletedAt == null }.sortedBy { it.treeNumber }
    }
    // List rows — trees carrying a height, tally order (iOS `measured`).
    val withHeights = remember(trees) { live.filter { it.heightM != null } }
    var pickerOpen by remember { mutableStateOf(false) }
    // Default = the LAST TALLIED tree (newest row), not the highest number
    // with a height — the cruiser usually heights the tree just measured.
    var selectedId by remember {
        mutableStateOf(live.maxByOrNull { it.createdAt }?.id)
    }
    val selected = live.firstOrNull { it.id == selectedId }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.surface) {
        Column(
            Modifier
                .padding(horizontal = ForestixSpace.md)
                .padding(bottom = ForestixSpace.xl)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                "HEIGHTS · PLOT ${plot.plotNumber}",
                style = type.sectionHead.copy(
                    fontWeight = FontWeight.ExtraBold, letterSpacing = 1.0.sp),
                color = colors.textTertiary,
                modifier = Modifier.padding(bottom = ForestixSpace.xs),
            )

            // Measured (tree #, DBH, height) pairs — pooled, no species
            // dimension.
            if (withHeights.isEmpty()) {
                Text(
                    "No heights measured yet",
                    style = type.caption,
                    color = colors.textTertiary,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
            withHeights.forEach { t ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 9.dp, horizontal = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Tree ${t.treeNumber}",
                        style = type.bodyBold.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold),
                        color = colors.textPrimary,
                        modifier = Modifier.width(76.dp),
                    )
                    Text(
                        MeasurementFormatter.diameter(t.dbhCm.toDouble(), unitSystem),
                        style = type.dataSmall.copy(
                            fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold),
                        color = colors.textSecondary,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        t.heightM?.let {
                            MeasurementFormatter.height(it.toDouble(), unitSystem)
                        } ?: "—",
                        style = type.dataSmall.copy(
                            fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold),
                        color = colors.textPrimary,
                    )
                }
                HorizontalDivider(color = colors.divider, thickness = 0.5.dp)
            }

            // LOCKED caption — the pooled curve is live and volume
            // estimation imputes the plot's unmeasured heights from it.
            if (withHeights.size >= PooledHeights.MIN_PAIRS) {
                Text(
                    "Height curve active — other trees estimated",
                    style = type.caption,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(top = ForestixSpace.xs),
                )
            }
            Spacer(Modifier.size(ForestixSpace.sm))

            if (!pickerOpen) {
                // 54 dp primary — LOCKED "Measure height"; stage 1 opens
                // the compact tree-number picker.
                HeightsSheetPrimary(enabled = live.isNotEmpty()) {
                    selectedId = live.maxByOrNull { it.createdAt }?.id
                    pickerOpen = true
                }
            } else {
                // Compact tree-number picker (default = last tallied
                // tree) — selectable "Tree N · DBH (✓)" rows, the
                // Android analogue of the iOS wheel.
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    live.forEach { t ->
                        val isSel = t.id == selectedId
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 44.dp)
                                .pressableNoRipple(onClick = { selectedId = t.id })
                                .clip(ForestixRadius.control)
                                .background(
                                    if (isSel) colors.primaryMuted else Color.Transparent)
                                .padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Tree ${t.treeNumber} · " +
                                    MeasurementFormatter.diameter(
                                        t.dbhCm.toDouble(), unitSystem) +
                                    (if (t.heightM != null) " ✓" else ""),
                                style = type.dataSmall.copy(
                                    fontSize = 13.5.sp,
                                    fontWeight = if (isSel) FontWeight.Bold
                                    else FontWeight.Medium,
                                ),
                                color = if (isSel) colors.primary else colors.textPrimary,
                            )
                        }
                    }
                }
                Spacer(Modifier.size(ForestixSpace.xs))
                // Stage 2 confirm — same LOCKED "Measure height" label.
                HeightsSheetPrimary(enabled = selected != null) {
                    selected?.let { onMeasure(it) }
                }
            }
        }
    }
}

/// The heights sheet's 54 dp primary — LOCKED "Measure height" on both
/// stages (iOS measureButton parity: filled primary, 0.45 alpha when
/// there is nothing to measure).
@Composable
private fun HeightsSheetPrimary(enabled: Boolean, onClick: () -> Unit) {
    val colors = Forestix.colors
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .pressableNoRipple(enabled = enabled, onClick = onClick)
            .clip(ForestixRadius.card)
            .background(colors.primary),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "Measure height",
            style = Forestix.type.bodyBold.copy(fontSize = 15.sp),
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

@Composable
private fun StatusChipSoft(label: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .clip(ForestixRadius.chip)
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Text(
            label.uppercase(),
            style = Forestix.type.caption.copy(
                fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp),
            color = color,
        )
    }
}

@Composable
private fun StatsCell(label: String, value: String, unit: String?, modifier: Modifier = Modifier) {
    val colors = Forestix.colors
    val type = Forestix.type
    Column(
        modifier.padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            label,
            style = type.caption.copy(
                fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.7.sp),
            color = colors.textTertiary,
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value,
                style = type.dataSmall.copy(fontSize = 14.5.sp, fontWeight = FontWeight.Bold),
                color = colors.textPrimary,
            )
            if (unit != null) {
                Text(
                    " $unit",
                    style = type.dataSmall.copy(fontSize = 9.sp),
                    color = colors.textTertiary,
                )
            }
        }
    }
}

@Composable
private fun StatsDivider() {
    Box(
        Modifier
            .size(width = 1.dp, height = 44.dp)
            .background(Forestix.colors.divider),
    )
}

/// PlotTallyViewModel.refresh + recomputeStats, folded into one pure pass
/// for the peek card (species / volume equations / H–D fits from the repos,
/// design falls back to a fixed-area stub exactly like AddTreeFlowScreen).
/// B: missing heights are estimated from the POOLED (species-blind)
/// DBH-height curve — plot-level pairs at ≥ 3, stand-level pool as
/// fallback — beneath the persisted §7.4 per-species fits.
private suspend fun computePlotStats(
    env: AppEnvironment,
    project: Project?,
    plot: Plot,
    trees: List<Tree>,
    allProjectTrees: List<Tree> = emptyList(),
): PlotStats {
    return try {
        val design = env.cruiseDesignRepository.forProject(plot.projectId).firstOrNull()
            ?: CruiseDesign(
                id = UUID.randomUUID(),
                projectId = plot.projectId,
                plotType = PlotType.FIXED_AREA,
                plotAreaAcres = plot.plotAreaAcres,
                baf = null,
                samplingScheme = SamplingScheme.MANUAL,
                gridSpacingMeters = null,
            )
        val speciesByCode = env.speciesConfigRepository.list().associateBy { it.code }
        val equationsById = mutableMapOf<String, VolumeEquation>()
        for (r in env.volumeEquationRepository.list()) {
            VolumeEquationFactory.make(r)?.let { equationsById[r.id] = it }
        }
        val volBySpecies = buildMap {
            for ((code, sp) in speciesByCode) {
                equationsById[sp.volumeEquationId]?.let { put(code, it) }
            }
        }
        val hdFits = buildMap {
            val projectId = project?.id ?: plot.projectId
            for (fit in env.heightDiameterFitRepository.listByProject(projectId)) {
                HDModel.Fit.fromCoefficients(fit.coefficients, nObs = fit.nObs, rmse = fit.rmse)
                    ?.let { put(fit.speciesCode, it) }
            }
        }
        val pooled = PooledHeights.fit(trees) ?: PooledHeights.fit(allProjectTrees)
        PlotStatsCalculator.compute(
            plot = plot,
            cruiseDesign = design,
            trees = trees,
            species = speciesByCode,
            volumeEquations = volBySpecies,
            hdFits = PooledHeights.overlay(hdFits, pooled, trees),
        )
    } catch (_: Exception) {
        PlotStats.empty
    }
}

// MARK: - Tree peek (mock ④) ----------------------------------------------------

@Composable
private fun TreePeekCard(
    tree: Tree,
    plotNumber: Int?,
    unitSystemMetric: Boolean,
    activity: Activity?,
    modifier: Modifier = Modifier,
    onMeasureHeight: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = Forestix.colors
    val type = Forestix.type
    val shape = RoundedCornerShape(14.dp)
    val unitSystem = if (unitSystemMetric) {
        com.hcjeong.forestix.common.UnitSystem.METRIC
    } else {
        com.hcjeong.forestix.common.UnitSystem.IMPERIAL
    }
    var showPhoto by remember(tree.id) { mutableStateOf(false) }
    var confirmDelete by remember(tree.id) { mutableStateOf(false) }

    Column(
        modifier
            .fillMaxWidth()
            .softDropShadow(Color.Black.copy(alpha = 0.22f), 14.dp, (-4).dp, cornerRadius = 14.dp)
            .clip(shape)
            .background(colors.surface)
            .border(1.dp, colors.divider, shape)
            .padding(14.dp),
    ) {
        Box(
            Modifier
                .align(Alignment.CenterHorizontally)
                .padding(bottom = 10.dp)
                .size(width = 36.dp, height = 4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(colors.divider),
        )
        Row {
            Text(
                "Tree ${tree.treeNumber}" +
                    (tree.speciesCode.takeIf { it.isNotBlank() }
                        ?.let { " · ${RegionalSpecies.nameForCode(it)}" } ?: ""),
                style = type.bodyBold.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold),
                color = colors.textPrimary,
                modifier = Modifier.weight(1f).alignByBaseline(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    plotNumber?.let { "Plot $it" },
                    SimpleDateFormat("d MMM · HH:mm", Locale.US).format(Date(tree.createdAt)),
                ).joinToString(" · "),
                style = type.dataSmall.copy(fontSize = 11.sp),
                color = colors.textTertiary,
                modifier = Modifier.alignByBaseline(),
                maxLines = 1,
            )
        }
        Spacer(Modifier.size(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Tapping the thumbnail opens the full-screen viewer (map-peek
            // spec item 3). The cruise tree photo lives in the same store.
            PhotoThumb(
                tree.photoPath, if (tree.photoPath != null) 1 else 0, activity,
                onClick = tree.photoPath?.let { { showPhoto = true } },
            )
            Column(Modifier.weight(1f)) {
                TreeMetricRow(
                    label = "DBH",
                    value = MeasurementFormatter.diameter(tree.dbhCm.toDouble(), unitSystem),
                    tierRaw = tree.dbhConfidence.raw,
                )
                if (tree.heightM != null) {
                    HorizontalDivider(color = colors.divider, thickness = 0.5.dp)
                    TreeMetricRow(
                        label = "HEIGHT",
                        value = MeasurementFormatter.height(tree.heightM!!.toDouble(), unitSystem),
                        tierRaw = tree.heightConfidence?.raw,
                    )
                }
            }
        }
        // Post-hoc detail chips (mock `.dchips`) — read-only here; edits
        // live on the existing TreeDetailScreen.
        Row(
            Modifier.padding(top = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            DetailChip(
                "SPECIES",
                tree.speciesCode.takeIf { it.isNotBlank() }
                    ?.let { RegionalSpecies.nameForCode(it) } ?: "—",
            )
            DetailChip("STATUS", tree.status.raw.replaceFirstChar { it.titlecase(Locale.US) })
            DetailChip(
                "DAMAGE",
                if (tree.damageCodes.isEmpty()) "None" else "${tree.damageCodes.size}",
            )
        }
        // Auto-computed geometry (replaces the retired Extras step).
        val bearing = tree.bearingFromCenterDeg
        val dist = tree.distanceFromCenterM
        if (bearing != null && dist != null) {
            Text(
                String.format(
                    Locale.US, "Bearing %.0f° · %.1f m from centre — auto", bearing, dist),
                style = type.dataSmall.copy(fontSize = 10.5.sp),
                color = colors.textTertiary,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Spacer(Modifier.size(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(ForestixSpace.xs)) {
            // A. Per-tree height on demand — LOCKED string "Measure height"
            // (the quick-tally loop dropped the chained Height leg).
            PeekActionButton("Measure height", primary = false, modifier = Modifier.weight(1f)) {
                onMeasureHeight()
            }
            // "Edit this tree" (map-peek spec item 3, was "Edit details") —
            // same TreeDetailScreen route: values + soft-delete + raw meta.
            PeekActionButton("Edit this tree", primary = true, modifier = Modifier.weight(1f)) {
                onEdit()
            }
        }
        Spacer(Modifier.size(8.dp))
        // Destructive "Delete tree" (map-peek spec item 4) — hard delete the
        // row + its photo, behind an AlertDialog confirm. Explicit button,
        // never long-press.
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .pressableNoRipple(onClick = { confirmDelete = true })
                .clip(ForestixRadius.control)
                .border(1.dp, colors.confidenceBad.copy(alpha = 0.5f), ForestixRadius.control),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Delete tree",
                style = type.bodyBold.copy(fontSize = 14.sp),
                color = colors.confidenceBad,
            )
        }
    }

    if (showPhoto) {
        tree.photoPath?.let { name ->
            FullScreenImageViewer(name, activity, onDismiss = { showPhoto = false })
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete Tree ${tree.treeNumber}?") },
            text = { Text("This permanently removes the tree and its photo. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun TreeMetricRow(label: String, value: String, tierRaw: String?) {
    val colors = Forestix.colors
    val type = Forestix.type
    Row(
        Modifier.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ForestixSpace.xs),
    ) {
        Text(
            label,
            style = type.caption.copy(
                fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.7.sp),
            color = colors.textTertiary,
            modifier = Modifier.width(52.dp),
        )
        // ±σ intentionally dropped from the peek metric row (map-peek spec
        // item 1); the value stands alone. Sigma still ships to storage /
        // CSV / FieldLog and the tree's Raw-metadata editor.
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                value,
                style = type.dataSmall.copy(fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold),
                color = colors.textPrimary,
            )
        }
        if (tierRaw != null) TierChipSoft(tierRaw)
    }
}

@Composable
private fun DetailChip(label: String, value: String) {
    val colors = Forestix.colors
    val type = Forestix.type
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .border(1.dp, colors.divider, RoundedCornerShape(7.dp))
            .padding(horizontal = 9.dp, vertical = 5.dp),
    ) {
        Text(
            label,
            style = type.caption.copy(
                fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp),
            color = colors.textTertiary,
        )
        Text(
            value,
            style = type.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
            color = colors.textPrimary,
        )
    }
}

// MARK: - Project sheet (mock ⑤) -------------------------------------------------

private data class ProjectRowInfo(
    val project: Project,
    val plotCount: Int,
    val closedPlots: Int,
    val treeCount: Int,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectSheet(
    env: AppEnvironment,
    currentProject: Project?,
    settings: SettingsSnapshot,
    onDismiss: () -> Unit,
    onSwitch: (String) -> Unit,
    onNavigate: (String) -> Unit,
    onCruiseSetup: () -> Unit,
) {
    val colors = Forestix.colors
    val type = Forestix.type
    val scope = rememberCoroutineScope()

    var rows by remember { mutableStateOf<List<ProjectRowInfo>>(emptyList()) }
    var newProjectOpen by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        rows = env.projectRepository.list().map { p ->
            val plots = env.plotRepository.listByProject(p.id)
            var treeCount = 0
            for (plot in plots) treeCount += env.treeRepository.listByPlot(plot.id).size
            ProjectRowInfo(
                project = p,
                plotCount = plots.size,
                closedPlots = plots.count { it.closedAt != null },
                treeCount = treeCount,
            )
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.surface) {
        Column(
            Modifier
                .padding(horizontal = ForestixSpace.md)
                .padding(bottom = ForestixSpace.xl)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                "PROJECT",
                style = type.sectionHead.copy(
                    fontWeight = FontWeight.ExtraBold, letterSpacing = 1.0.sp),
                color = colors.textTertiary,
                modifier = Modifier.padding(bottom = ForestixSpace.xs),
            )

            // Switcher rows (mock `.prow`).
            rows.forEach { info ->
                val isCurrent = info.project.id == currentProject?.id
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clickableNoRipple {
                            if (!isCurrent) onSwitch(info.project.id.toString())
                        }
                        .padding(vertical = 12.dp, horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ForestixSpace.sm),
                ) {
                    RadioDot(on = isCurrent)
                    Column(Modifier.weight(1f)) {
                        Text(
                            info.project.name,
                            style = type.bodyBold,
                            color = colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "Plots ${info.closedPlots}/${info.plotCount} · " +
                                "Trees ${info.treeCount} · " +
                                SimpleDateFormat("d MMM", Locale.US)
                                    .format(Date(info.project.createdAt)),
                            style = type.dataSmall.copy(fontSize = 10.5.sp),
                            color = colors.textTertiary,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    if (isCurrent) {
                        StatusChipSoft("Current", colors.accent)
                    }
                }
                HorizontalDivider(color = colors.divider, thickness = 0.5.dp)
            }

            // "New project" — name ONCE, everything else auto (Plot 1, 2… /
            // tree numbers). Units follow app settings.
            if (!newProjectOpen) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clickableNoRipple { newProjectOpen = true }
                        .padding(vertical = 12.dp, horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ForestixSpace.sm),
                ) {
                    RadioDot(on = false, dashed = true)
                    Column(Modifier.weight(1f)) {
                        Text("New project", style = type.bodyBold, color = colors.textSecondary)
                        Text(
                            "Name it once — plots and tree numbers are automatic",
                            style = type.dataSmall.copy(fontSize = 10.5.sp),
                            color = colors.textTertiary,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            } else {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp, horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ForestixSpace.xs),
                ) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        placeholder = { Text("Project name") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Words),
                        modifier = Modifier.weight(1f),
                    )
                    PeekActionButton(
                        "Create",
                        primary = true,
                        enabled = newName.trim().isNotEmpty(),
                    ) {
                        val trimmed = newName.trim()
                        if (trimmed.isNotEmpty()) {
                            scope.launch {
                                val now = System.currentTimeMillis()
                                val project = Project(
                                    id = UUID.randomUUID(),
                                    name = trimmed,
                                    description = "",
                                    owner = "",
                                    createdAt = now,
                                    updatedAt = now,
                                    units = if (settings.unitSystem ==
                                        com.hcjeong.forestix.common.UnitSystem.METRIC
                                    ) {
                                        com.hcjeong.forestix.data.cruise.UnitSystem.METRIC
                                    } else {
                                        com.hcjeong.forestix.data.cruise.UnitSystem.IMPERIAL
                                    },
                                    breastHeightConvention = BreastHeightConvention.UPHILL,
                                    slopeCorrection = true,
                                    lidarBiasMm = 0f,
                                    depthNoiseMm = 5f,
                                    dbhCorrectionAlpha = 0f,
                                    dbhCorrectionBeta = 1f,
                                    vioDriftFraction = 0.02f,
                                )
                                env.projectRepository.create(project)
                                onSwitch(project.id.toString())
                            }
                        }
                    }
                }
            }
            HorizontalDivider(color = colors.divider, thickness = 0.5.dp)

            // Project tool rows — all target EXISTING screens.
            val projectId = currentProject?.id?.toString()
            SheetChoiceRow(
                Icons.Filled.BarChart,
                "Stand summary",
                "Mean ± CI · per-plot table",
                enabled = projectId != null,
            ) { projectId?.let { onNavigate(CruiseRoutes.standSummary(it)) } }
            HorizontalDivider(color = colors.divider, thickness = 0.5.dp)
            SheetChoiceRow(
                Icons.Filled.GridOn,
                "Cruise setup",
                "Grid plots · strata · prism/BAF — optional",
                enabled = projectId != null,
                trailingChip = "Advanced",
            ) { if (projectId != null) onCruiseSetup() }

            // Export collapse (mock ⑤ `.sheetcol`): one primary "Export
            // all" through the existing bundle path, the 11-row picker
            // relegated to a "Choose files…" line.
            val exportProject = currentProject
            if (exportProject != null) {
                ExportAllBlock(
                    env = env,
                    project = exportProject,
                    onChooseFiles = {
                        onNavigate(ProjectFlowRoutes.export(exportProject.id.toString()))
                    },
                )
            }

            // Relocated hub tools (the Phase A "Classic view" bridge is
            // retired with the legacy stack).
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = ForestixSpace.sm),
                horizontalArrangement = Arrangement.spacedBy(ForestixSpace.xs),
            ) {
                ToolButton("Field log", Modifier.weight(1f)) { onNavigate(Routes.FIELD_LOG) }
                ToolButton("Reference", Modifier.weight(1f)) {
                    onNavigate(Routes.REFERENCE_LIBRARY)
                }
                ToolButton("Settings", Modifier.weight(1f)) { onNavigate(Routes.SETTINGS) }
            }
        }
    }
}

// MARK: - Export collapse (mock ⑤ `.sheetcol`) ---------------------------------

/// Primary 54 dp "Export all" running the EXISTING full-bundle export
/// (ExportViewModel.exportAll: CSV ×5 · GeoJSON ×2 · SHP ×3 · PDF) with
/// live progress and the same zip-the-session-folder share hand-off the
/// Export screen uses; "Choose files…" opens that kept screen.
@Composable
private fun ExportAllBlock(
    env: AppEnvironment,
    project: Project,
    onChooseFiles: () -> Unit,
) {
    val colors = Forestix.colors
    val type = Forestix.type
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val viewModel = remember(project.id) { ExportViewModel(project) }
    LaunchedEffect(viewModel) { viewModel.configure(env) }

    val isExporting by viewModel.isExporting.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val progressLabel by viewModel.progressLabel.collectAsStateWithLifecycle()
    val shareURL by viewModel.shareURL.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()

    // ExportScreen parity: folders ride one ACTION_SEND as a stored zip.
    LaunchedEffect(shareURL) {
        val target = shareURL ?: return@LaunchedEffect
        try {
            val file = withContext(Dispatchers.IO) {
                if (target.isDirectory) zipFolderForShare(target) else target
            }
            if (file != null) {
                shareFile(context, FullCruiseExporter.shareUri(context, file), exportMimeFor(file))
            }
        } finally {
            viewModel.shareURL.value = null
        }
    }

    Column(Modifier.fillMaxWidth().padding(top = ForestixSpace.sm)) {
        // 54 dp primary — LOCKED "Export all".
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 54.dp)
                .pressableNoRipple(enabled = !isExporting, onClick = {
                    scope.launch { viewModel.exportAll(context) }
                })
                .clip(ForestixRadius.card)
                .background(colors.primary),
            contentAlignment = Alignment.Center,
        ) {
            if (isExporting) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(22.dp),
                )
            } else {
                Text(
                    "Export all",
                    style = type.bodyBold.copy(fontSize = 15.sp),
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
        if (isExporting) {
            Column(Modifier.padding(top = ForestixSpace.xs)) {
                LinearProgressIndicator(
                    progress = { progress.toFloat() },
                    color = colors.primary,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    progressLabel,
                    style = type.caption,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
        errorMessage?.let {
            Text(
                it,
                style = type.caption,
                color = colors.confidenceBad,
                modifier = Modifier.padding(top = ForestixSpace.xs),
            )
        }
        // LOCKED smaller "Choose files…" → the kept ExportScreen picker.
        Text(
            "Choose files…",
            style = type.caption.copy(fontWeight = FontWeight.SemiBold),
            color = colors.textSecondary,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .clickableNoRipple(onChooseFiles)
                .padding(top = ForestixSpace.xs, bottom = 2.dp),
        )
    }
}

@Composable
private fun RadioDot(on: Boolean, dashed: Boolean = false) {
    val colors = Forestix.colors
    Box(
        Modifier
            .size(20.dp)
            .clip(CircleShape)
            .border(
                2.dp,
                if (on) colors.primary else colors.divider,
                CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (on) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(colors.primary))
        } else if (dashed) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .border(1.dp, colors.textTertiary, CircleShape),
            )
        }
    }
}

@Composable
private fun SheetChoiceRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean = true,
    trailingChip: String? = null,
    onClick: () -> Unit,
) {
    val colors = Forestix.colors
    val type = Forestix.type
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickableNoRipple { if (enabled) onClick() }
            .padding(vertical = 13.dp, horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ForestixSpace.sm),
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(if (enabled) colors.primaryMuted else colors.surfaceRaised),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (enabled) colors.primary else colors.textTertiary,
                modifier = Modifier.size(22.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = type.bodyBold.copy(fontSize = 15.5.sp, fontWeight = FontWeight.Bold),
                    color = if (enabled) colors.textPrimary else colors.textTertiary,
                )
                if (trailingChip != null) {
                    Spacer(Modifier.size(7.dp))
                    Text(
                        trailingChip.uppercase(),
                        style = type.caption.copy(
                            fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp),
                        color = colors.textTertiary,
                        modifier = Modifier
                            .clip(ForestixRadius.chip)
                            .background(colors.surfaceRaised)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            Text(subtitle, style = type.caption, color = colors.textSecondary)
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = colors.textTertiary,
            modifier = Modifier.size(13.dp),
        )
    }
}

@Composable
private fun ToolButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = Forestix.colors
    Box(
        modifier
            .heightIn(min = 38.dp)
            .clip(ForestixRadius.control)
            .border(1.dp, colors.divider, ForestixRadius.control)
            .clickableNoRipple(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = Forestix.type.caption.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
            color = colors.textSecondary,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
        )
    }
}
