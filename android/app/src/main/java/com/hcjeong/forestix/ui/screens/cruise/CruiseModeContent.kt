// Cruise MODE of the map home — the v3 cruise map (approved mock
// design/forestix-redesign-v3-cruise.html: ① cruise map, ② plot peek,
// ④ tree peek, ⑤ project sheet, ⑥ cruise setup, ⑦ planned plot + guide;
// ③ tally loop rides the shared DBH screen as a
// DIAMETER LOOP via CruiseCapture — heights on demand from the tree peek
// / plot sample heights sheet) absorbed into MapHomeScreen as a TOGGLED MODE (v3.1):
// one map, two modes, no navigation between them. MapHomeScreen owns the
// single MapView/camera and, while `tc.mapMode == "cruise"`, feeds it this
// file's markers/overlays and hosts this file's chrome, peeks and sheets.
//
// The map IS the cruise: plot RING pins (amber = active/in-progress,
// green = closed, HOLLOW DASHED = planned) + cruise tree teardrop pins
// are the whole workflow surface. The single primary (+) STATE-MORPHS:
// no active plot and no plans → "Start plot" (AR sampling-ring component
// saving a cruise Plot row); plans waiting → "Go to Plot N", which glides
// the camera onto the lowest-numbered unvisited plan, draws the guide to
// it and raises its card; active plot → "Add tree · Plot N" straight into
// the shared DBH→Height chain on the next auto tree number. A planned pin
// peeks into the PAIR: "Start plot now" (one tap, the fix that is live at
// that instant, plot open and measurable immediately — field report 17)
// and "Navigate" (dashed you-dot→plot guide line + live distance chip —
// the map is the nav). The 60 s GPS-averaging sheet is no longer offered
// there: field feedback found the window worse than not having it.
// Quick-measure pins NEVER appear in cruise mode and cruise pins never
// appear in measure mode — the two data worlds stay separate.

package com.hcjeong.forestix.ui.screens.cruise

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
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridOn
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.hcjeong.forestix.AppEnvironment
import com.hcjeong.forestix.LocalAppEnvironment
import com.hcjeong.forestix.ar.ArSessionHub
import com.hcjeong.forestix.basemap.MapCameraState
import com.hcjeong.forestix.basemap.MapMarker
import com.hcjeong.forestix.basemap.MapMarkerShape
import com.hcjeong.forestix.basemap.MapPolylineOverlay
import com.hcjeong.forestix.common.AreaUnit
import com.hcjeong.forestix.common.MeasurementFormatter
import com.hcjeong.forestix.common.RegionalSpecies
import com.hcjeong.forestix.common.UnitSystem
import com.hcjeong.forestix.common.areaUnit
import com.hcjeong.forestix.data.SettingsSnapshot
import com.hcjeong.forestix.data.cruise.BreastHeightConvention
import com.hcjeong.forestix.data.cruise.CruiseDesign
import com.hcjeong.forestix.data.cruise.HeightSubsampleRule
import com.hcjeong.forestix.data.cruise.PlannedPlot
import com.hcjeong.forestix.data.cruise.Plot
import com.hcjeong.forestix.data.cruise.PlotType
import com.hcjeong.forestix.data.cruise.PositionSource
import com.hcjeong.forestix.data.cruise.Project
import com.hcjeong.forestix.data.cruise.SamplingScheme
import com.hcjeong.forestix.data.cruise.Stratum
import com.hcjeong.forestix.data.cruise.Tree
import com.hcjeong.forestix.data.cruise.TreeLabel
import com.hcjeong.forestix.data.cruise.displayTitle
import com.hcjeong.forestix.data.cruise.hasCentre
import com.hcjeong.forestix.export.FullCruiseExporter
import com.hcjeong.forestix.geo.CoordinateConversions
import com.hcjeong.forestix.inventory.HDModel
import com.hcjeong.forestix.inventory.HeightSubsample
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
import com.hcjeong.forestix.ui.screens.DefaultZoom
import com.hcjeong.forestix.ui.screens.ExportViewModel
import com.hcjeong.forestix.ui.screens.FieldLogWords
import com.hcjeong.forestix.ui.screens.Haptics
import com.hcjeong.forestix.ui.screens.PeekActionButton
import com.hcjeong.forestix.ui.screens.PhotoThumb
import com.hcjeong.forestix.ui.screens.PhotoViewerDialog
import com.hcjeong.forestix.ui.screens.PlotDetailSheet
import com.hcjeong.forestix.ui.screens.SideCircleButton
import com.hcjeong.forestix.ui.screens.TierChipSoft
import com.hcjeong.forestix.ui.screens.tree.TierExplainerKind
import com.hcjeong.forestix.ui.screens.cruiseTreePhotoPages
import com.hcjeong.forestix.ui.screens.exportMimeFor
import com.hcjeong.forestix.ui.screens.plot.PlotFlowRoutes
import com.hcjeong.forestix.ui.screens.project.ProjectFlowRoutes
import com.hcjeong.forestix.ui.screens.zipFolderForShare
import com.hcjeong.forestix.ui.shareFile
import com.hcjeong.forestix.ui.softDropShadow
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixColors
import com.hcjeong.forestix.ui.theme.ForestixBorderedButton
import com.hcjeong.forestix.ui.theme.ForestixProminentButton
import com.hcjeong.forestix.ui.theme.ForestixRadius
import com.hcjeong.forestix.ui.theme.ForestixSpace
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
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
    /// Cruise setup (⑥) is up. Only ever raised with a project already in
    /// `data` — the sheet cannot draw without one, and a flag raised against
    /// no project is not a sheet that opens late, it is a tap that did
    /// nothing and then surfaced a sheet at some unrelated later moment.
    /// `CruiseModeSheets` drops it if a project goes away underneath it.
    var cruiseSetupOpen by mutableStateOf(false)
    /// Planned plot the dashed guide line + distance chip point at (mock ⑦).
    var navTargetId by mutableStateOf<UUID?>(null)
    /// Plot whose sample heights sheet is open ("Sample heights · N of M", B).
    var heightsSheetFor by mutableStateOf<UUID?>(null)
    /// Plot whose SITE DESCRIPTION sheet is open (plot peek "Add details").
    var detailPlotFor by mutableStateOf<UUID?>(null)
    /// Plot whose map-overlay menu (Edit / Remove) is open — raised by
    /// tapping the drawn plot's boundary on the map (M2).
    var plotMenuFor by mutableStateOf<UUID?>(null)
    /// Why a plot centre could not be saved. Non-null raises a dialog — a
    /// refusal the cruiser has to see, because the alternative was writing a
    /// plot centre the app had guessed (iOS `plotSaveRefusal`).
    var plotSaveRefusal by mutableStateOf<String?>(null)
    /// Why a PLAN could not be written. Its own channel rather than
    /// `plotSaveRefusal`, whose dialog is titled "Can't save the plot centre"
    /// — a plan has no centre, and a refusal that names the wrong thing sends
    /// the cruiser looking for a GPS problem they do not have.
    var planSaveRefusal by mutableStateOf<String?>(null)
    /// Why Cruise setup could not be OPENED. A third channel because the tap
    /// that raises it ("Cruise this area") arrives in either mode, so its
    /// dialog has to be hosted outside the cruise-only sheets — and because
    /// neither of the two above names a cruise that never got as far as a
    /// plot or a plan.
    var cruiseSetupRefusal by mutableStateOf<String?>(null)
    /// A "Start plot now" write is in flight. The storage write is a
    /// coroutine and the peek stays on screen until it lands, so without
    /// this a second tap inside that window runs the conversion twice: two
    /// Plot rows with the SAME plotNumber and the same plannedPlotId, and
    /// the planned pin marked visited twice.
    var startingPlanned by mutableStateOf(false)
    /// A "Cruise this area" open is in flight. Same hazard as
    /// [startingPlanned] and the same cure: resolving or creating the project
    /// is a storage round trip, the area callout stays on screen until it
    /// lands, and without this a second tap inside that window has both
    /// coroutines find no project and both create one — two projects named
    /// "Project 1", with the cruise laid into whichever `setCruiseProjectId`
    /// wrote last. iOS has no such window: there,
    /// `autoCreateProject()` and clearing the selection are one synchronous
    /// main-actor turn (`MapHomeScreen+Area.swift`).
    var openingAreaSetup by mutableStateOf(false)

    // MARK: - Planning on the map (press and hold)

    /// Coordinate a press-and-hold landed on, while its menu is up. This is
    /// the ONE door into planning: the (+)'s "Pick on the map" arms the
    /// prompt below and then waits for this same gesture, so there is a
    /// single flow to learn and a single place a drawn coordinate is born.
    var planMenuAt by mutableStateOf<CoordinateConversions.LatLon?>(null)
    /// The cruiser asked to plan on the map and has not pressed yet — the
    /// map shows the instruction banner until they do or cancel.
    var awaitingPlanPress by mutableStateOf(false)
    /// The planned plot a "Move plan" is in flight for. The next long press
    /// MOVES it instead of raising the menu; a plan drawn in the wrong spot
    /// is the ordinary case, not an exception.
    var movingPlannedId by mutableStateOf<UUID?>(null)
    /// Planned plot the delete confirmation is up for.
    var deletePlannedCandidate by mutableStateOf<PlannedPlot?>(null)
    /// The (+)'s two doors into a plot — start on the fix that exists now, or
    /// plan the location on the map first.
    var startPlotChoiceOpen by mutableStateOf(false)
    /// Drop a PlannedPlot where the cruiser pressed — wired by
    /// CruiseModeEffects (it needs the environment + a coroutine scope).
    var planPlot: (CoordinateConversions.LatLon) -> Unit = {}
    /// Move an existing plan to where the cruiser just pressed.
    var movePlanned: (PlannedPlot, CoordinateConversions.LatLon) -> Unit = { _, _ -> }
    /// Delete a plan (never a measurement — a planned plot holds no trees).
    var deletePlanned: (PlannedPlot) -> Unit = {}

    /// Where every hand-drawn coordinate enters the app — in BOTH modes; the
    /// menu it raises is what narrows (see MapPlanCallout).
    ///
    /// A press with a MOVE armed relocates that plan and nothing else — the
    /// cruiser asked one question ("where should this be instead?") and gets
    /// no menu in the middle of answering it. That branch is cruise-only: a
    /// planned plot exists only in the cruise world, and an arm that outlived
    /// a mode flip must not relocate a plan from a map no longer showing it.
    fun onMapLongPress(
        coordinate: CoordinateConversions.LatLon,
        inCruiseMode: Boolean,
    ) {
        val moving = if (inCruiseMode) movingPlannedId else null
        if (moving != null) {
            val planned = data.plannedPlots.firstOrNull { it.id == moving }
            movingPlannedId = null
            if (planned != null) movePlanned(planned, coordinate)
            return
        }
        selectedId = null
        planMenuAt = coordinate
    }

    /// Actions — wired by CruiseModeEffects (they need nav/scope/settings).
    var startPlot: () -> Unit = {}
    var addTree: (Plot) -> Unit = {}
    var closePlot: (Plot) -> Unit = {}
    /// Planned-peek "Start plot now": open the planned plot on the fix that
    /// is live right now, with no averaging window in the way (field 17).
    var startPlannedNow: (PlannedPlot) -> Unit = {}
    /// Toggle a planned plot's `skipped` flag (planned-peek "Skip this plot"
    /// / "Unskip this plot"). A skipped plot is inaccessible (cliff/water/
    /// private land) so the (+) passes over it while it still renders.
    var skipPlanned: (PlannedPlot) -> Unit = {}
    /// Hard delete a plot + cascade its trees (plot peek "Delete plot").
    var deletePlot: (Plot) -> Unit = {}
    /// Hard delete a tree + its photo (tree peek "Delete tree").
    var deleteTree: (Tree) -> Unit = {}
    /// Take the plot off the map (map-overlay menu "Remove plot"). Clears
    /// the plot's recorded CENTRE only — the plot row, its radius and every
    /// tree measured in it survive.
    var removePlot: (Plot) -> Unit = {}
    /// Height on demand (tree peek "Measure height" / sample heights sheet).
    var measureHeight: (Tree) -> Unit = {}

    val project: Project? get() = data.project

    /// The ACTIVE (open) plot the (+) scopes "Add tree · Plot N" to.
    fun activePlot(settings: SettingsSnapshot): Plot? =
        settings.cruisePlotId?.let(::uuidOrNull)
            ?.let { id -> data.plots.firstOrNull { it.id == id && it.closedAt == null } }

    /// Which units cruise surfaces render in: the PROJECT's, because a
    /// cruise is run in the units it was set up in. Falls back to the app
    /// setting when there is no project yet (same rule the peek cards use).
    fun unitSystem(settings: SettingsSnapshot): com.hcjeong.forestix.common.UnitSystem =
        when (project?.units) {
            com.hcjeong.forestix.data.cruise.UnitSystem.METRIC ->
                com.hcjeong.forestix.common.UnitSystem.METRIC
            com.hcjeong.forestix.data.cruise.UnitSystem.IMPERIAL ->
                com.hcjeong.forestix.common.UnitSystem.IMPERIAL
            null -> settings.unitSystem
        }

    /// A tap on the plot drawn on the map (MapView's onOverlayTap) — raises
    /// that plot's Edit / Remove menu. Ignores an id that no longer names a
    /// plot in this project.
    fun openPlotMenu(plotId: String) {
        val id = uuidOrNull(plotId) ?: return
        if (data.plots.none { it.id == id }) return
        selectedId = null
        plotMenuFor = id
    }

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
        // FIELD REPORT 7 — and the same guard for the AR RING, which had no
        // such rule at all: nothing dropped it when the cruiser closed a plot
        // and walked to the next one, so the plot-2 setup screen opened
        // already "placed", on plot 1's ring, tens of metres away.
        //
        // Once the ring is linked to a cruise plot it IS that plot's
        // boundary, and it lives exactly as long as the plot is one the
        // cruiser can still be measuring — OPEN, and in the current project.
        // Closed, deleted, or left behind by a project switch ⇒ dropped,
        // anchor and all.
        //
        // This effect is the funnel EVERY plot mutation already goes through
        // (`state.refresh++`), and returning from a pushed destination
        // recomposes cruise mode and re-runs it — so the plot summary
        // screen's Close / Reopen / Delete are covered without a second copy
        // of the rule there. Patching the two paths the report named would
        // have left that screen still leaking the ring.
        //
        // A ring linked to NOTHING is untouched: it is a quick-measure
        // sampling ring, or one just placed and not yet saved, and no cruise
        // plot's lifetime governs it. `ArSessionHub.armPlotSetup` covers that
        // half at the setup-screen doors.
        //
        // An unreadable plot list lands here as an empty one and drops the
        // ring. That is the right way to be wrong: a boundary we can no
        // longer prove belongs to an open plot is exactly the boundary the
        // cruiser must not be shown.
        val linkedRing = ArSessionHub.linkedCruisePlotId
        if (linkedRing != null &&
            plots.none { it.id == linkedRing && it.closedAt == null }
        ) {
            ArSessionHub.clearPlot()
        }
        if (awaitingFirstFix && fix == null) {
            val target = trees.values.flatten()
                .sortedByDescending { it.createdAt }
                .firstOrNull { it.latitude != null && it.longitude != null }
                ?.let { CoordinateConversions.LatLon(latitude = it.latitude!!, longitude = it.longitude!!) }
                ?: plots.sortedByDescending { it.startedAt }
                    .firstOrNull { it.hasCentre }
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
    /// "Start here" — the first of the (+)'s two doors, and the behaviour
    /// that button has always had. The second door ("Pick on the map") arms
    /// the press-and-hold instead and never reaches this.
    state.startPlot = {
        scope.launch {
            try {
                val p = state.project ?: createDefaultProject(env, settings).also {
                    env.settings.setCruiseProjectId(it.id.toString())
                }
                // A fresh plot: whatever ring is up belongs to somewhere else
                // (field report 7). Without this the setup screen opens
                // already "placed" and there is no crosshair to aim with.
                ArSessionHub.armPlotSetup(null)
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
            // The tally is switching to THIS plot, so a ring belonging to any
            // other one stops being the boundary being measured — and the DBH
            // / Height screens draw whatever ring is placed as their subdued
            // overlay. An "Add tree" from a plot peek can target an OLDER open
            // plot than the last-placed ring, which is exactly the case that
            // survives the open-plot reconcile above (both plots are open).
            ArSessionHub.dropPlotIfLinkedElsewhere(plot.id)
            if (settings.cruisePlotId != plot.id.toString()) {
                env.settings.setCruisePlotId(plot.id.toString())
            }
            val next = (state.data.treesByPlot[plot.id].orEmpty()
                .maxOfOrNull { it.treeNumber } ?: 0) + 1
            // Picked up from the plot the loop is entering, not carried over
            // from whatever was tallied last: re-entering Plot 2 after naming
            // trees in Plot 1 must continue PLOT 2's series. Null when this
            // plot has never been named, and the loop stays nameless until
            // the tally pill is tapped.
            CruiseCapture.begin(
                env, p, plot, next, CruiseCapture.nextTreeName(env, plot.id))
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

    /// FIELD REPORT 17 — planned peek "Start plot now": open a PLANNED plot
    /// on the fix that is live right now, with no averaging window in the
    /// way. Same conversion the averaging sheet's "Save centre" runs (so the
    /// planned pin is marked visited and the plot opens exactly as it always
    /// did); the only difference is in the position stamp the result carries,
    /// which says GPS_SINGLE / one sample rather than borrowing the averaged
    /// label.
    ///
    /// REFUSES rather than guesses. With no fix the app still believes,
    /// there is nothing to put in centerLat/centerLon — and a plot centre is
    /// the anchor for every tree tallied into the plot, so the wrong one is
    /// worse than none. Same refusal, word for word, as the AR "Start plot"
    /// path.
    state.startPlannedNow = startNow@{ planned ->
        // ONE write per tap. The conversion below creates a Plot row and
        // marks the planned pin visited, and it runs in a coroutine while
        // the peek is still on screen and still tappable — so the guard has
        // to be here, at the only door into the write, not on the button.
        if (state.startingPlanned) return@startNow
        val p = state.project ?: return@startNow
        val result = singleFixCentre(fix, System.currentTimeMillis())
        if (result == null) {
            state.plotSaveRefusal = "No GPS fix — the plot centre would be saved " +
                "in the wrong place. Step out for sky and try again."
            return@startNow
        }
        state.startingPlanned = true
        scope.launch {
            try {
                val plot = convertPlannedToActivePlot(env, p, planned, result)
                if (state.navTargetId == planned.id) state.navTargetId = null
                state.selectedId = "plot-${plot.id}"
            } catch (e: Exception) {
                state.plotSaveRefusal = "Storage error: ${e.message ?: e}. " +
                    "The centre was not saved — try again."
            }
            state.startingPlanned = false
            state.refresh++
        }
    }

    /// Press-and-hold "Plan a plot here": drop a PlannedPlot where the
    /// cruiser pressed.
    ///
    /// The coordinate is stamped MANUAL and stays in plannedLat/plannedLon.
    /// It is an INTENTION: no fix is read here, nothing is measured, and the
    /// plot this becomes will take its centre from the arrival fix instead
    /// (`startPlannedNow`). Keeping the drawn point and the arrival fix apart
    /// is the whole reason both are stored.
    ///
    /// The plot NUMBER is one past the highest already spoken for, planned
    /// OR real: a planned plot carries its number into the Plot it becomes
    /// (`convertPlannedToActivePlot`), so numbering off the planned list
    /// alone would collide with an ad-hoc plot started earlier in the day —
    /// two "Plot 4"s in one project, indistinguishable in every export.
    state.planPlot = { coordinate ->
        scope.launch {
            try {
                val p = state.project ?: createDefaultProject(env, settings).also {
                    env.settings.setCruiseProjectId(it.id.toString())
                }
                val highest = maxOf(
                    state.data.plannedPlots.maxOfOrNull { it.plotNumber } ?: 0,
                    state.data.plots.maxOfOrNull { it.plotNumber } ?: 0,
                )
                val planned = PlannedPlot(
                    id = UUID.randomUUID(),
                    projectId = p.id,
                    // No stratum: a finger on a map is not inside a stratum
                    // the app knows about, and guessing one from a boundary
                    // would file this plot under a stratum the cruiser never
                    // chose.
                    stratumId = null,
                    plotNumber = highest + 1,
                    plannedLat = coordinate.latitude,
                    plannedLon = coordinate.longitude,
                    visited = false,
                    skipped = false,
                    plannedSource = PositionSource.MANUAL,
                )
                env.plannedPlotRepository.create(planned)
                // Open its peek straight away: the plan is only useful once
                // the cruiser can navigate to it, and that is one tap inside.
                state.selectedId = "planned-${planned.id}"
            } catch (e: Exception) {
                state.planSaveRefusal = "Storage error: ${e.message ?: e}. " +
                    "The planned plot was not saved — try again."
            }
            state.refresh++
        }
    }

    /// Planned peek "Move plan" → the next press-and-hold. Re-stamps
    /// MANUAL whatever laid the point down first: after this the coordinate
    /// IS a drawn one, and a generator's provenance left in place would be a
    /// claim about a point the generator never produced.
    state.movePlanned = { planned, coordinate ->
        scope.launch {
            val lat = planned.plannedLat
            val lon = planned.plannedLon
            val source = planned.plannedSource
            try {
                planned.plannedLat = coordinate.latitude
                planned.plannedLon = coordinate.longitude
                planned.plannedSource = PositionSource.MANUAL
                env.plannedPlotRepository.update(planned)
                state.selectedId = "planned-${planned.id}"
            } catch (e: Exception) {
                // Storage error — put the plan back where the map still
                // shows it, then say so.
                planned.plannedLat = lat
                planned.plannedLon = lon
                planned.plannedSource = source
                state.planSaveRefusal = "Storage error: ${e.message ?: e}. " +
                    "The planned plot was not moved — try again."
            }
            state.refresh++
        }
    }

    /// Planned peek "Delete plan": remove the PlannedPlot row. Nothing
    /// measured can be lost — a planned plot holds no trees and no centre,
    /// and the moment it becomes a real plot it stops being reachable from
    /// here (the pin turns solid).
    state.deletePlanned = { planned ->
        scope.launch {
            try {
                env.plannedPlotRepository.delete(planned.id)
                if (state.navTargetId == planned.id) state.navTargetId = null
                if (state.movingPlannedId == planned.id) state.movingPlannedId = null
                state.selectedId = null
            } catch (e: Exception) {
                state.planSaveRefusal = "Storage error: ${e.message ?: e}. " +
                    "The planned plot was not deleted — try again."
            }
            state.refresh++
        }
    }

    /// Planned peek "Skip this plot" / "Unskip this plot": flip the planned
    /// plot's `skipped` flag and persist through the SAME
    /// plannedPlotRepository.update path the plot conversion uses to set
    /// visited=true. A newly-skipped plot drops out of the (+)'s running
    /// order, so any dashed guide line armed at it is cleared. Reverts the
    /// in-memory flag on a storage error, mirroring closePlot.
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
            // Dismiss on SKIP (the plot is done with for now), stay up on
            // UNSKIP — the cruiser who just un-skipped a plot is about to
            // start it, and the peek they need is the one already open.
            // Same rule as iOS `setPlannedSkipped`.
            if (planned.skipped) state.selectedId = null
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

    /// Map-overlay menu "Remove plot": take the plot OFF THE MAP and change
    /// nothing else.
    ///
    /// It clears exactly ONE thing: the recorded CENTRE, back to the (0,0)
    /// sentinel every cruise surface already reads as "no centre". The Plot
    /// row, its number, its radius/area and every Tree row measured in it
    /// are untouched, so the tally still exports, still rolls up, and the
    /// plot is one "Edit plot" away from having a centre again (re-centring
    /// rewrites the whole position stamp in one go). Deleting the plot row
    /// instead would leave those trees with no plot to belong to — invisible
    /// on every screen and in every export — which is precisely the loss
    /// this control must never cause.
    ///
    /// The AR ring that mirrors this plot on the scan screens is dropped
    /// too when it is linked to it: a ring standing in for a centre that no
    /// longer exists would be a lie about where the plot is.
    state.removePlot = { plot ->
        scope.launch {
            val lat = plot.centerLat
            val lon = plot.centerLon
            try {
                plot.centerLat = 0.0
                plot.centerLon = 0.0
                env.plotRepository.update(plot)
                if (ArSessionHub.linkedCruisePlotId == plot.id) ArSessionHub.clearPlot()
            } catch (_: Exception) {
                // Storage error — put the centre back so the map keeps
                // telling the truth; the next tap retries.
                plot.centerLat = lat
                plot.centerLon = lon
            }
            state.plotMenuFor = null
            state.selectedId = null
            state.refresh++
        }
    }

    /// Tree peek "Delete tree": hard delete the row + its auto-captured photo
    /// (same MeasurePhotoStore the quick world uses). Peek dismisses + reloads.
    state.deleteTree = { tree ->
        scope.launch {
            try {
                // Plain Context: `as? Activity` yields null wherever this
                // screen is hosted inside a dialog window, and a null there
                // silently skipped the deletion and orphaned the file.
                tree.photoPath?.let { name -> MeasurePhotoStore.delete(context, name) }
                env.treeRepository.hardDelete(tree.id)
            } catch (_: Exception) {
                // Storage error — leave the tree in place; the next tap retries.
            }
            state.selectedId = null
            state.refresh++
        }
    }

    /// Per-tree height on demand (tree peek "Measure height" / the plot
    /// sample heights sheet): arm a HEIGHT-ONLY cruise session on the EXISTING
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

// The active plot's boundary is no longer a plain polygon ring: it is the
// map's own PLOT OVERLAY (cruiseModePlotOverlay in PlotMapOverlay.kt), which
// draws the boundary plus range rings, compass badges, the centre mark and
// the inside/outside state at true ground scale.

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
    /// The chip states a distance the cruiser is about to walk, so it is
    /// rendered in the unit they pace in.
    unitSystem: UnitSystem,
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
            navDistanceLabel(navDistanceM, unitSystem),
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
            navDistanceLabel(navDistanceM, unitSystem),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 72.dp),
        )
    }
}

/// The armed-gesture banner: the map is waiting for a press and says so,
/// because an armed gesture with no visible state is a mode the cruiser
/// cannot see they are in. Names the plot being moved, since a move and a
/// fresh plan look identical once the finger is down. Strings byte-identical
/// to iOS `mapPlanPromptText`.
///
/// `topPaddingDp` comes from the caller because this rides UNDER the plot
/// status banner when there is one — two pills stacked, not one over the
/// other.
@Composable
internal fun BoxScope.CruisePlanPromptBanner(
    state: CruiseModeState,
    topPaddingDp: Dp,
) {
    val colors = Forestix.colors
    val type = Forestix.type
    val moving = state.movingPlannedId
        ?.let { id -> state.data.plannedPlots.firstOrNull { it.id == id } }
    val text = if (moving != null) {
        "Press and hold the map to move Plot ${moving.plotNumber}."
    } else {
        "Press and hold the map to plan a plot."
    }
    Row(
        Modifier
            .align(Alignment.TopCenter)
            .statusBarsPadding()
            .padding(top = topPaddingDp, start = ForestixSpace.md, end = ForestixSpace.md)
            .widthIn(max = 340.dp)
            .softDropShadow(Color.Black.copy(alpha = 0.18f), 8.dp, 2.dp, cornerRadius = 10.dp)
            .clip(ForestixRadius.control)
            .background(colors.surface)
            .border(1.dp, colors.divider, ForestixRadius.control)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ForestixSpace.xs),
    ) {
        Text(
            text,
            style = type.bodyBold.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
            color = colors.textPrimary,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.weight(1f))
        Box(
            Modifier.pressableNoRipple {
                state.awaitingPlanPress = false
                state.movingPlannedId = null
            },
        ) {
            Text(
                "Cancel",
                style = type.bodyBold.copy(fontSize = 12.sp),
                color = colors.primary,
            )
        }
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
    /// The shared map camera — the (+) MOVES it when plans are waiting, so
    /// this content can no longer be written without one.
    camera: MapCameraState,
    onToggleMode: () -> Unit,
) {
    val env = LocalAppEnvironment.current
    val settings by env.settings.state.collectAsStateWithLifecycle()
    val activePlot = state.activePlot(settings)
    val scope = rememberCoroutineScope()

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
                unitSystem = settings.unitSystem,
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 20.dp),
                starting = state.startingPlanned,
                onStartNow = { state.startPlannedNow(peekPlanned) },
                onMovePlan = {
                    // Same press-and-hold that made the plan — the card gets
                    // out of the way so the map underneath is pressable.
                    state.selectedId = null
                    state.movingPlannedId = peekPlanned.id
                },
                onDelete = { state.deletePlannedCandidate = peekPlanned },
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
                onAddDetails = { state.detailPlotFor = peekPlot.id },
                onDetails = {
                    nav.navigate(PlotFlowRoutes.plotSummary(peekPlot.id.toString()))
                },
            )

            peekTree != null -> TreePeekCard(
                tree = peekTree,
                plotNumber = state.data.plots.firstOrNull { it.id == peekTree.plotId }?.plotNumber,
                unitSystemMetric = state.project?.units == com.hcjeong.forestix.data.cruise.UnitSystem.METRIC,
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
                // (+) WITH PLANS WAITING: it takes the cruiser to the plot
                // they are ALREADY WALKING TO — the one the dashed guide is
                // armed at — and only with no guide up to the
                // LOWEST-NUMBERED unvisited plan. Camera, guide line and that
                // plot's own card. Skipped plots are documented inaccessible,
                // so the running order passes over them even though they
                // still render on the map.
                //
                // The plot order answers "which plot?" only until the cruiser
                // starts walking. Once a guide is drawn, re-aiming the button
                // at a different plan would fling the map off the walk in
                // progress, which is the opposite of what pressing it means.
                val targetPlanned = state.navTarget()
                    ?: state.data.plannedPlots
                        .filter { !it.skipped }
                        .minByOrNull { it.plotNumber }
                CruiseActionCluster(
                    activePlot = activePlot,
                    targetPlanned = targetPlanned,
                    treeCount = activePlot?.let { state.data.treesByPlot[it.id]?.size } ?: 0,
                    projectName = state.project?.name ?: "New project",
                    modifier = Modifier.padding(bottom = ForestixSpace.sm),
                    onToggleMode = onToggleMode,
                    onCapture = {
                        val plot = state.activePlot(settings)
                        when {
                            plot != null -> state.addTree(plot)
                            // The SAME target the label names, so the button
                            // can never promise a plot it will not go to.
                            targetPlanned != null ->
                                goToPlannedPlot(state, targetPlanned, camera, fix, scope)
                            // TWO DOORS, because the cruiser sometimes plans
                            // and sometimes just arrives. Neither is a new
                            // path: "Start here" is `startPlot()` below, the
                            // AR sampling-ring screen this button has always
                            // opened, and "Pick on the map" arms the
                            // press-and-hold that planning already uses.
                            // Adding a third way to plan is what this avoids.
                            else -> state.startPlotChoiceOpen = true
                        }
                    },
                    onProject = { state.projectSheetOpen = true },
                )
            }
        }
    }
}

/// How far a glide is still worth watching, measured in screenfuls of the
/// ground the map is showing. Two and a half screens is a move the eye can
/// follow; twenty is a featureless slide over country nobody asked to see,
/// and it ends on the same question it started with. iOS uses the same 2.5.
private const val FLIGHT_SCREEN_WIDTHS = 2.5

/// THE (+) WITH PLANS WAITING — take the cruiser to the next plot.
///
/// The button used to open the averaging sheet on a plan the cruiser could
/// not see, so it answered "record a centre" for a question that was really
/// "which plot, and where is it?". It now does the three things they were
/// doing by hand: glides the map onto the plot, draws the navigation guide to
/// it, and raises that plot's own card. Nothing here is a new flow — the
/// guide is `navTargetId` exactly as Navigate sets it, and the card is the
/// pin's own selection. Port of iOS `goToPlannedPlot`.
///
/// Tapped again while the first glide is still running, the second tap
/// REPLACES the first flight — `MapCameraState.flyTo` owns the job — so the
/// camera is never being interpolated towards two plots at once.
private fun goToPlannedPlot(
    state: CruiseModeState,
    planned: PlannedPlot,
    camera: MapCameraState,
    fix: CLLocationSnapshot?,
    scope: CoroutineScope,
) {
    val target = CoordinateConversions.LatLon(
        latitude = planned.plannedLat, longitude = planned.plannedLon)
    // Guide and card FIRST: they are what the cruiser reads, and waiting for
    // the camera to land would leave the button looking dead for the length
    // of the flight.
    state.navTargetId = planned.id
    state.selectedId = "planned-${planned.id}"

    val from = camera.center
    val travelM = if (from == null) {
        0.0
    } else {
        GeoMath.distanceM(from.latitude, from.longitude, target.latitude, target.longitude)
    }
    val screenM = visibleSpanM(camera)
    val tooFar = screenM != null && travelM > screenM * FLIGHT_SCREEN_WIDTHS
    val usableFix = fix ?: LocationService.lastGlobalFix

    if (!tooFar) {
        // Same zoom rule as my-location, the app's other "take me there"
        // control: close in if the map was pulled back, and never pull a
        // cruiser out of a closer look they chose.
        camera.flyTo(scope, target, max(camera.zoom, DefaultZoom))
        return
    }
    if (usableFix == null) {
        // Too far to watch and no position to fit against. Cut straight there
        // rather than glide: a long slide over ground the cruiser is not
        // standing on tells them nothing they can use.
        camera.moveTo(target, max(camera.zoom, DefaultZoom))
        return
    }
    // BOTH ENDS ON SCREEN. The separation is used as the span across the
    // SHORT side, so the pair fits whatever bearing the plot lies on.
    val separationM = GeoMath.distanceM(
        usableFix.latitude, usableFix.longitude, target.latitude, target.longitude)
    val zoom = spanFramingZoom(camera, separationM) ?: camera.zoom
    camera.flyTo(
        scope,
        CoordinateConversions.LatLon(
            latitude = (usableFix.latitude + target.latitude) / 2,
            longitude = (usableFix.longitude + target.longitude) / 2,
        ),
        zoom,
    )
}

/// THE AREA CALLOUT'S "Cruise this area" — open Cruise setup with this area
/// already chosen. Port of iOS `openCruiseSetup(forArea:)`.
///
/// The tap arrives from EITHER mode, and that is what made the Android side
/// of it a silent no-op: the cruise snapshot is only loaded while cruise
/// mode is on, so pressed from measure mode `state.project` was still null,
/// the setup sheet's own gate (`cruiseSetupOpen && project != null`) refused
/// to draw, and the raised flag then sat there as a latch — surfacing the
/// sheet at whatever unrelated later moment a project first appeared.
///
/// So the project is settled HERE, before anything is raised, the way iOS
/// settles it: resolved, or created when the cruiser has not made one yet —
/// a cruise needs a project and they did not come here to be told so. The
/// resolved project is seeded into the snapshot as well, because the sheet
/// reads the SNAPSHOT rather than this value, and a sheet that only appears
/// once the mode flip's reload happens to land is the same silence again.
/// The reload that follows replaces the seed with the full snapshot.
internal suspend fun openCruiseSetupForArea(
    env: AppEnvironment,
    settings: SettingsSnapshot,
    areaState: MapAreaState,
    state: CruiseModeState,
    stratum: Stratum,
    /// Flips the map into cruise mode — the pins this sheet is about to
    /// generate are cruise content, and generating them onto a map that does
    /// not draw them would look like nothing happened.
    enterCruiseMode: () -> Unit,
) {
    if (state.openingAreaSetup) return
    state.openingAreaSetup = true
    try {
        openCruiseSetupForAreaInner(env, settings, areaState, state, stratum, enterCruiseMode)
    } finally {
        state.openingAreaSetup = false
    }
}

private suspend fun openCruiseSetupForAreaInner(
    env: AppEnvironment,
    settings: SettingsSnapshot,
    areaState: MapAreaState,
    state: CruiseModeState,
    stratum: Stratum,
    enterCruiseMode: () -> Unit,
) {
    val project = try {
        resolveCurrentProject(env, settings.cruiseProjectId)
            ?: createDefaultProject(env, settings)
    } catch (e: Exception) {
        // The one outcome that is not allowed here is nothing at all. Its own
        // channel, not the area's: that dialog is titled "Couldn't save the
        // area" and nothing was being saved, and a refusal that names the
        // wrong thing sends the cruiser looking for the wrong problem.
        state.cruiseSetupRefusal = "Storage error: ${e.message ?: e}. There is no " +
            "project to lay the cruise into, so setup did not open — try again."
        return
    }
    if (settings.cruiseProjectId != project.id.toString()) {
        env.settings.setCruiseProjectId(project.id.toString())
    }
    // A snapshot for a DIFFERENT project is worse than an empty one: its
    // plots and trees belong to somewhere else. The reload triggered by the
    // mode flip fills this in a moment later.
    if (state.data.project?.id != project.id) {
        state.data = CruiseData(project, emptyList(), emptyMap())
    }
    areaState.cruiseSetupArea = stratum
    areaState.selectedId = null
    enterCruiseMode()
    state.cruiseSetupOpen = true
}

/// Why Cruise setup did not open. Hosted by MapHomeScreen in BOTH modes,
/// unlike the plot and plan refusals inside `CruiseModeSheets`: the tap that
/// can raise this one is on an area callout, and areas are drawn in measure
/// mode too — a refusal that only renders in cruise mode is the silence it
/// was written to break.
@Composable
internal fun CruiseSetupRefusalDialog(state: CruiseModeState) {
    val message = state.cruiseSetupRefusal ?: return
    AlertDialog(
        onDismissRequest = { state.cruiseSetupRefusal = null },
        title = { Text("Can't open Cruise setup") },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = { state.cruiseSetupRefusal = null }) { Text("OK") }
        },
    )
}

/// Cruise-mode sheets (project ⑤ / cruise setup ⑥ / site description),
/// hosted by MapHomeScreen after its own measure-world sheets.
@Composable
internal fun CruiseModeSheets(
    state: CruiseModeState,
    nav: NavController,
    camera: MapCameraState,
    fallbackCentre: CoordinateConversions.LatLon,
    /// The AREA cruise setup was opened from, when it was opened from an
    /// outline the cruiser selected on the map. Null from the project strip.
    setupArea: Stratum? = null,
    /// Clears that seed once the sheet is gone, so the next opening from the
    /// project strip is not still pointed at an area.
    onSetupClosed: () -> Unit = {},
) {
    val env = LocalAppEnvironment.current
    val settings by env.settings.state.collectAsStateWithLifecycle()

    // MARK: - Project sheet (mock ⑤)

    if (state.projectSheetOpen) {
        ProjectSheet(
            env = env,
            currentProject = state.project,
            // FIELD REPORT 5 — the plot in hand, so the sheet can offer that
            // plot's field log. Same `activePlot` the (+) scopes "Add tree ·
            // Plot N" to, so the two can never name different plots.
            activePlot = state.activePlot(settings),
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
    // A REQUEST, NOT A LATCH. `cruiseSetupOpen` is only ever raised with a
    // project already in hand, but a project can also go away underneath it
    // (deleted, or a switch that resolves to none) — and a flag left standing
    // through that would re-open the sheet the next time any project appeared,
    // which the cruiser reads as the app opening a sheet by itself. Dropping
    // it here means the worst case is a sheet that closed, never one that
    // opens unasked.
    LaunchedEffect(state.cruiseSetupOpen, setupProject?.id) {
        if (state.cruiseSetupOpen && setupProject == null) {
            state.cruiseSetupOpen = false
            onSetupClosed()
        }
    }
    if (state.cruiseSetupOpen && setupProject != null) {
        CruiseSetupSheet(
            project = setupProject,
            mapCentre = camera.center ?: fallbackCentre,
            onDismiss = {
                state.cruiseSetupOpen = false
                onSetupClosed()
                state.refresh++
            },
            onDrawBoundary = {
                state.cruiseSetupOpen = false
                onSetupClosed()
                nav.navigate(ProjectFlowRoutes.stratumDraw(setupProject.id.toString()))
            },
            // Opened from an area on the map: the sheet lays plots into THAT
            // area and leaves the others alone. Null from the project strip.
            area = setupArea,
        )
    }

    // MARK: - Plot sample heights sheet (B — "Sample heights · N of M")

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

    // MARK: - Plot map-overlay menu (M2 — tap the drawn plot)

    val menuPlot = state.plotMenuFor
        ?.let { id -> state.data.plots.firstOrNull { it.id == id } }
    if (menuPlot != null) {
        PlotOverlayMenu(
            plot = menuPlot,
            radiusM = plotRadiusMetres(menuPlot),
            treeCount = state.data.treesByPlot[menuPlot.id].orEmpty().size,
            system = state.unitSystem(settings),
            onEdit = {
                state.plotMenuFor = null
                // Any ring belonging to a DIFFERENT plot goes first. This
                // door can open on an older open plot while the ring still
                // marks the newest one, and applyEdit would read that ring as
                // "the cruiser re-placed this plot's centre" and move the plot
                // onto today's fix (field report 7).
                ArSessionHub.armPlotSetup(menuPlot.id)
                nav.navigate(
                    CruiseRoutes.editPlot(
                        menuPlot.projectId.toString(), menuPlot.id.toString()))
            },
            onRemove = { state.removePlot(menuPlot) },
            onDismiss = { state.plotMenuFor = null },
        )
    }

    // MARK: - Site description (plot peek "Add details")

    // Slope, aspect, ground elevation, canopy cover. It takes a plot ID and
    // loads its own copy: this screen already holds `data.plots`, and two
    // holders of one plot is how one of them saves over the other's edit.
    state.detailPlotFor?.let { plotId ->
        PlotDetailSheet(
            plotId = plotId,
            onDismiss = { state.detailPlotFor = null },
            onSaved = { state.refresh++ },
        )
    }

    // MARK: - The (+)'s two doors into a plot

    // Not a third path: "Pick on the map" arms the press-and-hold above and
    // nothing else.
    if (state.startPlotChoiceOpen) {
        ChoiceMenuDialog(
            title = "Start plot",
            primaryLabel = "Start here",
            secondaryLabel = "Pick on the map",
            onPrimary = {
                state.startPlotChoiceOpen = false
                state.startPlot()
            },
            onSecondary = {
                state.startPlotChoiceOpen = false
                state.selectedId = null
                state.awaitingPlanPress = true
            },
            onDismiss = { state.startPlotChoiceOpen = false },
        )
    }

    // MARK: - Delete a planned plot

    // The plan, never a measurement. Worded so the cruiser can tell: no tally
    // can be lost here, because a planned plot has never held one.
    val deletePlanned = state.deletePlannedCandidate
    if (deletePlanned != null) {
        AlertDialog(
            onDismissRequest = { state.deletePlannedCandidate = null },
            title = { Text("Delete planned plot?") },
            text = {
                Text(
                    "Delete the plan for Plot ${deletePlanned.plotNumber}? " +
                        "Nothing measured is affected — this plot has no readings yet.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.deletePlannedCandidate = null
                        state.deletePlanned(deletePlanned)
                    },
                ) { Text("Delete planned plot") }
            },
            dismissButton = {
                TextButton(onClick = { state.deletePlannedCandidate = null }) { Text("Cancel") }
            },
        )
    }

    // MARK: - Planned-plot refusal

    // A plan has no centre, so it gets its own refusal rather than borrowing
    // the one titled "Can't save the plot centre".
    val planRefusal = state.planSaveRefusal
    if (planRefusal != null) {
        AlertDialog(
            onDismissRequest = { state.planSaveRefusal = null },
            title = { Text("Can't save the planned plot") },
            text = { Text(planRefusal) },
            confirmButton = {
                TextButton(onClick = { state.planSaveRefusal = null }) { Text("OK") }
            },
        )
    }

    // MARK: - Plot-centre refusal (field 17 — "Start plot now" with no fix)

    val refusal = state.plotSaveRefusal
    if (refusal != null) {
        AlertDialog(
            onDismissRequest = { state.plotSaveRefusal = null },
            title = { Text("Can't save the plot centre") },
            text = { Text(refusal) },
            confirmButton = {
                TextButton(onClick = { state.plotSaveRefusal = null }) { Text("OK") }
            },
        )
    }
}

/// A one- or two-choice menu over the map: the (+)'s two doors into a plot.
/// Same card shape as `PlotOverlayMenu` — a titled column of full-width
/// buttons — which is this app's answer to the iOS confirmation dialog it
/// mirrors. Every label is an ordinary (non-destructive) action, so none
/// takes the danger treatment.
///
/// The second choice is optional; Cancel is always drawn, so the card is
/// never a bare title.
@Composable
private fun ChoiceMenuDialog(
    title: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    onDismiss: () -> Unit,
    secondaryLabel: String? = null,
    onSecondary: () -> Unit = {},
) {
    val colors = Forestix.colors
    val type = Forestix.type
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier
                .fillMaxWidth(0.86f)
                .widthIn(max = 340.dp)
                .clip(ForestixRadius.card)
                .background(colors.surface)
                .padding(ForestixSpace.md),
            verticalArrangement = Arrangement.spacedBy(ForestixSpace.sm),
        ) {
            Text(title, style = type.bodyBold, color = colors.textPrimary)
            ForestixProminentButton(
                label = primaryLabel,
                modifier = Modifier.fillMaxWidth(),
                onClick = onPrimary,
            )
            if (secondaryLabel != null) {
                ForestixBorderedButton(
                    label = secondaryLabel,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onSecondary,
                )
            }
            ForestixBorderedButton(
                label = "Cancel",
                modifier = Modifier.fillMaxWidth(),
                onClick = onDismiss,
            )
        }
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
internal suspend fun resolveCurrentProject(
    env: AppEnvironment,
    cruiseProjectId: String?,
): Project? {
    val picked = cruiseProjectId?.let(::uuidOrNull)?.let { env.projectRepository.read(it) }
    return picked ?: env.projectRepository.list().firstOrNull()
}

/// Zero-gate fallback when (+) "Start plot" is tapped with no project yet:
/// auto-named project, units from app settings, calibration defaults
/// (identity + 5 mm depth noise — the retired project screen's create).
internal suspend fun createDefaultProject(
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
        // No recorded centre → no pin. Same rule the plot overlay and every
        // exporter apply: a plot with no centre is not a plot at a place.
        if (!plot.hasCentre) continue
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
            // Named trees read by their name here too (shortened to what
            // the drop holds) — the peek prints it in full.
            title = TreeLabel.pinTitle(tree.treeName, tree.treeNumber),
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
/// guide line is up.
///
/// How far the cruiser still has to WALK, so it goes through `navDistance`
/// rather than `distance` — and through the unit system, because a cruiser who
/// paces in feet cannot use "142 m" for anything without doing arithmetic on a
/// hillside. iOS `distanceLabel` prints the identical string.
private fun navDistanceLabel(metres: Double, system: UnitSystem): String =
    MeasurementFormatter.navDistance(metres, system)

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
/// current fix, then the PAIR — a 54 dp "Start plot now" primary and a 54 dp
/// outlined-primary "Navigate", the two things a cruiser standing in front of
/// this pin actually does.
///
/// "Set plot centre (GPS)" used to sit between them, in the outlined primary
/// this card now gives Navigate. Field feedback: the 60 s averaging window was
/// worse than not having it — a cruiser who has already walked to the plot
/// spends a minute standing still under a canopy that will not give them a
/// better fix.
@Composable
private fun PlannedPeekCard(
    planned: PlannedPlot,
    fix: CLLocationSnapshot?,
    navigating: Boolean,
    starting: Boolean,
    /// The range row is a walking instruction, so it is stated in the unit the
    /// cruiser paces in.
    unitSystem: UnitSystem,
    modifier: Modifier = Modifier,
    onStartNow: () -> Unit,
    onToggleNavigate: () -> Unit,
    onToggleSkip: () -> Unit,
    onMovePlan: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = Forestix.colors
    val type = Forestix.type
    val shape = RoundedCornerShape(14.dp)
    // A skipped plot is documented inaccessible: the peek swaps the PLANNED
    // chip for SKIPPED. Every ACTION stays available, because "can't reach"
    // is a judgement the cruiser is allowed to revise — a plot marked
    // unreachable in the morning and walked into after lunch must be
    // startable from the peek that is already open. iOS has always kept the
    // three actions up on a skipped plot (MapHomeScreen+Cruise.swift); this
    // used to hide them behind `if (!skipped)` and leave Android-only
    // cruisers with an un-skip round trip. The skip toggle itself is the
    // last row either way.
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
        // LOCKED live line "X m · bearing Y°" — and the fix behind it must be
        // FRESH. This is a walking instruction: a minutes-old position sends
        // the cruiser off on a heading measured from somewhere they no longer
        // are. Same gate as the plot verdict and the drawn you-dot, so nothing
        // here claims a position the app has stopped trusting; when it lapses
        // the row falls through to "no GPS fix" below. `lastGlobalFix` is
        // deliberately NOT consulted — it survives across sessions.
        val rangeFix = freshFixOrNull(fix, System.currentTimeMillis())
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
                    // The bearing is degrees in both systems — a compass does
                    // not change with the Units toggle. The RANGE does: this
                    // row is what the cruiser paces off.
                    String.format(
                        Locale.US, "%s · bearing %.0f°",
                        MeasurementFormatter.navDistance(d, unitSystem),
                        (b + 360.0).mod(360.0))
                } else {
                    "no GPS fix"
                },
                style = type.dataSmall.copy(fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold),
                color = colors.textPrimary,
                maxLines = 1,
            )
        }
        Spacer(Modifier.size(ForestixSpace.sm))
        // 54 dp primary — FIELD REPORT 17. The plot opens on the fix
        // that is live right now, with no window to sit out. The FROM
        // YOU row directly above is the check that makes this safe: it
        // is the cruiser's own reading of whether they are standing at
        // the plot or looking at it from the far side of a draw.
        //
        // Dimmed and deaf while a start is in flight, so the button LOOKS
        // like what it is. The write itself is guarded at the action
        // (`CruiseModeState.startingPlanned`) — this is the visible half.
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 54.dp)
                .alpha(if (starting) 0.45f else 1f)
                .pressableNoRipple(enabled = !starting, onClick = onStartNow)
                .clip(ForestixRadius.card)
                .background(colors.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Start plot now",
                style = type.bodyBold.copy(fontSize = 15.sp),
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
        Spacer(Modifier.size(8.dp))
        // THE OTHER HALF OF THE PAIR — LOCKED "Navigate", in the same 54 dp
        // outlined primary the removed averaging button had, so walking to
        // the plot and opening it read as the two things this card is for.
        // While the guide is up it takes the accent instead: the outline is
        // what says "tapping this stops it".
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 54.dp)
                .pressableNoRipple(onClick = onToggleNavigate)
                .clip(ForestixRadius.card)
                .border(
                    1.5.dp,
                    if (navigating) colors.accent else colors.primary,
                    ForestixRadius.card,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Navigate",
                style = type.bodyBold.copy(fontSize = 15.sp),
                color = if (navigating) colors.accent else colors.primary,
            )
        }
        Spacer(Modifier.size(8.dp))
        // 44 dp outline skip toggle — an EXPLICIT cruiser action (unlike
        // `visited`, which flips implicitly when the plot is opened). "Skip
        // this plot" documents a cliff/water/private-land plot so navigation
        // passes over it; "Unskip this plot" undoes it.
        //
        // It read "Mark unreachable" / "Restore plot", which is office
        // language: nobody in the field says they marked a plot unreachable,
        // they say they skipped it. "Restore" was the worse of the two — it
        // is the word for bringing back something deleted, and nothing here
        // was ever deleted. Same two words and same warn/neutral treatment
        // as iOS.
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .pressableNoRipple(onClick = onToggleSkip)
                .clip(ForestixRadius.control)
                .border(
                    1.dp,
                    if (skipped) colors.divider else colors.confidenceWarn.copy(alpha = 0.5f),
                    ForestixRadius.control,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (skipped) "Unskip this plot" else "Skip this plot",
                style = type.bodyBold.copy(fontSize = 14.sp),
                color = if (skipped) colors.textPrimary else colors.confidenceWarn,
            )
        }
        Spacer(Modifier.size(8.dp))
        // A PLAN IN THE WRONG SPOT IS THE NORMAL CASE. Move re-arms the same
        // press-and-hold that made the plan; Delete throws the plan away.
        // Both sit below the skip toggle because they change the plan itself
        // rather than what to do about it. iOS carries the same pair.
        //
        // "Move plan", not "Move on map" and not "Relocate": it is the
        // word-for-word pair of the "Delete plan" beside it, and it names
        // WHAT moves. A cruiser reading "Relocate" on a card titled "Plot 4
        // (planned)" has to work out whether the plot they measured is about
        // to move — this one cannot be read that way.
        Row(horizontalArrangement = Arrangement.spacedBy(ForestixSpace.xs)) {
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .pressableNoRipple(onClick = onMovePlan)
                    .clip(ForestixRadius.control)
                    .border(1.dp, colors.divider, ForestixRadius.control),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Move plan",
                    style = type.bodyBold.copy(fontSize = 14.sp),
                    color = colors.textPrimary,
                )
            }
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .pressableNoRipple(onClick = onDelete)
                    .clip(ForestixRadius.control)
                    .border(1.dp, colors.confidenceBad.copy(alpha = 0.5f), ForestixRadius.control),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Delete plan",
                    style = type.bodyBold.copy(fontSize = 14.sp),
                    color = colors.confidenceBad,
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
    /// The plan the (+) will take the cruiser to, so the button can NAME it.
    targetPlanned: PlannedPlot?,
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
            // Plot N"; unvisited plan waiting → "Go to Plot N"; otherwise →
            // "Start plot".
            //
            // It read "Set plot centre", which described a sheet that is no
            // longer what the button opens. It NAMES THE PLOT because the
            // whole complaint was that the cruiser could not tell which plan
            // they were being sent to; "Add tree · Plot N" beside it already
            // establishes that this button says what it is about to act on.
            // ("Start cruising" was the other candidate and reads wrong by
            // the third plot of the morning — the button is pressed once per
            // plot all day, not once per cruise.)
            val captureLabel = when {
                activePlot != null -> "Add tree · Plot ${activePlot.plotNumber}"
                targetPlanned != null -> "Go to Plot ${targetPlanned.plotNumber}"
                else -> "Start plot"
            }
            CaptureColumn(
                caption = captureLabel,
                contentDescription = when {
                    activePlot != null -> "Add tree"
                    targetPlanned != null -> "Go to Plot ${targetPlanned.plotNumber}"
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
    onAddDetails: () -> Unit,
    onDetails: () -> Unit,
) {
    val colors = Forestix.colors
    val type = Forestix.type
    val shape = RoundedCornerShape(14.dp)
    val closed = plot.closedAt != null
    var confirmDelete by remember(plot.id) { mutableStateOf(false) }
    // Which units this card renders in, by the rule the whole cruise surface
    // follows (`CruiseModeState.unitSystem`): the PROJECT's, because a cruise
    // is run in the units it was set up in, falling back to the app setting
    // while there is no project. Stated once here so the radius, the basal
    // area and the mean DBH cannot each pick a different answer.
    val settings by env.settings.state.collectAsStateWithLifecycle()
    val unitSystem = when (project?.units) {
        com.hcjeong.forestix.data.cruise.UnitSystem.METRIC -> UnitSystem.METRIC
        com.hcjeong.forestix.data.cruise.UnitSystem.IMPERIAL -> UnitSystem.IMPERIAL
        null -> settings.unitSystem
    }

    // Live stats via the SAME inventory engine PlotTallyScreen uses.
    var stats by remember(plot.id, trees.size) { mutableStateOf(PlotStats.empty) }
    LaunchedEffect(plot.id, trees.size) {
        stats = computePlotStats(env, project, plot, trees, allProjectTrees)
    }
    val nextTree = (trees.maxOfOrNull { it.treeNumber } ?: 0) + 1
    // What the tree this button is about to open the tally on will be called:
    // the auto-incremented name when this plot has a series running, else
    // "Tree #<next>". Read straight off the peek's own tree list rather than
    // the repository, so the label tracks the tally without a round trip.
    val nextTreeTitle = TreeLabel.title(CruiseCapture.nextTreeName(trees), nextTree)
    // B. PLOT SAMPLE HEIGHTS — named as a SUBSAMPLE. The row read "Heights"
    // and a cruiser who had just tapped a PLOT asked why heights were being
    // offered here and where diameter was — a fair question of a word that
    // looks like a heading. Which trees get a measured height is a
    // plot-level decision (the rest are imputed from the H–D curve), which
    // is why the row is here and why there is correctly no diameter row
    // beside it: every tree gets a diameter through the tally.
    //
    // The rule the Add-Tree flow measures against, read from the project's
    // CruiseDesign. Cruise setup is optional, so a design-less project falls
    // back to the model default (every 5th tree) exactly as Mappers does —
    // one fallback, so the peek and the flow agree about such a project.
    //
    // Null until the read lands. The repository is suspend here (iOS reads
    // it synchronously), and a count derived from a rule not yet read would
    // be a number stated before it is known: the row shows its bare name for
    // that frame instead.
    var subsampleRule by remember(plot.projectId) {
        mutableStateOf<HeightSubsampleRule?>(null)
    }
    LaunchedEffect(plot.projectId) {
        subsampleRule = try {
            env.cruiseDesignRepository.forProject(plot.projectId)
                .firstOrNull()?.heightSubsampleRule
        } catch (_: Exception) {
            null
        } ?: HeightSubsampleRule.EveryKth(k = 5)
    }
    // LOCKED strings "Sample heights" / "Sample heights · N of M" /
    // "Sample heights · N measured"; iOS `plotHeightsLabel` returns the same
    // three. The target comes from HeightSubsample, the one owner of the
    // rule, so the row can never ask for a different number than the flow
    // that fills it. Where the rule sets no target (None) or asks nothing of
    // this plot (an empty plot asks nothing) there is no denominator to
    // show: the row falls back to the plain count, and to the bare name at
    // zero. A fraction is never invented to fill the gap, and a plot with no
    // trees never reads as work due.
    val heightsProgress = remember(subsampleRule, trees) {
        subsampleRule?.let { HeightSubsample.progress(it, trees) }
    }
    val heightsTarget = heightsProgress?.target
    val heightsLabel = when {
        heightsProgress == null -> "Sample heights"
        heightsTarget != null && heightsTarget > 0 ->
            "Sample heights · ${heightsProgress.measured} of $heightsTarget"
        heightsProgress.measured > 0 ->
            "Sample heights · ${heightsProgress.measured} measured"
        else -> "Sample heights"
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
                // Same ring, same label as the plot banner and the plot menu —
                // one plot cannot have one radius where you tap it and another
                // where the banner shows it.
                MeasurementFormatter.plotLength(plotRadiusMetres(plot), unitSystem) +
                    " radius · " +
                    SimpleDateFormat("HH:mm", Locale.US).format(Date(plot.startedAt)),
                style = type.dataSmall.copy(fontSize = 11.sp),
                color = colors.textTertiary,
                maxLines = 1,
            )
        }
        Spacer(Modifier.size(10.dp))
        // Stats strip (mock `.stats`): TREES / BASAL AREA / TREES-per-area /
        // MEAN DBH — spelled out, not the BA / TPA / QMD initialisms. Per-area basis
        // follows the project's units (US per acre, metric per hectare); the
        // engine computes per acre, so scale + relabel at display only.
        val metric = unitSystem == UnitSystem.METRIC
        val areaUnit = unitSystem.areaUnit
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
            // Basal area converts its NUMERATOR with the basis, not just its
            // suffix: the engine reports m²/acre, so an imperial cruise that
            // scaled only the denominator printed "m²/ac" — a unit no cruise
            // sheet uses and 10.76x off the ft²/ac the same app shows for the
            // same stand elsewhere.
            StatsCell(
                "BASAL AREA",
                String.format(
                    Locale.US, "%.1f",
                    MeasurementFormatter.basalAreaDensity(
                        stats.baPerAcreM2.toDouble(), areaUnit)),
                MeasurementFormatter.basalAreaDensityUnit(areaUnit), Modifier.weight(1f))
            StatsDivider()
            StatsCell(
                if (metric) "TREES/HA" else "TREES/AC",
                String.format(Locale.US, "%.0f", stats.tpa * densityF),
                densitySuffix, Modifier.weight(1f))
            StatsDivider()
            // Mean DBH is a diameter like the ones on the trees above it and
            // follows the same formatter they do.
            StatsCell(
                "MEAN DBH",
                MeasurementFormatter.entryText(
                    MeasurementFormatter.diameterValue(
                        stats.qmdCm.toDouble(), unitSystem), 1),
                MeasurementFormatter.diameterUnit(unitSystem), Modifier.weight(1f))
        }
        // B. PLOT SAMPLE HEIGHTS — list-row into the sample heights sheet
        // (iOS plotPeek row 1:1), shown for open AND closed plots. The
        // LOCKED label is built above.
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .clickableNoRipple(onHeights),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                heightsLabel,
                style = type.bodyBold.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                color = colors.textPrimary,
            )
        }
        // SITE DESCRIPTION — slope, aspect, ground elevation, canopy cover.
        // It sits beside Sample heights because both are the same kind of
        // act: recording something about THIS PLOT that the tally itself
        // cannot see. "Add details" rather than a second "Details" verb — the
        // pair below opens the plot's report, this one is where a cruiser
        // writes the ground down.
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .clickableNoRipple(onAddDetails),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Add details",
                style = type.bodyBold.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                color = colors.textPrimary,
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
                    "Add tree · $nextTreeTitle",
                    style = type.bodyBold.copy(fontSize = 15.sp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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

// MARK: - Plot sample heights sheet (B) ----------------------------------------

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
                // LOCKED "SAMPLE HEIGHTS · PLOT n" — one vocabulary with
                // the plot peek row that opens this sheet.
                "SAMPLE HEIGHTS · PLOT ${plot.plotNumber}",
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
                    // A cruiser's name is longer than "Tree #7" and this column
                    // was sized for the number alone; it gets room to grow, and
                    // truncates rather than shoving the diameter and height out
                    // of the row.
                    Text(
                        t.displayTitle,
                        style = type.bodyBold.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold),
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(min = 76.dp),
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
                // tree) — selectable "Tree #N · DBH (✓)" rows, the
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
                                t.displayTitle + " · " +
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
                // Stage 2 confirm — names the picked tree, "Measure height"
                // until there is one (iOS pickerStage 1:1).
                HeightsSheetPrimary(
                    enabled = selected != null,
                    label = selected?.let { "Measure ${it.displayTitle}" }
                        ?: "Measure height",
                ) {
                    selected?.let { onMeasure(it) }
                }
            }
        }
    }
}

/// The sample heights sheet's 54 dp primary — LOCKED "Measure height" on both
/// stages (iOS measureButton parity: filled primary, 0.45 alpha when
/// there is nothing to measure).
///
/// Stage 2 names the tree once one is picked — "Measure Tree #7", or the
/// cruiser's name for it. iOS has always done this and Android always said
/// "Measure height"; the label is a tree-identity surface like the rows above
/// it, so the two now say the same words.
@Composable
private fun HeightsSheetPrimary(
    enabled: Boolean,
    label: String = "Measure height",
    onClick: () -> Unit,
) {
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
            label,
            style = Forestix.type.bodyBold.copy(fontSize = 15.sp),
            color = MaterialTheme.colorScheme.onPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
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

    // One title and one subtitle for the card AND for the photo viewer the
    // thumbnail opens — the caption under the photo must name the same tree
    // the card above it does. Declared out here because the viewer is raised
    // beside the Column, not inside it.
    val treeTitle = tree.displayTitle +
        (tree.speciesCode.takeIf { it.isNotBlank() }
            ?.let { " · ${RegionalSpecies.nameForCode(it)}" } ?: "")
    val treeSubtitle = listOfNotNull(
        plotNumber?.let { "Plot $it" },
        SimpleDateFormat("d MMM · HH:mm", Locale.US).format(Date(tree.createdAt)),
    ).joinToString(" · ")

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
                treeTitle,
                style = type.bodyBold.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold),
                color = colors.textPrimary,
                modifier = Modifier.weight(1f).alignByBaseline(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                treeSubtitle,
                style = type.dataSmall.copy(fontSize = 11.sp),
                color = colors.textTertiary,
                modifier = Modifier.alignByBaseline(),
                maxLines = 1,
            )
        }
        Spacer(Modifier.size(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Tapping the thumbnail opens the app's one full-screen viewer
            // (map-peek spec item 3). The cruise tree photo lives in the
            // same store. A Tree row carries a SINGLE photo path, so this
            // opens one page and shows no counter.
            PhotoThumb(
                tree.photoPath, if (tree.photoPath != null) 1 else 0,
                onClick = tree.photoPath?.let { { showPhoto = true } },
            )
            Column(Modifier.weight(1f)) {
                TreeMetricRow(
                    label = "DBH",
                    value = MeasurementFormatter.diameter(tree.dbhCm.toDouble(), unitSystem),
                    tierRaw = tree.dbhConfidence.raw,
                    explains = TierExplainerKind.DIAMETER,
                )
                if (tree.heightM != null) {
                    HorizontalDivider(color = colors.divider, thickness = 0.5.dp)
                    TreeMetricRow(
                        label = "HEIGHT",
                        value = MeasurementFormatter.height(tree.heightM!!.toDouble(), unitSystem),
                        tierRaw = tree.heightConfidence?.raw,
                        explains = TierExplainerKind.HEIGHT,
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
                // The distance decides whether a borderline stem belongs to
                // the plot, so it is stated in the cruiser's own unit through
                // the same helper the plot banner uses. iOS already did this
                // (MapHomeScreen+Plot's "… from centre"); this row was the one
                // place the two platforms disagreed. The BEARING stays degrees
                // — a compass does not change with the Units toggle.
                String.format(
                    Locale.US, "Bearing %.0f° · %s from centre — auto",
                    bearing,
                    MeasurementFormatter.plotLength(dist.toDouble(), unitSystem)),
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
        PhotoViewerDialog(
            pages = cruiseTreePhotoPages(tree.photoPath, treeTitle, treeSubtitle),
            onDismiss = { showPhoto = false },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${tree.displayTitle}?") },
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
private fun TreeMetricRow(
    label: String,
    value: String,
    tierRaw: String?,
    explains: TierExplainerKind,
) {
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
        // Tapping the grade opens the same explainer the tree report and
        // the quick peek open, scoped to this row's measurement.
        if (tierRaw != null) TierChipSoft(tierRaw, explains = explains)
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
    /// The open plot, when there is one — the sheet's "Field log · Plot N"
    /// row scopes to it. Null hides that row: with no plot in hand there is
    /// nothing to scope to.
    activePlot: Plot?,
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
                    Text(
                        "New project",
                        style = type.bodyBold,
                        color = colors.textSecondary,
                        modifier = Modifier.weight(1f),
                    )
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
            // FIELD REPORT (item 4) — the door beside "New project". The
            // switcher rows above can only SWITCH; browsing is where a
            // project can be read properly and where it can be deleted.
            // Enabled with no projects too: the empty state is itself the
            // answer to "which ones exist?".
            SheetChoiceRow(
                Icons.Filled.Folder,
                "Browse projects",
                "Open, review or delete a saved project",
            ) { onNavigate(CruiseRoutes.PROJECT_BROWSER) }
            HorizontalDivider(color = colors.divider, thickness = 0.5.dp)
            SheetChoiceRow(
                Icons.Filled.BarChart,
                "Stand summary",
                "Averages across your closed plots",
                enabled = projectId != null,
            ) { projectId?.let { onNavigate(CruiseRoutes.standSummary(it)) } }
            HorizontalDivider(color = colors.divider, thickness = 0.5.dp)
            SheetChoiceRow(
                Icons.Filled.GridOn,
                "Cruise setup",
                "Lay out plots on a grid, set plot size, draw a boundary",
                enabled = projectId != null,
                trailingChip = "Advanced",
            ) { if (projectId != null) onCruiseSetup() }

            // FIELD REPORT 5 — read back THIS plot's trees without leaving
            // the project. Only offered when a plot is actually open: with no
            // plot in hand there is nothing to scope to, and the footer's
            // plain "Field log" already shows everything. The subtitle names
            // the plot so the row cannot be mistaken for that unscoped link.
            if (activePlot != null) {
                HorizontalDivider(color = colors.divider, thickness = 0.5.dp)
                SheetChoiceRow(
                    Icons.AutoMirrored.Filled.ListAlt,
                    "Field log",
                    FieldLogWords.plotName(activePlot.plotNumber),
                ) { onNavigate(Routes.fieldLogPlot(activePlot.id.toString())) }
            }

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
    val exportSettings by env.settings.state.collectAsStateWithLifecycle()
    // Keyed on the unit system so a cruiser who flips Units while this sheet is
    // up re-configures the view model before they tap Export — the report says
    // what the cruiser is looking at, not what the project was stamped with.
    LaunchedEffect(viewModel, exportSettings.unitSystem) {
        viewModel.configure(
            env,
            if (exportSettings.unitSystem == UnitSystem.METRIC) {
                com.hcjeong.forestix.data.cruise.UnitSystem.METRIC
            } else {
                com.hcjeong.forestix.data.cruise.UnitSystem.IMPERIAL
            },
        )
    }

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
