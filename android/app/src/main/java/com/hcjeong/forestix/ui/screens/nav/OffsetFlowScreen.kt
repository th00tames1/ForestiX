// Port of iOS Screens/OffsetFlowScreen.swift + ViewModels/OffsetFlowViewModel.swift.
// Spec §4.5 + §7.3.2 Offset-from-Opening flow. REQ-CTR-002.
//
// Walks the user through the five-step recovery when GPS at the plot
// center is too weak to accept:
//   A. Stand at plot center — snapshot the AR camera pose as `plotPose`.
//   B. Walk to an opening where sky is visible.
//   C. Run 30 s GPS averaging there → `openingFix`; snapshot `openingPose`.
//   D. Walk back to plot center (tracking must stay TRACKING throughout).
//   E. Confirm → OffsetFromOpening.compute to back-solve plot lat/lon.
//
// iOS runs the ARKit session headless behind a plain stepper; ARCore
// needs a rendered surface for the session to produce poses, so the
// Android screen hosts the shared ArCameraView full-bleed with the
// stepper overlaid on a bottom panel (the ARBoundaryScreen pattern).
// The walk-back distance is pumped from the camera pose at 4 Hz — the
// Android analogue of the iOS $currentCameraWorldPosition sink.

package com.hcjeong.forestix.ui.screens.nav

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.hcjeong.forestix.ar.ArCameraView
import com.hcjeong.forestix.ar.ArController
import com.hcjeong.forestix.ar.ArSessionHub
import com.hcjeong.forestix.ar.Vec3
import com.hcjeong.forestix.data.cruise.PlotCenterResult
import com.hcjeong.forestix.positioning.GPSAveraging
import com.hcjeong.forestix.positioning.LocationService
import com.hcjeong.forestix.positioning.OffsetFromOpening
import com.hcjeong.forestix.ui.screens.MeasureBackButton
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixProminentButton
import com.hcjeong.forestix.ui.theme.ForestixSpace
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// MARK: - Anchor compass

/// Rotation-vector compass sampled while the offset flow is on screen —
/// supplies the true-north azimuth of the CAMERA-FORWARD direction that,
/// paired with the same-instant ARCore camera yaw, aligns the arbitrary-yaw
/// ARCore world frame with north (iOS gets this for free from ARKit's
/// `.gravityAndHeading` world alignment; ARCore has no equivalent).
///
/// Sensor assumptions: the phone is held upright in the AR posture (portrait,
/// back camera aimed forward), so the rotation matrix is remapped with
/// (AXIS_X, AXIS_Z) to make azimuth track the camera-forward direction; the
/// magnetometer must be reasonably calibrated. Declination is added when a
/// GPS fix is available (same prefer-true rule as LocationService's heading).
private class AnchorCompass(
    context: Context,
    private val location: LocationService,
) : SensorEventListener {

    private val sensorManager: SensorManager? =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    /// Latest camera-forward azimuth in degrees true north (0…360);
    /// null before the first sensor sample.
    @Volatile
    var azimuthTrueDeg: Double? = null
        private set

    private val rotationMatrix = FloatArray(9)
    private val remapped = FloatArray(9)
    private val orientation = FloatArray(3)

    fun start() {
        sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        // Upright AR posture: remap so azimuth follows the back camera's
        // forward ray instead of the (near-vertical) device Y axis.
        SensorManager.remapCoordinateSystem(
            rotationMatrix, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped)
        SensorManager.getOrientation(remapped, orientation)
        var deg = Math.toDegrees(orientation[0].toDouble())
        location.latestSnapshot.value?.let { snap ->
            deg += GeomagneticField(
                snap.latitude.toFloat(),
                snap.longitude.toFloat(),
                0f,
                System.currentTimeMillis(),
            ).declination
        }
        azimuthTrueDeg = (deg + 360.0) % 360.0
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

// MARK: - View model (port of iOS OffsetFlowViewModel)

class OffsetFlowViewModel(
    val location: LocationService,
    /// Android analogue of the iOS ARKitSessionManager: the shared frame
    /// bridge the hosting ArCameraView keeps updated.
    val session: ArController,
    private val scope: CoroutineScope,
    val openingAveragingDurationS: Int = 30,
    /// Latest camera-forward compass azimuth in degrees true (null when no
    /// sample yet) — sampled at anchor time to heading-align the AR frame.
    private val compassAzimuthTrueDeg: () -> Double? = { null },
) {

    sealed class Step {
        object AnchorPlot : Step()                                              // A
        object WalkToOpening : Step()                                           // B
        data class AveragingAtOpening(val secondsElapsed: Int, val sampleCount: Int) : Step()  // C
        data class WalkBack(val distanceFromPlotM: Float?) : Step()             // D
        data class Computed(val result: PlotCenterResult) : Step()              // E
        data class Failed(val reason: String) : Step()
    }

    private val _step = MutableStateFlow<Step>(Step.AnchorPlot)
    val step: StateFlow<Step> = _step.asStateFlow()

    var plotPoseWorld: Vec3? = null
        private set
    var openingPoseWorld: Vec3? = null
        private set
    var openingFix: PlotCenterResult? = null
        private set

    /// Yaw (degrees) added to ARCore-world pseudo-headings to obtain true
    /// compass headings, sampled at anchor time: compass azimuth of the
    /// camera forward minus that forward's yaw in the ARCore world frame.
    /// ARCore's world yaw is arbitrary (session-start camera direction),
    /// unlike iOS `.gravityAndHeading`; this angle re-aligns the walk
    /// displacement to the X≈E / −Z≈N convention OffsetFromOpening assumes
    /// (see the "Compass note" in OffsetFromOpening.kt).
    var worldYawToNorthDeg: Double? = null
        private set

    private var tickJob: Job? = null
    private var averagingStartedAt: Long? = null

    // MARK: - Step transitions

    fun anchorPlotCenter() {
        val p = session.currentCameraPosition()
        if (p == null) {
            _step.value = Step.Failed("AR pose unavailable — tracking not started")
            return
        }
        // Heading alignment: pair a compass azimuth with the same-instant
        // AR camera yaw so device heading and world yaw correspond.
        val azimuth = compassAzimuthTrueDeg()
        val camYaw = session.cameraWorldYawDeg()
        if (azimuth == null || camYaw == null) {
            _step.value = Step.Failed(
                "Compass unavailable — hold the phone upright, aim level, and re-anchor.")
            return
        }
        worldYawToNorthDeg = azimuth - camYaw
        plotPoseWorld = p
        // Begin the tracking-normal latch that must hold through the walk
        // to the opening and back (iOS `trackingStayedNormal`).
        session.beginTrackingWatch()
        _step.value = Step.WalkToOpening
    }

    fun beginOpeningAveraging() {
        val p = session.currentCameraPosition()
        if (p == null) {
            _step.value = Step.Failed("AR pose unavailable at opening")
            return
        }
        openingPoseWorld = p
        location.clearBuffer()
        location.requestAuthorization()
        location.start()
        averagingStartedAt = System.currentTimeMillis()
        _step.value = Step.AveragingAtOpening(secondsElapsed = 0, sampleCount = 0)
        tickJob?.cancel()
        tickJob = scope.launch {
            while (isActive) {
                delay(1_000)
                tickAveraging()
            }
        }
    }

    private fun tickAveraging() {
        if (_step.value !is Step.AveragingAtOpening) return
        val startedAt = averagingStartedAt ?: return
        val elapsed = ((System.currentTimeMillis() - startedAt) / 1_000L).toInt()
        val samples = location.buffer.value.size
        if (elapsed >= openingAveragingDurationS) {
            finishOpeningAveraging()
        } else {
            _step.value = Step.AveragingAtOpening(secondsElapsed = elapsed, sampleCount = samples)
        }
    }

    private fun finishOpeningAveraging() {
        tickJob?.cancel()
        tickJob = null
        val result = GPSAveraging.compute(
            GPSAveraging.Input(samples = location.buffer.value))
        if (result == null) {
            _step.value = Step.Failed("Not enough clean samples at opening (need 30 ≤ 20 m).")
            return
        }
        openingFix = result
        _step.value = Step.WalkBack(distanceFromPlotM = null)
    }

    /// Pump a camera position (polled from the AR frame by the screen —
    /// the Android analogue of the iOS $currentCameraWorldPosition sink).
    fun updateWalkBackDistance(camera: Vec3?) {
        if (_step.value !is Step.WalkBack) return
        val plot = plotPoseWorld ?: return
        if (camera == null) return
        _step.value = Step.WalkBack(distanceFromPlotM = (camera - plot).length())
    }

    fun confirmPlotCenter() {
        val plotPose = plotPoseWorld
        val openingPose = openingPoseWorld
        val fix = openingFix
        val yawDeg = worldYawToNorthDeg
        if (plotPose == null || openingPose == null || fix == null || yawDeg == null) {
            _step.value = Step.Failed("Missing opening fix or pose snapshots.")
            return
        }
        // Rotate the AR displacement from the arbitrary-yaw ARCore world
        // frame into the heading-aligned ENU frame OffsetFromOpening
        // assumes (iOS skips this because ARKit runs `.gravityAndHeading`).
        // Rotating the plot snapshot about the opening snapshot keeps the
        // shared math's X≈E / −Z≈N decomposition valid; Y and the walk
        // distance are rotation-invariant.
        val theta = Math.toRadians(yawDeg)
        val east0 = (plotPose.x - openingPose.x).toDouble()
        val north0 = (-(plotPose.z - openingPose.z)).toDouble()
        val east = east0 * cos(theta) + north0 * sin(theta)
        val north = north0 * cos(theta) - east0 * sin(theta)
        val alignedPlotPose = Vec3(
            openingPose.x + east.toFloat(),
            plotPose.y,
            openingPose.z - north.toFloat())
        val input = OffsetFromOpening.Input(
            openingFix = fix,
            openingPointWorld = openingPose,
            plotPointWorld = alignedPlotPose,
            trackingStateWasNormalThroughout = session.trackingStayedNormalSinceWatch())
        val result = OffsetFromOpening.compute(input)
        if (result == null) {
            _step.value = Step.Failed("AR tracking was interrupted — offset invalid.")
            return
        }
        _step.value = Step.Computed(result)
    }

    fun cancel() {
        tickJob?.cancel()
        tickJob = null
        location.stop()
        _step.value = Step.AnchorPlot
        plotPoseWorld = null
        openingPoseWorld = null
        openingFix = null
        worldYawToNorthDeg = null
    }
}

// MARK: - Screen

@Composable
fun OffsetFlowScreen(
    nav: NavController,
    onDone: (PlotCenterResult) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = Forestix.colors
    val type = Forestix.type

    // Shared app-scoped AR session (one ARCore world across the AR screens).
    val controller = ArSessionHub.controller
    // PRIVATE location client — deliberately NOT LocationService.shared:
    // the offset flow's plot-centre averaging clearBuffer()s and reads the
    // rolling buffer as its own state, which must stay isolated from the
    // passive subscribers on the shared instance (same rule as
    // RecordCentreSheet; mirror of iOS).
    val location = remember { LocationService(context) }
    // Live compass so anchorPlotCenter can heading-align the ARCore world
    // frame (see AnchorCompass docs for the sensor assumptions).
    val compass = remember { AnchorCompass(context, location) }
    val viewModel = remember {
        OffsetFlowViewModel(
            location = location,
            session = controller,
            scope = scope,
            compassAzimuthTrueDeg = { compass.azimuthTrueDeg })
    }

    // Runtime location permission up front so step C can start averaging
    // without a mid-flow prompt.
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        // FINE + COARSE requested together (Android 12+ requirement);
        // either grant is enough to run, coarse just degrades the tier.
        location.onPermissionResult(results.values.any { it })
    }
    LaunchedEffect(Unit) {
        if (!LocationService.hasLocationPermission(context)) {
            launcher.launch(LocationService.PERMISSIONS)
        }
    }
    DisposableEffect(Unit) {
        compass.start()
        onDispose {
            compass.stop()
            viewModel.cancel()
        }
    }

    // 4 Hz walk-back distance pump.
    LaunchedEffect(Unit) {
        while (true) {
            delay(250)
            viewModel.updateWalkBackDistance(controller.currentCameraPosition())
        }
    }

    val step by viewModel.step.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize()) {
        // Live camera keeps the ARCore session (and its pose stream) alive.
        ArCameraView(
            controller = controller,
            markers = emptyList(),
            modifier = Modifier.fillMaxSize(),
        )

        MeasureBackButton { nav.popBackStack() }

        Column(
            verticalArrangement = Arrangement.spacedBy(ForestixSpace.md),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = ForestixSpace.md)
                .padding(bottom = ForestixSpace.lg)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black.copy(alpha = 0.65f))
                .padding(ForestixSpace.md),
        ) {
            // MARK: - Title
            Text(stepName(step), style = type.bodyBold, color = Color.White)
            Text(
                stepHint(step, viewModel.openingAveragingDurationS),
                style = type.caption,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
            )

            // MARK: - Step body
            when (val s = step) {
                is OffsetFlowViewModel.Step.AveragingAtOpening -> {
                    Column(
                        Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(ForestixSpace.xs),
                    ) {
                        LinearProgressIndicator(
                            progress = {
                                (s.secondsElapsed.toFloat() /
                                    viewModel.openingAveragingDurationS).coerceIn(0f, 1f)
                            },
                            color = colors.primary,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(Modifier.fillMaxWidth()) {
                            Text(
                                "${s.secondsElapsed} / ${viewModel.openingAveragingDurationS} s",
                                style = type.dataSmall,
                                color = Color.White.copy(alpha = 0.7f))
                            Spacer(Modifier.weight(1f))
                            Text(
                                "${s.sampleCount} samples",
                                style = type.dataSmall,
                                color = Color.White.copy(alpha = 0.7f))
                        }
                    }
                }
                is OffsetFlowViewModel.Step.WalkBack -> {
                    val d = s.distanceFromPlotM
                    Text(
                        if (d != null) String.format(Locale.US, "%.1f m from plot", d)
                        else "Waiting for AR pose…",
                        style = type.data,
                        color = Color.White,
                    )
                }
                is OffsetFlowViewModel.Step.Computed -> {
                    val r = s.result
                    Column(
                        verticalArrangement = Arrangement.spacedBy(ForestixSpace.xxs),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("Tier ${r.tier.raw}", style = type.title, color = colors.confidenceOk)
                        Text(
                            String.format(Locale.US, "%.6f, %.6f", r.lat, r.lon),
                            style = type.data,
                            color = Color.White,
                        )
                        val w = r.offsetWalkM
                        if (w != null) {
                            Text(
                                String.format(Locale.US, "Walk %.1f m", w),
                                style = type.caption,
                                color = Color.White.copy(alpha = 0.7f),
                            )
                        }
                    }
                }
                is OffsetFlowViewModel.Step.Failed -> {
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = null,
                        tint = colors.confidenceBad,
                        modifier = Modifier.size(40.dp),
                    )
                }
                is OffsetFlowViewModel.Step.AnchorPlot,
                is OffsetFlowViewModel.Step.WalkToOpening -> Unit
            }

            // MARK: - Actions (iOS `.forestixProminent` on the AR panel;
            // Cancel/Restart stay plain/white so they read on the scrim)
            when (val s = step) {
                is OffsetFlowViewModel.Step.AnchorPlot ->
                    ForestixProminentButton(label = "Anchor here") { viewModel.anchorPlotCenter() }
                is OffsetFlowViewModel.Step.WalkToOpening ->
                    ForestixProminentButton(label = "Capture fix here") { viewModel.beginOpeningAveraging() }
                is OffsetFlowViewModel.Step.AveragingAtOpening ->
                    TextButton(onClick = { viewModel.cancel() }) { Text("Cancel", color = Color.White) }
                is OffsetFlowViewModel.Step.WalkBack ->
                    ForestixProminentButton(label = "Confirm plot center") { viewModel.confirmPlotCenter() }
                is OffsetFlowViewModel.Step.Computed ->
                    ForestixProminentButton(label = "Save") { onDone(s.result) }
                is OffsetFlowViewModel.Step.Failed ->
                    // iOS renders Restart as a plain text button.
                    TextButton(onClick = { viewModel.cancel() }) { Text("Restart", color = Color.White) }
            }
        }
    }
}

// MARK: - Copy

private fun stepName(step: OffsetFlowViewModel.Step): String = when (step) {
    is OffsetFlowViewModel.Step.AnchorPlot -> "A · Anchor plot"
    is OffsetFlowViewModel.Step.WalkToOpening -> "B · Walk to opening"
    is OffsetFlowViewModel.Step.AveragingAtOpening -> "C · Averaging at opening"
    is OffsetFlowViewModel.Step.WalkBack -> "D · Walk back"
    is OffsetFlowViewModel.Step.Computed -> "E · Confirmed"
    is OffsetFlowViewModel.Step.Failed -> "Failed"
}

private fun stepHint(step: OffsetFlowViewModel.Step, openingAveragingDurationS: Int): String =
    when (step) {
        is OffsetFlowViewModel.Step.AnchorPlot ->
            "Stand at the plot center. Tap Anchor when steady."
        is OffsetFlowViewModel.Step.WalkToOpening ->
            "Walk to an opening with clear sky. Keep phone upright."
        is OffsetFlowViewModel.Step.AveragingAtOpening ->
            "Hold still for $openingAveragingDurationS s."
        is OffsetFlowViewModel.Step.WalkBack ->
            "Walk back to the plot center under continuous tracking."
        is OffsetFlowViewModel.Step.Computed ->
            "Plot center recovered."
        is OffsetFlowViewModel.Step.Failed -> step.reason
    }
