// Tree Height — Android, now using the SAME VIO walk-off tangent method as
// iOS (HeightEstimator, spec §7.2): anchor the trunk, walk back (live d_h),
// then capture the top + base aim angles; H = d_h·(tanα_top − tanα_base)
// with the identical guard rails, σ_H, and green/yellow/red tiers. α comes
// from the ARCore camera-forward elevation (the Android analogue of the
// iOS IMU pitch). Crown is folded in afterwards, reusing the measured d_h.

package com.hcjeong.forestix.ui.screens.height

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
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
import com.hcjeong.forestix.ar.OpticalAxisProbe
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
import com.hcjeong.forestix.ui.screens.GpsFixChip
import com.hcjeong.forestix.ui.screens.MeasureBackButton
import com.hcjeong.forestix.ui.screens.MeasureCircleButton
import com.hcjeong.forestix.ui.screens.MeasureShutterBar
import com.hcjeong.forestix.ui.screens.MeasureStatusPanel
import com.hcjeong.forestix.ui.screens.MeasureTopChrome
import com.hcjeong.forestix.ui.screens.MeasureTopStrip
import com.hcjeong.forestix.ui.screens.MeasureMiniMapSlot
import com.hcjeong.forestix.ui.screens.MeasureValuePill
import com.hcjeong.forestix.ui.screens.PlotPinCentreCard
import com.hcjeong.forestix.ui.screens.rememberPlotPinCentreOffer
import com.hcjeong.forestix.ui.screens.RawCaptureBadge
import com.hcjeong.forestix.ui.screens.RawCaptureOffNotice
import com.hcjeong.forestix.ui.screens.RawCaptureStatus
import com.hcjeong.forestix.ui.screens.RawCaptureStrings
import com.hcjeong.forestix.ui.screens.ResearchFieldsRow
import com.hcjeong.forestix.ui.screens.TruthFieldNote
import com.hcjeong.forestix.ui.screens.TruthFieldWarning
import com.hcjeong.forestix.ui.screens.ScanPlotMiniMap
import com.hcjeong.forestix.ui.screens.scanPlotMiniMapVisible
import com.hcjeong.forestix.ui.screens.scanPanelTextFieldColors
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixProminentButton
import com.hcjeong.forestix.ui.theme.ForestixWhiteButton
import com.hcjeong.forestix.ui.screens.cruise.freshFixOrNull
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

/// Radius of the anchor / top / base aim spheres, at the scaling reference
/// distance (ArSessionHub's 2.5 m).
///
/// FIELD REPORT 2 — was 0.08. Together with the sub-linear distance scaling
/// this makes the far-field marker roughly a third of the on-screen area it
/// used to cover, which is the difference between pointing AT a treetop and
/// painting over it. Near-field it is a quarter smaller and still an easy
/// target to see against foliage. iOS value 1:1.
private const val AIM_MARKER_RADIUS_M = 0.06f

/// How far the phone may move between the base sighting and the top
/// sighting before the reading is worth a warning.
///
/// The §7.2 formula takes both angles from ONE point. 0.25 m clears
/// ordinary hand and wrist movement while tilting up — measured against a
/// held phone, that is a few centimetres — and catches the two cases that
/// actually bias the number: raising the phone overhead to clear a crown,
/// and taking a step. It WARNS rather than refuses: a cruiser who has
/// already walked the off-distance should be told the reading is soft, not
/// sent back to the start by an arm's-length wobble.
private const val AIM_DRIFT_WARN_M = 0.25f

/// Owner sentinel for a truth kept on screen for a measurement that produced
/// NO usable bundle (the capture itself failed, so its id was dropped). Never
/// a real bundle id, so the cross-tree guard in `acceptResult` still fires.
/// iOS calls the same sentinel `noBundleOwner`.
private const val TRUTH_NO_BUNDLE_OWNER = "no-bundle"

/// Walk-off integrity copy (field round 9 — the vanishing anchor). Byte
/// identical to the iOS HeightScanScreen constants of the same names.
///
/// The cruiser's report was "the sphere disappears and the walked distance
/// goes back to zero". Both were the SAME silence: ARCore's camera pose is
/// undefined while tracking is PAUSED, the pose readers handed that out as a
/// real position anyway (now fixed in ArController), and the walk panel
/// recomputed both distances from it. A dropout must be announced while it
/// is happening AND remembered afterwards — a distance that restarts without
/// saying so is the one thing this screen must never do.
private const val TRACKING_LOST_NOW =
    "Tracking lost — hold still until the camera picks the scene back up. The distance is frozen, not restarted."
private const val TRACKING_DROPPED_DURING_WALK =
    "Tracking dropped during the walk-off, so the distance to the trunk may have shifted. Retake for a firm number."
private const val ANCHOR_LOST =
    "The trunk anchor is gone — the camera couldn't hold it. Tap Retake and anchor the trunk again."
/// ANDROID-ONLY, deliberately — the three constants above are byte-identical
/// to their iOS siblings and this one has none, which is worth saying out loud
/// rather than leaving to be discovered as drift. ARCore's
/// `session.createAnchor` can fail (session not running, resource exhaustion),
/// so the refusal is reachable here; the iOS `addWorldAnchor` always returns an
/// identifier on a real device, and on the non-ARKit stub it returns nil with
/// the raw hit point still standing in, so there is no branch to write the
/// sentence into. If iOS ever gains one, this exact string is the one to use.
private const val ANCHOR_NOT_PLACED =
    "Couldn't pin the trunk — hold still for a second, then tap + again."

/// The refusal for "the tap was legitimate, but ARCore has no world pose to
/// serve it from" — every pose reader here returns null off a non-TRACKING
/// frame. It was repeated verbatim at four call sites, which is how a string
/// that has to stay byte-identical across two platforms drifts; iOS now holds
/// it once as `HeightScanViewModel.cameraNotReadyText` and uses it at the
/// matching taps (anchor, aim base, aim top).
private const val CAMERA_NOT_READY =
    "The camera hasn't got its bearings yet — hold still for a second, then tap + again."

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
    val pendingLock = remember { PendingTreeNumber.consume() }
    var pendingTree by remember {
        mutableStateOf(
            treeOverride ?: pendingLock?.number ?: env.history.suggestedNextTreeNumber,
        )
    }
    // Developer-mode research capture: the clinometer-measured true height, AS
    // TYPED. The unit is `truthUnit` below, never assumed — the field used to
    // be named (and read) as metres whatever the cruiser was working in.
    var researchTrueText by remember { mutableStateOf("") }
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
    /// How far the camera moved between the base sighting and the top
    /// sighting. Null until a height has been computed. See the AIM_TOP
    /// handler for why this is a measurement fact and not a nicety.
    var aimDriftM by remember { mutableStateOf<Float?>(null) }
    var dhLive by remember { mutableStateOf(0f) }
    // Walk-panel triplet (locked spec): camera→anchor horizontal distance
    // at the MOMENT of anchoring, and the camera's displacement since then
    // (starts at 0.00 — the old single readout confusingly opened at the
    // full anchor distance). Total distance = dhLive (the d_h the math uses).
    var anchorInitialDistM by remember { mutableStateOf<Float?>(null) }
    // DELIBERATELY A RAW Vec3, not an ARCore `Anchor`, unlike the trunk. The
    // argument for anchoring the trunk is that its position enters d_h and d_h
    // is the whole scale of H, so every world-frame correction the walk
    // collects lands in the measurement. This point enters NOTHING: it feeds
    // the "Walked back" readout and only that. A relocalization will still
    // shift it, and the readout will still be off by the correction — so it is
    // a DISPLAY number, and it is labelled as one here rather than being made
    // to look like a measurement. Anchoring it would mean a second anchor with
    // its own detach path on every exit, retake and re-anchor, i.e. another
    // thing that can leak, bought for a line of chrome. The distance the
    // cruiser actually acts on is "Total distance", measured to the anchored
    // trunk. Same call, same wording, on iOS (`cameraPositionAtAnchor`).
    var standingAtAnchor by remember { mutableStateOf<Vec3?>(null) }
    var walkedLive by remember { mutableStateOf(0f) }
    // Live anchor-aim validity (gated hit available?) — drives the locked
    // "Move closer" status line and keeps the anchor "+" inert.
    var anchorAimOk by remember { mutableStateOf(false) }
    // Walk-off integrity. `trackingLive` is the CURRENT camera state (drives
    // the live "hold still" line); `trackingDropped` latches for the whole
    // measurement, because a dropout that has since recovered still means the
    // camera did not see part of the walk that set d_h. `anchorLost` is the
    // fatal one: ARCore stopped the trunk anchor, so there is nothing left to
    // measure d_h against and the capture refuses.
    var trackingLive by remember { mutableStateOf(true) }
    var trackingDropped by remember { mutableStateOf(false) }
    var anchorLost by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<HeightResult?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }

    // Manual height entry (typed metres) — iOS .manualEntry state.
    var manualOpen by remember { mutableStateOf(false) }
    var manualText by remember { mutableStateOf("") }
    // Accept-snapshot chrome blackout: while true, every 2D panel/button is
    // hidden so the captured JPEG shows only the AR feed + measurement
    // overlays (captured buttons read as real buttons in the photo viewer).
    var hidingChromeForCapture by remember { mutableStateOf(false) }
    // FIELD REPORT 14 × 17 — a cruise plot is being tallied but no AR anchor
    // marks its centre, so `PlotOverlay.SUBDUED` has nothing to draw and the
    // cruiser is looking at a bare camera feed with a plot open. Non-null
    // exactly while that is true; see rememberPlotPinCentreOffer.
    val pinCentreOffer = rememberPlotPinCentreOffer(controller)
    // Scan metadata (species / damage / note) attached on Accept — iOS
    // ScanMetadataSheet(kind: .height); no stem position for heights.
    // Seeded from the chooser's species control when it was used, so the
    // details chip already reads the species the cruiser picked at the tree.
    var metaSpecies by remember { mutableStateOf(pendingLock?.speciesCode) }
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
    // The trunk anchor is this screen's, and it outlives the composition
    // otherwise (the hub is app-scoped). Release it on the way out so an
    // abandoned walk-off can't hand its anchor to the next tree's session.
    DisposableEffect(Unit) {
        onDispose { ArSessionHub.clearHeightAnchor() }
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
    // The bundle a kept-on-screen truth was typed FOR (iOS truthOwnerBundleID).
    // Every path above deliberately KEEPS the text when the value could not be
    // attached, so what is in the field may belong to an EARLIER capture. In
    // the diameter → height chain this screen is re-entered tree after tree,
    // and without this mark the next tree's Accept re-parsed that text and
    // exported it as ITS true_value: one tree's pole reading, with an error
    // computed against another tree's height. Cleared the moment the cruiser
    // edits the field.
    var truthOwnerBundleId by remember { mutableStateOf<String?>(null) }
    // The unit THIS typed truth is in. It opens in the cruiser's active system
    // — an imperial operator gets feet, not a metre field they type feet into
    // — and the square button beside the field switches it for this entry.
    // Keyed on the active system so changing the project's units re-defaults
    // it; a per-entry toggle survives ordinary recomposition.
    var truthUnit by remember(settings.unitSystem) {
        mutableStateOf(TruthInput.defaultUnit(
            TruthInput.Quantity.HEIGHT,
            imperial = settings.unitSystem != UnitSystem.METRIC,
        ))
    }
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
    //
    // It runs through the AIM stages too, not just WALKING. The trunk is
    // pinned with a REAL ARCore anchor now (ArSessionHub.placeHeightAnchor),
    // and ARCore keeps correcting that anchor's pose while the cruiser lines
    // up the base and then the top — `anchorPt` is what BOTH aim taps compute
    // d_h from, so it has to keep following the anchor, not stop at whatever
    // the pose was when the walking stage ended.
    LaunchedEffect(stage) {
        while (stage == Stage.WALKING || stage == Stage.AIM_BASE || stage == Stage.AIM_TOP) {
            // Drift-corrected trunk position. Null = ARCore STOPPED the
            // anchor; latch it so the next tap refuses instead of the sphere
            // quietly disappearing and the math carrying on to a ghost.
            val anchorNow = ArSessionHub.heightAnchorWorld()
            if (anchorNow == null) {
                anchorLost = true
                // THE HONEST VANISH. Dropping the point takes the red sphere
                // out of markers() with it. Keeping the last `anchorPt` — as
                // this did — left a marker drawn on the bark at a pose nothing
                // is correcting any more, while the status line one line of
                // chrome away said the anchor was gone: the screen
                // contradicting itself at the moment it most needs not to. A
                // marker that disappears beats one that stays put and lies,
                // which is the entire argument for anchoring the trunk.
                //
                // The readouts below are not recomputed once it is null, so
                // "Total distance" freezes at its last real value rather than
                // restarting — the same hold a tracking dropout gets.
                anchorPt = null
            } else {
                anchorPt = anchorNow
            }
            // With no camera pose both readouts below return null and HOLD
            // their last good value rather than recomputing from a pose that
            // isn't real. Say it while it happens, and remember it after.
            //
            // NO BANNER HERE. `MeasureTopChrome.instruction` is already showing
            // TRACKING_LOST_NOW for exactly this state, and posting
            // TRACKING_DROPPED_DURING_WALK into the dismissible failure slot
            // put two different instructions on screen at once — one saying
            // hold still, the other saying retake. The dropout is remembered
            // and stated where it can be acted on: on the RESULT panel, when
            // the cruiser decides whether to keep the number, which is what
            // iOS does and the only place the string appears there.
            val tracking = controller.trackingOk()
            trackingLive = tracking
            if (!tracking && !trackingDropped) trackingDropped = true
            if (stage == Stage.WALKING) {
                anchorPt?.let { a -> controller.horizontalDistanceTo(a)?.let { dhLive = it } }
                standingAtAnchor?.let { s0 -> controller.horizontalDistanceTo(s0)?.let { walkedLive = it } }
            }
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

    // AIM-OFFSET INSTRUMENT (developer mode only) — where the camera optical
    // axis actually projects onto the view, against the view centre the aim
    // ring is drawn at. See ArController.opticalAxisProbe for the geometry
    // and for why this measures rather than corrects. The value only moves
    // when the display geometry does, so a quarter-second poll is ample; when
    // developer mode is off the loop never starts and nothing is computed.
    var axisProbe by remember { mutableStateOf<OpticalAxisProbe?>(null) }
    LaunchedEffect(settings.developerMode) {
        if (!settings.developerMode) {
            axisProbe = null
            return@LaunchedEffect
        }
        while (true) {
            axisProbe = controller.opticalAxisProbe()
            delay(250)
        }
    }

    fun markers(): List<ArSceneMarker> {
        // scalesWithDistance keeps every sphere readable from across the
        // walk-off — it grows with camera distance, but sub-linearly, so a
        // marker seen from 20 m no longer covers what it is marking
        // (FIELD REPORT 2, ArSessionHub.markerDistanceScale).
        val out = mutableListOf<ArSceneMarker>()
        anchorPt?.let { out.add(ArSceneMarker(it, MarkerShape.Sphere(AIM_MARKER_RADIUS_M), floatArrayOf(1f, 0.30f, 0.30f, 1f), scalesWithDistance = true)) }
        // Tree top (yellow) + base (green) spheres, each on the aim ray it
        // was sighted along (see the AIM_BASE / AIM_TOP handlers).
        topMarker?.let { out.add(ArSceneMarker(it, MarkerShape.Sphere(AIM_MARKER_RADIUS_M), floatArrayOf(1f, 0.85f, 0.15f, 1f), scalesWithDistance = true)) }
        baseMarker?.let { out.add(ArSceneMarker(it, MarkerShape.Sphere(AIM_MARKER_RADIUS_M), floatArrayOf(0.25f, 0.85f, 0.35f, 1f), scalesWithDistance = true)) }
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
        if (hit == null) { failure = CAMERA_NOT_READY; return }
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
            // Same freshness gate as the accept path — a bundle must not
            // claim a position the app would refuse to draw.
            gps = freshFixOrNull(
                com.hcjeong.forestix.positioning.LocationService.lastGlobalFix,
                System.currentTimeMillis()),
        )
        val samples = poseSamples.toList()
        val hitType = anchorHitType
        // Non-null for the whole of WALKING onward — the anchor stage refuses
        // when no camera pose is available, so there is no reading whose start
        // distance was never measured (see the ANCHOR branch of captureHeight).
        val dist = anchorInitialDistM ?: 0f
        // Snapshot the walk-off integrity latch with the rest of the payload,
        // so a dropout that arrives while the writer runs cannot retro-flag a
        // bundle whose walk was clean.
        val dropped = trackingDropped
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
                // Latched for the whole walk-off — the same fact the result
                // panel warns about and the research CSV row carries.
                trackingDropped = dropped,
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
                    if (researchTrueText == truthPendingText) researchTrueText = ""
                    truthPending = null
                    truthPendingId = null
                    // Durable and off the screen: it owns nothing now.
                    truthOwnerBundleId = null
                }
            } else {
                if (lastRawCaptureId == id) lastRawCaptureId = null
                lastCaptureFailure = outcome.error ?: "write failed"
                postRawStatus(RawCaptureStatus(RawCaptureStrings.notSaved(outcome.error), false))
                // A truth queued against this bundle died with it. Losing the
                // pairing is acceptable; losing it INVISIBLY is not — put the
                // number back on screen (or at least name it) and say so.
                outcome.queuedTruthLost?.let { lost ->
                    // Rendered in the unit it was TYPED in, not the metric base
                    // it was stored as — handing an imperial cruiser back a
                    // silently converted number is the same defect in reverse.
                    val text = TruthInput.text(lost.value, lost.unit)
                    val restore = TruthInput.normalized(researchTrueText).isEmpty()
                    if (restore) {
                        researchTrueText = text
                        truthUnit = lost.unit
                    }
                    // Only retire the pending marker if it is THIS capture's —
                    // a later capture may already own it.
                    val stillOurs = truthPendingId == id
                    if (stillOurs) {
                        truthPending = null
                        truthPendingId = null
                    }
                    // The text now on screen belongs to THIS dead capture —
                    // either we just put it back, or the cruiser never touched
                    // it while the write ran. Mark it, or the next tree's Accept
                    // exports a pole number never taken on that stem. When the
                    // cruiser HAS typed something newer, that text is their
                    // intent for the capture in front of them and stays unowned.
                    if (restore || stillOurs) truthOwnerBundleId = id
                    // The number is named WITH its unit — a bare "12.5" in a
                    // failure message is the same ambiguity this item exists
                    // to remove.
                    truthSaveFailure = RawCaptureStrings.truthLost(
                        "$text ${lost.unit.raw}", restore)
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
                // BOTH walk-panel reference values come from the camera pose,
                // and they are read BEFORE the anchor is placed so a refusal
                // leaves nothing behind to clean up.
                //
                // `?: 0f` used to stand here and invented a start distance of
                // zero — the one fallback pattern this round exists to remove.
                // It is nearly unreachable (`screenCenterAnchorHit` has just
                // succeeded, which needs a frame), but `frame` is @Volatile and
                // replaced by the render thread, and a measurement that is
                // WRONG is worse than one that refuses. iOS refuses at the same
                // point for the same reason (`anchorHereNow` requires
                // `currentCameraTranslation()`), with this same sentence — held
                // there as `HeightScanViewModel.cameraNotReadyText`. That claim
                // used to be aspirational: iOS folded the missing pose in with
                // the INERT cases above and returned in silence, so the cruiser
                // tapped "+" over and over with the eye-level prompt still on
                // screen. Same order on both platforms now: missing hit is
                // inert, missing pose speaks.
                val standing = controller.currentCameraPosition()
                val d0 = controller.horizontalDistanceTo(hit)
                if (standing == null || d0 == null) {
                    failure = CAMERA_NOT_READY
                    return
                }
                // Pin the trunk with a REAL ARCore anchor rather than keeping
                // the hit's raw world point (see ArSessionHub.heightAnchor for
                // the full argument): ARCore re-fits the world frame all the
                // way through a 10–30 m walk-off, and a frozen Vec3 collects
                // every one of those corrections straight into d_h. Refusing
                // when the anchor can't be created is the honest branch — the
                // fallback would be exactly the raw point we are replacing.
                if (!ArSessionHub.placeHeightAnchor(hit)) { failure = ANCHOR_NOT_PLACED; return }
                failure = null
                anchorPt = hit
                anchorLost = false
                trackingDropped = false
                trackingLive = true
                standingAtAnchor = standing
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
                // The anchor's CURRENT pose, not the one remembered from the
                // anchoring frame — it has been drift-corrected for the whole
                // walk. Null means ARCore stopped it: there is no trunk to
                // measure d_h to, so this refuses rather than reusing a stale
                // point that would still produce a confident-looking number.
                val anchor = ArSessionHub.heightAnchorWorld()
                if (anchor == null) { anchorLost = true; failure = ANCHOR_LOST; return }
                anchorPt = anchor
                if (a == null || s == null) { failure = CAMERA_NOT_READY; return }
                // Lock the standing pose on the first aim; both angles must
                // come from the same spot (the §7.2 formula assumes it).
                failure = null; alphaBase = a; standingLocked = s
                if (rawCaptureArmed) {
                    basePose = controller.currentCameraPose()
                    val (fr, rgb) = captureAim()
                    baseAimFrame = fr; baseAimRgb = rgb
                }
                val dh = kotlin.math.sqrt((s.x - anchor.x) * (s.x - anchor.x) + (s.z - anchor.z) * (s.z - anchor.z))
                // ON THE AIM RAY, not on the anchor's vertical axis
                // (FIELD REPORT 3). The marker is a receipt for "this is
                // the point you sighted", so it has to be built from the
                // SAME camera pose the angle came from. Placing it at
                // (anchor.x, standing.y + dh·tanα, anchor.z) used the
                // locked standing HEIGHT while α came from the live camera,
                // so any change in how the phone was held between the two —
                // and raising it to find a treetop is the normal way to
                // sight one — dropped the sphere below the crosshair.
                //
                // This is the same construction crown capture already uses,
                // and for the same reason: no fresh hit-test, because at
                // 10–30 m those fall back to planes and land nowhere near
                // the aim.
                baseMarker = controller.forwardPointAtHorizontalDistance(dh)
                    ?: Vec3(anchor.x, s.y + dh * kotlin.math.tan(a), anchor.z)
                stage = Stage.AIM_TOP
            }
            Stage.AIM_TOP -> {
                val aTop = controller.cameraForwardElevationRad()
                // Same rule as the base aim: the live anchor pose or nothing.
                val anchor = ArSessionHub.heightAnchorWorld()
                if (anchor == null) { anchorLost = true; failure = ANCHOR_LOST; return }
                anchorPt = anchor
                val standing = standingLocked; val aBase = alphaBase
                if (aTop == null || standing == null || aBase == null) {
                    failure = CAMERA_NOT_READY; return
                }
                failure = null; alphaTop = aTop
                // Sampled HERE, immediately after the angle and BEFORE the
                // raw-capture block. captureAim() acquires a depth frame and
                // writes a JPEG, and ArController.frame is replaced by the
                // render thread while that runs — reading the position after
                // it attributed the cruiser's hand movement during a disk
                // round-trip to drift that never touched the angle, and only
                // in developer mode, which is exactly the configuration the
                // study data is collected in.
                val topPosNow = controller.currentCameraPosition()
                if (rawCaptureArmed) {
                    topPose = controller.currentCameraPose()
                    val (fr, rgb) = captureAim()
                    topAimFrame = fr; topAimRgb = rgb
                }
                val dh = kotlin.math.sqrt((standing.x - anchor.x) * (standing.x - anchor.x) + (standing.z - anchor.z) * (standing.z - anchor.z))
                // On the aim ray — see the AIM_BASE handler for why.
                topMarker = controller.forwardPointAtHorizontalDistance(dh)
                    ?: Vec3(anchor.x, standing.y + dh * kotlin.math.tan(aTop), anchor.z)
                // HOW FAR THE INSTRUMENT MOVED between the two sightings.
                //
                // The §7.2 tangent formula assumes both angles were taken
                // from ONE point: H = d_h(tan α_top − tan α_base). The
                // screen locks that point at the base aim and reads α_top
                // from wherever the camera is now, so a cruiser who raises
                // or steps while finding the treetop measures a tree that
                // does not exist. The error is roughly the vertical
                // movement one-for-one, plus the horizontal movement scaled
                // by (tree height / d_h) — at 10 m from a 20 m tree, a 20 cm
                // step is a 40 cm error, which is inside nothing this app
                // claims. It is reported, not swallowed: the cruiser can
                // retake, and a recorded run carries the number.
                val drift = topPosNow?.let { now ->
                    val dx = now.x - standing.x
                    val dy = now.y - standing.y
                    val dz = now.z - standing.z
                    kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
                }
                aimDriftM = drift
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
        aimDriftM = null
        dhLive = 0f; anchorInitialDistM = null; standingAtAnchor = null
        walkedLive = 0f; anchorAimOk = false
        // Release the ARCore trunk anchor with the rest of the geometry — a
        // new measurement must pin its own trunk, never inherit the last one.
        ArSessionHub.clearHeightAnchor()
        trackingLive = true; trackingDropped = false; anchorLost = false
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
        // The UNIT is snapshotted with the text for the same reason: the
        // cruiser can retype and re-toggle while the write runs, and the value
        // must be converted and recorded with the unit it was typed under.
        val truthTextAtAccept = researchTrueText
        val truthUnitAtAccept = truthUnit
        // Always the metric base (m) — the conversion lives in TruthInput.
        val rawTrue = TruthInput.parsePositiveBase(truthTextAtAccept, truthUnitAtAccept)
        // OWNER GATE (iOS `typedTruthForThisMeasurement`). The typed value may
        // only be RECORDED against this measurement while both marks are clear,
        // i.e. the text was typed for THIS capture: an owned truth belongs to an
        // earlier capture that could not take it, and a pending one is queued
        // against a bundle still being written. Read HERE, synchronously, before
        // the attach block below can claim the pending slot — and read by BOTH
        // consumers (the saved reading and the research row) so the two can
        // never disagree about which capture a number was typed for.
        val ownedTrue =
            if (truthOwnerBundleId == null && truthPendingId == null) rawTrue else null
        // The plot this reading must land in. A field-log RE-MEASURE names the
        // plot explicitly — including "no plot", which is a real state for rows
        // recorded before plots existed — and `replaceReading` matches the
        // superseded reading on it. Resolving that null to the active plot sent
        // the replacement somewhere else, where it found nothing to supersede
        // and appended a SECOND reading on the tree: exactly the duplicate the
        // re-measure was built to prevent. Only a lock that is not a re-measure
        // (the map home's tree lock) means "wherever I am now". The crown below
        // rides the same decision, so a tree's readings cannot split across two
        // plots and become two rows in the log.
        val lockedPlotID = pendingLock?.plotID
        val targetPlotID =
            if (pendingLock?.replaceExisting == true) lockedPlotID
            else lockedPlotID ?: env.history.activePlotID.value
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
                    // Not recording: the value still went to the research CSV,
                    // so the field may clear — and with it every owner mark,
                    // since nothing is left on screen to belong to anyone.
                    !rawCaptureArmed -> {
                        if (researchTrueText == truthTextAtAccept) researchTrueText = ""
                        truthOwnerBundleId = null
                        truthPendingId = null
                        truthPending = null
                    }
                    // Left over from an EARLIER capture that couldn't take it:
                    // attaching it here would put one tree's pole measurement on
                    // another tree's bundle. Make the cruiser re-enter (or clear)
                    // it instead — the owner mark stays set, so the gate above
                    // keeps it out of this tree's reading and research row too.
                    truthOwnerBundleId != null && truthOwnerBundleId != rid ->
                        truthSaveFailure = RawCaptureStrings.TRUTH_STALE_OWNER
                    // The capture itself failed — keep the typed value on
                    // screen rather than attach it to a bundle that isn't there.
                    // It is marked as owned by no bundle (the failed capture's
                    // id was already dropped) so the NEXT tree's Accept refuses
                    // it instead of exporting it as that tree's pole number.
                    lastCaptureFailure != null -> {
                        truthSaveFailure =
                            RawCaptureStrings.truthCaptureFailed(lastCaptureFailure)
                        truthOwnerBundleId = rid ?: TRUTH_NO_BUNDLE_OWNER
                        truthPendingId = null
                        truthPending = null
                    }
                    rid == null -> if (researchTrueText == truthTextAtAccept) researchTrueText = ""
                    else -> {
                        // Claim the pending slot BEFORE the store call: the
                        // recorder's own completion handler reads it to decide
                        // whether the queued value landed.
                        truthPendingId = rid
                        truthPendingText = truthTextAtAccept
                        when (RawCaptureStore.setTruth(
                            context, rid, rawTrue, truthUnitAtAccept)) {
                            // Durable — the manifest already exists.
                            RawCaptureStore.TruthWrite.SAVED -> {
                                truthPending = null
                                truthPendingId = null
                                // In the manifest and off the screen — the only
                                // state that owns nothing.
                                truthOwnerBundleId = null
                                if (researchTrueText == truthTextAtAccept) researchTrueText = ""
                            }
                            // QUEUED is NOT durable: the value lives only in
                            // the store's pending queue until the in-flight
                            // write folds it in, so the text stays put — and
                            // text left on screen belongs to THIS bundle until
                            // the cruiser retypes it.
                            // (If the writer already resolved it, truthPendingId
                            // is null again and there is nothing to announce.)
                            RawCaptureStore.TruthWrite.QUEUED ->
                                if (truthPendingId == rid) {
                                    truthPending = RawCaptureStrings.TRUTH_PENDING
                                    truthOwnerBundleId = rid
                                }
                            RawCaptureStore.TruthWrite.FAILED -> {
                                truthPending = null
                                truthPendingId = null
                                truthOwnerBundleId = rid
                                truthSaveFailure = RawCaptureStrings.TRUTH_WRITE_FAILED
                            }
                        }
                    }
                }
            }
            lastRawCaptureId = null
            // Chrome-less snapshot of the AR surface (camera feed + the
            // rendered measurement geometry): hide the 2D chrome, give
            // Compose one committed frame, capture, then restore. Null when
            // the capture failed — the reading still saves, without a photo.
            val photo = activity?.let {
                hidingChromeForCapture = true
                delay(80)
                val name = MeasurePhotoStore.captureScene(it)
                hidingChromeForCapture = false
                name
            }
            // FRESHNESS-GATED. lastGlobalFix is the newest fix ANY screen
            // ever saw, with no age check, so a red GPS chip and a green one
            // used to produce byte-identical records: a cruiser under heavy
            // canopy stamped every tree in the plot with the position they
            // had when they walked in. The same rule the map, the plot
            // verdict and the chip itself use decides here — an unusable fix
            // stores NO position rather than a confident wrong one.
            val fix = freshFixOrNull(
                com.hcjeong.forestix.positioning.LocationService.lastGlobalFix,
                System.currentTimeMillis())
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
                val reading =
                    QuickMeasureEntry(
                        kind = MeasureKind.HEIGHT, value = r.heightM.toDouble(),
                        // σ null = no uncertainty was derivable. It stays
                        // null in the history rather than becoming a 0 that
                        // reads as perfect precision. (Accept already refuses
                        // tangent fits with no σ — see HeightEstimator.canAccept.)
                        sigma = r.sigmaHm?.toDouble(), confidenceRaw = r.confidence.raw,
                        method = r.method.raw, treeNumber = pendingTree,
                        // The chooser's name when this flow was launched with
                        // one, else the name the tree already carries — a
                        // chained or repeat height must not arrive nameless
                        // and split the tree in two in the export.
                        // The name is looked up in the plot this reading lands
                        // in, not the active one — on a re-measure they can be
                        // different plots, and the wrong plot's tree 7 is a
                        // different tree.
                        treeName = pendingLock?.name
                            ?: env.history.treeName(pendingTree, targetPlotID),
                        plotID = targetPlotID,
                        speciesCode = metaSpecies,
                        damageCodes = metaDamage,
                        note = metaNote.ifBlank { null },
                        latitude = fix?.latitude,
                        longitude = fix?.longitude,
                        photoPath = photo,
                        // The pole / clinometer height belongs ON the reading:
                        // the raw-capture manifest is developer plumbing that
                        // can be pruned, while the truth column the accuracy
                        // study reads is exported from here. A value typed on
                        // this screen is the newest word on this tree and wins;
                        // a re-measure otherwise keeps the hand-measured value
                        // already typed for it, and a fresh reading has none.
                        // `ownedTrue`, never the bare re-parse: text kept on
                        // screen from an earlier capture is not this tree's
                        // pole measurement.
                        truth = (if (settings.developerMode) ownedTrue else null)
                            ?: pendingLock?.truth,
                    )
                // A re-measure launched from the field log TAKES THE PLACE of
                // the reading it was launched from — appending would leave the
                // superseded number invisible in the log but still in the CSV.
                if (pendingLock?.replaceExisting == true) {
                    env.history.replaceReading(reading)
                } else {
                    env.history.append(reading)
                }
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
                        treeNumber = pendingTree,
                        treeName = pendingLock?.name
                            ?: env.history.treeName(pendingTree, targetPlotID),
                        // Same plot as the height this crown was measured
                        // inside — see `targetPlotID`.
                        plotID = targetPlotID,
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
                // Blank, not 0, when there was no pose to compare — an
                // unmeasured drift and a zero drift are different facts.
                "aim_drift_m" to (aimDriftM?.let { String.format(Locale.US, "%.3f", it) } ?: ""),
                // Did VIO drop between anchoring and the aims? The warning the
                // cruiser saw travels WITH the row — an accepted reading taken
                // across a dropout used to export identically to a clean one.
                // On a WALK-OFF row "false" is a positive statement that the
                // walk was continuous, not a missing observation.
                //
                // EMPTY on a typed manual height, which is what the column's
                // own definition says ("Empty on rows with no walk-off (DBH,
                // typed manual heights)" — ResearchLog) and what this line did
                // not do. The manual Save does not reset the latch, so a
                // cruiser who anchored, walked, hit a dropout, gave up and
                // typed the number exported tracking_dropped=true for a row
                // where nothing was tracked; and an ordinary typed row exported
                // "false", positively claiming that a walk-off which never
                // happened was continuous. Both are assertions about a
                // measurement the row did not make. iOS makes the same test.
                "tracking_dropped" to when {
                    r.method == HeightMethod.MANUAL_ENTRY -> ""
                    trackingDropped -> "true"
                    else -> "false"
                },
                "species" to (metaSpecies ?: ""),
                "note" to metaNote,
            )
            // The tree this capture is ALREADY locked to, not a box the
            // cruiser had to retype. Same value the raw-capture bundle and the
            // saved reading carry, so the three join.
            fields["tree_id"] = "${CruiseCapture.target?.treeNumber ?: pendingTree}"
            // `ownedTrue`, never the bare re-parse — see the owner gate. A
            // stale number here is the worst of the two: the CSV is what the
            // accuracy study reads, and this tree's row would carry an earlier
            // tree's pole value with an error computed against this height.
            ownedTrue?.let { t ->
                // `true_value` and `error` are in the row's `unit` (m) — the
                // same scale as `measured_value`, so the error column stays
                // subtractable. `truth_unit` records what was actually typed.
                fields["true_value"] = String.format(Locale.US, "%.2f", t)
                fields["error"] = String.format(Locale.US, "%.2f", r.heightM - t)
                fields["truth_unit"] = truthUnitAtAccept.raw
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
            // FIELD REPORT 10 — THIS SCREEN USED TO TAKE THE DEFAULTS, and
            // the defaults are `enableDepth = true, planeRenderer = true`.
            //
            // The height measurement reads NOTHING from depth: both angles
            // come from the camera pose and d_h from pose translation. Depth
            // is used in exactly two places — the anchor stage's gated hit
            // test, and the aim frames a recording run keeps — so outside
            // those it was a per-frame cost with no consumer.
            //
            // The plane renderer was worse, and it is why the stutter came
            // on PARTWAY THROUGH rather than at the start. ARCore keeps
            // finding new planes as the device moves, and this is the one
            // flow that walks 10–20 m across a forest floor: by the time the
            // cruiser turns to sight the top, SceneView is tessellating and
            // drawing every patch of leaf litter ARCore has decided is a
            // plane. The grid is not an aid here — the cruiser is lining a
            // crosshair up on a trunk and a treetop — so it is off. Plane
            // DETECTION is untouched, which is what the anchor hit test
            // needs; only the drawing stops.
            //
            // The two plot screens have done exactly this since the plot
            // overlay landed (`enableDepth = !placed, planeRenderer =
            // !placed`); the height screen was simply never given the same
            // treatment.
            enableDepth = stage == Stage.ANCHOR || rawCaptureArmed,
            planeRenderer = false,
            // Active sampling plot (if any) as a subdued, non-interactive
            // overlay under the height markers.
            plotOverlay = ArSessionHub.PlotOverlay.SUBDUED,
        )
        crosshairLabel?.let { label -> HeightAimCrosshair(label) }
        // Developer instrument, drawn ON TOP of the ring so one screenshot
        // carries the ring, the projected optical axis and the numbers
        // together. Hidden with the rest of the 2D chrome for the Accept
        // snapshot, so the stored photo is identical to a non-developer run's.
        if (settings.developerMode && !hidingChromeForCapture) {
            OpticalAxisInstrument(axisProbe)
        }
        if (!hidingChromeForCapture) MeasureBackButton { nav.popBackStack() }

        // Plot mini-map — top-right, same row as the GPS badge: the active
        // cruise plot (ring + YOU + measured trees) or the quick sampling
        // ring (ring + YOU). Hidden with the rest of the 2D chrome during
        // the Accept snapshot blackout.
        // F11 — the card is TAPPABLE: it re-opens plot setup so the radius /
        // centre stay editable after the first placement. The session's
        // project/plot are fixed for this screen's lifetime, so one remember
        // is safe. FIELD REPORT 12 — a quick sampling ring gets Edit too,
        // pointed at the sampling screen that owns it (Diameter parity).
        val miniMapUp = scanPlotMiniMapVisible()
        val editPlotTarget = remember { CruiseCapture.target }
        val onEditPlot: () -> Unit = editPlotTarget?.let { c ->
            {
                // The setup session about to open rewrites the plot this
                // session is measuring into — a ring linked to any OTHER plot
                // is not this plot's centre and must not be read there as a
                // re-placement (field report 7; see ArSessionHub.armPlotSetup).
                ArSessionHub.armPlotSetup(c.plotId)
                nav.navigate(
                    CruiseRoutes.editPlot(c.projectId.toString(), c.plotId.toString()))
            }
        } ?: { nav.navigate(Routes.SAMPLING) }
        if (!hidingChromeForCapture) ScanPlotMiniMap(onEditPlot = onEditPlot)

        // Same top strip as the Diameter scan — one row, inset past the
        // system status bar, with the mini-map's width reserved on the
        // trailing edge. Height has no centre title of its own; the slot
        // exists so both scans share one geometry.
        if (!hidingChromeForCapture) {
            MeasureTopStrip(
                reserveTrailing = if (miniMapUp) MeasureMiniMapSlot else 0.dp,
                // The same GPS chip the map and the Diameter scan show
                // (FIELD REPORT 11) — one readout for "can the app see where
                // I am right now?", including at the moment a fix is written
                // onto the stored reading.
                leading = { GpsFixChip(acquiresService = true) },
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
                            // Unmeasured reads as "—", never as 0.00: a start
                            // distance of zero is a claim (the cruiser was
                            // standing ON the trunk) and it would be a false
                            // one. Unreachable in practice — the anchor stage
                            // refuses without a camera pose, which is what
                            // makes iOS's non-optional `initialDistanceM`
                            // equivalent — so the two platforms print the same
                            // sentence for every state that can occur.
                            "Start distance " + (anchorInitialDistM?.let {
                                MeasurementFormatter.distance(
                                    it.toDouble(), settings.unitSystem)
                            } ?: "—"),
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
                // Walk-off integrity beats stage guidance: "walk back" is
                // useless advice while the camera has no idea where it is,
                // and the anchor being gone ends the measurement outright.
                anchorLost -> ANCHOR_LOST
                !trackingLive && stage in listOf(
                    Stage.WALKING, Stage.AIM_BASE, Stage.AIM_TOP,
                ) -> TRACKING_LOST_NOW
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
            // FIELD REPORT 14 × 17 — a plot is being tallied but no AR
            // anchor marks its centre, so there is no ring to draw. Same
            // card, same words, same act as the DBH twin.
            below = pinCentreOffer?.let { offer ->
                {
                    PlotPinCentreCard(
                        failure = offer.failure,
                        onPin = { offer.pin() },
                        onDismiss = { offer.dismiss() },
                    )
                }
            },
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
                        // White on the dark panel — the Material default is
                        // near-black, i.e. invisible here (see
                        // scanPanelTextFieldColors).
                        colors = scanPanelTextFieldColors(),
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
                    // THE INSTRUMENT MOVED between the two sightings, so the
                    // tangent pair no longer shares an origin and the height
                    // is soft by roughly this much.
                    //
                    // This lives in the SHARED panel, not in the COMPUTED
                    // action row where it started, because a RED fit is
                    // Accept-able here: a cruiser who stepped half a metre
                    // and got a red result would have read the rejection
                    // reason, decided the tree was just awkward, and accepted
                    // a badly drifted height with no warning at all. iOS has
                    // always rendered it for both stages, because its
                    // resultPanel is shared; this is the same placement.
                    aimDriftM?.takeIf { it > AIM_DRIFT_WARN_M }?.let { d ->
                        Text(
                            "You moved " + MeasurementFormatter.distance(
                                d.toDouble(), settings.unitSystem) +
                                " between the base and top sightings. Both have to be taken from one spot — retake for a firm number.",
                            style = type.caption,
                            color = Forestix.colors.confidenceWarn,
                        )
                    }
                    // THE CAMERA LOST THE SCENE somewhere between anchoring
                    // and this reading. d_h is the whole scale of the height
                    // — H = d_h(tan α_top − tan α_base) — and it rests on a
                    // walk ARCore did not see all of. The status line said so
                    // at the time, but that is transient and this is the
                    // moment the cruiser decides whether to keep the number,
                    // so it is repeated here for the same reason the drift
                    // warning is. Not a refusal: ARCore usually relocalizes
                    // and the corrected anchor makes the reading sound again,
                    // and the cruiser has already walked the distance.
                    if (trackingDropped) {
                        Text(
                            TRACKING_DROPPED_DURING_WALK,
                            style = type.caption,
                            color = Forestix.colors.confidenceWarn,
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
                                // Label and unit come from the SAME value, so
                                // the field can never say m while the app
                                // reads feet.
                                trueLabel = TruthInput.fieldLabel(
                                    TruthInput.Quantity.HEIGHT, truthUnit),
                                trueValue = researchTrueText,
                                // ',' is NORMALISED to '.', never deleted — the
                                // old digit filter turned "12,5" into "125".
                                onTrueChange = {
                                    researchTrueText = TruthInput.sanitize(it)
                                    // Editing retires every "couldn't save"
                                    // state: the text is now the cruiser's
                                    // current intent for the capture ON SCREEN,
                                    // so it belongs to no earlier bundle and is
                                    // no longer the value that was queued. This
                                    // is also the ONLY way out of the stale-owner
                                    // refusal — re-type it, or clear the field.
                                    truthOwnerBundleId = null
                                    truthSaveFailure = null
                                    truthPendingId = null
                                    truthPending = null
                                },
                                truePlaceholder = "clinometer",
                                truthUnit = truthUnit,
                                onToggleTruthUnit = { truthUnit = TruthInput.toggled(truthUnit) },
                            )
                            // Live warning under the truth field: a failed save,
                            // unparseable text, or an implausible value. The
                            // window is judged on the converted value, so an
                            // imperial entry is checked against the same limits.
                            (truthSaveFailure
                                ?: TruthInput.fieldWarning(
                                    researchTrueText,
                                    TruthInput.Quantity.HEIGHT,
                                    truthUnit))
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

/// Outer diameter of the aim ring (iOS 1:1).
private val HEIGHT_AIM_RING_DP = 40.dp

/// Label pill CENTRE, measured down from the crosshair centre: the ring's
/// outer radius (20) + the original 8 dp gap + half the pill (13 sp line +
/// 4 dp padding top and bottom ≈ 24 dp). Offsets the label only — the ring
/// cannot move with it.
private val HEIGHT_AIM_LABEL_OFFSET_DP = 40.dp

/// Amber labelled aim crosshair — port of the iOS Height `crosshair(label:)`:
/// dual-stroke 40/36 ring + cross with dark halos, and a state pill that
/// explains what the next tap captures.
///
/// FIELD REPORT 16 — the ring and the label used to be a Column, and the
/// COLUMN was what got centred. That put the ring's centre half the label
/// block (label height + the 8 gap, ≈ 16 dp) ABOVE the true screen centre,
/// while every hit test and every geometric marker placement uses the true
/// centre — so the anchor sphere landed consistently below the ring, by a
/// fixed amount, on every tap. The ring IS the aiming instrument, so it is
/// centred on its own here and the label is pushed clear with an explicit
/// offset. Same construction as the Diameter scan, where DbhRing is centred
/// alone and the tilt badge / capture pill ride explicit offsets.
@Composable
private fun BoxScope.HeightAimCrosshair(label: String) {
    val warn = Forestix.colors.confidenceWarn
    Box(
        Modifier.align(Alignment.Center).size(HEIGHT_AIM_RING_DP),
        contentAlignment = Alignment.Center,
    ) {
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
            .align(Alignment.Center)
            .offset(y = HEIGHT_AIM_LABEL_OFFSET_DP)
            .clip(RoundedCornerShape(4.dp))
            .background(Color.Black.copy(alpha = 0.65f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/// Cyan — deliberately NOT the amber the aim ring is drawn in, so a
/// screenshot can never be read with the two marks confused.
private val OPTICAL_AXIS_TINT = Color(0.20f, 0.85f, 1f, 0.85f)

/// Optical-axis marker box (an X + open circle, drawn inside it).
private val OPTICAL_AXIS_MARKER_DP = 22.dp

/// Readout block CENTRE, measured down from the view centre: clear of the aim
/// label pill (centre at 40 dp, ≈ 11 dp half-height), a gap, then half of a
/// four-line block.
private val OPTICAL_AXIS_READOUT_OFFSET_DP = 96.dp

/// AIM-OFFSET INSTRUMENT (developer mode only) — draws where the camera
/// optical axis projects onto the view, and reports its offset from the view
/// centre in view pixels and in degrees. See ArController.opticalAxisProbe:
/// the elevation the height tangent uses and the base/top spheres both live
/// on the optical axis, while the aim ring and the anchor hit test use the
/// view centre, and this says how far apart those two references actually
/// are. It measures only — the ring is not moved, the hit test is not moved,
/// and no stored value can reach this code.
///
/// A null probe prints WHY there is no reading rather than a zero offset,
/// which would read as "the two references coincide".
@Composable
private fun BoxScope.OpticalAxisInstrument(probe: OpticalAxisProbe?) {
    val density = LocalDensity.current
    if (probe != null) {
        val x = with(density) { probe.viewX.toDp() }
        val y = with(density) { probe.viewY.toDp() }
        Canvas(
            Modifier
                .align(Alignment.TopStart)
                .offset(
                    x = x - OPTICAL_AXIS_MARKER_DP / 2,
                    y = y - OPTICAL_AXIS_MARKER_DP / 2,
                )
                .size(OPTICAL_AXIS_MARKER_DP),
        ) {
            // Dark halo under a thin tint, the same sun-glare treatment the
            // aim ring gets — a bare cyan line vanishes against sky.
            val halo = Color.Black.copy(alpha = 0.55f)
            val haloW = 3.dp.toPx()
            val lineW = 1.5.dp.toPx()
            val r = size.minDimension * 0.30f
            val c = Offset(size.width / 2f, size.height / 2f)
            val a = Offset(0f, 0f)
            val b = Offset(size.width, size.height)
            val d = Offset(0f, size.height)
            val e = Offset(size.width, 0f)
            drawLine(halo, a, b, haloW)
            drawLine(halo, d, e, haloW)
            drawCircle(halo, r, c, style = Stroke(haloW))
            drawLine(OPTICAL_AXIS_TINT, a, b, lineW)
            drawLine(OPTICAL_AXIS_TINT, d, e, lineW)
            drawCircle(OPTICAL_AXIS_TINT, r, c, style = Stroke(lineW))
        }
    }
    Column(
        Modifier
            .align(Alignment.Center)
            .offset(y = OPTICAL_AXIS_READOUT_OFFSET_DP)
            .clip(RoundedCornerShape(4.dp))
            .background(Color.Black.copy(alpha = 0.65f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Optical axis vs view centre",
            style = Forestix.type.dataSmall,
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        if (probe == null) {
            Text(
                "not available — no camera frame or display mapping yet",
                style = Forestix.type.dataSmall,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
        } else {
            Text(
                String.format(Locale.US, "dx %+.1f px  %+.2f°", probe.dxPx, probe.dxDeg),
                style = Forestix.type.dataSmall,
                color = OPTICAL_AXIS_TINT,
            )
            Text(
                String.format(Locale.US, "dy %+.1f px  %+.2f°", probe.dyPx, probe.dyDeg),
                style = Forestix.type.dataSmall,
                color = OPTICAL_AXIS_TINT,
            )
            Text(
                String.format(Locale.US, "r %.1f px  %.2f°", probe.rPx, probe.rDeg),
                style = Forestix.type.dataSmall,
                color = OPTICAL_AXIS_TINT,
            )
        }
    }
}

