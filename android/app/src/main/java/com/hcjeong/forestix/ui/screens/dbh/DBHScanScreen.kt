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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.hcjeong.forestix.LocalAppEnvironment
import com.hcjeong.forestix.ar.ArController
import com.hcjeong.forestix.ar.ArCameraView
import com.hcjeong.forestix.ar.ArSceneMarker
import com.hcjeong.forestix.ar.MarkerShape
import com.hcjeong.forestix.ar.Vec3
import com.hcjeong.forestix.common.MeasurementFormatter
import com.hcjeong.forestix.common.UnitSystem
import com.hcjeong.forestix.sensors.ArCaliperDbh
import com.hcjeong.forestix.data.MeasureKind
import com.hcjeong.forestix.data.QuickMeasureEntry
import com.hcjeong.forestix.data.ResearchLog
import com.hcjeong.forestix.data.StemPosition
import com.hcjeong.forestix.sensors.ArDepthFrame
import com.hcjeong.forestix.sensors.ChordAlgorithm
import com.hcjeong.forestix.sensors.ConfidenceTier
import com.hcjeong.forestix.sensors.DBHEstimator
import com.hcjeong.forestix.sensors.DBHResult
import com.hcjeong.forestix.sensors.DistanceSmoother
import com.hcjeong.forestix.sensors.DBHMethod
import com.hcjeong.forestix.sensors.GuideAxis
import com.hcjeong.forestix.sensors.VioMotionDbh
import com.hcjeong.forestix.ui.MeasurePhotoStore
import com.hcjeong.forestix.ui.PendingTreeNumber
import com.hcjeong.forestix.ui.Routes
import com.hcjeong.forestix.ui.screens.ContinuationAction
import com.hcjeong.forestix.ui.screens.ContinuationOrigin
import com.hcjeong.forestix.ui.screens.MeasurementContinuationSheet
import com.hcjeong.forestix.ui.screens.ScanMetadataSheet
import com.hcjeong.forestix.ui.screens.CenteredText
import com.hcjeong.forestix.ui.screens.DevHud
import com.hcjeong.forestix.ui.screens.GPSAccuracyBadge
import com.hcjeong.forestix.ui.screens.MeasureBackButton
import com.hcjeong.forestix.ui.screens.MeasureCircleButton
import com.hcjeong.forestix.ui.screens.MeasureControlColumn
import com.hcjeong.forestix.ui.screens.MeasureFailureBanner
import com.hcjeong.forestix.ui.screens.MeasureStatusPanel
import com.hcjeong.forestix.ui.screens.ResearchFieldsRow
import com.hcjeong.forestix.ui.screens.TiltBadge
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixBorderedButton
import com.hcjeong.forestix.ui.theme.ForestixProminentButton
import com.hcjeong.forestix.ui.theme.ForestixRadius
import com.hcjeong.forestix.ui.theme.ForestixSpace
import com.hcjeong.forestix.ui.theme.confidenceDescriptor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

private enum class Stage { AIMING, CAPTURING, RESULT }

/// Sub-measurements per hold-steady capture; the 3 closest to the median
/// are kept (2 largest deviations trimmed) and averaged.
private const val SAMPLE_COUNT = 5

/// Consecutive bad/absent per-frame fits tolerated while locked before the
/// HUD drops back to aiming (iOS `redResetCount` parity). Without this a
/// single unusable frame flipped ring/chord/badge/status every tick — the
/// field-reported "screen flashing" during aiming.
private const val PREVIEW_MISS_RESET = 3

/// `chainToHeight` = launched from the map-home "Full measurement" row
/// ("dbh?chain=true"): Accept saves the diameter, skips the continuation
/// dialog, and goes straight to Height on the same tree.
@Composable
fun DBHScanScreen(nav: NavController, chainToHeight: Boolean = false) {
    val env = LocalAppEnvironment.current
    val context = LocalContext.current
    val controller = remember { ArController() }
    val scope = rememberCoroutineScope()
    // Map-home tree lock ("Measure this tree again" / chooser rows) hands
    // the tree number over via PendingTreeNumber — consume it once so the
    // accepted reading lands on the promised tree; otherwise pick the next
    // free number (iOS pendingTreeNumber parity).
    var pendingTree by remember {
        mutableStateOf(PendingTreeNumber.consume() ?: env.history.suggestedNextTreeNumber)
    }
    // Manual DBH entry (typed cm) — mirror of the iOS .manualEntry state.
    var manualOpen by remember { mutableStateOf(false) }
    var manualText by remember { mutableStateOf("") }
    // Accept-snapshot chrome blackout: while true, every 2D panel/button is
    // hidden so the captured JPEG shows only the AR feed + measurement
    // overlays (captured buttons read as real buttons in the photo viewer).
    var hidingChromeForCapture by remember { mutableStateOf(false) }
    // Scan metadata (species / position / damage / note) attached on Accept.
    var metaSpecies by remember { mutableStateOf<String?>(null) }
    var metaPosition by remember { mutableStateOf<StemPosition?>(StemPosition.DBH) }
    var metaDamage by remember { mutableStateOf<List<String>>(emptyList()) }
    var metaNote by remember { mutableStateOf("") }
    var showMetadata by remember { mutableStateOf(false) }
    // Post-save continuation (height same tree / next tree / done).
    var continuationTree by remember { mutableStateOf<Int?>(null) }
    // Developer-mode research capture: tape-measured true diameter (cm).
    var researchTrueCm by remember { mutableStateOf("") }
    val colors = Forestix.colors

    val settings by env.settings.state.collectAsStateWithLifecycle()
    // Project calibration — identity for plain quick-measure (iOS parity),
    // the active project's wall/cylinder fits when launched from the
    // Add-Tree flow (iOS injects it into DBHScanViewModel).
    val calibration by env.activeScanCalibration.collectAsStateWithLifecycle()
    // Field mode (developer mode OFF) requires the ARCore Depth API — the
    // depth-free AR motion / AR caliper arms are developer tools. Only
    // block once the session has actually REPORTED capability, so capable
    // devices don't flash the blocker while AR is still starting up.
    val depthBlocked = !settings.developerMode &&
        controller.depthSupportKnown && !controller.supportsDepth
    var stage by remember { mutableStateOf(Stage.AIMING) }
    var result by remember { mutableStateOf<DBHResult?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<DBHEstimator.DbhPreview?>(null) }
    // Last RAW per-frame preview distance (m) — the un-smoothed dTap the
    // estimator actually used. `preview.distanceM` is EMA-smoothed for the
    // badge since round 6; the research CSV must keep logging the raw value
    // (pre-round-6 semantics) so the σ analysis isn't fed filtered inputs.
    var rawDistM by remember { mutableStateOf<Float?>(null) }
    // Dev-mode snapshot of the depth frame internals (developer mode only).
    var devDepth by remember { mutableStateOf<String?>(null) }
    var devIntr by remember { mutableStateOf<String?>(null) }
    var devAxis by remember { mutableStateOf<String?>(null) }
    // CPU-image-scaled focals (the alternative registration hypothesis) +
    // the crosshair's mapped depth pixel — the field protocol reads these
    // against fx/fy to decide which registration the device honours.
    var devIntrImg by remember { mutableStateOf<String?>(null) }
    var devMap by remember { mutableStateOf<String?>(null) }
    // Silhouette-edge legibility: found width vs OFF-FRAME row count.
    var devEdge by remember { mutableStateOf<String?>(null) }
    // Dev-mode one-line geometry check: depth WxH, the fx the chord identity
    // consumed, raw vs smoothed distance — added after the round-6 stack
    // regression so a field run can confirm depth geometry at a glance.
    var devGeom by remember { mutableStateOf<String?>(null) }

    // AR-caliper (two-tap) method state.
    var sampleProgress by remember { mutableStateOf(0) }
    // Hampel-style robust moving average over the screen-centre AR distance
    // while the caliper is aiming — the plane hit-test distance flickers
    // frame-to-frame, and the caliper multiplies that distance straight
    // into the diameter. Fed by the sampling effect below.
    val caliperSmoother = remember { DistanceSmoother() }
    var captureMethod by remember {
        mutableStateOf(
            // The Depth/Motion/Caliper picker is developer-only: normal mode
            // always scans with the depth method, even if a dev-mode session
            // persisted another choice earlier.
            if (!settings.developerMode) DbhCaptureMethod.DEPTH
            else when (settings.dbhCaptureMethod) {
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

    // Preview smoothing (EMA α=0.3 on the diameter + distance, iOS parity)
    // + a short lock streak so the digit + cylinder don't flicker on a
    // single frame.
    var smoothedDiaCm by remember { mutableStateOf<Float?>(null) }
    var smoothedDistM by remember { mutableStateOf<Float?>(null) }
    // Cylinder anchor — EMA over the screen-centre hit (iOS
    // smoothedCenterWorldXZ parity), held through one-frame hit-test
    // misses. The raw per-frame hit wandered across the marker's 1 cm
    // quantisation grid, changing the marker value nearly every tick.
    var smoothedHitAnchor by remember { mutableStateOf<Vec3?>(null) }
    var lockStreak by remember { mutableStateOf(0) }
    // Dropout hysteresis: while locked, up to PREVIEW_MISS_RESET−1
    // consecutive misses HOLD the last smoothed preview instead of
    // unlocking — ring colour, chord, badge and status text stay steady
    // through one-frame fit dropouts (iOS tolerates transient reds the
    // same way).
    var missStreak by remember { mutableStateOf(0) }
    // Shown preview tier only flips after 2 consecutive frames agree, so
    // the green chip can't blink in/out on the raw per-frame CoV.
    var shownTier by remember { mutableStateOf(ConfidenceTier.YELLOW) }
    var tierStreak by remember { mutableStateOf(0) }
    // Translucent 3D trunk cylinder at the live fit. Quantised to ~1 cm so
    // the value only changes on a real move; ArCameraView then MOVES the
    // existing node and rebuilds Filament resources only when the quantised
    // radius itself changes.
    var cylinderMarker by remember { mutableStateOf<ArSceneMarker?>(null) }

    // Live single-frame preview loop while aiming (depth method only).
    LaunchedEffect(stage, captureMethod, depthBlocked) {
        while (stage == Stage.AIMING && captureMethod == DbhCaptureMethod.DEPTH && !depthBlocked) {
            controller.acquireDepthFrame()?.let { f ->
                // Crosshair → depth pixel through ARCore's own view↔texture
                // mapping (display rotation + aspect crop). Falls back to the
                // grid centre while the mapping isn't available — same point
                // for a centred crop, but the affine also carries the
                // principal-point offset the centre assumption ignores.
                val tap = f.viewToDepth(controller.viewWidthPx / 2f, controller.viewHeightPx / 2f)
                val tapX = tap?.first ?: (f.width / 2.0)
                val tapY = tap?.second ?: (f.height / 2.0)
                val axis = DBHEstimator.pickGuideAxis(f, tapX, tapY, calibration)
                val raw = DBHEstimator.livePreview(
                    f, tapX, tapY, axis, calibration, chordAlgorithm,
                )
                if (raw != null && raw.locked) {
                    missStreak = 0
                    rawDistM = raw.distanceM
                    val prev = smoothedDiaCm
                    val sm = if (prev == null) raw.diameterCm else 0.3f * raw.diameterCm + 0.7f * prev
                    smoothedDiaCm = sm
                    val prevD = smoothedDistM
                    val smD = if (prevD == null) raw.distanceM else 0.3f * raw.distanceM + 0.7f * prevD
                    smoothedDistM = smD
                    lockStreak = (lockStreak + 1).coerceAtMost(10)
                    val shownLocked = lockStreak >= 2
                    // Tier hysteresis — flip only after 2 frames agree.
                    if (raw.tier == shownTier) {
                        tierStreak = 0
                    } else {
                        tierStreak += 1
                        if (tierStreak >= 2) { shownTier = raw.tier; tierStreak = 0 }
                    }
                    // Strip edges live on the DEPTH walk axis — rotate/crop
                    // them into on-screen x fractions before the Canvas
                    // multiplies by screen width (in portrait the walk axis
                    // is the depth grid's y, mirrored+cropped vs the screen).
                    val vf = stripViewFractions(
                        f, axis, raw.stripLeftFraction, raw.stripRightFraction,
                        controller.viewWidthPx.toFloat(),
                    )
                    preview = raw.copy(
                        diameterCm = sm, distanceM = smD,
                        locked = shownLocked, tier = shownTier,
                        stripLeftFraction = vf?.first ?: raw.stripLeftFraction,
                        stripRightFraction = vf?.second ?: raw.stripRightFraction,
                    )
                    // Anchor EMA (α=0.3): the marker follows a stable trunk
                    // point instead of the jittery per-frame hit; a null
                    // hit this tick just holds the last anchor.
                    controller.screenCenterHit()?.let { hitRaw ->
                        val ph = smoothedHitAnchor
                        smoothedHitAnchor = if (ph == null) hitRaw else Vec3(
                            0.3f * hitRaw.x + 0.7f * ph.x,
                            0.3f * hitRaw.y + 0.7f * ph.y,
                            0.3f * hitRaw.z + 0.7f * ph.z,
                        )
                    }
                    val cam = controller.currentCameraPosition()
                    val hit = smoothedHitAnchor
                    cylinderMarker = if (shownLocked && hit != null) {
                        val rM = sm / 100f / 2f
                        // Push the centre back by one radius along the view
                        // ray (the hit is the trunk's FRONT surface).
                        var cx = hit.x
                        var cz = hit.z
                        if (cam != null) {
                            val dx = hit.x - cam.x; val dy = hit.y - cam.y; val dz = hit.z - cam.z
                            val len = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
                            if (len > 1e-3f) { cx = hit.x + dx / len * rM; cz = hit.z + dz / len * rM }
                        }
                        fun q(v: Float) = Math.round(v * 100f) / 100f   // 1 cm grid → stable equality
                        ArSceneMarker(
                            Vec3(q(cx), q(hit.y), q(cz)),
                            MarkerShape.Cylinder(q(rM).coerceAtLeast(0.01f), 1.0f),
                            floatArrayOf(0.30f, 0.65f, 1.0f, 0.45f),
                        )
                    } else null
                } else if (preview?.locked == true && missStreak < PREVIEW_MISS_RESET - 1) {
                    // Transient dropout while locked — hold the last smoothed
                    // preview (ring, chord, badge and status text stay put).
                    // Only PREVIEW_MISS_RESET consecutive misses unlock.
                    missStreak += 1
                } else {
                    missStreak = 0
                    rawDistM = null
                    smoothedDiaCm = null
                    smoothedDistM = null
                    smoothedHitAnchor = null
                    lockStreak = 0
                    tierStreak = 0
                    shownTier = ConfidenceTier.YELLOW
                    cylinderMarker = null
                    preview = raw
                }
                if (settings.developerMode) {
                    val isRow = axis is GuideAxis.Row
                    devDepth = "${f.width}×${f.height}"
                    // Edge legibility: were both silhouette edges found inside
                    // the frame (w = median width), or did rows run off the
                    // border (OFF-FRAME → the fit is invalid by design)?
                    devEdge = when {
                        raw == null -> null
                        raw.edgesClipped ->
                            String.format(Locale.US, "OFF-FRAME %d rows", raw.clippedRows)
                        raw.widthPx > 0 -> String.format(
                            Locale.US, "L✓R✓ w%dpx%s", raw.widthPx,
                            if (raw.clippedRows > 0) " clip${raw.clippedRows}" else "",
                        )
                        else -> null
                    }
                    devIntr = String.format(Locale.US, "%.0f/%.0f  %.0f,%.0f", f.fx, f.fy, f.cx, f.cy)
                    // Alternative-registration focals (CPU image). If a
                    // known-diameter object reads true only when the used
                    // focal is swapped for this value, the device registers
                    // depth to the CPU image, not the texture.
                    devIntrImg = if (f.fxImg > 0)
                        String.format(Locale.US, "%.0f/%.0f", f.fxImg, f.fyImg) else null
                    devAxis = if (isRow) "Row(y)" else "Col(x)"
                    // Crosshair's mapped depth pixel + whether the view↔depth
                    // affine is 90°-rotated (portrait ⇒ rot90).
                    devMap = f.depthFromViewAffine?.let { m ->
                        String.format(
                            Locale.US, "%.0f,%.0f %s",
                            tapX, tapY,
                            if (kotlin.math.abs(m[1]) > kotlin.math.abs(m[0])) "rot90" else "rot0",
                        )
                    }
                    // One-glance geometry check (stack-regression sentinel):
                    // depth WxH + the AXIS-MATCHED focal the chord identity
                    // divides by + raw vs smoothed distance. A wrong depth
                    // aspect/orientation or broken intrinsics show up here.
                    devGeom = String.format(
                        Locale.US, "%d×%d %s%.0f d %s/%s",
                        f.width, f.height,
                        if (isRow) "fx" else "fy",
                        if (isRow) f.fx else f.fy,
                        raw?.distanceM?.let { String.format(Locale.US, "%.2f", it) } ?: "—",
                        smoothedDistM?.let { String.format(Locale.US, "%.2f", it) } ?: "—",
                    )
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
                val f0 = frames.first()
                // Same crosshair→depth-pixel mapping as the live preview so
                // the committed burst reads the exact spot the cruiser aimed.
                val tap = f0.viewToDepth(controller.viewWidthPx / 2f, controller.viewHeightPx / 2f)
                val tapX = tap?.first ?: (f0.width / 2.0)
                val tapY = tap?.second ?: (f0.height / 2.0)
                // Auto-pick the across-the-trunk axis (fixes severe under-read
                // when the sensor orientation made the strip run along the trunk).
                val axis = DBHEstimator.pickGuideAxis(f0, tapX, tapY, calibration)
                // Chord (silhouette-width) method = median of the SAME per-frame
                // chord the live preview shows, so preview ≈ recorded value.
                val sub = DBHEstimator.estimateChord(frames, tapX, tapY, axis, calibration, chordAlgorithm)
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
                    "Couldn't read the trunk consistently. Hold steadier, 1–3 m away, and retry."
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

    // Accept the on-screen result — records the entry (photo + GPS fix),
    // fires the continuation / height chain, and logs the research CSV row.
    // Shared by the result row's Accept and the manual-entry Save (iOS
    // submitManualEntry goes straight to .accepted).
    fun acceptResult(r: DBHResult) {
        // Auto-capture (map home): window snapshot as evidence of what was
        // measured + the latest GPS fix from the badge's running location
        // service.
        val activity = context as? android.app.Activity
        scope.launch {
            // Chrome-less snapshot: hide the 2D chrome, give Compose one
            // committed frame, capture, then restore.
            val photo = activity?.let {
                hidingChromeForCapture = true
                delay(80)
                val name = MeasurePhotoStore.captureWindow(it)
                hidingChromeForCapture = false
                name
            }
            val fix = com.hcjeong.forestix.positioning.LocationService.lastGlobalFix
            env.history.append(
                QuickMeasureEntry(
                    kind = MeasureKind.DBH, value = r.diameterCm.toDouble(),
                    sigma = r.sigmaRmm.toDouble(), confidenceRaw = r.confidence.raw,
                    method = r.method.raw, treeNumber = pendingTree,
                    plotID = env.history.activePlotID.value,
                    speciesCode = metaSpecies,
                    position = metaPosition ?: StemPosition.DBH,
                    damageCodes = metaDamage,
                    note = metaNote.ifBlank { null },
                    latitude = fix?.latitude,
                    longitude = fix?.longitude,
                    photoPath = photo,
                )
            )
            // Full-measurement chain: skip the continuation dialog, go
            // straight to Height on this tree. Navigate AFTER the append
            // (this scope dies with the screen) and pop DBH so Height's
            // continuation DONE returns to the map.
            if (chainToHeight) {
                nav.navigate("height?tree=$pendingTree") {
                    popUpTo(Routes.DBH_PATTERN) { inclusive = true }
                }
            }
        }
        if (!chainToHeight) continuationTree = pendingTree
        if (settings.developerMode) {
            val fields = mutableMapOf(
                "measure_type" to "dbh",
                "method" to r.method.raw,
                // Same raw vocabulary as iOS dbhMethodSource so the two
                // platforms' CSVs filter identically; the platform column
                // says whether "lidarDepth" means LiDAR or ARCore depth.
                "depth_source" to when (captureMethod) {
                    DbhCaptureMethod.DEPTH -> "lidarDepth"
                    DbhCaptureMethod.MOTION -> "arMotion"
                    DbhCaptureMethod.CALIPER -> "arCaliper"
                },
                "measured_value" to String.format(Locale.US, "%.2f", r.diameterCm),
                "unit" to "cm",
                "sigma" to String.format(Locale.US, "%.1f", r.sigmaRmm),
                "confidence_tier" to r.confidence.raw,
                "n_points" to "${r.nInliers}",
                "arc_deg" to String.format(Locale.US, "%.1f", r.arcCoverageDeg),
                "rmse_mm" to String.format(Locale.US, "%.1f", r.rmseMm),
                "species" to (metaSpecies ?: ""),
                "note" to metaNote,
            )
            if (settings.researchTreeId.isNotEmpty()) {
                fields["tree_id"] = settings.researchTreeId  // repeat auto-filled by record()
            }
            // Raw per-frame distance (pre-round-6 semantics) — never the
            // EMA-smoothed badge value; the display smoothing must not leak
            // into research/σ inputs.
            rawDistM?.let { fields["distance_m"] = String.format(Locale.US, "%.2f", it) }
            controller.cameraForwardElevationRad()?.let {
                fields["pitch_deg"] = String.format(Locale.US, "%.1f", it * 180f / Math.PI.toFloat())
            }
            researchTrueCm.toDoubleOrNull()?.takeIf { it > 0 }?.let { t ->
                fields["true_value"] = String.format(Locale.US, "%.2f", t)
                fields["error"] = String.format(Locale.US, "%.2f", r.diameterCm - t)
            }
            ResearchLog.record(context, fields)
            researchTrueCm = ""
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

        if (!hidingChromeForCapture) MeasureBackButton { nav.popBackStack() }

        // GPS-accuracy pill on the top strip — leading 72 / top 22, clear of
        // the floating back button (same offsets as iOS DBHScanScreen).
        if (!hidingChromeForCapture) GPSAccuracyBadge(
            Modifier
                .align(Alignment.TopStart)
                .padding(start = 72.dp, top = 22.dp))

        if (settings.developerMode && !hidingChromeForCapture) {
            val p = preview
            DevHud(
                "DBH",
                listOfNotNull(
                    "depth" to (if (controller.supportsDepth) "ARCore✓" else "plane"),
                    "track" to (if (controller.trackingOk()) "OK" else "…"),
                    devDepth?.let { "depthMap" to it },
                    devIntr?.let { "fx/fy cx,cy" to it },
                    devIntrImg?.let { "fImg" to it },
                    devGeom?.let { "geom" to it },
                    devEdge?.let { "edge" to it },
                    devAxis?.let { "axis" to it },
                    devMap?.let { "tap px" to it },
                    "dist" to (p?.distanceM?.let { String.format(Locale.US, "%.2f m", it) } ?: "—"),
                    "Ø live" to (p?.let { String.format(Locale.US, "%.1f cm", it.diameterCm) } ?: "—"),
                    "pts" to (p?.nPoints?.toString() ?: "—"),
                    "locked" to (if (p?.locked == true) "yes" else "no"),
                    result?.let { "Ø saved" to String.format(Locale.US, "%.1f ±%.0fmm", it.diameterCm, it.sigmaRmm) },
                ),
            )
        }

        // Guide line + live fit chord (drawn relative to screen centre).
        // All scanning chrome is suppressed while the depth blocker is up.
        if (stage != Stage.RESULT && !depthBlocked) {
            Canvas(Modifier.fillMaxSize()) {
                val cy = size.height / 2f
                // Depth chrome (guide line + live fit chord) is meaningful only
                // for the DEPTH method — in caliper mode `preview` is stale and
                // the chord/guide would be misleading, so draw only the caliper
                // aid instead (mirrors the iOS caliper overlay).
                if (captureMethod == DbhCaptureMethod.DEPTH) {
                    // Horizontal guide line (dual stroke for sun-glare contrast).
                    drawLine(
                        Color.Black.copy(alpha = 0.55f),
                        Offset(0f, cy), Offset(size.width, cy),
                        strokeWidth = 3.dp.toPx(),
                    )
                    drawLine(
                        Color.White.copy(alpha = 0.9f),
                        Offset(0f, cy), Offset(size.width, cy),
                        strokeWidth = 1.5.dp.toPx(),
                    )
                    // Live fit-width chord spanning the strip edges the
                    // single-frame fit identified along the guide row — iOS
                    // fitChord parity (fit-derived span + dark-halo side bars).
                    val p = preview
                    if (locked && p != null && p.stripRightFraction > p.stripLeftFraction) {
                        val x0 = size.width * p.stripLeftFraction
                        val x1 = size.width * p.stripRightFraction
                        val half = 22.dp.toPx()
                        drawLine(
                            colors.confidenceOk.copy(alpha = 0.95f),
                            Offset(x0, cy), Offset(x1, cy),
                            strokeWidth = 4.dp.toPx(),
                        )
                        for (x in listOf(x0, x1)) {
                            drawLine(
                                Color.Black.copy(alpha = 0.55f),
                                Offset(x, cy - half), Offset(x, cy + half),
                                strokeWidth = 5.dp.toPx(),
                            )
                            drawLine(
                                colors.confidenceOk,
                                Offset(x, cy - half), Offset(x, cy + half),
                                strokeWidth = 3.dp.toPx(),
                            )
                        }
                    }
                }
                // AR-caliper: centre plus glyph + captured left-edge dot
                // (iOS arOverlay: 22 pt light plus, Ø12 dot with white ring).
                if (captureMethod == DbhCaptureMethod.CALIPER) {
                    val cx = size.width / 2f
                    val arm = 11.dp.toPx()
                    val lw = 1.5.dp.toPx()
                    drawLine(Color.White.copy(alpha = 0.85f), Offset(cx, cy - arm), Offset(cx, cy + arm), strokeWidth = lw)
                    drawLine(Color.White.copy(alpha = 0.85f), Offset(cx - arm, cy), Offset(cx + arm, cy), strokeWidth = lw)
                    leftOffset?.let { lo ->
                        drawCircle(colors.confidenceOk, radius = 6.dp.toPx(), center = Offset(lo.x, lo.y))
                        drawCircle(
                            Color.White, radius = 6.dp.toPx(), center = Offset(lo.x, lo.y),
                            style = Stroke(width = 1.5.dp.toPx()),
                        )
                    }
                }
                // AR-motion: 46 dp aiming ring (green while sweeping) + a
                // 16 pt centre plus (iOS vioOverlay).
                if (captureMethod == DbhCaptureMethod.MOTION) {
                    val cx = size.width / 2f
                    val ringColor = if (stage == Stage.CAPTURING) colors.confidenceOk
                        else Color.White.copy(alpha = 0.85f)
                    drawCircle(
                        ringColor, radius = 23.dp.toPx(), center = Offset(cx, cy),
                        style = Stroke(width = 2.dp.toPx()),
                    )
                    val arm = 8.dp.toPx()
                    val lw = 2.dp.toPx()
                    drawLine(Color.White.copy(alpha = 0.9f), Offset(cx, cy - arm), Offset(cx, cy + arm), strokeWidth = lw)
                    drawLine(Color.White.copy(alpha = 0.9f), Offset(cx - arm, cy), Offset(cx + arm, cy), strokeWidth = lw)
                }
            }

            if (captureMethod == DbhCaptureMethod.DEPTH) {
                // Crosshair ring — green locked / red aligning (spec §5.2).
                DbhRing(locked, colors.confidenceOk, colors.confidenceBad, Modifier.align(Alignment.Center))
                // Device-tilt badge floating just above the ring so the
                // cruiser sees level at the same focal point (iOS TiltBadge
                // at midY − ringRadius − 22). Both badges are 2D chrome, so
                // the accept snapshot drops them (ring + chord stay).
                if (!hidingChromeForCapture) {
                    Box(Modifier.align(Alignment.Center).offset(y = (-58).dp)) {
                        TiltBadge(controller)
                    }
                    // Live preview badge just below the ring.
                    Box(Modifier.align(Alignment.Center).offset(y = 64.dp)) {
                        LivePreviewBadge(preview, locked, settings.unitSystem)
                    }
                }
            }
        }

        if (stage == Stage.AIMING && !depthBlocked && !hidingChromeForCapture) {
            if (captureMethod == DbhCaptureMethod.CALIPER) {
                // The caliper captures via the trunk-edge taps, so there is
                // no "+" — but the Manual escape hatch keeps its rail slot
                // (hidden while the typed-entry row is open).
                if (!manualOpen) {
                    Column(
                        modifier = Modifier.align(Alignment.CenterEnd).padding(end = 18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        MeasureCircleButton(icon = Icons.Filled.Keyboard, caption = "Manual") {
                            manualOpen = true; manualText = ""
                        }
                    }
                }
            } else {
                MeasureControlColumn(
                    onCapture = {
                        if (captureMethod == DbhCaptureMethod.MOTION) startMotionSweep() else capture()
                    },
                    // Manual entry rides the rail directly below the capture
                    // "+" (hidden while the typed-entry row is open).
                    extra = if (!manualOpen) {
                        {
                            MeasureCircleButton(icon = Icons.Filled.Keyboard, caption = "Manual") {
                                manualOpen = true; manualText = ""
                            }
                        }
                    } else null,
                )
            }
        }

        if (!depthBlocked && !hidingChromeForCapture) MeasureStatusPanel(
            // Developer-mode method picker floats 12 dp above the status
            // panel while aiming (iOS dbhMethodPicker placement).
            above = if (stage == Stage.AIMING && settings.developerMode) {
                {
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
                            rawDistM = null
                            smoothedDiaCm = null; smoothedDistM = null
                            smoothedHitAnchor = null
                            lockStreak = 0; missStreak = 0
                            shownTier = ConfidenceTier.YELLOW; tierStreak = 0
                            cylinderMarker = null
                            stage = Stage.AIMING
                        },
                        depthEnabled = controller.supportsDepth,
                    )
                }
            } else null,
        ) {
            failure?.let { MeasureFailureBanner(it) }
            CenteredText(
                when {
                    manualOpen -> "Enter diameter manually in cm."
                    stage == Stage.AIMING -> when {
                        captureMethod == DbhCaptureMethod.CALIPER ->
                            if (caliperStep == 0) "AR caliper — aim at breast height, tap the LEFT trunk edge."
                            else "Now tap the RIGHT trunk edge."
                        captureMethod == DbhCaptureMethod.MOTION ->
                            "AR motion — aim at the trunk, tap + then sweep the phone slowly across it."
                        locked -> "Hold steady, then tap + to capture."
                        // Border-touch invalidity: the silhouette walk ran off
                        // the image — the trunk's edges aren't in frame, so no
                        // fit can lock. Honest guidance instead of a lock.
                        preview?.edgesClipped == true ->
                            "Edges not found — adjust framing."
                        else -> "Align the guide to the trunk's uphill side; hold steady."
                    }
                    stage == Stage.CAPTURING ->
                        if (captureMethod == DbhCaptureMethod.MOTION)
                            "Sweeping… keep the trunk centred and move side-to-side."
                        else "Capturing ${maxOf(1, sampleProgress)}/$SAMPLE_COUNT — hold steady."
                    result?.confidence == ConfidenceTier.RED ->
                        result?.rejectionReason ?: "Scan rejected. Try again."
                    else -> "Scan complete. Accept, retake, or add a second view."
                },
            )
            // Manual entry — typed diameter for trees the sensors can't read
            // (mirrors the iOS .manualEntry state, method "manualVisual").
            // Opened from the rail's Manual button under the capture "+".
            if (stage == Stage.AIMING && manualOpen) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = manualText,
                        onValueChange = { manualText = it.filter { c -> c.isDigit() || c == '.' } },
                        placeholder = {
                            Text(
                                if (settings.unitSystem == UnitSystem.METRIC) "Diameter in cm"
                                else "Diameter in inches",
                            )
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                    ForestixProminentButton(
                        "Save",
                        enabled = (manualText.toFloatOrNull() ?: 0f) > 0f,
                    ) {
                        val cm = manualText.toFloatOrNull()
                        if (cm != null && cm > 0f) {
                            val r = DBHResult(
                                diameterCm = cm, centerX = 0f, centerZ = 0f,
                                arcCoverageDeg = 0f, rmseMm = 0f, sigmaRmm = 0f,
                                nInliers = 0, confidence = ConfidenceTier.YELLOW,
                                method = DBHMethod.MANUAL_VISUAL, rejectionReason = null,
                            )
                            result = r
                            failure = null
                            manualOpen = false
                            // iOS submitManualEntry goes straight to
                            // .accepted — record and continue.
                            acceptResult(r)
                        }
                    }
                }
                ForestixBorderedButton("Cancel", modifier = Modifier.fillMaxWidth()) {
                    manualOpen = false
                }
            }
            if (stage == Stage.RESULT) result?.let { r ->
                Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalAlignment = Alignment.Start,
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // Monospaced value via the shared unit-aware
                        // formatter; σ beside it as a separate dataSmall
                        // text (hidden on red — the chip carries the
                        // warning). Red still shows the value.
                        Text(
                            MeasurementFormatter.diameter(r.diameterCm.toDouble(), settings.unitSystem),
                            style = Forestix.type.dataLarge,
                            color = Color.White,
                        )
                        if (r.confidence != ConfidenceTier.RED) {
                            Text(
                                MeasurementFormatter.diameterSigma(r.sigmaRmm.toDouble(), settings.unitSystem),
                                style = Forestix.type.dataSmall,
                                color = Color.White.copy(alpha = 0.75f),
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        // Yellow is a normal record-able fit — chip + hint
                        // only for green (good) or red (must retake).
                        if (r.confidence != ConfidenceTier.YELLOW) {
                            TierChip(r.confidence.raw)
                        }
                    }
                    if (r.confidence != ConfidenceTier.YELLOW) {
                        Text(
                            dbhTierHint(r.confidence),
                            style = Forestix.type.caption,
                            color = Color.White.copy(alpha = 0.9f),
                        )
                    }
                    if (settings.developerMode) {
                        ResearchFieldsRow(
                            targetValue = settings.researchTreeId,
                            onTargetChange = { env.settings.setResearchTreeId(it.trim()) },
                            targetPlaceholder = "T1",
                            trueLabel = "True Ø (cm)",
                            trueValue = researchTrueCm,
                            onTrueChange = { researchTrueCm = it.filter { c -> c.isDigit() || c == '.' } },
                            truePlaceholder = "tape",
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ForestixBorderedButton("Retake", modifier = Modifier.weight(1f)) {
                        result = null; failure = null; stage = Stage.AIMING
                    }
                    ForestixBorderedButton("Details", modifier = Modifier.weight(1f)) {
                        showMetadata = true
                    }
                    ForestixProminentButton(
                        "Accept",
                        modifier = Modifier.weight(1f),
                        enabled = r.confidence != ConfidenceTier.RED,
                    ) { acceptResult(r) }
                }
            }
        }

        // No Depth API + developer mode off → the scan can't run: replace
        // the whole scanning UI with a full-page canvas blocker (iOS
        // lidarRequiredPanel presentation; the probe session keeps running
        // hidden behind the opaque page).
        if (depthBlocked) {
            Box(Modifier.fillMaxSize().background(colors.canvas)) {
                Column(
                    Modifier
                        .align(Alignment.Center)
                        .padding(ForestixSpace.lg),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(ForestixSpace.sm),
                ) {
                    Icon(
                        Icons.Outlined.ViewInAr,
                        contentDescription = null,
                        tint = colors.textTertiary,
                        modifier = Modifier.size(44.dp),
                    )
                    Text(
                        "DBH scanning requires the ARCore Depth API on this device",
                        style = Forestix.type.bodyBold,
                        color = colors.textPrimary,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        "This phone doesn't provide depth sensing, so the trunk scan can't run here.",
                        style = Forestix.type.caption,
                        color = colors.textSecondary,
                        textAlign = TextAlign.Center,
                    )
                }
                MeasureBackButton { nav.popBackStack() }
            }
        }

        // Metadata editor (species / position / damage / note).
        if (showMetadata) {
            ScanMetadataSheet(
                speciesCode = metaSpecies, onSpeciesCode = { metaSpecies = it },
                position = metaPosition, onPosition = { metaPosition = it },
                damageCodes = metaDamage, onDamageCodes = { metaDamage = it },
                note = metaNote, onNote = { metaNote = it },
                onDismiss = { showMetadata = false },
            )
        }

        // Post-save continuation — height same tree / next tree / done.
        continuationTree?.let { savedTree ->
            MeasurementContinuationSheet(
                origin = ContinuationOrigin.AFTER_DIAMETER,
                treeNumber = savedTree,
                treeAlreadyHasHeight = env.history.entries.value.any {
                    it.treeNumber == savedTree && it.kind == MeasureKind.HEIGHT
                },
                onAction = { action ->
                    continuationTree = null
                    when (action) {
                        ContinuationAction.MEASURE_HEIGHT_SAME_TREE -> {
                            nav.navigate("height?tree=$savedTree") {
                                popUpTo(Routes.TREE_HUB)
                            }
                        }
                        ContinuationAction.START_NEW_TREE_DIAMETER -> {
                            // Reset in place for the next tree.
                            result = null; failure = null
                            metaSpecies = null; metaPosition = StemPosition.DBH
                            metaDamage = emptyList(); metaNote = ""
                            pendingTree = env.history.suggestedNextTreeNumber
                            stage = Stage.AIMING
                        }
                        ContinuationAction.DONE -> nav.popBackStack()
                    }
                },
            )
        }
    }
}

@Composable
private fun DbhRing(locked: Boolean, ok: Color, bad: Color, modifier: Modifier) {
    // Red until the depth fit stabilises, then green (spec §5.2 / iOS
    // crosshairRing confidenceBad → confidenceOk).
    val ring = if (locked) ok else bad
    Box(modifier.size(72.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(72.dp).border(5.dp, Color.Black.copy(alpha = 0.6f), CircleShape))
        Box(Modifier.size(64.dp).border(2.5.dp, ring, CircleShape))
    }
}

@Composable
private fun LivePreviewBadge(
    preview: DBHEstimator.DbhPreview?,
    locked: Boolean,
    unitSystem: UnitSystem,
) {
    val type = Forestix.type
    val p = preview ?: return
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        if (locked) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.65f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    "DBH: " + MeasurementFormatter.diameter(p.diameterCm.toDouble(), unitSystem),
                    style = type.data, color = Color.White,
                )
                // Green-only tier chip — yellow stays silent (the cruiser
                // sees a bare DBH digit), iOS Phase 18.2 behaviour.
                if (p.tier == ConfidenceTier.GREEN) {
                    PreviewTierChip(p.tier.raw)
                }
            }
        }
        if (p.distanceM > 0f) {
            Text(
                "Distance: " + MeasurementFormatter.distance(p.distanceM.toDouble(), unitSystem),
                style = type.dataSmall, color = Color.White.copy(alpha = 0.85f),
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

/// Compact tier chip beside the live DBH digit — 9 sp semibold, tracking
/// 0.6, stroked chip-radius outline (iOS previewTierChip).
@Composable
private fun PreviewTierChip(rawTier: String) {
    val d = confidenceDescriptor(rawTier)
    Text(
        d.label.uppercase(Locale.US),
        style = TextStyle(fontSize = 9.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp),
        color = d.color,
        modifier = Modifier
            .border(0.75.dp, d.color, ForestixRadius.chip)
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/// Result-panel tier chip — 10 sp semibold, tracking 0.8, stroked chip
/// outline (iOS tierChip). Shared look with the Height result panel.
@Composable
private fun TierChip(rawTier: String) {
    val d = confidenceDescriptor(rawTier)
    Text(
        d.label.uppercase(Locale.US),
        style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp),
        color = d.color,
        modifier = Modifier
            .border(0.75.dp, d.color, ForestixRadius.chip)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/// Short cruiser-actionable sentence matching the tier — iOS tierHint.
private fun dbhTierHint(tier: ConfidenceTier): String = when (tier) {
    ConfidenceTier.GREEN -> "Good — wide arc, low scatter. Safe to record."
    ConfidenceTier.YELLOW -> "Fair — narrow arc or noisier fit. Consider a second pass."
    ConfidenceTier.RED -> "Check — step left 1 m and retake, or enter manually."
}

/// Map the estimator's strip edges (fractions of the DEPTH-map walk axis)
/// to on-screen x fractions through the frame's depth→view affine. The walk
/// axis is rotated 90°, aspect-cropped and possibly mirrored relative to
/// the screen in portrait, so multiplying the raw depth fractions by screen
/// width (the iOS behaviour, where the depth grid shares the screen's
/// orientation) drew the chord bar at the wrong width and offset on
/// Android. Null when the frame has no view mapping (callers keep the raw
/// fractions as a fallback).
private fun stripViewFractions(
    f: ArDepthFrame,
    axis: GuideAxis,
    leftFrac: Float,
    rightFrac: Float,
    viewW: Float,
): Pair<Float, Float>? {
    if (viewW <= 1f) return null
    val (extent, fixed) = when (axis) {
        is GuideAxis.Row -> f.width to axis.y
        is GuideAxis.Col -> f.height to axis.x
    }
    fun toViewX(alongPx: Double): Float? = when (axis) {
        is GuideAxis.Row -> f.depthToView(alongPx, fixed.toDouble())?.first
        is GuideAxis.Col -> f.depthToView(fixed.toDouble(), alongPx)?.first
    }
    val a = toViewX((leftFrac * extent).toDouble()) ?: return null
    val b = toViewX((rightFrac * extent).toDouble()) ?: return null
    val lo = (minOf(a, b) / viewW).coerceIn(0f, 1f)
    val hi = (maxOf(a, b) / viewW).coerceIn(0f, 1f)
    return lo to hi
}
