// Field log — port of iOS FieldLogScreen. Plot summary card for the
// active plot, grouped summary card (total / today / last + capacity
// banner), then one grouped surface card of rows with hairline dividers
// and trailing swipe-to-delete, plus an Export menu (single CSV or 5-file
// ZIP bundle) shared via the system share sheet. Empty state mirrors the
// iOS "No readings yet" layout (content above centre, uniform 16 gaps).

package com.hcjeong.forestix.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.hcjeong.forestix.LocalAppEnvironment
import com.hcjeong.forestix.common.MeasurementFormatter
import com.hcjeong.forestix.common.UnitSystem
import com.hcjeong.forestix.common.areaUnit
import com.hcjeong.forestix.data.MeasureKind
import com.hcjeong.forestix.data.QuickMeasureEntry
import com.hcjeong.forestix.ui.screens.plot.PlotSummaryCard
import com.hcjeong.forestix.ui.shareFile
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixRadius
import com.hcjeong.forestix.ui.theme.ForestixSpace
import com.hcjeong.forestix.ui.theme.confidenceDescriptor
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI

@Composable
fun FieldLogScreen(nav: NavController) {
    val colors = Forestix.colors
    val env = LocalAppEnvironment.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val entries by env.history.entries.collectAsStateWithLifecycle()
    val plots by env.history.plots.collectAsStateWithLifecycle()
    val activePlotID by env.history.activePlotID.collectAsStateWithLifecycle()
    val nearCapacity by env.history.isNearCapacity.collectAsStateWithLifecycle()
    val settings by env.settings.state.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }

    ForestixScaffold(
        nav, title = "Field log",
        actions = {
            if (entries.isNotEmpty()) {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.IosShare, contentDescription = "Export", tint = colors.primary)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("CSV (single file)") },
                        leadingIcon = {
                            Icon(Icons.Filled.Description, contentDescription = null, modifier = Modifier.size(18.dp))
                        },
                        onClick = {
                            menuOpen = false
                            scope.launch { env.history.exportCSV()?.let { shareFile(context, it, "text/csv") } }
                        })
                    DropdownMenuItem(
                        text = { Text("Bundle (5-file zip)") },
                        leadingIcon = {
                            Icon(Icons.Filled.FolderZip, contentDescription = null, modifier = Modifier.size(18.dp))
                        },
                        onClick = {
                            menuOpen = false
                            scope.launch { env.history.exportBundle(settings.logRule)?.let { shareFile(context, it, "application/zip") } }
                        })
                }
            }
        },
    ) { padding ->
        if (entries.isEmpty()) {
            EmptyState(Modifier.padding(padding))
        } else {
            LazyColumn(
                Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(ForestixSpace.md),
            ) {
                // Plot summary card — shows BA / TPA / QMD + stocking gauge
                // + species mix for the active plot (iOS FieldLogScreen
                // renders it atop the log when a plot is active).
                val plotID = activePlotID
                val plot = plotID?.let { id -> plots.firstOrNull { it.id == id } }
                if (plotID != null && plot != null) {
                    val defaultID = plots.firstOrNull { it.isDefault }?.id
                    val plotEntries = entries.filter { (it.plotID ?: defaultID) == plotID }
                    if (plotEntries.isNotEmpty()) {
                        item(key = "plotSummary") {
                            Box(Modifier.padding(bottom = ForestixSpace.md)) {
                                PlotSummaryCard(
                                    plot = plot,
                                    entries = plotEntries,
                                    unitSystem = settings.unitSystem,
                                    logRule = settings.logRule,
                                    areaUnit = settings.unitSystem.areaUnit)
                            }
                        }
                    }
                }

                // Summary + capacity — one surface-backed grouped card.
                item(key = "summary") {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(ForestixRadius.card)
                            .background(colors.surface),
                    ) {
                        SummaryHeader(entries)
                        if (nearCapacity) {
                            HorizontalDivider(
                                color = colors.divider, thickness = 0.5.dp,
                                modifier = Modifier.padding(start = ForestixSpace.md))
                            CapacityBanner()
                        }
                    }
                }

                item(key = "columnHeader") { ColumnHeader() }

                // Rows — one grouped surface card with 0.5 dividers and
                // trailing swipe-to-delete (iOS insetGrouped look).
                itemsIndexed(entries, key = { _, e -> e.id }) { index, entry ->
                    val last = index == entries.lastIndex
                    val shape = when {
                        entries.size == 1 -> ForestixRadius.card
                        index == 0 -> RoundedCornerShape(
                            topStart = ForestixRadius.cardDp, topEnd = ForestixRadius.cardDp)
                        last -> RoundedCornerShape(
                            bottomStart = ForestixRadius.cardDp, bottomEnd = ForestixRadius.cardDp)
                        else -> RectangleShape
                    }
                    Column(Modifier.fillMaxWidth().clip(shape).background(colors.surface)) {
                        SwipeToDeleteRow(onDelete = { env.history.delete(entry.id) }) {
                            FieldLogRow(entry, settings.unitSystem)
                        }
                        if (!last) {
                            HorizontalDivider(
                                color = colors.divider, thickness = 0.5.dp,
                                modifier = Modifier.padding(start = ForestixSpace.md))
                        }
                    }
                }

                item(key = "swipeHint") {
                    Text(
                        "Swipe left to delete.",
                        style = Forestix.type.caption, color = colors.textTertiary,
                        modifier = Modifier.padding(start = ForestixSpace.md, top = ForestixSpace.xs))
                }
            }
        }
    }
}

@Composable
private fun SummaryHeader(entries: List<QuickMeasureEntry>) {
    fun sameDay(t: Long): Boolean {
        val a = java.util.Calendar.getInstance().apply { timeInMillis = t }
        val b = java.util.Calendar.getInstance()
        return a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR) &&
            a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR)
    }
    val todayCount = entries.count { sameDay(it.createdAt) }
    val last = entries.firstOrNull()?.let { relativeAgo(it.createdAt) } ?: "—"
    Row(
        Modifier.fillMaxWidth().padding(horizontal = ForestixSpace.md, vertical = ForestixSpace.sm),
        horizontalArrangement = Arrangement.spacedBy(ForestixSpace.lg),
    ) {
        Cell("${entries.size}", "TOTAL")
        Cell("$todayCount", "TODAY")
        Cell(last, "LAST")
    }
}

@Composable
private fun Cell(value: String, label: String) {
    val colors = Forestix.colors
    val type = Forestix.type
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, style = type.dataLarge, color = colors.textPrimary)
        Text(label, style = type.sectionHead.copy(letterSpacing = 1.2.sp), color = colors.textTertiary)
    }
}

@Composable
private fun CapacityBanner() {
    val colors = Forestix.colors
    val type = Forestix.type
    Box(Modifier.padding(horizontal = ForestixSpace.md, vertical = ForestixSpace.xs)) {
        Row(
            Modifier.fillMaxWidth().clip(ForestixRadius.control).background(colors.confidenceWarn.copy(alpha = 0.12f)).padding(ForestixSpace.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ForestixSpace.xs),
        ) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = colors.confidenceWarn, modifier = Modifier.size(16.dp))
            Text("Log nearly full. Export soon to free space.", style = type.caption, color = colors.textSecondary)
        }
    }
}

@Composable
private fun ColumnHeader() {
    val colors = Forestix.colors
    val type = Forestix.type
    Row(
        Modifier.fillMaxWidth().padding(
            start = ForestixSpace.md, end = ForestixSpace.md,
            top = ForestixSpace.md, bottom = ForestixSpace.xs),
        horizontalArrangement = Arrangement.spacedBy(ForestixSpace.sm),
    ) {
        Text("TYPE", style = type.sectionHead.copy(letterSpacing = 1.2.sp), color = colors.textTertiary, modifier = Modifier.width(52.dp))
        Text("VALUE", style = type.sectionHead.copy(letterSpacing = 1.2.sp), color = colors.textTertiary, modifier = Modifier.width(96.dp), textAlign = TextAlign.End)
        Text("PRECISION", style = type.sectionHead.copy(letterSpacing = 1.2.sp), color = colors.textTertiary, modifier = Modifier.width(64.dp), textAlign = TextAlign.End)
        Spacer(Modifier.weight(1f))
        Text("QUALITY", style = type.sectionHead.copy(letterSpacing = 1.2.sp), color = colors.textTertiary)
    }
}

@Composable
private fun FieldLogRow(entry: QuickMeasureEntry, unitSystem: UnitSystem) {
    val colors = Forestix.colors
    val type = Forestix.type
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .padding(horizontal = ForestixSpace.md, vertical = ForestixSpace.sm),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ForestixSpace.sm)) {
            Text(typeLabel(entry), style = type.dataSmall, color = colors.textSecondary, modifier = Modifier.width(52.dp))
            Text(valueText(entry, unitSystem), style = type.data, color = colors.textPrimary, modifier = Modifier.width(96.dp), textAlign = TextAlign.End)
            Text(sigmaText(entry, unitSystem), style = type.dataSmall, color = colors.textTertiary, modifier = Modifier.width(64.dp), textAlign = TextAlign.End)
            Spacer(Modifier.weight(1f))
            TierChip(entry.confidenceRaw)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(start = 52.dp + ForestixSpace.sm)) {
            entry.treeNumber?.let {
                Text("#$it", style = type.dataSmall, color = colors.primary,
                    modifier = Modifier.border(0.5.dp, colors.primary.copy(alpha = 0.4f), CircleShape).padding(horizontal = 5.dp, vertical = 1.dp))
            }
            Text(relativeAgo(entry.createdAt), style = type.dataSmall, color = colors.textTertiary)
        }
    }
}

@Composable
private fun TierChip(rawTier: String) {
    val d = confidenceDescriptor(rawTier)
    val type = Forestix.type
    Text(
        d.label.uppercase(),
        style = type.sectionHead.copy(letterSpacing = 0.8.sp),
        color = d.color,
        modifier = Modifier.border(0.75.dp, d.color, ForestixRadius.chip).padding(horizontal = ForestixSpace.xs, vertical = 3.dp),
    )
}

@Composable
private fun EmptyState(modifier: Modifier) {
    val colors = Forestix.colors
    val type = Forestix.type
    // iOS: uniform 16 gaps, content ABOVE centre — one flexible spacer on
    // top, two on the bottom (1 : 2 split).
    Column(
        modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(ForestixSpace.md),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        Icon(Icons.Filled.Inbox, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(34.dp))
        Text("No readings yet", style = type.bodyBold, color = colors.textPrimary)
        Text(
            "Accept a scan in a measurement tool and it'll land here.",
            style = type.caption, color = colors.textSecondary, textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = ForestixSpace.xl),
        )
        Spacer(Modifier.weight(2f))
    }
}

// MARK: - Row formatting (ports of FieldLogRow's switches) ----------------

private fun typeLabel(e: QuickMeasureEntry) = when (e.kind) {
    MeasureKind.DBH -> "DBH"
    MeasureKind.HEIGHT -> "Height"
    MeasureKind.CROWN -> "Crown"
    MeasureKind.DISTANCE -> "Dist"
    MeasureKind.SAMPLING_PLOT -> "Plot"
}

private fun valueText(e: QuickMeasureEntry, system: UnitSystem): String = when (e.kind) {
    MeasureKind.DBH -> MeasurementFormatter.diameter(e.value, system)
    MeasureKind.HEIGHT -> MeasurementFormatter.height(e.value, system)
    MeasureKind.CROWN -> String.format(Locale.US, "%.1f × %.1f m", e.value, e.secondaryValue ?: 0.0)
    MeasureKind.DISTANCE -> if (e.value < 1) String.format(Locale.US, "%.0f cm", e.value * 100) else String.format(Locale.US, "%.2f m", e.value)
    MeasureKind.SAMPLING_PLOT -> {
        val area = e.secondaryValue ?: (PI * e.value * e.value)
        String.format(Locale.US, "r %.1f m · %.1f m²", e.value, area)
    }
}

private fun sigmaText(e: QuickMeasureEntry, system: UnitSystem): String {
    val s = e.sigma ?: return "—"
    if (s <= 0) return "—"
    return when (e.kind) {
        MeasureKind.DBH -> MeasurementFormatter.diameterSigma(s, system)
        MeasureKind.HEIGHT -> MeasurementFormatter.heightSigma(s, system)
        else -> String.format(Locale.US, "±%.2f m", s)
    }
}

private fun relativeAgo(epochMs: Long): String {
    val now = System.currentTimeMillis()
    val diff = (now - epochMs).coerceAtLeast(0)
    val min = diff / 60000
    val hr = min / 60
    val day = hr / 24
    return when {
        min < 1 -> "now"
        min < 60 -> "${min}m"
        hr < 24 -> "${hr}h"
        day < 7 -> "${day}d"
        else -> SimpleDateFormat("MMM d", Locale.US).format(Date(epochMs))
    }
}
