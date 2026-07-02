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
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.hcjeong.forestix.LocalAppEnvironment
import com.hcjeong.forestix.ar.ArController
import com.hcjeong.forestix.ar.ArCameraView
import com.hcjeong.forestix.ar.ArSceneMarker
import com.hcjeong.forestix.ar.MarkerShape
import com.hcjeong.forestix.ar.Vec3
import com.hcjeong.forestix.sensors.ArCaliperDbh
import com.hcjeong.forestix.data.MeasureKind
import com.hcjeong.forestix.data.QuickMeasureEntry
import com.hcjeong.forestix.data.StemPosition
import com.hcjeong.forestix.sensors.ArDepthFrame
import com.hcjeong.forestix.sensors.ChordAlgorithm
import com.hcjeong.forestix.sensors.ConfidenceTier
import com.hcjeong.forestix.sensors.DBHEstimator
import com.hcjeong.forestix.sensors.DBHResult
import com.hcjeong.forestix.sensors.DistanceSmoother
import com.hcjeong.forestix.sensors.GuideAxis
import com.hcjeong.forestix.sensors.ProjectCalibration
import com.hcjeong.forestix.sensors.VioMotionDbh
import com.hcjeong.forestix.ui.screens.CenteredText
import com.hcjeong.forestix.ui.screens.DevHud
import com.hcjeong.forestix.ui.screens.MeasureBackButton
import com.hcjeong.forestix.ui.screens.MeasureControlColumn
import com.hcjeong.forestix.ui.screens.MeasureStatusPanel
import com.hcjeong.forestix.ui.theme.Forestix
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

private enum class Stage { AIMING, CAPTURING, RESULT }

/// Sub-measurements per hold-steady capture; the 3 closest to the median
/// are kept (2 largest deviations trimmed) and averaged.
private const val SAMPLE_COUNT = 5

@Composable
fun DBHScanScreen(nav: NavController) {
    val env = LocalAppEnvironment.current
    val controller = remember { ArController() }
    val scope = rememberCoroutineScope()
    val pendingTree = remember { env.history.suggestedNextTreeNumber }
    val colors = Forestix.colors

    val settings by env.settings.state.collectAsStateWithLifecycle()
    var stage by remember { mutableStateOf(Stage.AIMING) }
    var result by remember { mutableStateOf<DBHResult?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<DBHEstimator.DbhPreview?>(null) }
    // Dev-mode snapshot of the depth frame internals (developer mode only).
    var devDepth by remember { mutableStateOf<String?>(null) }
    var devIntr by remember { mutableStateOf<String?>(null) }
    var devAxis by remember { mutableStateOf<String?>(null) }

    // AR-caliper (two-tap) method state.
    var sampleProgress by remember { mutableStateOf(0) }
    // Hampel-style robust moving average over the screen-centre AR distance
    // while the caliper is aiming — the plane hit-test distance flickers
    // frame-to-frame, and the caliper multiplies that distance straight
    // into the diameter. Fed by the sampling effect below.
    val caliperSmoother = remember { DistanceSmoother() }
    var captureMethod by remember {
        mutableStateOf(
            when (settings.dbhCaptureMethod) {
                "caliper" -> DbhCaptureMethod.CALIPER
                "motion" -> DbhCaptureMethod.MOTION
                else -> DbhCaptureMethod.DEPTH
            },
        )
    }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var caliperStep by remember { mutableStateOf(0) }       // 0 = await left, 1 = await right
    var leftRay by remember { mutableStateOf<Vec3?>(null) }
    var leftOffset by remember { mutableStateOf<Offset?>(null) }

    // Caliper = depth-free AR arm → use plane hits for distance, not depth.
    LaunchedEffect(captureMethod) {
        controller.preferDepth = captureMethod == DbhCaptureMethod.DEPTH
    }

    // User-selected per-frame chord algorithm (silhouette = iOS-identical).
    val chordAlgorithm = ChordAlgorithm.fromRaw(settings.dbhChordAlgorithm)

    // Preview smoothing (EMA α=0.3 on the diameter, iOS parity) + a short
    // lock streak so the digit + cylinder don't flicker on a single frame.
    var smoothedDiaCm by remember { mutableStateOf<Float?>(null) }
    var lockStreak by remember { mutableStateOf(0) }
    // Translucent 3D trunk cylinder at the live fit (quantised to ~1 cm so
    // ArCameraView doesn't rebuild its nodes every frame while holding).
    var cylinderMarker by remember { mutableStateOf<ArSceneMarker?>(null) }

    // Live single-frame preview loop while aiming (depth method only).
    LaunchedEffect(stage, captureMethod) {
        while (stage == Stage.AIMING && captureMethod == DbhCaptureMethod.DEPTH) {
            controller.acquireDepthFrame()?.let { f ->
                val axis = DBHEstimator.pickGuideAxis(f, f.width / 2.0, f.height / 2.0, ProjectCalibration.identity)
                val raw = DBHEstimator.livePreview(
                    f, f.width / 2.0, f.height / 2.0, axis, ProjectCalibration.identity, chordAlgorithm,
                )
                if (raw != null && raw.locked) {
                    val prev = smoothedDiaCm
                    val sm = if (prev == null) raw.diameterCm else 0.3f * raw.diameterCm + 0.7f * prev
                    smoothedDiaCm = sm
                    lockStreak = (lockStreak + 1).coerceAtMost(10)
                    val shownLocked = lockStreak >= 2
                    preview = raw.copy(diameterCm = sm, locked = shownLocked)
                    val cam = controller.currentCameraPosition()
                    val hit = controller.screenCenterHit()
                    cylinderMarker = if (shownLocked && cam != null && hit != null) {
                        val rM = sm / 100f / 2f
                        val dx = hit.x - cam.x; val dy = hit.y - cam.y; val dz = hit.z - cam.z
                        val len = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
                        val cx = if (len > 1e-3f) hit.x + dx / len * rM else hit.x
                        val cz = if (len > 1e-3f) hit.z + dz / len * rM else hit.z
                        fun q(v: Float) = Math.round(v * 100f) / 100f   // 1 cm grid → stable equality
                        ArSceneMarker(
                            Vec3(q(cx), q(hit.y), q(cz)),
                            MarkerShape.Cylinder(q(rM).coerceAtLeast(0.01f), 1.0f),
                            floatArrayOf(0.30f, 0.65f, 1.0f, 0.45f),
                        )
                    } else null
                } else {
                    smoothedDiaCm = null
                    lockStreak = 0
                    cylinderMarker = null
                    preview = raw
                }
                if (settings.developerMode) {
                    devDepth = "${f.width}×${f.height}"
                    devIntr = String.format(Locale.US, "%.0f/%.0f  %.0f,%.0f", f.fx, f.fy, f.cx, f.cy)
                    devAxis = if (axis is com.hcjeong.forestix.sensors.GuideAxis.Row) "Row(y)" else "Col(x)"
                }
            }
            delay(150)
        }
    }

    // Caliper aiming: sample the centre AR distance at ~10 Hz into the
    // robust smoother; the second edge tap then reads the spike-free
    // average instead of a single flickery hit-test.
    LaunchedEffect(stage, captureMethod) {
        if (stage != Stage.AIMING || captureMethod != DbhCaptureMethod.CALIPER) return@LaunchedEffect
        caliperSmoother.reset()
        while (true) {
            controller.cameraToCenterDistance()?.let { caliperSmoother.add(it) }
            delay(100)
        }
    }

    // Hold-steady capture: SAMPLE_COUNT sub-measurements over a few
    // seconds; the 3 closest to the median diameter are averaged (so with
    // 5 samples the 2 largest deviations are trimmed). Mirrors the iOS
    // DBHScanViewModel multi-sample burst.
    fun capture() {
        if (stage == Stage.CAPTURING) return
        // Require a locked live fit before starting the burst — mirrors the
        // iOS armed + non-red gate, so a capture can't run on an unresolved
        // trunk and immediately reject.
        if (preview?.locked != true) return
        stage = Stage.CAPTURING
        failure = null
        scope.launch {
            val samples = ArrayList<DBHResult>()
            var firstRed: DBHResult? = null      // surface the FIRST red (matches iOS)
            for (k in 1..SAMPLE_COUNT) {
                sampleProgress = k
                // ~0.5 s window per sub-sample (min 5 frames for the chord;
                // the attempt cap bounds a stalled depth stream).
                val frames = ArrayList<ArDepthFrame>()
                var attempts = 0
                while (attempts < 24 && frames.size < 10) {
                    controller.acquireDepthFrame()?.let { frames.add(it) }
                    attempts++
                    delay(50)
                }
                if (frames.size < 5) continue
                val w = frames.first().width
                val h = frames.first().height
                // Auto-pick the across-the-trunk axis (fixes severe under-read
                // when the sensor orientation made the strip run along the trunk).
                val axis = DBHEstimator.pickGuideAxis(frames.first(), w / 2.0, h / 2.0, ProjectCalibration.identity)
                // Chord (silhouette-width) method = median of the SAME per-frame
                // chord the live preview shows, so preview ≈ recorded value.
                val sub = DBHEstimator.estimateChord(frames, w / 2.0, h / 2.0, axis, ProjectCalibration.identity, chordAlgorithm)
                    ?: continue
                if (sub.confidence == ConfidenceTier.RED) {
                    if (firstRed == null) firstRed = sub
                } else samples.add(sub)
            }
            sampleProgress = 0
            val agg = DBHEstimator.aggregateSamples(samples)
            if (agg == null) {
                // Surface the red sub-sample's reason when we have one — it
                // says WHY the trunk couldn't be read.
                result = firstRed
                failure = if (firstRed == null)
                    "Couldn't read the trunk consistently. Hold steadier, 1\u20133 m away, and retry."
                else null
                stage = if (firstRed != null) Stage.RESULT else Stage.AIMING
            } else {
                result = agg
                stage = Stage.RESULT
            }
        }
    }

    // AR motion: aim at the trunk, tap + to start a short sweep. Feature
    // points accumulate (de-duped by ARCore point id) over ~3 s, then the
    // circle fit runs — the Android port of the iOS VIO DBH method.
    fun startMotionSweep() {
        if (stage == Stage.CAPTURING) return
        controller.preferDepth = false
        val anchor = controller.screenCenterHit() ?: controller.forwardPointAtHorizontalDistance(1.5f)
        if (anchor == null) {
            failure = "Aim at the trunk on a visible surface and try again."
            return
        }
        failure = null
        stage = Stage.CAPTURING
        scope.launch {
            controller.beginTrackingWatch()
            val acc = HashMap<Int, Vec3>()
            repeat(30) {                                 // ~3 s at 10 Hz
                controller.acquireFeaturePointsWorld().forEach { (id, p) -> acc[id] = p }
                delay(100)
            }
            val res = VioMotionDbh.estimate(
                acc.values.toList(), anchor, controller.trackingStayedNormalSinceWatch(),
            )
            if (res == null || res.confidence == ConfidenceTier.RED) {
                result = res
                failure = res?.rejectionReason
                    ?: "Not enough VIO points — sweep slower across the trunk and retry."
                stage = if (res != null) Stage.RESULT else Stage.AIMING
            } else {
                result = res
                stage = Stage.RESULT
            }
        }
    }

    // AR caliper: first tap caches the LEFT trunk-edge ray; the second
    // supplies the RIGHT ray + a centre distance and computes the diameter.
    fun handleCaliperTap(offset: Offset) {
        if (stage != Stage.AIMING) return
        val ray = controller.screenToWorldRayDirection(
            offset.x, offset.y, viewSize.width, viewSize.height,
        ) ?: return
        if (caliperStep == 0) {
            leftRay = ray
            leftOffset = offset
            caliperStep = 1
        } else {
            val l = leftRay
            // Prefer the robust moving average accumulated while aiming
            // (AR hit-test distances flicker); fall back to a spot sample.
            val d = (caliperSmoother.value() ?: controller.cameraToCenterDistance())?.toFloat()
            if (l != null && d != null) {
                val res = ArCaliperDbh.estimate(l, ray, d)
                if (res != null) { result = res; failure = null; stage = Stage.RESULT }
                else failure = "Couldn't measure — re-aim with the trunk centred and tap both edges."
            } else {
                failure = "No distance lock — centre the trunk on a surface and try again."
            }
            caliperStep = 0
            leftRay = null
            leftOffset = null
        }
    }

    val locked = stage == Stage.AIMING && preview?.locked == true

    Box(Modifier.fillMaxSize()) {
        // Live trunk-cylinder marker only while aiming with the depth method
        // (mirrors the iOS DBH cylinder overlay).
        val dbhMarkers = if (stage == Stage.AIMING && captureMethod == DbhCaptureMethod.DEPTH) {
            listOfNotNull(cylinderMarker)
        } else {
            emptyList()
        }
        ArCameraView(controller, dbhMarkers, modifier = Modifier.fillMaxSize())

        // Two-tap catcher for the AR-caliper method. Placed early in the Box
        // so the controls/status panel (later children) get taps first.
        if (captureMethod == DbhCaptureMethod.CALIPER && stage == Stage.AIMING) {
            Box(
                Modifier
                    .fillMaxSize()
                    .onSizeChanged { viewSize = it }
                    .pointerInput(caliperStep) {
                        detectTapGestures { offset -> handleCaliperTap(offset) }
                    },
            )
        }

        MeasureBackButton { nav.popBackStack() }

        if (settings.developerMode) {
            val p = preview
            DevHud(
                "DBH",
                listOfNotNull(
                    "depth" to (if (controller.supportsDepth) "ARCore✓" else "plane"),
                    "track" to (if (controller.trackingOk()) "OK" else "…"),
                    devDepth?.let { "depthMap" to it },
                    devIntr?.let { "fx/fy cx,cy" to it },
                    devAxis?.let { "axis" to it },
                    "dist" to (p?.distanceM?.let { String.format(Locale.US, "%.2f m", it) } ?: "—"),
                    "Ø live" to (p?.let { String.format(Locale.US, "%.1f cm", it.diameterCm) } ?: "—"),
                    "pts" to (p?.nPoints?.toString() ?: "—"),
                    "locked" to (if (p?.locked == true) "yes" else "no"),
                    result?.let { "Ø saved" to String.format(Locale.US, "%.1f ±%.0fmm", it.diameterCm, it.sigmaRmm) },
                ),
            )
        }

        // Guide line + live fit chord (drawn relative to screen centre).
        if (stage != Stage.RESULT) {
            Canvas(Modifier.fillMaxSize()) {
                val cy = size.height / 2f
                // Depth chrome (guide line + live fit chord) is meaningful only
                // for the DEPTH method — in caliper mode `preview` is stale and
                // the chord/guide would be misleading, so draw only the caliper
                // aid instead (mirrors the iOS caliper overlay).
                if (captureMethod == DbhCaptureMethod.DEPTH) {
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
                // AR-caliper: centre crosshair + captured left-edge marker.
                if (captureMethod == DbhCaptureMethod.CALIPER) {
                    val cx = size.width / 2f
                    drawLine(Color.White.copy(alpha = 0.9f), Offset(cx, cy - 16f), Offset(cx, cy + 16f), strokeWidth = 2f)
                    leftOffset?.let { lo ->
                        drawCircle(Color(0xFF4A9B5C), radius = 9f, center = Offset(lo.x, lo.y))
                    }
                }
                // AR-motion: centre aim cross (the trunk band ROI is centred here).
                if (captureMethod == DbhCaptureMethod.MOTION) {
                    val cx = size.width / 2f
                    val c = if (stage == Stage.CAPTURING) Color(0xFF4A9B5C) else Color.White.copy(alpha = 0.9f)
                    drawLine(c, Offset(cx, cy - 16f), Offset(cx, cy + 16f), strokeWidth = 2f)
                    drawLine(c, Offset(cx - 16f, cy), Offset(cx + 16f, cy), strokeWidth = 2f)
                }
            }

            if (captureMethod == DbhCaptureMethod.DEPTH) {
                // Crosshair ring — green locked / amber aligning (depth only).
                DbhRing(locked, colors.confidenceOk, colors.confidenceWarn, Modifier.align(Alignment.Center))
                // Live preview badge just below the ring.
                Box(Modifier.align(Alignment.Center).offset(y = 64.dp)) {
                    LivePreviewBadge(stage, preview, locked)
                }
            }

            // DBH method picker (Depth vs Caliper) floating above the panel.
            if (stage == Stage.AIMING) {
                Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 152.dp)) {
                    DbhMethodSelector(
                        method = captureMethod,
                        onSelect = { m ->
                            captureMethod = m
                            env.settings.setDbhCaptureMethod(
                                when (m) {
                                    DbhCaptureMethod.CALIPER -> "caliper"
                                    DbhCaptureMethod.MOTION -> "motion"
                                    DbhCaptureMethod.DEPTH -> "depth"
                                },
                            )
                            caliperStep = 0; leftRay = null; leftOffset = null
                            result = null; failure = null
                            preview = null          // drop the stale depth fit
                            stage = Stage.AIMING
                        },
                        depthEnabled = controller.supportsDepth,
                    )
                }
            }
        }

        if (stage == Stage.AIMING &&
            (captureMethod == DbhCaptureMethod.DEPTH || captureMethod == DbhCaptureMethod.MOTION)
        ) {
            MeasureControlColumn(onCapture = {
                if (captureMethod == DbhCaptureMethod.MOTION) startMotionSweep() else capture()
            })
        }

        MeasureStatusPanel {
            failure?.let { CenteredText(it, dim = true) }
            when (stage) {
                Stage.AIMING -> CenteredText(
                    when {
                        captureMethod == DbhCaptureMethod.CALIPER ->
                            if (caliperStep == 0) "AR caliper \u2014 aim at breast height, tap the LEFT trunk edge"
                            else "Now tap the RIGHT trunk edge"
                        captureMethod == DbhCaptureMethod.MOTION ->
                            "AR motion \u2014 aim at the trunk, tap + then sweep the phone slowly across it"
                        locked -> "Trunk locked \u2014 tap + to scan"
                        else -> "Aim at the trunk (1\u20133 m), centre the line"
                    },
                )
                Stage.CAPTURING -> CenteredText(
                    if (captureMethod == DbhCaptureMethod.MOTION)
                        "Sweeping\u2026 keep the trunk centred and move side-to-side"
                    else "Capturing ${maxOf(1, sampleProgress)}/$SAMPLE_COUNT \u2014 hold steady",
                )
                Stage.RESULT -> {}
            }
            result?.let { r ->
                if (r.confidence == ConfidenceTier.RED) {
                    CenteredText("DBH \u2014 check", large = true)
                    r.rejectionReason?.let { CenteredText(it, dim = true) }
                } else {
                    CenteredText(String.format(Locale.US, "DBH %.1f cm  \u00B1%.0f mm", r.diameterCm, r.sigmaRmm), large = true)
                    CenteredText(String.format(Locale.US, "%d frames \u00B7 %s", r.nInliers, r.confidence.raw.uppercase()), dim = true)
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
