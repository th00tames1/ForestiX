// Port of iOS Screens/CalibrationScreen.swift + ViewModels/CalibrationViewModel.swift.
// Spec §7.10 + REQ-CAL-003/004. Hosts the Wall + Cylinder calibration
// procedures under a segmented picker.
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
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
import com.hcjeong.forestix.data.cruise.Project
import com.hcjeong.forestix.sensors.ArDepthFrame
import com.hcjeong.forestix.sensors.CylinderCalibration
import com.hcjeong.forestix.sensors.CylinderCalibrationResult
import com.hcjeong.forestix.sensors.Vec3d
import com.hcjeong.forestix.sensors.WallCalibration
import com.hcjeong.forestix.sensors.WallCalibrationResult
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixRadius
import com.hcjeong.forestix.ui.theme.ForestixSpace
import java.util.Locale
import java.util.UUID
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

    val newMeasuredCm = MutableStateFlow("")
    val newTrueCm = MutableStateFlow("")

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
        }
        updated.updatedAt = System.currentTimeMillis()
        return updated
    }

    // MARK: - Cylinder procedure

    fun addCylinderSample() {
        val measured = newMeasuredCm.value.toDoubleOrNull()
        val trueV = newTrueCm.value.toDoubleOrNull()
        if (measured == null || trueV == null || measured <= 0 || trueV <= 0) return
        val samples = currentCylinderSamples() +
            CylinderCalibration.Sample(dbhMeasuredCm = measured, dbhTrueCm = trueV)
        _cylinder.value = CylinderState.Collecting(samples = samples)
        newMeasuredCm.value = ""
        newTrueCm.value = ""
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
        newMeasuredCm.value = ""
        newTrueCm.value = ""
    }

    private fun currentCylinderSamples(): List<CylinderCalibration.Sample> =
        when (val c = _cylinder.value) {
            is CylinderState.Collecting -> c.samples
            is CylinderState.Computed -> c.samples
            else -> emptyList()
        }

    private fun describe(err: Throwable): String = when (err) {
        is WallCalibration.Failure.TooFewPoints ->
            "Need at least ${err.minimum} points (captured ${err.count})."
        is CylinderCalibration.Failure.TooFewSamples ->
            "Need at least ${err.minimum} samples (collected ${err.count})."
        is CylinderCalibration.Failure.DegenerateX ->
            "All diameters were identical — vary the target sizes."
        else -> "$err"
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
            updated.updatedAt = System.currentTimeMillis()
            return updated
        }
    }
}

// MARK: - Screen

@Composable
fun CalibrationScreen(nav: NavController, projectId: String? = null) {
    val env = LocalAppEnvironment.current
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

    val controller = remember { ArController() }
    val viewModel = remember { CalibrationViewModel(session = controller) }
    var selectedProcedure by remember { mutableStateOf(CalibrationViewModel.Procedure.WALL) }
    var appliedToast by remember { mutableStateOf<String?>(null) }

    val wall by viewModel.wall.collectAsStateWithLifecycle()
    val cylinder by viewModel.cylinder.collectAsStateWithLifecycle()
    val newMeasuredCm by viewModel.newMeasuredCm.collectAsStateWithLifecycle()
    val newTrueCm by viewModel.newTrueCm.collectAsStateWithLifecycle()

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
                    "No depth frames received — this device may not support the Depth API.")
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
                CalSectionHeader("Current project values")
                LabeledValueRow("Depth noise", String.format(Locale.US, "%.2f mm", p.depthNoiseMm))
                LabeledValueRow("LiDAR bias", String.format(Locale.US, "%.2f mm", p.lidarBiasMm))
                LabeledValueRow("DBH α", String.format(Locale.US, "%.3f", p.dbhCorrectionAlpha))
                LabeledValueRow("DBH β", String.format(Locale.US, "%.4f", p.dbhCorrectionBeta))
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
                    label = { Text("Cylinder") },
                )
            }

            when (selectedProcedure) {
                CalibrationViewModel.Procedure.WALL ->
                    WallSection(wall, controller, viewModel)
                CalibrationViewModel.Procedure.CYLINDER ->
                    CylinderSection(cylinder, newMeasuredCm, newTrueCm, viewModel)
            }

            // MARK: - Apply
            if (p != null) {
                CalDivider()
                CalSectionHeader("Apply")
                val hasAnyComputed =
                    wall is CalibrationViewModel.WallState.Computed ||
                        cylinder is CalibrationViewModel.CylinderState.Computed
                Button(
                    onClick = {
                        scope.launch {
                            val updated = viewModel.applyTo(p)
                            try {
                                env.projectRepository.update(updated)
                                appliedToast = "Calibration values written to project."
                                project = updated
                            } catch (e: Exception) {
                                appliedToast =
                                    "Couldn't save: ${e.message ?: e}. Try again from Settings."
                            }
                        }
                    },
                    enabled = hasAnyComputed,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Apply scanned values to project") }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val updated = CalibrationViewModel.sensibleDefaultsApplied(to = p)
                            try {
                                env.projectRepository.update(updated)
                                appliedToast = "Sensible defaults applied — depth noise 5 mm, " +
                                    "identity DBH correction. Run the wall + cylinder later " +
                                    "for higher precision."
                                project = updated
                            } catch (e: Exception) {
                                appliedToast =
                                    "Couldn't save: ${e.message ?: e}. Try again from Settings."
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Use sensible defaults (skip scan)") }
                Text(
                    "Wall + cylinder results write to this project's depth noise, LiDAR bias, " +
                        "and DBH α/β. The defaults shortcut applies the spec §7.10 identity " +
                        "values without scanning — useful for getting into the field on a " +
                        "freshly installed phone.",
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
            title = { Text("Calibration applied") },
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
) {
    val colors = Forestix.colors
    val type = Forestix.type
    CalSectionHeader("Wall plane (depth noise + bias)")
    Text(
        "Point the phone at a flat wall 1–2 m away and hold while capturing 30 frames.",
        style = type.caption,
        color = colors.textSecondary,
    )
    when (wall) {
        is CalibrationViewModel.WallState.Idle -> {
            Text("Not calibrated yet.", style = type.body, color = colors.textPrimary)
            Button(
                onClick = { viewModel.startWallScan() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) { Text("Start wall scan") }
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
            LabeledValueRow("Depth noise", String.format(Locale.US, "%.2f mm", wall.result.depthNoiseMm))
            LabeledValueRow("Depth bias", String.format(Locale.US, "%.2f mm", wall.result.depthBiasMm))
            LabeledValueRow("Points", "${wall.result.pointCount}")
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
    newMeasuredCm: String,
    newTrueCm: String,
    viewModel: CalibrationViewModel,
) {
    val colors = Forestix.colors
    val type = Forestix.type
    CalSectionHeader("Cylinder correction (α + β · raw DBH)")
    Text(
        "Scan PVC pipes of known diameter. Enter the measured DBH from the scan and the true diameter.",
        style = type.caption,
        color = colors.textSecondary,
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(ForestixSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = newMeasuredCm,
            onValueChange = { viewModel.newMeasuredCm.value = it },
            label = { Text("Measured (cm)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = newTrueCm,
            onValueChange = { viewModel.newTrueCm.value = it },
            label = { Text("True (cm)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Button(onClick = { viewModel.addCylinderSample() }) { Text("Add") }
    }

    when (cylinder) {
        is CalibrationViewModel.CylinderState.Idle -> {
            Text("No samples yet.", style = type.body, color = colors.textPrimary)
        }
        is CalibrationViewModel.CylinderState.Collecting -> {
            SampleList(cylinder.samples)
            Button(
                onClick = { viewModel.computeCylinderCalibration() },
                enabled = cylinder.samples.size >= 2,
            ) { Text("Compute α, β") }
        }
        is CalibrationViewModel.CylinderState.Computed -> {
            SampleList(cylinder.samples)
            LabeledValueRow("α", String.format(Locale.US, "%.3f", cylinder.result.alpha))
            LabeledValueRow("β", String.format(Locale.US, "%.4f", cylinder.result.beta))
            LabeledValueRow("R²", String.format(Locale.US, "%.4f", cylinder.result.rSquared))
            TextButton(onClick = { viewModel.resetCylinder() }) { Text("Reset") }
        }
        is CalibrationViewModel.CylinderState.Failed -> {
            Text(cylinder.message, style = type.body, color = colors.confidenceBad)
            TextButton(onClick = { viewModel.resetCylinder() }) { Text("Reset") }
        }
    }
}

@Composable
private fun SampleList(samples: List<CylinderCalibration.Sample>) {
    val colors = Forestix.colors
    val type = Forestix.type
    Column(verticalArrangement = Arrangement.spacedBy(ForestixSpace.xxs)) {
        for (s in samples) {
            Row(Modifier.fillMaxWidth()) {
                Text(
                    String.format(Locale.US, "raw %.1f cm", s.dbhMeasuredCm),
                    style = type.dataSmall,
                    color = colors.textPrimary)
                Spacer(Modifier.weight(1f))
                Text(
                    String.format(Locale.US, "true %.1f cm", s.dbhTrueCm),
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
