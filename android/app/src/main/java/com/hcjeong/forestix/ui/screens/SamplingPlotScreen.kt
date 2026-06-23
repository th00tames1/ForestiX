// Sampling plot — aim the crosshair at the plot centre and tap + to drop a
// tall, clearly-visible centre pole (base sphere + pole + top sphere) plus a
// thick boundary ring. The radius slider sits top; a consistent bottom panel
// shows a big INSIDE / OUTSIDE status + distance/area. Leaving the ring
// flashes the screen border red and vibrates.

package com.hcjeong.forestix.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
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
    var flashOn by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(200)
            flashOn = !flashOn
            val c = center
            val cam = controller.currentCameraPosition()
            if (c == null || cam == null) {
                isOutside = false; distanceFromCenter = null
            } else {
                val dx = cam.x - c.x; val dz = cam.z - c.z
                val d = sqrt((dx * dx + dz * dz).toDouble())
                distanceFromCenter = d
                val nowOutside = d > radiusM
                if (nowOutside != isOutside) {
                    isOutside = nowOutside
                    if (nowOutside) haptics.warn()
                } else if (isOutside && flashOn) {
                    haptics.warn()
                }
            }
        }
    }

    fun markers(): List<ArSceneMarker> {
        val c = center ?: return emptyList()
        val out = mutableListOf<ArSceneMarker>()
        val white = floatArrayOf(1f, 1f, 1f, 1f)
        val red = floatArrayOf(1f, 0.25f, 0.25f, 1f)
        val yellow = floatArrayOf(1f, 0.85f, 0.15f, 1f)
        // Exact-centre base sphere so the tapped point is unmistakable.
        out.add(ArSceneMarker(c, MarkerShape.Sphere(0.07f), red))
        // Tall white pole rising from the centre (base at the tapped point).
        out.add(ArSceneMarker(Vec3(c.x, c.y + 0.6f, c.z), MarkerShape.Cylinder(0.05f, 1.2f), white))
        // Bright top sphere — visible from far so the cruiser can find it.
        out.add(ArSceneMarker(Vec3(c.x, c.y + 1.2f, c.z), MarkerShape.Sphere(0.12f), yellow))
        // Thick boundary ring, cyan inside / red outside.
        val ringColor = if (isOutside) floatArrayOf(1f, 0.2f, 0.2f, 1f) else floatArrayOf(0.2f, 0.85f, 1f, 1f)
        out.add(ArSceneMarker(Vec3(c.x, c.y + 0.02f, c.z), MarkerShape.Ring(radiusM.toFloat(), 0.18f), ringColor))
        return out
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
        ArCameraView(controller, markers(), modifier = Modifier.fillMaxSize())

        if (isOutside) {
            Box(Modifier.fillMaxSize().border(14.dp, Color.Red.copy(alpha = if (flashOn) 0.95f else 0.35f)))
        }
        if (center == null) CenterCrosshair(Modifier.align(Alignment.Center))

        MeasureBackButton { nav.popBackStack() }

        // Top radius slider — left-padded to clear the back button.
        Column(
            Modifier.align(Alignment.TopCenter)
                .padding(top = 8.dp, start = 72.dp, end = 16.dp)
                .clip(RoundedCornerShape(ForestixSpace.sm)).background(Color.Black.copy(alpha = 0.6f)).padding(ForestixSpace.sm),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("SAMPLING RADIUS", style = Forestix.type.sectionHead, color = Color.White.copy(alpha = 0.85f))
                Text(String.format(Locale.US, "%.1f m", radiusM), style = Forestix.type.data, color = Color.White)
            }
            Slider(value = radiusM.toFloat(), onValueChange = { radiusM = it.toDouble() }, valueRange = 1f..30f, steps = 57)
        }

        // Capture button only before the centre is placed.
        if (center == null) MeasureControlColumn(onCapture = { place() })

        MeasureStatusPanel {
            failure?.let { CenteredText(it, dim = true) }
            if (center == null) {
                CenteredText("Aim at the plot centre, tap + to drop it")
            } else {
                CenteredText(
                    if (isOutside) "OUTSIDE \u2014 walk back inside" else "INSIDE sampling area",
                    large = true,
                    color = if (isOutside) colors.confidenceBad else colors.confidenceOk,
                )
                val d = distanceFromCenter
                CenteredText(
                    if (d == null) "\u2014"
                    else String.format(Locale.US, "%.1f m from centre \u00B7 %.0f m\u00B2", d, Units.circleAreaM2(radiusM)),
                    dim = true,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ForestixSpace.sm)) {
                    OutlinedButton(onClick = {
                        center = null; isOutside = false; distanceFromCenter = null
                    }, modifier = Modifier.weight(1f)) { Text("Reset") }
                    Button(modifier = Modifier.weight(1f), onClick = {
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
                    }) { Text("Save") }
                }
            }
        }
    }
}
