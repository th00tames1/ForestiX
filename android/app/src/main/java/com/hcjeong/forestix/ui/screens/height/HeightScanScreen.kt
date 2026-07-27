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
import androidx.compose.material.icons.filled.SkipNext
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
import com.hcjeong.forestix.common.TruthInput
import com.hcjeong.forestix.common.UnitSystem
import com.hcjeong.forestix.common.Units
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
import com.hcjeong.forestix.ui.screens.cruise.CruiseRoutes
import com.hcjeong.forestix.ui.screens.ScanMetadataSheet
import com.hcjeong.forestix.ui.screens.GPSAccuracyBadge
import com.hcjeong.forestix.ui.screens.MeasureBackButton
import com.hcjeong.forestix.ui.screens.MeasureCircleButton
import com.hcjeong.forestix.ui.screens.MeasureShutterBar
import com.hcjeong.forestix.ui.screens.MeasureStatusPanel
import com.hcjeong.forestix.ui.screens.MeasureTopChrome
import com.hcjeong.forestix.ui.screens.MeasureTopStrip
import com.hcjeong.forestix.ui.screens.MeasureMiniMapSlot
import com.hcjeong.forestix.ui.screens.MeasureValuePill
import com.hcjeong.forestix.ui.screens.RawCaptureBadge
import com.hcjeong.forestix.ui.screens.RawCaptureOffNotice
import com.hcjeong.forestix.ui.screens.RawCaptureStatus
import com.hcjeong.forestix.ui.screens.RawCaptureStrings
import com.hcjeong.forestix.ui.screens.ResearchFieldsRow
import com.hcjeong.forestix.ui.screens.TruthFieldNote
import com.hcjeong.forestix.ui.screens.TruthFieldWarning
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
//
// REJECTED is the RED-TIER RESULT stage, not a dead end: it renders the same
// result panel (value + inline reason + the developer truth field) and its
// action row offers Accept alongside Retake and Manual. The tier itself is
// untouched — it still travels with the reading.
private enum class Stage { ANCHOR, WALKING, AIM_BASE, AIM_TOP, COMPUTED, REJECTED }
private enum class CrownStep { NONE, LEFT, RIGHT, TOP, BOTTOM, DONE }

/// Anchor range gate (locked spec, iOS applies the same): the trunk anchor
/// may only be captured from a hit ≤ 4 m away. Field round 8: with no
/// DepthPoint available, a near-horizontal eye-level aim intersected the
/// detected GROUND plane 12–44 m out and the walk-off readout started at
/// that phantom distance.
private const val ANCHOR_MAX_M = 4.0f

/// Hard cap on the raw-capture 5 Hz pose trail (iOS parity): ~2 minutes of
/// walk-off. Without it a session parked on the aim stage grew the manifest
/// without bound.
private const val MAX_POSE_SAMPLES = 600

/// `chainedFromDiameter` = this session was opened AUTOMATICALLY by the
/// cruise tally right after a diameter was accepted (field report F10). It is
/// the one case where "I don't want a height for this tree" needs its own
/// labelled way out, so the trailing rail slot carries a "Skip" button; both
/// Skip and a successful Accept pop back onto the tally, which is already
/// aiming at the next tree. Everywhere else the flag is false and nothing
/// about the screen changes.
@Composable
fun HeightScanScreen(
    nav: NavController,
    treeOverride: Int? = null,
    chainedFromDiameter: Boolean = false,
) {
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
    // REF-COUNTED arm (see ArSessionHub.armRawDepth): in the "Full
    // measurement" DBH→Height chain navigation-compose composes THIS screen
    // before the DBH screen disposes, and the old
    // `onDispose { controller.captureRawDepth = false }` on the outgoing DBH
    // screen therefore disarmed the process-wide controller for the whole
    // height session — every chained height bundle lost its base/top aim
    // frames while still self-checking "pass".
    DisposableEffect(rawCaptureArmed) {
        val token = if (rawCaptureArmed) ArSessionHub.armRawDepth() else null
        onDispose { token?.let { ArSessionHub.releaseRawDepth(it) } }
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
    // The most-recent compute's stored bundle id — minted SYNCHRONOUSLY at
    // compute time (not when the async write finishes), so an Accept tapped
    // immediately still attaches its ground truth. Flipped to
    // operator_accepted on Accept (iOS markAccepted flow).
    var lastRawCaptureId by remember { mutableStateOf<String?>(null) }
    // Visible outcome of the last capture attempt (saved / NOT saved).
    var rawCaptureStatus by remember { mutableStateOf<RawCaptureStatus?>(null) }
    var rawStatusEpoch by remember { mutableStateOf(0) }
    // Reason the last compute failed to record (null = it saved). Accept
    // reads it so a typed truth is never attached to — or silently lost
    // with — a bundle that isn't there.
    var lastCaptureFailure by remember { mutableStateOf<String?>(null) }
    // Why the typed ground truth could not be stored; shown under the truth
    // field, and the field is NOT cleared while it is set.
    var truthSaveFailure by remember { mutableStateOf<String?>(null) }
    // A truth QUEUED against a bundle whose write hasn't finished. It is not
    // in any manifest yet, so the field keeps the text and shows a pending
    // note; the recorder resolves it — folded in (clear the field) or lost
    // with a failed write (hand the number back). Round 1 cleared the field on
    // a merely-queued result, so a write that then failed erased the pairing.
    var truthPending by remember { mutableStateOf<String?>(null) }
    var truthPendingId by remember { mutableStateOf<String?>(null) }
    var truthPendingText by remember { mutableStateOf("") }
    // Storage headroom, re-read on entry and after every capture: below the
    // guard the recorder refuses to write, and the capture's outcome pill
    // says so before a whole plot is lost.
    var storageLow by remember { mutableStateOf(false) }
    LaunchedEffect(rawCaptureArmed, rawStatusEpoch) {
        storageLow = rawCaptureArmed &&
            RawCaptureStore.freeSpaceBytes(context) < RawCaptureStore.MIN_FREE_BYTES
    }
    LaunchedEffect(rawStatusEpoch) {
        // A success fades; a FAILURE stays up until the next attempt.
        if (rawCaptureStatus?.saved == true) {
            delay(4_000)
            rawCaptureStatus = null
        }
    }
    fun postRawStatus(status: RawCaptureStatus) {
        rawCaptureStatus = status
        rawStatusEpoch += 1
    }

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
                // Cap the trail like iOS (~600 samples = 2 min at 5 Hz):
                // unbounded, a session left standing on the aim stage grew
                // the manifest without limit.
                if (poseSamples.size < MAX_POSE_SAMPLES) {
                    poseSamples.add(RawCaptureStore.PoseSample(
                        System.currentTimeMillis() - anchorTimeMs, it))
                }
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
        if (hit == null) { failure = "The camera hasn't got its bearings yet — hold still for a second, then tap + again."; return }
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
        // A missing pose used to abort recording in silence. Say which piece
        // is absent — the operator can retake immediately instead of
        // discovering the hole back at the desk.
        val aPose = anchorPose
        val bPoseRaw = basePose
        val tPose = topPose
        val standing = standingLocked
        if (aPose == null || bPoseRaw == null || tPose == null || standing == null) {
            val missing = listOfNotNull(
                if (aPose == null) "anchor" else null,
                if (bPoseRaw == null) "base" else null,
                if (tPose == null) "top" else null,
                if (standing == null) "standing" else null,
            ).joinToString("/")
            lastCaptureFailure = "no $missing pose (tracking lost)"
            postRawStatus(RawCaptureStatus(
                RawCaptureStrings.notSaved(lastCaptureFailure), false))
            return
        }
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
        // Mint the id NOW so Accept has something to attach truth to even if
        // the write is still running (RawCaptureStore queues edits per id).
        val id = RawCaptureStore.newBundleId()
        lastRawCaptureId = id
        // Aim depth frames are the schema-2 payload; when the recorder was
        // disarmed mid-session (or ARCore gave no depth) they are absent and
        // the bundle silently degrades to schema-1 — flag that, it is exactly
        // the failure the DBH→Height chain used to produce.
        val aimsMissing = baseAimFrame?.rawDepthMm == null || topAimFrame?.rawDepthMm == null
        scope.launch {
            val outcome = RawCaptureStore.recordHeight(
                context = context,
                id = id,
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
            if (outcome.saved) {
                lastCaptureFailure = null
                postRawStatus(
                    if (aimsMissing) {
                        // Bundle is on disk but degraded: the tangent replay
                        // still works, future depth-based height methods
                        // cannot. Warning colour, never a plain "saved".
                        RawCaptureStatus("Raw capture saved · NO aim depth frames", false)
                    } else {
                        RawCaptureStatus(RawCaptureStrings.SAVED, true)
                    },
                )
                // A truth typed mid-write is DURABLE only now that the
                // manifest carries it — this is the only place the field may
                // be cleared for a queued value.
                if (outcome.queuedTruthSaved != null && truthPendingId == id) {
                    if (researchTrueM == truthPendingText) researchTrueM = ""
                    truthPending = null
                    truthPendingId = null
                }
            } else {
                if (lastRawCaptureId == id) lastRawCaptureId = null
                lastCaptureFailure = outcome.error ?: "write failed"
                postRawStatus(RawCaptureStatus(RawCaptureStrings.notSaved(outcome.error), false))
                // A truth queued against this bundle died with it. Losing the
                // pairing is acceptable; losing it INVISIBLY is not — put the
                // number back on screen (or at least name it) and say so.
                outcome.queuedTruthLost?.let { lost ->
                    val text = TruthInput.text(lost)
                    val restore = TruthInput.normalized(researchTrueM).isEmpty()
                    if (restore) researchTrueM = text
                    // Only retire the pending marker if it is THIS capture's —
                    // a later capture may already own it.
                    if (truthPendingId == id) {
                        truthPending = null
                        truthPendingId = null
                    }
                    truthSaveFailure = RawCaptureStrings.truthLost(text, restore)
                }
            }
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
                if (a == null || s == null || anchor == null) { failure = "The camera hasn't got its bearings yet — hold still for a second, then tap + again."; return }
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
                    failure = "The camera hasn't got its bearings yet — hold still for a second, then tap + again."; return
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
                    // So the σ sentence quotes its ± band in the cruiser's
                    // own units, like every other number on this screen.
                    unitSystem = settings.unitSystem,
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
        // Releasing the bundle id too: a discarded compute must not collect
        // the NEXT reading's accept flag or ground truth.
        lastRawCaptureId = null
        lastCaptureFailure = null; truthSaveFailure = null
        anchorPose = null; basePose = null; topPose = null
        anchorHitType = "unknown"; poseSamples.clear()
        baseAimFrame = null; topAimFrame = null; baseAimRgb = null; topAimRgb = null
    }

    // Accept the result: record the entry (photo + GPS + metadata), fold in
    // the crown if measured, fire the continuation, and log the research
    // row. Shared by the result row's Accept and the manual-entry Save
    // (iOS submitManualEntry goes straight to .accepted).
    fun acceptResult(r: HeightResult) {
        // Refused only when the result is not a measurement at all (inverted
        // aims, out-of-range H, degenerate geometry). The TIER is deliberately
        // not part of this — a red fit is committable, with its reason shown
        // inline, and stays red everywhere it is recorded.
        if (!HeightEstimator.canAccept(r)) return
        val activity = context as? android.app.Activity
        // Cruise tally session (v3): the height leg folds into the Tree row
        // the DBH leg created, then the chain returns to the cruise map —
        // no quick-history entry, no continuation sheet.
        val cruise = CruiseCapture.target
        // Snapshot the typed "True H" BEFORE anything async: the truth must
        // survive regardless of whether the bundle write has finished (the
        // store queues edits against the synchronously-minted id) — the old
        // code wrote truth only when the write had already completed, so a
        // fast Accept dropped it and the field was cleared anyway.
        val truthTextAtAccept = researchTrueM
        val rawTrue = TruthInput.parsePositive(truthTextAtAccept)
        truthSaveFailure = null
        scope.launch {
            // Stamp operator_accepted on the bundle this Accept confirms
            // (safe while the writer is still running — the store parks it).
            val rid = lastRawCaptureId
            if (rid != null) RawCaptureStore.markAccepted(context, rid)
            // Attach the typed ground truth. The input is cleared ONLY once
            // the value is durable (in the manifest, or queued for the
            // in-flight writer); on any failure the text stays put.
            if (settings.developerMode && TruthInput.normalized(truthTextAtAccept).isNotEmpty()) {
                when {
                    rawTrue == null -> truthSaveFailure = RawCaptureStrings.TRUTH_NOT_A_NUMBER
                    // Not recording: the value still went to the research CSV.
                    !rawCaptureArmed -> if (researchTrueM == truthTextAtAccept) researchTrueM = ""
                    // The capture itself failed — keep the typed value on
                    // screen rather than attach it to a bundle that isn't there.
                    lastCaptureFailure != null -> truthSaveFailure =
                        RawCaptureStrings.truthCaptureFailed(lastCaptureFailure)
                    rid == null -> if (researchTrueM == truthTextAtAccept) researchTrueM = ""
                    else -> {
                        // Claim the pending slot BEFORE the store call: the
                        // recorder's own completion handler reads it to decide
                        // whether the queued value landed.
                        truthPendingId = rid
                        truthPendingText = truthTextAtAccept
                        when (RawCaptureStore.setTruth(context, rid, rawTrue)) {
                            // Durable — the manifest already exists.
                            RawCaptureStore.TruthWrite.SAVED -> {
                                truthPending = null
                                truthPendingId = null
                                if (researchTrueM == truthTextAtAccept) researchTrueM = ""
                            }
                            // QUEUED is NOT durable: the value lives only in
                            // the store's pending queue until the in-flight
                            // write folds it in, so the text stays put.
                            // (If the writer already resolved it, truthPendingId
                            // is null again and there is nothing to announce.)
                            RawCaptureStore.TruthWrite.QUEUED ->
                                if (truthPendingId == rid) {
                                    truthPending = RawCaptureStrings.TRUTH_PENDING
                                }
                            RawCaptureStore.TruthWrite.FAILED -> {
                                truthPending = null
                                truthPendingId = null
                                truthSaveFailure = RawCaptureStrings.TRUTH_WRITE_FAILED
                            }
                        }
                    }
                }
            }
            lastRawCaptureId = null
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
                // The height leg must actually land on the tree row. It used
                // to be a bare `runCatching {}` followed by an unconditional
                // pop, so a failed fold looked identical to a success and the
                // tree peek quietly kept showing DBH alone. Keep the result on
                // screen and name the failure instead; Accept can be retried.
                val folded = runCatching {
                    CruiseCapture.recordHeight(env, r, photoPath = photo, fix = fix)
                }.getOrDefault(false)
                if (folded) {
                    // One pop, two destinations by construction: in the
                    // diameter → height chain (F10) the tally is directly
                    // underneath, so this drops back onto it already aiming
                    // at the next tree; from the tree peek / heights sheet
                    // Height was pushed from the map, so this returns there
                    // in cruise mode with the new pin and an updated tally.
                    nav.popBackStack()
                } else {
                    // Name the tree this SESSION is measuring, not
                    // `CruiseCapture.target` — in the chain the tally has
                    // already advanced its target to the next number.
                    failure = "Height NOT saved to Tree $pendingTree — " +
                        "the tree row couldn't be updated. Tap Accept again."
                }
            } else {
                env.history.append(
                    QuickMeasureEntry(
                        kind = MeasureKind.HEIGHT, value = r.heightM.toDouble(),
                        // σ null = no uncertainty was derivable. It stays
                        // null in the history rather than becoming a 0 that
                        // reads as perfect precision. (Accept already refuses
                        // tangent fits with no σ — see HeightEstimator.canAccept.)
                        sigma = r.sigmaHm?.toDouble(), confidenceRaw = r.confidence.raw,
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
                // Empty cell when σ_H was not derivable — the research CSV
                // must never carry a manufactured 0 in the σ column.
                "sigma" to (r.sigmaHm?.let { String.format(Locale.US, "%.2f", it) } ?: ""),
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
            rawTrue?.let { t ->
                fields["true_value"] = String.format(Locale.US, "%.2f", t)
                fields["error"] = String.format(Locale.US, "%.2f", r.heightM - t)
            }
            ResearchLog.record(context, fields)
            // The field is NOT cleared here any more — the accept coroutine
            // above clears it only once the truth is durably applied.
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

        // Plot mini-map — top-right, same row as the GPS badge: the active
        // cruise plot (ring + YOU + measured trees) or the quick sampling
        // ring (ring + YOU). Hidden with the rest of the 2D chrome during
        // the Accept snapshot blackout.
        // F11 — the card is TAPPABLE in cruise: it re-opens plot setup so
        // the radius / centre stay editable after the first placement. The
        // session's project/plot are fixed for this screen's lifetime, so
        // one remember is safe.
        val miniMapUp = scanPlotMiniMapVisible()
        val editPlotTarget = remember { CruiseCapture.target }
        if (!hidingChromeForCapture) ScanPlotMiniMap(
            onEditPlot = editPlotTarget?.let { c ->
                {
                    nav.navigate(
                        CruiseRoutes.editPlot(c.projectId.toString(), c.plotId.toString()))
                }
            },
        )

        // Same top strip as the Diameter scan — one row, inset past the
        // system status bar, with the mini-map's width reserved on the
        // trailing edge. Height has no centre title of its own; the slot
        // exists so both scans share one geometry.
        if (!hidingChromeForCapture) {
            MeasureTopStrip(
                reserveTrailing = if (miniMapUp) MeasureMiniMapSlot else 0.dp,
                leading = { GPSAccuracyBadge() },
            )
        }

        // Raw-capture recording state: the last attempt's outcome (saved /
        // NOT saved), plus a loud NOT RECORDING warning when Settings asked
        // for recording and the recorder is not actually armed. There is no
        // persistent REC pill — `armed` reads the hub's REAL ref-counted
        // arm (never the Settings wish) so that mismatch is still caught.
        if (!hidingChromeForCapture) {
            RawCaptureBadge(
                armed = ArSessionHub.rawDepthArmed,
                requested = rawCaptureArmed,
                storageLow = storageLow,
                status = rawCaptureStatus,
            )
        }

        // The developer internals HUD is NOT drawn here any more — same
        // reason as DBH: in the field it covered the AR view and collided
        // with the mini-map. The DevHud component is untouched
        // (ui/screens/DevHud.kt) and so is every recording path: the
        // research CSV row (ResearchLog.record, in the accept path above)
        // and the raw-capture bundle are written from the measurement
        // path, not from this overlay.

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
            // Right-hand rail slot. Retake wins whenever there is something
            // to restart; before that — and only in the cruise diameter →
            // height chain (field report F10) — the slot carries "Skip", the
            // labelled way to say "no height for this tree" and drop back to
            // the tally on the next tree. Elsewhere the slot is empty exactly
            // as before.
            right = if (crownActive || stage in listOf(
                    Stage.WALKING, Stage.AIM_BASE, Stage.AIM_TOP)
            ) {
                {
                    MeasureCircleButton(icon = Icons.Filled.Replay, caption = "Retake") {
                        resetAll()
                    }
                }
            } else if (chainedFromDiameter) {
                {
                    MeasureCircleButton(icon = Icons.Filled.SkipNext, caption = "Skip") {
                        nav.popBackStack()
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
                            "Start distance " + MeasurementFormatter.distance(
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
                    "Move closer — stand within 4 m of the trunk, then tap +."
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
                        onValueChange = { manualText = TruthInput.sanitize(it) },
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
                    // The field is typed in the ACTIVE unit system: under
                    // imperial the prompt says feet, so the number must be
                    // converted before it lands in heightM (and before the
                    // plausibility gate, which is a METRE threshold).
                    val typed = TruthInput.parse(manualText)?.toFloat()
                    val typedM = typed?.let {
                        if (settings.unitSystem == UnitSystem.METRIC) it
                        else Units.feetToMeters(it.toDouble()).toFloat()
                    }
                    // SAME floor as the AR path (HeightEstimator.MIN_H_M,
                    // 0.3 m). It was a separate hard-coded 1.3 m, which left
                    // the absurd split where a 0.8 m seedling could be
                    // MEASURED but not TYPED. Inclusive, exactly like the
                    // estimator's MIN_H_M..MAX_H_M window.
                    val manualOk = (typedM ?: 0f) >= HeightEstimator.MIN_H_M
                    ForestixProminentButton(
                        "Save",
                        enabled = manualOk,
                    ) {
                        val m = typedM
                        if (m != null && m >= HeightEstimator.MIN_H_M) {
                            val r = HeightResult(
                                heightM = m, dHm = 0f, alphaTopRad = 0f, alphaBaseRad = 0f,
                                // A typed height has NO propagated uncertainty —
                                // there is no geometry to propagate. Recording 0
                                // would claim perfect precision and poison the
                                // sigma column the accuracy work reads, so leave
                                // it unset (iOS stores nil here too).
                                sigmaHm = null, confidence = ConfidenceTier.YELLOW,
                                method = HeightMethod.MANUAL_ENTRY, rejectionReason = null,
                            )
                            result = r
                            failure = null
                            manualOpen = false
                            // A typed height is NOT any recorded compute's
                            // reading — release the bundle id so the accept
                            // can't mark an unrelated capture as accepted.
                            lastRawCaptureId = null
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
        // σ and the tier stay recorded internally. The tier no longer gates
        // Accept (a red fit is a fit); what a red fit DOES get is its reason
        // rendered inline under the number, so the cruiser reads it before
        // committing rather than only catching it in the transient banner.
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
                    // WHY the fit is poor, INLINE — beside the number, not
                    // only in the top banner. A red fit is acceptable now
                    // (the action row offers Accept at every tier), so the
                    // cruiser has to be able to read the reason at the moment
                    // of deciding; the banner carries the stage prompt and is
                    // the wrong place to park something that changes what the
                    // reading means. Null on green/yellow.
                    r.rejectionReason?.let { reason ->
                        Text(
                            reason,
                            style = type.caption,
                            color = Forestix.colors.confidenceBad,
                        )
                    }
                    if (settings.developerMode) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            // Bench diagnostic — the raw captured inputs that
                            // fed the §7.2 formula. It used to print on every
                            // result panel: three formula variables with no
                            // legend, on the screen where a cruiser decides
                            // whether to keep a height. It is an author's
                            // instrument, so it lives with the author's other
                            // instruments, behind Developer mode.
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
                            ResearchFieldsRow(
                                targetValue = settings.researchTreeId,
                                onTargetChange = { env.settings.setResearchTreeId(it.trim()) },
                                targetPlaceholder = "T1",
                                trueLabel = "True H (m)",
                                trueValue = researchTrueM,
                                // ',' is NORMALISED to '.', never deleted — the
                                // old digit filter turned "12,5" into "125".
                                onTrueChange = { researchTrueM = TruthInput.sanitize(it) },
                                truePlaceholder = "clinometer",
                            )
                            // Live warning under the truth field: a failed save,
                            // unparseable text, or an implausible value.
                            (truthSaveFailure
                                ?: TruthInput.fieldWarning(researchTrueM, isHeight = true))
                                ?.let { w -> TruthFieldWarning(w) }
                            // Queued-but-not-yet-durable truth: the field was
                            // deliberately NOT cleared, so say why.
                            if (truthSaveFailure == null) {
                                truthPending?.let { TruthFieldNote(it) }
                            }
                            // Developer mode on but the recorder off: nothing
                            // is being kept for the corpus.
                            if (!settings.rawCaptureEnabled) RawCaptureOffNotice()
                        }
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
                                enabled = result?.let { HeightEstimator.canAccept(it) } == true,
                            ) {
                                result?.let { acceptResult(it) }
                            }
                        }
                    }
                }
                Stage.REJECTED -> {
                    // RED-TIER RESULT — and Accept lives here too. A red fit
                    // is still a fit: σ_H and the aim angles say it is poor,
                    // the reason says why in the panel above, and the cruiser
                    // decides. This row used to offer only Retake and Manual,
                    // which meant a legitimately-measured tree could not be
                    // kept at all — and, in developer mode, that a typed
                    // ground truth could never be committed for exactly the
                    // hard cases the algorithm comparison needs. The tier
                    // still travels with the reading (Tree.heightConfidence,
                    // research CSV, raw-capture manifest all keep `red`).
                    //
                    // Accept goes inert only when the result is not a
                    // measurement (inverted aims, out-of-range H, degenerate
                    // geometry) — see HeightEstimator.canAccept.
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ForestixWhiteButton("Retake", modifier = Modifier.weight(1f)) { resetAll() }
                        ForestixWhiteButton("Manual", modifier = Modifier.weight(1f)) {
                            resetAll(); manualOpen = true; manualText = ""
                        }
                        ForestixProminentButton(
                            "Accept",
                            modifier = Modifier.weight(1f),
                            enabled = result?.let { HeightEstimator.canAccept(it) } == true,
                        ) {
                            result?.let { acceptResult(it) }
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

