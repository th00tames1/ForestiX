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

package com.hcjeong.forestix.ui.screens

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CenterFocusWeak
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Height
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.outlined.Forest
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import com.hcjeong.forestix.geo.CoordinateConversions
import com.hcjeong.forestix.positioning.CLLocationSnapshot
import com.hcjeong.forestix.positioning.LocationService
import com.hcjeong.forestix.ui.MeasurePhotoStore
import com.hcjeong.forestix.ui.Routes
import com.hcjeong.forestix.ui.clickableNoRipple
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixRadius
import com.hcjeong.forestix.ui.theme.ForestixSpace
import com.hcjeong.forestix.ui.theme.confidenceDescriptor
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/// Fallback camera when there's no fix and no located reading yet — the
/// seed-data PNW working area (Portland), wide enough to orient.
private val DefaultCenter = CoordinateConversions.LatLon(latitude = 45.5152, longitude = -122.6784)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapHomeScreen(nav: NavController) {
    val colors = Forestix.colors
    val type = Forestix.type
    val env = LocalAppEnvironment.current
    val context = LocalContext.current
    val activity = context as? Activity
    val settings by env.settings.state.collectAsStateWithLifecycle()
    val entries by env.history.entries.collectAsStateWithLifecycle()

    // MARK: - Live GPS (GPSAccuracyBadge pattern: local service + launcher)

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
    DisposableEffect(Unit) {
        onDispose { location.stop() }
    }
    val fix by location.latestSnapshot.collectAsStateWithLifecycle()

    // MARK: - Pins + camera

    val pins = remember(entries) { buildTreePins(entries) }

    // Last fix → else newest located reading → else the PNW default.
    val initialCamera = remember {
        val fromFix = LocationService.lastGlobalFix?.let {
            CoordinateConversions.LatLon(latitude = it.latitude, longitude = it.longitude)
        }
        val fromEntry = entries.firstOrNull { it.latitude != null && it.longitude != null }?.let {
            CoordinateConversions.LatLon(latitude = it.latitude!!, longitude = it.longitude!!)
        }
        val centre = fromFix ?: fromEntry ?: DefaultCenter
        centre to if (fromFix != null || fromEntry != null) 16.0 else 12.0
    }
    val camera = rememberMapCameraState()

    var selectedPinId by remember { mutableStateOf<String?>(null) }
    var chooserOpen by remember { mutableStateOf(false) }
    var offlineOpen by remember { mutableStateOf(false) }
    var photoEntry by remember { mutableStateOf<QuickMeasureEntry?>(null) }

    val selectedPin = pins.firstOrNull { it.id == selectedPinId }
    BackHandler(enabled = selectedPin != null) { selectedPinId = null }

    Box(Modifier.fillMaxSize().background(colors.canvas)) {
        MapView(
            center = initialCamera.first,
            modifier = Modifier.fillMaxSize(),
            initialZoom = initialCamera.second,
            // Base stays the built-in satellite; the user template is an
            // overlay on top, honouring the layers sheet's toggle.
            overlayURLTemplate = if (settings.overlayEnabled) settings.tileURLTemplate else null,
            markers = pins.map { pin ->
                MapMarker(
                    coordinate = pin.coordinate,
                    title = pin.title,
                    tint = if (pin.warn) colors.confidenceWarn else colors.primary,
                    id = pin.id,
                    shape = MapMarkerShape.PIN,
                    badges = pin.badges,
                    selected = pin.id == selectedPinId,
                )
            },
            attribution = settings.tileProviderLabel,
            onMarkerTap = { selectedPinId = it },
            onMapTap = { selectedPinId = null },
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
                GpsChip(fix)
                Spacer(Modifier.weight(1f))
                RoundChromeButton(Icons.Filled.Layers, "Offline basemap") { offlineOpen = true }
                val dark = settings.appearance == "dark"
                RoundChromeButton(
                    icon = if (dark) Icons.Filled.WbSunny else Icons.Filled.DarkMode,
                    contentDescription = if (dark) "Switch to light appearance" else "Switch to dark appearance",
                ) { env.settings.setAppearance(if (dark) "light" else "dark") }
            }
            // Coordinate readout directly under the GPS chip (mirror of the
            // identical iOS chip) — last fix, live age once stale.
            CoordChip(
                fix,
                modifier = Modifier
                    .align(Alignment.Start)
                    .padding(start = 14.dp, top = 6.dp),
            )
            // The satellite base is built-in, so this hint only concerns the
            // optional overlay (offline/no-network lives in the sheet).
            if (settings.tileURLTemplate == null) {
                Spacer(Modifier.size(ForestixSpace.xs))
                Text(
                    "OVERLAY TILES · ADD A TEMPLATE IN SETTINGS",
                    style = type.dataSmall.copy(fontSize = 10.sp, letterSpacing = 0.8.sp),
                    color = colors.textTertiary,
                    modifier = Modifier
                        .clip(ForestixRadius.chip)
                        .background(colors.surface)
                        .border(1.dp, colors.divider, ForestixRadius.chip)
                        .clickableNoRipple { offlineOpen = true }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }

        // MARK: - Bottom: action cluster ①, or peek card ② when a pin is up

        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding(),
        ) {
            val pin = selectedPin
            if (pin == null) {
                ActionCluster(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = ForestixSpace.md),
                    onCruise = { nav.navigate(Routes.TIMBER_HUB) },
                    onMeasure = { chooserOpen = true },
                    onLog = { nav.navigate(Routes.FIELD_LOG) },
                )
            } else {
                PeekCard(
                    pin = pin,
                    unitSystem = settings.unitSystem,
                    activity = activity,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 20.dp),
                    onViewPhoto = { photoEntry = it },
                    onMeasureAgain = { nav.navigate(Routes.DBH) },
                )
            }
        }
    }

    // MARK: - Sheets + photo detail

    if (chooserOpen) {
        MeasureChooserSheet(
            nextTree = env.history.suggestedNextTreeNumber,
            onDismiss = { chooserOpen = false },
            onChoose = { route ->
                chooserOpen = false
                nav.navigate(route)
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

/// Mock `.gpschip` — surface pill, tier-coloured dot, mono accuracy.
@Composable
private fun GpsChip(fix: CLLocationSnapshot?) {
    val colors = Forestix.colors
    val type = Forestix.type
    val acc = fix?.horizontalAccuracyM
    val (label, dotColor) = when {
        acc == null || acc <= 0 -> "GPS —" to colors.confidenceBad
        acc <= 5 -> String.format(Locale.US, "GPS ±%.0f m", acc) to colors.confidenceOk
        acc <= 15 -> String.format(Locale.US, "GPS ±%.0f m", acc) to colors.confidenceWarn
        else -> String.format(Locale.US, "GPS ±%.0f m", acc) to colors.confidenceBad
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .clip(ForestixRadius.control)
            .background(colors.surface)
            .border(1.dp, colors.divider, ForestixRadius.control)
            .padding(horizontal = 11.dp, vertical = 8.dp),
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(dotColor))
        Text(label, style = type.dataSmall, color = colors.textPrimary)
    }
}

/// Coordinate readout under the GPS chip — the last known fix at five
/// decimals (mono). Younger than ~5 s: coords only. Older (GPS lost under
/// canopy): a live-ticking age suffix ("· 12 s ago", minutes past 60 s)
/// and a warn-tinted dot. Never had a fix: "no fix yet". Mirror of the
/// identical iOS chip.
@Composable
private fun CoordChip(fix: CLLocationSnapshot?, modifier: Modifier = Modifier) {
    val colors = Forestix.colors
    val type = Forestix.type
    // This screen's live service, else the newest fix any screen captured.
    val snap = fix ?: LocationService.lastGlobalFix
    // 1 s clock so the age suffix ticks while the chip is on screen.
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val ageSec = snap?.let { ((nowMs - it.timestamp) / 1_000).coerceAtLeast(0) }
    val stale = ageSec != null && ageSec > 5
    val dotColor = when {
        snap == null -> colors.confidenceBad
        stale -> colors.confidenceWarn
        else -> colors.confidenceOk
    }
    val label = when {
        snap == null -> "no fix yet"
        !stale -> String.format(Locale.US, "%.5f, %.5f", snap.latitude, snap.longitude)
        else -> {
            val age = if (ageSec!! < 60) "$ageSec s ago" else "${ageSec / 60} min ago"
            String.format(Locale.US, "%.5f, %.5f · %s", snap.latitude, snap.longitude, age)
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .clip(ForestixRadius.control)
            .background(colors.surface)
            .border(1.dp, colors.divider, ForestixRadius.control)
            .padding(horizontal = 11.dp, vertical = 5.dp),
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(dotColor))
        Text(
            label,
            style = type.dataSmall.copy(fontSize = 11.sp),
            color = if (snap == null) colors.textTertiary else colors.textSecondary,
        )
    }
}

/// Mock `.roundbtn`, sized to the 44 dp hit-target rule and styled like
/// ModeSelectionScreen's appearance toggle.
@Composable
private fun RoundChromeButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val colors = Forestix.colors
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(colors.surfaceRaised)
            .border(1.dp, colors.divider, CircleShape),
    ) {
        Icon(icon, contentDescription = contentDescription, tint = colors.textSecondary, modifier = Modifier.size(20.dp))
    }
}

// MARK: - Bottom action cluster (mock `.actioncluster`) -----------------------

@Composable
private fun ActionCluster(
    modifier: Modifier = Modifier,
    onCruise: () -> Unit,
    onMeasure: () -> Unit,
    onLog: () -> Unit,
) {
    val colors = Forestix.colors
    Row(
        modifier,
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(26.dp),
    ) {
        SideCircleButton("Cruise", Icons.Outlined.Forest, onCruise)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(74.dp)
                    .shadow(10.dp, CircleShape)
                    .clip(CircleShape)
                    .background(colors.primary)
                    .border(4.dp, colors.surface, CircleShape)
                    .clickableNoRipple(onMeasure),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = "New measurement",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(30.dp),
                )
            }
            ClusterLabel("Measure")
        }
        SideCircleButton("Log", Icons.AutoMirrored.Filled.List, onLog)
    }
}

@Composable
private fun SideCircleButton(label: String, icon: ImageVector, onClick: () -> Unit) {
    val colors = Forestix.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(54.dp)
                .shadow(6.dp, CircleShape)
                .clip(CircleShape)
                .background(colors.surface)
                .border(1.dp, colors.divider, CircleShape)
                .clickableNoRipple(onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = colors.textPrimary, modifier = Modifier.size(22.dp))
        }
        ClusterLabel(label)
    }
}

@Composable
private fun ClusterLabel(label: String) {
    Text(
        label.uppercase(),
        style = Forestix.type.caption.copy(
            fontSize = 10.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp),
        color = Forestix.colors.textSecondary,
        modifier = Modifier.padding(top = 5.dp),
    )
}

// MARK: - Peek card (mock `.peek`) --------------------------------------------

@Composable
private fun PeekCard(
    pin: TreePin,
    unitSystem: UnitSystem,
    activity: Activity?,
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
        (species?.let { " · $it" } ?: "")
    val photoOwner = pin.entries.firstOrNull { it.photoPath != null }
    val photoCount = pin.entries.count { it.photoPath != null }

    Column(
        modifier
            .fillMaxWidth()
            .shadow(12.dp, shape)
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = type.bodyBold.copy(fontSize = 16.sp),
                color = colors.textPrimary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(dateLine(newest.createdAt), style = type.dataSmall.copy(fontSize = 11.sp), color = colors.textTertiary)
        }
        Spacer(Modifier.size(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PhotoThumb(photoOwner?.photoPath, photoCount, activity)
            Column(Modifier.weight(1f)) {
                val rows = latestPerKind(pin.entries)
                rows.forEachIndexed { i, entry ->
                    MeasureRow(entry, unitSystem)
                    if (i < rows.lastIndex) HorizontalDivider(color = colors.divider, thickness = 1.dp)
                }
            }
        }
        Spacer(Modifier.size(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(ForestixSpace.xs)) {
            if (photoOwner != null) {
                PeekActionButton(
                    "View photo", primary = false, modifier = Modifier.weight(1f),
                ) { onViewPhoto(photoOwner) }
            }
            PeekActionButton(
                "Measure this tree again", primary = true, modifier = Modifier.weight(1f),
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
@Composable
private fun TierChipSoft(rawTier: String) {
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
@Composable
private fun PhotoThumb(photoName: String?, photoCount: Int, activity: Activity?) {
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
                modifier = Modifier.align(Alignment.Center).size(28.dp),
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

@Composable
private fun PeekActionButton(
    label: String,
    primary: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = Forestix.colors
    val shape = ForestixRadius.control
    Box(
        modifier
            .heightIn(min = 44.dp)
            .clip(shape)
            .then(
                if (primary) Modifier.background(colors.primary)
                else Modifier.border(1.dp, colors.divider, shape)
            )
            .clickableNoRipple(onClick),
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
    onDismiss: () -> Unit,
    onChoose: (String) -> Unit,
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
                "MEASURE · TREE $nextTree (NEXT)",
                style = type.sectionHead.copy(letterSpacing = 1.2.sp),
                color = colors.textTertiary,
                modifier = Modifier.padding(bottom = ForestixSpace.xs),
            )
            ChoiceRow(Icons.Filled.Straighten, "Diameter (DBH)", "Depth · AR motion · AR caliper") {
                onChoose(Routes.DBH)
            }
            HorizontalDivider(color = colors.divider)
            ChoiceRow(Icons.Filled.Height, "Height", "Walk-off tangent · crown add-on") {
                onChoose(Routes.HEIGHT)
            }
            HorizontalDivider(color = colors.divider)
            ChoiceRow(Icons.Filled.SwapHoriz, "Distance", "Live · two-point") {
                onChoose(Routes.DISTANCE)
            }
            HorizontalDivider(color = colors.divider)
            ChoiceRow(Icons.Filled.CenterFocusWeak, "Sampling plot", "Centre stake · boundary ring") {
                onChoose(Routes.SAMPLING)
            }
        }
    }
}

@Composable
private fun ChoiceRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    val colors = Forestix.colors
    val type = Forestix.type
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickableNoRipple(onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ForestixSpace.sm),
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(colors.primaryMuted),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = colors.primary, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = type.bodyBold.copy(fontSize = 15.5.sp), color = colors.textPrimary)
            Text(subtitle, style = type.caption, color = colors.textSecondary)
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = colors.textTertiary,
            modifier = Modifier.size(16.dp),
        )
    }
}

// MARK: - Photo detail (mock ⑤) ------------------------------------------------

/// Full-screen accept-snapshot viewer. Deliberately dark in both app
/// appearances, matching the mock's photo screens.
@Composable
private fun PhotoViewerDialog(
    entry: QuickMeasureEntry,
    unitSystem: UnitSystem,
    activity: Activity?,
    onDismiss: () -> Unit,
) {
    val type = Forestix.type
    val tier = confidenceDescriptor(entry.confidenceRaw)
    val bitmap by produceState<Bitmap?>(initialValue = null, entry.photoPath, activity) {
        value = withContext(Dispatchers.IO) {
            val name = entry.photoPath
            if (name == null || activity == null) null
            else decodeSampled(MeasurePhotoStore.file(activity, name), targetPx = 1600)
        }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color(0xFF0A0D0B))) {
            val image = bitmap
            if (image != null) {
                Image(
                    image.asImageBitmap(),
                    contentDescription = "Measurement photo",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(bottom = 96.dp),
                )
            } else {
                Text(
                    "Photo unavailable",
                    style = type.body,
                    color = Color(0xFF79837D),
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = ForestixSpace.xs, end = 14.dp)
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color(0xB306090A)),
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Close photo", tint = Color(0xFFF2F5F3))
            }
            // Bottom meta strip (mock `.photofull .meta`)
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color(0xEB06090A))
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = ForestixSpace.md),
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        (if (entry.kind == MeasureKind.DBH) "Ø " else "") +
                            valueText(entry, unitSystem),
                        style = type.dataLarge,
                        color = Color(0xFFF2F5F3),
                    )
                    Text(
                        "  " + listOfNotNull(sigmaText(entry, unitSystem), tier.label).joinToString(" · "),
                        style = type.dataSmall,
                        color = Color(0xFFA5AEA8),
                        modifier = Modifier.padding(bottom = 3.dp),
                    )
                }
                Row(
                    Modifier.padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    MetaCell(
                        "TREE",
                        listOfNotNull(
                            entry.treeNumber?.let { "T$it" }, entry.speciesCode,
                        ).joinToString(" · ").ifEmpty { "—" },
                    )
                    MetaCell("METHOD", entry.method)
                    MetaCell("DATE", dateLine(entry.createdAt))
                }
            }
        }
    }
}

@Composable
private fun MetaCell(label: String, value: String) {
    val type = Forestix.type
    Column {
        Text(
            label,
            style = type.dataSmall.copy(fontSize = 10.sp, letterSpacing = 0.8.sp),
            color = Color(0xFF79837D),
        )
        Text(
            value,
            style = type.dataSmall.copy(fontWeight = FontWeight.Bold),
            color = Color(0xFFF2F5F3),
            modifier = Modifier.padding(top = 1.dp),
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
    MeasureKind.DISTANCE ->
        if (e.value < 1) String.format(Locale.US, "%.0f cm", e.value * 100)
        else String.format(Locale.US, "%.2f m", e.value)
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
