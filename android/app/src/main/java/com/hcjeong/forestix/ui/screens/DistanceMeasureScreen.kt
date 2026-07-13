// Distance measurement — Android mirror of the iOS redesign. Two modes
// (Live, Two-point) toggled by a pill under the "+" capture button. The "+"
// captures against the centre crosshair: in Live it logs the device→target
// distance, in Two-point it drops point A then B. Points + line are a crisp
// 2D overlay (no oversized 3D spheres); the readout is a centred half-width
// panel. There is NO sensor-source toggle (field fix, both platforms) — the
// Depth API is used automatically when the device supports it.

package com.hcjeong.forestix.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.hcjeong.forestix.LocalAppEnvironment
import com.hcjeong.forestix.ar.ArController
import com.hcjeong.forestix.ar.ArCameraView
import com.hcjeong.forestix.ar.Vec3
import com.hcjeong.forestix.ar.distance
import com.hcjeong.forestix.common.MeasurementFormatter
import com.hcjeong.forestix.data.MeasureKind
import com.hcjeong.forestix.data.QuickMeasureEntry
import com.hcjeong.forestix.data.ResearchLog
import com.hcjeong.forestix.sensors.DistanceSmoother
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hcjeong.forestix.ui.softDropShadow
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixProminentButton
import com.hcjeong.forestix.ui.theme.ForestixWhiteButton
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

private enum class DistMode { LIVE, TWO_POINT }

@Composable
fun DistanceMeasureScreen(nav: NavController) {
    val env = LocalAppEnvironment.current
    val context = LocalContext.current
    val settings by env.settings.state.collectAsStateWithLifecycle()
    val controller = remember { ArController() }
    val density = LocalDensity.current
    fun formatDistance(m: Double) = MeasurementFormatter.distance(m, settings.unitSystem)

    var mode by remember { mutableStateOf(DistMode.LIVE) }
    var liveDistance by remember { mutableStateOf<Double?>(null) }
    var pointA by remember { mutableStateOf<Vec3?>(null) }
    var pointB by remember { mutableStateOf<Vec3?>(null) }
    var screenA by remember { mutableStateOf<Offset?>(null) }
    var screenB by remember { mutableStateOf<Offset?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }

    // Hampel-style robust moving average for the non-depth (plane hit-test)
    // path — the raw AR distance flickers there. Depth-API values stay raw.
    val arSmoother = remember { DistanceSmoother() }
    // Developer-mode research capture: tape-measured true distance (m).
    var researchTrueM by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(50)
            controller.preferDepth = true
            val raw = controller.cameraToCenterDistance()
            liveDistance = if (controller.supportsDepth) {
                arSmoother.reset()
                raw
            } else {
                raw?.let { arSmoother.add(it) }
                arSmoother.value()
            }
        }
    }

    val twoPointDistance: Double? = run {
        val a = pointA; val b = pointB
        if (a != null && b != null) distance(a, b).toDouble() else null
    }

    fun hitForSource(): Vec3? {
        controller.preferDepth = true
        return controller.screenCenterHit() ?: controller.forwardPointAtHorizontalDistance(3f)
    }

    fun resetTwoPoint() { pointA = null; pointB = null; screenA = null; screenB = null; failure = null }

    fun placeAtCenter() {
        val hit = hitForSource()
        if (hit == null) {
            failure = "No surface in view. Move slightly so the camera picks up texture, then tap again."
            return
        }
        failure = null
        val centre = Offset(viewSize.width / 2f, viewSize.height / 2f)
        when {
            pointA == null -> { pointA = hit; screenA = centre }
            pointB == null -> { pointB = hit; screenB = centre }
            else -> { pointA = hit; screenA = centre; pointB = null; screenB = null }
        }
    }

    fun save(d: Double, kind: String) {
        // iOS raw-value vocabulary ("live.lidar" / "two-point.ar") so the
        // two platforms' CSVs filter identically — "lidar" here means the
        // depth path (ARCore Depth API on Android).
        val src = if (controller.supportsDepth) "lidar" else "ar"
        env.history.append(distanceEntry(d, "$kind.$src", env.history.activePlotID.value))
        // Developer-mode research CSV row — measured vs tape distance,
        // sensing source, and aim pitch (distance-accuracy study).
        if (settings.developerMode) {
            val fields = mutableMapOf(
                "measure_type" to "distance",
                "method" to kind,
                "depth_source" to src,
                "measured_value" to String.format(Locale.US, "%.3f", d),
                "unit" to "m",
                "distance_m" to String.format(Locale.US, "%.3f", d),
            )
            if (settings.researchTreeId.isNotEmpty()) {
                fields["tree_id"] = settings.researchTreeId  // repeat auto-filled by record()
            }
            controller.cameraForwardElevationRad()?.let {
                fields["pitch_deg"] = String.format(Locale.US, "%.1f", it * 180f / Math.PI.toFloat())
            }
            researchTrueM.toDoubleOrNull()?.takeIf { it > 0 }?.let { t ->
                fields["true_value"] = String.format(Locale.US, "%.3f", t)
                fields["error"] = String.format(Locale.US, "%.3f", d - t)
            }
            ResearchLog.record(context, fields)
        }
    }

    fun capture() {
        when (mode) {
            DistMode.LIVE -> { val d = liveDistance ?: return; save(d, "live") }
            DistMode.TWO_POINT -> placeAtCenter()
        }
    }

    fun screenPoint(world: Vec3?, fallback: Offset?): Offset? {
        if (world == null) return fallback
        val p = controller.projectToScreen(world) ?: return fallback
        return Offset(p.first, p.second)
    }

    Box(Modifier.fillMaxSize().onSizeChanged { viewSize = it }) {
        // No 3D markers — the points + line are a 2D overlay below.
        ArCameraView(controller, emptyList(), preferDepth = true, modifier = Modifier.fillMaxSize())

        MeasureBackButton { nav.popBackStack() }

        CenterCrosshair(Modifier.align(Alignment.Center))

        if (mode == DistMode.TWO_POINT) {
            val a = screenPoint(pointA, screenA)
            val b = screenPoint(pointB, screenB)
            if (a != null && b != null) {
                Box(Modifier.fillMaxSize().drawBehind {
                    drawLine(Color.White, a, b, strokeWidth = with(density) { 7.dp.toPx() }, cap = StrokeCap.Round)
                    drawLine(Color.Yellow, a, b, strokeWidth = with(density) { 3.5.dp.toPx() }, cap = StrokeCap.Round)
                })
            }
            a?.let { PointDot(it, density) }
            b?.let { PointDot(it, density) }
            if (a != null && b != null && twoPointDistance != null) {
                DistancePill(formatDistance(twoPointDistance), Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f))
            }
        }

        // Right-centre + button with Live/Two-point pill beneath.
        MeasureControlColumn(
            onCapture = { capture() },
            extra = {
                MeasurePill(if (mode == DistMode.LIVE) "Live" else "2-Pt") {
                    mode = if (mode == DistMode.LIVE) DistMode.TWO_POINT else DistMode.LIVE
                    resetTwoPoint()
                }
            },
        )

        // Bottom-centre readout — row order: header, value, hint, dev
        // fields, buttons (iOS bottomPanel).
        MeasureStatusPanel {
            failure?.let {
                Text(it, style = Forestix.type.caption, color = Color.White)
            }
            Text(
                if (mode == DistMode.LIVE) "DEVICE → TARGET" else "POINT A → POINT B",
                style = Forestix.type.sectionHead.copy(letterSpacing = 1.2.sp),
                color = Color.White.copy(alpha = 0.75f),
            )
            val value = if (mode == DistMode.LIVE) liveDistance else twoPointDistance
            CenteredText(value?.let { formatDistance(it) } ?: "—", large = true)
            if (mode == DistMode.TWO_POINT) {
                Text(
                    twoPointHint(pointA, pointB),
                    style = Forestix.type.caption,
                    color = Color.White.copy(alpha = 0.85f),
                )
            }
            if (settings.developerMode) {
                ResearchFieldsRow(
                    targetValue = settings.researchTreeId,
                    onTargetChange = { env.settings.setResearchTreeId(it.trim()) },
                    targetPlaceholder = "D1",
                    trueLabel = "True (m)",
                    trueValue = researchTrueM,
                    onTrueChange = { researchTrueM = it.filter { c -> c.isDigit() || c == '.' } },
                    truePlaceholder = "tape",
                )
            }
            when (mode) {
                DistMode.LIVE -> {
                    // Explicit full-width Save alongside the "+" capture —
                    // disabled until a distance locks (iOS actionRow).
                    ForestixProminentButton(
                        "Save",
                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                        enabled = liveDistance != null,
                    ) {
                        val d = liveDistance
                        if (d != null) save(d, "live")
                    }
                }
                DistMode.TWO_POINT -> {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ForestixWhiteButton("Reset", modifier = Modifier.weight(1f)) { resetTwoPoint() }
                        ForestixProminentButton(
                            "Save",
                            modifier = Modifier.weight(1f),
                            enabled = twoPointDistance != null,
                        ) {
                            val d = twoPointDistance
                            if (d != null) { save(d, "two-point"); resetTwoPoint() }
                        }
                    }
                }
            }
        }
    }
}

/// Two-point endpoint marker — white Ø22 disc with a yellow Ø16 core,
/// centred exactly on the projected world point (iOS pointDot).
@Composable
private fun PointDot(at: Offset, density: Density) {
    val half = with(density) { 11.dp.toPx() }
    Box(
        Modifier
            .offset { IntOffset((at.x - half).roundToInt(), (at.y - half).roundToInt()) }
            .size(22.dp)
            .clip(CircleShape)
            .background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(16.dp).clip(CircleShape).background(Color.Yellow))
    }
}

/// Distance label pill centred on the exact A–B midpoint (both axes) with
/// the iOS drop shadow (black 0.25, r3, y1).
@Composable
private fun DistancePill(text: String, center: Offset) {
    Box(Modifier.fillMaxSize()) {
        Text(
            text, color = Color.Black,
            style = Forestix.type.data.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 16.sp),
            modifier = Modifier
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        placeable.place(
                            (center.x - placeable.width / 2f).roundToInt(),
                            (center.y - placeable.height / 2f).roundToInt(),
                        )
                    }
                }
                .softDropShadow(Color.Black.copy(alpha = 0.25f), blurRadius = 3.dp, offsetY = 1.dp, cornerRadius = 12.dp)
                .clip(RoundedCornerShape(12.dp)).background(Color.White)
                .border(1.dp, Color.Black.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

private fun twoPointHint(a: Vec3?, b: Vec3?) = when {
    a == null -> "Aim, tap + to place point A"
    b == null -> "Aim, tap + to place point B"
    else -> "Tap Save to log this reading"
}


private fun distanceEntry(d: Double, method: String, plotID: UUID?) = QuickMeasureEntry(
    kind = MeasureKind.DISTANCE, value = d, sigma = null,
    confidenceRaw = "green", method = method, plotID = plotID,
)
