// Tree Diameter (DBH) — Android, with a live HUD matching iOS: a horizontal
// guide line, a crosshair ring that turns green when a trunk fit locks, a
// live "DBH x.x cm / Distance y.y m" badge, and a green fit-width chord
// drawn across the trunk. The committed reading still runs the full §7.1
// burst estimator (DBHEstimator) on Accept.

package com.hcjeong.forestix.ui.screens.dbh

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.hcjeong.forestix.LocalAppEnvironment
import com.hcjeong.forestix.ar.ArCameraView
import com.hcjeong.forestix.ar.ArSceneMarker
import com.hcjeong.forestix.ar.ArSessionHub
import com.hcjeong.forestix.ar.MarkerShape
import com.hcjeong.forestix.ar.Vec3
import com.hcjeong.forestix.ar.distance
import com.hcjeong.forestix.common.MeasurementFormatter
import com.hcjeong.forestix.common.TruthInput
import com.hcjeong.forestix.common.UnitSystem
import com.hcjeong.forestix.common.Units
import com.hcjeong.forestix.data.MeasureKind
import com.hcjeong.forestix.data.QuickMeasureEntry
import com.hcjeong.forestix.data.ResearchLog
import com.hcjeong.forestix.data.StemPosition
import com.hcjeong.forestix.sensors.ArDepthFrame
import com.hcjeong.forestix.sensors.ChordAlgorithm
import com.hcjeong.forestix.sensors.ConfidenceTier
import com.hcjeong.forestix.sensors.DBHEstimator
import com.hcjeong.forestix.sensors.DBHResult
import com.hcjeong.forestix.sensors.DBHMethod
import com.hcjeong.forestix.sensors.GuideAxis
import com.hcjeong.forestix.sensors.RawCaptureStore
import com.hcjeong.forestix.ui.MeasurePhotoStore
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
import com.hcjeong.forestix.ui.screens.RawCaptureBadge
import com.hcjeong.forestix.ui.screens.RawCaptureOffNotice
import com.hcjeong.forestix.ui.screens.RawCaptureStatus
import com.hcjeong.forestix.ui.screens.RawCaptureStrings
import com.hcjeong.forestix.ui.screens.ResearchFieldsRow
import com.hcjeong.forestix.ui.screens.TruthFieldNote
import com.hcjeong.forestix.ui.screens.TruthFieldWarning
import com.hcjeong.forestix.ui.screens.ScanPlotMiniMap
import com.hcjeong.forestix.ui.screens.TiltBadge
import com.hcjeong.forestix.ui.screens.scanPlotMiniMapVisible
import com.hcjeong.forestix.ui.clickableNoRipple
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixProminentButton
import com.hcjeong.forestix.ui.theme.ForestixSpace
import com.hcjeong.forestix.ui.theme.ForestixWhiteButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

private enum class Stage { AIMING, CAPTURING, RESULT }

/// Sub-measurements per hold-steady capture; the 3 closest to the median
/// are kept (2 largest deviations trimmed) and averaged.
private const val SAMPLE_COUNT = 5

/// Consecutive bad/absent per-frame fits tolerated while locked before the
/// HUD drops back to aiming. The display freezes on the last good smoothed
/// value through the hold — invisible while steady. At the 150 ms preview
/// tick this holds (7−1)×150 ≈ 900 ms of misses. iOS parity in SPIRIT, not
/// tick count: its redResetCount=3 rides a much faster preview tick, i.e. a
/// sub-second wall-clock hold — matched here in wall-clock terms. Round-7
/// field fix: ML-depth "breathing" produces miss runs of ~0.5 s on a
/// perfectly steady aim, so the old 3-tick (0.45 s) hold flapped the lock.
/// A REAL re-aim still cuts the hold short (~300 ms) via the tap-jump
/// detector in the preview loop.
private const val PREVIEW_MISS_RESET = 7

/// Fresh-vs-held tap-depth divergence treated as a possible re-aim: one
/// such tick is bridged with the EMA seed (could be an ML-depth hole /
/// outlier); two consecutive divergent ticks reset the lock immediately.
private const val TAP_JUMP_M = 0.5f

/// Cylinder-anchor snap threshold: a chord-fit centre that moves by more
/// than this in one tick is a real move (new aim spot / user stepped), so
/// the anchor SNAPS there instead of EMA-lagging — a lagging anchor at a
/// stale nearer depth rendered the cylinder up to z_old/z_new wider than
/// the bar. Below it, the EMA (α=0.3) irons out hand tremor as before.
private const val CYL_ANCHOR_SNAP_M = 0.05f

/// `chainToHeight` = launched from the map-home "Full measurement" row
/// ("dbh?chain=true"): Accept saves the diameter, skips the continuation
/// dialog, and goes straight to Height on the same tree.
@Composable
fun DBHScanScreen(nav: NavController, chainToHeight: Boolean = false) {
    val env = LocalAppEnvironment.current
    val context = LocalContext.current
    // Shared app-scoped AR session (world coordinates survive navigation,
    // and the sampling plot's anchor renders here as a subdued overlay).
    val controller = ArSessionHub.controller
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
    // Developer-mode research capture: tape-measured true diameter (cm).
    var researchTrueCm by remember { mutableStateOf("") }
    // Cruise quick-tally loop (field-benchmark batch): the target tree number
    // shown in the top pill, mirrored into Compose state so advance/undo
    // recompose (CruiseCapture.target is a plain @Volatile holder).
    var cruiseTreeNumber by remember { mutableStateOf(CruiseCapture.target?.treeNumber) }
    // Undo toast (F): the just-saved tree number, cleared 3 s after the
    // epoch it was raised in (epoch restarts the timer on rapid tallies).
    var undoToast by remember { mutableStateOf<Int?>(null) }
    var undoEpoch by remember { mutableStateOf(0) }
    LaunchedEffect(undoEpoch) {
        if (undoToast != null) {
            delay(3_000)
            undoToast = null
        }
    }
    val colors = Forestix.colors

    val settings by env.settings.state.collectAsStateWithLifecycle()
    // Project calibration — identity for plain quick-measure (iOS parity),
    // the active project's wall/cylinder fits when launched from the
    // Add-Tree flow (iOS injects it into DBHScanViewModel).
    val calibration by env.activeScanCalibration.collectAsStateWithLifecycle()
    // The DBH scan requires the ARCore Depth API (manual typed entry is the
    // only depth-free path left). Block when a previous session's DEFINITIVE
    // negative verdict is cached (tc.depthUnsupported — then no probe
    // session ever starts, see the ArCameraView gate below), or once the
    // live session has actually REPORTED capability, so capable devices
    // don't flash the blocker while AR is still starting up. Developer mode
    // ignores the cache along with the blocker so a dev-mode session
    // re-probes, refreshing/clearing the cached verdict.
    val depthBlocked = !settings.developerMode &&
        (settings.depthUnsupported ||
            (controller.depthSupportKnown && !controller.supportsDepth))
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

    // Frames captured so far in the current burst (drives the progress arc).
    var sampleProgress by remember { mutableStateOf(0) }

    // ADJUST edge-bracket mode (manual edge placement, depth method only):
    // two draggable handles bracket the trunk on the guide line; the
    // constrained estimate uses the handle span as the silhouette width.
    var adjustMode by remember { mutableStateOf(false) }
    var adjustLeftFrac by remember { mutableStateOf(0.25f) }
    var adjustRightFrac by remember { mutableStateOf(0.75f) }
    var adjustPreview by remember { mutableStateOf<DBHEstimator.DbhPreview?>(null) }
    // Whether the on-screen result came from the ADJUST bracket — recorded
    // as captureMode "manual" vs "auto" on Accept.
    var resultFromAdjust by remember { mutableStateOf(false) }

    // User-selected per-frame chord algorithm (silhouette = iOS-identical).
    val chordAlgorithm = ChordAlgorithm.fromRaw(settings.dbhChordAlgorithm)

    // Raw-capture recorder arm (developer mode + tc.rawCaptureEnabled).
    // While armed the shared controller keeps the native u16 depth on each
    // acquired frame so a burst can be serialized for offline replay; reset
    // on leave so no other screen inherits the cost.
    val rawCaptureArmed = settings.developerMode && settings.rawCaptureEnabled
    // REF-COUNTED arm (see ArSessionHub.armRawDepth). A plain
    // `onDispose { controller.captureRawDepth = false }` used to disarm the
    // process-wide controller from the OUTGOING screen during the
    // DBH→Height chain, silently stripping every chained height bundle's
    // aim frames — a disposing screen may only release its own token.
    DisposableEffect(rawCaptureArmed) {
        val token = if (rawCaptureArmed) ArSessionHub.armRawDepth() else null
        onDispose { token?.let { ArSessionHub.releaseRawDepth(it) } }
    }
    // The most-recent burst's stored bundle id — minted SYNCHRONOUSLY at
    // burst end (not when the async write finishes), so an Accept tapped
    // before serialization completes still attaches its ground truth. Flipped
    // to operator_accepted on Accept (iOS markAccepted flow).
    var lastRawCaptureId by remember { mutableStateOf<String?>(null) }
    // Visible outcome of the last capture attempt (saved / NOT saved).
    var rawCaptureStatus by remember { mutableStateOf<RawCaptureStatus?>(null) }
    var rawStatusEpoch by remember { mutableStateOf(0) }
    // Reason the last capture failed to record (null = the last capture
    // saved). Accept reads it so a typed truth is never attached to — or
    // silently lost with — a bundle that isn't there.
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
    // guard the recorder refuses to write, so the REC pill says LOW STORAGE
    // before a whole plot is lost.
    var storageLow by remember { mutableStateOf(false) }
    LaunchedEffect(rawCaptureArmed, rawStatusEpoch) {
        storageLow = rawCaptureArmed &&
            RawCaptureStore.freeSpaceBytes(context) < RawCaptureStore.MIN_FREE_BYTES
    }
    LaunchedEffect(rawStatusEpoch) {
        // A success fades; a FAILURE stays up until the next attempt — a lost
        // capture must not scroll past unnoticed.
        if (rawCaptureStatus?.saved == true) {
            delay(4_000)
            rawCaptureStatus = null
        }
    }
    fun postRawStatus(status: RawCaptureStatus) {
        rawCaptureStatus = status
        rawStatusEpoch += 1
    }

    // Serialize one DBH burst for offline estimator replay (off the main
    // thread). `bracket` non-null = the ADJUST manual path. Fired at the end
    // of every burst regardless of tier (accepted / rejected / ADJUST alike).
    // `rgb` is the reference JPEG grabbed at BURST START.
    fun recordRawDbh(
        frames: List<ArDepthFrame>,
        tapX: Double, tapY: Double, axisRow: Boolean,
        bracket: RawCaptureStore.BracketSpec?,
        rgb: ByteArray?,
    ) {
        if (!rawCaptureArmed) return
        val cruise = CruiseCapture.target
        val ctx = RawCaptureStore.CaptureContext(
            mode = if (cruise != null) "cruise" else "quick",
            projectId = cruise?.projectId?.toString(),
            plotId = cruise?.plotId?.toString() ?: env.history.activePlotID.value?.toString(),
            treeNumber = cruise?.treeNumber ?: pendingTree,
            gps = com.hcjeong.forestix.positioning.LocationService.lastGlobalFix,
        )
        // Mint the id NOW so Accept has something to attach truth to even if
        // the write is still running (RawCaptureStore queues edits per id).
        val id = RawCaptureStore.newBundleId()
        lastRawCaptureId = id
        scope.launch {
            val outcome = RawCaptureStore.recordDbh(
                context = context,
                id = id,
                frames = frames,
                tapX = tapX, tapY = tapY, axisRow = axisRow,
                bracket = bracket,
                cal = calibration,
                algorithmRaw = settings.dbhChordAlgorithm,
                unitSystem = if (settings.unitSystem == UnitSystem.METRIC) "metric" else "imperial",
                ctx = ctx,
                rgb = rgb,
            )
            if (outcome.saved) {
                lastCaptureFailure = null
                postRawStatus(RawCaptureStatus(RawCaptureStrings.saved(outcome.frameCount), true))
                // A truth typed mid-write is DURABLE only now that the
                // manifest carries it — this is the only place the field may
                // be cleared for a queued value.
                if (outcome.queuedTruthSaved != null && truthPendingId == id) {
                    if (researchTrueCm == truthPendingText) researchTrueCm = ""
                    truthPending = null
                    truthPendingId = null
                }
            } else {
                // Nothing on disk under this id — drop it so a later Accept
                // can't queue truth onto a bundle that will never exist.
                if (lastRawCaptureId == id) lastRawCaptureId = null
                lastCaptureFailure = outcome.error ?: "write failed"
                postRawStatus(RawCaptureStatus(RawCaptureStrings.notSaved(outcome.error), false))
                // A truth queued against this bundle died with it. Losing the
                // pairing is acceptable; losing it INVISIBLY is not — put the
                // number back on screen (or at least name it) and say so.
                outcome.queuedTruthLost?.let { lost ->
                    val text = TruthInput.text(lost)
                    val restore = TruthInput.normalized(researchTrueCm).isEmpty()
                    if (restore) researchTrueCm = text
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

    // One reference RGB JPEG of the CURRENT camera frame, grabbed at burst
    // START (iOS parity). The old path let the store grab it on a background
    // thread AFTER the whole burst, by which time the phone was often
    // pointing elsewhere — or the frame was gone and rgb_file came back null.
    // Written to a throwaway cache file (the only sink captureCameraJpeg
    // offers), read into memory, deleted.
    fun captureReferenceJpeg(): ByteArray? {
        if (!rawCaptureArmed) return null
        return try {
            val tmp = java.io.File.createTempFile("dbh_ref_", ".jpg", context.cacheDir)
            val bytes = if (controller.captureCameraJpeg(tmp)) tmp.readBytes() else null
            tmp.delete()
            bytes
        } catch (_: Throwable) { null }
    }

    // Preview smoothing (EMA α=0.3 on the diameter + distance, iOS parity)
    // + a short lock streak so the digit + cylinder don't flicker on a
    // single frame.
    var smoothedDiaCm by remember { mutableStateOf<Float?>(null) }
    var smoothedDistM by remember { mutableStateOf<Float?>(null) }
    // Cylinder anchor — EMA over the CHORD FIT's own axis centre (iOS
    // smoothedCenterWorldXZ parity), held through one-frame misses and
    // SNAPPED when the fit centre moves beyond CYL_ANCHOR_SNAP_M. The raw
    // per-tick centre wanders across the marker's 1 cm quantisation grid,
    // so unsmoothed it changed the marker value nearly every tick.
    var smoothedHitAnchor by remember { mutableStateOf<Vec3?>(null) }
    var lockStreak by remember { mutableStateOf(0) }
    // Dropout hysteresis: while locked, up to PREVIEW_MISS_RESET−1
    // consecutive misses HOLD the last smoothed preview instead of
    // unlocking — ring colour, chord, badge and status text stay steady
    // through one-frame fit dropouts (iOS tolerates transient reds the
    // same way).
    var missStreak by remember { mutableStateOf(0) }
    // Consecutive ticks whose FRESH centre median diverged > TAP_JUMP_M from
    // the held smoothed distance while shown-locked. 1 = bridge with the EMA
    // seed (hole/outlier); 2 = real re-aim → reset immediately (cuts the
    // miss-hold short).
    var tapJumpStreak by remember { mutableStateOf(0) }
    // Clean-row widths of the last 2 preview ticks — rolling row quorum
    // while shown-locked, so one tick's edge jitter clipping a couple of
    // rows can't kill an established fit. Preview-only; the capture burst
    // never sees carry. Cleared whenever the guide axis flips (widths are
    // walk-axis pixels — mixing axes would median incompatible units).
    val carryWidths = remember { ArrayDeque<List<Int>>() }
    var lastAxisRow by remember { mutableStateOf<Boolean?>(null) }
    // Shown preview tier only flips after 2 consecutive frames agree, so
    // the green chip can't blink in/out on the raw per-frame CoV.
    var shownTier by remember { mutableStateOf(ConfidenceTier.YELLOW) }
    var tierStreak by remember { mutableStateOf(0) }
    // Translucent 3D trunk cylinder at the live fit. Quantised to ~1 cm so
    // the value only changes on a real move; ArCameraView then MOVES the
    // existing node and rebuilds Filament resources only when the quantised
    // radius itself changes.
    var cylinderMarker by remember { mutableStateOf<ArSceneMarker?>(null) }

    // Live single-frame preview loop while aiming (paused while the ADJUST
    // bracket owns the edges).
    LaunchedEffect(stage, depthBlocked, adjustMode) {
        while (stage == Stage.AIMING && !depthBlocked && !adjustMode) {
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
                val isRowAxis = axis is GuideAxis.Row
                if (lastAxisRow != isRowAxis) { carryWidths.clear(); lastAxisRow = isRowAxis }
                // Preview-layer tap-depth seeding (lock stability). The
                // fresh 5×5 median is the normal seed; while shown-locked, a
                // hole (null) or a single wild excursion vs the held EMA
                // distance is bridged by seeding from the EMA instead of
                // failing the whole tick — the dominant flap cause, since a
                // bad dTap shifts the envelope centre and fails all 21 rows
                // at once. TWO consecutive excursions = a real re-aim →
                // reset the lock immediately (don't ride the miss-hold).
                val fresh = DBHEstimator.medianDepth(tapX, tapY, f, 2)
                val heldD = smoothedDistM
                var seededFromEma = false
                var dTapSeed: Float? = fresh
                if (preview?.locked == true && heldD != null) {
                    when {
                        fresh == null -> { dTapSeed = heldD; seededFromEma = true; tapJumpStreak = 0 }
                        kotlin.math.abs(fresh - heldD) > TAP_JUMP_M -> {
                            tapJumpStreak += 1
                            if (tapJumpStreak >= 2) {
                                // Real aim change: cleanly restart on fresh.
                                missStreak = 0; rawDistM = null
                                smoothedDiaCm = null; smoothedDistM = null
                                smoothedHitAnchor = null; lockStreak = 0
                                tierStreak = 0; shownTier = ConfidenceTier.YELLOW
                                cylinderMarker = null; preview = null
                                carryWidths.clear(); tapJumpStreak = 0
                            } else {
                                dTapSeed = heldD; seededFromEma = true
                            }
                        }
                        else -> tapJumpStreak = 0
                    }
                } else {
                    tapJumpStreak = 0
                }
                val raw = if (dTapSeed == null) null else DBHEstimator.livePreview(
                    f, tapX, tapY, axis, calibration, chordAlgorithm,
                    dTapOverrideM = dTapSeed,
                    // Rolling row quorum only continues an established lock.
                    carryWidths = if (preview?.locked == true) carryWidths.flatten() else emptyList(),
                )
                // Feed this tick's own clean rows into the carry window.
                if (raw != null && raw.cleanRows > 0) {
                    carryWidths.addLast(raw.cleanWidths)
                    while (carryWidths.size > 2) carryWidths.removeFirst()
                }
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
                    // Cylinder anchor = the chord fit's OWN axis centre
                    // (strip midpoint back-projected at its depth + pushed
                    // one radius behind the front surface, computed in the
                    // estimator) — the SAME fit the bar is drawn from, so
                    // bar and cylinder agree in position and width by
                    // construction. The old anchor EMA'd a SEPARATE raycast
                    // (screenCenterHit): any bias or lag between that second
                    // depth source and the measurement's dTap scaled the
                    // cylinder's apparent width (∝ asin(r/z_axis)) and
                    // shifted it laterally off the bar — the field-reported
                    // intermittent 1.4–2× wide / off-centre cylinder.
                    // EMA (α=0.3) still irons out hand tremor, but a move
                    // beyond CYL_ANCHOR_SNAP_M SNAPS to the new centre so a
                    // stale near-depth anchor can't linger after the user
                    // moves.
                    raw.centerWorld?.let { c ->
                        val ph = smoothedHitAnchor
                        smoothedHitAnchor =
                            if (ph == null || distance(ph, c) > CYL_ANCHOR_SNAP_M) c
                            else Vec3(
                                0.3f * c.x + 0.7f * ph.x,
                                0.3f * c.y + 0.7f * ph.y,
                                0.3f * c.z + 0.7f * ph.z,
                            )
                    }
                    val anchor = smoothedHitAnchor
                    cylinderMarker = if (shownLocked && anchor != null) {
                        fun q(v: Float) = Math.round(v * 100f) / 100f   // 1 cm grid → stable equality
                        // Quantise the DIAMETER once (1 cm — the same grid
                        // the displayed value uses), then halve. The old
                        // q(rM) rounded the RADIUS to 1 cm, i.e. the
                        // rendered diameter to a 2 cm grid (11.1 cm drew
                        // as 12 — up to +18% width on small stems).
                        val rM = (q(sm / 100f) / 2f).coerceAtLeast(0.01f)
                        // 0.30 m tall, vertically CENTRED on the chord
                        // height: the anchor y IS the guide-line height and
                        // SceneView cylinders extend ±height/2 around their
                        // position (field fix — the old 1.0 m sleeve dwarfed
                        // the measure line).
                        ArSceneMarker(
                            Vec3(q(anchor.x), q(anchor.y), q(anchor.z)),
                            MarkerShape.Cylinder(rM, 0.30f),
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
                    carryWidths.clear()
                    tapJumpStreak = 0
                    preview = raw
                }
                if (settings.developerMode) {
                    val isRow = axis is GuideAxis.Row
                    devDepth = "${f.width}×${f.height}"
                    // Edge legibility + miss diagnosis: found width / clean
                    // rows on good ticks; the failing STAGE on miss ticks
                    // (tap— = centre-median hole, range = dTap gate,
                    // r n/5 = row quorum, OFF-FRAME = border clips). "ema"
                    // marks ticks bridged by the EMA-seeded tap depth.
                    val ema = if (seededFromEma) " ema" else ""
                    devEdge = when {
                        raw == null -> if (fresh == null) "miss tap—" else "miss —"
                        raw.edgesClipped ->
                            String.format(Locale.US, "OFF-FRAME %d rows", raw.clippedRows)
                        raw.widthPx > 0 -> String.format(
                            Locale.US, "L✓R✓ w%d r%d%s%s", raw.widthPx, raw.cleanRows,
                            if (raw.clippedRows > 0) " c${raw.clippedRows}" else "", ema,
                        )
                        raw.distanceM !in 0.4f..3.5f ->
                            String.format(Locale.US, "miss range %.1fm", raw.distanceM)
                        else -> String.format(
                            Locale.US, "miss r%d/5 c%d%s", raw.cleanRows, raw.clippedRows, ema,
                        )
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

    // ADJUST live estimate: the handle span (view px → depth px via the
    // frame's own view↔depth affine) + the median depth inside the bracket
    // at the guide row, refreshed on the same cadence as the auto preview.
    LaunchedEffect(stage, adjustMode, depthBlocked) {
        while (stage == Stage.AIMING && adjustMode && !depthBlocked) {
            controller.acquireDepthFrame()?.let { f ->
                val w = controller.viewWidthPx.toFloat()
                val cy = controller.viewHeightPx / 2f
                adjustPreview = if (w > 1f) {
                    DBHEstimator.constrainedEstimate(
                        f, adjustLeftFrac * w, adjustRightFrac * w, cy, calibration,
                    )
                } else null
            }
            delay(150)
        }
    }

    // Border chip (D — cruise tally only): live distance to the plot
    // boundary from the AR anchor (|camera→centre| − radius), the sampling
    // machinery's 5 Hz cadence. Non-null only within 2.0 m of the boundary:
    // first = inside?, second = metres to the boundary line.
    var borderChip by remember { mutableStateOf<Pair<Boolean, Double>?>(null) }
    LaunchedEffect(Unit) {
        val cruise = CruiseCapture.target ?: return@LaunchedEffect
        // Cruise plots know their real radius (denormalized acre area) —
        // same source as the mini-map's radiusOverrideM.
        val radiusM = runCatching { env.plotRepository.read(cruise.plotId) }.getOrNull()
            ?.plotAreaAcres?.toDouble()
            ?.let { kotlin.math.sqrt(Units.acresToSquareMeters(it) / Math.PI) }
            ?.takeIf { it.isFinite() && it > 0.5 }
        if (radiusM == null) return@LaunchedEffect
        while (true) {
            // The anchor may only stand in for THIS plot's centre when the
            // cruise Start-plot save linked them (mini-map rule).
            val anchor = if (ArSessionHub.linkedCruisePlotId == cruise.plotId) {
                ArSessionHub.plotCenterWorld()
            } else {
                null
            }
            val cam = controller.currentCameraPosition()
            borderChip = if (anchor != null && cam != null) {
                val dx = cam.x - anchor.x
                val dz = cam.z - anchor.z
                val d = kotlin.math.sqrt((dx * dx + dz * dz).toDouble())
                val toBoundary = kotlin.math.abs(d - radiusM)
                // iOS parity: outside at signed ≥ 0 (d ≥ radius).
                if (toBoundary <= 2.0) (d < radiusM) to toBoundary else null
            } else {
                null
            }
            delay(200)
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
        resultFromAdjust = false
        lastCaptureFailure = null
        truthSaveFailure = null
        scope.launch {
            val samples = ArrayList<DBHResult>()
            var firstRed: DBHResult? = null      // surface the FIRST red (matches iOS)
            // Raw-capture: retain the first usable sub-sample's frames (+ its
            // tap/axis) so the burst can be serialized for offline replay.
            var recFrames: List<ArDepthFrame>? = null
            var recTapX = 0.0; var recTapY = 0.0; var recAxisRow = false
            var recRgb: ByteArray? = null
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
                if (frames.isEmpty()) continue
                val f0 = frames.first()
                // Same crosshair→depth-pixel mapping as the live preview so
                // the committed burst reads the exact spot the cruiser aimed.
                val tap = f0.viewToDepth(controller.viewWidthPx / 2f, controller.viewHeightPx / 2f)
                val tapX = tap?.first ?: (f0.width / 2.0)
                val tapY = tap?.second ?: (f0.height / 2.0)
                // Auto-pick the across-the-trunk axis (fixes severe under-read
                // when the sensor orientation made the strip run along the trunk).
                val axis = DBHEstimator.pickGuideAxis(f0, tapX, tapY, calibration)
                // Latch the RAW window BEFORE the 5-frame estimator gate: a
                // dusk/canopy window that can't be estimated is exactly the
                // data the corpus needs, and dropping it here is what made
                // whole captures vanish silently.
                if (recFrames == null && rawCaptureArmed) {
                    recFrames = frames.toList()
                    recTapX = tapX; recTapY = tapY; recAxisRow = axis is GuideAxis.Row
                    recRgb = captureReferenceJpeg()
                }
                // The live estimate still needs its 5-frame window.
                if (frames.size < 5) continue
                // Chord (silhouette-width) method = median of the SAME per-frame
                // chord the live preview shows, so preview ≈ recorded value.
                val sub = DBHEstimator.estimateChord(frames, tapX, tapY, axis, calibration, chordAlgorithm)
                    ?: continue
                if (sub.confidence == ConfidenceTier.RED) {
                    if (firstRed == null) firstRed = sub
                } else samples.add(sub)
            }
            sampleProgress = 0
            val rec = recFrames
            if (rec != null) {
                recordRawDbh(rec, recTapX, recTapY, recAxisRow, bracket = null, rgb = recRgb)
            } else if (rawCaptureArmed) {
                lastCaptureFailure = "no depth frames in this burst"
                postRawStatus(RawCaptureStatus(
                    RawCaptureStrings.notSaved(lastCaptureFailure), false))
            }
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

    // ADJUST capture: the SAME 5-sub-sample burst shape as the auto path —
    // each sub-sample is a short frame window whose per-frame CONSTRAINED
    // chords (handle span + median bracket depth) are medianed; the shared
    // aggregator then trims/averages the sub-samples, so σ comes from the
    // cross-sample spread exactly like an auto capture (iOS
    // bracketChordEstimate + aggregateSamples parity). The automatic edge
    // search never runs. The handle fractions are latched at tap time so a
    // mid-burst drag can't change what this capture measures.
    fun captureAdjust() {
        if (stage == Stage.CAPTURING) return
        if (adjustPreview?.locked != true) return
        stage = Stage.CAPTURING
        failure = null
        resultFromAdjust = true
        lastCaptureFailure = null
        truthSaveFailure = null
        val lockedLeftFrac = adjustLeftFrac
        val lockedRightFrac = adjustRightFrac
        scope.launch {
            val subs = ArrayList<DBHResult>()
            var firstRed: DBHResult? = null      // surface the FIRST red (matches iOS)
            // Raw-capture: retain the first usable sub-sample's frames + the
            // latched view-space bracket for offline replay.
            var recFrames: List<ArDepthFrame>? = null
            var recBracket: RawCaptureStore.BracketSpec? = null
            var recRgb: ByteArray? = null
            for (k in 1..SAMPLE_COUNT) {
                sampleProgress = k
                // Same ~0.5 s window per sub-sample as the auto burst.
                val frames = ArrayList<ArDepthFrame>()
                var attempts = 0
                while (attempts < 24 && frames.size < 10) {
                    controller.acquireDepthFrame()?.let { frames.add(it) }
                    attempts++
                    delay(50)
                }
                if (frames.isEmpty()) continue
                val vw = controller.viewWidthPx.toFloat()
                val cy = controller.viewHeightPx / 2f
                if (vw <= 1f) continue
                // Latch the raw window BEFORE the estimator's 5-frame gate
                // (same reasoning as the auto burst) + the reference RGB.
                if (recFrames == null && rawCaptureArmed) {
                    recFrames = frames.toList()
                    recBracket = RawCaptureStore.BracketSpec(
                        lockedLeftFrac * vw, lockedRightFrac * vw, cy)
                    recRgb = captureReferenceJpeg()
                }
                if (frames.size < 5) continue
                val diameters = ArrayList<Double>(frames.size)
                var spanPxSum = 0
                for (f in frames) {
                    val est = DBHEstimator.constrainedEstimate(
                        f, lockedLeftFrac * vw, lockedRightFrac * vw, cy, calibration,
                    ) ?: continue
                    diameters.add(est.diameterCm.toDouble())
                    spanPxSum += est.nPoints
                }
                if (diameters.size < 3) {
                    if (firstRed == null) {
                        firstRed = DBHResult(
                            diameterCm = 0f, centerX = 0f, centerZ = 0f,
                            arcCoverageDeg = 0f, rmseMm = 0f, sigmaRmm = 0f,
                            nInliers = diameters.size, confidence = ConfidenceTier.RED,
                            method = DBHMethod.LIDAR_CHORD_SILHOUETTE,
                            rejectionReason = "Not enough usable frames; hold steadier or move closer",
                        )
                    }
                    continue
                }
                diameters.sort()
                val medianCm = diameters[diameters.size / 2]
                // Frame-to-frame agreement grades the sub-sample (iOS
                // bracketChordEstimate: range/mean ≤ 15% ⇒ green).
                val mean = diameters.average()
                val cov = if (mean > 0) (diameters.last() - diameters.first()) / mean else 1.0
                subs.add(
                    DBHResult(
                        diameterCm = medianCm.toFloat(), centerX = 0f, centerZ = 0f,
                        arcCoverageDeg = 0f, rmseMm = 0f, sigmaRmm = 0f,
                        nInliers = spanPxSum,
                        confidence = if (cov <= 0.15) ConfidenceTier.GREEN else ConfidenceTier.YELLOW,
                        method = DBHMethod.LIDAR_CHORD_SILHOUETTE, rejectionReason = null,
                    ),
                )
            }
            sampleProgress = 0
            val rf = recFrames; val rb = recBracket
            if (rf != null && rb != null) {
                recordRawDbh(rf, tapX = 0.0, tapY = 0.0, axisRow = false, bracket = rb, rgb = recRgb)
            } else if (rawCaptureArmed) {
                lastCaptureFailure = "no depth frames in this burst"
                postRawStatus(RawCaptureStatus(
                    RawCaptureStrings.notSaved(lastCaptureFailure), false))
            }
            val agg = DBHEstimator.aggregateSamples(subs)
            if (agg == null) {
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

    // Accept the on-screen result — records the entry (photo + GPS fix),
    // fires the continuation / height chain, and logs the research CSV row.
    // Shared by the result row's Accept and the manual-entry Save (iOS
    // submitManualEntry goes straight to .accepted).
    fun acceptResult(r: DBHResult) {
        // Auto-capture (map home): window snapshot as evidence of what was
        // measured + the latest GPS fix from the badge's running location
        // service.
        val activity = context as? android.app.Activity
        // Cruise tally session (v3): captured once so the whole accept
        // rides ONE consistent routing decision.
        val cruise = CruiseCapture.target
        // Snapshot the typed "True Ø" BEFORE anything async: the truth must
        // survive regardless of whether the bundle write has finished (the
        // store queues edits against the synchronously-minted id) — the old
        // code wrote truth only when the write had already completed, so a
        // fast Accept dropped it and the field was cleared anyway.
        val truthTextAtAccept = researchTrueCm
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
                    !rawCaptureArmed -> if (researchTrueCm == truthTextAtAccept) researchTrueCm = ""
                    // The capture itself failed — keep the typed value on
                    // screen rather than attach it to a bundle that isn't there.
                    lastCaptureFailure != null -> truthSaveFailure =
                        RawCaptureStrings.truthCaptureFailed(lastCaptureFailure)
                    rid == null -> if (researchTrueCm == truthTextAtAccept) researchTrueCm = ""
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
                                if (researchTrueCm == truthTextAtAccept) researchTrueCm = ""
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
                // Cruise mode: the SAME accept pedigree (value + σ + meta +
                // GPS + photo) lands on a cruise Tree row in the active
                // plot — the quick-measure history (and its map pins) never
                // sees cruise readings. A storage failure must not crash
                // the AR screen; the missing pin surfaces it on the map.
                runCatching {
                    CruiseCapture.recordDbh(
                        env, r,
                        speciesCode = metaSpecies,
                        damageCodes = metaDamage,
                        note = metaNote,
                        photoPath = photo,
                        fix = fix,
                    )
                }
                // A. QUICK-TALLY LOOP: no Height leg, no pop, no
                // continuation — bump the session to the plot's next tree
                // number and reset this screen to aiming, with the Undo
                // toast (F) offering a 3 s take-back.
                CruiseCapture.advanceTally()
                cruiseTreeNumber = CruiseCapture.target?.treeNumber
                pendingTree = CruiseCapture.target?.treeNumber ?: pendingTree
                result = null
                failure = null
                metaSpecies = null
                metaPosition = StemPosition.DBH
                metaDamage = emptyList()
                metaNote = ""
                stage = Stage.AIMING
                undoToast = cruise.treeNumber
                undoEpoch += 1
            } else {
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
                        // Edge provenance: "manual" when the ADJUST bracket
                        // supplied the edges, "auto" otherwise (other measure
                        // kinds leave the column null).
                        captureMode = if (resultFromAdjust) "manual" else "auto",
                    )
                )
                // Full-measurement chain (quick-measure world): skip the
                // continuation dialog, go straight to Height on this tree.
                // Navigate AFTER the append (this scope dies with the
                // screen) and pop DBH so Height's continuation DONE
                // returns to the map.
                if (chainToHeight) {
                    nav.navigate("height?tree=$pendingTree") {
                        popUpTo(Routes.DBH_PATTERN) { inclusive = true }
                    }
                } else {
                    // Quick measure saved — no continuation prompt (iOS
                    // parity); return to the map once the save completes.
                    nav.popBackStack()
                }
            }
        }
        if (settings.developerMode) {
            val fields = mutableMapOf(
                "measure_type" to "dbh",
                "method" to r.method.raw,
                // Same raw vocabulary as iOS dbhMethodSource so the two
                // platforms' CSVs filter identically; the platform column
                // says whether "lidarDepth" means LiDAR or ARCore depth.
                "depth_source" to "lidarDepth",
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
            rawTrue?.let { t ->
                fields["true_value"] = String.format(Locale.US, "%.2f", t)
                fields["error"] = String.format(Locale.US, "%.2f", r.diameterCm - t)
            }
            ResearchLog.record(context, fields)
            // The field is NOT cleared here any more — the accept coroutine
            // above clears it only once the truth is durably applied.
        }
    }

    // F. Undo toast action: delete the just-saved tree row + its photo and
    // step the auto number back (hard delete — the tally never happened).
    fun undoTally() {
        val activity = context as? android.app.Activity
        scope.launch {
            val deleted = CruiseCapture.undoLastTally(env)
            if (deleted != null) {
                val name = deleted.photoPath
                if (name != null && activity != null) {
                    runCatching { MeasurePhotoStore.delete(activity, name) }
                }
                cruiseTreeNumber = CruiseCapture.target?.treeNumber
                pendingTree = CruiseCapture.target?.treeNumber ?: pendingTree
            }
            undoToast = null
        }
    }

    val locked = stage == Stage.AIMING && preview?.locked == true

    Box(Modifier.fillMaxSize()) {
        // Live trunk-cylinder marker only while aiming (mirrors the iOS DBH
        // cylinder overlay).
        val dbhMarkers = if (stage == Stage.AIMING) {
            listOfNotNull(cylinderMarker)
        } else {
            emptyList()
        }
        // The AR host only runs while the scan can: with the depth blocker
        // up there is nothing to see or measure, so composing it would just
        // burn a hidden ARCore session. Cached-unsupported entries never
        // attach at all (no probe session); a live negative report flips
        // depthBlocked mid-session, detaching this host — which pauses the
        // shared session while the blocker is displayed (other screens'
        // attach/resume semantics unchanged).
        if (!depthBlocked) ArCameraView(
            controller,
            dbhMarkers,
            // The DBH scan is depth-only, so always prefer depth hits over
            // plane hits (applied on attach + on change, so the shared
            // controller can't keep a stale value from another screen).
            preferDepth = true,
            modifier = Modifier.fillMaxSize(),
            // Active sampling plot (if any) as a subdued, non-interactive
            // overlay so the cruiser can see the boundary while measuring.
            plotOverlay = ArSessionHub.PlotOverlay.SUBDUED,
        )

        // ADJUST-mode drag catcher: a drag grabs whichever handle is nearer
        // at drag start (hit target = the nearer half of the screen, well
        // over 44 dp). Early in the Box so the rail / status panel (later
        // children) still win their own touches.
        if (adjustMode && stage == Stage.AIMING) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        var draggingLeft = true
                        detectDragGestures(
                            onDragStart = { pos ->
                                val w = size.width.toFloat().coerceAtLeast(1f)
                                draggingLeft =
                                    kotlin.math.abs(pos.x - adjustLeftFrac * w) <=
                                    kotlin.math.abs(pos.x - adjustRightFrac * w)
                            },
                        ) { change, dragAmount ->
                            change.consume()
                            val w = size.width.toFloat().coerceAtLeast(1f)
                            val df = dragAmount.x / w
                            if (draggingLeft) {
                                adjustLeftFrac = (adjustLeftFrac + df)
                                    .coerceIn(0.02f, adjustRightFrac - 0.04f)
                            } else {
                                adjustRightFrac = (adjustRightFrac + df)
                                    .coerceIn(adjustLeftFrac + 0.04f, 0.98f)
                            }
                        }
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

        // Plot mini-map — top-right, same row as the GPS badge: the active
        // cruise plot (ring + YOU + measured trees) or the quick sampling
        // ring (ring + YOU). Hidden with the rest of the 2D chrome during
        // the Accept snapshot blackout.
        val miniMapUp = scanPlotMiniMapVisible()
        if (!hidingChromeForCapture) ScanPlotMiniMap()

        // Raw-capture recording state: a persistent REC pill while the
        // recorder is REALLY armed (the hub's ref-counted token set, not the
        // Settings wish — the pill used to stay red through an arm clobber
        // while nothing was being recorded), and the last attempt's outcome
        // (saved / NOT saved) directly under it.
        if (!hidingChromeForCapture) {
            RawCaptureBadge(
                armed = ArSessionHub.rawDepthArmed,
                requested = rawCaptureArmed,
                storageLow = storageLow,
                status = rawCaptureStatus,
            )
        }

        // Cruise tally target pill — top-centre on the GPS-badge row, the
        // auto tree number the next Accept saves to ("Tree 8", updating).
        // iOS tallyTargetPill 1:1 (13 bold mono, black 0.65 capsule).
        cruiseTreeNumber?.let { n ->
            if (!hidingChromeForCapture) {
                Text(
                    "Tree $n",
                    style = TextStyle(
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                    ),
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 22.dp)
                        .shadow(3.dp, CircleShape, clip = false)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.65f))
                        .border(0.5.dp, Color.White.copy(alpha = 0.18f), CircleShape)
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }
        }

        // D. Border chip — small dark-glass pill directly UNDER the
        // mini-map card (22 top + 116 card + 6 gap), only within 2.0 m of
        // the plot boundary: live "Border 1.3 m" inside (white) /
        // "Outside plot" (warn) outside. iOS borderChip 1:1.
        borderChip?.let { (inside, toBoundary) ->
            if (!hidingChromeForCapture) {
                Text(
                    if (inside) {
                        String.format(Locale.US, "Border %.1f m", toBoundary)
                    } else {
                        "Outside plot"
                    },
                    style = TextStyle(
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                    ),
                    color = if (inside) Color.White else colors.confidenceWarn,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 144.dp, end = 16.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.65f))
                        .border(0.5.dp, Color.White.copy(alpha = 0.18f), CircleShape)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }

        if (settings.developerMode && !hidingChromeForCapture) {
            val p = preview
            DevHud(
                "DBH",
                listOfNotNull(
                    "depth" to (if (controller.supportsDepth) "ARCore✓" else "plane"),
                    "track" to (if (controller.trackingOk()) "OK" else "…"),
                    // Recorder state — an explicit warning when developer
                    // mode is on but nothing is being kept for the corpus.
                    "rec" to when {
                        !settings.rawCaptureEnabled -> "OFF — not recording"
                        ArSessionHub.rawDepthArmed -> "armed"
                        else -> "off"
                    },
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
                // Below the plot mini-map when it occupies the top-right
                // slot (22 + 116 card + 12 gap); the cruise border chip
                // claims the first slot under the card, so cruise sessions
                // push the HUD one row further down.
                topPadding = when {
                    cruiseTreeNumber != null -> 180.dp
                    miniMapUp -> 150.dp
                    else -> 56.dp
                },
            )
        }

        // Guide line + live fit chord (drawn relative to screen centre).
        // All scanning chrome is suppressed while the depth blocker is up.
        if (stage != Stage.RESULT && !depthBlocked) {
            Canvas(Modifier.fillMaxSize()) {
                val cy = size.height / 2f
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
                if (!adjustMode && locked && p != null && p.stripRightFraction > p.stripLeftFraction) {
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
                // ADJUST bracket: subtle band highlight between the
                // handles, a chord bar tracking the handles exactly, and
                // two white draggable handle lines with grab circles
                // (dark halos for sun-glare legibility).
                if (adjustMode) {
                    val lx = size.width * adjustLeftFrac
                    val rx = size.width * adjustRightFrac
                    drawRect(
                        Color.White.copy(alpha = 0.10f),
                        topLeft = Offset(lx, cy - 44.dp.toPx()),
                        size = Size(rx - lx, 88.dp.toPx()),
                    )
                    drawLine(
                        colors.confidenceOk.copy(alpha = 0.95f),
                        Offset(lx, cy), Offset(rx, cy),
                        strokeWidth = 4.dp.toPx(),
                    )
                    // White 2 dp handle lines (88 dp tall, black halo)
                    // with Ø14 grab circles — iOS adjustHandle parity.
                    val half = 44.dp.toPx()
                    for (x in listOf(lx, rx)) {
                        drawLine(
                            Color.Black.copy(alpha = 0.45f),
                            Offset(x, cy - half), Offset(x, cy + half),
                            strokeWidth = 4.dp.toPx(),
                        )
                        drawLine(
                            Color.White,
                            Offset(x, cy - half), Offset(x, cy + half),
                            strokeWidth = 2.dp.toPx(),
                        )
                        drawCircle(Color.White, radius = 7.dp.toPx(), center = Offset(x, cy))
                        drawCircle(
                            Color.Black.copy(alpha = 0.35f),
                            radius = 7.dp.toPx(), center = Offset(x, cy),
                            style = Stroke(width = 1.dp.toPx()),
                        )
                    }
                }
            }

            // Crosshair ring — green locked / red aligning (spec §5.2).
            // During the capture burst it holds green and gains a
            // determinate progress arc (3 dp, confidenceOk) sweeping
            // 0→360° across the 5 frames.
            val ringLocked = if (adjustMode) adjustPreview?.locked == true else locked
            val burstRunning = stage == Stage.CAPTURING
            val arcProgress by animateFloatAsState(
                targetValue = if (burstRunning) maxOf(1, sampleProgress) / SAMPLE_COUNT.toFloat() else 0f,
                animationSpec = tween(durationMillis = 450, easing = LinearEasing),
                label = "captureArc",
            )
            DbhRing(
                ringLocked || burstRunning,
                colors.confidenceOk, colors.confidenceBad,
                progress = if (burstRunning) arcProgress else null,
                modifier = Modifier.align(Alignment.Center),
            )
            // Device-tilt badge floating just above the ring so the
            // cruiser sees level at the same focal point (iOS TiltBadge
            // at midY − ringRadius − 22). Both badges are 2D chrome, so
            // the accept snapshot drops them (ring + chord stay).
            if (!hidingChromeForCapture) {
                Box(Modifier.align(Alignment.Center).offset(y = (-58).dp)) {
                    TiltBadge(controller)
                }
                // Directly under the crosshair: the capture-progress
                // pill while the burst runs (flips the instant the
                // shutter is tapped, updates per frame) — U2 kept it
                // here; the live DBH/distance readouts moved to the
                // value strip above the shutter row.
                if (burstRunning) {
                    Box(Modifier.align(Alignment.Center).offset(y = 64.dp)) {
                        CapturingPill(maxOf(1, sampleProgress))
                    }
                }
            }
        }

        // 12 dp above the bottom block: the Undo toast (F), then the ADJUST
        // exit pill while the bracket is up.
        val showAutoPill = stage == Stage.AIMING && adjustMode
        val aboveBottomBlock: (@Composable () -> Unit)? =
            if (undoToast != null || showAutoPill) {
                {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        undoToast?.let { savedTree ->
                            UndoToastPill(savedTree) { undoTally() }
                        }
                        if (showAutoPill) {
                            AutoModePill {
                                adjustMode = false
                                adjustPreview = null
                            }
                        }
                    }
                }
            } else {
                null
            }

        // U2 — bottom-centre shutter row for the AIMING states: "Type"
        // left, shutter centre, "Adjust" right, the live DBH/distance
        // readouts in the strip directly above.
        if (stage == Stage.AIMING && !depthBlocked && !hidingChromeForCapture && !manualOpen) {
            MeasureShutterBar(
                onCapture = if (adjustMode) ({ captureAdjust() }) else ({ capture() }),
                left = {
                    MeasureCircleButton(icon = Icons.Filled.Keyboard, caption = "Type") {
                        manualOpen = true; manualText = ""
                    }
                },
                right = if (!adjustMode) {
                    {
                        // Horizontal unfold (↕ rotated 90° ≈ the iOS
                        // "arrow.left.and.right") — manual edge placement
                        // for trunks the auto edge-finder struggles with.
                        MeasureCircleButton(
                            icon = Icons.Filled.UnfoldMore,
                            caption = "Adjust",
                            iconRotation = 90f,
                        ) {
                            // Handles start at the current auto edges when
                            // a fit is up, else ±25% of the screen width
                            // around centre.
                            val p = preview
                            if (p != null && p.locked &&
                                p.stripRightFraction > p.stripLeftFraction
                            ) {
                                adjustLeftFrac = p.stripLeftFraction.coerceIn(0.02f, 0.90f)
                                adjustRightFrac = p.stripRightFraction
                                    .coerceIn(adjustLeftFrac + 0.04f, 0.98f)
                            } else {
                                adjustLeftFrac = 0.25f
                                adjustRightFrac = 0.75f
                            }
                            adjustPreview = null
                            // Park the auto preview so its chrome (chord,
                            // badge, cylinder) can't linger under the
                            // bracket.
                            preview = null; rawDistM = null
                            smoothedDiaCm = null; smoothedDistM = null
                            smoothedHitAnchor = null
                            lockStreak = 0; missStreak = 0
                            shownTier = ConfidenceTier.YELLOW; tierStreak = 0
                            cylinderMarker = null
                            carryWidths.clear(); tapJumpStreak = 0
                            adjustMode = true
                        }
                    }
                } else {
                    null
                },
                valueStrip = {
                    if (adjustMode) {
                        LivePreviewBadge(
                            adjustPreview, adjustPreview?.locked == true, settings.unitSystem)
                    } else {
                        LivePreviewBadge(preview, locked, settings.unitSystem)
                    }
                },
                above = aboveBottomBlock,
            )
        }

        // U1 — stage guidance + failure, top-centre banner (clears the
        // GPS-badge / mini-map row).
        if (!depthBlocked && !hidingChromeForCapture) MeasureTopChrome(
            instruction = when {
                manualOpen -> "Enter diameter manually in cm."
                stage == Stage.AIMING -> when {
                    // ADJUST keeps the standard aligning/armed copy
                    // (iOS statusText parity): armed as soon as a
                    // bracket fit exists.
                    adjustMode ->
                        if (adjustPreview?.locked == true) "Hold steady, then tap + to capture."
                        else "Align the guide to the trunk's uphill side; hold steady."
                    locked -> "Hold steady, then tap + to capture."
                    // Border-touch invalidity: the silhouette walk ran off
                    // the image — the trunk's edges aren't in frame, so no
                    // fit can lock. Honest guidance instead of a lock.
                    preview?.edgesClipped == true ->
                        "Edges not found — adjust framing."
                    else -> "Align the guide to the trunk's uphill side; hold steady."
                }
                // Depth burst: the under-crosshair capture pill carries the
                // progress — no duplicate banner (iOS topBannerText parity).
                stage == Stage.CAPTURING -> null
                result?.confidence == ConfidenceTier.RED ->
                    result?.rejectionReason ?: "Scan rejected. Try again."
                else -> "Scan complete. Accept, retake, or add a second view."
            },
            failure = failure,
        )

        // Manual entry — typed diameter for trees the sensors can't read
        // (mirrors the iOS .manualEntry state, method "manualVisual").
        // Opened from the shutter row's Type button; the panel keeps the
        // field + action rows (the shutter row yields while typing).
        if (!depthBlocked && !hidingChromeForCapture && stage == Stage.AIMING && manualOpen) {
            MeasureStatusPanel(above = aboveBottomBlock) {
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
                                if (settings.unitSystem == UnitSystem.METRIC) "Diameter in cm"
                                else "Diameter in inches",
                            )
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                    // The field is typed in the ACTIVE unit system: under
                    // imperial the prompt says inches, so the number must be
                    // converted before it lands in diameterCm (it used to be
                    // stored raw — a silent 2.54x corruption).
                    val typed = TruthInput.parse(manualText)?.toFloat()
                    val typedCm = typed?.let {
                        if (settings.unitSystem == UnitSystem.METRIC) it
                        else Units.inchesToCm(it.toDouble()).toFloat()
                    }
                    ForestixProminentButton(
                        "Save",
                        enabled = (typedCm ?: 0f) > 0f,
                    ) {
                        val cm = typedCm
                        if (cm != null && cm > 0f) {
                            val r = DBHResult(
                                diameterCm = cm, centerX = 0f, centerZ = 0f,
                                arcCoverageDeg = 0f, rmseMm = 0f, sigmaRmm = 0f,
                                nInliers = 0, confidence = ConfidenceTier.YELLOW,
                                method = DBHMethod.MANUAL_VISUAL, rejectionReason = null,
                            )
                            result = r
                            resultFromAdjust = false
                            failure = null
                            manualOpen = false
                            // A typed diameter is NOT any recorded burst's
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

        // RESULT state — the existing result panel (values + dev fields +
        // Retake/Details/Accept) occupies the bottom as before; U2 shows
        // no shutter row here.
        if (!depthBlocked && !hidingChromeForCapture && stage == Stage.RESULT) {
            MeasureStatusPanel {
                result?.let { r ->
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
                        // formatter. Field fix: no σ and no tier chip here —
                        // σ stays recorded internally (history/CSV) and the
                        // tier still gates Accept; a red scan's reason shows
                        // in the status line above.
                        Text(
                            MeasurementFormatter.diameter(r.diameterCm.toDouble(), settings.unitSystem),
                            style = Forestix.type.dataLarge,
                            color = Color.White,
                        )
                    }
                    if (settings.developerMode) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            ResearchFieldsRow(
                                targetValue = settings.researchTreeId,
                                onTargetChange = { env.settings.setResearchTreeId(it.trim()) },
                                targetPlaceholder = "T1",
                                trueLabel = "True Ø (cm)",
                                trueValue = researchTrueCm,
                                // ',' is NORMALISED to '.', never deleted — the
                                // old digit filter turned "12,5" into "125".
                                onTrueChange = { researchTrueCm = TruthInput.sanitize(it) },
                                truePlaceholder = "tape",
                            )
                            // Live warning under the truth field: a failed save,
                            // unparseable text, or an implausible value.
                            (truthSaveFailure
                                ?: TruthInput.fieldWarning(researchTrueCm, isHeight = false))
                                ?.let { w -> TruthFieldWarning(w) }
                            // Queued-but-not-yet-durable truth: the field was
                            // deliberately NOT cleared, so say why.
                            if (truthSaveFailure == null) {
                                truthPending?.let { TruthFieldNote(it) }
                            }
                            // Developer mode on but the recorder off: nothing
                            // is being kept for the corpus. Say it here, where
                            // the truth is typed.
                            if (!settings.rawCaptureEnabled) RawCaptureOffNotice()
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ForestixWhiteButton("Retake", modifier = Modifier.weight(1f)) {
                        result = null; failure = null; stage = Stage.AIMING
                        // A discarded burst must not collect the NEXT
                        // reading's accept flag or ground truth.
                        lastRawCaptureId = null
                    }
                    ForestixWhiteButton("Details", modifier = Modifier.weight(1f)) {
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
        }

        // No Depth API + developer mode off → the scan can't run: replace
        // the whole scanning UI with a full-page canvas blocker (iOS
        // lidarRequiredPanel presentation). No AR session runs behind the
        // opaque page — the host above is gated out, and a cached verdict
        // skips the probe entirely on re-entry.
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

    }
}

@Composable
private fun DbhRing(locked: Boolean, ok: Color, bad: Color, progress: Float?, modifier: Modifier) {
    // Red until the depth fit stabilises, then green (spec §5.2 / iOS
    // crosshairRing confidenceBad → confidenceOk). `progress` (0–1, non-null
    // only during the capture burst) draws a determinate arc sweeping
    // 0→360° over the ring so the burst is visibly running.
    val ring = if (locked) ok else bad
    Box(modifier.size(72.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(72.dp).border(5.dp, Color.Black.copy(alpha = 0.6f), CircleShape))
        Box(Modifier.size(64.dp).border(2.5.dp, ring, CircleShape))
        if (progress != null) {
            Canvas(Modifier.size(72.dp)) {
                // Rides the inner (64 dp) ring — iOS draws the arc on a
                // 61.5 pt circle inside its 64 pt ring.
                val stroke = 3.dp.toPx()
                val d = 61.5.dp.toPx()
                val inset = (size.width - d) / 2f
                drawArc(
                    color = ok,
                    startAngle = -90f,
                    sweepAngle = 360f * progress.coerceIn(0f, 1f),
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = Size(d, d),
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
    }
}

/// Burst-progress pill floating directly under the crosshair — same pill
/// style as the Height screen's aim labels (radius 4, black 0.65 fill,
/// dataSmall white). Flips on the instant the "+" is tapped and counts the
/// frames up.
@Composable
private fun CapturingPill(k: Int) {
    Text(
        "Capturing $k/$SAMPLE_COUNT — hold steady.",
        style = Forestix.type.dataSmall,
        color = Color.White,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color.Black.copy(alpha = 0.65f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/// F. Undo toast — dark-glass pill above the bottom controls after each
/// cruise tally Accept: "Tree 7 saved · Undo" with Undo as a tappable bold
/// segment; the caller clears it after 3 s (locked, matches iOS).
@Composable
private fun UndoToastPill(treeNumber: Int, onUndo: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .shadow(3.dp, CircleShape, clip = false)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.65f))
            .border(0.5.dp, Color.White.copy(alpha = 0.18f), CircleShape)
            .padding(start = 16.dp, end = 4.dp),
    ) {
        Text(
            "Tree $treeNumber saved · ",
            style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
            color = Color.White,
        )
        // Undo — the tappable bold segment, ≥ 44 dp hit target (iOS
        // tallyUndoToast 1:1).
        Box(
            Modifier
                .defaultMinSize(minWidth = 44.dp, minHeight = 44.dp)
                .clickableNoRipple(onUndo),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Undo",
                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold),
                color = Color.White,
            )
        }
    }
}

/// "Auto" exit pill shown 12 dp above the status panel while the ADJUST
/// edge-bracket is up — black-0.55 capsule, white 12 sp semibold.
@Composable
private fun AutoModePill(onClick: () -> Unit) {
    Text(
        "Auto",
        style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
        color = Color.White,
        modifier = Modifier
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.55f))
            .border(0.5.dp, Color.White.copy(alpha = 0.18f), CircleShape)
            .clickableNoRipple(onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

@Composable
private fun LivePreviewBadge(
    preview: DBHEstimator.DbhPreview?,
    locked: Boolean,
    unitSystem: UnitSystem,
) {
    val p = preview ?: return
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (locked) {
            // Bare DBH digit — field fix removed the live tier chip; the
            // tier still drives lock gating internally. dataLarge in the
            // U2 value strip (iOS liveValueStrip parity).
            MeasureValuePill(
                "DBH: " + MeasurementFormatter.diameter(p.diameterCm.toDouble(), unitSystem),
                large = true,
            )
        }
        if (p.distanceM > 0f) {
            MeasureValuePill(
                "Distance: " + MeasurementFormatter.distance(p.distanceM.toDouble(), unitSystem),
                dimmed = true,
            )
        }
    }
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
