// Port of iOS Screens/PlotDetailScreen.swift.
// Plot detail screen — SilvaCruise-style sub-tab navigation inside
// a single (quick-measure) plot context. The tab strip at the top keeps
// the cruiser inside the plot while flipping between Summary, the tree
// tally list, and plot-level settings (rename, area, type).
//
// Reachable from the plot picker in TreeIdentitySheet (Phase 2)
// and from a "Plots" surfacing inside the FieldLogScreen summary header.

package com.hcjeong.forestix.ui.screens.plot

import androidx.compose.foundation.background
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
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.hcjeong.forestix.LocalAppEnvironment
import com.hcjeong.forestix.common.MeasurementFormatter
import com.hcjeong.forestix.common.UnitSystem
import com.hcjeong.forestix.data.MeasureKind
import com.hcjeong.forestix.data.QuickMeasureEntry
import com.hcjeong.forestix.data.QuickMeasurePlot
import com.hcjeong.forestix.ui.clickableNoRipple
import com.hcjeong.forestix.ui.screens.ForestixScaffold
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixRadius
import com.hcjeong.forestix.ui.theme.ForestixSpace
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

// MARK: - Tabs

private enum class Tab(val label: String) {
    SUMMARY("Summary"),
    TREES("Trees"),
    INFO("Info"),
}

/// Suggested route: PlotFlowRoutes.PLOT_DETAIL ("plotDetail/{plotID}").
@Composable
fun PlotDetailScreen(nav: NavController, plotID: String) {
    val env = LocalAppEnvironment.current
    val colors = Forestix.colors
    val type = Forestix.type

    val id = remember(plotID) {
        try {
            UUID.fromString(plotID)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    val plots by env.history.plots.collectAsStateWithLifecycle()
    val allEntries by env.history.entries.collectAsStateWithLifecycle()
    val settings by env.settings.state.collectAsStateWithLifecycle()

    val plot = plots.firstOrNull { it.id == id }
    val entries = remember(allEntries, id) {
        if (id == null) emptyList() else env.history.entriesForPlot(id)
    }

    var selectedTab by remember { mutableStateOf(Tab.SUMMARY) }

    ForestixScaffold(nav, title = plot?.name ?: "Plot") { padding ->
        if (plot == null) {
            Box(
                Modifier.padding(padding).fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text("Plot not found", style = type.body, color = colors.textSecondary)
            }
            return@ForestixScaffold
        }

        Column(Modifier.padding(padding).fillMaxSize()) {
            // MARK: - Tab strip
            Row(Modifier.fillMaxWidth().background(colors.surface)) {
                Tab.entries.forEach { tab ->
                    Column(
                        Modifier
                            .weight(1f)
                            .clickableNoRipple { selectedTab = tab }
                            .padding(top = ForestixSpace.xs),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            tab.label,
                            style = type.bodyBold,
                            color = if (selectedTab == tab) colors.primary
                                    else colors.textSecondary)
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(2.dp)
                                .background(
                                    if (selectedTab == tab) colors.primary
                                    else androidx.compose.ui.graphics.Color.Transparent))
                    }
                }
            }
            HorizontalDivider(color = colors.divider, thickness = 0.5.dp)

            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = ForestixSpace.md, vertical = ForestixSpace.md),
                verticalArrangement = Arrangement.spacedBy(ForestixSpace.md),
            ) {
                when (selectedTab) {
                    Tab.SUMMARY -> SummaryTab(plot, entries, settings.unitSystem, settings.logRule)
                    Tab.TREES -> TreesTab(entries, settings.unitSystem)
                    Tab.INFO -> InfoTab(plot)
                }
            }
        }
    }
}

// MARK: - Summary tab

@Composable
private fun SummaryTab(
    plot: QuickMeasurePlot,
    entries: List<QuickMeasureEntry>,
    unitSystem: UnitSystem,
    logRule: com.hcjeong.forestix.sensors.LogRule,
) {
    if (entries.isEmpty()) {
        EmptyTabState("No readings yet for this plot. Start a scan from the Quick Measure home.")
    } else {
        PlotSummaryCard(
            plot = plot,
            entries = entries,
            unitSystem = unitSystem,
            logRule = logRule)
    }
}

// MARK: - Trees tab

@Composable
private fun TreesTab(entries: List<QuickMeasureEntry>, unitSystem: UnitSystem) {
    if (entries.isEmpty()) {
        EmptyTabState("No trees tallied. Pick this plot before your next scan.")
    } else {
        // Group entries by tree number; each row in the tab is a
        // tree, not a measurement, so cruisers see "Tree 7 — DBH
        // 34.5 cm + Height 28.2 m" together.
        val byTree = entries.groupBy { it.treeNumber ?: -1 }
            .toSortedMap()
        Column(verticalArrangement = Arrangement.spacedBy(ForestixSpace.sm)) {
            byTree.forEach { (num, group) ->
                TreeRowCard(treeNumber = num, entries = group, unitSystem = unitSystem)
            }
        }
    }
}

// MARK: - Info tab

@Composable
private fun InfoTab(plot: QuickMeasurePlot) {
    val colors = Forestix.colors
    val type = Forestix.type
    Column(
        Modifier
            .fillMaxWidth()
            .clip(ForestixRadius.card)
            .background(colors.surface)
            .padding(ForestixSpace.md),
        verticalArrangement = Arrangement.spacedBy(ForestixSpace.sm),
    ) {
        InfoRow("NAME", plot.name)
        InfoRow("UNIT", plot.unitName.ifEmpty { "—" })
        InfoRow("ACRES",
            plot.acres?.let { String.format(Locale.US, "%.2f", it) } ?: "—")
        InfoRow("TYPE", plot.typeRaw.replaceFirstChar { it.uppercase(Locale.US) })
        plot.baf?.let { baf ->
            InfoRow("BASAL FACTOR", String.format(Locale.US, "%.0f ft²/ac", baf))
        }
        plot.radiusFt?.let { r ->
            InfoRow("RADIUS", String.format(Locale.US, "%.1f ft", r))
        }
        InfoRow("CREATED",
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.US)
                .format(Date(plot.createdAt)))
        if (plot.isDefault) {
            Text(
                "Default plot — auto-created. Cannot be deleted.",
                style = type.caption,
                color = colors.textTertiary,
                modifier = Modifier.padding(top = ForestixSpace.xs))
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    val colors = Forestix.colors
    val type = Forestix.type
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = type.sectionHead.copy(letterSpacing = 1.2.sp),
            color = colors.textTertiary,
            modifier = Modifier.width(96.dp))
        Text(value, style = type.data, color = colors.textPrimary)
        Spacer(Modifier.weight(1f))
    }
}

// MARK: - Empty state

@Composable
private fun EmptyTabState(message: String) {
    val colors = Forestix.colors
    val type = Forestix.type
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = ForestixSpace.lg),
        verticalArrangement = Arrangement.spacedBy(ForestixSpace.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.Inbox,
            contentDescription = null,
            tint = colors.textTertiary,
            modifier = Modifier.size(24.dp))
        Text(
            message,
            style = type.caption,
            color = colors.textSecondary,
            textAlign = TextAlign.Center)
    }
}

// MARK: - Tree row

@Composable
private fun TreeRowCard(
    treeNumber: Int,
    entries: List<QuickMeasureEntry>,
    unitSystem: UnitSystem,
) {
    val colors = Forestix.colors
    val type = Forestix.type
    val dbh = entries.firstOrNull { it.kind == MeasureKind.DBH }
    val hgt = entries.firstOrNull { it.kind == MeasureKind.HEIGHT }
    val species = entries.mapNotNull { it.speciesCode }.firstOrNull()

    Row(
        Modifier
            .fillMaxWidth()
            .clip(ForestixRadius.card)
            .background(colors.surface)
            .border(0.5.dp, colors.divider, ForestixRadius.card)
            .padding(ForestixSpace.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ForestixSpace.md),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                if (treeNumber > 0) "Tree #$treeNumber" else "Untagged",
                style = type.bodyBold,
                color = colors.textPrimary)
            if (!species.isNullOrEmpty()) {
                Text(species, style = type.dataSmall, color = colors.primary)
            }
        }
        Spacer(Modifier.weight(1f))
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            horizontalAlignment = Alignment.End,
        ) {
            if (dbh != null) {
                Text(
                    "DBH " + MeasurementFormatter.diameter(dbh.value, unitSystem),
                    style = type.data,
                    color = colors.textPrimary)
            }
            if (hgt != null) {
                Text(
                    "Height " + MeasurementFormatter.height(hgt.value, unitSystem),
                    style = type.dataSmall,
                    color = colors.textSecondary)
            }
        }
    }
}
