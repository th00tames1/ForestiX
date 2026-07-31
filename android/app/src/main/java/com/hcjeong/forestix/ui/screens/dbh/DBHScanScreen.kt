// Tree Diameter (DBH) — Android, with a live HUD matching iOS: a horizontal
// guide line, a crosshair ring that turns green when a trunk fit locks, a
// live "DBH x.x cm / Distance y.y m" badge, and a green fit-width chord
// drawn across the trunk. The committed reading still runs the full §7.1
// burst estimator (DBHEstimator) on Accept.

package com.hcjeong.forestix.ui.screens.dbh

import android.os.SystemClock
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
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
import com.hcjeong.forestix.data.clampBracketHalfWidth
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
import com.hcjeong.forestix.data.cruise.TreeLabel
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
import com.hcjeong.forestix.ui.screens.MeasurePillSize
import com.hcjeong.forestix.ui.screens.MeasureValuePill
import com.hcjeong.forestix.ui.screens.PlotPinCentreCard
import com.hcjeong.forestix.ui.screens.rememberPlotPinCentreOffer
import com.hcjeong.forestix.ui.screens.RawCaptureBadge
import com.hcjeong.forestix.ui.screens.RawCaptureOffNotice
import com.hcjeong.forestix.ui.screens.RawCaptureStatus
import com.hcjeong.forestix.ui.screens.RawCaptureStrings
import com.hcjeong.forestix.ui.screens.ResearchFieldsRow
import com.hcjeong.forestix.ui.screens.tree.TreeFormWords
import com.hcjeong.forestix.ui.screens.tree.treeBasalAreaText
import com.hcjeong.forestix.ui.screens.TruthFieldNote
import com.hcjeong.forestix.ui.screens.TruthFieldWarning
import com.hcjeong.forestix.ui.screens.ScanPlotMiniMap
import com.hcjeong.forestix.ui.screens.TiltBadge
import com.hcjeong.forestix.ui.screens.scanPlotMiniMapVisible
import com.hcjeong.forestix.ui.screens.scanPanelTextFieldColors
import com.hcjeong.forestix.ui.clickableNoRipple
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixProminentButton
import com.hcjeong.forestix.ui.theme.ForestixSpace
import com.hcjeong.forestix.ui.theme.ForestixWhiteButton
import com.hcjeong.forestix.ui.screens.cruise.freshFixOrNull
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

/// FIELD REPORT 15 — how long the screen aims without producing a single
/// fit before it stops repeating "align and hold steady" and says what to
/// do instead. ARCore's depth wants parallax, and whether it gets any is
/// dominated by how far the cruiser is standing from the stem; the cruiser
/// found that a gentle sway or a step in/out clears it, but nothing on
/// screen said so. Two seconds is long enough that a normal acquisition
/// never trips it and short enough to answer someone who is already
/// wondering why the number won't come up.
private const val ACQUISITION_STALL_MS = 2_000L

/// The hint itself. Byte-identical to the iOS sibling
/// (`DBHScanScreen.acquisitionStallHint`).
private const val ACQUISITION_STALL_HINT =
    "No depth lock yet — move the phone gently side to side, or change your distance."

/// The tap-depth window `DBHEstimator.livePreview` will produce a lock in.
/// The screen only READS it — to pick which sentence a refused "+" gets, and
/// to label the dev HUD's range miss. The estimator stays the single place
/// that decides whether a frame can be measured; this must not become a
/// second gate in front of it (that is precisely the bug being fixed on iOS,
/// where a screen-side 0.5–3.0 m arm gate outlived the estimator window it
/// was written to match).
private val PREVIEW_RANGE_M: ClosedFloatingPointRange<Float> = 0.4f..3.5f

/// A "+" that refuses must say why: a capture button that does nothing is
/// indistinguishable from a broken one, which is the same defect that was
/// fixed on the height anchor tap (`HeightScanScreen.CAMERA_NOT_READY`).
/// Byte-identical to the iOS `DBHScanViewModel` constants of the same names.
///
/// None of them names the metres of the window. iOS arms over its estimator's
/// 0.3–5.0 m and this screen over `PREVIEW_RANGE_M`, so a sentence quoting a
/// number could not be the same sentence on both platforms — and the
/// direction to walk is the part the cruiser can act on.
private const val TOO_FAR_TEXT =
    "Too far from the trunk to read depth — step closer, then tap + again."
private const val TOO_CLOSE_TEXT =
    "Too close to the trunk to read depth — step back, then tap + again."
private const val NO_TRUNK_LOCK_TEXT =
    "No trunk lock yet — hold the crosshair on the bark until a diameter shows, then tap + again."

/// The silhouette walk ran off the image: the trunk's edges aren't in frame.
/// Hoisted out of the instruction banner so a refused "+" can repeat the
/// banner's sentence WORD FOR WORD — two different remedies on screen for one
/// failure is worse than one.
private const val EDGES_CLIPPED_TEXT =
    "Can't see both sides of the trunk — step back so the whole trunk is in view."

/// How long without a single depth frame before everything the banner could
/// say about `preview` / `adjustPreview` is treated as describing a frame that
/// no longer exists. Both preview loops write those only inside the frame
/// block, so an ARCore stall (interruption, thermal throttle, tracking loss)
/// freezes the last one — and a frozen locked preview would otherwise keep
/// telling the cruiser to "hold steady, then tap +" over a dead screen, and
/// keep the stall clock from ever running. Three missed 150 ms ticks, so one
/// dropped frame is not an outage; matches the iOS `depthSilentSec`.
private const val DEPTH_SILENT_MS = 500L

/// How long the ADJUST bracket may keep reading two surfaces before the held
/// diameter stops being current enough to show. Long enough that the
/// intermittent excursion never reaches it (it is one or two ticks), short
/// enough that a cruiser who has walked the bracket off the stem is told so
/// while they are still holding it there. iOS holds the identical value in
/// `DBHScanViewModel.bracketUnsettledGraceSec`.
private const val BRACKET_UNSETTLED_GRACE_MS = 1_000L

/// Advice for a bracket spanning the stem AND what is behind it. Byte
/// identical to the iOS `DBHScanViewModel.bracketTwoSurfacesText`.
///
/// NOT "widen it": a wider bracket takes in more background and more diameter
/// at once, which is the same trap the sibling depth-advice line documents.
private const val BRACKET_TWO_SURFACES =
    "The bracket is reading past the trunk — narrow it onto the bark, or step closer."

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

/// Owner sentinel for a truth kept on screen for a measurement that produced
/// NO usable bundle (the capture itself failed, so its id was dropped). Never
/// a real bundle id, so the cross-tree guard in `acceptResult` still fires.
/// iOS calls the same sentinel `noBundleOwner`.
private const val TRUTH_NO_BUNDLE_OWNER = "no-bundle"

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
    // Map-home tree lock ("Measure this tree again" / chooser rows) hands the
    // tree number — and the name and species the cruiser typed in the chooser
    // — over via PendingTreeNumber. Consume it once so the accepted reading
    // lands on the promised tree; otherwise pick the next free number (iOS
    // pendingTreeNumber parity).
    val pendingLock = remember { PendingTreeNumber.consume() }
    var pendingTree by remember {
        mutableStateOf(pendingLock?.number ?: env.history.suggestedNextTreeNumber)
    }
    // Manual DBH entry (typed cm) — mirror of the iOS .manualEntry state.
    var manualOpen by remember { mutableStateOf(false) }
    var manualText by remember { mutableStateOf("") }
    // Measurement-snapshot chrome blackout: while true, every 2D panel/button
    // is hidden so the captured JPEG shows only the AR feed + measurement
    // overlays (captured buttons read as real buttons in the photo viewer).
    var hidingChromeForCapture by remember { mutableStateOf(false) }
    // The JPEG taken THE INSTANT THE 5-FRAME BURST FINISHED, held here until
    // Accept attaches it to the reading.
    //
    // FIELD REPORT: the shutter used to sit in the Accept handler, and by the
    // time the cruiser has read the result panel and decided, the phone is
    // down at their side — every stored photo was leaf litter and boots. The
    // frame worth keeping is the one taken with the bracket still on the stem.
    // Nothing about the stored measurement changes: this is still the value
    // that goes into QuickMeasureEntry.photoPath at Accept.
    //
    // It is a FILE, so every path that abandons it has to delete it (retake, a
    // superseding burst, leaving the screen). It is released without deleting
    // only once a reading has taken ownership of it. Same shape and the same
    // lifetime rules as iOS `DBHScanScreen.heldPhoto`.
    var heldPhoto by remember { mutableStateOf<String?>(null) }
    // Scan metadata (species / position / damage / note) attached on Accept.
    // Seeded from the chooser's species control when it was used, so the
    // details chip already reads the species the cruiser picked at the tree.
    var metaSpecies by remember { mutableStateOf(pendingLock?.speciesCode) }
    var metaPosition by remember { mutableStateOf<StemPosition?>(StemPosition.DBH) }
    var metaDamage by remember { mutableStateOf<List<String>>(emptyList()) }
    var metaNote by remember { mutableStateOf("") }
    var showMetadata by remember { mutableStateOf(false) }
    // Developer-mode research capture: the tape-measured true diameter, AS
    // TYPED. The unit is `truthUnit` below, never assumed — the field used to
    // be named (and read) as centimetres whatever the cruiser was working in.
    var researchTrueText by remember { mutableStateOf("") }
    // Cruise quick-tally loop (field-benchmark batch): the target tree number
    // shown in the top pill, mirrored into Compose state so advance/undo
    // recompose (CruiseCapture.target is a plain @Volatile holder).
    var cruiseTreeNumber by remember { mutableStateOf(CruiseCapture.target?.treeNumber) }
    /// What the pill calls that tree — the cruiser's name for it when they
    /// have a series running, else "Tree #<n>". Mirrored alongside the number
    /// rather than derived from it, because the number is also the raw-capture
    /// join key and must stay a plain Int.
    var cruiseTreeTitle by remember { mutableStateOf(CruiseCapture.target?.treeTitle) }
    /// Tally pill tapped — the rename field is up, holding the text being
    /// edited. Null means closed; cancelling leaves the pending name untouched.
    var renameDraft by remember { mutableStateOf<String?>(null) }
    // Undo toast (F): what the just-saved tree was called, cleared 3 s after
    // the epoch it was raised in (epoch restarts the timer on rapid tallies).
    // Held as the finished LABEL, not a number: the session advances both the
    // number and the name the instant the save lands, so recomputing it at
    // render time would name the NEXT tree instead of the one Undo removes.
    var undoToast by remember { mutableStateOf<String?>(null) }
    var undoEpoch by remember { mutableStateOf(0) }
    LaunchedEffect(undoEpoch) {
        if (undoToast != null) {
            delay(3_000)
            undoToast = null
        }
    }
    val colors = Forestix.colors
    // FIELD REPORT 14 × 17 — a cruise plot is being tallied but no AR anchor
    // marks its centre, so `PlotOverlay.SUBDUED` has nothing to draw and the
    // cruiser is looking at a bare camera feed with a plot open. Non-null
    // exactly while that is true; see rememberPlotPinCentreOffer.
    val pinCentreOffer = rememberPlotPinCentreOffer(controller)

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
    /// Why the last "+" did nothing. Held separately from `failure` so it can
    /// be taken down the moment the gate would honour a tap, without touching
    /// a real capture failure. Rendered on the same amber surface, behind
    /// `failure`, so it can never hide one.
    var captureRefusal by remember { mutableStateOf<String?>(null) }
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
    //
    // FIELD REPORT 4 — the screen OPENS on the bracket unless the cruiser
    // last chose Auto. Automatic edge-finding was the default and the
    // cruiser's verdict on it was that it jumped left and right on a real
    // stem badly enough to be unusable in the stand; the bracket is one drag
    // and holds still. It OPENS CENTRED on the crosshair at the width the
    // last tree was measured at, so a plot walked at one standing distance
    // needs no drag at all after the first tree. (Centred only at open —
    // each handle then moves on its own; see the drag gesture.)
    //
    // NO AUTO INTERLUDE, and this is where iOS was brought into line: both
    // values are read from the persisted settings at first composition, so
    // the bracket is live on the first frame at a known width. The settings
    // snapshot is loaded synchronously in `AppSettings.loadSnapshot`, so
    // these `remember`s see the cruiser's stored width, not the defaults.
    var adjustMode by remember { mutableStateOf(settings.dbhEdgeAdjustDefault) }
    var adjustLeftFrac by remember {
        mutableStateOf(0.5f - clampBracketHalfWidth(settings.dbhBracketHalfWidth))
    }
    var adjustRightFrac by remember {
        mutableStateOf(0.5f + clampBracketHalfWidth(settings.dbhBracketHalfWidth))
    }
    var adjustPreview by remember { mutableStateOf<DBHEstimator.DbhPreview?>(null) }

    // ADJUST live-readout settling (field round 10 — the diameter that jumps
    // by inches). `adjustPreview` above stays THE FIT, always: the capture
    // gate, the ring and the refusals all read it, and gating a burst on a
    // live display tick would refuse a capture whose stored median is
    // demonstrably unaffected (rho = -0.11). What is decided below is only
    // which NUMBER reaches the badge.
    //
    // See DBHEstimator.bracketCoreDepthSpreadM for the measurement and the
    // cause: the bracket's middle half intermittently straddles the stem AND
    // what is behind it, and the median hops between the two clusters. WHY
    // HOLD RATHER THAN BLANK — a bad tick lands roughly one time in ten at the
    // preview cadence, so blanking each one would strobe the number in and out,
    // harder to read than the jump it replaces. The held value is the most
    // recent diameter actually measured across bark, not an invented one, and a
    // settled tick publishes immediately so a handle drag still tracks exactly.
    /// What the badge shows — the fit on a settled tick, the last settled fit
    /// while a short excursion passes, nothing once the excursion outlasts the
    /// grace.
    var adjustShown by remember { mutableStateOf<DBHEstimator.DbhPreview?>(null) }
    /// The last fit measured across a single surface, and when.
    ///
    /// THE CLOCK IS WHAT MAKES THIS SAFE ACROSS TREES, not the reset sites: a
    /// capture burst takes seconds, so a value held from the previous tree is
    /// already older than `BRACKET_UNSETTLED_GRACE_MS` by the time the aiming
    /// loop resumes, and the first unsettled tick on the new tree discards it.
    var adjustHeld by remember { mutableStateOf<DBHEstimator.DbhPreview?>(null) }
    var adjustHeldAt by remember { mutableStateOf(0L) }
    /// True while the bracket is spanning more than one surface.
    var bracketTwoSurfaces by remember { mutableStateOf(false) }
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
    // Drop the held frame AND delete the file. Called on retake, on a
    // superseding burst, and on the way off the screen — the store keeps one
    // file per reading (QuickMeasureHistory deletes a reading's photo with
    // it), so a frame no reading will ever claim has to go here.
    fun discardHeldPhoto() {
        val name = heldPhoto ?: return
        heldPhoto = null
        (context as? android.app.Activity)?.let { act ->
            runCatching { MeasurePhotoStore.delete(act, name) }
        }
    }
    // Take the measurement-moment JPEG and park it in `heldPhoto`.
    //
    // ORDER MATTERS. The chrome blackout is raised BEFORE the delay, so
    // Compose has committed a chrome-less frame by the time the copy runs —
    // and the callers run this BEFORE flipping to Stage.RESULT, so the result
    // panel is not even composed yet. What deliberately stays is the AR
    // scene: the trunk cylinder IS the measurement, and it is the whole
    // evidentiary value of the photo. (The copy targets the AR surface, so
    // Compose chrome could not reach the JPEG anyway — the blackout is belt
    // and braces, and keeps the screen honest about what is photographed.)
    suspend fun captureHeldPhoto() {
        val activity = context as? android.app.Activity ?: return
        // A fresh burst supersedes whatever was held — never leave the
        // previous capture's file behind on disk.
        discardHeldPhoto()
        hidingChromeForCapture = true
        // LEAVING THE SCREEN INSIDE THIS SETTLE writes nothing: the caller
        // runs in `rememberCoroutineScope`, whose job is cancelled when the
        // composition goes, and the suspension points below (this delay, the
        // PixelCopy await, the hop to the IO dispatcher) are all cancellation
        // points ahead of the file write. A cancellation that lands DURING
        // the write — the one case the store cannot be pre-empted out of —
        // is cleaned up inside `captureScene`, which deletes its own bytes
        // rather than leave a file no screen is left to hold or delete.
        // (iOS needs an explicit has-left check there — its capture task is
        // unstructured.)
        //
        // The suspension is no longer main-thread time: everything after the
        // surface copy — the emptiness check, the JPEG encode, the write —
        // runs on Dispatchers.IO. The screen stays live throughout.
        delay(80)
        heldPhoto = MeasurePhotoStore.captureScene(activity)
        hidingChromeForCapture = false
    }
    // Leaving without accepting: the held frame belongs to a measurement that
    // was never stored, so the file goes with it. Anything already handed to a
    // reading was released at Accept, so this can only ever delete an orphan.
    DisposableEffect(Unit) {
        onDispose { discardHeldPhoto() }
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
    // The bundle a kept-on-screen truth was typed FOR (iOS truthOwnerBundleID).
    // Every path above deliberately KEEPS the text when the value could not be
    // attached, so what is in the field may belong to an EARLIER capture — and
    // the cruise tally reset does not clear it either. Without this mark the
    // next tree's Accept re-parsed that text and exported it as ITS true_value:
    // tree 7's tape number, and an error computed against tree 8's diameter.
    // Cleared the moment the cruiser edits the field.
    var truthOwnerBundleId by remember { mutableStateOf<String?>(null) }
    // The unit THIS typed truth is in. It opens in the cruiser's active system
    // — an imperial operator gets inches, not a centimetre field they type
    // inches into — and the square button beside the field switches it for
    // this entry. Keyed on the active system so changing the project's units
    // re-defaults it; a per-entry toggle survives ordinary recomposition.
    var truthUnit by remember(settings.unitSystem) {
        mutableStateOf(TruthInput.defaultUnit(
            TruthInput.Quantity.DIAMETER,
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
            // Same freshness gate as the accept path — a bundle must not
            // claim a position the app would refuse to draw.
            gps = freshFixOrNull(
                com.hcjeong.forestix.positioning.LocationService.lastGlobalFix,
                System.currentTimeMillis()),
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
                    if (researchTrueText == truthPendingText) researchTrueText = ""
                    truthPending = null
                    truthPendingId = null
                    // Durable and off the screen: it owns nothing now.
                    truthOwnerBundleId = null
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
                    // exports a tape number never taken on that stem. When the
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
    // True once ACQUISITION_STALL_MS of aiming has gone by without a single
    // lock — the acquisition hint's only trigger. Both preview loops
    // maintain it, and it is a Boolean rather than a timestamp so a screen
    // that is quietly failing to acquire recomposes exactly twice (in, and
    // back out again) instead of on every 150 ms tick.
    var acquisitionStalled by remember { mutableStateOf(false) }
    // True once DEPTH_SILENT_MS has gone by with no depth frame at all. The
    // banner uses it to stop quoting a preview that stopped being updated —
    // see DEPTH_SILENT_MS. Maintained by both preview loops alongside the
    // stall flag.
    var depthSilent by remember { mutableStateOf(false) }
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
        // The stall clock belongs to THIS aiming run — a stage change or a
        // mode flip restarts the effect and so restarts the clock.
        acquisitionStalled = false
        depthSilent = false
        var lastLockAt = SystemClock.elapsedRealtime()
        var lastFrameAt = SystemClock.elapsedRealtime()
        while (stage == Stage.AIMING && !depthBlocked && !adjustMode) {
            controller.acquireDepthFrame()?.let { f ->
                lastFrameAt = SystemClock.elapsedRealtime()
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
                        raw.distanceM !in PREVIEW_RANGE_M ->
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
            // OUTSIDE the frame block on purpose: "no depth frame at all" is
            // one of the states the cruiser is stuck in, and it has to count
            // towards the stall the same as a frame that produced no lock.
            //
            // Which is why the lock test is gated on a FRESH frame. `preview`
            // is only ever written inside the block above, so with no frames
            // arriving it holds its last value — and a held `locked == true`
            // pushed `lastLockAt` forward on every tick, so the clock never
            // ran and the hint could never come up in precisely the state
            // this line was written for.
            val nowMs = SystemClock.elapsedRealtime()
            depthSilent = nowMs - lastFrameAt >= DEPTH_SILENT_MS
            if (!depthSilent && preview?.locked == true) lastLockAt = nowMs
            // The refusal describes a "+" that was refused. Once the gate
            // would honour a tap it is no longer true, so it comes down
            // without waiting for a second tap. (iOS clears it from its stall
            // ticker, for the same reason: the frame handler has too many
            // early returns to be trusted with this.)
            if (captureRefusal != null && preview?.locked == true) captureRefusal = null
            acquisitionStalled = nowMs - lastLockAt >= ACQUISITION_STALL_MS
            delay(150)
        }
    }

    // ADJUST live estimate: the handle span (view px → depth px via the
    // frame's own view↔depth affine) + the median depth inside the bracket
    // at the guide row, refreshed on the same cadence as the auto preview.
    LaunchedEffect(stage, adjustMode, depthBlocked) {
        acquisitionStalled = false
        depthSilent = false
        var lastLockAt = SystemClock.elapsedRealtime()
        var lastFrameAt = SystemClock.elapsedRealtime()
        while (stage == Stage.AIMING && adjustMode && !depthBlocked) {
            controller.acquireDepthFrame()?.let { f ->
                lastFrameAt = SystemClock.elapsedRealtime()
                val w = controller.viewWidthPx.toFloat()
                val cy = controller.viewHeightPx / 2f
                adjustPreview = if (w > 1f) {
                    DBHEstimator.constrainedEstimate(
                        f, adjustLeftFrac * w, adjustRightFrac * w, cy, calibration,
                    )
                } else null
                // The settling decision for THIS tick — see `adjustShown`.
                // The probe is read-only and the fit above is untouched on
                // every branch.
                val tick = SystemClock.elapsedRealtime()
                val fitNow = adjustPreview
                if (fitNow == null) {
                    // No fit is already a fully-explained state on this path,
                    // and it is not evidence about the bark either way — leave
                    // the held value and its clock alone so a one-frame depth
                    // outage mid-drag does not restart the grace.
                    bracketTwoSurfaces = false
                    adjustShown = null
                } else {
                    val spread = if (w > 1f) {
                        DBHEstimator.bracketCoreDepthSpreadM(
                            f, adjustLeftFrac * w, adjustRightFrac * w, cy,
                        )
                    } else null
                    // A fit exists but the probe declined to describe it
                    // (fewer than three valid depths cannot happen here — the
                    // fit needs them too — so this is the bounds refusal).
                    // Treat an unmeasurable spread as settled rather than
                    // inventing a verdict about it.
                    val settled = spread == null ||
                        spread <= DBHEstimator.BRACKET_CORE_DEPTH_SPREAD_LIMIT_M
                    if (settled) {
                        adjustHeld = fitNow
                        adjustHeldAt = tick
                        bracketTwoSurfaces = false
                        adjustShown = fitNow
                    } else {
                        bracketTwoSurfaces = true
                        val held = adjustHeld
                        adjustShown = if (held != null && adjustHeldAt != 0L &&
                            tick - adjustHeldAt <= BRACKET_UNSETTLED_GRACE_MS
                        ) held else null
                    }
                }
            }
            // Same gate as the auto loop: `adjustPreview` survives a frame
            // outage untouched, so a held lock would refresh the clock for
            // ever. ADJUST is the default path, so this is the common case.
            val nowMs = SystemClock.elapsedRealtime()
            depthSilent = nowMs - lastFrameAt >= DEPTH_SILENT_MS
            if (!depthSilent && adjustPreview?.locked == true) lastLockAt = nowMs
            // Same rule as the auto loop — a refusal outlives its own truth
            // the moment the bracket resolves.
            if (captureRefusal != null && adjustPreview?.locked == true) captureRefusal = null
            acquisitionStalled = nowMs - lastLockAt >= ACQUISITION_STALL_MS
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
    /// The sentence for a "+" the gate will not honour.
    ///
    /// `livePreview` hands back a preview carrying the tap depth even on the
    /// ticks it refuses to lock, so a cruiser standing outside the window is
    /// told WHICH WAY TO WALK rather than getting a generic miss. A tap depth
    /// of 0 means the centre pixel had no return at all — not a distance
    /// worth talking about — so it falls through to the lock sentence, as
    /// does an in-range frame the walk simply couldn't read.
    fun refusalFor(p: DBHEstimator.DbhPreview?): String {
        val d = p?.distanceM ?: return NO_TRUNK_LOCK_TEXT
        if (p.edgesClipped) return EDGES_CLIPPED_TEXT
        return when {
            d > PREVIEW_RANGE_M.endInclusive -> TOO_FAR_TEXT
            d > 0f && d < PREVIEW_RANGE_M.start -> TOO_CLOSE_TEXT
            else -> NO_TRUNK_LOCK_TEXT
        }
    }

    fun capture() {
        if (stage == Stage.CAPTURING) return
        // Require a locked live fit before starting the burst — mirrors the
        // iOS armed + non-red gate, so a capture can't run on an unresolved
        // trunk and immediately reject.
        //
        // AND SAY SO. This returned in silence, so at a range the preview
        // would not lock at, the cruiser got a live badge, a red crosshair
        // and a "+" that did nothing, with no sentence on screen mentioning
        // distance at all.
        if (preview?.locked != true) {
            captureRefusal = refusalFor(preview)
            return
        }
        captureRefusal = null
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
            // THE SHUTTER — here, at the instant the burst finished and the
            // diameter is computed, with the cruiser still holding the phone
            // on the stem, and BEFORE Stage.RESULT puts the panel up. NOT at
            // Accept (see `heldPhoto`). A red fit cannot be accepted, so it
            // gets no file — only a diameter a reading can carry does.
            if (agg != null) captureHeldPhoto() else discardHeldPhoto()
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
        // Same silence, same fix. `constrainedEstimate` returns null rather
        // than an unlocked preview, so the bracket can only ever report the
        // lock sentence — it has no tap depth to name a direction from.
        if (adjustPreview?.locked != true) {
            captureRefusal = refusalFor(adjustPreview)
            return
        }
        captureRefusal = null
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
                if (diameters.size < DBHEstimator.TierThresholds.MIN_USABLE_FRAMES) {
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
                // Frame-to-frame agreement grades the sub-sample — the same
                // rule bracketChordEstimate applies, read from the same
                // constant so this inline copy cannot drift away from it.
                val mean = diameters.average()
                val cov = if (mean > 0) (diameters.last() - diameters.first()) / mean else 1.0
                subs.add(
                    DBHResult(
                        diameterCm = medianCm.toFloat(), centerX = 0f, centerZ = 0f,
                        arcCoverageDeg = 0f, rmseMm = 0f, sigmaRmm = 0f,
                        nInliers = spanPxSum,
                        confidence = if (cov <= DBHEstimator.TierThresholds.FRAME_SPREAD_GREEN)
                            ConfidenceTier.GREEN else ConfidenceTier.YELLOW,
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
            // THE SHUTTER — same moment and same rule as the auto burst
            // above: the frame with the bracket still on the stem, taken
            // before the result panel is composed.
            if (agg != null) captureHeldPhoto() else discardHeldPhoto()
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
        // Auto-capture (map home): the photo was taken at the end of the
        // burst, by `captureHeldPhoto` — Accept attaches `heldPhoto` and the
        // latest GPS fix from the badge's running location service, and needs
        // no Activity of its own to do it.
        // Cruise tally session (v3): captured once so the whole accept
        // rides ONE consistent routing decision.
        val cruise = CruiseCapture.target
        // Snapshot the typed "True Ø" BEFORE anything async: the truth must
        // survive regardless of whether the bundle write has finished (the
        // store queues edits against the synchronously-minted id) — the old
        // code wrote truth only when the write had already completed, so a
        // fast Accept dropped it and the field was cleared anyway.
        // The UNIT is snapshotted with the text for the same reason: the
        // cruiser can retype and re-toggle while the write runs, and the value
        // must be converted and recorded with the unit it was typed under.
        val truthTextAtAccept = researchTrueText
        val truthUnitAtAccept = truthUnit
        // Always the metric base (cm) — the conversion lives in TruthInput.
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
        // (the map home's tree lock) means "wherever I am now".
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
                    // attaching it here would put one tree's tape measurement on
                    // another tree's bundle. Make the cruiser re-enter (or clear)
                    // it instead — the owner mark stays set, so the gate above
                    // keeps it out of this tree's reading and research row too.
                    truthOwnerBundleId != null && truthOwnerBundleId != rid ->
                        truthSaveFailure = RawCaptureStrings.TRUTH_STALE_OWNER
                    // The capture itself failed — keep the typed value on
                    // screen rather than attach it to a bundle that isn't there.
                    // It is marked as owned by no bundle (the failed capture's
                    // id was already dropped) so the NEXT tree's Accept refuses
                    // it instead of exporting it as that tree's tape number.
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
            // The chrome-less snapshot of the AR surface (camera feed + the
            // rendered measurement geometry) was already taken, at the moment
            // the burst finished (see `heldPhoto`) — Accept only attaches it.
            // Null when that capture failed, or when there was no measurement
            // moment at all: a TYPED diameter carries no photo, because the
            // frame at Save is the keyboard panel and whatever the phone
            // happened to be pointing at.
            val photo = heldPhoto
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
                // Cruise mode: the SAME accept pedigree (value + σ + meta +
                // GPS + photo) lands on a cruise Tree row in the active
                // plot — the quick-measure history (and its map pins) never
                // sees cruise readings. A storage failure must not crash
                // the AR screen; the missing pin surfaces it on the map.
                val savedTreeId = runCatching {
                    CruiseCapture.recordDbh(
                        env, r,
                        speciesCode = metaSpecies,
                        damageCodes = metaDamage,
                        note = metaNote,
                        photoPath = photo,
                        fix = fix,
                        captureMode = when {
                            r.method == DBHMethod.MANUAL_VISUAL -> "typed"
                            resultFromAdjust -> "manual"
                            else -> "auto"
                        },
                    )
                }.getOrNull()
                // The tree row owns the file now — release it WITHOUT
                // deleting, so the tally reset below (and this screen's own
                // disposal) can't take the photo off a saved tree. If the row
                // never reached the database there is nothing holding the
                // file and the tally moves on regardless, so delete it here
                // rather than leave an orphan in the photo store.
                if (savedTreeId != null) heldPhoto = null else discardHeldPhoto()
                // A. QUICK-TALLY LOOP: no pop, no continuation — bump the
                // session to the plot's next tree number and reset this
                // screen to aiming, with the Undo toast (F) offering a 3 s
                // take-back.
                val measuredTreeNumber = cruise.treeNumber
                // The label BEFORE the advance — the toast names the tree that
                // was just written, which is the one Undo would take back.
                val measuredTreeTitle = cruise.treeTitle
                CruiseCapture.advanceTally(env, saved = savedTreeId != null)
                cruiseTreeNumber = CruiseCapture.target?.treeNumber
                cruiseTreeTitle = CruiseCapture.target?.treeTitle
                pendingTree = CruiseCapture.target?.treeNumber ?: pendingTree
                result = null
                failure = null
                metaSpecies = null
                metaPosition = StemPosition.DBH
                metaDamage = emptyList()
                metaNote = ""
                stage = Stage.AIMING
                undoToast = measuredTreeTitle
                undoEpoch += 1
                // F10 — DIAMETER → HEIGHT CHAIN, on by default. Push Height
                // for the tree just written, ON TOP of this tally (so its
                // Accept and its Skip both land back here, already aiming at
                // the next tree). `savedTreeId == null` means the row never
                // reached the database, and a height would have nothing to
                // fold into, so the chain is skipped and the loop continues.
                if (settings.measureHeightAfterDiameter && savedTreeId != null) {
                    nav.navigate("height?tree=$measuredTreeNumber&chained=true")
                }
            } else {
                val reading =
                    QuickMeasureEntry(
                        kind = MeasureKind.DBH, value = r.diameterCm.toDouble(),
                        // A TYPED diameter has no propagated uncertainty —
                        // there is no geometry to propagate. Recording 0
                        // would claim a perfect measurement and poison the
                        // sigma column the accuracy work reads.
                        sigma = if (r.method == DBHMethod.MANUAL_VISUAL) null
                                else r.sigmaRmm.toDouble(),
                        confidenceRaw = r.confidence.raw,
                        method = r.method.raw, treeNumber = pendingTree,
                        // The chooser's name when this flow was launched with
                        // one, else the name the tree already carries — a
                        // re-measurement must not arrive nameless and split
                        // the tree in two in the export.
                        // The name is looked up in the plot this reading lands
                        // in, not the active one — on a re-measure they can be
                        // different plots, and the wrong plot's tree 7 is a
                        // different tree.
                        treeName = pendingLock?.name
                            ?: env.history.treeName(pendingTree, targetPlotID),
                        plotID = targetPlotID,
                        speciesCode = metaSpecies,
                        position = metaPosition ?: StemPosition.DBH,
                        damageCodes = metaDamage,
                        note = metaNote.ifBlank { null },
                        latitude = fix?.latitude,
                        longitude = fix?.longitude,
                        photoPath = photo,
                        // Edge provenance. "typed" is its OWN value, not
                        // "auto": a hand-entered diameter had no edge-finder
                        // and no bracket, and filing it under "auto" put a
                        // number the sensors never produced into the same
                        // bucket the algorithm comparison draws from.
                        captureMode = when {
                            r.method == DBHMethod.MANUAL_VISUAL -> "typed"
                            resultFromAdjust -> "manual"
                            else -> "auto"
                        },
                        // The tape diameter belongs ON the reading: the
                        // raw-capture manifest is developer plumbing that can
                        // be pruned, while the truth column the accuracy study
                        // reads is exported from here. A value typed on this
                        // screen is the newest word on this tree and wins; a
                        // re-measure otherwise keeps the tape value already
                        // typed for it, and a fresh reading has none.
                        // `ownedTrue`, never the bare re-parse: text kept on
                        // screen from an earlier capture is not this tree's
                        // tape measurement.
                        truth = (if (settings.developerMode) ownedTrue else null)
                            ?: pendingLock?.truth,
                        // Provenance follows the value it belongs to: a truth
                        // typed on THIS screen is typed (null), while one
                        // riding across from the reading being re-measured
                        // keeps whatever source it already had — a recovered
                        // truth must not be re-labelled as hand-typed by a
                        // re-measure that never touched the tape.
                        truthSource =
                            if (settings.developerMode && ownedTrue != null) null
                            else pendingLock?.truthSource,
                        // The unit rides with the same value, for the same
                        // reason it is on the reading at all: dropping it on a
                        // re-measure would take the mark off a truth
                        // TruthUnitRepair had already re-based, and a mark that
                        // a re-measure can erase is not a durable one.
                        truthUnit =
                            if (settings.developerMode && ownedTrue != null) null
                            else pendingLock?.truthUnit,
                    )
                // A re-measure launched from the field log TAKES THE PLACE of
                // the reading it was launched from — appending would leave the
                // superseded number invisible in the log but still in the CSV.
                if (pendingLock?.replaceExisting == true) {
                    env.history.replaceReading(reading)
                } else {
                    env.history.append(reading)
                }
                // The reading owns the file now — release it WITHOUT deleting,
                // so the disposal the navigation below triggers can't take the
                // photo off a saved reading.
                heldPhoto = null
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
            // The tree this capture is ALREADY locked to, not a box the
            // cruiser had to retype. Same value the raw-capture bundle and the
            // saved reading carry, so the three join.
            fields["tree_id"] = "${CruiseCapture.target?.treeNumber ?: pendingTree}"
            // Raw per-frame distance (pre-round-6 semantics) — never the
            // EMA-smoothed badge value; the display smoothing must not leak
            // into research/σ inputs.
            rawDistM?.let { fields["distance_m"] = String.format(Locale.US, "%.2f", it) }
            controller.cameraForwardElevationRad()?.let {
                fields["pitch_deg"] = String.format(Locale.US, "%.1f", it * 180f / Math.PI.toFloat())
            }
            // `ownedTrue`, never the bare re-parse — see the owner gate. A
            // stale number here is the worst of the three: the CSV is what the
            // accuracy study reads, and tree 8's row would carry tree 7's tape
            // value with an error computed against tree 8's diameter.
            ownedTrue?.let { t ->
                // `true_value` and `error` are in the row's `unit` (cm) — the
                // same scale as `measured_value`, so the error column stays
                // subtractable. `truth_unit` records what was actually typed.
                fields["true_value"] = String.format(Locale.US, "%.2f", t)
                fields["error"] = String.format(Locale.US, "%.2f", r.diameterCm - t)
                fields["truth_unit"] = truthUnitAtAccept.raw
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
                cruiseTreeTitle = CruiseCapture.target?.treeTitle
                pendingTree = CruiseCapture.target?.treeNumber ?: pendingTree
            }
            undoToast = null
        }
    }

    val locked = stage == Stage.AIMING && preview?.locked == true

    Box(Modifier.fillMaxSize()) {
        // Live trunk-cylinder marker while aiming, and FROZEN THROUGH THE
        // BURST — the preview loop stops at Stage.CAPTURING, so what stays on
        // screen is the last locked fit, which is exactly the fit the burst is
        // measuring. iOS renders it in the same window (`cylinderMarkers` is
        // not stage-gated at all, and `adjustOverlayVisible` explicitly keeps
        // the bracket up through `.capturing`).
        //
        // It also has to be there because the measurement photo is taken at
        // the END of the burst, while the stage is still CAPTURING: the
        // cylinder is what makes that photo evidence of WHERE the cruiser
        // aimed rather than a bare camera frame. Result stages drop it, as
        // before.
        val dbhMarkers = if (stage == Stage.AIMING || stage == Stage.CAPTURING) {
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

        // ADJUST-mode drag catcher. Early in the Box so the rail / status
        // panel (later children) still win their own touches.
        //
        // SYMMETRIC — a drag moves BOTH handles, mirrored about the
        // crosshair (FIELD REPORT 4). They used to move independently, which
        // made fitting a trunk a two-handed job: drag the left edge on, drag
        // the right edge on, then find that the crosshair no longer sat on
        // the stem centre and the chord had picked up whatever was behind
        // the tree on one side. A stem is symmetric about where it is aimed
        // at, so one drag is enough — the cruiser sets the WIDTH and the app
        // keeps the centre. Which handle was grabbed no longer matters, so
        // the drag reads the FINGER's distance from centre directly rather
        // than accumulating deltas (which drifted when a drag crossed the
        // centre line).
        if (adjustMode && stage == Stage.AIMING) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        // INDEPENDENT HANDLES. Each one moves on its own.

                    // They were briefly symmetric about the crosshair, which
                    // is what "move one side and both match" was read to
                    // mean. It measured wrong. A stem is only symmetric
                    // about the crosshair if the crosshair is exactly on its
                    // centre, and in the stand it usually is not — so to
                    // cover the trunk the cruiser had to open the bracket
                    // until BOTH edges cleared it, and the span came out
                    // wider than the tree. Centre off by half a radius and
                    // the symmetric span is 2*(1.5r) = 3r against a true
                    // diameter of 2r: a 1.5x over-read, which is what the
                    // field measured.
                    //
                    // Placing each edge where the edge actually is has no
                    // such failure mode, and it is what worked before. The
                    // width still carries over to the next tree, which was
                    // the other half of the request and the part that
                    // actually saves the cruiser time.
                        var draggingLeft = true
                        detectDragGestures(
                            onDragStart = { pos ->
                                val w = size.width.toFloat().coerceAtLeast(1f)
                                draggingLeft =
                                    kotlin.math.abs(pos.x - adjustLeftFrac * w) <=
                                    kotlin.math.abs(pos.x - adjustRightFrac * w)
                            },
                            // Persisted on release, not on every frame of
                            // the drag: the next tree opens at the width
                            // this one ended on.
                            onDragEnd = {
                                env.settings.setDbhBracketHalfWidth(
                                    (adjustRightFrac - adjustLeftFrac) / 2f)
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

        // Plot mini-map — top-right, same row as the GPS badge: the active
        // cruise plot (ring + YOU + measured trees) or the quick sampling
        // ring (ring + YOU). Hidden with the rest of the 2D chrome during
        // the Accept snapshot blackout.
        // F11 — the card is TAPPABLE: it re-opens plot setup so the radius /
        // centre stay editable after the first placement. The session's
        // project/plot are fixed for this screen's lifetime (advanceTally
        // only moves the tree NUMBER), so one remember is safe.
        //
        // FIELD REPORT 12 — a QUICK sampling ring gets Edit too, pointed at
        // the sampling screen that owns it. The ring the cruiser is measuring
        // into is editable either way; which screen edits it is an internal
        // detail, and hiding the control for one of them read as a bug.
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

        // Top strip: GPS pill leading, the cruise tally target ("Tree #8", or
        // the cruiser's name for it) centred in what is left.
        // ONE row, inset past the status bar, with the mini-map's width
        // reserved on the trailing edge — the two used to be independently
        // aligned overlays and collided on narrow screens.
        // iOS tallyTargetPill styling 1:1 (13 bold mono, black 0.65 capsule).
        if (!hidingChromeForCapture) {
            MeasureTopStrip(
                reserveTrailing = if (miniMapUp) MeasureMiniMapSlot else 0.dp,
                // FIELD REPORT 11 — this is the map's chip, not a scan-only
                // variant. It used to be GPSAccuracyBadge ("GPS good / fair
                // / check"), a second vocabulary for the same question,
                // shown at the exact moment a position gets written onto a
                // stored measurement. The cruiser asked for the readout they
                // already trust; the badge is gone.
                leading = { GpsFixChip(acquiresService = true) },
                centre = {
                    cruiseTreeTitle?.let { title ->
                        // TAPPABLE: this is where a cruise tree gets its name,
                        // and it is deliberately the pill rather than a step in
                        // front of the scan. The cruiser is already aiming at
                        // the trunk; the name arrives pre-filled by
                        // TreeNameSequence and only has to be touched when the
                        // series starts or breaks, so the loop stays
                        // zero-typing per tree. iOS taps the same pill.
                        Text(
                            title,
                            style = TextStyle(
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                            ),
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .shadow(3.dp, CircleShape, clip = false)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.65f))
                                .border(0.5.dp, Color.White.copy(alpha = 0.18f), CircleShape)
                                .clickableNoRipple {
                                    renameDraft =
                                        CruiseCapture.target?.treeName.orEmpty()
                                }
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                        )
                    }
                },
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
                        .statusBarsPadding()
                        .padding(top = 144.dp, end = 16.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.65f))
                        .border(0.5.dp, Color.White.copy(alpha = 0.18f), CircleShape)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }

        // The developer internals HUD is NOT drawn here any more. In the
        // field it covered the AR view and collided with the mini-map and
        // the border chip, and a cruiser has no use for it. The DevHud
        // component still exists (ui/screens/DevHud.kt) and the dev* state
        // below is still computed, so it can be re-attached for a bench
        // session; nothing about RECORDING went with it. The research CSV
        // row (ResearchLog.record, in the accept path above) and the
        // raw-capture bundle (ArSessionHub.rawDepth* / RawCaptureStore) are
        // written from the measurement path, never from this overlay.

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
                        undoToast?.let { savedTitle ->
                            UndoToastPill(savedTitle) { undoTally() }
                        }
                        if (showAutoPill) {
                            AutoModePill {
                                adjustMode = false
                                adjustPreview = null
                                // A held ADJUST value belongs to the bracket
                                // that produced it, and there is no bracket
                                // outside this mode.
                                adjustShown = null; adjustHeld = null
                                adjustHeldAt = 0L; bracketTwoSurfaces = false
                                // The refusal was about the OTHER path's gate.
                                captureRefusal = null
                                // Remembered, so a cruiser who prefers the
                                // automatic edges is not handed the bracket
                                // again on the next tree. This pill and the
                                // Adjust rail button are the whole control —
                                // the preference has no Settings row.
                                env.settings.setDbhEdgeAdjustDefault(false)
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
                            // Open the bracket at the width the LAST tree
                            // was measured at, centred on the crosshair.
                            //
                            // FIELD REPORT 4 — this used to seed from the
                            // automatic fit's edges when it had any. That
                            // sounded helpful and was not: the automatic
                            // edges are the thing the cruiser reached for
                            // ADJUST to get away from, so the bracket opened
                            // already wrong and asymmetric, and the first
                            // drag was spent undoing it. A plot is walked at
                            // roughly one standing distance, so the previous
                            // tree's width is the better guess — and when it
                            // is wrong it is wrong symmetrically, which one
                            // drag fixes. On a fresh install the stored
                            // width is DEFAULT_BRACKET_HALF_WIDTH, derived
                            // there from the field corpus rather than picked
                            // for symmetry. iOS now opens the same way (it
                            // seeded from the auto fit until this round).
                            val half = clampBracketHalfWidth(settings.dbhBracketHalfWidth)
                            adjustLeftFrac = 0.5f - half
                            adjustRightFrac = 0.5f + half
                            env.settings.setDbhEdgeAdjustDefault(true)
                            adjustPreview = null
                            // Entering ADJUST starts a fresh bracket — nothing
                            // held from a previous session of it may show
                            // under the new handles.
                            adjustShown = null; adjustHeld = null
                            adjustHeldAt = 0L; bracketTwoSurfaces = false
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
                            // The refusal was about the OTHER path's gate.
                            captureRefusal = null
                            adjustMode = true
                        }
                    }
                } else {
                    null
                },
                valueStrip = {
                    if (adjustMode) {
                        // `adjustShown`, not `adjustPreview`: the badge is the
                        // one surface the settling decision applies to. The
                        // LOCK still comes from the fit, so a tick whose number
                        // is being held does not also take the capture
                        // affordance away. See `adjustShown`.
                        LivePreviewBadge(
                            adjustShown, adjustPreview?.locked == true, settings.unitSystem)
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
                    // FIELD REPORT 15, the stuck case: depth delivery has
                    // stopped outright and has been down long enough to
                    // stall. Every line below reads `preview` /
                    // `adjustPreview`, which the loops froze at whatever the
                    // last frame said — a stale "hold steady, then tap +"
                    // over a dead screen, or bracket advice for a bracket
                    // that is no longer being evaluated. The hint is the only
                    // sentence that is still true, so it goes first. (iOS
                    // reaches the same place by clearing the stale strip line
                    // from its stall ticker.)
                    depthSilent && acquisitionStalled -> ACQUISITION_STALL_HINT
                    // ADJUST keeps the standard aligning/armed copy
                    // (iOS statusText parity): armed as soon as a
                    // bracket fit exists.
                    adjustMode ->
                        // The bracket measured, but not across bark, and it
                        // has been that way for longer than the grace — so the
                        // number has been withheld and the strip has to say
                        // what happened. Ahead of "hold steady", which is the
                        // one remedy that cannot clear it. While the run is
                        // shorter than the grace the held value is on screen
                        // and this stays quiet: a sentence for every one-tick
                        // excursion would flicker exactly as badly as the
                        // number used to.
                        if (bracketTwoSurfaces && adjustShown == null) BRACKET_TWO_SURFACES
                        else if (adjustPreview?.locked == true) "Hold steady, then tap + to capture."
                        // Field report 15 — the bracket has been up for
                        // seconds with no depth behind it. Say what clears
                        // it instead of repeating "hold steady", which is
                        // the one thing that does not.
                        else if (acquisitionStalled) ACQUISITION_STALL_HINT
                        else "Align the guide to the trunk's uphill side; hold steady."
                    locked -> "Hold steady, then tap + to capture."
                    // Border-touch invalidity: the silhouette walk ran off
                    // the image — the trunk's edges aren't in frame, so no
                    // fit can lock. Honest guidance instead of a lock. More
                    // specific than the stall hint, so it wins.
                    preview?.edgesClipped == true -> EDGES_CLIPPED_TEXT
                    acquisitionStalled -> ACQUISITION_STALL_HINT
                    else -> "Align the guide to the trunk's uphill side; hold steady."
                }
                // Depth burst: the under-crosshair capture pill carries the
                // progress — no duplicate banner (iOS topBannerText parity).
                stage == Stage.CAPTURING -> null
                result?.confidence == ConfidenceTier.RED ->
                    result?.rejectionReason ?: "Scan rejected. Try again."
                else -> "Scan complete. Accept, retake, or add a second view."
            },
            // A refused "+" gets the same amber surface a failed capture gets.
            // A real failure outranks it, so the refusal can never hide one.
            failure = failure ?: captureRefusal,
            // FIELD REPORT 14 × 17 — a plot is being tallied but no AR
            // anchor marks its centre, so there is no ring to draw. Say
            // that, and offer the one act that produces a centre worth
            // drawing. Same card, same words, same act on the Height twin.
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

        // Tally pill → rename. A dialog rather than a sheet: it is one field,
        // it must not disturb the AR session running underneath, and it keeps
        // the cruiser on the trunk they are already aiming at. LOCKED strings
        // — the iOS alert says the same words.
        renameDraft?.let { draft ->
            AlertDialog(
                onDismissRequest = { renameDraft = null },
                title = { Text("Name this tree") },
                text = {
                    Column {
                        Text(
                            "Leave it empty and this tree goes back to " +
                                TreeLabel.title(null, cruiseTreeNumber ?: 0) + ".",
                        )
                        Spacer(Modifier.size(ForestixSpace.sm))
                        OutlinedTextField(
                            value = draft,
                            onValueChange = { renameDraft = it },
                            placeholder = { Text("e.g. Tree1") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        CruiseCapture.renameTarget(draft)
                        cruiseTreeTitle = CruiseCapture.target?.treeTitle
                        renameDraft = null
                    }) { Text("Save") }
                },
                dismissButton = {
                    TextButton(onClick = { renameDraft = null }) { Text("Cancel") }
                },
            )
        }

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
                        // White on the dark panel — the Material default is
                        // near-black, i.e. invisible here (see
                        // scanPanelTextFieldColors).
                        colors = scanPanelTextFieldColors(),
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
                        // BASAL AREA, beside the diameter that produced it.
                        // It is the number a forester converts the diameter
                        // into before doing anything else with it, and it
                        // costs one line of arithmetic — through the engine's
                        // own `basalAreaM2`, so this stem's basal area here,
                        // on its record sheet and in the plot total are one
                        // number. A diameter that is not a usable stem has
                        // none, and the line reads the form's em dash.
                        Text(
                            treeBasalAreaText(r.diameterCm, settings.unitSystem)
                                ?: TreeFormWords.EMPTY,
                            style = Forestix.type.dataSmall,
                            color = Color.White.copy(alpha = 0.75f),
                        )
                    }
                    if (settings.developerMode) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            ResearchFieldsRow(
                                // Label and unit come from the SAME value, so
                                // the field can never say cm while the app
                                // reads inches.
                                trueLabel = TruthInput.fieldLabel(
                                    TruthInput.Quantity.DIAMETER, truthUnit),
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
                                truePlaceholder = "tape",
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
                                    TruthInput.Quantity.DIAMETER,
                                    truthUnit))
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
                        // The held frame captions the measurement being thrown
                        // away — keeping it would put the OLD aim on the NEW
                        // diameter.
                        discardHeldPhoto()
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
                        "This phone can't measure diameter",
                        style = Forestix.type.bodyBold,
                        color = colors.textPrimary,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        "It doesn't have the depth camera the trunk scan needs. " +
                            "Measure this tree with a tape or calipers instead.",
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
/// cruise tally Accept: "Tree #7 saved · Undo" with Undo as a tappable bold
/// segment; the caller clears it after 3 s (locked, matches iOS).
@Composable
private fun UndoToastPill(treeTitle: String, onUndo: () -> Unit) {
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
            "$treeTitle saved · ",
            style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
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
                size = MeasurePillSize.LARGE,
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
