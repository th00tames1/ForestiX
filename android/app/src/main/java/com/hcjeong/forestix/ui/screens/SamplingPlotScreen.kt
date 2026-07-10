// Sampling plot — aim the crosshair at the plot centre and tap + to drop a
// clearly-visible centre marker (base sphere + pole + top sphere) and a thick
// boundary ring. Radius slider sits top; a consistent bottom panel shows a
// big INSIDE / OUTSIDE status + distance/area. Leaving the ring pulses a red
// border and vibrates.
//
// Performance: the marker list is hoisted into `remember(center, radius)` so
// the AR nodes are NOT rebuilt on every frame, and the out-of-bounds flash is
// an isolated animation (not a 5 Hz full-screen recomposition) — both were
// causing severe lag.

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
import com.hcjeong.forestix.ar.ArController
import com.hcjeong.forestix.ar.ArCameraView
import com.hcjeong.forestix.ar.ArSceneMarker
import com.hcjeong.forestix.ar.MarkerShape
import com.hcjeong.forestix.ar.Vec3
import com.hcjeong.forestix.common.Units
import com.hcjeong.forestix.data.MeasureKind
import com.hcjeong.forestix.data.QuickMeasureEntry
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixBorderedButton
import com.hcjeong.forestix.ui.theme.ForestixProminentButton
import com.hcjeong.forestix.ui.theme.ForestixRadius
import com.hcjeong.forestix.ui.theme.ForestixSpace
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.sqrt

@Composable
fun SamplingPlotScreen(nav: NavController) {
    val env = LocalAppEnvironment.current
    val context = LocalContext.current
    val controller = remember { ArController() }
    val haptics = remember { Haptics(context) }
    val colors = Forestix.colors

    var center by remember { mutableStateOf<Vec3?>(null) }
    var radiusM by remember { mutableStateOf(8.0) }
    var isOutside by remember { mutableStateOf(false) }
    var distanceFromCenter by remember { mutableStateOf<Double?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }

    // 5 Hz boundary check + haptics. Does NOT drive any animation/flash, so
    // it doesn't recompose the AR view — only the small readouts that read it.
    LaunchedEffect(Unit) {
        var tick = 0
        while (true) {
            delay(200)
            val c = center
            val cam = controller.currentCameraPosition()
            if (c == null || cam == null) {
                isOutside = false; distanceFromCenter = null
            } else {
                val dx = cam.x - c.x; val dz = cam.z - c.z
                val d = sqrt((dx * dx + dz * dz).toDouble())
                distanceFromCenter = d
                val now = d > radiusM
                if (now && !isOutside) haptics.warn()           // crossed out
                else if (now && tick % 2 == 0) haptics.warn()   // every 0.4 s while out (iOS cadence)
                isOutside = now
            }
            tick++
        }
    }

    // Markers rebuilt ONLY when the centre or radius changes (stable instance
    // otherwise) so the ring geometry isn't recreated every frame.
    val markerList = remember(center, radiusM) {
        val c = center ?: return@remember emptyList<ArSceneMarker>()
        listOf(
            ArSceneMarker(c, MarkerShape.Sphere(0.07f), floatArrayOf(1f, 0.25f, 0.25f, 1f)),
            ArSceneMarker(Vec3(c.x, c.y + 0.6f, c.z), MarkerShape.Cylinder(0.05f, 1.2f), floatArrayOf(1f, 1f, 1f, 1f)),
            ArSceneMarker(Vec3(c.x, c.y + 1.2f, c.z), MarkerShape.Sphere(0.12f), floatArrayOf(1f, 0.85f, 0.15f, 1f)),
            ArSceneMarker(Vec3(c.x, c.y + 0.02f, c.z), MarkerShape.Ring(radiusM.toFloat(), 0.4f), floatArrayOf(0.2f, 0.85f, 1f, 1f)),
        )
    }

    fun place() {
        if (center != null) return
        val hit = controller.screenCenterHit() ?: controller.forwardPointAtHorizontalDistance(3f)
        if (hit == null) {
            failure = "Couldn't read scene depth. Aim at the ground and try again."
            return
        }
        failure = null; center = hit
    }

    Box(Modifier.fillMaxSize()) {
        ArCameraView(controller, markerList, modifier = Modifier.fillMaxSize())

        OutsideFlashOverlay(isOutside)
        if (center == null) CenterCrosshair(Modifier.align(Alignment.Center))

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
                onValueChange = { radiusM = it.toDouble() },
                valueRange = 1f..30f,
                steps = 57,
                colors = SliderDefaults.colors(
                    thumbColor = colors.confidenceWarn,
                    activeTrackColor = colors.confidenceWarn,
                ),
            )
        }

        if (center == null) MeasureControlColumn(onCapture = { place() })

        MeasureStatusPanel {
            failure?.let {
                Text(it, style = Forestix.type.caption, color = Color.White)
            }
            if (center == null) {
                CenteredText("Set the radius, aim at the plot centre, tap +")
            } else {
                CenteredText(
                    if (isOutside) "OUTSIDE \u2014 walk back inside" else "INSIDE sampling area",
                    large = true,
                    color = if (isOutside) colors.confidenceBad else colors.confidenceOk,
                )
            }
            // Distance line \u2014 always rendered ("\u2014" until the centre lands),
            // iOS distanceLine format + style.
            Text(
                distanceFromCenter?.let {
                    String.format(Locale.US, "Centre: %.2f m \u00B7 area: %.1f m\u00B2", it, Units.circleAreaM2(radiusM))
                } ?: "\u2014",
                style = Forestix.type.dataSmall,
                color = Color.White.copy(alpha = 0.85f),
            )
            // Reset / Save always visible, both disabled until the centre
            // is placed; Save exits the flow (both platforms).
            Row(
                Modifier.fillMaxWidth().padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ForestixBorderedButton(
                    "Reset",
                    modifier = Modifier.weight(1f),
                    enabled = center != null,
                ) {
                    center = null; isOutside = false; distanceFromCenter = null
                }
                ForestixProminentButton(
                    "Save",
                    modifier = Modifier.weight(1f),
                    enabled = center != null,
                ) {
                    val r = radiusM
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
