// Port of iOS Screens/CalibrationScreen.swift + ViewModels/CalibrationViewModel.swift.
// Spec §7.10 + REQ-CAL-003/004. Hosts the Wall + Cylinder calibration
// procedures under a segmented picker. The cylinder procedure is LABELLED
// "Round post" throughout the UI — tab, header and Apply helper agree — while
// the type/state names stay CYLINDER so the cross-platform port lines up.
//
// Wall scan: iOS subscribes to the ARKit depth-frame stream; on Android
// the screen mounts the shared ArCameraView while scanning (ARCore needs
// a rendered surface) and pumps `ArController.acquireDepthFrame()` at
// ~10 Hz into `appendPatch`, which back-projects each frame's centre
// 21x21 patch into world space. After 30 frames `WallCalibration.fit`
// runs. Devices without the Depth API fail the scan with a clear message
// after a 15 s grace period.
//
// Apply section (Phase 7.2 parity): writes wall/cylinder results into
// `Project.depthNoiseMm` / `lidarBiasMm` / `dbhCorrectionAlpha` / `beta`,
// plus a "sensible defaults" shortcut that skips scanning entirely.

package com.hcjeong.forestix.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Scanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.hcjeong.forestix.LocalAppEnvironment
import com.hcjeong.forestix.ar.ArCameraView
import com.hcjeong.forestix.ar.ArController
import com.hcjeong.forestix.ar.ArSessionHub
import com.hcjeong.forestix.common.MeasurementFormatter
import com.hcjeong.forestix.common.TruthInput
import com.hcjeong.forestix.common.UnitSystem
import com.hcjeong.forestix.data.cruise.Project
import com.hcjeong.forestix.sensors.ArDepthFrame
import com.hcjeong.forestix.sensors.DBHEstimator
import com.hcjeong.forestix.sensors.CylinderCalibration
import com.hcjeong.forestix.sensors.CylinderCalibrationResult
import com.hcjeong.forestix.sensors.Vec3d
import com.hcjeong.forestix.sensors.WallCalibration
import com.hcjeong.forestix.sensors.WallCalibrationResult
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixProminentButton
import com.hcjeong.forestix.ui.theme.ForestixRadius
import com.hcjeong.forestix.ui.theme.ForestixSpace
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.min
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// MARK: - View model (port of iOS CalibrationViewModel)

class CalibrationViewModel(
    /// Android analogue of the iOS ARKitSessionManager; the hosting
    /// ArCameraView keeps its frame bridge updated while scanning.
    val session: ArController,
) {

    enum class Procedure { WALL, CYLINDER }

    sealed class WallState {
        object Idle : WallState()
        data class Scanning(val progress: Double) : WallState()   // 0…1 (frames / 30)
        data class Computed(val result: WallCalibrationResult) : WallState()
        data class Failed(val message: String) : WallState()
    }

    sealed class CylinderState {
        object Idle : CylinderState()
        data class Collecting(val samples: List<CylinderCalibration.Sample>) : CylinderState()
        data class Computed(
            val result: CylinderCalibrationResult,
            val samples: List<CylinderCalibration.Sample>,
        ) : CylinderState()
        data class Failed(val message: String) : CylinderState()
    }

    private val _wall = MutableStateFlow<WallState>(WallState.Idle)
    val wall: StateFlow<WallState> = _wall.asStateFlow()

    private val _cylinder = MutableStateFlow<CylinderState>(CylinderState.Idle)
    val cylinder: StateFlow<CylinderState> = _cylinder.asStateFlow()

    /// The two round-post widths as TYPED — in whatever unit the screen's
    /// boxes are labelled with, which is the cruiser's, not necessarily
    /// centimetres. `addCylinderSample(unit)` is the only reader and it
    /// converts. The stored `Sample` stays cm, because the fitted alpha is
    /// added in centimetres to every diameter this phone measures
    /// (`DBHEstimator`), so an inch-scale alpha would bias every tree.
    val newMeasuredText = MutableStateFlow("")
    val newTrueText = MutableStateFlow("")

    private var collectedPoints = ArrayList<Vec3d>()
    private val targetWallFrames = 30

    // MARK: - Wall procedure

    /// Apply a final point set — tests inject directly, the live pump
    /// finishes through here after 30 frames.
    fun finishWallScan(points: List<Vec3d>) {
        WallCalibration.fit(points).fold(
            onSuccess = { _wall.value = WallState.Computed(it) },
            onFailure = { _wall.value = WallState.Failed(describe(it)) },
        )
    }

    /// Begin live wall-scan collection. The screen mounts the AR camera
    /// and pumps `appendPatch(frame)` while this state is Scanning.
    fun startWallScan() {
        if (_wall.value !is WallState.Idle) return
        _wall.value = WallState.Scanning(progress = 0.0)
        collectedPoints = ArrayList()
    }

    fun cancelWallScan() {
        collectedPoints = ArrayList()
        _wall.value = WallState.Idle
    }

    /// Depth unavailable / other pump-level failures.
    fun failWallScan(message: String) {
        if (_wall.value !is WallState.Scanning) return
        collectedPoints = ArrayList()
        _wall.value = WallState.Failed(message)
    }

    /// Back-project a 21x21 patch from the depth-map center into world
    /// space and accumulate. The ArDepthFrame pose is expressed in the
    /// OpenCV-style camera frame (+X right, +Y down, +Z forward), so the
    /// camera-space point is (xc, yc, +d) — unlike the iOS ARKit frame's
    /// (xc, yc, −d).
    fun appendPatch(frame: ArDepthFrame) {
        if (_wall.value !is WallState.Scanning) return
        val cxPix = frame.width / 2
        val cyPix = frame.height / 2
        val half = 10
        val p = frame.pose
        for (dy in -half..half) {
            for (dx in -half..half) {
                val x = cxPix + dx
                val y = cyPix + dy
                if (x < 0 || x >= frame.width || y < 0 || y >= frame.height) continue
                val d = frame.depthAt(x, y)
                if (!d.isFinite() || d <= 0.1f || d >= 5.0f) continue
                // Pinhole back-projection.
                val xc = (x - frame.cx) * d / frame.fx
                val yc = (y - frame.cy) * d / frame.fy
                val zc = d.toDouble()
                val wx = p[0] * xc + p[4] * yc + p[8] * zc + p[12]
                val wy = p[1] * xc + p[5] * yc + p[9] * zc + p[13]
                val wz = p[2] * xc + p[6] * yc + p[10] * zc + p[14]
                collectedPoints.add(Vec3d(wx, wy, wz))
            }
        }

        val frames = collectedPoints.size / 441   // 21*21
        val progress = min(1.0, frames.toDouble() / targetWallFrames)
        _wall.value = WallState.Scanning(progress = progress)

        if (frames >= targetWallFrames) {
            finishWallScan(collectedPoints)
        }
    }

    fun resetWall() {
        collectedPoints = ArrayList()
        _wall.value = WallState.Idle
    }

    // MARK: - Apply to project

    /// Write the (wall, cylinder) results back into a Project, returning
    /// a fresh copy. The caller persists via the ProjectRepository.
    fun applyTo(project: Project): Project {
        val updated = project.copy()
        val w = _wall.value
        if (w is WallState.Computed) {
            updated.depthNoiseMm = w.result.depthNoiseMm.toFloat()
            updated.lidarBiasMm = w.result.depthBiasMm.toFloat()
        }
        val c = _cylinder.value
        if (c is CylinderState.Computed) {
            updated.dbhCorrectionAlpha = c.result.alpha.toFloat()
            updated.dbhCorrectionBeta = c.result.beta.toFloat()
            // Stamp WHAT was calibrated. Without it the coefficients outlive
            // the estimator they correct and stack with its successor's.
            updated.dbhCalibrationEpoch = DBHEstimator.ESTIMATOR_EPOCH
        }
        updated.updatedAt = System.currentTimeMillis()
        return updated
    }

    // MARK: - Cylinder procedure

    /// `unit` is the unit the two boxes are LABELLED in — cm or inches. Both
    /// widths are converted to the stored centimetre base here, in one place,
    /// because the fit's offset alpha is applied in centimetres to every
    /// diameter the phone measures for the project (`sensors/DBHEstimator`):
    /// a fit taken on inch-scale numbers lands as a ~2.5x understated cm
    /// offset on every tree, and nothing downstream would say so. Beta is a
    /// ratio and survives an inch/inch fit either way.
    fun addCylinderSample(unit: TruthInput.Unit = TruthInput.Unit.CM) {
        val measured = TruthInput.parsePositiveBase(newMeasuredText.value, unit)
        val trueV = TruthInput.parsePositiveBase(newTrueText.value, unit)
        if (measured == null || trueV == null) return
        val samples = currentCylinderSamples() +
            CylinderCalibration.Sample(dbhMeasuredCm = measured, dbhTrueCm = trueV)
        _cylinder.value = CylinderState.Collecting(samples = samples)
        newMeasuredText.value = ""
        newTrueText.value = ""
    }

    fun computeCylinderCalibration() {
        val samples = currentCylinderSamples()
        CylinderCalibration.fit(samples).fold(
            onSuccess = { _cylinder.value = CylinderState.Computed(it, samples = samples) },
            onFailure = { _cylinder.value = CylinderState.Failed(describe(it)) },
        )
    }

    fun resetCylinder() {
        _cylinder.value = CylinderState.Idle
        newMeasuredText.value = ""
        newTrueText.value = ""
    }

    private fun currentCylinderSamples(): List<CylinderCalibration.Sample> =
        when (val c = _cylinder.value) {
            is CylinderState.Collecting -> c.samples
            is CylinderState.Computed -> c.samples
            else -> emptyList()
        }

    private fun describe(err: Throwable): String = when (err) {
        is WallCalibration.Failure.TooFewPoints ->
            "The scan only picked up ${err.count} points on the wall and it needs " +
                "${err.minimum}. Stand closer so the wall fills the screen, then scan again."
        is CylinderCalibration.Failure.TooFewSamples ->
            "You have entered ${err.count} posts and it needs ${err.minimum}. " +
                "Measure another post and add it."
        is CylinderCalibration.Failure.DegenerateX ->
            "Every post you entered is the same width. Add posts of different widths " +
                "so the app can tell how the error changes with size."
        // A raw exception interpolated into the red line on the Calibration
        // screen put a type name and its message in front of a cruiser —
        // unreadable, and it named internals on a screen that sits in the
        // ordinary Settings group. Anything that isn't one of the three
        // cases above gets a sentence they can act on instead. Nothing
        // about the failure itself changes.
        else -> "The app couldn't work out a correction from that. " +
            "Check what you entered and run the scan again."
    }

    companion object {
        /// Apply spec §7.10 identity / sensible defaults without scanning:
        /// nominal depth-sensor noise (5 mm) and an identity DBH
        /// correction (α = 0, β = 1).
        fun sensibleDefaultsApplied(to: Project): Project {
            val updated = to.copy()
            updated.depthNoiseMm = 5f
            updated.lidarBiasMm = 0f
            updated.dbhCorrectionAlpha = 0f
            updated.dbhCorrectionBeta = 1f
            updated.dbhCalibrationEpoch = 0
            updated.updatedAt = System.currentTimeMillis()
            return updated
        }
    }
}

// MARK: - Screen

@Composable
fun CalibrationScreen(nav: NavController, projectId: String? = null) {
    val env = LocalAppEnvironment.current
    val settings by env.settings.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val colors = Forestix.colors
    val type = Forestix.type

    var project by remember { mutableStateOf<Project?>(null) }
    LaunchedEffect(projectId) {
        if (projectId != null) {
            project = try {
                env.projectRepository.read(UUID.fromString(projectId))
            } catch (_: Exception) {
                null
            }
        }
    }

    // Shared app-scoped AR session (one ARCore world across the AR screens).
    val controller = ArSessionHub.controller
    val viewModel = remember { CalibrationViewModel(session = controller) }
    var selectedProcedure by remember { mutableStateOf(CalibrationViewModel.Procedure.WALL) }
    var appliedToast by remember { mutableStateOf<String?>(null) }

    val wall by viewModel.wall.collectAsStateWithLifecycle()
    val cylinder by viewModel.cylinder.collectAsStateWithLifecycle()
    val newMeasuredText by viewModel.newMeasuredText.collectAsStateWithLifecycle()
    val newTrueText by viewModel.newTrueText.collectAsStateWithLifecycle()

    // Depth-frame pump — the Android analogue of the iOS latestDepthFrame
    // subscription. Runs only while a wall scan is live; if the device
    // never produces a depth frame, fail with a clear message.
    val isScanningWall = wall is CalibrationViewModel.WallState.Scanning
    LaunchedEffect(isScanningWall) {
        if (!isScanningWall) return@LaunchedEffect
        val startedAt = System.currentTimeMillis()
        var gotAnyFrame = false
        while (viewModel.wall.value is CalibrationViewModel.WallState.Scanning) {
            delay(100)
            val frame = controller.acquireDepthFrame()
            if (frame != null) {
                gotAnyFrame = true
                viewModel.appendPatch(frame)
            } else if (!gotAnyFrame && System.currentTimeMillis() - startedAt > 15_000) {
                viewModel.failWallScan(
                    "This phone isn't returning any depth readings, so it probably can't " +
                        "measure distance with its camera. Skip this scan — the app will " +
                        "measure with its standard allowances instead.")
            }
        }
    }

    ForestixScaffold(nav, title = "Calibration") { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(ForestixSpace.md),
            verticalArrangement = Arrangement.spacedBy(ForestixSpace.sm),
        ) {
            // MARK: - Current project values
            val p = project
            if (p != null) {
                // This screen hangs off a NON-developer settings group, so what a
                // cruiser reads here is words: what the two scans are for, and
                // whether this project is running on a scan or on the standard
                // settings. The fitted numbers themselves (α / β and the wall's
                // mm figures) say nothing a cruiser can act on, so they moved
                // behind developer mode. The stored fields (depthNoiseMm /
                // lidarBiasMm / dbhCorrectionAlpha / dbhCorrectionBeta) and the
                // export are UNCHANGED — this is a display change only.
                CalSectionHeader("How this phone is set up")
                Text(
                    "Two short scans tune the app to this phone. The wall scan learns how " +
                        "steady its distance readings are; the round-post scan corrects the " +
                        "widths it measures. Both are saved with this project.",
                    style = type.caption,
                    color = colors.textSecondary,
                )
                // Identity correction (α = 0, β = 1) is exactly the untouched /
                // "standard settings" state — see sensibleDefaultsApplied.
                //
                // THREE STATES, not two — iOS CalibrationScreen parity. A
                // round-post scan corrects whatever the width estimator got
                // wrong on the day it was fitted, so when that estimator
                // changes the scan is answering a question that no longer
                // exists and is no longer applied. "Corrected" would be false
                // and "not corrected" would hide a scan the cruiser remembers
                // doing, so the stale case gets its own sentence and names the
                // one action that fixes it.
                val widthsCorrected =
                    p.dbhCorrectionAlpha != 0f || p.dbhCorrectionBeta != 1f
                val widthsStale = widthsCorrected &&
                    p.dbhCalibrationEpoch != DBHEstimator.ESTIMATOR_EPOCH
                Text(
                    if (widthsStale) {
                        "Your round-post scan was made with an earlier version of the " +
                            "width measurement and no longer applies, so it is being " +
                            "ignored. Run the round-post scan again to correct widths " +
                            "on this project."
                    } else if (widthsCorrected) {
                        "Widths are being corrected using your round-post scan."
                    } else {
                        "Widths are being used exactly as the phone measures them — " +
                            "no round-post scan has been applied yet."
                    },
                    style = type.body,
                    color = if (widthsStale) colors.confidenceWarn else colors.textPrimary,
                )
                if (settings.developerMode) {
                    LabeledValueRow("Depth reading spread", String.format(Locale.US, "%.2f mm", p.depthNoiseMm))
                    LabeledValueRow("Depth sensor offset", String.format(Locale.US, "%.2f mm", p.lidarBiasMm))
                    LabeledValueRow("Fitted α (cm)", String.format(Locale.US, "%.3f", p.dbhCorrectionAlpha))
                    LabeledValueRow("Fitted β", String.format(Locale.US, "%.4f", p.dbhCorrectionBeta))
                }
                CalDivider()
            }

            // MARK: - Procedure picker (iOS segmented control)
            Row(horizontalArrangement = Arrangement.spacedBy(ForestixSpace.sm)) {
                FilterChip(
                    selected = selectedProcedure == CalibrationViewModel.Procedure.WALL,
                    onClick = { selectedProcedure = CalibrationViewModel.Procedure.WALL },
                    label = { Text("Wall") },
                )
                FilterChip(
                    selected = selectedProcedure == CalibrationViewModel.Procedure.CYLINDER,
                    onClick = { selectedProcedure = CalibrationViewModel.Procedure.CYLINDER },
                    // Names the thing the cruiser actually scans, and matches the
                    // section header ("Round-post scan") and the Apply helper below.
                    label = { Text("Round post") },
                )
            }

            when (selectedProcedure) {
                CalibrationViewModel.Procedure.WALL ->
                    WallSection(wall, controller, viewModel, settings.developerMode,
                        settings.unitSystem)
                CalibrationViewModel.Procedure.CYLINDER ->
                    CylinderSection(cylinder, newMeasuredText, newTrueText, viewModel,
                        settings.developerMode, settings.unitSystem)
            }

            // MARK: - Apply
            if (p != null) {
                CalDivider()
                CalSectionHeader("Apply")
                val hasAnyComputed =
                    wall is CalibrationViewModel.WallState.Computed ||
                        cylinder is CalibrationViewModel.CylinderState.Computed
                // iOS renders both Apply actions as plain Form buttons
                // (icon + tinted label), not filled/outlined pills.
                TextButton(
                    onClick = {
                        scope.launch {
                            val updated = viewModel.applyTo(p)
                            try {
                                env.projectRepository.update(updated)
                                // The rest of this screen talks about what the two
                                // scans DO. The confirmation used to answer in the
                                // storage layer's voice ("values written to project"),
                                // which told the cruiser nothing about what had just
                                // changed for them.
                                appliedToast = "Your scans are now in use for this " +
                                    "project. Widths and distances measured from here " +
                                    "on are corrected with them."
                                project = updated
                            } catch (e: Exception) {
                                appliedToast =
                                    "Couldn't save: ${e.message ?: e}. Try again from Settings."
                            }
                        }
                    },
                    enabled = hasAnyComputed,
                ) {
                    Icon(Icons.Filled.SaveAlt, contentDescription = null,
                        modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Apply scanned values to project")
                }
                TextButton(
                    onClick = {
                        scope.launch {
                            val updated = CalibrationViewModel.sensibleDefaultsApplied(to = p)
                            try {
                                env.projectRepository.update(updated)
                                appliedToast = "Standard settings applied. The app will measure " +
                                    "with its usual allowances and will not correct this " +
                                    "phone's widths. Run the wall and round-post scans later " +
                                    "to tune it to this phone."
                                project = updated
                            } catch (e: Exception) {
                                appliedToast =
                                    "Couldn't save: ${e.message ?: e}. Try again from Settings."
                            }
                        }
                    },
                ) {
                    Icon(Icons.Filled.AutoFixHigh, contentDescription = null,
                        modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Use the standard settings (skip the scans)")
                }
                Text(
                    "Applies your wall and round-post scans to this project. No scans yet? " +
                        "Use the standard settings to start measuring now and scan later.",
                    style = type.caption,
                    color = colors.textSecondary,
                )
            }
        }
    }

    // iOS "Calibration applied" alert.
    if (appliedToast != null) {
        AlertDialog(
            onDismissRequest = { appliedToast = null },
            title = { Text("Saved to this project") },
            text = { Text(appliedToast ?: "") },
            confirmButton = {
                TextButton(onClick = { appliedToast = null }) { Text("OK") }
            },
        )
    }
}

// MARK: - Wall section

@Composable
private fun WallSection(
    wall: CalibrationViewModel.WallState,
    controller: ArController,
    viewModel: CalibrationViewModel,
    developerMode: Boolean,
    unitSystem: UnitSystem,
) {
    val colors = Forestix.colors
    val type = Forestix.type
    CalSectionHeader("Wall scan")
    Text(
        // The standing distance follows the cruiser's units like every other
        // instruction on the scan screens; the range itself is the depth
        // camera's and does not move.
        "Shows the app how steady this phone's distance readings are. Point it at a " +
            "flat wall " + MeasurementFormatter.guidanceRange(1.0, 2.0, unitSystem) +
            " away and hold still until the bar fills.",
        style = type.caption,
        color = colors.textSecondary,
    )
    when (wall) {
        is CalibrationViewModel.WallState.Idle -> {
            Text("No wall scan yet.", style = type.body, color = colors.textPrimary)
            ForestixProminentButton(
                label = "Start wall scan",
                icon = Icons.Filled.Scanner,      // iOS scanner.fill
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
            ) { viewModel.startWallScan() }
        }
        is CalibrationViewModel.WallState.Scanning -> {
            // Live camera keeps the ARCore session (and depth frames) alive.
            ArCameraView(
                controller = controller,
                markers = emptyList(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .clip(ForestixRadius.card),
            )
            LinearProgressIndicator(
                progress = { wall.progress.toFloat() },
                color = colors.primary,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Scanning wall… ${(wall.progress * 100).toInt()}%",
                style = type.body,
                color = colors.textPrimary,
            )
            TextButton(onClick = { viewModel.cancelWallScan() }) { Text("Cancel") }
        }
        is CalibrationViewModel.WallState.Computed -> {
            // What the scan FOUND, in words plus the one number that is a real
            // quantity in a real unit. depthBiasMm / pointCount are inputs to the
            // estimator, not something a cruiser acts on, so they sit behind
            // developer mode with the fit coefficients.
            Text(
                "Wall scan done. Across ${wall.result.pointCount} points on the wall this " +
                    "phone's distance readings varied by about " +
                    String.format(Locale.US, "%.1f mm", wall.result.depthNoiseMm) + ".",
                style = type.body,
                color = colors.textPrimary,
            )
            if (developerMode) {
                LabeledValueRow("Depth reading spread", String.format(Locale.US, "%.2f mm", wall.result.depthNoiseMm))
                LabeledValueRow("Depth sensor offset", String.format(Locale.US, "%.2f mm", wall.result.depthBiasMm))
                LabeledValueRow("Points", "${wall.result.pointCount}")
            }
            TextButton(onClick = { viewModel.resetWall() }) { Text("Reset") }
        }
        is CalibrationViewModel.WallState.Failed -> {
            Text(wall.message, style = type.body, color = colors.confidenceBad)
            TextButton(onClick = { viewModel.resetWall() }) { Text("Retry") }
        }
    }
}

// MARK: - Cylinder section

@Composable
private fun CylinderSection(
    cylinder: CalibrationViewModel.CylinderState,
    newMeasuredText: String,
    newTrueText: String,
    viewModel: CalibrationViewModel,
    developerMode: Boolean,
    unitSystem: UnitSystem,
) {
    val colors = Forestix.colors
    val type = Forestix.type
    // The unit a round-post width is TYPED in. Both boxes and both read-back
    // columns carry it, and `addCylinderSample(unit)` converts with it — the
    // fitted alpha is added in CENTIMETRES to every diameter this phone
    // measures, so a fit taken on inch-scale numbers biases the whole project
    // and nothing downstream says so.
    val postUnit = TruthInput.defaultUnit(
        TruthInput.Quantity.DIAMETER, imperial = unitSystem == UnitSystem.IMPERIAL)
    CalSectionHeader("Round-post scan")
    Text(
        "Corrects the widths this phone measures. Scan round posts you have already " +
            "measured by hand, then enter both widths for each one — the app works " +
            "out how far the scan runs wide or narrow and takes it off every tree.",
        style = type.caption,
        color = colors.textSecondary,
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(ForestixSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = newMeasuredText,
            onValueChange = { viewModel.newMeasuredText.value = it },
            label = { Text("Scanned (${postUnit.raw})") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = newTrueText,
            onValueChange = { viewModel.newTrueText.value = it },
            label = { Text("By hand (${postUnit.raw})") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        ForestixProminentButton(label = "Add") { viewModel.addCylinderSample(postUnit) }
    }

    when (cylinder) {
        is CalibrationViewModel.CylinderState.Idle -> {
            Text("No posts entered yet.", style = type.body, color = colors.textPrimary)
        }
        is CalibrationViewModel.CylinderState.Collecting -> {
            SampleList(cylinder.samples, unitSystem)
            // iOS renders this as a plain Form button.
            TextButton(
                onClick = { viewModel.computeCylinderCalibration() },
                enabled = cylinder.samples.size >= 2,
            ) { Text("Work out the correction") }
        }
        is CalibrationViewModel.CylinderState.Computed -> {
            SampleList(cylinder.samples, unitSystem)
            // α / β / R² are the fitted coefficients — nothing a cruiser can act
            // on, and R² is a grade rather than a quantity. What a cruiser needs
            // is how close the CORRECTED width now lands to the hand
            // measurement, IN THE UNIT THEY MEASURED IN — the one number that
            // tells them how good the correction is is useless in a unit they
            // did not type. Display only: the fit, its thresholds and what gets
            // stored are untouched.
            Text(
                "Correction worked out from ${cylinder.samples.size} posts. Corrected widths " +
                    "land within " +
                    String.format(
                        Locale.US, "%.1f %s",
                        TruthInput.fromBase(meanAbsResidualCm(cylinder), postUnit),
                        postUnit.raw,
                    ) +
                    " of your hand measurements on average.",
                style = type.body,
                color = colors.textPrimary,
            )
            if (developerMode) {
                LabeledValueRow("Fitted α", String.format(Locale.US, "%.3f cm", cylinder.result.alpha))
                LabeledValueRow("Fitted β", String.format(Locale.US, "%.4f", cylinder.result.beta))
                LabeledValueRow("R²", String.format(Locale.US, "%.4f", cylinder.result.rSquared))
            }
            TextButton(onClick = { viewModel.resetCylinder() }) { Text("Reset") }
        }
        is CalibrationViewModel.CylinderState.Failed -> {
            Text(cylinder.message, style = type.body, color = colors.confidenceBad)
            TextButton(onClick = { viewModel.resetCylinder() }) { Text("Reset") }
        }
    }
}

/// Mean |corrected − hand-measured| over the entered posts, in cm — the one
/// number that tells a cruiser how good the correction is, in the unit they
/// measured in. Read-only: it re-applies the fit that was already computed
/// and changes no check, threshold or stored value.
private fun meanAbsResidualCm(state: CalibrationViewModel.CylinderState.Computed): Double {
    if (state.samples.isEmpty()) return 0.0
    val a = state.result.alpha
    val b = state.result.beta
    return state.samples.sumOf { abs(a + b * it.dbhMeasuredCm - it.dbhTrueCm) } /
        state.samples.size
}

@Composable
private fun SampleList(
    samples: List<CylinderCalibration.Sample>,
    unitSystem: UnitSystem,
) {
    val colors = Forestix.colors
    val type = Forestix.type
    Column(verticalArrangement = Arrangement.spacedBy(ForestixSpace.xxs)) {
        for (s in samples) {
            // Read back in the unit they were typed in — a post entered as
            // 12 in must not reappear as "30.5 cm" on the same screen.
            Row(Modifier.fillMaxWidth()) {
                Text(
                    "scanned " + MeasurementFormatter.diameter(s.dbhMeasuredCm, unitSystem),
                    style = type.dataSmall,
                    color = colors.textPrimary)
                Spacer(Modifier.weight(1f))
                Text(
                    "by hand " + MeasurementFormatter.diameter(s.dbhTrueCm, unitSystem),
                    style = type.dataSmall,
                    color = colors.textPrimary)
            }
        }
    }
}

// MARK: - Shared section chrome

@Composable
private fun CalSectionHeader(title: String) {
    Text(
        title.uppercase(Locale.US),
        style = Forestix.type.sectionHead,
        color = Forestix.colors.textSecondary,
        modifier = Modifier.padding(top = ForestixSpace.xs),
    )
}

@Composable
private fun CalDivider() {
    HorizontalDivider(color = Forestix.colors.textSecondary.copy(alpha = 0.15f))
}

@Composable
private fun LabeledValueRow(label: String, value: String) {
    val colors = Forestix.colors
    val type = Forestix.type
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = type.body, color = colors.textPrimary)
        Spacer(Modifier.weight(1f))
        Text(value, style = type.data, color = colors.textSecondary)
    }
}
