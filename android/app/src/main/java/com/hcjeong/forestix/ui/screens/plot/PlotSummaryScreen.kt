// Port of iOS Screens/PlotSummaryScreen.swift.
// Phase 5 §5.4 PlotSummaryScreen. REQ-AGG-001/002, §7.4.
//
// Pre-close summary: validation warnings/errors, final plot stats,
// and Close button that triggers the H–D rolling update.

package com.hcjeong.forestix.ui.screens.plot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dangerous
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.hcjeong.forestix.LocalAppEnvironment
import com.hcjeong.forestix.inventory.PlotStats
import com.hcjeong.forestix.ui.screens.ForestixScaffold
import com.hcjeong.forestix.ui.screens.project.FormSection
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixProminentButton
import com.hcjeong.forestix.ui.theme.ForestixSpace
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.launch

/// Suggested route: PlotFlowRoutes.PLOT_SUMMARY ("plotSummary/{plotId}").
/// `onClosed` mirrors the iOS closure — it fully owns the post-close
/// transition (iOS onClosed() + dismiss(), where dismiss is a no-op once
/// the host coordinator has already rewritten the path). The default pops
/// this screen; callers that navigate elsewhere (e.g. the cruise SUMMARIZE
/// registration pushing StandSummary) replace it entirely.
@Composable
fun PlotSummaryScreen(
    nav: NavController,
    plotId: String,
    onClosed: () -> Unit = { nav.popBackStack() },
) {
    val env = LocalAppEnvironment.current
    val scope = rememberCoroutineScope()
    val colors = Forestix.colors
    val type = Forestix.type

    var viewModel by remember { mutableStateOf<PlotSummaryViewModel?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(plotId) {
        val plot = env.plotRepository.read(UUID.fromString(plotId))
        val project = plot?.let { env.projectRepository.read(it.projectId) }
        val design = project?.let {
            env.cruiseDesignRepository.forProject(it.id).firstOrNull()
        }
        if (plot == null || project == null || design == null) {
            loadError = "Plot, project, or cruise design not found."
        } else {
            val vm = PlotSummaryViewModel(
                project = project,
                design = design,
                plot = plot,
                plotRepo = env.plotRepository,
                treeRepo = env.treeRepository,
                speciesRepo = env.speciesConfigRepository,
                volRepo = env.volumeEquationRepository,
                hdFitRepo = env.heightDiameterFitRepository)
            vm.refresh()   // iOS `.onAppear { viewModel.refresh() }`
            viewModel = vm
        }
    }

    val vm = viewModel
    if (vm == null) {
        ForestixScaffold(nav, title = "Plot summary") { padding ->
            Box(
                Modifier.padding(padding).fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                if (loadError != null) {
                    Text(loadError ?: "", style = type.body, color = colors.textSecondary)
                } else {
                    CircularProgressIndicator()
                }
            }
        }
        return
    }

    val validation by vm.validation.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val hdFitsByProject by vm.hdFitsByProject.collectAsStateWithLifecycle()
    val hdFitDurationMs by vm.hdFitDurationMs.collectAsStateWithLifecycle()
    val closedAt by vm.closedAt.collectAsStateWithLifecycle()
    val isClosing by vm.isClosing.collectAsStateWithLifecycle()
    val errorMessage by vm.errorMessage.collectAsStateWithLifecycle()

    ForestixScaffold(nav, title = "Plot ${vm.plot.plotNumber} summary") { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ForestixSpace.md),
            verticalArrangement = Arrangement.spacedBy(ForestixSpace.md),
        ) {
            // MARK: - Closed banner
            val closed = closedAt
            if (closed != null) {
                FormSection {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(ForestixSpace.xs),
                    ) {
                        Icon(
                            Icons.Filled.Lock,
                            contentDescription = null,
                            tint = colors.textSecondary,
                            modifier = Modifier.size(18.dp))
                        Text(
                            "Plot closed ${formatClosedAt(closed)}",
                            style = type.body,
                            color = colors.textSecondary)
                    }
                }
            }

            // MARK: - Validation
            FormSection(header = "Validation") {
                if (validation.errors.isEmpty() && validation.warnings.isEmpty()) {
                    IssueRow(
                        icon = Icons.Filled.Verified,
                        tint = colors.confidenceOk,
                        message = "All checks passed.")
                } else {
                    validation.errors.forEach { issue ->
                        IssueRow(
                            icon = Icons.Filled.Dangerous,
                            tint = colors.confidenceBad,
                            message = issue.message)
                    }
                    validation.warnings.forEach { issue ->
                        IssueRow(
                            icon = Icons.Filled.Warning,
                            tint = colors.confidenceWarn,
                            message = issue.message)
                    }
                }
            }

            // MARK: - Stats
            FormSection(header = "Plot stats") {
                StatRow("Live trees", "${stats.liveTreeCount}")
                StatRow("Trees / ac", String.format(Locale.US, "%.1f", stats.tpa))
                StatRow("Basal area / ac",
                    String.format(Locale.US, "%.2f m²/ac", stats.baPerAcreM2))
                StatRow("Quadratic mean diameter",
                    String.format(Locale.US, "%.1f cm", stats.qmdCm))
                StatRow("Gross volume / ac",
                    String.format(Locale.US, "%.1f m³/ac", stats.grossVolumePerAcreM3))
                StatRow("Merchantable volume / ac",
                    String.format(Locale.US, "%.1f m³/ac", stats.merchVolumePerAcreM3))
            }

            // MARK: - Species breakdown
            FormSection(header = "By species") {
                if (stats.bySpecies.isEmpty()) {
                    Text("No live trees.", style = type.body, color = colors.textSecondary)
                } else {
                    stats.bySpecies.keys.sorted().forEach { code ->
                        val stat = stats.bySpecies[code] ?: return@forEach
                        SpeciesRow(code, stat)
                    }
                }
            }

            // MARK: - H-D fits
            if (hdFitsByProject.isNotEmpty()) {
                FormSection(header = "H–D fits (project)") {
                    hdFitsByProject.keys.sorted().forEach { code ->
                        val fit = hdFitsByProject[code] ?: return@forEach
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(code, style = type.data, color = colors.textPrimary)
                            Spacer(Modifier.weight(1f))
                            Text(
                                String.format(Locale.US, "a=%.3f b=%.3f n=%d RMSE=%.2fm",
                                    fit.a, fit.b, fit.nObs, fit.rmse),
                                style = type.dataSmall,
                                color = colors.textSecondary)
                        }
                    }
                    if (hdFitDurationMs > 0) {
                        Text(
                            String.format(Locale.US, "Rolling update: %.0f ms", hdFitDurationMs),
                            style = type.caption,
                            color = colors.textSecondary)
                    }
                }
            }

            // MARK: - Actions
            if (closedAt == null) {
                ForestixProminentButton(
                    label = "Close plot",
                    icon = Icons.Filled.Lock,       // iOS lock.fill
                    enabled = validation.canClose && !isClosing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    scope.launch {
                        vm.close()
                        if (vm.errorMessage.value == null && vm.closedAt.value != null) {
                            // Navigation is the caller's job (iOS parity):
                            // popping here would undo an onClosed() that
                            // just pushed the stand summary.
                            onClosed()
                        }
                    }
                }
            } else {
                ForestixProminentButton(
                    label = "Done",
                    modifier = Modifier.fillMaxWidth(),
                ) { nav.popBackStack() }
            }

            Spacer(Modifier.height(ForestixSpace.xl))
        }
    }

    // MARK: - Error alert
    if (errorMessage != null) {
        AlertDialog(
            onDismissRequest = { vm.clearError() },
            title = { Text("Error") },
            text = { Text(errorMessage ?: "") },
            confirmButton = {
                TextButton(onClick = { vm.clearError() }) { Text("OK") }
            },
        )
    }
}

// MARK: - Rows

@Composable
private fun IssueRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: androidx.compose.ui.graphics.Color,
    message: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ForestixSpace.xs),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Text(message, style = Forestix.type.body, color = tint)
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    val colors = Forestix.colors
    val type = Forestix.type
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = type.body, color = colors.textPrimary)
        Spacer(Modifier.weight(1f))
        Text(value, style = type.data, color = colors.textSecondary)
    }
}

@Composable
private fun SpeciesRow(code: String, stat: PlotStats.SpeciesStat) {
    val colors = Forestix.colors
    val type = Forestix.type
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(code, style = type.data, color = colors.textPrimary)
            Spacer(Modifier.weight(1f))
            Text("${stat.count} trees", style = type.dataSmall, color = colors.textSecondary)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                String.format(Locale.US, "%.1f /ac", stat.tpa),
                style = type.dataSmall, color = colors.textSecondary)
            Spacer(Modifier.weight(1f))
            Text(
                String.format(Locale.US, "%.2f m²/ac", stat.baPerAcreM2),
                style = type.dataSmall, color = colors.textSecondary)
            Spacer(Modifier.weight(1f))
            Text(
                String.format(Locale.US, "%.1f m³/ac", stat.grossVolumePerAcreM3),
                style = type.dataSmall, color = colors.textSecondary)
        }
    }
}

// MARK: - Formatting

/// iOS `closedAt.formatted(date: .abbreviated, time: .shortened)`.
private fun formatClosedAt(epochMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.US)
        .format(Date(epochMillis))
