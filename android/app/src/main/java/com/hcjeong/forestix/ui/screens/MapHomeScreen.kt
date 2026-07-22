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

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
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
import com.hcjeong.forestix.basemap.MapMarker
import com.hcjeong.forestix.basemap.MapMarkerShape
import com.hcjeong.forestix.basemap.MapView
import com.hcjeong.forestix.basemap.rememberMapCameraState
import com.hcjeong.forestix.common.MeasurementFormatter
import com.hcjeong.forestix.common.UnitSystem
import com.hcjeong.forestix.data.MeasureKind
import com.hcjeong.forestix.data.QuickMeasureEntry
import com.hcjeong.forestix.data.SettingsSnapshot
import com.hcjeong.forestix.geo.CoordinateConversions
import com.hcjeong.forestix.positioning.CLLocationSnapshot
import com.hcjeong.forestix.positioning.GeoMath
import com.hcjeong.forestix.positioning.LocationService
import com.hcjeong.forestix.ui.MeasurePhotoStore
import com.hcjeong.forestix.ui.PendingTreeNumber
import com.hcjeong.forestix.ui.Routes
import com.hcjeong.forestix.ui.clickableNoRipple
import com.hcjeong.forestix.ui.pressableNoRipple
import com.hcjeong.forestix.ui.screens.cruise.CruiseCapture
import com.hcjeong.forestix.ui.screens.cruise.CruiseDistanceOverlay
import com.hcjeong.forestix.ui.screens.cruise.CruiseModeBottomContent
import com.hcjeong.forestix.ui.screens.cruise.CruiseModeEffects
import com.hcjeong.forestix.ui.screens.cruise.CruiseModeSheets
import com.hcjeong.forestix.ui.screens.cruise.CruiseModeState
import com.hcjeong.forestix.ui.screens.cruise.cruiseModeMarkers
import com.hcjeong.forestix.ui.screens.cruise.cruiseModePolygons
import com.hcjeong.forestix.ui.screens.cruise.cruiseModePolylines
import com.hcjeong.forestix.ui.softDropShadow
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixRadius
import com.hcjeong.forestix.ui.theme.ForestixSpace
import com.hcjeong.forestix.ui.theme.confidenceDescriptor
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/// Peavy Hall (OSU College of Forestry) fallback — used only when there is
/// no fix and no located reading. Mirrors iOS `fallbackCamera`.
private val DefaultCenter = CoordinateConversions.LatLon(latitude = 44.56417, longitude = -123.28556)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapHomeScreen(nav: NavController) {
    val colors = Forestix.colors
    val env = LocalAppEnvironment.current
    val context = LocalContext.current
    val activity = context as? Activity
    val settings by env.settings.state.collectAsStateWithLifecycle()
    val entries by env.history.entries.collectAsStateWithLifecycle()

    // v3.1 merged cruise mode: ONE map, two modes, persisted (tc.mapMode).
    val isCruise = settings.mapMode == "cruise"

    // Re-entry housekeeping (was CruiseMapScreen's): a finished/abandoned
    // cruise tally chain must not leak its session or its project scan
    // calibration into the quick world — every landing on the home (the
    // chain always pops back here) disarms it. Idempotent.
    LaunchedEffect(Unit) { CruiseCapture.end(env) }

    // MARK: - Live GPS (GPSAccuracyBadge pattern: shared service + launcher)

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
    // always at zoom 16 (iOS startUp()).
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

    var selectedPinId by remember { mutableStateOf<String?>(null) }
    var chooserOpen by remember { mutableStateOf(false) }
    // Peek-card "Measure this tree" scopes the chooser to that pin's tree
    // (header "MEASURE · TREE N", no "(NEXT)"); null = plain chooser on the
    // next free number. Cleared whenever the chooser dismisses.
    var chooserTreeOverride by remember { mutableStateOf<Int?>(null) }
    // Far-GPS confirmation before the scoped chooser: (tree, whole metres)
    // when the current fix sits > 30 m from the tapped tree's pin.
    var farTreeConfirm by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var offlineOpen by remember { mutableStateOf(false) }
    var photoEntry by remember { mutableStateOf<QuickMeasureEntry?>(null) }

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

    /// The toggle circle + system back both land here. Leaving a mode drops
    /// its selection so no stale peek pops back on the next visit.
    fun setMode(mode: String) {
        selectedPinId = null
        cruise.selectedId = null
        env.settings.setMapMode(mode)
    }

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
            initialZoom = 16.0,
            // Base stays the built-in satellite; the user template is an
            // overlay on top, honouring the layers sheet's toggle AND the
            // provider usage-policy acknowledgement (iOS
            // makeOverlayTileCache gate).
            overlayURLTemplate = if (settings.overlayEnabled && settings.providerUsageAcknowledged) {
                settings.tileURLTemplate
            } else {
                null
            },
            // Mode content separation (v3.1): quick pins are measure-only,
            // cruise pins/rings/guides cruise-only — the map itself (camera,
            // zoom, base + overlay) is shared across the toggle.
            polygons = if (isCruise) {
                cruiseModePolygons(cruise, settings, colors.accent)
            } else {
                emptyList()
            },
            polylines = if (isCruise) {
                cruiseModePolylines(cruise, fix, colors.accent)
            } else {
                emptyList()
            },
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
            onMapTap = { if (isCruise) cruise.selectedId = null else selectedPinId = null },
            youLocation = fix?.let {
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
                // single-line SlashScan readout truncates instead of shoving
                // the trailing round buttons off-screen. Shared by both modes
                // (the cruise project chip is gone — the project lives in the
                // bottom cluster's PROJECT circle + strip now).
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    GpsChip(fix)
                }
                // My-location: recentre on the freshest fix (this screen's
                // live service, else the last global fix) without ever
                // zooming OUT past 16. Dimmed no-op until any fix exists.
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
                            zoom = max(camera.zoom, 16.0),
                        )
                    }
                }
                RoundChromeButton(Icons.Filled.Layers, "Basemap layers") { offlineOpen = true }
                // Settings — rightmost of the top-right group, both modes.
                RoundChromeButton(Icons.Filled.Settings, "Settings") {
                    nav.navigate(Routes.SETTINGS)
                }
            }
        }

        // Cruise navigate mode: floating live distance chip riding the
        // dashed guide line's midpoint (mock ⑦ `.distchip`).
        if (isCruise) {
            CruiseDistanceOverlay(cruise, camera, fix)
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
                        activity = activity,
                        plotNameFor = { pin ->
                            pin.entries.firstOrNull()?.plotID
                                ?.let { env.history.plot(it) }
                                ?.takeIf { !it.isDefault }?.name
                        },
                        onToggleMode = { setMode("cruise") },
                        onMeasure = { chooserOpen = true },
                        onLog = { nav.navigate(Routes.FIELD_LOG) },
                        onViewPhoto = { photoEntry = it },
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
        MeasureChooserSheet(
            nextTree = env.history.suggestedNextTreeNumber,
            treeOverride = chooserTreeOverride,
            onDismiss = {
                chooserOpen = false
                chooserTreeOverride = null
            },
            onChoose = { route, lockTree ->
                chooserOpen = false
                // Full / DBH / Height lock the reading to the tree number
                // the sheet header promised (iOS chooserRow actions) — the
                // peek card's override tree when set, else the next free.
                if (lockTree) {
                    PendingTreeNumber.value =
                        chooserTreeOverride ?: env.history.suggestedNextTreeNumber
                }
                chooserTreeOverride = null
                nav.navigate(route)
            },
        )
    }
    // Far-GPS confirmation — shown before the scoped chooser when the tree's
    // pin is > 30 m from the current fix (peek "Measure this tree" only).
    farTreeConfirm?.let { (tree, metres) ->
        AlertDialog(
            onDismissRequest = { farTreeConfirm = null },
            title = { Text("Tree $tree is $metres m away") },
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
    if (offlineOpen) {
        OfflineMapSheet(
            camera = camera,
            onDismiss = { offlineOpen = false },
            onOpenSettings = {
                offlineOpen = false
                nav.navigate(Routes.SETTINGS)
            },
        )
    }
    photoEntry?.let { entry ->
        PhotoViewerDialog(
            entry = entry,
            unitSystem = settings.unitSystem,
            activity = activity,
            onDismiss = { photoEntry = null },
        )
    }

    // First-launch UX: the map home hosts the region picker (it is the
    // screen after the splash). Auto-present once; picking, skipping or
    // swipe-dismissing all stamp regionPickerSeen, and it stays reachable
    // later via Settings → Region. Measure mode only — the default mode is
    // measure, so first run always lands here; cruise chrome stays clean.
    if (!isCruise && settings.region == null && !settings.regionPickerSeen) {
        ModalBottomSheet(
            onDismissRequest = { env.settings.setRegionPickerSeen(true) },
            containerColor = colors.surface,
        ) {
            RegionPickerSheet(onDismiss = {
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
    activity: Activity?,
    plotNameFor: (TreePin) -> String?,
    onToggleMode: () -> Unit,
    onMeasure: () -> Unit,
    onLog: () -> Unit,
    onViewPhoto: (QuickMeasureEntry) -> Unit,
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
                activity = activity,
                plotName = plotNameFor(pin),
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 20.dp),
                onViewPhoto = onViewPhoto,
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
                title = "T$number",
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

/// Unified GPS chip — one-line SlashScan live readout of the last known
/// fix behind a freshness dot: green < 5 s, yellow ≤ 60 s, red past 60 s
/// or when no fix ever arrived. The 1 s tick keeps the age (and the dot
/// tier) counting live. Single mono line, byte-identical to the iOS
/// sibling's format so both platforms render the same string.
/// Internal: shared by both map modes' top chrome.
@Composable
internal fun GpsChip(fix: CLLocationSnapshot?) {
    val colors = Forestix.colors
    val type = Forestix.type
    val context = LocalContext.current
    // Bearing: LocationService.headingTrueDeg (the fix carries no course on
    // Android) — 0 when the compass hasn't reported yet, per the locked spec.
    val location = remember { LocationService.shared(context) }
    val heading by location.headingTrueDeg.collectAsStateWithLifecycle()
    // This screen's live service, else the newest fix any screen captured.
    val snap = fix ?: LocationService.lastGlobalFix
    // 1 s clock so the age suffix + dot tier tick while the chip is on screen.
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val ageSec = snap?.let { ((nowMs - it.timestamp) / 1_000).coerceAtLeast(0) }
    val dotColor = when {
        ageSec == null -> colors.confidenceBad // no fix ever
        ageSec < 5 -> colors.confidenceOk
        ageSec <= 60 -> colors.confidenceWarn
        else -> colors.confidenceBad
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .clip(ForestixRadius.control)
            .background(colors.surface)
            .border(1.dp, colors.divider, ForestixRadius.control)
            .padding(horizontal = 11.dp, vertical = 7.dp),
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(dotColor))
        Text(
            text = if (snap != null && ageSec != null) {
                // SlashScan single-line format (LOCKED, byte-identical to
                // iOS): lon, lat, altitude m, bearing deg, horizontal
                // accuracy m, seconds since the fix.
                String.format(
                    Locale.US,
                    "GPS: %.2f %.2f %.2fm %ddeg %.0fm [%d sec old]",
                    snap.longitude,
                    snap.latitude,
                    snap.altitudeM ?: 0.0,
                    (heading ?: 0.0).toInt(),
                    snap.horizontalAccuracyM,
                    ageSec.toInt(),
                )
            } else {
                "GPS: [not available]"
            },
            style = type.dataSmall.copy(fontSize = 11.sp),
            color = colors.textPrimary,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

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
    activity: Activity?,
    plotName: String?,
    modifier: Modifier = Modifier,
    onViewPhoto: (QuickMeasureEntry) -> Unit,
    onMeasureAgain: () -> Unit,
) {
    val colors = Forestix.colors
    val type = Forestix.type
    val shape = RoundedCornerShape(14.dp)
    val newest = pin.entries.first()
    val species = pin.entries.firstNotNullOfOrNull { it.speciesCode }
    val title = (pin.treeNumber?.let { "Tree $it" } ?: rowLabel(newest)) +
        (species?.let { " · ${it.uppercase(Locale.US)}" } ?: "")
    // Date, plus the plot name when the reading isn't on the default plot
    // (iOS peekSubtitle: "7 Jul · 09:41 · Plot 2").
    val subtitle = listOfNotNull(dateLine(newest.createdAt), plotName).joinToString(" · ")
    val photoOwner = pin.entries.firstOrNull { it.photoPath != null }
    val photoCount = pin.entries.count { it.photoPath != null }

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
            PhotoThumb(photoOwner?.photoPath, photoCount, activity)
            Column(Modifier.weight(1f)) {
                val rows = latestPerKind(pin.entries)
                rows.forEachIndexed { i, entry ->
                    MeasureRow(entry, unitSystem)
                    if (i < rows.lastIndex) HorizontalDivider(color = colors.divider, thickness = 0.5.dp)
                }
            }
        }
        Spacer(Modifier.size(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(ForestixSpace.xs)) {
            // "View photo" is ALWAYS rendered — disabled and dimmed when
            // the tree has no photo (iOS peek card).
            PeekActionButton(
                "View photo", primary = false,
                enabled = photoOwner != null,
                modifier = Modifier.weight(1f),
            ) { photoOwner?.let(onViewPhoto) }
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
            Text(
                valueText(entry, unitSystem),
                style = type.dataSmall.copy(fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold),
                color = colors.textPrimary,
            )
            sigmaText(entry, unitSystem)?.let {
                Text(
                    " $it",
                    style = type.dataSmall.copy(fontSize = 11.sp),
                    color = colors.textTertiary,
                )
            }
        }
        TierChipSoft(entry.confidenceRaw)
    }
}

/// Mock `.chip` — soft tier-coloured background, dot + uppercase label.
/// Internal: the cruise tree/plot peeks reuse it (v3).
@Composable
internal fun TierChipSoft(rawTier: String) {
    val d = confidenceDescriptor(rawTier)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .clip(ForestixRadius.chip)
            .background(d.color.copy(alpha = 0.12f))
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
internal fun PhotoThumb(photoName: String?, photoCount: Int, activity: Activity?) {
    val colors = Forestix.colors
    val thumb by produceState<Bitmap?>(initialValue = null, photoName, activity) {
        value = withContext(Dispatchers.IO) {
            if (photoName == null || activity == null) null
            else decodeSampled(MeasurePhotoStore.file(activity, photoName), targetPx = 256)
        }
    }
    Box(
        Modifier
            .size(96.dp)
            .clip(ForestixRadius.card)
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
    onDismiss: () -> Unit,
    /// (route, lockTree) — lockTree is true for the tree-bound rows
    /// (Full / DBH / Height), which pin the reading to `treeOverride`
    /// when set, else `nextTree`.
    onChoose: (String, Boolean) -> Unit,
) {
    val colors = Forestix.colors
    val type = Forestix.type
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.surface) {
        Column(
            Modifier
                .padding(horizontal = ForestixSpace.md)
                .padding(bottom = ForestixSpace.xl),
        ) {
            Text(
                treeOverride?.let { "MEASURE · TREE $it" }
                    ?: "MEASURE · TREE $nextTree (NEXT)",
                style = type.sectionHead.copy(
                    fontWeight = FontWeight.ExtraBold, letterSpacing = 1.0.sp),
                color = colors.textTertiary,
                modifier = Modifier.padding(bottom = ForestixSpace.xs),
            )
            // Field fix: the chained DBH → Height capture is the common
            // whole-tree workflow, so it leads the sheet (emphasised icon
            // tile). "dbh?chain=true" tells DBH Accept to jump straight to
            // Height on the same tree instead of the continuation dialog.
            ChoiceRow(
                Icons.Filled.Park,
                "Full measurement",
                "DBH → Height, one tree",
                emphasized = true,
            ) { onChoose("${Routes.DBH}?chain=true", true) }
            HorizontalDivider(color = colors.divider, thickness = 0.5.dp)
            ChoiceRow(Icons.Filled.Straighten, "Diameter (DBH)", "Depth · AR motion · AR caliper") {
                onChoose(Routes.DBH, true)
            }
            HorizontalDivider(color = colors.divider, thickness = 0.5.dp)
            ChoiceRow(Icons.Filled.Height, "Height", "Walk-off tangent · crown add-on") {
                onChoose(Routes.HEIGHT, true)
            }
            HorizontalDivider(color = colors.divider, thickness = 0.5.dp)
            ChoiceRow(Icons.Filled.SwapHoriz, "Distance", "Live · two-point") {
                onChoose(Routes.DISTANCE, false)
            }
            HorizontalDivider(color = colors.divider, thickness = 0.5.dp)
            ChoiceRow(Icons.Filled.CenterFocusWeak, "Sampling plot", "Centre stake · boundary ring") {
                onChoose(Routes.SAMPLING, false)
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

/// Full-screen accept-snapshot viewer. Deliberately dark in both app
/// appearances, matching the mock's photo screens (iOS
/// MeasurePhotoDetailView).
@Composable
private fun PhotoViewerDialog(
    entry: QuickMeasureEntry,
    unitSystem: UnitSystem,
    activity: Activity?,
    onDismiss: () -> Unit,
) {
    val type = Forestix.type
    val tier = confidenceDescriptor(entry.confidenceRaw)
    val ink = Color(0xFFF2F5F3)
    val inkDim = Color(0xFFA5AEA8)
    // Spinner while the JPEG decodes; "Photo unavailable" only once the
    // decode has actually failed (iOS ProgressView behaviour).
    var loadFailed by remember(entry.photoPath) { mutableStateOf(false) }
    val bitmap by produceState<Bitmap?>(initialValue = null, entry.photoPath, activity) {
        val decoded = withContext(Dispatchers.IO) {
            val name = entry.photoPath
            if (name == null || activity == null) null
            else decodeSampled(MeasurePhotoStore.file(activity, name), targetPx = 1600)
        }
        value = decoded
        if (decoded == null) loadFailed = true
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color(0xFF0A0D0B))) {
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
            // Bottom meta strip (mock `.photofull .meta`) over a vertical
            // fade into near-black.
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
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        bigValueText(entry, unitSystem),
                        style = type.dataLarge.copy(
                            fontSize = 30.sp, fontWeight = FontWeight.ExtraBold),
                        color = ink,
                        modifier = Modifier.alignByBaseline(),
                    )
                    Text(
                        listOfNotNull(sigmaText(entry, unitSystem), tier.label)
                            .joinToString(" · "),
                        style = type.dataSmall,
                        color = inkDim,
                        modifier = Modifier.alignByBaseline(),
                    )
                }
                Row(
                    Modifier.padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    MetaCell(
                        "TREE",
                        listOfNotNull(
                            entry.treeNumber?.let { "T$it" },
                            entry.speciesCode?.takeIf { it.isNotEmpty() }
                                ?.uppercase(Locale.US),
                        ).joinToString(" · ").ifEmpty { "—" },
                    )
                    MetaCell("METHOD", entry.method)
                    MetaCell(
                        "GPS",
                        if (entry.latitude != null && entry.longitude != null) {
                            String.format(
                                Locale.US, "%.5f, %.5f", entry.latitude, entry.longitude)
                        } else {
                            "—"
                        },
                    )
                }
            }
        }
    }
}

/// "Ø 32.4 cm" / "H 18.2 m" — the viewer's headline value with the iOS
/// kind prefixes.
private fun bigValueText(e: QuickMeasureEntry, system: UnitSystem): String {
    val prefix = when (e.kind) {
        MeasureKind.DBH -> "Ø "
        MeasureKind.HEIGHT -> "H "
        else -> ""
    }
    return prefix + valueText(e, system)
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
        String.format(Locale.US, "r %.1f m · %.0f m²", e.value, area)
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

/// Decode a stored JPEG at roughly `targetPx` on the short side — thumbs
/// shouldn't pay for a full window-size bitmap.
private fun decodeSampled(file: File, targetPx: Int): Bitmap? {
    if (!file.exists()) return null
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetPx &&
            bounds.outHeight / (sample * 2) >= targetPx
        ) {
            sample *= 2
        }
        BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    } catch (_: Exception) {
        null
    }
}
