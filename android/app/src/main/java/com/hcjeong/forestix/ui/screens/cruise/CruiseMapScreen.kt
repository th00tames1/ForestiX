// Cruise-mode map — Android build of the approved v3 mock
// design/forestix-redesign-v3-cruise.html (① cruise map, ② plot peek,
// ④ tree peek, ⑤ project sheet; ③ tally loop rides the shared DBH→Height
// chain via CruiseCapture).
//
// The map IS the cruise: plot RING pins (amber = active/in-progress,
// green = closed, dashed = planned style support) + cruise tree teardrop
// pins are the whole workflow surface. The single primary (+) STATE-MORPHS:
// no active plot → "Start plot" (AR sampling-ring component saving a cruise
// Plot row); active plot → "Add tree · Plot N" straight into the shared
// DBH→Height chain on the next auto tree number. Quick-measure pins NEVER
// appear here and cruise pins never appear on the map home — the two data
// worlds stay separate.

package com.hcjeong.forestix.ui.screens.cruise

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.hcjeong.forestix.LocalAppEnvironment
import com.hcjeong.forestix.AppEnvironment
import com.hcjeong.forestix.basemap.MapMarker
import com.hcjeong.forestix.basemap.MapMarkerShape
import com.hcjeong.forestix.basemap.MapPolygonOverlay
import com.hcjeong.forestix.basemap.MapView
import com.hcjeong.forestix.basemap.rememberMapCameraState
import com.hcjeong.forestix.common.MeasurementFormatter
import com.hcjeong.forestix.common.Units
import com.hcjeong.forestix.data.SettingsSnapshot
import com.hcjeong.forestix.data.cruise.BreastHeightConvention
import com.hcjeong.forestix.data.cruise.CruiseDesign
import com.hcjeong.forestix.data.cruise.Plot
import com.hcjeong.forestix.data.cruise.PlotType
import com.hcjeong.forestix.data.cruise.Project
import com.hcjeong.forestix.data.cruise.SamplingScheme
import com.hcjeong.forestix.data.cruise.Tree
import com.hcjeong.forestix.geo.CoordinateConversions
import com.hcjeong.forestix.inventory.HDModel
import com.hcjeong.forestix.inventory.PlotStats
import com.hcjeong.forestix.inventory.PlotStatsCalculator
import com.hcjeong.forestix.inventory.VolumeEquation
import com.hcjeong.forestix.inventory.VolumeEquationFactory
import com.hcjeong.forestix.positioning.LocationService
import com.hcjeong.forestix.ui.Routes
import com.hcjeong.forestix.ui.clickableNoRipple
import com.hcjeong.forestix.ui.pressableNoRipple
import com.hcjeong.forestix.ui.screens.ClusterLabel
import com.hcjeong.forestix.ui.screens.GpsChip
import com.hcjeong.forestix.ui.screens.PeekActionButton
import com.hcjeong.forestix.ui.screens.PhotoThumb
import com.hcjeong.forestix.ui.screens.RoundChromeButton
import com.hcjeong.forestix.ui.screens.TierChipSoft
import com.hcjeong.forestix.ui.screens.plot.PlotFlowRoutes
import com.hcjeong.forestix.ui.screens.project.CruiseStepRoutes
import com.hcjeong.forestix.ui.screens.project.ProjectFlowRoutes
import com.hcjeong.forestix.ui.softDropShadow
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixRadius
import com.hcjeong.forestix.ui.theme.ForestixSpace
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt
import kotlinx.coroutines.launch

/// Peavy Hall fallback (MapHomeScreen parity) — only when no fix and no
/// cruise data exists yet.
private val DefaultCenter = CoordinateConversions.LatLon(latitude = 44.56417, longitude = -123.28556)

/// Everything the cruise map renders for the CURRENT project, loaded in one
/// pass (fresh on entry + after every mutating action).
private data class CruiseData(
    val project: Project?,
    val plots: List<Plot>,
    val treesByPlot: Map<UUID, List<Tree>>,
) {
    val trees: List<Tree> get() = treesByPlot.values.flatten()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CruiseMapScreen(nav: NavController) {
    val colors = Forestix.colors
    val env = LocalAppEnvironment.current
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val settings by env.settings.state.collectAsStateWithLifecycle()

    // Re-entry housekeeping: a finished/abandoned tally chain must not leak
    // its session or its project calibration into the quick world.
    LaunchedEffect(Unit) { CruiseCapture.end(env) }

    // MARK: - Live GPS (map-home pattern)

    val location = remember { LocationService(context) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results.values.any { it }
        location.onPermissionResult(granted)
        if (granted) location.start()
    }
    LaunchedEffect(Unit) {
        if (LocationService.hasLocationPermission(context)) {
            location.start()
        } else {
            launcher.launch(LocationService.PERMISSIONS)
        }
    }
    DisposableEffect(Unit) { onDispose { location.stop() } }
    val fix by location.latestSnapshot.collectAsStateWithLifecycle()

    // MARK: - Cruise data (current project + plots + trees)

    var refresh by remember { mutableIntStateOf(0) }
    var data by remember { mutableStateOf(CruiseData(null, emptyList(), emptyMap())) }
    var awaitingFirstFix by remember { mutableStateOf(LocationService.lastGlobalFix == null) }
    var mapCenter by remember {
        mutableStateOf(
            LocationService.lastGlobalFix?.let {
                CoordinateConversions.LatLon(latitude = it.latitude, longitude = it.longitude)
            } ?: DefaultCenter,
        )
    }
    val camera = rememberMapCameraState()

    LaunchedEffect(refresh, settings.cruiseProjectId) {
        val project = resolveCurrentProject(env, settings.cruiseProjectId)
        val plots = project?.let { env.plotRepository.listByProject(it.id) } ?: emptyList()
        val trees = plots.associate { it.id to env.treeRepository.listByPlot(it.id) }
        data = CruiseData(project, plots, trees)
        // Stale active-plot guard (deleted / closed / other project).
        val active = settings.cruisePlotId?.let(::uuidOrNull)
        if (active != null && plots.none { it.id == active && it.closedAt == null }) {
            env.settings.setCruisePlotId(null)
        }
        // No fix yet: centre once on the newest cruise geometry instead of
        // the Peavy fallback so a returning cruiser sees their stand.
        if (awaitingFirstFix && fix == null) {
            val target = trees.values.flatten()
                .sortedByDescending { it.createdAt }
                .firstOrNull { it.latitude != null && it.longitude != null }
                ?.let { CoordinateConversions.LatLon(latitude = it.latitude!!, longitude = it.longitude!!) }
                ?: plots.sortedByDescending { it.startedAt }
                    .firstOrNull { it.centerLat != 0.0 || it.centerLon != 0.0 }
                    ?.let { CoordinateConversions.LatLon(latitude = it.centerLat, longitude = it.centerLon) }
            if (target != null) {
                awaitingFirstFix = false
                mapCenter = target
            }
        }
    }
    // First GPS fix pulls the camera home exactly once (map-home parity).
    LaunchedEffect(fix) {
        val snap = fix
        if (awaitingFirstFix && snap != null) {
            awaitingFirstFix = false
            mapCenter = CoordinateConversions.LatLon(
                latitude = snap.latitude, longitude = snap.longitude)
        }
    }

    val project = data.project
    val activePlot = settings.cruisePlotId?.let(::uuidOrNull)
        ?.let { id -> data.plots.firstOrNull { it.id == id && it.closedAt == null } }

    // MARK: - Selection + sheets

    var selectedId by remember { mutableStateOf<String?>(null) }
    var projectSheetOpen by remember { mutableStateOf(false) }
    BackHandler(enabled = selectedId != null) { selectedId = null }

    // MARK: - Actions

    /// (+) with no active plot: ensure a CURRENT project exists (auto-named,
    /// units from settings — name-once philosophy, never a gate), then hand
    /// off to the AR sampling-ring plot creator.
    fun startPlot() {
        scope.launch {
            try {
                val p = project ?: createDefaultProject(env, settings).also {
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
    /// session on the next auto tree number and enter the shared DBH→Height
    /// full-measurement chain (project calibration, GPS + photo on Accept).
    fun addTree(plot: Plot) {
        val p = project ?: return
        scope.launch {
            if (settings.cruisePlotId != plot.id.toString()) {
                env.settings.setCruisePlotId(plot.id.toString())
            }
            val next = (data.treesByPlot[plot.id].orEmpty()
                .maxOfOrNull { it.treeNumber } ?: 0) + 1
            CruiseCapture.begin(env, p, plot, next)
            nav.navigate("${Routes.DBH}?chain=true")
        }
    }

    fun closePlot(plot: Plot) {
        scope.launch {
            try {
                plot.closedAt = System.currentTimeMillis()
                plot.closedBy = project?.owner?.takeIf { it.isNotBlank() }
                env.plotRepository.update(plot)
                if (settings.cruisePlotId == plot.id.toString()) {
                    env.settings.setCruisePlotId(null)
                }
            } catch (_: Exception) {
                plot.closedAt = null
                plot.closedBy = null
            }
            selectedId = null
            refresh++
        }
    }

    // MARK: - Map layer

    Box(Modifier.fillMaxSize().background(colors.canvas)) {
        MapView(
            center = mapCenter,
            modifier = Modifier.fillMaxSize(),
            initialZoom = 16.0,
            overlayURLTemplate = if (settings.overlayEnabled && settings.providerUsageAcknowledged) {
                settings.tileURLTemplate
            } else {
                null
            },
            polygons = activePlot
                ?.takeIf { it.centerLat != 0.0 || it.centerLon != 0.0 }
                ?.let { listOf(plotBoundaryOverlay(it, colors.accent)) }
                ?: emptyList(),
            markers = cruiseMarkers(data, activePlot, selectedId, colors.accent,
                colors.confidenceOk, colors.confidenceWarn, colors.primary),
            onMarkerTap = { selectedId = if (selectedId == it) null else it },
            onMapTap = { selectedId = null },
            youLocation = fix?.let {
                CoordinateConversions.LatLon(latitude = it.latitude, longitude = it.longitude)
            },
            cameraState = camera,
        )

        // MARK: - Top chrome: back ‹ project chip · GPS chip (mock ①)

        Row(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(top = ForestixSpace.xs)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ForestixSpace.xs),
        ) {
            RoundChromeButton(Icons.AutoMirrored.Filled.ArrowBack, "Back to map home") {
                nav.popBackStack()
            }
            ProjectChip(project?.name ?: "New project") { projectSheetOpen = true }
            Spacer(Modifier.weight(1f))
            GpsChip(fix)
        }
        // My-location under the chip column, trailing-aligned (keeps the
        // top row narrow enough for long project names).
        val locateSnap = fix ?: LocationService.lastGlobalFix
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 92.dp, end = 14.dp),
        ) {
            RoundChromeButton(
                Icons.Filled.MyLocation,
                "My location",
                enabled = locateSnap != null,
            ) {
                locateSnap?.let {
                    camera.moveTo(
                        CoordinateConversions.LatLon(
                            latitude = it.latitude, longitude = it.longitude),
                        zoom = max(camera.zoom, 16.0),
                    )
                }
            }
        }

        // MARK: - Bottom: morphing (+) cluster, or a peek card

        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding(),
        ) {
            AnimatedContent(
                targetState = selectedId,
                modifier = Modifier.align(Alignment.BottomCenter),
                contentAlignment = Alignment.BottomCenter,
                transitionSpec = {
                    val spec = tween<Float>(durationMillis = 180, easing = EaseOut)
                    (slideInVertically { it } + fadeIn(spec)) togetherWith
                        (slideOutVertically { it } + fadeOut(spec))
                },
                label = "cruisePeekCluster",
            ) { sel ->
                // Resolve peek data from THIS branch's id, so the exiting
                // card keeps its own content through the transition.
                val peekPlot = sel?.takeIf { it.startsWith("plot-") }
                    ?.removePrefix("plot-")?.let(::uuidOrNull)
                    ?.let { id -> data.plots.firstOrNull { it.id == id } }
                val peekTree = sel?.takeIf { it.startsWith("tree-") }
                    ?.removePrefix("tree-")?.let(::uuidOrNull)
                    ?.let { id -> data.trees.firstOrNull { it.id == id } }
                when {
                    peekPlot != null -> PlotPeekCard(
                        env = env,
                        plot = peekPlot,
                        project = project,
                        trees = data.treesByPlot[peekPlot.id].orEmpty(),
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 20.dp),
                        onAddTree = { addTree(peekPlot) },
                        onClose = { closePlot(peekPlot) },
                        onDetails = {
                            nav.navigate(PlotFlowRoutes.plotSummary(peekPlot.id.toString()))
                        },
                    )

                    peekTree != null -> TreePeekCard(
                        tree = peekTree,
                        plotNumber = data.plots.firstOrNull { it.id == peekTree.plotId }?.plotNumber,
                        unitSystemMetric = project?.units == com.hcjeong.forestix.data.cruise.UnitSystem.METRIC,
                        activity = activity,
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 20.dp),
                        onEdit = {
                            nav.navigate(PlotFlowRoutes.treeDetail(peekTree.id.toString()))
                        },
                    )

                    else -> CruiseActionCluster(
                        activePlot = activePlot,
                        treeCount = activePlot?.let { data.treesByPlot[it.id]?.size } ?: 0,
                        modifier = Modifier.padding(bottom = ForestixSpace.sm),
                        onCapture = {
                            val plot = activePlot
                            if (plot == null) startPlot() else addTree(plot)
                        },
                    )
                }
            }
        }
    }

    // MARK: - Project sheet (mock ⑤)

    if (projectSheetOpen) {
        ProjectSheet(
            env = env,
            currentProject = project,
            settings = settings,
            onDismiss = { projectSheetOpen = false; refresh++ },
            onSwitch = { id ->
                env.settings.setCruiseProjectId(id)
                env.settings.setCruisePlotId(null)
                projectSheetOpen = false
                refresh++
            },
            onNavigate = { route ->
                projectSheetOpen = false
                nav.navigate(route)
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
/// auto-named project, units from app settings, calibration defaults as
/// HomeScreen.create (identity + 5 mm depth noise).
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

/// Plot RING pins (status colour: amber in-progress / green closed) +
/// cruise tree teardrop pins (label = tree number, D/H badges, warn tint).
private fun cruiseMarkers(
    data: CruiseData,
    activePlot: Plot?,
    selectedId: String?,
    accent: Color,
    ok: Color,
    warn: Color,
    primary: Color,
): List<MapMarker> {
    val markers = mutableListOf<MapMarker>()
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

/// Mock `.projchip` — current project name (or "New project") + a dim ▾;
/// the cruise map's title AND switcher in one control.
@Composable
private fun ProjectChip(name: String, onClick: () -> Unit) {
    val colors = Forestix.colors
    val type = Forestix.type
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .pressableNoRipple(onClick = onClick)
            .clip(ForestixRadius.control)
            .background(colors.surface)
            .border(1.dp, colors.divider, ForestixRadius.control)
            .padding(horizontal = 11.dp, vertical = 10.dp),
    ) {
        Text(
            name,
            style = type.bodyBold.copy(fontSize = 12.sp),
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // Long names must never push the GPS chip off-screen.
            modifier = Modifier.widthIn(max = 150.dp),
        )
        Text(
            "▾",
            style = type.caption,
            color = colors.textTertiary,
        )
    }
}

// MARK: - Morphing (+) cluster (mock ① `.actioncluster`) -----------------------

@Composable
private fun CruiseActionCluster(
    activePlot: Plot?,
    treeCount: Int,
    modifier: Modifier = Modifier,
    onCapture: () -> Unit,
) {
    val colors = Forestix.colors
    val type = Forestix.type
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        // Tally pill — LOCKED string "N trees" (mock `.tallypill`).
        if (activePlot != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                modifier = Modifier
                    .padding(bottom = 12.dp)
                    .softDropShadow(Color.Black.copy(alpha = 0.14f), 10.dp, 2.dp, cornerRadius = 999.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(colors.surface)
                    .border(1.dp, colors.divider, RoundedCornerShape(999.dp))
                    .padding(horizontal = 13.dp, vertical = 7.dp),
            ) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(colors.accent))
                Text(
                    "$treeCount trees",
                    style = type.dataSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                    color = colors.textPrimary,
                )
            }
        }
        // 74 dp primary (+) — accent-scoped outline while a plot is active
        // (mock `.capture.scoped`).
        Box(
            Modifier
                .size(82.dp)
                .then(
                    if (activePlot != null) {
                        Modifier.border(1.5.dp, colors.accent, CircleShape)
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(74.dp)
                    .pressableNoRipple(onClick = onCapture)
                    .softDropShadow(Color.Black.copy(alpha = 0.28f), 10.dp, 6.dp)
                    .clip(CircleShape)
                    .background(colors.primary)
                    .border(4.dp, colors.surface, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = if (activePlot == null) "Start plot" else "Add tree",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(30.dp),
                )
            }
        }
        // LOCKED strings: "Start plot" / "Add tree · Plot N".
        ClusterLabel(
            if (activePlot == null) "Start plot" else "Add tree · Plot ${activePlot.plotNumber}",
            gap = 6.dp,
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
    modifier: Modifier = Modifier,
    onAddTree: () -> Unit,
    onClose: () -> Unit,
    onDetails: () -> Unit,
) {
    val colors = Forestix.colors
    val type = Forestix.type
    val shape = RoundedCornerShape(14.dp)
    val closed = plot.closedAt != null

    // Live stats via the SAME inventory engine PlotTallyScreen uses.
    var stats by remember(plot.id, trees.size) { mutableStateOf(PlotStats.empty) }
    LaunchedEffect(plot.id, trees.size) {
        stats = computePlotStats(env, project, plot, trees)
    }
    val nextTree = (trees.maxOfOrNull { it.treeNumber } ?: 0) + 1

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
        // Stats strip (mock `.stats`): TREES / BA / TPA / QMD.
        Row(
            Modifier
                .fillMaxWidth()
                .clip(ForestixRadius.card)
                .border(1.dp, colors.divider, ForestixRadius.card),
        ) {
            StatsCell("TREES", "${stats.liveTreeCount}", null, Modifier.weight(1f))
            StatsDivider()
            StatsCell(
                "BA", String.format(Locale.US, "%.1f", stats.baPerAcreM2),
                "m²/ac", Modifier.weight(1f))
            StatsDivider()
            StatsCell(
                "TPA", String.format(Locale.US, "%.0f", stats.tpa),
                "/ac", Modifier.weight(1f))
            StatsDivider()
            StatsCell(
                "QMD", String.format(Locale.US, "%.1f", stats.qmdCm),
                "cm", Modifier.weight(1f))
        }
        Spacer(Modifier.size(12.dp))
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
private suspend fun computePlotStats(
    env: AppEnvironment,
    project: Project?,
    plot: Plot,
    trees: List<Tree>,
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
        PlotStatsCalculator.compute(
            plot = plot,
            cruiseDesign = design,
            trees = trees,
            species = speciesByCode,
            volumeEquations = volBySpecies,
            hdFits = hdFits,
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
    onEdit: () -> Unit,
) {
    val colors = Forestix.colors
    val type = Forestix.type
    val shape = RoundedCornerShape(14.dp)
    val unitSystem = if (unitSystemMetric) {
        com.hcjeong.forestix.common.UnitSystem.METRIC
    } else {
        com.hcjeong.forestix.common.UnitSystem.IMPERIAL
    }

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
                        ?.let { " · ${it.uppercase(Locale.US)}" } ?: ""),
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
            PhotoThumb(tree.photoPath, if (tree.photoPath != null) 1 else 0, activity)
            Column(Modifier.weight(1f)) {
                TreeMetricRow(
                    label = "DBH",
                    value = MeasurementFormatter.diameter(tree.dbhCm.toDouble(), unitSystem),
                    sigma = tree.dbhSigmaMm?.takeIf { it > 0 }
                        ?.let { MeasurementFormatter.diameterSigma(it.toDouble(), unitSystem) },
                    tierRaw = tree.dbhConfidence.raw,
                )
                if (tree.heightM != null) {
                    HorizontalDivider(color = colors.divider, thickness = 0.5.dp)
                    TreeMetricRow(
                        label = "HEIGHT",
                        value = MeasurementFormatter.height(tree.heightM!!.toDouble(), unitSystem),
                        sigma = tree.heightSigmaM?.takeIf { it > 0 }
                            ?.let { MeasurementFormatter.heightSigma(it.toDouble(), unitSystem) },
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
            DetailChip("SPECIES", tree.speciesCode.takeIf { it.isNotBlank() } ?: "—")
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
        PeekActionButton("Edit details", primary = true, modifier = Modifier.fillMaxWidth()) {
            onEdit()
        }
    }
}

@Composable
private fun TreeMetricRow(label: String, value: String, sigma: String?, tierRaw: String?) {
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
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                value,
                style = type.dataSmall.copy(fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold),
                color = colors.textPrimary,
            )
            if (sigma != null) {
                Text(
                    " $sigma",
                    style = type.dataSmall.copy(fontSize = 11.sp),
                    color = colors.textTertiary,
                )
            }
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
            ) { projectId?.let { onNavigate(CruiseStepRoutes.standSummary(it)) } }
            HorizontalDivider(color = colors.divider, thickness = 0.5.dp)
            SheetChoiceRow(
                Icons.Filled.IosShare,
                "Export",
                "PDF · CSV · GeoJSON · Shapefile",
                enabled = projectId != null,
            ) { projectId?.let { onNavigate(ProjectFlowRoutes.export(it)) } }
            HorizontalDivider(color = colors.divider, thickness = 0.5.dp)
            SheetChoiceRow(
                Icons.Filled.GridOn,
                "Cruise setup",
                "Grid plots · strata · prism/BAF — optional",
                enabled = projectId != null,
                trailingChip = "Advanced",
            ) { projectId?.let { onNavigate(ProjectFlowRoutes.cruiseDesign(it)) } }

            // Relocated hub tools + the Phase A bridge to the old stack.
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
                // Temporary Phase A row: the old TimberCruisingHub stays
                // reachable until Phase B retires the legacy screens.
                ToolButton("Classic view", Modifier.weight(1f)) {
                    onNavigate(Routes.TIMBER_HUB)
                }
            }
        }
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
