// Tree Height — Android, now using the SAME VIO walk-off tangent method as
// iOS (HeightEstimator, spec §7.2): anchor the trunk, walk back (live d_h),
// then capture the top + base aim angles; H = d_h·(tanα_top − tanα_base)
// with the identical guard rails, σ_H, and green/yellow/red tiers. α comes
// from the ARCore camera-forward elevation (the Android analogue of the
// iOS IMU pitch). Crown is folded in afterwards, reusing the measured d_h.

package com.hcjeong.forestix.ui.screens.height

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.rememberCoroutineScope
import com.hcjeong.forestix.ui.MeasurePhotoStore
import kotlinx.coroutines.launch
import androidx.navigation.NavController
import com.hcjeong.forestix.LocalAppEnvironment
import com.hcjeong.forestix.ar.ArCameraView
import com.hcjeong.forestix.ar.ArSceneMarker
import com.hcjeong.forestix.ar.ArSessionHub
import com.hcjeong.forestix.ar.MarkerShape
import com.hcjeong.forestix.ar.Vec3
import com.hcjeong.forestix.common.MeasurementFormatter
import com.hcjeong.forestix.common.UnitSystem
import com.hcjeong.forestix.data.MeasureKind
import com.hcjeong.forestix.data.QuickMeasureEntry
import com.hcjeong.forestix.data.ResearchLog
import com.hcjeong.forestix.sensors.ArDepthFrame
import com.hcjeong.forestix.sensors.ConfidenceTier
import com.hcjeong.forestix.sensors.HeightEstimator
import com.hcjeong.forestix.sensors.HeightMethod
import com.hcjeong.forestix.sensors.HeightResult
import com.hcjeong.forestix.sensors.RawCaptureStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hcjeong.forestix.ui.PendingTreeNumber
import com.hcjeong.forestix.ui.Routes
import com.hcjeong.forestix.ui.screens.cruise.CruiseCapture
import com.hcjeong.forestix.ui.screens.ScanMetadataSheet
import com.hcjeong.forestix.ui.screens.DevHud
import com.hcjeong.forestix.ui.screens.GPSAccuracyBadge
import com.hcjeong.forestix.ui.screens.MeasureBackButton
import com.hcjeong.forestix.ui.screens.MeasureCircleButton
import com.hcjeong.forestix.ui.screens.MeasureShutterBar
import com.hcjeong.forestix.ui.screens.MeasureStatusPanel
import com.hcjeong.forestix.ui.screens.MeasureTopChrome
import com.hcjeong.forestix.ui.screens.MeasureValuePill
import com.hcjeong.forestix.ui.screens.ResearchFieldsRow
import com.hcjeong.forestix.ui.screens.ScanPlotMiniMap
import com.hcjeong.forestix.ui.screens.scanPlotMiniMapVisible
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixProminentButton
import com.hcjeong.forestix.ui.theme.ForestixWhiteButton
import kotlinx.coroutines.delay
import java.io.File
import java.util.Locale
import kotlin.math.abs

// Aim BASE before TOP: from eye level you tilt down slightly to the base,
// then sweep up to the top — one continuous motion, less device rotation.
private enum class Stage { ANCHOR, WALKING, AIM_BASE, AIM_TOP, COMPUTED, REJECTED }
private enum class CrownStep { NONE, LEFT, RIGHT, TOP, BOTTOM, DONE }

/// Anchor range gate (locked spec, iOS applies the same): the trunk anchor
/// may only be captured from a hit ≤ 4 m away. Field round 8: with no
/// DepthPoint available, a near-horizontal eye-level aim intersected the
/// detected GROUND plane 12–44 m out and the walk-off readout started at
/// that phantom distance.
private const val ANCHOR_MAX_M = 4.0f

@Composable
fun HeightScanScreen(nav: NavController, treeOverride: Int? = null) {
    val env = LocalAppEnvironment.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Shared app-scoped AR session (world coordinates survive navigation,
    // and the sampling plot's anchor renders here as a subdued overlay).
    val controller = ArSessionHub.controller
    // Continuation from a just-saved DBH passes the SAME tree number so the
    // height lands on that tree; the map-home chooser hands one over via
    // PendingTreeNumber; otherwise the next free number is used. The slot
    // is consumed unconditionally so a stale lock can never leak into a
    // later capture session.
    var pendingTree by remember {
        mutableStateOf(
            PendingTreeNumber.consume().let { pending ->
                treeOverride ?: pending ?: env.history.suggestedNextTreeNumber
            },
        )
    }
    // Developer-mode research capture: true height (m) from a clinometer.
    var researchTrueM by remember { mutableStateOf("") }
    val settings by env.settings.state.collectAsStateWithLifecycle()
    // Project calibration — identity for plain quick-measure (iOS parity),
    // the active project's VIO drift fraction when launched from the
    // Add-Tree flow (iOS injects it into HeightScanViewModel).
    val calibration by env.activeScanCalibration.collectAsStateWithLifecycle()
    val type = Forestix.type

    var stage by remember { mutableStateOf(Stage.ANCHOR) }
    var anchorPt by remember { mutableStateOf<Vec3?>(null) }
    var standingLocked by remember { mutableStateOf<Vec3?>(null) }
    var alphaBase by remember { mutableStateOf<Float?>(null) }
    var alphaTop by remember { mutableStateOf<Float?>(null) }
    var topMarker by remember { mutableStateOf<Vec3?>(null) }
    var baseMarker by remember { mutableStateOf<Vec3?>(null) }
    var dhLive by remember { mutableStateOf(0f) }
    // Walk-panel triplet (locked spec): camera→anchor horizontal distance
    // at the MOMENT of anchoring, and the camera's displacement since then
    // (starts at 0.00 — the old single readout confusingly opened at the
    // full anchor distance). Total distance = dhLive (the d_h the math uses).
    var anchorInitialDistM by remember { mutableStateOf<Float?>(null) }
    var standingAtAnchor by remember { mutableStateOf<Vec3?>(null) }
    var walkedLive by remember { mutableStateOf(0f) }
    // Live anchor-aim validity (gated hit available?) — drives the locked
    // "Move closer" status line and keeps the anchor "+" inert.
    var anchorAimOk by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<HeightResult?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }

    // Manual height entry (typed metres) — iOS .manualEntry state.
    var manualOpen by remember { mutableStateOf(false) }
    var manualText by remember { mutableStateOf("") }
    // Accept-snapshot chrome blackout: while true, every 2D panel/button is
    // hidden so the captured JPEG shows only the AR feed + measurement
    // overlays (captured buttons read as real buttons in the photo viewer).
    var hidingChromeForCapture by remember { mutableStateOf(false) }
    // Scan metadata (species / damage / note) attached on Accept — iOS
    // ScanMetadataSheet(kind: .height); no stem position for heights.
    var metaSpecies by remember { mutableStateOf<String?>(null) }
    var metaDamage by remember { mutableStateOf<List<String>>(emptyList()) }
    var metaNote by remember { mutableStateOf("") }
    var showMetadata by remember { mutableStateOf(false) }

    // Raw-capture recorder arm (developer mode + tc.rawCaptureEnabled) +
    // the geometry a height bundle records: full camera poses at anchor /
    // base / top, the anchor hit type, and the 5 Hz pose-sample trail from
    // anchoring to compute. Populated only while armed; zero cost otherwise.
    val rawCaptureArmed = settings.developerMode && settings.rawCaptureEnabled
    var anchorPose by remember { mutableStateOf<FloatArray?>(null) }
    var anchorHitType by remember { mutableStateOf("unknown") }
    var basePose by remember { mutableStateOf<FloatArray?>(null) }
    var topPose by remember { mutableStateOf<FloatArray?>(null) }
    // Schema-2 height capture: the AR depth frame + a reference RGB JPEG
    // grabbed AT the base tap and the top tap (same u16-mm depth + ~80% JPEG
    // the DBH path stores), so future height algorithms can be re-run over the
    // aim pixels. Populated only while armed; the tangent replay never reads
    // them, so an old bundle without them still re-runs unchanged.
    var baseAimFrame by remember { mutableStateOf<ArDepthFrame?>(null) }
    var topAimFrame by remember { mutableStateOf<ArDepthFrame?>(null) }
    var baseAimRgb by remember { mutableStateOf<ByteArray?>(null) }
    var topAimRgb by remember { mutableStateOf<ByteArray?>(null) }
    // Keep the shared controller's raw-depth arm on for the whole height
    // session while recording, so acquireDepthFrame() at the base/top tap
    // carries the native u16 grid to serialize. Nothing else in the height
    // flow calls acquireDepthFrame, so there is no per-frame cost.
    DisposableEffect(rawCaptureArmed) {
        controller.captureRawDepth = rawCaptureArmed
        onDispose { controller.captureRawDepth = false }
    }
    // Grab the current AR depth frame + reference RGB JPEG bytes at an aim
    // moment. Returns (frame, jpegBytes) — either may be null; recordHeight
    // degrades gracefully. The JPEG is written to a throwaway cache file (the
    // only sink captureCameraJpeg offers), read into memory, then deleted — so
    // nothing lingers and there is no temp-file lifecycle to race.
    fun captureAim(): Pair<ArDepthFrame?, ByteArray?> {
        if (!rawCaptureArmed) return null to null
        val frame = controller.acquireDepthFrame()
        val rgb = try {
            val tmp = File.createTempFile("height_aim_", ".jpg", context.cacheDir)
            val bytes = if (controller.captureCameraJpeg(tmp)) tmp.readBytes() else null
            tmp.delete()
            bytes
        } catch (_: Throwable) { null }
        return frame to rgb
    }
    val poseSamples = remember { mutableListOf<RawCaptureStore.PoseSample>() }
    var anchorTimeMs by remember { mutableStateOf(0L) }
    // The most-recent compute's stored bundle id — flipped to accepted when
    // the cruiser taps Accept (iOS markAccepted flow).
    var lastRawCaptureId by remember { mutableStateOf<String?>(null) }

    var crownStep by remember { mutableStateOf(CrownStep.NONE) }
    var cL by remember { mutableStateOf<Vec3?>(null) }
    var cR by remember { mutableStateOf<Vec3?>(null) }
    var cT by remember { mutableStateOf<Vec3?>(null) }
    var cB by remember { mutableStateOf<Vec3?>(null) }
    var crownW by remember { mutableStateOf<Double?>(null) }
    var crownH by remember { mutableStateOf<Double?>(null) }

    // Live walk-off distances while walking back: Total (camera→anchor,
    // the d_h the math uses) + Walked (displacement since anchoring).
    LaunchedEffect(stage) {
        while (stage == Stage.WALKING) {
            anchorPt?.let { a -> controller.horizontalDistanceTo(a)?.let { dhLive = it } }
            standingAtAnchor?.let { s0 -> controller.horizontalDistanceTo(s0)?.let { walkedLive = it } }
            delay(100)
        }
    }

    // Raw-capture pose trail: sample the camera pose at 5 Hz from anchoring
    // through compute (the walk-off + both aims) so the replay can re-derive
    // d_h from the pose sequence and expose VIO drift. Armed-only.
    LaunchedEffect(stage, rawCaptureArmed) {
        if (!rawCaptureArmed) return@LaunchedEffect
        while (stage == Stage.WALKING || stage == Stage.AIM_BASE || stage == Stage.AIM_TOP) {
            controller.currentCameraPose()?.let {
                poseSamples.add(RawCaptureStore.PoseSample(
                    System.currentTimeMillis() - anchorTimeMs, it))
            }
            delay(200)
        }
    }

    var devHitInfo by remember { mutableStateOf<String?>(null) }

    // Anchor-stage aim gate poll (always on): a valid gated hit must exist
    // for the "+" to do anything, and the status line shows the locked
    // "Move closer" copy otherwise. Also feeds the dev HUD's hit line with
    // accept/reject readouts ("plane 31.2m rejected").
    LaunchedEffect(stage) {
        if (stage != Stage.ANCHOR) return@LaunchedEffect
        while (true) {
            anchorAimOk = controller.screenCenterAnchorHit(ANCHOR_MAX_M) != null
            devHitInfo = controller.lastCenterHitInfo
            delay(250)
        }
    }

    // Dev-mode probe for the OTHER stages: raycast the crosshair a few
    // times a second so the HUD's hit line stays live (the anchor stage is
    // covered by the gate poll above).
    LaunchedEffect(settings.developerMode, stage) {
        while (settings.developerMode && stage != Stage.ANCHOR) {
            controller.screenCenterHit()
            devHitInfo = controller.lastCenterHitInfo
            delay(250)
        }
    }

    fun markers(): List<ArSceneMarker> {
        // scalesWithDistance keeps every sphere readable from across the
        // walk-off — natural size up close, grows with camera distance.
        val out = mutableListOf<ArSceneMarker>()
        anchorPt?.let { out.add(ArSceneMarker(it, MarkerShape.Sphere(0.08f), floatArrayOf(1f, 0.30f, 0.30f, 1f), scalesWithDistance = true)) }
        // Tree top (yellow) + base (green) spheres on the anchor's vertical
        // axis at the alpha-derived height — same as iOS rebuildSceneMarkers.
        topMarker?.let { out.add(ArSceneMarker(it, MarkerShape.Sphere(0.08f), floatArrayOf(1f, 0.85f, 0.15f, 1f), scalesWithDistance = true)) }
        baseMarker?.let { out.add(ArSceneMarker(it, MarkerShape.Sphere(0.08f), floatArrayOf(0.25f, 0.85f, 0.35f, 1f), scalesWithDistance = true)) }
        // Crown L/R yellow matches iOS exactly (1, 0.85, 0, 1); crown T/B cyan.
        val yellow = floatArrayOf(1f, 0.85f, 0f, 1f); val cyan = floatArrayOf(0.2f, 0.7f, 1f, 1f)
        cL?.let { out.add(ArSceneMarker(it, MarkerShape.Sphere(0.05f), yellow, scalesWithDistance = true)) }
        cR?.let { out.add(ArSceneMarker(it, MarkerShape.Sphere(0.05f), yellow, scalesWithDistance = true)) }
        cT?.let { out.add(ArSceneMarker(it, MarkerShape.Sphere(0.05f), cyan, scalesWithDistance = true)) }
        cB?.let { out.add(ArSceneMarker(it, MarkerShape.Sphere(0.05f), cyan, scalesWithDistance = true)) }
        return out
    }

    fun crownProjDistance(): Float = (result?.dHm ?: dhLive).let { if (it > 0.5f) it else 8f }

    fun computeCrown() {
        cL?.let { l -> cR?.let { r -> val dx = l.x - r.x; val dz = l.z - r.z; crownW = kotlin.math.sqrt((dx * dx + dz * dz).toDouble()) } }
        cT?.let { t -> cB?.let { b -> crownH = abs(t.y - b.y).toDouble() } }
        crownStep = CrownStep.DONE
    }

    fun captureCrown() {
        // Geometric placement (locked spec, iOS parity): the capture ray
        // direction at the current pitch × the anchor-plane horizontal
        // distance. NO fresh hitTest — at 10–30 m those fall back to
        // planes/garbage and put the sphere visibly off the crosshair.
        val hit = controller.forwardPointAtHorizontalDistance(crownProjDistance())
        if (hit == null) { failure = "AR tracking not ready — try again."; return }
        failure = null
        when (crownStep) {
            CrownStep.LEFT -> { cL = hit; crownStep = CrownStep.RIGHT }
            CrownStep.RIGHT -> { cR = hit; crownStep = CrownStep.TOP }
            CrownStep.TOP -> { cT = hit; crownStep = CrownStep.BOTTOM }
            CrownStep.BOTTOM -> { cB = hit; computeCrown() }
            else -> {}
        }
    }

    // Serialize one Height compute for offline estimator replay (off the
    // main thread). Records anchor / base / top geometry, d_h, calibration,
    // and the 5 Hz pose trail; the store re-runs HeightEstimator.estimate
    // from disk for the reproducibility self-check.
    fun recordRawHeight(r: HeightResult, anchor: Vec3, aBase: Float, aTop: Float) {
        if (!rawCaptureArmed) return
        val aPose = anchorPose ?: return
        val bPoseRaw = basePose ?: return
        val tPose = topPose ?: return
        val standing = standingLocked ?: return
        // Pin the base camera_pose's translation column to the EXACT standing
        // point the live estimator used, so the replay's standing == live and
        // the self-check is exact (iOS parity).
        val bPose = bPoseRaw.copyOf().also {
            it[12] = standing.x; it[13] = standing.y; it[14] = standing.z
        }
        val cruise = CruiseCapture.target
        val ctx = RawCaptureStore.CaptureContext(
            mode = if (cruise != null) "cruise" else "quick",
            projectId = cruise?.projectId?.toString(),
            plotId = cruise?.plotId?.toString() ?: env.history.activePlotID.value?.toString(),
            treeNumber = cruise?.treeNumber ?: pendingTree,
            gps = com.hcjeong.forestix.positioning.LocationService.lastGlobalFix,
        )
        val samples = poseSamples.toList()
        val hitType = anchorHitType
        val dist = anchorInitialDistM ?: 0f
        // Schema-2 aim captures (base/top depth + RGB). Snapshot the temp files
        // now and hand them off; recordHeight copies them into the bundle.
        val baseAim = RawCaptureStore.HeightAim(baseAimFrame, baseAimRgb)
        val topAim = RawCaptureStore.HeightAim(topAimFrame, topAimRgb)
        scope.launch {
            val id = RawCaptureStore.recordHeight(
                context = context,
                anchorWorld = anchor, anchorHitType = hitType,
                anchorDistanceM = dist, anchorPose = aPose,
                basePitchDeg = (aBase * 180f / Math.PI.toFloat()), basePose = bPose,
                topPitchDeg = (aTop * 180f / Math.PI.toFloat()), topPose = tPose,
                dHm = r.dHm, live = r, cal = calibration,
                poseSamples = samples,
                unitSystem = if (settings.unitSystem == UnitSystem.METRIC) "metric" else "imperial",
                ctx = ctx,
                baseAim = baseAim,
                topAim = topAim,
            )
            if (id != null) lastRawCaptureId = id
        }
    }

    fun captureHeight() {
        when (stage) {
            Stage.ANCHOR -> {
                // Gated anchor (locked spec): DepthPoint or ray-facing Plane
                // within 4 m only. No valid hit → the "+" is INERT — the
                // status line already shows the locked "Move closer" copy.
                val hit = controller.screenCenterAnchorHit(ANCHOR_MAX_M) ?: return
                failure = null
                anchorPt = hit
                standingAtAnchor = controller.currentCameraPosition()
                val d0 = controller.horizontalDistanceTo(hit) ?: 0f
                anchorInitialDistM = d0
                dhLive = d0
                walkedLive = 0f
                // Raw-capture: latch the anchor pose + hit provenance and
                // start a fresh 5 Hz pose trail from this instant.
                if (rawCaptureArmed) {
                    anchorPose = controller.currentCameraPose()
                    anchorHitType = controller.lastCenterHitInfo?.substringBefore(' ') ?: "unknown"
                    poseSamples.clear()
                    anchorTimeMs = System.currentTimeMillis()
                }
                stage = Stage.WALKING
            }
            Stage.WALKING -> { stage = Stage.AIM_BASE }
            Stage.AIM_BASE -> {
                val a = controller.cameraForwardElevationRad()
                val s = controller.currentCameraPosition()
                val anchor = anchorPt
                if (a == null || s == null || anchor == null) { failure = "AR tracking not ready — try again."; return }
                // Lock the standing pose on the first aim; both angles must
                // come from the same spot (the §7.2 formula assumes it).
                failure = null; alphaBase = a; standingLocked = s
                if (rawCaptureArmed) {
                    basePose = controller.currentCameraPose()
                    val (fr, rgb) = captureAim()
                    baseAimFrame = fr; baseAimRgb = rgb
                }
                val dh = kotlin.math.sqrt((s.x - anchor.x) * (s.x - anchor.x) + (s.z - anchor.z) * (s.z - anchor.z))
                baseMarker = Vec3(anchor.x, s.y + dh * kotlin.math.tan(a), anchor.z)
                stage = Stage.AIM_TOP
            }
            Stage.AIM_TOP -> {
                val aTop = controller.cameraForwardElevationRad()
                val anchor = anchorPt; val standing = standingLocked; val aBase = alphaBase
                if (aTop == null || anchor == null || standing == null || aBase == null) {
                    failure = "AR tracking not ready — try again."; return
                }
                failure = null; alphaTop = aTop
                if (rawCaptureArmed) {
                    topPose = controller.currentCameraPose()
                    val (fr, rgb) = captureAim()
                    topAimFrame = fr; topAimRgb = rgb
                }
                val dh = kotlin.math.sqrt((standing.x - anchor.x) * (standing.x - anchor.x) + (standing.z - anchor.z) * (standing.z - anchor.z))
                topMarker = Vec3(anchor.x, standing.y + dh * kotlin.math.tan(aTop), anchor.z)
                val r = HeightEstimator.estimate(
                    anchorX = anchor.x, anchorZ = anchor.z,
                    standingX = standing.x, standingZ = standing.z,
                    alphaTopRad = aTop, alphaBaseRad = aBase,
                    vioDriftFraction = calibration.vioDriftFraction,
                )
                result = r
                // Raw-capture: serialize this compute for offline replay
                // (every compute, accepted or rejected). Off the main thread.
                recordRawHeight(r, anchor, aBase, aTop)
                stage = if (r.confidence == ConfidenceTier.RED) Stage.REJECTED else Stage.COMPUTED
            }
            else -> {}
        }
    }

    fun resetCrown() { crownStep = CrownStep.NONE; cL = null; cR = null; cT = null; cB = null; crownW = null; crownH = null }
    fun resetAll() {
        stage = Stage.ANCHOR; anchorPt = null; standingLocked = null
        alphaBase = null; alphaTop = null; topMarker = null; baseMarker = null
        dhLive = 0f; anchorInitialDistM = null; standingAtAnchor = null
        walkedLive = 0f; anchorAimOk = false
        result = null; failure = null; resetCrown()
        // Drop the raw-capture geometry so a fresh measurement starts clean.
        anchorPose = null; basePose = null; topPose = null
        anchorHitType = "unknown"; poseSamples.clear()
        baseAimFrame = null; topAimFrame = null; baseAimRgb = null; topAimRgb = null
    }

    // Accept the result: record the entry (photo + GPS + metadata), fold in
    // the crown if measured, fire the continuation, and log the research
    // row. Shared by the result row's Accept and the manual-entry Save
    // (iOS submitManualEntry goes straight to .accepted).
    fun acceptResult(r: HeightResult) {
        val activity = context as? android.app.Activity
        // Cruise tally session (v3): the height leg folds into the Tree row
        // the DBH leg created, then the chain returns to the cruise map —
        // no quick-history entry, no continuation sheet.
        val cruise = CruiseCapture.target
        // Snapshot the dev "True H" field now — the developer block below
        // clears it synchronously before the async launch runs.
        val rawTrue = researchTrueM.toDoubleOrNull()?.takeIf { it > 0 }
        scope.launch {
            // Flip the just-recorded raw-capture bundle to accepted (iOS
            // markAccepted parity) + fold in the field-entered ground truth.
            lastRawCaptureId?.let { rid ->
                RawCaptureStore.markAccepted(context, rid)
                if (rawTrue != null) RawCaptureStore.setTruth(context, rid, rawTrue)
                lastRawCaptureId = null
            }
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
            if (cruise != null) {
                // Storage failure must not crash the AR screen — the tree
                // keeps its DBH-only row from the first leg either way.
                runCatching { CruiseCapture.recordHeight(env, r, photoPath = photo, fix = fix) }
                // Back to the map home in cruise mode (the chain popped DBH
                // already; tc.mapMode is still "cruise"); the new tree pin
                // appears and the tally pill ticks up.
                nav.popBackStack()
            } else {
                env.history.append(
                    QuickMeasureEntry(
                        kind = MeasureKind.HEIGHT, value = r.heightM.toDouble(),
                        sigma = r.sigmaHm.toDouble(), confidenceRaw = r.confidence.raw,
                        method = r.method.raw, treeNumber = pendingTree,
                        plotID = env.history.activePlotID.value,
                        speciesCode = metaSpecies,
                        damageCodes = metaDamage,
                        note = metaNote.ifBlank { null },
                        latitude = fix?.latitude,
                        longitude = fix?.longitude,
                        photoPath = photo,
                    )
                )
                // Quick measure saved — no continuation prompt (iOS parity);
                // return to the map once the save completes.
                nav.popBackStack()
            }
        }
        if (cruise == null && crownStep == CrownStep.DONE) {
            val w = crownW; val ch = crownH
            if (w != null && ch != null) {
                env.history.append(
                    QuickMeasureEntry(
                        kind = MeasureKind.CROWN, value = w, secondaryValue = ch,
                        sigma = null, confidenceRaw = "green", method = "ar.crown.dh",
                        treeNumber = pendingTree, plotID = env.history.activePlotID.value,
                    )
                )
            }
        }
        if (settings.developerMode) {
            val fields = mutableMapOf(
                "measure_type" to "height",
                "method" to r.method.raw,
                "depth_source" to "ar",
                "measured_value" to String.format(Locale.US, "%.2f", r.heightM),
                "unit" to "m",
                "sigma" to String.format(Locale.US, "%.2f", r.sigmaHm),
                "confidence_tier" to r.confidence.raw,
                "distance_m" to String.format(Locale.US, "%.2f", r.dHm),
                "alpha_top_deg" to String.format(Locale.US, "%.2f", r.alphaTopRad * 180f / Math.PI.toFloat()),
                "alpha_base_deg" to String.format(Locale.US, "%.2f", r.alphaBaseRad * 180f / Math.PI.toFloat()),
                "species" to (metaSpecies ?: ""),
                "note" to metaNote,
            )
            if (settings.researchTreeId.isNotEmpty()) {
                fields["tree_id"] = settings.researchTreeId  // repeat auto-filled by record()
            }
            researchTrueM.toDoubleOrNull()?.takeIf { it > 0 }?.let { t ->
                fields["true_value"] = String.format(Locale.US, "%.2f", t)
                fields["error"] = String.format(Locale.US, "%.2f", r.heightM - t)
            }
            ResearchLog.record(context, fields)
            researchTrueM = ""
        }
    }

    val crownActive = crownStep != CrownStep.NONE && crownStep != CrownStep.DONE
    val showCapture = !manualOpen &&
        (stage in listOf(Stage.ANCHOR, Stage.WALKING, Stage.AIM_TOP, Stage.AIM_BASE) || crownActive)

    // Crosshair label — explains what the next "+" tap will capture; nil
    // hides the crosshair (computed / rejected / manual states). Direct
    // port of iOS crosshairLabel.
    val crosshairLabel: String? = when {
        manualOpen -> null
        crownStep == CrownStep.LEFT -> "Aim at crown's LEFT edge"
        crownStep == CrownStep.RIGHT -> "Aim at crown's RIGHT edge"
        crownStep == CrownStep.TOP -> "Aim at HIGHEST branch"
        crownStep == CrownStep.BOTTOM -> "Aim at LOWEST branch"
        crownStep == CrownStep.DONE -> null
        stage == Stage.ANCHOR -> "Aim at trunk (eye level)"
        stage == Stage.WALKING -> "Walk back — aim stays on tree"
        stage == Stage.AIM_TOP -> "Aim at treetop"
        stage == Stage.AIM_BASE -> "Aim at trunk + ground"
        else -> null
    }

    Box(Modifier.fillMaxSize()) {
        ArCameraView(
            controller,
            markers(),
            modifier = Modifier.fillMaxSize(),
            // Active sampling plot (if any) as a subdued, non-interactive
            // overlay under the height markers.
            plotOverlay = ArSessionHub.PlotOverlay.SUBDUED,
        )
        crosshairLabel?.let { label ->
            HeightAimCrosshair(label, Modifier.align(Alignment.Center))
        }
        if (!hidingChromeForCapture) MeasureBackButton { nav.popBackStack() }

        // Same GPS-accuracy strip as the Diameter scan — leading 72 /
        // top 22, clear of the floating back button (iOS parity).
        if (!hidingChromeForCapture) GPSAccuracyBadge(
            Modifier
                .align(Alignment.TopStart)
                .padding(start = 72.dp, top = 22.dp))

        // Plot mini-map — top-right, same row as the GPS badge: the active
        // cruise plot (ring + YOU + measured trees) or the quick sampling
        // ring (ring + YOU). Hidden with the rest of the 2D chrome during
        // the Accept snapshot blackout.
        val miniMapUp = scanPlotMiniMapVisible()
        if (!hidingChromeForCapture) ScanPlotMiniMap()

        if (settings.developerMode && !hidingChromeForCapture) {
            DevHud(
                "HEIGHT",
                listOfNotNull(
                    "depth" to (if (controller.supportsDepth) "ARCore✓" else "plane"),
                    "track" to (if (controller.trackingOk()) "OK" else "…"),
                    "stage" to stage.name,
                    // What the last centre raycast landed on (type + range) —
                    // the anchor sphere is placed exactly at this hit, so a
                    // "point"/far reading here explains an off-trunk anchor.
                    "hit" to (devHitInfo ?: "—"),
                    "pitch" to (controller.cameraPitchDeg()?.let { String.format(Locale.US, "%+.1f°", it) } ?: "—"),
                    "d_h live" to String.format(Locale.US, "%.1f m", dhLive),
                    alphaBase?.let { "α_base" to String.format(Locale.US, "%+.1f°", Math.toDegrees(it.toDouble())) },
                    alphaTop?.let { "α_top" to String.format(Locale.US, "%+.1f°", Math.toDegrees(it.toDouble())) },
                    result?.let { "H" to String.format(Locale.US, "%.1f ±%.1f m · %s", it.heightM, it.sigmaHm, it.confidence.raw) },
                ),
                // Below the plot mini-map when it occupies the top-right
                // slot (22 + 116 card + 12 gap).
                topPadding = if (miniMapUp) 150.dp else 56.dp,
            )
        }
        // U2 — bottom-centre shutter for the capture stages: "Manual"
        // flanks left while anchoring, Retake flanks RIGHT once a
        // walk/aim stage (or crown capture) could need restarting, and
        // the WALKING readout triplet rides the live-value strip directly
        // above the row (iOS MeasureShutterRow wiring 1:1).
        if (showCapture && !hidingChromeForCapture) MeasureShutterBar(
            onCapture = { if (crownActive) captureCrown() else captureHeight() },
            left = if (stage == Stage.ANCHOR && !crownActive) {
                {
                    MeasureCircleButton(icon = Icons.Filled.Keyboard, caption = "Manual") {
                        manualOpen = true; manualText = ""
                    }
                }
            } else {
                null
            },
            right = if (crownActive || stage in listOf(
                    Stage.WALKING, Stage.AIM_BASE, Stage.AIM_TOP)
            ) {
                {
                    MeasureCircleButton(icon = Icons.Filled.Replay, caption = "Retake") {
                        resetAll()
                    }
                }
            } else {
                null
            },
            valueStrip = when {
                stage == Stage.WALKING -> ({
                    // Walking readout (locked spec, identical on iOS): ONLY
                    // three lines — Initial dist (camera→anchor at the
                    // anchoring moment), Walked back (displacement since
                    // anchoring, STARTS AT 0.00), and the primary Total
                    // distance (current camera→anchor horizontal distance —
                    // the d_h the math uses).
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        MeasureValuePill(
                            "Initial dist " + MeasurementFormatter.distance(
                                (anchorInitialDistM ?: 0f).toDouble(), settings.unitSystem),
                            dimmed = true,
                        )
                        MeasureValuePill(
                            "Walked back " + MeasurementFormatter.distance(
                                walkedLive.toDouble(), settings.unitSystem),
                            dimmed = true,
                        )
                        MeasureValuePill(
                            "Total distance " + MeasurementFormatter.distance(
                                dhLive.toDouble(), settings.unitSystem),
                            large = true,
                        )
                    }
                })
                // Crown capture: keep the computed height on screen while
                // the canopy taps run (iOS heightValueStrip parity).
                crownActive && result != null -> ({
                    MeasureValuePill(
                        MeasurementFormatter.height(
                            result!!.heightM.toDouble(), settings.unitSystem),
                        large = true,
                    )
                })
                else -> null
            },
        )

        // U1 — stage guidance + the dismissible anchor/tracking failure
        // banner, top-centre (iOS anchorFailureBanner semantics kept; the
        // crown aim instructions live on the crosshair label).
        if (!hidingChromeForCapture) MeasureTopChrome(
            instruction = when {
                manualOpen -> "Enter height manually in metres."
                // Locked spec: while no gated hit exists the "+" is
                // inert and this exact copy explains why.
                stage == Stage.ANCHOR && !anchorAimOk ->
                    "Move closer — anchor within 4 m of the trunk."
                stage == Stage.ANCHOR -> "Aim at the trunk at eye level, then tap +."
                stage == Stage.WALKING -> "Walk back, then tap + to continue."
                stage == Stage.AIM_BASE -> "Aim at where the trunk meets the ground, then tap +."
                stage == Stage.AIM_TOP -> "Aim at the treetop, then tap +."
                stage == Stage.REJECTED -> result?.rejectionReason ?: "Rejected."
                else -> "Height computed."
            },
            failure = failure,
            onDismissFailure = { failure = null },
        )

        // Manual entry — typed height (iOS .manualEntry: field + Save,
        // Cancel row beneath). The panel keeps the field + action rows.
        if (!hidingChromeForCapture && manualOpen) MeasureStatusPanel {
            if (manualOpen) {
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
                                if (settings.unitSystem == UnitSystem.METRIC) "Height in metres"
                                else "Height in feet",
                            )
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                    ForestixProminentButton(
                        "Save",
                        enabled = (manualText.toFloatOrNull() ?: 0f) > 1.3f,
                    ) {
                        val m = manualText.toFloatOrNull()
                        if (m != null && m > 1.3f) {
                            val r = HeightResult(
                                heightM = m, dHm = 0f, alphaTopRad = 0f, alphaBaseRad = 0f,
                                sigmaHm = 0f, confidence = ConfidenceTier.YELLOW,
                                method = HeightMethod.MANUAL_ENTRY, rejectionReason = null,
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
                ForestixWhiteButton("Cancel", modifier = Modifier.fillMaxWidth()) {
                    manualOpen = false
                }
            }
        }

        // RESULT states (COMPUTED / REJECTED, crown capture excepted — the
        // shutter row owns the crown taps): the existing result panel
        // occupies the bottom as before. Unit-aware H + the α/d_h
        // diagnostic line. Field fix: no σ, no tier chip, no tier hint —
        // σ and the tier stay recorded internally, the tier still gates
        // Accept, and a rejection's reason shows in the banner above.
        if (!hidingChromeForCapture && !manualOpen && !crownActive &&
            (stage == Stage.COMPUTED || stage == Stage.REJECTED)
        ) MeasureStatusPanel {
            result?.let { r ->
                Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalAlignment = Alignment.Start,
                ) {
                    Text(
                        MeasurementFormatter.height(r.heightM.toDouble(), settings.unitSystem),
                        style = type.dataLarge,
                        color = Color.White,
                    )
                    // Diagnostic — the raw captured inputs that fed the §7.2
                    // formula (iOS diagnosticLine).
                    Text(
                        String.format(
                            Locale.US, "α_top %+.1f° · α_base %+.1f° · d_h %.2f m",
                            r.alphaTopRad * 180f / Math.PI.toFloat(),
                            r.alphaBaseRad * 180f / Math.PI.toFloat(),
                            r.dHm,
                        ),
                        style = type.caption,
                        color = Color.White.copy(alpha = 0.55f),
                    )
                    if (settings.developerMode) {
                        ResearchFieldsRow(
                            targetValue = settings.researchTreeId,
                            onTargetChange = { env.settings.setResearchTreeId(it.trim()) },
                            targetPlaceholder = "T1",
                            trueLabel = "True H (m)",
                            trueValue = researchTrueM,
                            onTrueChange = { researchTrueM = it.filter { c -> c.isDigit() || c == '.' } },
                            truePlaceholder = "clinometer",
                        )
                    }
                }
            }

            // Crown line once measured (iOS crownSection — the capture
            // prompts live in the top banner + crosshair label now).
            if (stage == Stage.COMPUTED && crownStep == CrownStep.DONE &&
                crownW != null && crownH != null
            ) {
                Text(
                    String.format(Locale.US, "Crown %.2f m wide · %.2f m tall", crownW, crownH),
                    style = type.data,
                    color = Color.White,
                )
            }

            // Action rows (iOS actionRow).
            when (stage) {
                Stage.COMPUTED -> {
                    Column(
                        Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // Crown control: start it, or restart it once done.
                        if (crownStep == CrownStep.NONE) {
                            ForestixWhiteButton("Measure crown", modifier = Modifier.fillMaxWidth()) {
                                crownStep = CrownStep.LEFT
                            }
                        } else if (crownStep == CrownStep.DONE) {
                            ForestixWhiteButton("Redo crown", modifier = Modifier.fillMaxWidth()) {
                                resetCrown()
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            ForestixWhiteButton("Retake", modifier = Modifier.weight(1f)) { resetAll() }
                            ForestixWhiteButton("Details", modifier = Modifier.weight(1f)) {
                                showMetadata = true
                            }
                            ForestixProminentButton(
                                "Accept",
                                modifier = Modifier.weight(1f),
                                enabled = result?.confidence != ConfidenceTier.RED,
                            ) {
                                result?.let { acceptResult(it) }
                            }
                        }
                    }
                }
                Stage.REJECTED -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ForestixProminentButton("Retake") { resetAll() }
                        ForestixWhiteButton("Manual") {
                            resetAll(); manualOpen = true; manualText = ""
                        }
                    }
                }
                else -> {}
            }
        }

        // Metadata editor (species / damage / note — no stem position for
        // heights, iOS kind .height parity).
        if (showMetadata) {
            ScanMetadataSheet(
                speciesCode = metaSpecies, onSpeciesCode = { metaSpecies = it },
                position = null, onPosition = {},
                damageCodes = metaDamage, onDamageCodes = { metaDamage = it },
                note = metaNote, onNote = { metaNote = it },
                showPosition = false,
                onDismiss = { showMetadata = false },
            )
        }

    }
}

/// Amber labelled aim crosshair — port of the iOS Height `crosshair(label:)`:
/// dual-stroke 40/36 ring + cross with dark halos, and a state pill that
/// explains what the next tap captures.
@Composable
private fun HeightAimCrosshair(label: String, modifier: Modifier = Modifier) {
    val warn = Forestix.colors.confidenceWarn
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(40.dp).clip(CircleShape).border(4.dp, Color.Black.copy(alpha = 0.6f), CircleShape))
            Box(Modifier.size(36.dp).clip(CircleShape).border(2.dp, warn, CircleShape))
            Box(Modifier.size(width = 16.dp, height = 3.5.dp).background(Color.Black.copy(alpha = 0.6f)))
            Box(Modifier.size(width = 3.5.dp, height = 16.dp).background(Color.Black.copy(alpha = 0.6f)))
            Box(Modifier.size(width = 14.dp, height = 1.5.dp).background(warn))
            Box(Modifier.size(width = 1.5.dp, height = 14.dp).background(warn))
        }
        Text(
            label,
            style = Forestix.type.dataSmall,
            color = Color.White,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(Color.Black.copy(alpha = 0.65f))
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

