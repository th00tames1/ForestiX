// Tree Diameter (DBH) — Android, with a live HUD matching iOS: a horizontal
// guide line, a crosshair ring that turns green when a trunk fit locks, a
// live "DBH x.x cm / Distance y.y m" badge, and a green fit-width chord
// drawn across the trunk. The committed reading still runs the full §7.1
// burst estimator (DBHEstimator) on Accept.

package com.hcjeong.forestix.ui.screens.dbh

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
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.hcjeong.forestix.LocalAppEnvironment
import com.hcjeong.forestix.ar.ArController
import com.hcjeong.forestix.ar.ArCameraView
import com.hcjeong.forestix.data.MeasureKind
import com.hcjeong.forestix.data.QuickMeasureEntry
import com.hcjeong.forestix.data.StemPosition
import com.hcjeong.forestix.sensors.ArDepthFrame
import com.hcjeong.forestix.sensors.ConfidenceTier
import com.hcjeong.forestix.sensors.DBHEstimator
import com.hcjeong.forestix.sensors.DBHResult
import com.hcjeong.forestix.sensors.DbhScanInput
import com.hcjeong.forestix.sensors.GuideAxis
import com.hcjeong.forestix.sensors.ProjectCalibration
import com.hcjeong.forestix.ui.screens.CenteredText
import com.hcjeong.forestix.ui.screens.MeasureBackButton
import com.hcjeong.forestix.ui.screens.MeasureControlColumn
import com.hcjeong.forestix.ui.screens.MeasureStatusPanel
import com.hcjeong.forestix.ui.theme.Forestix
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

private enum class Stage { AIMING, CAPTURING, RESULT }

@Composable
fun DBHScanScreen(nav: NavController) {
    val env = LocalAppEnvironment.current
    val controller = remember { ArController() }
    val scope = rememberCoroutineScope()
    val pendingTree = remember { env.history.suggestedNextTreeNumber }
    val colors = Forestix.colors

    var stage by remember { mutableStateOf(Stage.AIMING) }
    var result by remember { mutableStateOf<DBHResult?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<DBHEstimator.DbhPreview?>(null) }

    // Live single-frame preview loop while aiming (the iOS HUD analogue).
    LaunchedEffect(stage) {
        while (stage == Stage.AIMING) {
            controller.acquireDepthFrame()?.let { f ->
                preview = DBHEstimator.livePreview(
                    f, f.width / 2.0, f.height / 2.0, GuideAxis.Col(f.width / 2), ProjectCalibration.identity,
                )
            }
            delay(150)
        }
    }

    fun capture() {
        if (stage == Stage.CAPTURING) return
        stage = Stage.CAPTURING
        failure = null
        scope.launch {
            val frames = ArrayList<ArDepthFrame>()
            var attempts = 0
            while (frames.size < 12 && attempts < 30) {
                controller.acquireDepthFrame()?.let { frames.add(it) }
                attempts++
                delay(50)
            }
            if (frames.size < 5) {
                failure = "Couldn't read depth. Point at the trunk in good light, 1\u20133 m away, and hold steady."
                stage = Stage.AIMING
                return@launch
            }
            val w = frames.first().width
            val h = frames.first().height
            val input = DbhScanInput(
                frames = frames, tapX = w / 2.0, tapY = h / 2.0,
                guideAxis = GuideAxis.Col(w / 2), projectCalibration = ProjectCalibration.identity,
            )
            result = DBHEstimator.estimate(input)
            if (result == null) {
                failure = "Not enough depth points. Move closer and retry."
                stage = Stage.AIMING
            } else {
                stage = Stage.RESULT
            }
        }
    }

    val locked = stage == Stage.AIMING && preview?.locked == true

    Box(Modifier.fillMaxSize()) {
        ArCameraView(controller, emptyList(), modifier = Modifier.fillMaxSize())

        MeasureBackButton { nav.popBackStack() }

        // Guide line + live fit chord (drawn relative to screen centre).
        if (stage != Stage.RESULT) {
            Canvas(Modifier.fillMaxSize()) {
                val cy = size.height / 2f
                // Horizontal guide line (dual stroke for sun-glare contrast).
                drawLine(Color.Black.copy(alpha = 0.55f), Offset(0f, cy), Offset(size.width, cy), strokeWidth = 3f)
                drawLine(Color.White.copy(alpha = 0.9f), Offset(0f, cy), Offset(size.width, cy), strokeWidth = 1.5f)
                // Live fit-width chord — width grows with diameter, shrinks
                // with distance (a recognition + size indicator).
                val p = preview
                if (locked && p != null && p.distanceM > 0f) {
                    val chord = ((p.diameterCm / 100f) / p.distanceM * size.width * 0.9f)
                        .coerceIn(0f, size.width * 0.95f)
                    if (chord > 8f) {
                        val cx = size.width / 2f
                        val x0 = cx - chord / 2f; val x1 = cx + chord / 2f
                        val g = Color(0xFF4A9B5C)
                        drawLine(g, Offset(x0, cy), Offset(x1, cy), strokeWidth = 5f, cap = StrokeCap.Round)
                        // side bars
                        drawLine(g, Offset(x0, cy - 22f), Offset(x0, cy + 22f), strokeWidth = 4f)
                        drawLine(g, Offset(x1, cy - 22f), Offset(x1, cy + 22f), strokeWidth = 4f)
                    }
                }
            }

            // Crosshair ring — green locked / amber aligning.
            DbhRing(locked, colors.confidenceOk, colors.confidenceWarn, Modifier.align(Alignment.Center))

            // Live preview badge just below the ring.
            Box(Modifier.align(Alignment.Center).offset(y = 64.dp)) {
                LivePreviewBadge(stage, preview, locked)
            }
        }

        if (stage == Stage.AIMING) MeasureControlColumn(onCapture = { capture() })

        MeasureStatusPanel {
            failure?.let { CenteredText(it, dim = true) }
            when (stage) {
                Stage.AIMING -> CenteredText(if (locked) "Trunk locked \u2014 tap + to scan" else "Aim at the trunk (1\u20133 m), centre the line")
                Stage.CAPTURING -> CenteredText("Scanning trunk\u2026 hold steady")
                Stage.RESULT -> {}
            }
            result?.let { r ->
                if (r.confidence == ConfidenceTier.RED) {
                    CenteredText("DBH \u2014 check", large = true)
                    r.rejectionReason?.let { CenteredText(it, dim = true) }
                } else {
                    CenteredText(String.format(Locale.US, "DBH %.1f cm  \u00B1%.0f mm", r.diameterCm, r.sigmaRmm), large = true)
                    CenteredText(String.format(Locale.US, "arc %.0f\u00B0 \u00B7 n=%d \u00B7 %s", r.arcCoverageDeg, r.nInliers, r.confidence.raw.uppercase()), dim = true)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { result = null; failure = null; stage = Stage.AIMING }, modifier = Modifier.weight(1f)) { Text("Retake") }
                    Button(
                        onClick = {
                            env.history.append(
                                QuickMeasureEntry(
                                    kind = MeasureKind.DBH, value = r.diameterCm.toDouble(),
                                    sigma = r.sigmaRmm.toDouble(), confidenceRaw = r.confidence.raw,
                                    method = r.method.raw, treeNumber = pendingTree,
                                    plotID = env.history.activePlotID.value, position = StemPosition.DBH,
                                )
                            )
                            nav.popBackStack()
                        },
                        enabled = r.confidence != ConfidenceTier.RED,
                        modifier = Modifier.weight(1f),
                    ) { Text("Accept") }
                }
            }
        }
    }
}

@Composable
private fun DbhRing(locked: Boolean, ok: Color, warn: Color, modifier: Modifier) {
    val ring = if (locked) ok else warn
    Box(modifier.size(72.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(72.dp).border(5.dp, Color.Black.copy(alpha = 0.6f), CircleShape))
        Box(Modifier.size(64.dp).border(2.5.dp, ring, CircleShape))
    }
}

@Composable
private fun LivePreviewBadge(stage: Stage, preview: DBHEstimator.DbhPreview?, locked: Boolean) {
    val type = Forestix.type
    val p = preview ?: return
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        if (locked) {
            Text(
                String.format(Locale.US, "DBH: %.1f cm", p.diameterCm),
                style = type.data, color = Color.White,
                modifier = Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.65f)).padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        if (p.distanceM > 0f) {
            Text(
                String.format(Locale.US, "Distance: %.2f m", p.distanceM),
                style = type.dataSmall, color = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}
