// Port of iOS Screens/NavigationScreen.swift + ViewModels/NavigationViewModel.swift.
// Spec §5.1 NavigationScreen. REQ-NAV-002/003/004.
//
// Compass arrow pointing to the planned plot, live distance readout,
// GPS tier badge (A/B/C/D) that recolors with accuracy, and a track-
// log toggle per session. The arrow rotates purely via Compose
// transforms — no AR, no map tiles — so it's cheap under canopy where
// basemap tiles don't load.

package com.hcjeong.forestix.ui.screens.nav

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.hcjeong.forestix.LocalAppEnvironment
import com.hcjeong.forestix.data.cruise.PlannedPlot
import com.hcjeong.forestix.data.cruise.PositionTier
import com.hcjeong.forestix.export.uuidString
import com.hcjeong.forestix.positioning.CLLocationSnapshot
import com.hcjeong.forestix.positioning.GeoMath
import com.hcjeong.forestix.positioning.LocationService
import com.hcjeong.forestix.ui.screens.ForestixScaffold
import com.hcjeong.forestix.ui.screens.Haptics
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixColors
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// MARK: - View model (port of iOS NavigationViewModel)

/// Owns the live GPS subscription, computes bearing + distance to
/// the planned plot target using GeoMath, classifies tier per-sample
/// for the on-screen badge, and emits a single haptic pulse when the
/// user crosses inside the 5 m arrival radius (REQ-NAV-002). Track-log
/// recording is a user toggle (REQ-NAV-004).
class NavigationViewModel(
    val target: PlannedPlot,
    val location: LocationService,
    scope: CoroutineScope,
    val arrivalRadiusM: Double = 5.0,
    private val onArrival: (() -> Unit)? = null,
) {

    // MARK: - Derived state

    private val _distanceM = MutableStateFlow<Double?>(null)
    val distanceM: StateFlow<Double?> = _distanceM.asStateFlow()

    private val _bearingDeg = MutableStateFlow<Double?>(null)
    val bearingDeg: StateFlow<Double?> = _bearingDeg.asStateFlow()

    private val _tier = MutableStateFlow(PositionTier.D)
    val tier: StateFlow<PositionTier> = _tier.asStateFlow()

    val authStatus: StateFlow<LocationService.AuthStatus> = location.authStatus

    private val _hasArrived = MutableStateFlow(false)
    val hasArrived: StateFlow<Boolean> = _hasArrived.asStateFlow()

    /// REQ-NAV-004: per-session track log toggle. Off by default to
    /// respect user privacy / battery; the UI flips it on.
    val isTrackLogEnabled = MutableStateFlow(false)

    /// Compass heading for the arrow (surfaced so the screen recomposes
    /// on every heading update).
    val headingTrueDeg: StateFlow<Double?> = location.headingTrueDeg

    init {
        // wireBindings — iOS Combine sink over $latestSnapshot.
        scope.launch { location.latestSnapshot.collect { ingest(it) } }
    }

    private fun ingest(snap: CLLocationSnapshot?) {
        if (snap == null) {
            _distanceM.value = null
            _bearingDeg.value = null
            _tier.value = PositionTier.D
            return
        }
        val d = GeoMath.distanceM(
            fromLat = snap.latitude, fromLon = snap.longitude,
            toLat = target.plannedLat, toLon = target.plannedLon)
        val b = GeoMath.bearingDeg(
            fromLat = snap.latitude, fromLon = snap.longitude,
            toLat = target.plannedLat, toLon = target.plannedLon)
        _distanceM.value = d
        _bearingDeg.value = b
        _tier.value = LocationService.tier(forHorizontalAccuracyM = snap.horizontalAccuracyM)
        // Edge-trigger the 5 m arrival — fire once when we first
        // cross the threshold. Leaving and re-entering re-arms.
        if (d <= arrivalRadiusM) {
            if (!_hasArrived.value) {
                _hasArrived.value = true
                onArrival?.invoke()
            }
        } else if (d > arrivalRadiusM * 1.5) {
            _hasArrived.value = false
        }
    }

    // MARK: - Controls

    fun start() {
        location.requestAuthorization()
        location.start()
    }

    fun stop() {
        location.stop()
    }

    /// Arrow rotation in degrees for the compass needle. Combines
    /// great-circle bearing to target with the phone's current true
    /// heading so the arrow points in a device-relative direction.
    fun arrowRotationDeg(): Double? {
        val bearing = _bearingDeg.value ?: return null
        val heading = location.headingTrueDeg.value ?: 0.0
        return ((bearing - heading) % 360.0 + 360.0) % 360.0
    }
}

private val PlannedPlot.plannedLabel: String
    get() = id.uuidString.take(6)

// MARK: - Screen

@Composable
fun NavigationScreen(
    nav: NavController,
    plannedPlotId: String,
    onArrival: (() -> Unit)? = null,
) {
    val env = LocalAppEnvironment.current
    var target by remember { mutableStateOf<PlannedPlot?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    LaunchedEffect(plannedPlotId) {
        target = env.plannedPlotRepository.read(UUID.fromString(plannedPlotId))
        if (target == null) loadFailed = true
    }
    val loaded = target
    if (loaded == null) {
        ForestixScaffold(nav, title = "Navigate") { padding ->
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                if (loadFailed) {
                    Text(
                        "Planned plot not found.",
                        style = Forestix.type.body,
                        color = Forestix.colors.textSecondary)
                } else {
                    CircularProgressIndicator()
                }
            }
        }
        return
    }
    NavigationContent(nav, loaded, onArrival)
}

@Composable
private fun NavigationContent(
    nav: NavController,
    target: PlannedPlot,
    onArrival: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = Forestix.colors
    val type = Forestix.type

    val location = remember { LocationService(context) }
    val haptics = remember { Haptics(context) }
    val viewModel = remember(target.id) {
        NavigationViewModel(
            target = target,
            location = location,
            scope = scope,
            // REQ-NAV-002 haptic pulse; the cruise flow additionally
            // replaces this step with the record-centre screen (iOS
            // CruiseFlowCoordinator.pushRecordCenter).
            onArrival = { haptics.warn(); onArrival?.invoke() },
        )
    }

    // iOS onAppear → viewModel.start(); Android additionally routes the
    // runtime permission through a result launcher (GPSAccuracyBadge
    // pattern).
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        location.onPermissionResult(granted)
        if (granted) viewModel.start()
    }
    LaunchedEffect(Unit) {
        if (LocationService.hasLocationPermission(context)) {
            viewModel.start()
        } else {
            launcher.launch(LocationService.PERMISSION)
        }
    }
    DisposableEffect(Unit) {
        onDispose { viewModel.stop() }
    }

    val distanceM by viewModel.distanceM.collectAsStateWithLifecycle()
    val bearingDeg by viewModel.bearingDeg.collectAsStateWithLifecycle()
    val headingTrueDeg by viewModel.headingTrueDeg.collectAsStateWithLifecycle()
    val tier by viewModel.tier.collectAsStateWithLifecycle()
    val authStatus by viewModel.authStatus.collectAsStateWithLifecycle()
    val hasArrived by viewModel.hasArrived.collectAsStateWithLifecycle()
    val isTrackLogEnabled by viewModel.isTrackLogEnabled.collectAsStateWithLifecycle()

    ForestixScaffold(nav, title = "Navigate") { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f)),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                // MARK: - Header
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Plot ${target.plannedLabel}", style = type.bodyBold, color = Color.White)
                        Text(
                            String.format(Locale.US, "%.5f, %.5f", target.plannedLat, target.plannedLon),
                            style = type.dataSmall,
                            color = Color.White.copy(alpha = 0.6f),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    TierBadge(tier = tier, colors = colors)
                }

                Spacer(Modifier.weight(1f))

                // MARK: - Compass
                val arrowRotation = if (bearingDeg != null) {
                    ((bearingDeg!! - (headingTrueDeg ?: 0.0)) % 360.0 + 360.0) % 360.0
                } else null
                Compass(
                    arrowRotationDeg = arrowRotation,
                    hasArrived = hasArrived,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )

                Spacer(Modifier.weight(1f))

                // MARK: - Distance readout
                Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val d = distanceM
                    if (d != null) {
                        Text(
                            String.format(Locale.US, "%.1f m", d),
                            style = type.dataLarge,
                            color = Color.White,
                        )
                        if (hasArrived) {
                            Text(
                                "Arrived — plot within ${viewModel.arrivalRadiusM.toInt()} m",
                                style = type.caption,
                                color = colors.confidenceOk,
                            )
                        }
                    } else {
                        Text("—", style = type.dataLarge, color = Color.White.copy(alpha = 0.4f))
                    }
                }

                // MARK: - Track log toggle (REQ-NAV-004)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Record track log", style = type.body, color = Color.White)
                    Spacer(Modifier.weight(1f))
                    Switch(
                        checked = isTrackLogEnabled,
                        onCheckedChange = { viewModel.isTrackLogEnabled.value = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = colors.confidenceOk),
                    )
                }

                // MARK: - Auth status banner
                when (authStatus) {
                    LocationService.AuthStatus.DENIED,
                    LocationService.AuthStatus.RESTRICTED -> Text(
                        "Location access denied — enable in Settings to navigate.",
                        style = type.caption,
                        color = colors.confidenceBad,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    LocationService.AuthStatus.NOT_DETERMINED -> Text(
                        "Requesting location permission…",
                        style = type.caption,
                        color = colors.confidenceWarn,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    LocationService.AuthStatus.UNSUPPORTED -> Text(
                        "Location unsupported on this platform.",
                        style = type.caption,
                        color = colors.confidenceWarn,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    LocationService.AuthStatus.AUTHORIZED,
                    LocationService.AuthStatus.AUTHORIZED_WHEN_IN_USE -> Unit
                }
            }
        }
    }
}

// MARK: - Tier badge

@Composable
private fun TierBadge(tier: PositionTier, colors: ForestixColors) {
    // 4 GPS tiers collapse to the design system's 3 instrument hues:
    // A → good, B/C → usable, D → bad — same rule as iOS.
    val tierColor = when (tier) {
        PositionTier.A -> colors.confidenceOk
        PositionTier.B, PositionTier.C -> colors.confidenceWarn
        PositionTier.D -> colors.confidenceBad
    }
    val shape = RoundedCornerShape(6.dp)
    Text(
        "GPS ${tier.raw}",
        style = Forestix.type.dataSmall,
        color = tierColor,
        modifier = Modifier
            .clip(shape)
            .background(tierColor.copy(alpha = 0.25f))
            .border(1.dp, tierColor, shape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

// MARK: - Compass

@Composable
private fun Compass(
    arrowRotationDeg: Double?,
    hasArrived: Boolean,
    modifier: Modifier = Modifier,
) {
    val okGreen = Forestix.colors.confidenceOk
    Box(modifier.size(240.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(220.dp)
                .border(2.dp, Color.White.copy(alpha = 0.25f), CircleShape),
        )
        Text(
            "N",
            style = Forestix.type.dataSmall,
            color = Color.White,
            modifier = Modifier.offset(y = (-115).dp),
        )
        if (arrowRotationDeg != null) {
            // iOS `Arrow` shape: kite pointing up, rotated by the
            // device-relative bearing.
            val fill = if (hasArrived) okGreen else Color.White
            Canvas(
                Modifier
                    .size(width = 40.dp, height = 140.dp)
                    .graphicsLayer { rotationZ = arrowRotationDeg.toFloat() },
            ) {
                val w = size.width
                val h = size.height
                val path = Path().apply {
                    moveTo(w / 2f, 0f)
                    lineTo(w, h)
                    lineTo(w / 2f, h - h * 0.3f)
                    lineTo(0f, h)
                    close()
                }
                drawPath(path, fill)
            }
        } else {
            Icon(
                Icons.Filled.LocationOff,
                contentDescription = "No GPS fix",
                tint = Color.White.copy(alpha = 0.4f),
                modifier = Modifier.size(60.dp),
            )
        }
    }
}
