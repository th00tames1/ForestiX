// INLINE GPS-AVERAGING SHEET (mock ⑧) — Phase B replacement for the old
// full-screen PlotCenterScreen. A compact sheet over the cruise map:
// progress ring counting the 60 s averaging window, live ±accuracy +
// tier chip, elapsed seconds, one 54 dp "Save centre". The engine is
// unchanged — GPSAveraging.compute over the LocationService buffer
// (≥ 30 accepted samples, A/B/C/D tier table); the window semantics are
// the retired PlotCenterViewModel's: readouts run live, Save arms when
// the 60 s window completes with a result, < 30 good fixes at the bell
// is the failed state pointing at the offset link. Saving converts the
// planned plot into a real ACTIVE Plot at the averaged centre — the
// exact conversion the retired cruise-flow coordinator performed — and
// marks the PlannedPlot visited.
//
// The weak-GPS escape hatch is one line: "GPS weak? Use offset" opens
// the kept OffsetFlowScreen (its own AR session, hosted full-screen by
// CruiseOffsetHostScreen below); its computed PlotCenterResult lands
// the centre through the same save path.
//
// FIELD REPORT 17 — averaging is no longer a GATE. The 60 s window was
// the only way out of this sheet, and a cruiser who has already walked to
// the plot spends that minute standing still under a canopy that is not
// going to give them a better fix anyway. "Start plot now" takes whatever
// fix is live at that instant and opens the plot immediately; "Save
// centre" still arms at the bell for anyone who wants the median. The two
// produce DIFFERENT DATA and are recorded as such — `gpsSingle` /
// nSamples 1 for the instant one, `gpsAveraged` / nSamples >= 30 for the
// window — so nothing downstream has to guess which it was reading.

package com.hcjeong.forestix.ui.screens.cruise

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.hcjeong.forestix.AppEnvironment
import com.hcjeong.forestix.LocalAppEnvironment
import com.hcjeong.forestix.common.ForestixLogger
import com.hcjeong.forestix.data.cruise.PlannedPlot
import com.hcjeong.forestix.data.cruise.Plot
import com.hcjeong.forestix.data.cruise.PlotCenterResult
import com.hcjeong.forestix.data.cruise.PositionSource
import com.hcjeong.forestix.data.cruise.PositionTier
import com.hcjeong.forestix.data.cruise.Project
import com.hcjeong.forestix.positioning.CLLocationSnapshot
import com.hcjeong.forestix.positioning.GPSAveraging
import com.hcjeong.forestix.positioning.GeoMath
import com.hcjeong.forestix.positioning.LocationService
import com.hcjeong.forestix.ui.clickableNoRipple
import com.hcjeong.forestix.ui.pressableNoRipple
import com.hcjeong.forestix.ui.screens.nav.OffsetFlowScreen
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixRadius
import com.hcjeong.forestix.ui.theme.ForestixSpace
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/// The engine's averaging window (the retired view model's default).
private const val AVERAGING_WINDOW_S = 60

// MARK: - Sheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordCentreSheet(
    project: Project,
    planned: PlannedPlot,
    onDismiss: () -> Unit,
    onSaved: (Plot) -> Unit,
    onUseOffset: () -> Unit,
) {
    val env = LocalAppEnvironment.current
    val context = LocalContext.current
    val colors = Forestix.colors
    val type = Forestix.type
    val scope = rememberCoroutineScope()

    // Own PRIVATE 1 Hz location client — deliberately NOT
    // LocationService.shared: the 60 s averaging window clearBuffer()s on
    // open and treats the rolling buffer as its own state. On the shared
    // instance that clear would wipe (and its samples would pollute) any
    // other consumer's view of the buffer, and stop() semantics would
    // fight the ref-count. Passive readers (chip/badge/mini-map) use the
    // shared instance; averaging stays isolated (mirror of iOS).
    val location = remember { LocationService(context) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results.values.any { it }
        location.onPermissionResult(granted)
        if (granted) location.start()
    }
    LaunchedEffect(Unit) {
        location.clearBuffer()
        if (LocationService.hasLocationPermission(context)) {
            location.start()
        } else {
            launcher.launch(LocationService.PERMISSIONS)
        }
    }
    DisposableEffect(Unit) { onDispose { location.stop() } }
    val latest by location.latestSnapshot.collectAsStateWithLifecycle()

    var elapsedS by remember { mutableIntStateOf(0) }
    /// Wall clock the FRESHNESS test reads. Freshness changes with time, not
    /// with the arrival of a fix, so without a clock that keeps ticking past
    /// the 60 s bell "Start plot now" would stay armed on a fix that aged
    /// out while the cruiser read the sheet.
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    /// Engine run live over the rolling buffer while the window is open
    /// (nil until 30 accurate samples) — readouts only.
    var liveResult by remember { mutableStateOf<PlotCenterResult?>(null) }
    /// The window's FINAL result — Save arms on this (60 s bell).
    var finalResult by remember { mutableStateOf<PlotCenterResult?>(null) }
    var windowDone by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }

    // 1 s tick: advance the clock, re-run the engine over the buffer, and
    // ring the 60 s bell exactly once (retired PlotCenterViewModel.tick).
    // The tick outlives the window because the freshness clock above does.
    LaunchedEffect(Unit) {
        val startedAt = System.currentTimeMillis()
        while (true) {
            delay(1_000)
            nowMs = System.currentTimeMillis()
            if (windowDone) continue
            elapsedS = ((nowMs - startedAt) / 1_000L)
                .toInt().coerceAtMost(AVERAGING_WINDOW_S)
            liveResult = GPSAveraging.compute(
                GPSAveraging.Input(samples = location.buffer.value))
            if (elapsedS >= AVERAGING_WINDOW_S) {
                finalResult = liveResult
                windowDone = true
            }
        }
    }

    /// One save path for both buttons — whichever result they hand over is
    /// what gets stamped on the row, so the instant centre cannot pick up
    /// the averaged one's labels by taking a different route to storage.
    fun save(r: PlotCenterResult) {
        if (saving) return
        saving = true
        scope.launch {
            try {
                val plot = convertPlannedToActivePlot(env, project, planned, r)
                onSaved(plot)
            } catch (e: Exception) {
                saveError = "Storage error: ${e.message ?: e}. " +
                    "The centre was not saved — try again."
                saving = false
            }
        }
    }

    // Live readouts (iOS RecordCentreSheet 1:1).
    val readout = liveResult
    val final = finalResult
    val snap = latest
    /// The centre "Start plot now" would write — null while there is no fix
    /// the app still believes, which is exactly when the button greys out.
    /// Independent of the averaging window: it has nothing to say about
    /// whether a single fix is available.
    val instantCentre = singleFixCentre(latest, nowMs)
    /// Range + bearing from the cruiser to where this plot was PLANNED.
    /// "Start plot now" binds Plot N to wherever they are standing, so the
    /// one number that says whether that is the right place has to be on the
    /// same screen as the button. Same strings and same freshness gate as
    /// the planned pin's peek.
    val rangeText = freshFixOrNull(latest, nowMs)?.let { f ->
        val d = GeoMath.distanceM(
            fromLat = f.latitude, fromLon = f.longitude,
            toLat = planned.plannedLat, toLon = planned.plannedLon)
        val b = GeoMath.bearingDeg(
            fromLat = f.latitude, fromLon = f.longitude,
            toLat = planned.plannedLat, toLon = planned.plannedLon)
        String.format(Locale.US, "%.0f m · bearing %.0f°", d, (b + 360.0).mod(360.0))
    } ?: "no GPS fix"
    val accuracyText = when {
        readout != null -> String.format(Locale.US, "±%.1f m", readout.medianHAccuracyM)
        snap != null && snap.horizontalAccuracyM > 0 ->
            String.format(Locale.US, "±%.1f m", snap.horizontalAccuracyM)
        else -> "±— m"
    }
    // (`liveTier` went with the chip — field report F9. `LocationService.tier`
    // and `GPSAveraging.classify` are untouched; the grade is still computed
    // and stored on the Plot, it just isn't shown to a cruiser.)
    val failed = windowDone && final == null
    val sampleCount = readout?.nSamples ?: location.buffer.value.size
    val metaTitle = when {
        failed -> "Not enough good fixes"
        final != null && (final.tier == PositionTier.C || final.tier == PositionTier.D) ->
            "Centre ready — accuracy weak"
        final != null -> "Centre ready"
        snap == null -> "Waiting for GPS"
        else -> "Averaging position"
    }
    val metaDetail = when {
        failed ->
            "Not enough samples (need 30 with accuracy ≤ 20 m)\nTry the offset link below."
        final != null ->
            // The scatter of the GPS fixes, as a plain quantity. It used to
            // print as "σxy 1.4 m" — a Greek letter with an algebraic
            // subscript, on the sheet the A/B/C/D position grade was already
            // pulled from for being unreadable.
            "${final.nSamples} fixes · centre locked\n" +
                String.format(Locale.US, "fixes spread ±%.1f m", final.sampleStdXyM)
        else ->
            "$sampleCount fixes · needs 30 good ones\n" +
                "median converges over $AVERAGING_WINDOW_S s"
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.surface) {
        Column(
            Modifier
                .padding(horizontal = ForestixSpace.md)
                .padding(bottom = ForestixSpace.xl),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Sheet header — matches the planned-peek "Set plot centre" verb.
                Text(
                    "SET PLOT CENTRE",
                    style = type.sectionHead.copy(
                        fontWeight = FontWeight.ExtraBold, letterSpacing = 1.0.sp),
                    color = colors.textTertiary,
                )
                Text(
                    " · Plot ${planned.plotNumber}",
                    style = type.sectionHead.copy(fontWeight = FontWeight.ExtraBold),
                    color = colors.textTertiary,
                )
                Spacer(Modifier.weight(1f))
                // FIELD REPORT F9 — the A/B/C/D "TIER B" / "NO FIX" chip is
                // gone. It is a GPS-averaging grade the cruiser has no way
                // to act on and no way to interpret. The plain accuracy
                // figure (±x.x m) is still right there in the progress ring,
                // which is the one number that means something on a ridge.
                // `positionTier` is UNCHANGED on the Plot record and in every
                // export — see convertPlannedToActivePlot below, which still
                // stamps `positionTier = result.tier`.
            }
            Spacer(Modifier.size(ForestixSpace.sm))

            // Progress ring (60 s window; live "±X m" + elapsed "N s")
            // beside the phase meta column.
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                ProgressRing(
                    progress = (elapsedS.toFloat() / AVERAGING_WINDOW_S).coerceIn(0f, 1f),
                    centreTop = accuracyText,
                    centreBottom = "$elapsedS s",
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        metaTitle,
                        style = type.bodyBold.copy(fontSize = 15.sp),
                        color = colors.textPrimary,
                    )
                    Text(
                        metaDetail,
                        style = type.dataSmall.copy(fontSize = 11.sp),
                        color = colors.textTertiary,
                        lineHeight = 15.sp,
                    )
                }
            }
            Spacer(Modifier.size(ForestixSpace.sm))

            // FROM YOU — how far the cruiser is from the PLANNED position.
            Row(
                Modifier.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ForestixSpace.xs),
            ) {
                Text(
                    "FROM YOU",
                    style = type.caption.copy(
                        fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.7.sp),
                    color = colors.textTertiary,
                    modifier = Modifier.width(72.dp),
                )
                Text(
                    rangeText,
                    style = type.dataSmall.copy(fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold),
                    color = colors.textPrimary,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.size(ForestixSpace.xs))

            // 54 dp primary — the instant path (field report 17): armed the
            // moment there is a fix the app still believes, so the cruiser
            // never waits out the window to start measuring.
            val canStartNow = instantCentre != null && !saving
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 54.dp)
                    .alpha(if (canStartNow) 1f else 0.45f)
                    .pressableNoRipple(enabled = canStartNow) {
                        instantCentre?.let(::save)
                    }
                    .clip(ForestixRadius.card)
                    .background(colors.primary),
                contentAlignment = Alignment.Center,
            ) {
                if (saving) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(22.dp),
                    )
                } else {
                    Text(
                        "Start plot now",
                        style = type.bodyBold.copy(fontSize = 15.sp),
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
            Spacer(Modifier.size(ForestixSpace.xs))

            // Says what the button above actually records, because the
            // difference between one fix and a 60 s median is the whole
            // reason the other button exists — and it is invisible once the
            // plot is on the map.
            Text(
                "Records one GPS fix, not an average.",
                style = type.caption.copy(fontSize = 11.sp),
                color = colors.textTertiary,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.size(ForestixSpace.xs))

            // 54 dp outline — LOCKED "Save centre"; arms when the window
            // completes with an engine result. The averaged centre is still
            // the better one; it is no longer the only way out of here.
            val ready = finalResult != null && !saving
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 54.dp)
                    .alpha(if (ready) 1f else 0.45f)
                    .pressableNoRipple(enabled = ready) {
                        finalResult?.let(::save)
                    }
                    .clip(ForestixRadius.card)
                    .border(
                        if (ready) 1.5.dp else 1.dp,
                        if (ready) colors.primary else colors.divider,
                        ForestixRadius.card,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Save centre",
                    style = type.bodyBold.copy(fontSize = 15.sp),
                    color = if (ready) colors.primary else colors.textPrimary,
                )
            }
            Spacer(Modifier.size(ForestixSpace.xs))

            // LOCKED "Cancel".
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .pressableNoRipple(onClick = onDismiss)
                    .clip(ForestixRadius.control)
                    .border(1.dp, colors.divider, ForestixRadius.control),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Cancel",
                    style = type.bodyBold.copy(fontSize = 14.sp),
                    color = colors.textPrimary,
                )
            }

            // LOCKED one-line link into the KEPT Offset-from-Opening flow.
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 32.dp)
                    .clickableNoRipple(onUseOffset),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "GPS weak? Use offset",
                    style = type.caption.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                    color = colors.textTertiary,
                )
            }
        }
    }

    if (saveError != null) {
        AlertDialog(
            onDismissRequest = { saveError = null },
            title = { Text("Couldn't save the centre") },
            text = { Text(saveError ?: "") },
            confirmButton = {
                TextButton(onClick = { saveError = null }) { Text("OK") }
            },
        )
    }
}

// MARK: - Ring

@Composable
private fun ProgressRing(progress: Float, centreTop: String, centreBottom: String) {
    val colors = Forestix.colors
    val type = Forestix.type
    Box(Modifier.size(104.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 8.dp.toPx()
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = colors.surfaceRaised,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke),
            )
            if (progress > 0f) {
                drawArc(
                    color = colors.primary,
                    startAngle = -90f,
                    sweepAngle = 360f * progress,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                centreTop,
                style = type.data.copy(fontSize = 17.sp, fontWeight = FontWeight.ExtraBold),
                color = colors.textPrimary,
                maxLines = 1,
            )
            Text(
                centreBottom,
                style = type.dataSmall.copy(fontSize = 10.sp),
                color = colors.textTertiary,
            )
        }
    }
}

// (The A/B/C/D `TierChip` — "TIER B" / "NO FIX" — was deleted per field
// report F9. It graded GPS averaging on a scale nothing in the field
// explains, and a cruiser could neither act on a "C" nor tell what would
// make it a "B". The ±x.x m accuracy in the ring is the plain figure that
// survives. Storage and exports are untouched.)

// MARK: - Single instantaneous fix (field report 17)

/// The centre a SINGLE instantaneous fix stands for, or null when there is
/// no fix worth standing on ("Start plot now").
///
/// `sampleStdXyM` is 0 because the field is not nullable, NOT because the
/// fixes agreed: there is one sample and no spread to measure. The pair that
/// says so is `source == GPS_SINGLE` and `nSamples == 1`, and the tier comes
/// from `classifySingleFix`, which refuses the tiers a spread bound would
/// have to earn.
internal fun singleFixCentre(fix: CLLocationSnapshot?, nowMs: Long): PlotCenterResult? {
    val f = freshFixOrNull(fix, nowMs) ?: return null
    val acc = f.horizontalAccuracyM.toFloat()
    return PlotCenterResult(
        lat = f.latitude,
        lon = f.longitude,
        source = PositionSource.GPS_SINGLE,
        tier = GPSAveraging.classifySingleFix(acc),
        nSamples = 1,
        medianHAccuracyM = acc,
        sampleStdXyM = 0f,
        offsetWalkM = null,
    )
}

// MARK: - Conversion (retired CruiseFlowCoordinator.acceptCenter semantics)

/// Persist a Plot row at the recorded centre, mark the PlannedPlot
/// visited, and make the new plot the ACTIVE cruise plot — Android
/// persists the active plot in settings (iOS derives it as the newest
/// open plot; a just-started plot wins there too). Shared by the sheet's
/// Save and the offset host.
internal suspend fun convertPlannedToActivePlot(
    env: AppEnvironment,
    project: Project,
    planned: PlannedPlot,
    result: PlotCenterResult,
): Plot {
    val design = env.cruiseDesignRepository.forProject(project.id).firstOrNull()
    val plot = Plot(
        id = UUID.randomUUID(),
        projectId = project.id,
        plannedPlotId = planned.id,
        plotNumber = planned.plotNumber,
        centerLat = result.lat,
        centerLon = result.lon,
        positionSource = result.source,
        positionTier = result.tier,
        gpsNSamples = result.nSamples,
        gpsMedianHAccuracyM = result.medianHAccuracyM,
        gpsSampleStdXyM = result.sampleStdXyM,
        offsetWalkM = result.offsetWalkM,
        slopeDeg = 0f,
        aspectDeg = 0f,
        plotAreaAcres = design?.plotAreaAcres ?: 0.1f,
        startedAt = System.currentTimeMillis(),
        closedAt = null,
        closedBy = null,
        notes = "",
        coverPhotoPath = null,
        panoramaPath = null,
    )
    env.plotRepository.create(plot)
    planned.visited = true
    env.plannedPlotRepository.update(planned)
    env.settings.setCruiseProjectId(project.id.toString())
    env.settings.setCruisePlotId(plot.id.toString())
    ForestixLogger.plotOpened(plot.id, plot.projectId)
    return plot
}

// MARK: - Offset host (KEPT OffsetFlowScreen, cruise-map conversion)

/// Route host for CruiseRoutes.OFFSET: loads the project + planned plot,
/// runs the kept Offset-from-Opening flow full-screen (ARCore needs a
/// rendered surface — the iOS sibling pushes it inside the sheet), and
/// lands its PlotCenterResult through the SAME conversion as "Save
/// centre", then returns to the map.
@Composable
fun CruiseOffsetHostScreen(nav: NavController, projectId: String, plannedPlotId: String) {
    val env = LocalAppEnvironment.current
    val scope = rememberCoroutineScope()
    var project by remember { mutableStateOf<Project?>(null) }
    var planned by remember { mutableStateOf<PlannedPlot?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(projectId, plannedPlotId) {
        try {
            project = env.projectRepository.read(UUID.fromString(projectId))
            planned = env.plannedPlotRepository.read(UUID.fromString(plannedPlotId))
        } catch (_: Exception) {
            // fall through to the failure branch below
        }
        if (project == null || planned == null) loadFailed = true
    }

    val loadedProject = project
    val loadedPlanned = planned
    if (loadedProject == null || loadedPlanned == null) {
        Box(
            Modifier.fillMaxSize().background(Forestix.colors.canvas),
            contentAlignment = Alignment.Center,
        ) {
            if (loadFailed) {
                Text(
                    "Planned plot not found.",
                    style = Forestix.type.body,
                    color = Forestix.colors.textSecondary,
                )
            } else {
                CircularProgressIndicator()
            }
        }
        return
    }

    OffsetFlowScreen(
        nav = nav,
        onDone = { result ->
            scope.launch {
                try {
                    convertPlannedToActivePlot(env, loadedProject, loadedPlanned, result)
                    nav.popBackStack()
                } catch (e: Exception) {
                    error = "Storage error: ${e.message ?: e}. " +
                        "The centre was not saved — try again."
                }
            }
        },
    )

    if (error != null) {
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text("Couldn't save the centre") },
            text = { Text(error ?: "") },
            confirmButton = {
                TextButton(onClick = { error = null }) { Text("OK") }
            },
        )
    }
}
