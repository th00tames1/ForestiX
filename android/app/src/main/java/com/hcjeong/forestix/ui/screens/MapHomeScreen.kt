// Map-first home — Android build of the approved mock
// design/forestix-redesign-v2-maphome.html (① map home, ② pin peek card,
// ③ measure chooser sheet, ⑤ photo detail; ④ AR accept already ships in
// the scan screens' auto-capture).
//
// The map is the home because a cruiser's data IS spatial: every accepted
// reading with a GPS fix becomes a tree pin (one tree = one pin, D/H/C
// badges), pin tap opens a bottom peek card so the map never disappears,
// and the single primary action is the centre (+) capture button. The
// built-in Esri satellite base gives imagery out of the box; the user's
// XYZ template renders as an overlay on top (toggle in the layers sheet).
//
// v3.1: the separate CruiseMapScreen is absorbed HERE as a toggled MODE
// (tc.mapMode "measure" | "cruise") — one map, two modes, no navigation
// between them. The left side-circle toggles the mode in place (camera and
// zoom are shared, no snap), measure mode stays exactly this screen, and
// cruise mode swaps in the CruiseModeContent pins/chrome/peeks/sheets:
// quick pins are measure-only, cruise pins cruise-only. System back in
// cruise mode returns to measure mode.

package com.hcjeong.forestix.ui.screens

import android.graphics.Bitmap
import android.text.format.DateUtils
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CenterFocusWeak
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Height
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Park
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.hcjeong.forestix.LocalAppEnvironment
import com.hcjeong.forestix.basemap.MapBoundaryOverlay
import com.hcjeong.forestix.basemap.MapMarker
import com.hcjeong.forestix.basemap.MapMarkerShape
import com.hcjeong.forestix.basemap.MapView
import com.hcjeong.forestix.basemap.rememberMapCameraState
import com.hcjeong.forestix.common.ForestixLogger
import com.hcjeong.forestix.common.MeasurementFormatter
import com.hcjeong.forestix.common.RegionalSpecies
import com.hcjeong.forestix.common.TruthInput
import com.hcjeong.forestix.common.UnitSystem
import com.hcjeong.forestix.data.MeasureKind
import com.hcjeong.forestix.data.QuickMeasureEntry
import com.hcjeong.forestix.data.SettingsSnapshot
import com.hcjeong.forestix.data.cruise.CrashRecoveryService
import com.hcjeong.forestix.data.cruise.TreeLabel
import com.hcjeong.forestix.geo.BoundaryGeometryKind
import com.hcjeong.forestix.geo.CoordinateConversions
import com.hcjeong.forestix.geo.SurveyBoundaryStore
import com.hcjeong.forestix.positioning.CLLocationSnapshot
import com.hcjeong.forestix.positioning.GeoMath
import com.hcjeong.forestix.positioning.LocationService
import com.hcjeong.forestix.ui.MeasurePhotoStore
import com.hcjeong.forestix.ui.PendingTreeNumber
import com.hcjeong.forestix.ui.Routes
import com.hcjeong.forestix.ui.clickableNoRipple
import com.hcjeong.forestix.ui.screens.tree.TierExplainerKind
import com.hcjeong.forestix.ui.screens.tree.TierExplainerSheet
import com.hcjeong.forestix.ui.pressableNoRipple
import com.hcjeong.forestix.ui.screens.cruise.CruiseCapture
import com.hcjeong.forestix.ui.screens.cruise.CruiseDistanceOverlay
import com.hcjeong.forestix.ui.screens.cruise.CruiseModeBottomContent
import com.hcjeong.forestix.ui.screens.cruise.CruiseModeEffects
import com.hcjeong.forestix.ui.screens.cruise.CruiseModeSheets
import com.hcjeong.forestix.ui.screens.cruise.CruiseModeState
import com.hcjeong.forestix.ui.screens.cruise.CruisePlanPromptBanner
import com.hcjeong.forestix.ui.screens.cruise.CruisePlotBanner
import com.hcjeong.forestix.ui.screens.cruise.PendingPlotFraming
import com.hcjeong.forestix.ui.screens.cruise.cruiseModeMarkers
import com.hcjeong.forestix.ui.screens.cruise.cruiseModePlotOverlay
import com.hcjeong.forestix.ui.screens.cruise.cruiseModePolylines
import com.hcjeong.forestix.ui.screens.cruise.freshFixOrNull
import com.hcjeong.forestix.ui.screens.cruise.msUntilFreshnessChanges
import com.hcjeong.forestix.ui.screens.cruise.plotFramingZoom
import com.hcjeong.forestix.ui.softDropShadow
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixRadius
import com.hcjeong.forestix.ui.theme.ForestixSpace
import com.hcjeong.forestix.ui.theme.confidenceDescriptor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/// Peavy Hall (OSU College of Forestry) fallback — used only when there is
/// no fix and no located reading. Mirrors iOS `fallbackCamera`.
private val DefaultCenter = CoordinateConversions.LatLon(latitude = 44.56417, longitude = -123.28556)

/// WHERE THE MAP OPENS. Two steps closer than the 16 it used to be: at 16 a
/// phone viewport is the better part of a kilometre across, which is a road
/// map. A cruiser opening this screen is standing in the stand they are
/// working, and what they need to see is their own plots and pins — 18 puts
/// roughly 150 m across the short side, so a plot and its neighbours are on
/// screen at arm's length without a pinch. Every camera move that has no
/// better idea of a scale uses this one constant. iOS `MapHomeScreen
/// .defaultZoom` is the same 18.
private const val DefaultZoom = 18.0

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapHomeScreen(nav: NavController) {
    val colors = Forestix.colors
    val env = LocalAppEnvironment.current
    val context = LocalContext.current
    val settings by env.settings.state.collectAsStateWithLifecycle()
    val entries by env.history.entries.collectAsStateWithLifecycle()

    // v3.1 merged cruise mode: ONE map, two modes, persisted (tc.mapMode).
    val isCruise = settings.mapMode == "cruise"

    // Re-entry housekeeping (was CruiseMapScreen's): a finished/abandoned
    // cruise tally chain must not leak its session or its project scan
    // calibration into the quick world — every landing on the home (the
    // chain always pops back here) disarms it. Idempotent.
    LaunchedEffect(Unit) { CruiseCapture.end(env) }

    // MARK: - Live GPS (GpsFixChip pattern: shared service + launcher)

    val location = remember { LocationService.shared(context) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results.values.any { it }
        location.onPermissionResult(granted)
        if (granted) location.start()
    }
    LaunchedEffect(Unit) {
        if (!LocationService.hasLocationPermission(context)) {
            launcher.launch(LocationService.PERMISSIONS)
        }
    }
    DisposableEffect(Unit) {
        location.acquire()
        onDispose { location.release() }
    }
    val fix by location.latestSnapshot.collectAsStateWithLifecycle()

    // MARK: - Pins + camera (the camera is SHARED across the mode toggle —
    // switching modes never snaps or re-zooms the map)

    val pins = remember(entries) { buildTreePins(entries) }

    // Last fix → else newest located reading → else the Peavy Hall fallback,
    // always at DefaultZoom (iOS startUp()).
    val initialCamera = remember {
        val fromFix = LocationService.lastGlobalFix?.let {
            CoordinateConversions.LatLon(latitude = it.latitude, longitude = it.longitude)
        }
        val fromEntry = entries.firstOrNull { it.latitude != null && it.longitude != null }?.let {
            CoordinateConversions.LatLon(latitude = it.latitude!!, longitude = it.longitude!!)
        }
        (fromFix ?: fromEntry ?: DefaultCenter) to (fromFix == null && fromEntry == null)
    }
    // Fresh install in the field: nothing to centre on at launch, so the
    // first GPS fix pulls the camera home — exactly once (iOS
    // recenterOnFirstFix). MapView re-centres whenever `mapCenter` changes.
    var mapCenter by remember { mutableStateOf(initialCamera.first) }
    var awaitingFirstFix by remember { mutableStateOf(initialCamera.second) }
    LaunchedEffect(fix) {
        val snap = fix
        if (awaitingFirstFix && snap != null) {
            awaitingFirstFix = false
            mapCenter = CoordinateConversions.LatLon(
                latitude = snap.latitude, longitude = snap.longitude)
        }
    }
    val camera = rememberMapCameraState()

    // A plot that has just been created gets FRAMED: the camera goes to its
    // centre at a zoom derived from its own radius, close enough that the
    // BOUNDARY is on screen. The question a cruiser asks the moment a plot
    // exists is "am I in it?", and at the default zoom an 11 m ring is a few
    // pixels across — unreadable, and no answer at all.
    //
    // The request is left by whichever surface created the plot (the AR
    // "Start plot" destination, or the planned-pin conversion) because none
    // of them owns this camera; see PendingPlotFraming. Consumed once — a
    // standing instruction would fight the cruiser's own panning. With no
    // measured viewport yet the zoom is left alone and only the centre
    // moves: a frame computed from nothing would be a guess.
    val framingRequest = PendingPlotFraming.request
    LaunchedEffect(framingRequest) {
        val r = framingRequest ?: return@LaunchedEffect
        // WAIT FOR THE VIEWPORT. The AR "Start plot" screen is its own nav
        // destination, so popping back re-composes this screen from scratch
        // and the camera has not been measured yet at this instant — and a
        // zoom cannot be derived from a viewport whose size is not known.
        // Poll until it is, then give up after a couple of seconds and just
        // centre on the plot: late is better than wrong, and doing nothing at
        // all would leave the cruiser looking at the wrong ground.
        val zoom = withTimeoutOrNull(2_000L) {
            var z = plotFramingZoom(camera, r.radiusM)
            while (z == null) {
                delay(50)
                z = plotFramingZoom(camera, r.radiusM)
            }
            z
        }
        camera.moveTo(r.centre, zoom ?: camera.zoom)
        PendingPlotFraming.consume()
    }

    // Imported survey boundary (Map settings → Survey boundary). Read once
    // per process off the main thread; the store's flow keeps the map and
    // the sheet in step when it is imported or removed.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { SurveyBoundaryStore.loadIfNeeded(context) }
    }
    val storedBoundary by SurveyBoundaryStore.state.collectAsStateWithLifecycle()
    val importedBoundary = remember(storedBoundary) {
        storedBoundary?.geometries.orEmpty().map { geometry ->
            MapBoundaryOverlay(
                rings = geometry.rings,
                closed = geometry.kind == BoundaryGeometryKind.POLYGON,
            )
        }
    }

    var selectedPinId by remember { mutableStateOf<String?>(null) }
    var chooserOpen by remember { mutableStateOf(false) }
    // Peek-card "Measure this tree" scopes the chooser to that pin's tree
    // (header "MEASURE · TREE N", no "(NEXT)"); null = plain chooser on the
    // next free number. Cleared whenever the chooser dismisses.
    var chooserTreeOverride by remember { mutableStateOf<Int?>(null) }
    // Far-GPS confirmation before the scoped chooser: (tree, whole metres)
    // when the current fix sits > 30 m from the tapped tree's pin.
    var farTreeConfirm by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var mapSettingsOpen by remember { mutableStateOf(false) }
    // "Draw an area" — the boundary editor, raised full-screen over the
    // home. Not a nav destination: it is a modal edit of one map object,
    // and back out of it must land here, not wherever nav last was.
    var drawingBoundary by remember { mutableStateOf(false) }
    // The photos the viewer is open on — every frame the tapped tree has,
    // in capture order. Empty = closed.
    var photoPages by remember { mutableStateOf<List<PhotoPage>>(emptyList()) }
    // Quick peek "Edit this tree" → compact edit sheet for one reading.
    var editEntry by remember { mutableStateOf<QuickMeasureEntry?>(null) }
    // Quick peek "Details" → the field log's own per-tree record sheet,
    // opened on the row behind the tapped pin. Null = closed.
    var inspectingRowId by remember { mutableStateOf<String?>(null) }

    // Crash-recovery resume prompt: open plots (closedAt == null) edited within
    // the last 24h, most-recent first. Non-null + non-empty shows the prompt.
    var recoveryPrompt by remember {
        mutableStateOf<List<CrashRecoveryService.ResumeCandidate>?>(null)
    }

    // MARK: - Cruise mode state + effects (data load / navigate guide /
    // actions run only while the mode is on; the holder survives toggles so
    // the 180 ms exit crossfade renders from live data)

    val cruise = remember { CruiseModeState() }
    if (isCruise) {
        CruiseModeEffects(
            state = cruise,
            nav = nav,
            settings = settings,
            fix = fix,
            awaitingFirstFix = awaitingFirstFix,
            onCentreOnCruiseGeometry = {
                awaitingFirstFix = false
                mapCenter = it
            },
        )
    }

    // Crash recovery: on the home's FIRST appearance per launch, scan for open
    // plots edited within the last 24h and surface the most-recent as a
    // Resume / View / Discard prompt. The process-static guard makes this run
    // AT MOST once per launch (the home recomposes / is re-entered on every
    // Settings round-trip and mode toggle); a dismiss / Discard never re-prompts.
    LaunchedEffect(Unit) {
        if (!CrashRecoveryService.checkedThisLaunch) {
            CrashRecoveryService.checkedThisLaunch = true
            val found = try {
                CrashRecoveryService.openPlotsWithinLast(
                    projectRepo = env.projectRepository,
                    plotRepo = env.plotRepository,
                    treeRepo = env.treeRepository,
                )
            } catch (_: Exception) {
                emptyList()
            }
            if (found.isNotEmpty()) {
                recoveryPrompt = found
                val top = found.first()
                ForestixLogger.crashRecoveryPrompted(top.plot.projectId, top.plot.id)
            }
        }
    }

    /// The toggle circle + system back both land here. Leaving a mode drops
    /// its selection so no stale peek pops back on the next visit.
    fun setMode(mode: String) {
        selectedPinId = null
        cruise.selectedId = null
        env.settings.setMapMode(mode)
    }

    // The cruiser's sampling plot ON THE MAP: the active plot drawn at true
    // ground scale (boundary, labelled range rings, compass badges, centre
    // mark) with its live INSIDE / OUTSIDE state. Null with no open plot, no
    // recorded centre, or outside cruise mode.
    //
    // The clock every position on this screen is judged against (see
    // PLOT_FIX_MAX_AGE_MS). Losing GPS produces no new fix, so without a
    // wake-up nothing would ever recompose this screen again and the banner
    // would keep asserting Inside from a fix minutes old.
    //
    // The wake-up fires ONCE, at the moment the current fix ages out —
    // deliberately not a free-running 1 Hz clock, which would recompose the
    // whole map (tiles, pins, plot) every second for the sake of a value
    // that changes twice an hour. A newer fix restarts the effect and
    // re-arms it; the immediate re-read at the top covers arriving with an
    // old fix already in hand.
    //
    // It runs in BOTH modes. The you-dot is drawn in both, and a fix too
    // old for the cruise banner to believe is exactly as untrustworthy on
    // the measure map — the rule is about the fix, not about the mode.
    var fixNowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(fix) {
        fixNowMs = System.currentTimeMillis()
        // The slack guarantees we wake AFTER the threshold, never a
        // millisecond short of it.
        val wait = msUntilFreshnessChanges(fix, fixNowMs) ?: return@LaunchedEffect
        delay(wait.coerceAtLeast(0L) + 250L)
        fixNowMs = System.currentTimeMillis()
    }

    // THE ONE POSITION ANYTHING ON THIS SCREEN IS ALLOWED TO DRAW.
    //
    // The map used to be handed the RAW fix while the banner ran it through
    // the age gate, so a rejected fix still painted a confident blue dot —
    // often sitting inside a plot the banner had just labelled unknown. The
    // picture contradicted the words beside it. Everything that puts a mark
    // at the cruiser's position now starts from this value: the you-dot,
    // the navigate guide line, and the distance chip riding it. Null means
    // the app does not know where the cruiser is, and draws nothing that
    // claims otherwise. (The plot overlay does its own gating through the
    // SAME helper — it needs the raw fix and the clock to date it.)
    val usableFix = freshFixOrNull(fix, fixNowMs)
    val plotOverlay =
        if (isCruise) cruiseModePlotOverlay(cruise, settings, fix, fixNowMs) else null

    val selectedPin = pins.firstOrNull { it.id == selectedPinId }
    // System back: cruise mode is a MODE of the home, not a destination —
    // back first dismisses a raised peek (the mode-specific handlers below
    // compose later, so they win while enabled), then flips cruise back to
    // measure; measure mode keeps the default behaviour.
    BackHandler(enabled = isCruise) { setMode("measure") }
    BackHandler(enabled = !isCruise && selectedPin != null) { selectedPinId = null }
    BackHandler(enabled = isCruise && cruise.selectedId != null) { cruise.selectedId = null }

    Box(Modifier.fillMaxSize().background(colors.canvas)) {
        MapView(
            center = mapCenter,
            modifier = Modifier.fillMaxSize(),
            initialZoom = DefaultZoom,
            // Base = the built-in layer the Map settings sheet selected
            // (satellite / normal); the user template is an overlay on top,
            // honouring its toggle AND the provider usage-policy
            // acknowledgement (iOS makeOverlayTileCache gate).
            mapType = settings.mapType,
            overlayURLTemplate = if (settings.overlayEnabled && settings.providerUsageAcknowledged) {
                settings.tileURLTemplate
            } else {
                null
            },
            // Imported survey boundary — over the tiles, under app content.
            boundary = importedBoundary,
            // Mode content separation (v3.1): quick pins are measure-only,
            // cruise pins/rings/guides cruise-only — the map itself (camera,
            // zoom, base + overlay) is shared across the toggle.
            // The navigate guide is a line drawn FROM the cruiser: with no
            // position it has no start point, and one drawn from a rejected
            // fix points at the plot from the wrong place. It goes with the
            // you-dot rather than outliving it.
            polylines = if (isCruise) {
                cruiseModePolylines(cruise, usableFix, colors.accent)
            } else {
                emptyList()
            },
            // The sampling plot itself — above the survey boundary and the
            // guide line, below the you-dot and the pins.
            plot = plotOverlay?.overlay,
            markers = if (isCruise) {
                cruiseModeMarkers(cruise, settings, colors)
            } else {
                pins.map { pin ->
                    MapMarker(
                        coordinate = pin.coordinate,
                        title = pin.title,
                        tint = if (pin.warn) colors.confidenceWarn else colors.primary,
                        id = pin.id,
                        shape = MapMarkerShape.PIN,
                        badges = pin.badges,
                        selected = pin.id == selectedPinId,
                    )
                }
            },
            // Tapping the selected pin again deselects it (iOS toggle).
            onMarkerTap = { id ->
                if (isCruise) {
                    cruise.selectedId = if (cruise.selectedId == id) null else id
                } else {
                    selectedPinId = if (selectedPinId == id) null else id
                }
            },
            // A tap ON the drawn plot's boundary raises its small Edit /
            // Remove menu (M2) — the plot's own pin still owns the centre.
            onPlotTap = { id -> cruise.openPlotMenu(id) },
            onMapTap = { if (isCruise) cruise.selectedId = null else selectedPinId = null },
            // PLANNING IS A CRUISE ACT, so the gesture only means anything in
            // cruise mode — measure mode's map carries no plots and no stand
            // to bound, and a menu offering both there would name two things
            // that do not exist in it. iOS gates it the same way.
            onMapLongPress = { coordinate ->
                if (isCruise) cruise.onMapLongPress(coordinate)
            },
            // The you-dot, from the gated fix ONLY. A dot is the map's
            // flattest assertion — "you are here" — so it may never outlive
            // the evidence for it. No usable fix, no dot: the map simply
            // does not say where the cruiser is, which is the truth.
            youLocation = usableFix?.let {
                CoordinateConversions.LatLon(latitude = it.latitude, longitude = it.longitude)
            },
            cameraState = camera,
        )

        // MARK: - Top chrome (mock `.topchrome`)

        Column(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(top = ForestixSpace.xs),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ForestixSpace.xs),
            ) {
                // GPS chip leads, bounded by a flexible slot so its
                // single-line GPS readout truncates instead of shoving
                // the trailing round buttons off-screen. Shared by both modes
                // (the cruise project chip is gone — the project lives in the
                // bottom cluster's PROJECT circle + strip now).
                // THE SAME CHIP the two measurement screens show. It used to
                // be defined in this file, which is how the scan screens
                // ended up with a different GPS widget saying a different
                // thing (FIELD REPORT 11).
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    GpsFixChip(fix)
                }
                // My-location: recentre on the freshest fix (this screen's
                // live service, else the last global fix) without ever
                // zooming OUT past DefaultZoom. Dimmed no-op until any fix exists.
                val locateSnap = fix ?: LocationService.lastGlobalFix
                RoundChromeButton(
                    Icons.Filled.MyLocation,
                    "My location",
                    enabled = locateSnap != null,
                ) {
                    locateSnap?.let {
                        camera.moveTo(
                            CoordinateConversions.LatLon(
                                latitude = it.latitude, longitude = it.longitude),
                            zoom = max(camera.zoom, DefaultZoom),
                        )
                    }
                }
                RoundChromeButton(Icons.Filled.Layers, "Map settings") { mapSettingsOpen = true }
                // Settings — rightmost of the top-right group, both modes.
                RoundChromeButton(Icons.Filled.Settings, "Settings") {
                    nav.navigate(Routes.SETTINGS)
                }
            }
        }

        // The plot's own status line: INSIDE / OUTSIDE (or "no position"),
        // the radius, and the live distance from the centre — all in the
        // cruiser's units.
        plotOverlay?.let { CruisePlotBanner(it, settings, cruise) }

        // The map is waiting for a press: says so. Rides under the plot
        // status banner when there is one, so the two stack rather than
        // overlap (iOS puts them in the same top VStack).
        if (isCruise && (cruise.awaitingPlanPress || cruise.movingPlannedId != null)) {
            CruisePlanPromptBanner(
                cruise,
                topPaddingDp = if (plotOverlay != null) 108.dp else 56.dp,
            )
        }

        // Cruise navigate mode: floating live distance chip riding the
        // dashed guide line's midpoint (mock ⑦ `.distchip`).
        if (isCruise) {
            // Rides the guide line's midpoint and states a distance measured
            // from the cruiser — same evidence, same gate, so chip and line
            // appear and disappear together.
            CruiseDistanceOverlay(cruise, camera, usableFix)
        }

        // MARK: - Bottom: action cluster ①, or peek card ② when a pin is up.
        // Both slide/fade over 0.18 s ease-out like the iOS transitions; the
        // MODE flip crossfades the whole region over the same 0.18 s ease-out.
        // Both modes' clusters are built on the same fixed ClusterSlots
        // geometry, so every circle lands on identical pixels and only the
        // glyphs, fills and caption pills visibly swap.

        // Keep the last selected pin so the card's exit animation has data.
        var lastPin by remember { mutableStateOf<TreePin?>(null) }
        selectedPin?.let { lastPin = it }
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding(),
        ) {
            AnimatedContent(
                targetState = isCruise,
                modifier = Modifier.align(Alignment.BottomCenter),
                contentAlignment = Alignment.BottomCenter,
                transitionSpec = {
                    val spec = tween<Float>(durationMillis = 180, easing = EaseOut)
                    fadeIn(spec) togetherWith fadeOut(spec)
                },
                label = "modeSwap",
            ) { cruiseMode ->
                if (cruiseMode) {
                    CruiseModeBottomContent(
                        state = cruise,
                        nav = nav,
                        fix = fix,
                        onToggleMode = { setMode("measure") },
                    )
                } else {
                    MeasureBottomContent(
                        selectedPin = selectedPin,
                        lastPin = lastPin,
                        settings = settings,
                        plotNameFor = { pin ->
                            pin.entries.firstOrNull()?.plotID
                                ?.let { env.history.plot(it) }
                                ?.takeIf { !it.isDefault }?.name
                        },
                        onToggleMode = { setMode("cruise") },
                        onMeasure = { chooserOpen = true },
                        onLog = { nav.navigate(Routes.FIELD_LOG) },
                        onViewPhoto = { pages -> photoPages = pages },
                        onEditEntry = { editEntry = it },
                        // Which field-log row the peek's "Details" opens.
                        //
                        // The log groups by (plot, tree number) because tree
                        // numbering restarts on each plot; the map groups by
                        // tree number ALONE, so one pin can in principle span
                        // two plots' trees. The row picked is the one the
                        // pin's REPRESENTATIVE reading belongs to — the same
                        // reading "Edit this tree" edits — so the two peek
                        // buttons can never be about different trees. The key
                        // is built by `fieldLogRows`, and this must stay in
                        // step with it.
                        onDetails = { pin ->
                            val entry = pin.entries.first()
                            inspectingRowId = entry.treeNumber
                                ?.let { "t|${entry.plotID?.toString() ?: "-"}|$it" }
                                ?: "e|${entry.id}"
                        },
                        onMeasureAgain = { pin ->
                            val tree = pin.treeNumber
                            if (tree == null) {
                                // Tree-less pin ("New measurement") — plain
                                // chooser on the next free number.
                                chooserOpen = true
                            } else {
                                // Field fix: opening a measurement on a pin
                                // that is far from where the cruiser stands
                                // is usually a mis-tap — confirm past 30 m.
                                val snap = fix ?: LocationService.lastGlobalFix
                                val distM = snap?.let {
                                    GeoMath.distanceM(
                                        it.latitude, it.longitude,
                                        pin.coordinate.latitude, pin.coordinate.longitude,
                                    )
                                }
                                if (distM != null && distM > 30.0) {
                                    farTreeConfirm = tree to distM.roundToInt()
                                } else {
                                    chooserTreeOverride = tree
                                    chooserOpen = true
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    // MARK: - Sheets + photo detail

    if (chooserOpen) {
        // The species already recorded against the tree the peek card scoped
        // to. Read once so the sheet and its provisional flag cannot disagree
        // about where the value came from.
        val scopedSpecies = chooserTreeOverride
            ?.let { env.history.speciesCode(it, env.history.activePlotID.value) }
        MeasureChooserSheet(
            nextTree = env.history.suggestedNextTreeNumber,
            treeOverride = chooserTreeOverride,
            // A tree the peek card scoped to already has its name; only a NEW
            // tree gets the incremented suggestion.
            suggestedName = chooserTreeOverride
                ?.let { env.history.treeName(it, env.history.activePlotID.value) }
                ?: env.history.suggestedNextTreeName,
            suggestedSpecies = scopedSpecies ?: env.history.suggestedNextSpeciesCode,
            suggestedSpeciesConfirmed = scopedSpecies != null,
            onDismiss = {
                chooserOpen = false
                chooserTreeOverride = null
            },
            onChoose = { route, lockTree, name, speciesCode ->
                chooserOpen = false
                // Full / DBH / Height lock the reading to the tree number
                // the sheet header promised (iOS chooserRow actions) — the
                // peek card's override tree when set, else the next free —
                // and to the name and species typed above them.
                if (lockTree) {
                    PendingTreeNumber.set(
                        number = chooserTreeOverride ?: env.history.suggestedNextTreeNumber,
                        name = name,
                        speciesCode = speciesCode,
                    )
                }
                chooserTreeOverride = null
                nav.navigate(route)
            },
        )
    }
    // Far-GPS confirmation — shown before the scoped chooser when the tree's
    // pin is > 30 m from the current fix (peek "Measure this tree" only).
    farTreeConfirm?.let { (tree, metres) ->
        // The alert names the tree the way every other surface does — the
        // cruiser's name when it has one, else "Tree #n".
        val farTreeTitle = TreeLabel.title(
            entries.firstOrNull { it.treeNumber == tree && it.treeName != null }?.treeName,
            tree,
        )
        AlertDialog(
            onDismissRequest = { farTreeConfirm = null },
            title = { Text("$farTreeTitle is $metres m away") },
            text = {
                Text(
                    "Your current GPS position is about $metres m from this " +
                        "tree's pin. Measure it anyway?",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    farTreeConfirm = null
                    chooserTreeOverride = tree
                    chooserOpen = true
                }) { Text("Measure anyway") }
            },
            dismissButton = {
                TextButton(onClick = { farTreeConfirm = null }) { Text("Cancel") }
            },
        )
    }
    if (mapSettingsOpen) {
        MapSettingsSheet(
            camera = camera,
            onDismiss = { mapSettingsOpen = false },
            // The boundary editor needs the whole screen — a map you drag
            // corners on cannot live inside a bottom sheet. Close the sheet
            // and raise it over the home instead.
            onDrawArea = {
                mapSettingsOpen = false
                drawingBoundary = true
            },
        )
    }
    // DRAW AN AREA — full-screen over the home, opening on the camera the
    // cruiser was already looking at so the starting rectangle lands on the
    // ground they had on screen.
    if (drawingBoundary) {
        BoundaryDrawScreen(
            initialCenter = camera.center ?: mapCenter,
            initialZoom = camera.zoom,
            onDismiss = { drawingBoundary = false },
        )
    }
    if (photoPages.isNotEmpty()) {
        PhotoViewerDialog(
            pages = photoPages,
            onDismiss = { photoPages = emptyList() },
        )
    }
    // Quick-edit sheet (map-peek spec item 2): value / species / note +
    // destructive Delete on the tapped reading. Save persists through the
    // history store's update mutator; Delete removes the row + its photo.
    editEntry?.let { entry ->
        QuickEntryEditSheet(
            entry = entry,
            onDismiss = { editEntry = null },
            onSave = { updated ->
                env.history.update(updated)
                editEntry = null
            },
            onDelete = {
                env.history.delete(entry.id)
                editEntry = null
                // The pin may vanish (last reading gone) — drop any selection
                // so a stale peek can't linger over the deleted tree.
                selectedPinId = null
            },
        )
    }

    // Quick-peek "Details" — the SAME sheet the field log opens, not a second
    // copy of it. "Measure again" from inside it lands on the map's own
    // route: close the sheet, hand the scan the tree it must land on (the
    // slot the peek's own tree lock uses) and go.
    inspectingRowId?.let { id ->
        val live = fieldLogRows(entries).firstOrNull { it.id == id }
        ModalBottomSheet(
            onDismissRequest = { inspectingRowId = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = colors.canvas,
        ) {
            if (live == null) {
                // Every reading behind this pin went away while the sheet was
                // open. An empty sheet would read as "this tree has nothing
                // on it" — say what happened instead. Same sentence the field
                // log uses for the same state.
                Text(
                    "Every reading on this row has been deleted.",
                    style = Forestix.type.caption, color = colors.textTertiary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(ForestixSpace.lg),
                )
            } else {
                FieldLogDetailSheet(
                    row = live,
                    unitSystem = settings.unitSystem,
                    developerMode = settings.developerMode,
                    onSave = { env.history.update(it) },
                    onAdd = { env.history.append(it) },
                    onRemeasure = { kind, tree, name, species, truth ->
                        inspectingRowId = null
                        PendingTreeNumber.set(
                            number = tree, name = name, speciesCode = species,
                            replaceExisting = true, truth = truth,
                            plotID = live.entries.firstOrNull()?.plotID)
                        nav.navigate(
                            if (kind == MeasureKind.DBH) Routes.DBH else Routes.HEIGHT)
                    },
                )
            }
        }
    }

    // First-launch UX: the map home hosts the region picker (it is the
    // screen after the splash). Auto-present once; picking, skipping or
    // swipe-dismissing all stamp regionPickerSeen, and it stays reachable
    // later via Settings → Region. Measure mode only — the default mode is
    // measure, so first run always lands here; cruise chrome stays clean.
    if (!isCruise && !settings.regionPickerSeen) {
        ModalBottomSheet(
            onDismissRequest = { env.settings.setRegionPickerSeen(true) },
            containerColor = colors.surface,
        ) {
            LocaleSetupSheet(onDismiss = {
                // Selection/Skip already stamped regionPickerSeen — the
                // state change hides the sheet.
            })
        }
    }

    // MARK: - Cruise-mode sheets (project ⑤ / cruise setup ⑥ / record ⑧)

    if (isCruise) {
        CruiseModeSheets(
            state = cruise,
            nav = nav,
            camera = camera,
            fallbackCentre = mapCenter,
        )
    }

    // Crash-recovery resume prompt. Resume reuses the SAME enter-open-plot
    // mechanism the cruise map uses (current project + active plot pointer +
    // cruise mode), so it lands on that plot's active cruise/tally surface;
    // Discard dismisses without deleting anything (the plot stays open).
    recoveryPrompt?.let { candidates ->
        CrashRecoveryDialog(
            candidates = candidates,
            onResume = {
                val c = candidates.first()
                env.settings.setCruiseProjectId(c.plot.projectId.toString())
                env.settings.setCruisePlotId(c.plot.id.toString())
                env.settings.setMapMode("cruise")
                ForestixLogger.plotOpened(c.plot.id, c.plot.projectId)
                recoveryPrompt = null
            },
            onDiscard = { recoveryPrompt = null },
        )
    }
}

// MARK: - Crash-recovery resume prompt ---------------------------------------

/// Three-option resume prompt (mock: Resume / View / Discard). "View" is
/// folded into the card — the top candidate's plot #, project, live-tree count
/// and relative last-edited time are shown inline so the cruiser can decide
/// before acting. Resume targets the most-recent candidate; when several open
/// plots qualify the rest are summarised so nothing is hidden. Discard deletes
/// nothing — the plot stays open and reachable from the cruise map.
@Composable
private fun CrashRecoveryDialog(
    candidates: List<CrashRecoveryService.ResumeCandidate>,
    onResume: () -> Unit,
    onDiscard: () -> Unit,
) {
    val top = candidates.firstOrNull() ?: return
    val edited = DateUtils.getRelativeTimeSpanString(
        top.lastEditedAt,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
    )
    val treeWord = if (top.liveTreeCount == 1) "tree" else "trees"
    AlertDialog(
        onDismissRequest = onDiscard,
        title = { Text("Resume plot ${top.plot.plotNumber}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(ForestixSpace.xs)) {
                Text(
                    "Plot ${top.plot.plotNumber} · ${top.projectName}\n" +
                        "${top.liveTreeCount} $treeWord · edited $edited",
                )
                if (candidates.size > 1) {
                    val moreWord = if (candidates.size - 1 == 1) "plot" else "plots"
                    Text(
                        "+ ${candidates.size - 1} more open $moreWord from the last 24 hours.",
                        color = Forestix.colors.textSecondary,
                        style = Forestix.type.caption,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onResume) { Text("Resume") } },
        dismissButton = { TextButton(onClick = onDiscard) { Text("Discard") } },
    )
}

// MARK: - Measure-mode bottom region -----------------------------------------

/// The v2 home's cluster ↔ peek swap, byte-identical, lifted into its own
/// composable so the v3.1 mode-flip AnimatedContent can host it as the
/// measure branch.
@Composable
private fun MeasureBottomContent(
    selectedPin: TreePin?,
    lastPin: TreePin?,
    settings: SettingsSnapshot,
    plotNameFor: (TreePin) -> String?,
    onToggleMode: () -> Unit,
    onMeasure: () -> Unit,
    onLog: () -> Unit,
    onViewPhoto: (List<PhotoPage>) -> Unit,
    onEditEntry: (QuickMeasureEntry) -> Unit,
    onDetails: (TreePin) -> Unit,
    onMeasureAgain: (TreePin) -> Unit,
) {
    AnimatedContent(
        targetState = selectedPin != null,
        contentAlignment = Alignment.BottomCenter,
        transitionSpec = {
            val spec = tween<Float>(durationMillis = 180, easing = EaseOut)
            val slide = tween<IntOffset>(durationMillis = 180, easing = EaseOut)
            (slideInVertically(slide) { it } + fadeIn(spec)) togetherWith
                (slideOutVertically(slide) { it } + fadeOut(spec))
        },
        label = "peekCluster",
    ) { showPeek ->
        val pin = if (showPeek) selectedPin ?: lastPin else null
        if (pin == null) {
            ActionCluster(
                modifier = Modifier.padding(bottom = ForestixSpace.sm),
                onToggleMode = onToggleMode,
                onMeasure = onMeasure,
                onLog = onLog,
            )
        } else {
            PeekCard(
                pin = pin,
                unitSystem = settings.unitSystem,
                plotName = plotNameFor(pin),
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 20.dp),
                onViewPhoto = onViewPhoto,
                onEditEntry = onEditEntry,
                onDetails = { onDetails(pin) },
                onMeasureAgain = { onMeasureAgain(pin) },
            )
        }
    }
}

// MARK: - Tree pins ---------------------------------------------------------

/// One map pin: a tree (all readings sharing its number, badges D/H/C) or a
/// single tree-less located reading. `entries` stays newest-first.
private data class TreePin(
    val id: String,
    val coordinate: CoordinateConversions.LatLon,
    val title: String,
    val warn: Boolean,
    val badges: List<String>,
    val entries: List<QuickMeasureEntry>,
    val treeNumber: Int?,
)

private val badgeKinds = listOf(MeasureKind.DBH, MeasureKind.HEIGHT, MeasureKind.CROWN)

private fun kindLetter(kind: MeasureKind): String = when (kind) {
    MeasureKind.DBH -> "D"
    MeasureKind.HEIGHT -> "H"
    MeasureKind.CROWN -> "C"
    MeasureKind.DISTANCE -> "L"
    MeasureKind.SAMPLING_PLOT -> "P"
}

private fun isWarnTier(raw: String) = raw == "yellow" || raw == "red"

/// Entries WITH coords, grouped by treeNumber (one pin per tree, anchored at
/// the newest located reading); tree-less located entries pin individually.
private fun buildTreePins(entries: List<QuickMeasureEntry>): List<TreePin> {
    val located = entries.filter { it.latitude != null && it.longitude != null }
    val pins = mutableListOf<TreePin>()
    located
        .filter { it.treeNumber != null }
        .groupBy { it.treeNumber!! }
        .toSortedMap()
        .forEach { (number, group) ->
            // The peek card shows the whole tree, including readings that
            // were captured without a fix.
            val all = entries.filter { it.treeNumber == number }
            val anchor = group.first() // newest located (entries are newest-first)
            pins += TreePin(
                id = "tree-$number",
                coordinate = CoordinateConversions.LatLon(
                    latitude = anchor.latitude!!, longitude = anchor.longitude!!),
                // The pin says what the cruiser called this tree, shortened
                // to what a 30 dp drop holds — one rule, shared with the
                // cruise pins and both mini-maps.
                title = TreeLabel.pinTitle(
                    name = all.firstNotNullOfOrNull { it.treeName },
                    number = number,
                ),
                warn = all.any { isWarnTier(it.confidenceRaw) },
                badges = badgeKinds.filter { k -> all.any { it.kind == k } }.map(::kindLetter),
                entries = all,
                treeNumber = number,
            )
        }
    located.filter { it.treeNumber == null }.forEach { e ->
        pins += TreePin(
            id = "entry-${e.id}",
            coordinate = CoordinateConversions.LatLon(
                latitude = e.latitude!!, longitude = e.longitude!!),
            title = kindLetter(e.kind),
            warn = isWarnTier(e.confidenceRaw),
            badges = emptyList(),
            entries = listOf(e),
            treeNumber = null,
        )
    }
    return pins
}

// MARK: - Top chrome pieces ---------------------------------------------------

/// Mock `.roundbtn`, sized to the 44 dp hit-target rule, with the shared
/// map-chrome pressed feedback (iOS MapPressableStyle). `enabled = false`
/// renders the 0.45-alpha disabled look and swallows taps.
/// Internal: shared with the cruise-mode map's chrome (v3).
@Composable
internal fun RoundChromeButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = Forestix.colors
    Box(
        Modifier
            .size(44.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .pressableNoRipple(enabled = enabled, onClick = onClick)
            .clip(CircleShape)
            .background(colors.surfaceRaised)
            .border(1.dp, colors.divider, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = colors.textSecondary, modifier = Modifier.size(18.dp))
    }
}

// MARK: - Bottom action cluster (mock `.actioncluster`) -----------------------

/// Fixed slot geometry shared by BOTH mode clusters (v3.1). Every circle's
/// frame derives ONLY from these constants — captions and pills render as
/// NON-MEASURING decoration (fixed-width slots with centre-overflow, a
/// reserved tally zone, a halo painted outside its slot) — so the MEASURE
/// and CRUISE clusters measure pixel-identically and the mode flip
/// crossfades in place instead of re-centring the row (iOS parity).
internal object ClusterSlots {
    /// Side-circle Ø = its slot width.
    val side = 54.dp
    /// (+) circle Ø = its slot width in BOTH modes (the cruise scoped halo
    /// DRAWS outside this slot rather than enlarging it — the Android twin
    /// of the iOS negative-inset overlay).
    val capture = 74.dp
    /// Gap between the three slots.
    val gap = 26.dp
    /// Reserved zone above the (+) where the cruise tally pill floats —
    /// same height when empty (measure mode / no active plot) so the
    /// pill's arrival can never resize or shift the cluster.
    val tallyZone = 48.dp
}

@Composable
private fun ActionCluster(
    modifier: Modifier = Modifier,
    onToggleMode: () -> Unit,
    onMeasure: () -> Unit,
    onLog: () -> Unit,
) {
    val colors = Forestix.colors
    Row(
        modifier,
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(ClusterSlots.gap),
    ) {
        // v3.1: the left circle is the MODE TOGGLE showing the CURRENT mode
        // — tree icon + "MEASURE" here; plot-centre target + "CRUISE" in
        // cruise mode (CruiseModeContent's cluster). Tap flips the map in
        // place.
        SideCircleButton("Measure", Icons.Filled.Park, onClick = onToggleMode)
        CaptureColumn(
            caption = "Measure",
            contentDescription = "New measurement",
            fill = colors.primary,
            ink = MaterialTheme.colorScheme.onPrimary,
            onClick = onMeasure,
        )
        SideCircleButton("Log", Icons.AutoMirrored.Filled.List, onClick = onLog)
    }
}

/// The centre capture column, geometry-invariant across modes: reserved
/// tally zone (fixed height; the cruise pill renders inside it as
/// unbounded-width overflow), the 74 dp (+) slot (the accent-scoped halo
/// paints OUTSIDE the slot via drawBehind, mock `.capture.scoped`), and
/// the caption pill centre-overflowing the slot width. Nothing
/// mode-dependent is ever measured, so the (+) occupies identical screen
/// pixels in measure and cruise mode by construction.
/// Internal: the cruise-mode cluster reuses it for its exact positions.
@Composable
internal fun CaptureColumn(
    caption: String,
    contentDescription: String,
    fill: Color,
    ink: Color,
    haloed: Boolean = false,
    tallyPill: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val colors = Forestix.colors
    val accent = colors.accent
    Column(
        Modifier.width(ClusterSlots.capture),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.height(ClusterSlots.tallyZone).fillMaxWidth(),
            contentAlignment = Alignment.BottomCenter,
        ) {
            if (tallyPill != null) tallyPill()
        }
        Box(
            Modifier
                .size(ClusterSlots.capture)
                // Accent-scoped outline while a plot is active — same ring
                // as the retired 82 dp wrapper box (stroke centreline at
                // slot radius + 3.25 dp), now layout-neutral.
                .drawBehind {
                    if (haloed) {
                        drawCircle(
                            color = accent,
                            radius = size.minDimension / 2f + 3.25.dp.toPx(),
                            style = Stroke(width = 1.5.dp.toPx()),
                        )
                    }
                }
                .pressableNoRipple(onClick = onClick)
                .softDropShadow(Color.Black.copy(alpha = 0.28f), 10.dp, 6.dp)
                .clip(CircleShape)
                .background(fill)
                .border(4.dp, colors.surface, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Add,
                contentDescription = contentDescription,
                tint = ink,
                modifier = Modifier.size(30.dp),
            )
        }
        ClusterLabel(caption, gap = 6.dp)
    }
}

/// 54 dp side circle + caption pill. `tint` overrides the glyph ink — the
/// cruise cluster tints its mode-toggle ring in the cruise accent (v3.1).
/// Internal: the cruise-mode cluster reuses it for its exact positions.
@Composable
internal fun SideCircleButton(
    label: String,
    icon: ImageVector,
    tint: Color? = null,
    onClick: () -> Unit,
) {
    val colors = Forestix.colors
    Column(
        // Fixed slot: the caption pill centre-overflows this width, so
        // "MEASURE" vs "CRUISE" can never re-measure the cluster.
        Modifier.width(ClusterSlots.side),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(ClusterSlots.side)
                .pressableNoRipple(onClick = onClick)
                .softDropShadow(Color.Black.copy(alpha = 0.18f), 6.dp, 3.dp)
                .clip(CircleShape)
                .background(colors.surface)
                .border(1.dp, colors.divider, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = tint ?: colors.textPrimary,
                modifier = Modifier.size(22.dp),
            )
        }
        ClusterLabel(label, gap = 5.dp)
    }
}

/// Field fix: plain theme-tinted text vanished over satellite imagery, so
/// each label sits in a small dark-glass pill. Deliberately hardcoded —
/// the backdrop is a satellite photo in BOTH themes. Gap above: 6 under
/// the capture button, 5 under the side circles (iOS spacing).
/// Internal: the cruise map's morphing (+) label reuses it (v3).
@Composable
internal fun ClusterLabel(label: String, gap: Dp) {
    Text(
        label.uppercase(),
        style = Forestix.type.dataSmall.copy(
            fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp),
        color = Color(0xFFF2F5F3),
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .padding(top = gap)
            // Centre-overflow: the pill measures unbounded, reports the
            // incoming fixed-slot width, and spills symmetrically — so
            // caption text NEVER affects the cluster's geometry (v3.1
            // mode-invariance).
            .wrapContentWidth(unbounded = true)
            .clip(RoundedCornerShape(5.dp))
            .background(Color(0xA606090A))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

// MARK: - Peek card (mock `.peek`) --------------------------------------------

@Composable
private fun PeekCard(
    pin: TreePin,
    unitSystem: UnitSystem,
    plotName: String?,
    modifier: Modifier = Modifier,
    onViewPhoto: (List<PhotoPage>) -> Unit,
    onEditEntry: (QuickMeasureEntry) -> Unit,
    onDetails: () -> Unit,
    onMeasureAgain: () -> Unit,
) {
    val colors = Forestix.colors
    val type = Forestix.type
    val shape = RoundedCornerShape(14.dp)
    val newest = pin.entries.first()
    val species = pin.entries.firstNotNullOfOrNull { it.speciesCode }
    // The cruiser's name if this tree has one — same rule as the field log's
    // TREE column, so the two surfaces call the tree one thing.
    val title = (pin.entries.firstNotNullOfOrNull { it.treeName }
        ?: pin.treeNumber?.let { "Tree $it" } ?: rowLabel(newest)) +
        (species?.takeIf { it.isNotBlank() }
            ?.let { " · ${RegionalSpecies.nameForCode(it)}" } ?: "")
    // Date, plus the plot name when the reading isn't on the default plot
    // (iOS peekSubtitle: "7 Jul · 09:41 · Plot 2").
    val subtitle = listOfNotNull(dateLine(newest.createdAt), plotName).joinToString(" · ")
    // EVERY photo on this tree, in the order the readings were taken — a
    // Full measurement leaves a diameter frame and a height frame, and the
    // thumbnail used to be a dead end at whichever one was newest.
    val photoPages = measurePhotoPages(pin.entries, unitSystem)

    Column(
        modifier
            .fillMaxWidth()
            .softDropShadow(Color.Black.copy(alpha = 0.22f), 14.dp, (-4).dp, cornerRadius = 14.dp)
            .clip(shape)
            .background(colors.surface)
            .border(1.dp, colors.divider, shape)
            .padding(14.dp),
    ) {
        // Grabber
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
                title,
                style = type.bodyBold.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold),
                color = colors.textPrimary,
                modifier = Modifier.weight(1f).alignByBaseline(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = type.dataSmall.copy(fontSize = 11.sp),
                color = colors.textTertiary,
                modifier = Modifier.alignByBaseline(),
                maxLines = 1,
            )
        }
        Spacer(Modifier.size(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Tapping the thumbnail opens the existing full-screen viewer
            // (map-peek spec item 2 — the retired "View photo" button's job).
            PhotoThumb(
                photoPages.firstOrNull()?.photoPath, photoPages.size,
                onClick = photoPages.takeIf { it.isNotEmpty() }
                    ?.let { pages -> { onViewPhoto(pages) } },
            )
            Column(Modifier.weight(1f)) {
                val rows = latestPerKind(pin.entries)
                rows.forEachIndexed { i, entry ->
                    MeasureRow(entry, unitSystem)
                    if (i < rows.lastIndex) HorizontalDivider(color = colors.divider, thickness = 0.5.dp)
                }
            }
        }
        Spacer(Modifier.size(12.dp))
        // DETAILS — the whole record behind this pin, on the SAME surface the
        // field log opens (`FieldLogDetailSheet`): species, stem position,
        // damage, note, ±sigma, where and when it was recorded, every photo.
        // The word is "Details" because that is what the map's plot peek
        // already calls its equivalent step; a second word for one idea is
        // how a map ends up with two.
        //
        // Full width above the pair rather than a third button in the row:
        // three 44 dp controls across a phone leave no label room.
        PeekActionButton(
            "Details", primary = false, modifier = Modifier.fillMaxWidth(),
            onClick = onDetails,
        )
        Spacer(Modifier.size(ForestixSpace.xs))
        Row(horizontalArrangement = Arrangement.spacedBy(ForestixSpace.xs)) {
            // "Edit this tree" replaces the redundant "View photo" (the thumb
            // now opens the viewer): a compact edit sheet on the newest
            // reading — value / species / note + destructive Delete.
            PeekActionButton(
                "Edit this tree", primary = false,
                modifier = Modifier.weight(1f),
            ) { onEditEntry(newest) }
            PeekActionButton(
                if (pin.treeNumber != null) "Measure this tree" else "New measurement",
                primary = true, modifier = Modifier.weight(1f),
                onClick = onMeasureAgain,
            )
        }
    }
}

/// Newest reading per kind, in the mock's row order (DBH, Height, Crown,
/// then distance / plot readings when the group has them).
private fun latestPerKind(entries: List<QuickMeasureEntry>): List<QuickMeasureEntry> {
    val order = listOf(
        MeasureKind.DBH, MeasureKind.HEIGHT, MeasureKind.CROWN,
        MeasureKind.DISTANCE, MeasureKind.SAMPLING_PLOT,
    )
    return order.mapNotNull { kind -> entries.firstOrNull { it.kind == kind } }
}

@Composable
private fun MeasureRow(entry: QuickMeasureEntry, unitSystem: UnitSystem) {
    val colors = Forestix.colors
    val type = Forestix.type
    Row(
        Modifier.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ForestixSpace.xs),
    ) {
        Text(
            rowLabel(entry).uppercase(),
            style = type.caption.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.7.sp),
            color = colors.textTertiary,
            modifier = Modifier.width(52.dp),
        )
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            // ±σ intentionally dropped from the peek metric row (map-peek
            // spec item 1) — the value stands alone; sigma still ships to
            // storage / CSV / FieldLog and the full-screen photo viewer.
            Text(
                valueText(entry, unitSystem),
                style = type.dataSmall.copy(fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold),
                color = colors.textPrimary,
            )
        }
        TierChipSoft(entry.confidenceRaw, explains = tierExplainerKind(entry.kind))
    }
}

/// Which explainer a peek row's chip opens. Null where nothing in the
/// confidence framework graded the reading.
internal fun tierExplainerKind(kind: MeasureKind): TierExplainerKind? = when (kind) {
    MeasureKind.DBH -> TierExplainerKind.DIAMETER
    MeasureKind.HEIGHT -> TierExplainerKind.HEIGHT
    else -> null
}

/// Mock `.chip` — soft tier-coloured background, dot + uppercase label.
/// Internal: the cruise tree/plot peeks reuse it (v3).
///
/// Pass `explains` and the chip becomes tappable, opening the confidence
/// explainer for that measurement. "Good" / "Fair" with no stated criteria
/// reads as a mood; the sheet is what makes it a criterion. It is left null
/// where the grade is not one an estimator computed (crown, distance, plot),
/// because a sheet describing diameter checks over a plot radius would be a
/// worse answer than no sheet.
///
/// The sheet's open state lives HERE rather than in each host screen: it is
/// a ModalBottomSheet, it needs nothing from the host, and one state per
/// chip is what keeps the two peeks from having to agree about it.
@Composable
internal fun TierChipSoft(rawTier: String, explains: TierExplainerKind? = null) {
    val d = confidenceDescriptor(rawTier)
    var explaining by remember { mutableStateOf(false) }
    if (explaining) {
        TierExplainerSheet(explains ?: TierExplainerKind.DIAMETER) { explaining = false }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .clip(ForestixRadius.chip)
            .background(d.color.copy(alpha = 0.12f))
            .then(
                if (explains != null) {
                    Modifier
                        .clickableNoRipple { explaining = true }
                        .semantics { contentDescription = "What this grade means" }
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(d.color))
        Text(
            d.label.uppercase(),
            style = Forestix.type.caption.copy(
                fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp),
            color = d.color,
        )
    }
}

/// 96 dp thumbnail: the entry's auto-captured accept snapshot, a grey
/// placeholder when the group has none, and a ×K overlay for extras.
/// Internal: the cruise tree peek reuses it (v3).
@Composable
internal fun PhotoThumb(
    photoName: String?,
    photoCount: Int,
    onClick: (() -> Unit)? = null,
) {
    val colors = Forestix.colors
    // LocalContext, not an Activity threaded down from the screen root. The
    // store resolves against any Context, and this composable is reused
    // inside sheets and dialogs where LocalContext is a ContextThemeWrapper.
    val context = LocalContext.current
    val thumb by produceState<Bitmap?>(initialValue = null, photoName, context) {
        value = photoName?.let { MeasurePhotoStore.loadBitmap(context, it, targetPx = 256) }
    }
    Box(
        Modifier
            .size(96.dp)
            .clip(ForestixRadius.card)
            // Tappable only when it actually owns a photo to open.
            .then(
                if (onClick != null && photoName != null) {
                    Modifier.clickableNoRipple(onClick)
                } else {
                    Modifier
                }
            )
            .background(colors.surfaceRaised)
            .border(1.dp, colors.divider, ForestixRadius.card),
    ) {
        val bitmap = thumb
        if (bitmap != null) {
            Image(
                bitmap.asImageBitmap(),
                contentDescription = "Measurement photo",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                Icons.Filled.Image,
                contentDescription = if (photoName == null) "No photo" else "Photo loading",
                tint = colors.textTertiary,
                modifier = Modifier.align(Alignment.Center).size(22.dp),
            )
        }
        if (photoCount > 1) {
            Text(
                "×$photoCount",
                style = Forestix.type.dataSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                color = Color(0xFFF2F5F3),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xB306090A))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            )
        }
    }
}

/// Internal: the cruise peeks reuse the same action button (v3).
@Composable
internal fun PeekActionButton(
    label: String,
    primary: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = Forestix.colors
    val shape = ForestixRadius.control
    Box(
        modifier
            .heightIn(min = 44.dp)
            .pressableNoRipple(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(shape)
            .then(
                if (primary) Modifier.background(colors.primary)
                else Modifier.border(1.dp, colors.divider, shape)
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = Forestix.type.bodyBold.copy(fontSize = 14.sp),
            color = if (primary) MaterialTheme.colorScheme.onPrimary else colors.textPrimary,
            modifier = Modifier.padding(horizontal = ForestixSpace.xs),
        )
    }
}

// MARK: - Measure chooser sheet (mock ③) --------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MeasureChooserSheet(
    nextTree: Int,
    /// Non-null when the peek card scoped the sheet to an existing tree —
    /// the header drops "(NEXT)" and the tree-bound rows lock to it.
    treeOverride: Int? = null,
    /// Name the tree-name field opens on: the tree's existing name when the
    /// peek card scoped this sheet, else the auto-incremented successor of the
    /// last name in the log. Null / blank starts the field empty.
    suggestedName: String? = null,
    /// Species the picker opens on — the scoped tree's own recorded species,
    /// else the last species seen anywhere in the log. Null opens it unset.
    suggestedSpecies: String? = null,
    /// True when [suggestedSpecies] came off the scoped tree's own readings, so
    /// somebody already recorded it against this stem. False when it is the
    /// log-wide carry-over, which is a guess about the tree in front of the
    /// cruiser and is drawn provisional until they pick.
    suggestedSpeciesConfirmed: Boolean = false,
    onDismiss: () -> Unit,
    /// (route, lockTree, name, speciesCode) — lockTree is true for the
    /// tree-bound rows (Full / DBH / Height), which pin the reading to
    /// `treeOverride` when set, else `nextTree`. Name and species ride along
    /// only for those rows; a distance or a plot belongs to no tree.
    onChoose: (String, Boolean, String?, String?) -> Unit,
) {
    val colors = Forestix.colors
    val type = Forestix.type
    // Named BEFORE the scan, not after: the cruiser is standing at the tree
    // when they open this sheet, and typing its tag then is one action rather
    // than a second trip through the details sheet once the number is already
    // recorded.
    var treeName by remember(suggestedName) { mutableStateOf(suggestedName.orEmpty()) }
    // Species used to start unset, on the grounds that it must not be inherited
    // by accident — but in one stand it is the same species tree after tree, so
    // that made it the most retyped field in the app. It is inherited now and
    // marked provisional instead, which shows the inheritance rather than
    // hiding it. `speciesConfirmed` drives that styling and nothing else: the
    // code is stored either way, because the sheet showed it and this app does
    // not silently drop what it showed. See the iOS sibling's
    // `suggestedSpecies` for the full argument.
    var speciesCode by remember(suggestedSpecies) { mutableStateOf(suggestedSpecies) }
    var speciesConfirmed by remember(suggestedSpecies) {
        mutableStateOf(suggestedSpeciesConfirmed)
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.surface) {
        Column(
            Modifier
                .padding(horizontal = ForestixSpace.md)
                .padding(bottom = ForestixSpace.xl)
                .imePadding(),
        ) {
            Text(
                treeOverride?.let { "MEASURE · TREE $it" }
                    ?: "MEASURE · TREE $nextTree (NEXT)",
                style = type.sectionHead.copy(
                    fontWeight = FontWeight.ExtraBold, letterSpacing = 1.0.sp),
                color = colors.textTertiary,
                modifier = Modifier.padding(bottom = ForestixSpace.xs),
            )
            Row(
                Modifier.fillMaxWidth().padding(bottom = ForestixSpace.sm),
                horizontalArrangement = Arrangement.spacedBy(ForestixSpace.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The placeholder is an EXAMPLE, not the words "Tree name": the
                // shape of the first name decides whether the app can name the
                // rest of the stand, because [TreeNameSequence] only steps on a
                // TRAILING NUMBER. "Big oak" comes back unchanged and the
                // cruiser retypes every tree. Same string on both platforms.
                OutlinedTextField(
                    value = treeName,
                    onValueChange = { treeName = it },
                    placeholder = { Text("e.g. Tree1") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                // The same control the reading-details sheet uses — one
                // species list, one typed-code escape, no second copy to
                // drift.
                SpeciesPickerField(
                    speciesCode = speciesCode,
                    onSpeciesCode = {
                        speciesCode = it
                        // Any pick makes it definite, including re-picking the
                        // code already showing — that IS the confirmation.
                        speciesConfirmed = true
                    },
                    unspecifiedLabel = "Species",
                    bordered = true,
                    provisional = !speciesConfirmed,
                )
            }
            // Field fix: the chained DBH → Height capture is the common
            // whole-tree workflow, so it leads the sheet (emphasised icon
            // tile). "dbh?chain=true" tells DBH Accept to jump straight to
            // Height on the same tree instead of the continuation dialog.
            ChoiceRow(
                Icons.Filled.Park,
                "Full measurement",
                "DBH → Height, one tree",
                emphasized = true,
            ) { onChoose("${Routes.DBH}?chain=true", true, treeName, speciesCode) }
            HorizontalDivider(color = colors.divider, thickness = 0.5.dp)
            ChoiceRow(Icons.Filled.Straighten, "Diameter (DBH)", "Scan the trunk with the camera") {
                onChoose(Routes.DBH, true, treeName, speciesCode)
            }
            HorizontalDivider(color = colors.divider, thickness = 0.5.dp)
            ChoiceRow(Icons.Filled.Height, "Height", "Walk back, aim at the base and the top") {
                onChoose(Routes.HEIGHT, true, treeName, speciesCode)
            }
            HorizontalDivider(color = colors.divider, thickness = 0.5.dp)
            ChoiceRow(Icons.Filled.SwapHoriz, "Distance", "Point at a target, or tap two points") {
                onChoose(Routes.DISTANCE, false, null, null)
            }
            HorizontalDivider(color = colors.divider, thickness = 0.5.dp)
            ChoiceRow(Icons.Filled.CenterFocusWeak, "Sampling plot", "Centre stake · boundary ring") {
                onChoose(Routes.SAMPLING, false, null, null)
            }
        }
    }
}

/// One chooser row. `emphasized` (the Full measurement row) inverts the
/// icon tile — solid primary + ink glyph, same treatment as the big (+)
/// capture button — while the row itself stays plain like its siblings
/// (iOS chooserRow).
@Composable
private fun ChoiceRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    emphasized: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = Forestix.colors
    val type = Forestix.type
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .pressableNoRipple(onClick = onClick)
            .padding(vertical = 13.dp, horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ForestixSpace.sm),
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(if (emphasized) colors.primary else colors.primaryMuted),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (emphasized) MaterialTheme.colorScheme.onPrimary else colors.primary,
                modifier = Modifier.size(22.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = type.bodyBold.copy(fontSize = 15.5.sp, fontWeight = FontWeight.Bold),
                color = colors.textPrimary,
            )
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

// MARK: - Photo detail (mock ⑤) ------------------------------------------------

/// One page of the photo viewer: the frame, and the caption saying what it
/// belongs to. A photo with no caption is half a record — with two frames on
/// one tree the cruiser cannot otherwise tell the diameter frame from the
/// height frame.
internal data class PhotoPage(
    /// Filename inside the MeasurePhotoStore directory.
    val photoPath: String,
    val headline: String,
    val detail: String?,
    /// TREE / METHOD / GPS cells. Empty draws no meta row at all.
    val meta: List<Pair<String, String>>,
    val caption: PhotoCaption,
)

/// How a page's caption reads.
internal enum class PhotoCaption {
    /// A quick-measure reading's own frame: the kind and the value in
    /// figures ("DBH 32.4 cm"), then σ and the confidence word.
    READING,

    /// A cruise tree's photo. The Tree row keeps ONE photo path and does not
    /// record which leg of the measurement it came off, so the page is
    /// labelled with the tree rather than with a reading it cannot honestly
    /// claim.
    TREE,
}

/// Every photo attached to a group of readings, IN THE ORDER THEY WERE
/// TAKEN. The history hands entries back newest-first, which would have
/// paged a Full measurement height-then-diameter — backwards from the way
/// the cruiser shot them.
@Composable
internal fun measurePhotoPages(
    entries: List<QuickMeasureEntry>,
    system: UnitSystem,
): List<PhotoPage> = entries
    .sortedBy { it.createdAt }
    .mapNotNull { entry ->
        // A reading that was never photographed has no page; an empty path
        // would open a black screen.
        entry.photoPath?.let { path -> readingPhotoPage(entry, path, system) }
    }

@Composable
private fun readingPhotoPage(
    entry: QuickMeasureEntry,
    photoPath: String,
    system: UnitSystem,
): PhotoPage {
    val tier = confidenceDescriptor(entry.confidenceRaw)
    val tree = listOfNotNull(
        // The caption strip has room for the whole name, so it prints the
        // full title rather than the pin's shortened form.
        entry.treeNumber?.let { TreeLabel.title(entry.treeName, it) },
        entry.speciesCode?.takeIf { it.isNotEmpty() }
            ?.let { RegionalSpecies.nameForCode(it) },
    ).joinToString(" · ").ifEmpty { "—" }
    // The entry stores the fix itself (not its accuracy), so the GPS cell
    // shows the coordinates the reading was anchored to.
    val gps = if (entry.latitude != null && entry.longitude != null) {
        String.format(Locale.US, "%.5f, %.5f", entry.latitude, entry.longitude)
    } else {
        "—"
    }
    return PhotoPage(
        photoPath = photoPath,
        headline = bigValueText(entry, system),
        detail = listOfNotNull(sigmaText(entry, system), tier.label).joinToString(" · "),
        meta = listOf("TREE" to tree, "METHOD" to methodLabel(entry.method), "GPS" to gps),
        caption = PhotoCaption.READING,
    )
}

/// A cruise tree's single Accept snapshot — see [PhotoCaption.TREE] for why
/// it is not labelled with a diameter or a height.
internal fun cruiseTreePhotoPages(
    photoPath: String?,
    title: String,
    subtitle: String,
): List<PhotoPage> = if (photoPath == null) {
    emptyList()
} else {
    listOf(PhotoPage(photoPath, title, subtitle, emptyList(), PhotoCaption.TREE))
}

/// THE full-screen photo viewer — one dialog, every surface.
///
/// The map peek, the field-log detail sheet and the cruise tree peek all
/// open THIS. They had a viewer each before, and none of them could reach
/// past the first frame: a Full measurement records a diameter AND a height,
/// each with its own Accept snapshot, so the second photo sat on the tree
/// with no way in. One viewer is what stops the gesture, the caption and the
/// chrome drifting apart between the three again.
///
/// PAGING STAYS INVISIBLE UNTIL THERE IS SOMEWHERE TO PAGE TO. A tree with
/// one photo renders exactly as it did before — no counter, nothing new.
///
/// Deliberately dark in both app appearances, matching the mock's photo
/// screens (iOS MeasurePhotoDetailView).
@Composable
internal fun PhotoViewerDialog(
    pages: List<PhotoPage>,
    onDismiss: () -> Unit,
    startIndex: Int = 0,
) {
    if (pages.isEmpty()) return
    val type = Forestix.type
    val ink = Color(0xFFF2F5F3)
    val pagerState = rememberPagerState(
        initialPage = startIndex.coerceIn(0, pages.lastIndex),
        pageCount = { pages.size },
    )
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color(0xFF0A0D0B))) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { index ->
                PhotoViewerPage(pages[index])
            }
            // "1 / 2" — says there IS a second photo and which one is on
            // screen. Drawn only from two photos up; one photo gets no
            // counter, and the screen is what it always was.
            if (pages.size > 1) {
                val position = "${pagerState.currentPage + 1} / ${pages.size}"
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .statusBarsPadding()
                        .padding(start = 14.dp)
                        .height(44.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        position,
                        style = type.dataSmall.copy(
                            fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                        color = ink,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color(0xB306090A))
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                            .semantics {
                                contentDescription =
                                    "Photo ${pagerState.currentPage + 1} of ${pages.size}"
                            },
                    )
                }
            }
            // Close — 44 dp dark-glass circle flush to the status-bar
            // inset, trailing 14 (iOS xmark button).
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(end = 14.dp)
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color(0xB306090A))
                    .clickableNoRipple(onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Close photo",
                    tint = ink,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/// One page: the photo itself plus its caption strip. Each page decodes its
/// own file, so paging never shows the previous photo under the next one's
/// caption.
@Composable
private fun PhotoViewerPage(page: PhotoPage) {
    val type = Forestix.type
    val ink = Color(0xFFF2F5F3)
    val inkDim = Color(0xFFA5AEA8)
    // Spinner while the JPEG decodes; "Photo unavailable" only once the
    // decode has actually failed (iOS ProgressView behaviour).
    var loadFailed by remember(page.photoPath) { mutableStateOf(false) }
    // This page is composed inside a Dialog, so LocalContext here is the
    // dialog's ContextThemeWrapper — fine, because the store resolves
    // against a Context and no longer asks anyone for an Activity.
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, page.photoPath, context) {
        val decoded = MeasurePhotoStore.loadBitmap(context, page.photoPath, targetPx = 1600)
        value = decoded
        if (decoded == null) loadFailed = true
    }
    Box(Modifier.fillMaxSize()) {
        val image = bitmap
        when {
            image != null -> Image(
                image.asImageBitmap(),
                contentDescription = "Measurement photo",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            !loadFailed -> CircularProgressIndicator(
                color = ink,
                modifier = Modifier.align(Alignment.Center),
            )
            else -> Text(
                "Photo unavailable",
                style = type.body,
                color = Color(0xFF79837D),
                modifier = Modifier.align(Alignment.Center),
            )
        }
        // Bottom meta strip (mock `.photofull .meta`) over a vertical fade
        // into near-black.
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color(0xEB06090A)),
                    ),
                )
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, top = 40.dp, bottom = 30.dp),
        ) {
            // A reading leads with its number, so the value and its σ sit on
            // one baseline in figures. A cruise tree leads with the tree's
            // name, which is prose and stacks above its date line.
            when (page.caption) {
                PhotoCaption.READING -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        page.headline,
                        style = type.dataLarge.copy(
                            fontSize = 30.sp, fontWeight = FontWeight.ExtraBold),
                        color = ink,
                        modifier = Modifier.alignByBaseline(),
                    )
                    Text(
                        page.detail ?: "",
                        style = type.dataSmall,
                        color = inkDim,
                        modifier = Modifier.alignByBaseline(),
                    )
                }
                PhotoCaption.TREE -> {
                    Text(
                        page.headline,
                        style = type.bodyBold.copy(
                            fontSize = 17.sp, fontWeight = FontWeight.ExtraBold),
                        color = ink,
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        page.detail ?: "",
                        style = type.dataSmall.copy(fontSize = 12.sp),
                        color = inkDim,
                    )
                }
            }
            if (page.meta.isNotEmpty()) {
                Row(
                    Modifier.padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    page.meta.forEach { (label, value) -> MetaCell(label, value) }
                }
            }
        }
    }
}

/// "DBH 32.4 cm" / "Height 18.2 m" — the viewer's headline value, labelled
/// with the same words the peek card the cruiser arrived from uses (it used
/// to read "Ø" and "H": a drafting symbol and a formula variable).
private fun bigValueText(e: QuickMeasureEntry, system: UnitSystem): String {
    val prefix = when (e.kind) {
        MeasureKind.DBH -> "DBH "
        MeasureKind.HEIGHT -> "Height "
        else -> ""
    }
    return prefix + valueText(e, system)
}

/// The stored `method` raw value as a phrase a cruiser can read.
///
/// The meta strip used to print the raw identifier — "lidarChordSilhouette",
/// "vioWalkoffTangent", "two-point.arcore". Those strings exist so the CSV
/// and the two platforms' exports join; they are not English. The raw value
/// is UNCHANGED on the record and in every export — only the display is
/// mapped. Unknown values fall back to a neutral word rather than leaking a
/// new identifier onto the screen.
private fun methodLabel(raw: String): String = when {
    raw.startsWith("lidar") -> "Trunk scan"
    raw.startsWith("manual") -> "Typed in"
    raw == "vioWalkoffTangent" -> "Walk-back sighting"
    raw == "tapeTangent" -> "Tape and angle"
    raw == "imputedHD" -> "Estimated from the height curve"
    raw.startsWith("ar.crown") -> "Crown span"
    raw.startsWith("ar.tap") -> "Tapped on screen"
    raw.startsWith("live.") -> "Pointed at a target"
    raw.startsWith("two-point") -> "Two points"
    raw.isEmpty() -> "—"
    else -> "Measured"
}

@Composable
private fun MetaCell(label: String, value: String) {
    val type = Forestix.type
    Column {
        Text(
            label,
            style = type.dataSmall.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Normal),
            color = Color(0xFFB7C0BA),
        )
        Text(
            value,
            style = type.dataSmall.copy(fontWeight = FontWeight.Bold),
            color = Color(0xFFF2F5F3),
            modifier = Modifier.padding(top = 1.dp),
            maxLines = 1,
        )
    }
}

// MARK: - Quick-entry edit sheet (map-peek spec item 2) ------------------------

/// Compact editor raised from the quick peek's "Edit this tree": the measured
/// value (native cm/m), species code, and note for ONE reading, plus a
/// destructive Delete behind an AlertDialog confirm (removes the row + its
/// photo via the history store). Measurement math is untouched — only the
/// stored value and labels change.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickEntryEditSheet(
    entry: QuickMeasureEntry,
    onDismiss: () -> Unit,
    onSave: (QuickMeasureEntry) -> Unit,
    onDelete: () -> Unit,
) {
    val colors = Forestix.colors
    val type = Forestix.type
    // The text the value field opens with: the stored reading at the SAME
    // precision the row above it prints — one decimal for a diameter or a
    // height, two for a distance — so the peek and its editor never show one
    // reading as two numbers. iOS builds the identical string. `save` compares
    // the field against this STRING, which is what tells an untouched form
    // from a retyped one.
    val valuePrefill = MeasurementFormatter.entryText(
        entry.value, if (entry.kind == MeasureKind.DISTANCE) 2 else 1)
    var valueField by remember(entry.id) { mutableStateOf(valuePrefill) }
    // False when the cruiser has typed something that is not a usable reading.
    // Save is held off and the field says why, rather than the sheet quietly
    // keeping the old number and closing.
    val valueEntryValid =
        valueField == valuePrefill || TruthInput.parsePositive(valueField) != null
    var species by remember(entry.id) { mutableStateOf(entry.speciesCode ?: "") }
    var note by remember(entry.id) { mutableStateOf(entry.note ?: "") }
    var confirmDelete by remember(entry.id) { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.surface) {
        Column(
            Modifier
                .padding(horizontal = ForestixSpace.md)
                .padding(bottom = ForestixSpace.xl),
            verticalArrangement = Arrangement.spacedBy(ForestixSpace.sm),
        ) {
            Text(
                // A named stem is headed by its name, not by the number the
                // cruiser stopped using the moment they named it. The name
                // is NOT uppercased — it is the cruiser's own word, and
                // "STARKER32" is not what they typed. Unnamed rows keep the
                // header they have always had.
                entry.treeName?.takeIf { it.isNotEmpty() }?.let { "EDIT · $it" }
                    ?: entry.treeNumber?.let { "EDIT · TREE $it" }
                    ?: "EDIT · ${rowLabel(entry).uppercase(Locale.US)}",
                style = type.sectionHead.copy(
                    fontWeight = FontWeight.ExtraBold, letterSpacing = 1.0.sp),
                color = colors.textTertiary,
            )
            // Measured value — edited in native units (cm for DBH, m else),
            // matching storage; the display unit system doesn't apply here.
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = valueField,
                    onValueChange = { valueField = it },
                    label = { Text(rowLabel(entry)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(entry.valueUnit, style = type.body, color = colors.textSecondary)
            }
            if (!valueEntryValid) {
                // Diameter and height reuse the field log's sentences word for
                // word; the other kinds get the same sentence about a reading.
                Text(
                    when (entry.kind) {
                        MeasureKind.DBH ->
                            "A typed diameter must be a number greater than zero."
                        MeasureKind.HEIGHT ->
                            "A typed height must be a number greater than zero."
                        else ->
                            "A typed reading must be a number greater than zero."
                    },
                    style = type.body.copy(fontSize = 13.sp),
                    color = colors.confidenceBad,
                )
            }
            OutlinedTextField(
                value = species,
                onValueChange = { species = it },
                label = { Text("Species code") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters),
                modifier = Modifier.fillMaxWidth(),
            )
            // Read-only resolution of the typed code → common name, so the
            // cruiser can confirm what "DF" maps to. Hidden for blank or
            // free-typed codes that don't resolve to a preset.
            val resolvedSpecies = RegionalSpecies.nameForCode(species)
            if (species.isNotBlank() && !resolvedSpecies.equals(species.trim(), ignoreCase = true)) {
                Text(
                    resolvedSpecies,
                    style = type.body.copy(fontSize = 13.sp),
                    color = colors.textSecondary,
                )
            }
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("Note") },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
            PeekActionButton(
                "Save changes",
                primary = true,
                enabled = valueEntryValid,
                modifier = Modifier.fillMaxWidth(),
            ) {
                // A value the cruiser retyped is a TYPED reading from here on:
                // it keeps neither the sensor's sigma nor its edge provenance.
                // Carrying those across left a hand-entered number wearing the
                // precision of a measurement it has nothing to do with.
                //
                // "Retyped" is decided on the TEXT against the prefill, not on
                // the numbers. The prefill is the reading ROUNDED for display,
                // so a sensor value of 18.274 opened as "18.3" and every
                // numeric test — the old 0.001 tolerance included, at any
                // tolerance — called that a change: opening the sheet and
                // pressing Save rounded the reading AND demoted it to a hand
                // entry with no sigma. Comparing the strings is the only test
                // an untouched form passes.
                val base =
                    if (valueField == valuePrefill) entry
                    else TruthInput.parsePositive(valueField)
                        ?.let { entry.typedValue(it) } ?: entry
                onSave(
                    base.copy(
                        speciesCode = species.trim().ifEmpty { null },
                        note = note.trim().ifEmpty { null },
                    ),
                )
            }
            // Destructive Delete — red-outlined, confirmed before it fires.
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
                    "Delete",
                    style = type.bodyBold.copy(fontSize = 14.sp),
                    color = colors.confidenceBad,
                )
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this reading?") },
            text = {
                Text("This removes the measurement and its photo. This cannot be undone.")
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

// MARK: - Formatting (mirrors FieldLogScreen's row switches) -------------------

private fun rowLabel(e: QuickMeasureEntry) = when (e.kind) {
    MeasureKind.DBH -> "DBH"
    MeasureKind.HEIGHT -> "Height"
    MeasureKind.CROWN -> "Crown"
    MeasureKind.DISTANCE -> "Dist"
    MeasureKind.SAMPLING_PLOT -> "Plot"
}

private fun valueText(e: QuickMeasureEntry, system: UnitSystem): String = when (e.kind) {
    MeasureKind.DBH -> MeasurementFormatter.diameter(e.value, system)
    MeasureKind.HEIGHT -> MeasurementFormatter.height(e.value, system)
    MeasureKind.CROWN -> String.format(Locale.US, "%.1f × %.1f m", e.value, e.secondaryValue ?: 0.0)
    // Unit-aware shared formatter (metric keeps the <1 m cm branch).
    MeasureKind.DISTANCE -> MeasurementFormatter.distance(e.value, system)
    MeasureKind.SAMPLING_PLOT -> {
        val area = e.secondaryValue ?: (PI * e.value * e.value)
        String.format(Locale.US, "%.1f m radius · %.0f m²", e.value, area)
    }
}

private fun sigmaText(e: QuickMeasureEntry, system: UnitSystem): String? {
    val s = e.sigma ?: return null
    if (s <= 0) return null
    return when (e.kind) {
        MeasureKind.DBH -> MeasurementFormatter.diameterSigma(s, system)
        MeasureKind.HEIGHT -> MeasurementFormatter.heightSigma(s, system)
        else -> String.format(Locale.US, "±%.2f m", s)
    }
}

/// "7 Jul · 09:41" — the mock's peek-card date line.
private fun dateLine(epochMs: Long): String =
    SimpleDateFormat("d MMM · HH:mm", Locale.US).format(Date(epochMs))

