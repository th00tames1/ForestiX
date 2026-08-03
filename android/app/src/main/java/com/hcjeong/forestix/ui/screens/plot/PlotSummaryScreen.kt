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
import androidx.compose.material.icons.filled.Delete
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
import com.hcjeong.forestix.common.AreaUnit
import com.hcjeong.forestix.common.RegionalSpecies
import com.hcjeong.forestix.common.MeasurementFormatter
import com.hcjeong.forestix.common.areaUnit
import com.hcjeong.forestix.inventory.PlotStats
import com.hcjeong.forestix.ui.screens.ForestixScaffold
import com.hcjeong.forestix.ui.screens.project.FormSection
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixBorderedButton
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
    val settings by env.settings.state.collectAsStateWithLifecycle()
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
    val closedAt by vm.closedAt.collectAsStateWithLifecycle()
    val isClosing by vm.isClosing.collectAsStateWithLifecycle()
    val errorMessage by vm.errorMessage.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }

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
                    TextButton(onClick = { scope.launch { vm.reopen() } }) {
                        Text("Reopen plot")
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

            // Density basis: a metric unit system reads per hectare, imperial
            // per acre. Derived from the (manually-overridable) Units setting,
            // not the country, so a manual toggle wins. The engine computes per
            // acre; scale + relabel at display only (mirrors StandSummaryScreen).
            val areaUnit = settings.unitSystem.areaUnit
            val f = areaUnit.perAcreDensityFactor
            val abbr = areaUnit.abbreviation

            // MARK: - Stats
            FormSection(header = "Plot stats") {
                StatRow("Live trees", "${stats.liveTreeCount}")
                StatRow("Trees / $abbr", String.format(Locale.US, "%.1f", stats.tpa * f))
                // Basal area converts its NUMERATOR with the basis, not only
                // its suffix — the engine reports m² per ACRE, so scaling just
                // the denominator left an imperial cruise reading "11.49
                // m²/ac", a unit no cruise sheet uses and 10.76x away from the
                // ft²/ac the quick-measure card shows for the same stand.
                StatRow("Basal area / $abbr",
                    String.format(Locale.US, "%.2f %s",
                        MeasurementFormatter.basalAreaDensity(stats.baPerAcreM2.toDouble(), areaUnit),
                        MeasurementFormatter.basalAreaDensityUnit(areaUnit)))
                // The one row in this block that used to stay metric — and the
                // one a cruiser compares against the inch diameters above it.
                StatRow("Quadratic mean diameter",
                    MeasurementFormatter.diameter(stats.qmdCm.toDouble(), settings.unitSystem))
                // Volume turns on the same rule as the basal-area row above
                // it: the engine reports m³ per ACRE, so an imperial cruise
                // converts the numerator too. Scaling only the denominator
                // printed "m³/ac" — 35.3x away from the cubic feet per acre
                // the sheet is written in, and the one figure on this card a
                // landowner is paid on.
                StatRow("Gross volume / $abbr",
                    String.format(Locale.US, "%.1f %s",
                        MeasurementFormatter.volumeDensity(
                            stats.grossVolumePerAcreM3.toDouble(), areaUnit),
                        MeasurementFormatter.volumeDensityUnit(areaUnit)))
                StatRow("Merchantable volume / $abbr",
                    String.format(Locale.US, "%.1f %s",
                        MeasurementFormatter.volumeDensity(
                            stats.merchVolumePerAcreM3.toDouble(), areaUnit),
                        MeasurementFormatter.volumeDensityUnit(areaUnit)))
            }

            // MARK: - Species breakdown
            FormSection(header = "By species") {
                if (stats.bySpecies.isEmpty()) {
                    Text("No live trees.", style = type.body, color = colors.textSecondary)
                } else {
                    stats.bySpecies.keys.sorted().forEach { code ->
                        val stat = stats.bySpecies[code] ?: return@forEach
                        SpeciesRow(code, stat, areaUnit)
                    }
                }
            }

            // MARK: - H-D fits
            if (hdFitsByProject.isNotEmpty()) {
                FormSection(header = "Height curves for this project") {
                    hdFitsByProject.keys.sorted().forEach { code ->
                        val fit = hdFitsByProject[code] ?: return@forEach
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(RegionalSpecies.nameForCode(code), style = type.data, color = colors.textPrimary)
                            Spacer(Modifier.weight(1f))
                            // One plain sentence per species. The row used
                            // to print the raw regression coefficients and an
                            // RMSE label ("a=1.234 b=0.567 n=42 RMSE=1.20m") —
                            // nothing a cruiser can act on. The fit itself is
                            // unchanged and still ships whole in the export.
                            // The ± is the ONLY thing this screen says about
                            // how far an imputed height can be out. Read as
                            // feet on an imperial cruise it understated the
                            // curve's error by 3.28x, so it goes through the
                            // same band the per-tree report uses.
                            Text(
                                "Height curve from ${fit.nObs} trees, typically within " +
                                    MeasurementFormatter.heightSigma(fit.rmse.toDouble(), settings.unitSystem),
                                style = type.dataSmall,
                                color = colors.textSecondary)
                        }
                    }
                    // The "Rolling update: 12 ms" timing that used to sit
                    // here was developer telemetry — no cruiser decides
                    // anything differently at 12 ms versus 40 ms.
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

            // Destructive "Delete plot" (map-peek spec item 4) — cascades to
            // the plot's trees, behind an AlertDialog confirm; pops back after.
            ForestixBorderedButton(
                label = "Delete plot",
                icon = Icons.Filled.Delete,
                tint = colors.confidenceBad,
                modifier = Modifier.fillMaxWidth(),
            ) { confirmDelete = true }

            Spacer(Modifier.height(ForestixSpace.xl))
        }
    }

    // MARK: - Delete confirm
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = {
                Text(
                    "Delete Plot ${vm.plot.plotNumber} and its ${stats.liveTreeCount} " +
                        (if (stats.liveTreeCount == 1) "tree?" else "trees?"),
                )
            },
            text = {
                Text("This permanently removes the plot and all its trees. This cannot be undone.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        try {
                            env.treeRepository.listByPlot(vm.plot.id, includeDeleted = true)
                                .forEach { env.treeRepository.hardDelete(it.id) }
                            env.plotRepository.delete(vm.plot.id)
                        } catch (_: Exception) {
                            // Storage error — stay on the summary; retry later.
                        }
                        nav.popBackStack()
                    }
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
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
private fun SpeciesRow(code: String, stat: PlotStats.SpeciesStat, areaUnit: AreaUnit) {
    val colors = Forestix.colors
    val type = Forestix.type
    val f = areaUnit.perAcreDensityFactor
    val suffix = areaUnit.densitySuffix
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(RegionalSpecies.nameForCode(code), style = type.data, color = colors.textPrimary)
            Spacer(Modifier.weight(1f))
            Text("${stat.count} trees", style = type.dataSmall, color = colors.textSecondary)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                String.format(Locale.US, "%.1f $suffix", stat.tpa * f),
                style = type.dataSmall, color = colors.textSecondary)
            Spacer(Modifier.weight(1f))
            Text(
                String.format(Locale.US, "%.2f %s",
                    MeasurementFormatter.basalAreaDensity(stat.baPerAcreM2.toDouble(), areaUnit),
                    MeasurementFormatter.basalAreaDensityUnit(areaUnit)),
                style = type.dataSmall, color = colors.textSecondary)
            Spacer(Modifier.weight(1f))
            Text(
                String.format(Locale.US, "%.1f %s",
                    MeasurementFormatter.volumeDensity(
                        stat.grossVolumePerAcreM3.toDouble(), areaUnit),
                    MeasurementFormatter.volumeDensityUnit(areaUnit)),
                style = type.dataSmall, color = colors.textSecondary)
        }
    }
}

// MARK: - Formatting

/// iOS `closedAt.formatted(date: .abbreviated, time: .shortened)`.
private fun formatClosedAt(epochMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.US)
        .format(Date(epochMillis))
