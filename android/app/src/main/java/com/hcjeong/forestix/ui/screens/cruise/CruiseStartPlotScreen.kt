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
import com.hcjeong.forestix.common.MeasurementFormatter
import com.hcjeong.forestix.common.Units
import com.hcjeong.forestix.data.cruise.Plot
import com.hcjeong.forestix.data.cruise.PositionSource
import com.hcjeong.forestix.geo.CoordinateConversions
import com.hcjeong.forestix.positioning.GPSAveraging
import com.hcjeong.forestix.positioning.LocationService
import com.hcjeong.forestix.ui.screens.CenterCrosshair
import com.hcjeong.forestix.ui.screens.CenteredText
import com.hcjeong.forestix.ui.screens.Haptics
import com.hcjeong.forestix.ui.screens.MeasureBackButton
import com.hcjeong.forestix.ui.screens.MeasureShutterBar
import com.hcjeong.forestix.ui.screens.MeasureStatusPanel
import com.hcjeong.forestix.ui.screens.MeasureTopChrome
import com.hcjeong.forestix.ui.screens.PLOT_GROUND_NOT_SEEN
import com.hcjeong.forestix.ui.screens.PLOT_TRACKING_LOST_HINT
import com.hcjeong.forestix.ui.screens.PLOT_TRACKING_LOST_STATUS
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
    // The ring, the slider and the area line all read the cruiser's units.
    // The stored radius stays METRES — that is what the AR ring, the
    // inside/outside test and the saved plot are in; only the dial's scale
    // and the readouts change. (SamplingPlotScreen is the twin of this card;
    // fixing one does not fix the other.)
    val settings by env.settings.state.collectAsStateWithLifecycle()
    val unitSystem = settings.unitSystem
    // ARCore stopped correcting the plot's anchor pose, so the hub hid the
    // ring — everything this screen says about the plot comes from that same
    // pose and goes unknown with it (sampling-tool parity).
    val trackingLost = placed && ArSessionHub.plotTrackingLost

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
            failure = PLOT_GROUND_NOT_SEEN
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
        // FRESHNESS-GATED, exactly as `save()` below. A re-centre writes a
        // plot centre, so it is the same act and it answers to the same
        // rule: `latestSnapshot` is never cleared and
        // `LocationService.lastGlobalFix` outlives the screen, so ungated
        // they hand back the last fix that ever got through — an hour old, a
        // valley away — and this path would stamp it as a real single fix.
        // Moving a plot centre to yesterday's position is the same invisible
        // data loss the `recentred` test exists to prevent, arrived at from
        // the other side.
        val snap = if (recentred) {
            freshFixOrNull(fix ?: LocationService.lastGlobalFix, System.currentTimeMillis())
        } else {
            null
        }
        if (snap != null) {
            val acc = snap.horizontalAccuracyM.toFloat()
            existing.centerLat = snap.latitude
            existing.centerLon = snap.longitude
            // ONE fix, named as one. This path never opened an averaging
            // window, so it may not wear GPS_AVERAGED, and the tier comes
            // from the single-fix rule rather than from `classify` with a
            // spread of zero it never measured.
            existing.positionSource = PositionSource.GPS_SINGLE
            existing.gpsNSamples = 1
            existing.gpsMedianHAccuracyM = acc
            existing.gpsSampleStdXyM = 0f
            // Still stored, still exported — just never shown (F9).
            existing.positionTier = GPSAveraging.classifySingleFix(acc)
        }
        val refusedRecentre = recentred && snap == null
        env.plotRepository.update(existing)
        // Re-link so the mini-map trusts the AR-anchor path for YOU again —
        // but ONLY when that ring is genuinely this plot's centre. On a
        // refused re-centre the stored centre stayed where it was, so linking
        // would draw YOU against a ring the plot was never moved to. Left
        // unlinked, the mini-map falls through to the GPS path measured from
        // the centre the plot actually has.
        if (!refusedRecentre && ArSessionHub.activePlot != null) ArSessionHub.linkPlot(id)
        // A button that did not do what it looked like it did has to say so.
        // The radius edit landed; the re-placed ring did not become the new
        // centre, and without this the cruiser walks away believing it did.
        // The screen stays up (see `save()`) so the message is read and the
        // re-centre can be retried once there is sky.
        failure = if (refusedRecentre) {
            "No GPS fix — the plot keeps its recorded centre. " +
                "The radius was saved. Step out for sky and try again."
        } else {
            null
        }
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
                    // A refused re-centre leaves `failure` set: stay on the
                    // screen so the cruiser reads why the ring they just
                    // placed is not the new centre, and can try again from
                    // here the moment a fix arrives.
                    if (failure == null) nav.popBackStack() else saving = false
                    return@launch
                }
                val pid = UUID.fromString(projectId)
                // FRESHNESS-GATED, and REFUSED when there is nothing usable.
                //
                // This used to fall through to `snap?.latitude ?: 0.0` and
                // still stamp the row PositionSource.GPS_AVERAGED with a
                // tier off the accuracy table — so a plot placed with no fix
                // was saved at (0, 0), off West Africa, wearing the app's
                // best position label. Every tree tallied into it inherited
                // that centre, and nothing downstream could tell it from a
                // real one. A plot centre is the anchor for everything
                // measured in the plot; refusing to save is the only honest
                // answer when the app does not know where it is.
                val snap = freshFixOrNull(
                    fix ?: LocationService.lastGlobalFix,
                    System.currentTimeMillis())
                if (snap == null) {
                    failure = "No GPS fix — the plot centre would be saved " +
                        "in the wrong place. Step out for sky and try again."
                    saving = false
                    return@launch
                }
                val r = ArSessionHub.plotRadiusM
                val number = (env.plotRepository.listByProject(pid)
                    .maxOfOrNull { it.plotNumber } ?: 0) + 1
                val acc = snap.horizontalAccuracyM.toFloat()
                val newPlot = Plot(
                    id = UUID.randomUUID(),
                    projectId = pid,
                    plannedPlotId = null,
                    plotNumber = number,
                    centerLat = snap.latitude,
                    centerLon = snap.longitude,
                    // ONE fix, named as one — see PositionSource.GPS_SINGLE.
                    // Nothing on this path opens an averaging window, so
                    // neither the source nor the tier may be borrowed from
                    // the one that does; nSamples = 1 records the shortcut.
                    positionSource = PositionSource.GPS_SINGLE,
                    positionTier = GPSAveraging.classifySingleFix(acc),
                    gpsNSamples = 1,
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
                // A plot that has just come into existence is the one thing
                // on the map worth looking at, and the first question about
                // it is whether the cruiser is inside its boundary. Ask the
                // map home — which owns the camera this screen cannot reach
                // — to frame the ring when it comes back up.
                PendingPlotFraming.requestFraming(
                    CoordinateConversions.LatLon(
                        latitude = snap.latitude, longitude = snap.longitude),
                    r,
                )
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
                Text(
                    // The same ring the cruise map draws, so the same label
                    // its plot banner and mini-map put on it.
                    MeasurementFormatter.plotLength(radiusM, unitSystem),
                    style = Forestix.type.data,
                    color = Color.White,
                )
            }
            Slider(
                // Drags in the cruiser's unit and converts back on the way in,
                // so an imperial thumb lands on whole and half FEET instead of
                // on whichever foot value happens to sit on a half-metre stop.
                // A one-way format here would be a bug: this control writes.
                value = MeasurementFormatter.plotRadiusDisplay(radiusM, unitSystem).toFloat(),
                onValueChange = {
                    ArSessionHub.setPlotRadius(
                        MeasurementFormatter.plotRadiusMetres(it.toDouble(), unitSystem))
                },
                valueRange = MeasurementFormatter.plotRadiusSliderRange(unitSystem),
                steps = MeasurementFormatter.plotRadiusSliderSteps(unitSystem),
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
                ?: if (trackingLost) PLOT_TRACKING_LOST_HINT
                else if (!placed) "Set the radius, aim at the plot centre, tap +" else null,
        )

        // U2 — bottom-centre shutter while aiming for the centre.
        if (!placed) MeasureShutterBar(onCapture = { place() })

        // Placed = the RESULT state: status panel with INSIDE/OUTSIDE +
        // distance line + Reset/Save plot, as before.
        if (placed) MeasureStatusPanel {
            CenteredText(
                if (trackingLost) PLOT_TRACKING_LOST_STATUS
                else if (isOutside) "OUTSIDE — walk back inside" else "INSIDE plot boundary",
                large = true,
                color = when {
                    trackingLost -> colors.confidenceWarn
                    isOutside -> colors.confidenceBad
                    else -> colors.confidenceOk
                },
            )
            Text(
                // Both halves in the cruiser's unit — the distance to the
                // centre is a measurement, the plot area is sized the way
                // plots are sized in each convention.
                distanceFromCenter?.let {
                    "Centre: " + MeasurementFormatter.distance(it, unitSystem) +
                        " · area: " +
                        MeasurementFormatter.plotArea(Units.circleAreaM2(radiusM), unitSystem)
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
