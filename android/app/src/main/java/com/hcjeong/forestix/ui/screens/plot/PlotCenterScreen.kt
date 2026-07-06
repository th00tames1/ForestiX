// Plot center screen — port of iOS Screens/PlotCenterScreen.swift +
// ViewModels/PlotCenterViewModel.swift. Spec §4.5 + §5.1 + §7.3.1,
// REQ-CTR-001/002/005.
//
// Progress bar + countdown + live sample count + live accuracy while
// the 60 s window runs; on completion, show the computed center, tier,
// and either "Save" (tier A/B) or "Try Offset / Save anyway" (C/D per
// the §4.5 Offset-from-Opening fallback).

package com.hcjeong.forestix.ui.screens.plot

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BlurCircular
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
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
import com.hcjeong.forestix.data.cruise.PlotCenterResult
import com.hcjeong.forestix.data.cruise.PositionTier
import com.hcjeong.forestix.positioning.CLLocationSnapshot
import com.hcjeong.forestix.positioning.GPSAveraging
import com.hcjeong.forestix.positioning.LocationService
import com.hcjeong.forestix.ui.screens.ForestixScaffold
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixRadius
import com.hcjeong.forestix.ui.theme.ForestixSpace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

// MARK: - View model (port of iOS PlotCenterViewModel)

/// Runs the 60 s GPS averaging window, surfaces live counters (sample
/// count, current accuracy), and on completion hands the buffer to
/// `GPSAveraging.compute`. Tier A or B → good; tier C/D → show the
/// Offset-from-Opening fallback banner per §4.5 and let the user decide
/// between "Save anyway" and "Try Offset".
class PlotCenterViewModel(
    val location: LocationService,
    private val scope: CoroutineScope,
    val averagingDurationS: Int = 60,
) {

    sealed class Phase {
        object Idle : Phase()
        data class Averaging(val secondsElapsed: Int, val sampleCount: Int) : Phase()
        data class Good(val result: PlotCenterResult) : Phase()
        data class Poor(val result: PlotCenterResult) : Phase()   // tier C/D — offer Offset
        data class Failed(val reason: String) : Phase()
    }

    private val _phase = MutableStateFlow<Phase>(Phase.Idle)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    /// Mirror of `LocationService.latestSnapshot` (iOS assigns the
    /// publisher straight through).
    val latestSample: StateFlow<CLLocationSnapshot?> = location.latestSnapshot

    var startedAt: Long? = null
        private set

    private var tickJob: Job? = null

    // MARK: - Lifecycle

    fun start() {
        if (_phase.value !is Phase.Idle) return
        location.clearBuffer()
        location.requestAuthorization()
        location.start()
        startedAt = System.currentTimeMillis()
        _phase.value = Phase.Averaging(secondsElapsed = 0, sampleCount = 0)
        tickJob?.cancel()
        tickJob = scope.launch {
            while (isActive) {
                delay(1_000)
                tick()
            }
        }
    }

    fun cancel() {
        tickJob?.cancel()
        tickJob = null
        location.stop()
        _phase.value = Phase.Idle
    }

    private fun tick() {
        if (_phase.value !is Phase.Averaging) return
        val startedAt = startedAt ?: return
        val elapsed = ((System.currentTimeMillis() - startedAt) / 1_000L).toInt()
        val samples = location.buffer.value.size
        if (elapsed >= averagingDurationS) {
            finish()
        } else {
            _phase.value = Phase.Averaging(secondsElapsed = elapsed, sampleCount = samples)
        }
    }

    private fun finish() {
        tickJob?.cancel()
        tickJob = null
        val samples = location.buffer.value
        val result = GPSAveraging.compute(GPSAveraging.Input(samples = samples))
        if (result == null) {
            _phase.value = Phase.Failed(
                reason = "Not enough samples (need 30 with accuracy ≤ 20 m)")
            return
        }
        _phase.value = when (result.tier) {
            PositionTier.A, PositionTier.B -> Phase.Good(result)
            PositionTier.C, PositionTier.D -> Phase.Poor(result)
        }
    }

    /// Force-accept a tier-C/D result the user chose over falling back
    /// to Offset. Returned as `.Good` so downstream accept flow can
    /// treat both paths uniformly.
    fun acceptAnyway() {
        val p = _phase.value
        if (p is Phase.Poor) _phase.value = Phase.Good(p.result)
    }
}

// MARK: - Screen

@Composable
fun PlotCenterScreen(
    nav: NavController,
    onAccept: (PlotCenterResult) -> Unit = {},
    onTryOffset: (PlotCenterResult) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val location = remember { LocationService(context) }
    val viewModel = remember { PlotCenterViewModel(location = location, scope = scope) }

    // iOS onAppear → viewModel.start(); Android additionally routes the
    // runtime permission through a result launcher (see GPSAccuracyBadge).
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        location.onPermissionResult(granted)
        viewModel.start()
    }
    LaunchedEffect(Unit) {
        if (LocationService.hasLocationPermission(context)) {
            viewModel.start()
        } else {
            launcher.launch(LocationService.PERMISSION)
        }
    }
    DisposableEffect(Unit) {
        onDispose { viewModel.cancel() }
    }

    val phase by viewModel.phase.collectAsStateWithLifecycle()
    val latestSample by viewModel.latestSample.collectAsStateWithLifecycle()

    ForestixScaffold(nav, title = "Plot Center") { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(ForestixSpace.lg),
            verticalArrangement = Arrangement.spacedBy(ForestixSpace.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CenterHeader(viewModel.averagingDurationS)
            PhaseBody(
                phase = phase,
                averagingDurationS = viewModel.averagingDurationS,
                latestSample = latestSample)
            Spacer(Modifier.weight(1f))
            PhaseActions(
                phase = phase,
                viewModel = viewModel,
                onAccept = onAccept,
                onTryOffset = onTryOffset)
        }
    }
}

// MARK: - Header

@Composable
private fun CenterHeader(averagingDurationS: Int) {
    val colors = Forestix.colors
    val type = Forestix.type
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("GPS Averaging", style = type.bodyBold, color = colors.textPrimary)
        Text(
            "Stand still at the plot center for $averagingDurationS s.",
            style = type.caption,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

// MARK: - Phase body

@Composable
private fun PhaseBody(
    phase: PlotCenterViewModel.Phase,
    averagingDurationS: Int,
    latestSample: CLLocationSnapshot?,
) {
    val colors = Forestix.colors
    val type = Forestix.type
    when (phase) {
        is PlotCenterViewModel.Phase.Idle -> {
            Column(
                verticalArrangement = Arrangement.spacedBy(ForestixSpace.sm),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(color = colors.primary)
                Text("Waiting for GPS…", style = type.caption, color = colors.textSecondary)
            }
        }

        is PlotCenterViewModel.Phase.Averaging ->
            AveragingView(
                secs = phase.secondsElapsed,
                count = phase.sampleCount,
                averagingDurationS = averagingDurationS,
                latestSample = latestSample)

        is PlotCenterViewModel.Phase.Good ->
            ResultView(phase.result, banner = null, bannerColor = colors.confidenceOk)

        is PlotCenterViewModel.Phase.Poor ->
            ResultView(
                phase.result,
                banner = "Accuracy weak (tier ${phase.result.tier.raw}). " +
                    "Offset-from-Opening usually helps under canopy.",
                bannerColor = colors.confidenceWarn)

        is PlotCenterViewModel.Phase.Failed -> {
            Column(
                verticalArrangement = Arrangement.spacedBy(ForestixSpace.sm),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    tint = colors.confidenceBad,
                    modifier = Modifier.size(48.dp))
                Text(
                    phase.reason,
                    style = type.body,
                    color = colors.textPrimary,
                    textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun AveragingView(
    secs: Int,
    count: Int,
    averagingDurationS: Int,
    latestSample: CLLocationSnapshot?,
) {
    val colors = Forestix.colors
    val type = Forestix.type
    val progress = (secs.toFloat() / averagingDurationS.toFloat()).coerceAtMost(1f)
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(ForestixSpace.md),
    ) {
        LinearProgressIndicator(
            progress = { progress },
            color = colors.primary,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth()) {
            Text("$secs / $averagingDurationS s", style = type.dataSmall, color = colors.textSecondary)
            Spacer(Modifier.weight(1f))
            Text("$count samples", style = type.dataSmall, color = colors.textSecondary)
        }
        val s = latestSample
        if (s != null) {
            Row(Modifier.fillMaxWidth()) {
                Text("Live accuracy", style = type.dataSmall, color = colors.textSecondary)
                Spacer(Modifier.weight(1f))
                Text(
                    String.format(Locale.US, "±%.1f m", s.horizontalAccuracyM),
                    style = type.dataSmall,
                    color = colors.textSecondary)
            }
        }
    }
}

@Composable
private fun ResultView(r: PlotCenterResult, banner: String?, bannerColor: Color) {
    val colors = Forestix.colors
    val type = Forestix.type
    Column(
        verticalArrangement = Arrangement.spacedBy(ForestixSpace.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Tier ${r.tier.raw}", style = type.title, color = bannerColor)
        Text(
            String.format(Locale.US, "%.6f, %.6f", r.lat, r.lon),
            style = type.data,
            color = colors.textPrimary)
        Row(horizontalArrangement = Arrangement.spacedBy(ForestixSpace.md)) {
            ResultLabel(Icons.Filled.Sensors, "${r.nSamples}")
            ResultLabel(
                Icons.Filled.MyLocation,
                String.format(Locale.US, "±%.1f m", r.medianHAccuracyM))
            ResultLabel(
                Icons.Filled.BlurCircular,
                String.format(Locale.US, "σxy %.1f m", r.sampleStdXyM))
        }
        if (banner != null) {
            Text(
                banner,
                style = type.caption,
                color = bannerColor,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .clip(ForestixRadius.chip)
                    .background(bannerColor.copy(alpha = 0.12f))
                    .padding(ForestixSpace.xs),
            )
        }
    }
}

@Composable
private fun ResultLabel(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    val colors = Forestix.colors
    val type = Forestix.type
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(14.dp))
        Text(text, style = type.caption, color = colors.textSecondary)
    }
}

// MARK: - Actions

@Composable
private fun PhaseActions(
    phase: PlotCenterViewModel.Phase,
    viewModel: PlotCenterViewModel,
    onAccept: (PlotCenterResult) -> Unit,
    onTryOffset: (PlotCenterResult) -> Unit,
) {
    when (phase) {
        is PlotCenterViewModel.Phase.Good -> {
            Button(
                onClick = { onAccept(phase.result) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save plot center")
            }
        }

        is PlotCenterViewModel.Phase.Poor -> {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ForestixSpace.sm),
            ) {
                Button(
                    onClick = { onTryOffset(phase.result) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Try Offset")
                }
                OutlinedButton(
                    onClick = {
                        viewModel.acceptAnyway()
                        onAccept(phase.result)
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Save anyway")
                }
            }
        }

        is PlotCenterViewModel.Phase.Failed -> {
            Button(
                onClick = {
                    viewModel.cancel()
                    viewModel.start()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Retry")
            }
        }

        is PlotCenterViewModel.Phase.Idle,
        is PlotCenterViewModel.Phase.Averaging -> {
            TextButton(onClick = { viewModel.cancel() }) {
                Text("Cancel")
            }
        }
    }
}
