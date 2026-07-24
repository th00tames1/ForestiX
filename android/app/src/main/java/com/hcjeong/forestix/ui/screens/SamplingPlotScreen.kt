// Sampling plot — aim the crosshair at the plot centre and tap + to drop a
// clearly-visible centre marker (base sphere + pole + top sphere) and a thick
// boundary ring. Radius slider sits top; a consistent bottom panel shows a
// big INSIDE / OUTSIDE status + distance/area. Leaving the ring pulses a red
// border and vibrates.
//
// The plot lives in the app-scoped ArSessionHub as a REAL ARCore anchor:
//  - the centre stays pinned as ARCore corrects its map (the old raw-Vec3
//    marker drifted with VIO error over time/movement);
//  - the plot survives navigation — DBH/Height render it as a subdued
//    overlay through the same shared AR session (it is NOT persisted across
//    app restarts: the AR world it is defined in dies with the session);
//  - re-entering this screen with an active plot resumes it (Reset clears).
//
// Performance: the hub renders the plot nodes directly (a radius change
// rebuilds only the ring node against a cached material), the out-of-bounds
// flash is an isolated animation, and — once the centre is placed — the
// plane-grid renderer is switched OFF for the walking phase. The Depth API
// now stays ON the whole time: this screen runs camera-stream DEPTH
// OCCLUSION so the boundary ring passes BEHIND real trunks (a deliberate
// partial revert of the walking-phase depth shutdown — occlusion consumes
// a depth image every frame; the plane-renderer + material-cache fixes
// stay). A north-up plot mini-map floats top-right once the centre lands.

package com.hcjeong.forestix.ui.screens

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.hcjeong.forestix.LocalAppEnvironment
import com.hcjeong.forestix.ar.ArCameraView
import com.hcjeong.forestix.ar.ArSceneMarker
import com.hcjeong.forestix.ar.ArSessionHub
import com.hcjeong.forestix.common.Units
import com.hcjeong.forestix.data.MeasureKind
import com.hcjeong.forestix.data.QuickMeasureEntry
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixProminentButton
import com.hcjeong.forestix.ui.theme.ForestixRadius
import com.hcjeong.forestix.ui.theme.ForestixSpace
import com.hcjeong.forestix.ui.theme.ForestixWhiteButton
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.sqrt

@Composable
fun SamplingPlotScreen(nav: NavController) {
    val env = LocalAppEnvironment.current
    val context = LocalContext.current
    val controller = ArSessionHub.controller
    val haptics = remember { Haptics(context) }
    val colors = Forestix.colors

    // Active plot + radius live in the hub (shared with DBH/Height's
    // subdued overlay). Reading them here recomposes on place/reset.
    val plot = ArSessionHub.activePlot
    val radiusM = ArSessionHub.plotRadiusM
    val placed = plot != null

    var isOutside by remember { mutableStateOf(false) }
    var distanceFromCenter by remember { mutableStateOf<Double?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }

    // 5 Hz boundary check + haptics. Does NOT drive any animation/flash, so
    // it doesn't recompose the AR view — only the small readouts that read
    // it. Distance is measured to the ANCHOR's corrected pose.
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
                if (now && !isOutside) haptics.warn()           // crossed out
                else if (now && tick % 2 == 0) haptics.warn()   // every 0.4 s while out (iOS cadence)
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

    Box(Modifier.fillMaxSize()) {
        // The hub renders the plot itself (OWNER = full alpha). The plane
        // grid is only needed while AIMING for the centre; depth stays ON
        // throughout because ring occlusion consumes it per frame (the
        // ring hides behind real trunks — perf trade documented above).
        ArCameraView(
            controller,
            emptyList<ArSceneMarker>(),
            modifier = Modifier.fillMaxSize(),
            enableDepth = true,
            planeRenderer = !placed,
            plotOverlay = ArSessionHub.PlotOverlay.OWNER,
            depthOcclusion = true,
        )

        OutsideFlashOverlay(isOutside)
        if (!placed) CenterCrosshair(Modifier.align(Alignment.Center))

        MeasureBackButton { nav.popBackStack() }

        // Top radius slider — full-width card below the floating back row
        // (16 top inset + 44 button + 8 gap ⇒ top 68), iOS topControls.
        Column(
            Modifier.align(Alignment.TopCenter)
                .padding(top = 68.dp, start = ForestixSpace.md, end = ForestixSpace.md)
                .clip(ForestixRadius.card).background(Color.Black.copy(alpha = 0.55f)).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "SAMPLING RADIUS",
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
        // top-right, below the full-width radius card.
        if (placed) SamplingPlotMiniMap()

        // U1 — capture-failure hints first, else the aim guidance while
        // placing (iOS topBannerText parity; the placed state's
        // INSIDE/OUTSIDE status is a value and stays in the panel below).
        MeasureTopChrome(
            instruction = failure
                ?: if (!placed) "Set the radius, aim at the plot centre, tap +" else null,
        )

        // U2 — bottom-centre shutter while aiming for the centre (no
        // secondaries on this screen).
        if (!placed) MeasureShutterBar(onCapture = { place() })

        // Placed = this screen's RESULT state: the existing status panel
        // (INSIDE/OUTSIDE + distance line + Reset/Save) occupies the
        // bottom as before.
        if (placed) MeasureStatusPanel {
            CenteredText(
                if (isOutside) "OUTSIDE — walk back inside" else "INSIDE sampling area",
                large = true,
                color = if (isOutside) colors.confidenceBad else colors.confidenceOk,
            )
            // Distance line — iOS distanceLine format + style.
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
                ) {
                    ArSessionHub.clearPlot()
                    isOutside = false; distanceFromCenter = null
                }
                ForestixProminentButton(
                    "Save",
                    modifier = Modifier.weight(1f),
                ) {
                    val r = ArSessionHub.plotRadiusM
                    env.history.append(
                        QuickMeasureEntry(
                            kind = MeasureKind.SAMPLING_PLOT, value = r,
                            secondaryValue = Units.circleAreaM2(r),
                            sigma = null, confidenceRaw = "green", method = "ar.tap",
                            plotID = env.history.activePlotID.value,
                        )
                    )
                    nav.popBackStack()
                }
            }
        }
    }
}

/// Pulsing red border shown while the device is outside the plot. The pulse
/// is an isolated animation so it doesn't recompose the camera view.
@Composable
private fun BoxScope.OutsideFlashOverlay(active: Boolean) {
    if (!active) return
    val transition = rememberInfiniteTransition(label = "flash")
    val alpha by transition.animateFloat(
        initialValue = 0.35f, targetValue = 0.95f,
        animationSpec = infiniteRepeatable(tween(350), RepeatMode.Reverse), label = "alpha",
    )
    Box(Modifier.fillMaxSize().border(12.dp, Color.Red.copy(alpha = alpha)))
}
