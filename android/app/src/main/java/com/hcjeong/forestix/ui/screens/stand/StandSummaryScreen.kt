// Port of iOS Screens/StandSummaryScreen.swift.
// Phase 5 §5.5 StandSummaryScreen. REQ-AGG-003, §7.5.
//
// Stratified stand-level summary: three stat cards (TPA, BA/ac, V/ac) each
// showing mean ± SE ± 95% CI and a per-plot bar chart (Canvas replaces
// Swift Charts). Below: a table of per-plot PlotStats and a stratum
// breakdown.

package com.hcjeong.forestix.ui.screens.stand

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.hcjeong.forestix.LocalAppEnvironment
import com.hcjeong.forestix.data.cruise.Plot
import com.hcjeong.forestix.inventory.StandStat
import com.hcjeong.forestix.ui.screens.ForestixScaffold
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixRadius
import com.hcjeong.forestix.ui.theme.ForestixSpace
import java.util.Locale
import java.util.UUID
import kotlin.math.max
import kotlin.math.sqrt

/// Suggested route: "standSummary/{projectId}" with a string UUID argument.
@Composable
fun StandSummaryScreen(nav: NavController, projectId: UUID) {
    val env = LocalAppEnvironment.current
    val settings by env.settings.state.collectAsStateWithLifecycle()
    val colors = Forestix.colors
    val type = Forestix.type

    var viewModel by remember { mutableStateOf<StandSummaryViewModel?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(projectId) {
        val project = env.projectRepository.read(projectId)
        val design = project?.let {
            env.cruiseDesignRepository.forProject(it.id).firstOrNull()
        }
        if (project == null || design == null) {
            loadError = "Project or cruise design not found."
        } else {
            val vm = StandSummaryViewModel(
                project = project,
                design = design,
                plotRepo = env.plotRepository,
                treeRepo = env.treeRepository,
                speciesRepo = env.speciesConfigRepository,
                volRepo = env.volumeEquationRepository,
                hdFitRepo = env.heightDiameterFitRepository,
                stratumRepo = env.stratumRepository,
                plannedRepo = env.plannedPlotRepository)
            vm.refresh()
            viewModel = vm
        }
    }

    ForestixScaffold(nav, title = "Stand summary") { padding ->
        val vm = viewModel
        if (vm == null) {
            Text(
                loadError ?: "Loading…",
                style = type.caption, color = colors.textSecondary,
                modifier = Modifier.padding(padding).padding(ForestixSpace.md))
            return@ForestixScaffold
        }

        val closedPlots by vm.closedPlots.collectAsStateWithLifecycle()
        val tpaStat by vm.tpaStat.collectAsStateWithLifecycle()
        val baStat by vm.baStat.collectAsStateWithLifecycle()
        val volStat by vm.volStat.collectAsStateWithLifecycle()
        val totalLiveTreeCount by vm.totalLiveTreeCount.collectAsStateWithLifecycle()
        val perPlotStats by vm.perPlotStats.collectAsStateWithLifecycle()
        val errorMessage by vm.errorMessage.collectAsStateWithLifecycle()

        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ForestixSpace.md),
            verticalArrangement = Arrangement.spacedBy(ForestixSpace.md),
        ) {
            // Header
            SummaryCard {
                Text(vm.project.name, style = type.bodyBold, color = colors.textPrimary)
                Text(
                    "${closedPlots.size} closed plot(s) · $totalLiveTreeCount live trees",
                    style = type.caption, color = colors.textSecondary)
            }

            StatCardSection(
                title = "Trees / ac", unit = "/ac", stat = tpaStat, vm = vm,
                perPlot = perPlotStats.map { Pair(it.plot, it.stats.tpa.toDouble()) })
            StatCardSection(
                title = "Basal area", unit = "m²/ac", stat = baStat, vm = vm,
                perPlot = perPlotStats.map { Pair(it.plot, it.stats.baPerAcreM2.toDouble()) })
            // Volume unit branches on the country: metric countries render m³
            // (the engine's native unit); the US keeps m³ here as it does today.
            // Korea is a scaffold — its official NIFoS coefficients are pending,
            // so stand volume shows "—" rather than a fabricated figure.
            if (settings.country.volumeStandardPending) {
                PendingVolumeCard()
            } else {
                StatCardSection(
                    title = "Gross volume", unit = "m³/ac", stat = volStat, vm = vm,
                    perPlot = perPlotStats.map { Pair(it.plot, it.stats.grossVolumePerAcreM3.toDouble()) })
            }

            PerPlotTableSection(perPlotStats)
            Spacer(Modifier.height(ForestixSpace.xl))
        }

        // Error alert — mirrors the iOS `.alert("Error", ...)`.
        var dismissedError by remember(errorMessage) { mutableStateOf(false) }
        val msg = errorMessage
        if (msg != null && !dismissedError) {
            AlertDialog(
                onDismissRequest = { dismissedError = true },
                confirmButton = {
                    TextButton(onClick = { dismissedError = true }) { Text("OK") }
                },
                title = { Text("Error") },
                text = { Text(msg) })
        }
    }
}

// MARK: - Section chrome

@Composable
private fun SectionTitle(title: String) {
    Text(
        title.uppercase(Locale.US),
        style = Forestix.type.sectionHead.copy(letterSpacing = 1.2.sp),
        color = Forestix.colors.textTertiary,
        modifier = Modifier.padding(start = ForestixSpace.xs))
}

@Composable
private fun SummaryCard(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(ForestixRadius.card)
            .background(Forestix.colors.surface)
            .padding(ForestixSpace.md),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        content()
    }
}

// MARK: - Pending volume (Korea scaffold — coefficients not yet bundled)

@Composable
private fun PendingVolumeCard() {
    val colors = Forestix.colors
    val type = Forestix.type
    Column(verticalArrangement = Arrangement.spacedBy(ForestixSpace.xs)) {
        SectionTitle("Gross volume")
        SummaryCard {
            Text("—", style = type.dataLarge, color = colors.textPrimary)
            Text(
                "Volume coefficients pending — official NIFoS national table not yet " +
                    "bundled. DBH, height, basal area and trees/ac are unaffected.",
                style = type.caption, color = colors.textSecondary)
        }
    }
}

// MARK: - Stat card

@Composable
private fun StatCardSection(
    title: String,
    unit: String,
    stat: StandStat,
    vm: StandSummaryViewModel,
    perPlot: List<Pair<Plot, Double>>,
) {
    val colors = Forestix.colors
    val type = Forestix.type
    Column(verticalArrangement = Arrangement.spacedBy(ForestixSpace.xs)) {
        SectionTitle(title)
        SummaryCard {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        String.format(Locale.US, "%.2f %s", stat.mean, unit),
                        style = type.dataLarge, color = colors.textPrimary)
                    Spacer(Modifier.weight(1f))
                    Text(
                        String.format(Locale.US, "± %.2f (95%% confidence)", stat.ci95HalfWidth),
                        style = type.dataSmall, color = colors.textSecondary)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(ForestixSpace.sm)) {
                    Text(
                        String.format(Locale.US, "Std error %.2f", stat.seMean),
                        style = type.dataSmall, color = colors.textSecondary)
                    Text(
                        String.format(Locale.US, "eff. plots %.1f", stat.dfSatterthwaite),
                        style = type.dataSmall, color = colors.textSecondary)
                    Text("n ${stat.nPlots}", style = type.dataSmall, color = colors.textSecondary)
                }

                if (perPlot.isNotEmpty()) {
                    PerPlotBarChart(perPlot = perPlot, mean = stat.mean)
                }

                if (stat.byStratum.isNotEmpty()) {
                    HorizontalDivider(
                        Modifier.padding(vertical = 2.dp), color = colors.divider)
                    for (key in stat.byStratum.keys.sorted()) {
                        val s = stat.byStratum[key] ?: continue
                        Row(Modifier.fillMaxWidth()) {
                            Text(
                                vm.stratumName(forKey = key),
                                style = type.caption, color = colors.textPrimary)
                            Spacer(Modifier.weight(1f))
                            Text(
                                String.format(
                                    Locale.US, "n=%d  mean=%.2f  std-dev=%.2f",
                                    s.nPlots, s.mean, sqrt(max(s.variance, 0.0))),
                                style = type.dataSmall, color = colors.textSecondary)
                        }
                    }
                }
            }
        }
    }
}

// MARK: - Per-plot bar chart (Canvas stand-in for Swift Charts)

@Composable
private fun PerPlotBarChart(perPlot: List<Pair<Plot, Double>>, mean: Double) {
    val colors = Forestix.colors
    val type = Forestix.type
    val primary = colors.primary
    val ruleColor = colors.textSecondary
    Column(Modifier.fillMaxWidth()) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(140.dp),
        ) {
            val maxV = max(perPlot.maxOf { it.second }, mean)
            if (maxV <= 0.0) return@Canvas
            val n = perPlot.size
            val slot = size.width / n
            val barW = slot * 0.6f
            perPlot.forEachIndexed { i, (_, v) ->
                val h = (v / maxV).toFloat() * size.height
                drawRoundRect(
                    color = primary,
                    topLeft = Offset(i * slot + (slot - barW) / 2f, size.height - h),
                    size = Size(barW, h),
                    cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()))
            }
            // Dashed rule at the stand mean.
            val yMean = size.height - (mean / maxV).toFloat() * size.height
            drawLine(
                color = ruleColor,
                start = Offset(0f, yMean),
                end = Offset(size.width, yMean),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(
                    floatArrayOf(4.dp.toPx(), 4.dp.toPx())))
        }
        Row(Modifier.fillMaxWidth()) {
            for ((plot, _) in perPlot) {
                Text(
                    "${plot.plotNumber}",
                    style = type.dataSmall, color = colors.textTertiary,
                    textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            }
        }
    }
}

// MARK: - Per-plot table

@Composable
private fun PerPlotTableSection(perPlotStats: List<StandSummaryViewModel.PerPlotStat>) {
    val colors = Forestix.colors
    val type = Forestix.type
    Column(verticalArrangement = Arrangement.spacedBy(ForestixSpace.xs)) {
        SectionTitle("Per-plot")
        SummaryCard {
            if (perPlotStats.isEmpty()) {
                Text(
                    "No closed plots yet.",
                    style = type.caption, color = colors.textSecondary)
            } else {
                val headStyle = type.sectionHead.copy(fontSize = 11.sp)
                Row(Modifier.fillMaxWidth()) {
                    Text("#", style = headStyle, color = colors.textSecondary, modifier = Modifier.width(28.dp))
                    Text("Live", style = headStyle, color = colors.textSecondary, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                    Text("Trees/ac", style = headStyle, color = colors.textSecondary, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                    Text("Basal/ac", style = headStyle, color = colors.textSecondary, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                    Text("Volume/ac", style = headStyle, color = colors.textSecondary, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                }
                for (row in perPlotStats) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text("${row.plot.plotNumber}", style = type.dataSmall, color = colors.textPrimary, modifier = Modifier.width(28.dp))
                        Text("${row.stats.liveTreeCount}", style = type.dataSmall, color = colors.textPrimary, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                        Text(String.format(Locale.US, "%.1f", row.stats.tpa), style = type.dataSmall, color = colors.textPrimary, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                        Text(String.format(Locale.US, "%.2f", row.stats.baPerAcreM2), style = type.dataSmall, color = colors.textPrimary, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                        Text(String.format(Locale.US, "%.1f", row.stats.grossVolumePerAcreM3), style = type.dataSmall, color = colors.textPrimary, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
