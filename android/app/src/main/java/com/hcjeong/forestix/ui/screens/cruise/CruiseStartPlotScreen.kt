// Cruise "Start plot" — the v3 redesign's plot creation (screen ① state 1):
// the SAME sampling-ring AR component as the quick-measure Sampling tool
// (shared ArSessionHub anchor + radius slider + centre placement), but Save
// creates a cruise `Plot` row in the CURRENT project (auto-name "Plot N",
// centre = the live GPS fix, radius → plotAreaAcres) and makes it the
// ACTIVE plot. The hub's AR anchor is deliberately KEPT on Save so the
// boundary ring keeps overlaying the DBH/Height scan screens (subdued)
// while trees are added inside it.
//
// The quick-measure SamplingPlotScreen stays byte-identical — this is the
// cruise-scoped twin, not a modification of it.

package com.hcjeong.forestix.ui.screens.cruise

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.hcjeong.forestix.LocalAppEnvironment
import com.hcjeong.forestix.ar.ArCameraView
import com.hcjeong.forestix.ar.ArSceneMarker
import com.hcjeong.forestix.ar.ArSessionHub
import com.hcjeong.forestix.common.Units
import com.hcjeong.forestix.data.cruise.Plot
import com.hcjeong.forestix.data.cruise.PositionSource
import com.hcjeong.forestix.positioning.GPSAveraging
import com.hcjeong.forestix.positioning.LocationService
import com.hcjeong.forestix.ui.screens.CenterCrosshair
import com.hcjeong.forestix.ui.screens.CenteredText
import com.hcjeong.forestix.ui.screens.Haptics
import com.hcjeong.forestix.ui.screens.MeasureBackButton
import com.hcjeong.forestix.ui.screens.MeasureControlColumn
import com.hcjeong.forestix.ui.screens.MeasureStatusPanel
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixProminentButton
import com.hcjeong.forestix.ui.theme.ForestixRadius
import com.hcjeong.forestix.ui.theme.ForestixSpace
import com.hcjeong.forestix.ui.theme.ForestixWhiteButton
import java.util.Locale
import java.util.UUID
import kotlin.math.sqrt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun CruiseStartPlotScreen(nav: NavController, projectId: String) {
    val env = LocalAppEnvironment.current
    val context = LocalContext.current
    val controller = ArSessionHub.controller
    val haptics = remember { Haptics(context) }
    val colors = Forestix.colors
    val scope = rememberCoroutineScope()

    // Live GPS for the plot centre — same local-service pattern as the
    // GPS badge, so Save stamps a FRESH fix instead of a stale global one.
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

    // Active plot + radius live in the hub (shared with DBH/Height's
    // subdued overlay) — identical mechanics to the sampling tool.
    val plot = ArSessionHub.activePlot
    val radiusM = ArSessionHub.plotRadiusM
    val placed = plot != null

    var isOutside by remember { mutableStateOf(false) }
    var distanceFromCenter by remember { mutableStateOf<Double?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    // 5 Hz boundary check + haptics (sampling-tool parity).
    LaunchedEffect(Unit) {
        var tick = 0
        while (true) {
            delay(200)
            val c = ArSessionHub.plotCenterWorld()
            val cam = controller.currentCameraPosition()
            if (c == null || cam == null) {
                isOutside = false; distanceFromCenter = null
            } else {
                val dx = cam.x - c.x; val dz = cam.z - c.z
                val d = sqrt((dx * dx + dz * dz).toDouble())
                distanceFromCenter = d
                val now = d > ArSessionHub.plotRadiusM
                if (now && !isOutside) haptics.warn()
                else if (now && tick % 2 == 0) haptics.warn()
                isOutside = now
            }
            tick++
        }
    }

    fun place() {
        if (ArSessionHub.activePlot != null) return
        val hit = controller.screenCenterHit() ?: controller.forwardPointAtHorizontalDistance(3f)
        if (hit == null || !ArSessionHub.placePlot(hit)) {
            failure = "Couldn't read scene depth. Aim at the ground and try again."
            return
        }
        failure = null
    }

    /// Save = create the cruise Plot row ("Plot N", centre GPS, radius),
    /// make it ACTIVE, keep the AR ring for the scan overlays, go back to
    /// the cruise map.
    fun save() {
        if (saving) return
        saving = true
        scope.launch {
            try {
                val pid = UUID.fromString(projectId)
                val snap = fix ?: LocationService.lastGlobalFix
                val r = ArSessionHub.plotRadiusM
                val number = (env.plotRepository.listByProject(pid)
                    .maxOfOrNull { it.plotNumber } ?: 0) + 1
                val acc = (snap?.horizontalAccuracyM ?: 0.0).toFloat()
                val newPlot = Plot(
                    id = UUID.randomUUID(),
                    projectId = pid,
                    plannedPlotId = null,
                    plotNumber = number,
                    centerLat = snap?.latitude ?: 0.0,
                    centerLon = snap?.longitude ?: 0.0,
                    positionSource = PositionSource.GPS_AVERAGED,
                    // Honest single-fix stamp: spec tier table on the
                    // accuracy axis, nSamples = 1 records the shortcut.
                    positionTier = GPSAveraging.classify(acc, 0f),
                    gpsNSamples = if (snap != null) 1 else 0,
                    gpsMedianHAccuracyM = acc,
                    gpsSampleStdXyM = 0f,
                    offsetWalkM = null,
                    slopeDeg = 0f,
                    aspectDeg = 0f,
                    plotAreaAcres = Units.squareMetersToAcres(
                        Units.circleAreaM2(r)).toFloat(),
                    startedAt = System.currentTimeMillis(),
                    closedAt = null,
                    closedBy = null,
                    notes = "",
                    coverPhotoPath = null,
                    panoramaPath = null,
                )
                env.plotRepository.create(newPlot)
                env.settings.setCruiseProjectId(projectId)
                env.settings.setCruisePlotId(newPlot.id.toString())
                // Keep ArSessionHub.activePlot: the ring overlays the
                // DBH/Height screens while this plot is tallied.
                nav.popBackStack()
            } catch (e: Exception) {
                failure = "Couldn't save the plot: ${e.message ?: e}"
                saving = false
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        ArCameraView(
            controller,
            emptyList<ArSceneMarker>(),
            modifier = Modifier.fillMaxSize(),
            enableDepth = !placed,
            planeRenderer = !placed,
            plotOverlay = ArSessionHub.PlotOverlay.OWNER,
        )

        CruiseOutsideFlash(isOutside)
        if (!placed) CenterCrosshair(Modifier.align(Alignment.Center))

        MeasureBackButton { nav.popBackStack() }

        // Top radius slider — sampling-tool layout verbatim.
        Column(
            Modifier.align(Alignment.TopCenter)
                .padding(top = 68.dp, start = ForestixSpace.md, end = ForestixSpace.md)
                .clip(ForestixRadius.card).background(Color.Black.copy(alpha = 0.55f)).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "PLOT RADIUS",
                    style = Forestix.type.sectionHead.copy(letterSpacing = 1.2.sp),
                    color = Color.White.copy(alpha = 0.85f),
                )
                Text(String.format(Locale.US, "%.1f m", radiusM), style = Forestix.type.data, color = Color.White)
            }
            Slider(
                value = radiusM.toFloat(),
                onValueChange = { ArSessionHub.setPlotRadius(it.toDouble()) },
                valueRange = 1f..30f,
                steps = 57,
                colors = SliderDefaults.colors(
                    thumbColor = colors.confidenceWarn,
                    activeTrackColor = colors.confidenceWarn,
                ),
            )
        }

        if (!placed) MeasureControlColumn(onCapture = { place() })

        MeasureStatusPanel {
            failure?.let {
                Text(it, style = Forestix.type.caption, color = Color.White)
            }
            if (!placed) {
                CenteredText("Set the radius, aim at the plot centre, tap +")
            } else {
                CenteredText(
                    if (isOutside) "OUTSIDE — walk back inside" else "INSIDE plot boundary",
                    large = true,
                    color = if (isOutside) colors.confidenceBad else colors.confidenceOk,
                )
            }
            Text(
                distanceFromCenter?.let {
                    String.format(Locale.US, "Centre: %.2f m · area: %.1f m²", it, Units.circleAreaM2(radiusM))
                } ?: "—",
                style = Forestix.type.dataSmall,
                color = Color.White.copy(alpha = 0.85f),
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ForestixWhiteButton(
                    "Reset",
                    modifier = Modifier.weight(1f),
                    enabled = placed && !saving,
                ) {
                    ArSessionHub.clearPlot()
                    isOutside = false; distanceFromCenter = null
                }
                ForestixProminentButton(
                    "Save plot",
                    modifier = Modifier.weight(1f),
                    enabled = placed && !saving,
                ) { save() }
            }
        }
    }
}

/// Pulsing red border while the device is outside the ring — sampling-tool
/// parity (isolated animation, no camera-view recomposition).
@Composable
private fun BoxScope.CruiseOutsideFlash(active: Boolean) {
    if (!active) return
    val transition = rememberInfiniteTransition(label = "cruiseFlash")
    val alpha by transition.animateFloat(
        initialValue = 0.35f, targetValue = 0.95f,
        animationSpec = infiniteRepeatable(tween(350), RepeatMode.Reverse), label = "alpha",
    )
    Box(Modifier.fillMaxSize().border(12.dp, Color.Red.copy(alpha = alpha)))
}
