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
import androidx.compose.foundation.layout.statusBarsPadding
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
import com.hcjeong.forestix.common.ForestixLogger
import com.hcjeong.forestix.common.Units
import com.hcjeong.forestix.data.cruise.Plot
import com.hcjeong.forestix.data.cruise.PositionSource
import com.hcjeong.forestix.positioning.GPSAveraging
import com.hcjeong.forestix.positioning.LocationService
import com.hcjeong.forestix.ui.screens.CenterCrosshair
import com.hcjeong.forestix.ui.screens.CenteredText
import com.hcjeong.forestix.ui.screens.Haptics
import com.hcjeong.forestix.ui.screens.MeasureBackButton
import com.hcjeong.forestix.ui.screens.MeasureShutterBar
import com.hcjeong.forestix.ui.screens.MeasureStatusPanel
import com.hcjeong.forestix.ui.screens.MeasureTopChrome
import com.hcjeong.forestix.ui.screens.SamplingPlotMiniMap
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
import com.hcjeong.forestix.ar.Vec3
import com.hcjeong.forestix.ui.screens.plotPillarPreviewMarkers

/// `editPlotId` — FIELD REPORT F11. The same screen now serves BOTH "place
/// the first plot" and "come back and change it": the scan screens' top-right
/// mini-map re-opens it with the tally's plot id, and Save then rewrites THAT
/// plot instead of minting a duplicate Plot N+1 out from under the cruiser.
/// Empty/unknown id ⇒ the original create behaviour, unchanged.
@Composable
fun CruiseStartPlotScreen(nav: NavController, projectId: String, editPlotId: String = "") {
    val env = LocalAppEnvironment.current
    val context = LocalContext.current
    val controller = ArSessionHub.controller
    val haptics = remember { Haptics(context) }
    val colors = Forestix.colors
    val scope = rememberCoroutineScope()

    // Live GPS for the plot centre — same shared-service pattern as the
    // GPS badge (updates run while this screen holds its subscription), so
    // Save stamps a FRESH fix instead of a stale global one.
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

    // Active plot + radius live in the hub (shared with DBH/Height's
    // subdued overlay) — identical mechanics to the sampling tool.
    val plot = ArSessionHub.activePlot
    val radiusM = ArSessionHub.plotRadiusM
    val placed = plot != null

    var isOutside by remember { mutableStateOf(false) }
    var distanceFromCenter by remember { mutableStateOf<Double?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    /// Live crosshair hit while aiming — where the pillar would land.
    /// Null once the plot is placed, or before the first successful ray.
    var previewCentre by remember { mutableStateOf<Vec3?>(null) }

    // 5 Hz boundary check + haptics (sampling-tool parity).
    LaunchedEffect(Unit) {
        var tick = 0
        while (true) {
            delay(200)
            // Where the pillar WOULD be planted if the cruiser tapped now
            // (FIELD REPORT 8). The SAME ray and fallback `place()` uses, so
            // the ghost cannot promise a placement the tap would refuse or
            // put somewhere else. Cleared once the plot is down — the real
            // pillar is on screen and a second translucent one is noise.
            previewCentre =
                if (ArSessionHub.activePlot != null) null
                else controller.screenCenterHit()
                    ?: controller.forwardPointAtHorizontalDistance(3f)
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
            failure = "Couldn't see the ground here. Aim at the ground and try again."
            return
        }
        failure = null
    }

    /// F11 — apply a RE-OPENED setup session to the EXISTING plot instead of
    /// creating a new one. Returns false when there is nothing to edit, so
    /// Save falls through to creating.
    ///
    /// Radius always applies. The CENTRE deliberately does not, unless the
    /// cruiser actually re-placed the ring: a radius-only edit that also
    /// re-stamped the centre would silently teleport the plot to wherever the
    /// cruiser happened to be standing, which is the worst kind of data loss
    /// — invisible. Both `ArSessionHub.clearPlot()` (the Reset button) and
    /// `placePlot(...)` null `linkedCruisePlotId`, so "still linked to this
    /// plot" is exactly the test for "the centre was NOT re-placed".
    suspend fun applyEdit(): Boolean {
        val id = runCatching { UUID.fromString(editPlotId) }.getOrNull() ?: return false
        val existing = runCatching { env.plotRepository.read(id) }.getOrNull() ?: return false
        val r = ArSessionHub.plotRadiusM
        existing.plotAreaAcres = Units.squareMetersToAcres(Units.circleAreaM2(r)).toFloat()
        val recentred = ArSessionHub.activePlot != null &&
            ArSessionHub.linkedCruisePlotId != id
        if (recentred) {
            val snap = fix ?: LocationService.lastGlobalFix
            if (snap != null) {
                val acc = snap.horizontalAccuracyM.toFloat()
                existing.centerLat = snap.latitude
                existing.centerLon = snap.longitude
                existing.positionSource = PositionSource.GPS_AVERAGED
                existing.gpsNSamples = 1
                existing.gpsMedianHAccuracyM = acc
                existing.gpsSampleStdXyM = 0f
                // Still stored, still exported — just never shown (F9).
                existing.positionTier = GPSAveraging.classify(acc, 0f)
            }
        }
        env.plotRepository.update(existing)
        // Re-link so the mini-map trusts the AR-anchor path for YOU again.
        if (ArSessionHub.activePlot != null) ArSessionHub.linkPlot(id)
        return true
    }

    /// Save = create the cruise Plot row ("Plot N", centre GPS, radius),
    /// make it ACTIVE, keep the AR ring for the scan overlays, go back to
    /// the cruise map. With `editPlotId` set it REWRITES that plot instead
    /// (F11) and leaves the active-plot pointers alone.
    fun save() {
        if (saving) return
        saving = true
        scope.launch {
            try {
                if (applyEdit()) {
                    nav.popBackStack()
                    return@launch
                }
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
                ForestixLogger.plotOpened(newPlot.id, newPlot.projectId)
                // Keep ArSessionHub.activePlot: the ring overlays the
                // DBH/Height screens while this plot is tallied. Stamp
                // the anchor as THIS cruise plot's centre so the scan
                // screens' mini-map may use the (accurate) anchor path
                // for YOU while measuring into this plot.
                ArSessionHub.linkPlot(newPlot.id)
                nav.popBackStack()
            } catch (e: Exception) {
                failure = "Couldn't save the plot: ${e.message ?: e}"
                saving = false
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        // Sampling-tool parity: plane grid AND the Depth API only while
        // aiming for the centre; no depth occlusion (it made the ring and
        // pillar flicker and cost a depth image per frame — see
        // SamplingPlotScreen / ArSessionHub).
        ArCameraView(
            controller,
            // The hub draws the PLACED plot; the only screen markers are the
            // translucent preview of the pillar the next tap would plant.
            plotPillarPreviewMarkers(previewCentre, radiusM.toFloat()),
            modifier = Modifier.fillMaxSize(),
            enableDepth = !placed,
            planeRenderer = !placed,
            plotOverlay = ArSessionHub.PlotOverlay.OWNER,
            depthOcclusion = false,
        )

        CruiseOutsideFlash(isOutside)
        if (!placed) CenterCrosshair(Modifier.align(Alignment.Center))

        MeasureBackButton { nav.popBackStack() }

        // Top radius slider — sampling-tool layout verbatim.
        Column(
            Modifier.align(Alignment.TopCenter)
                .statusBarsPadding()
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

        // North-up plot mini-map (ring + YOU) once the centre is placed —
        // top-right, below the full-width radius card (sampling parity;
        // iOS shows no plot number on the creation screen either).
        if (placed) SamplingPlotMiniMap()

        // U1 — capture/save-failure hints first, else the aim guidance
        // while placing (sampling-tool parity; the INSIDE/OUTSIDE status
        // is a value and stays in the bottom panel).
        MeasureTopChrome(
            instruction = failure
                ?: if (!placed) "Set the radius, aim at the plot centre, tap +" else null,
        )

        // U2 — bottom-centre shutter while aiming for the centre.
        if (!placed) MeasureShutterBar(onCapture = { place() })

        // Placed = the RESULT state: status panel with INSIDE/OUTSIDE +
        // distance line + Reset/Save plot, as before.
        if (placed) MeasureStatusPanel {
            CenteredText(
                if (isOutside) "OUTSIDE — walk back inside" else "INSIDE plot boundary",
                large = true,
                color = if (isOutside) colors.confidenceBad else colors.confidenceOk,
            )
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
                    enabled = !saving,
                ) {
                    ArSessionHub.clearPlot()
                    isOutside = false; distanceFromCenter = null
                }
                ForestixProminentButton(
                    "Save plot",
                    modifier = Modifier.weight(1f),
                    enabled = !saving,
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
