// Field log — port of iOS FieldLogScreen.
//
// FIELD REPORT 5 — the log is now ONE ROW PER TREE.
//
// It used to be one row per MEASUREMENT, so a tree the cruiser had
// diametered and then measured the height of appeared twice, in two places
// in the list, joined only by a small "#12" on each row's meta line.
// Reading back a plot meant scanning for pairs. The table now leads with the
// tree number and puts that tree's diameter and height beside it, which is
// the shape of the paper tally sheet this replaces.
//
// The RANGE and QUAL columns are gone with it. Four columns on a phone had
// every cell scaling to fit, and the two that were dropped were the two a
// cruiser was not reading in the field. Neither number is lost: sigma is
// still recorded on the entry, still exported, and now shown in the detail
// sheet — which is what a tap on a row opens, and which carries the whole
// record (species, stem position, damage, note, position, photo, and in
// developer mode the ground truth typed against that tree).
//
// Readings that were never attached to a tree — a sampling-plot record, a
// standalone crown or distance — still get their own row. They are real
// records; grouping by tree must not make them disappear.
//
// The screen otherwise keeps its shape: plot summary card for the active
// plot, grouped summary card (total / today / last + capacity banner), then
// one grouped surface card of rows with hairline dividers and trailing
// swipe-to-delete, plus an Export menu (single CSV or 5-file ZIP bundle).

package com.hcjeong.forestix.ui.screens

import android.app.Activity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.hcjeong.forestix.LocalAppEnvironment
import com.hcjeong.forestix.common.MeasurementFormatter
import com.hcjeong.forestix.common.RegionalSpecies
import com.hcjeong.forestix.common.UnitSystem
import com.hcjeong.forestix.common.areaUnit
import com.hcjeong.forestix.data.MeasureKind
import com.hcjeong.forestix.data.QuickMeasureEntry
import com.hcjeong.forestix.sensors.RawCaptureStore
import com.hcjeong.forestix.ui.MeasurePhotoStore
import com.hcjeong.forestix.ui.screens.plot.PlotSummaryCard
import com.hcjeong.forestix.ui.shareFile
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixDenseTextScale
import com.hcjeong.forestix.ui.theme.ForestixRadius
import com.hcjeong.forestix.ui.theme.ForestixSpace
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI

@OptIn(ExperimentalMaterial3Api::class)
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
    /// The row whose detail sheet is open. Null = closed.
    var inspecting by remember { mutableStateOf<FieldLogRowModel?>(null) }
    /// The row a swipe asked to delete, held until the cruiser confirms.
    /// Only multi-reading rows go through here (see onDelete below).
    var pendingDelete by remember { mutableStateOf<FieldLogRowModel?>(null) }

    val rows = remember(entries) { fieldLogRows(entries) }

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
                        text = { Text("All tables (zip of 5 CSVs)") },
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
                itemsIndexed(rows, key = { _, r -> r.id }) { index, row ->
                    val last = index == rows.lastIndex
                    val shape = when {
                        rows.size == 1 -> ForestixRadius.card
                        index == 0 -> RoundedCornerShape(
                            topStart = ForestixRadius.cardDp, topEnd = ForestixRadius.cardDp)
                        last -> RoundedCornerShape(
                            bottomStart = ForestixRadius.cardDp, bottomEnd = ForestixRadius.cardDp)
                        else -> RectangleShape
                    }
                    Column(Modifier.fillMaxWidth().clip(shape).background(colors.surface)) {
                        SwipeToDeleteRow(
                            onDelete = {
                                // One reading goes immediately; a tree
                                // carrying several asks first.
                                if (row.entries.size == 1) {
                                    env.history.delete(row.entries[0].id)
                                } else {
                                    pendingDelete = row
                                }
                            },
                        ) {
                            FieldLogRow(
                                row, settings.unitSystem,
                                onClick = { inspecting = row })
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
                        "Tap a tree for the full record. Swipe left to delete.",
                        style = Forestix.type.caption, color = colors.textTertiary,
                        modifier = Modifier.padding(start = ForestixSpace.md, top = ForestixSpace.xs))
                }
            }
        }
    }

    inspecting?.let { row ->
        ModalBottomSheet(
            onDismissRequest = { inspecting = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = colors.canvas,
        ) {
            FieldLogDetailSheet(
                row = row,
                unitSystem = settings.unitSystem,
                developerMode = settings.developerMode)
        }
    }

    // A tree row can carry more than one reading, and a swipe is a cheap
    // gesture — so a swipe that would take BOTH the diameter and the height
    // says what it is about to take first.
    pendingDelete?.let { row ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete ${row.title}?") },
            text = { Text(row.deleteWarning) },
            confirmButton = {
                TextButton(onClick = {
                    row.entries.forEach { env.history.delete(it.id) }
                    pendingDelete = null
                }) { Text("Delete ${row.entries.size} readings", color = colors.confidenceBad) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
            containerColor = colors.surface,
        )
    }
}

// MARK: - Row model -------------------------------------------------------

/// One row of the log: everything recorded against one tree, or a single
/// reading that was never attached to one.
///
/// GROUPING IS BY (plot, tree number), not by tree number alone. Tree
/// numbering restarts on each plot, so keying on the number by itself would
/// have merged plot 1's tree 4 with plot 2's tree 4 into a row claiming a
/// diameter and a height that came off two different trees.
data class FieldLogRowModel(
    val id: String,
    /// Null for a reading with no tree number — a sampling-plot record, or
    /// a standalone crown / distance measurement.
    val treeNumber: Int?,
    /// Newest diameter and height on this tree. Earlier re-measurements stay
    /// in [entries] and are listed in the detail sheet.
    val dbh: QuickMeasureEntry?,
    val height: QuickMeasureEntry?,
    /// Every reading behind this row, newest first.
    val entries: List<QuickMeasureEntry>,
    /// Sort key — the most recent reading in the group.
    val latest: Long,
) {
    val title: String
        get() = treeNumber?.let { "Tree #$it" } ?: kindWord(entries.first().kind)

    /// What a destructive swipe is actually about to remove.
    val deleteWarning: String
        get() {
            val listed = entries.map { kindWord(it.kind).lowercase(Locale.US) }
                .distinct().sorted().joinToString(" and ")
            return "This removes the $listed recorded against it. It cannot be undone."
        }
}

internal fun kindWord(kind: MeasureKind): String = when (kind) {
    MeasureKind.DBH -> "DBH"
    MeasureKind.HEIGHT -> "Height"
    MeasureKind.CROWN -> "Crown"
    MeasureKind.DISTANCE -> "Dist"
    MeasureKind.SAMPLING_PLOT -> "Plot"
}

/// Collapses the flat entry list into rows, newest tree first.
///
/// [entries] arrives newest-first from the history, and that order is
/// preserved inside each group, so `dbh` / `height` pick up the latest
/// reading of each kind without a second sort.
internal fun fieldLogRows(entries: List<QuickMeasureEntry>): List<FieldLogRowModel> {
    val order = mutableListOf<String>()
    val grouped = LinkedHashMap<String, MutableList<QuickMeasureEntry>>()
    for (e in entries) {
        val key = e.treeNumber?.let { "t|${e.plotID?.toString() ?: "-"}|$it" }
        // Never merged with anything: one row, this reading.
            ?: "e|${e.id}"
        if (!grouped.containsKey(key)) order.add(key)
        grouped.getOrPut(key) { mutableListOf() }.add(e)
    }
    return order.mapNotNull { key ->
        val group = grouped[key] ?: return@mapNotNull null
        val first = group.firstOrNull() ?: return@mapNotNull null
        FieldLogRowModel(
            id = key,
            treeNumber = first.treeNumber,
            dbh = group.firstOrNull { it.kind == MeasureKind.DBH },
            height = group.firstOrNull { it.kind == MeasureKind.HEIGHT },
            entries = group,
            latest = group.maxOf { it.createdAt },
        )
    }
}

// MARK: - Summary / capacity / empty --------------------------------------

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
    ForestixDenseTextScale {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = ForestixSpace.md, vertical = ForestixSpace.sm),
            horizontalArrangement = Arrangement.spacedBy(ForestixSpace.lg),
        ) {
            Cell("${entries.size}", "TOTAL")
            Cell("$todayCount", "TODAY")
            Cell(last, "LAST")
        }
    }
}

@Composable
private fun Cell(value: String, label: String) {
    val colors = Forestix.colors
    val type = Forestix.type
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // Both single-line and unwrappable: these cells size to their own
        // content, and a 26 sp count split across two lines is the same
        // defect as "PRECISI/ON" one card down (G3).
        Text(value, style = type.dataLarge, color = colors.textPrimary,
            maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        Text(label, style = type.sectionHead.copy(letterSpacing = 1.2.sp), color = colors.textTertiary,
            maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
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

// MARK: - Column geometry -------------------------------------------------
//
// THREE columns now, not four. Dropping RANGE and QUAL gave back roughly
// 128 dp on a 360 dp phone, which is why every cell here sits well inside
// its column instead of scaling to fit as the four-column table did. A row
// has screen − 32 (list inset) − 32 (row padding) − 12 (two 6 dp gutters)
// to share:
//
//   column   widest content        needs    360 dp   411 dp   320 dp
//   TREE     "#128"                 32 dp    61 dp    73 dp    52 dp
//   DBH      "150.0 cm"             82 dp   111 dp   134 dp    96 dp
//   HEIGHT   "150.00 ft"            88 dp   111 dp   134 dp    96 dp
//
// Every heading is a single unwrappable word, so nothing can break
// mid-word the way "PRECISI/ON" and "QUALI/TY" did. The two numeric cells
// may take a second line instead — a sampling-plot reading is wider than
// any phone column, and a taller row is better than a measurement the
// cruiser cannot read in full.
private const val ColTreeWeight = 1.0f
private const val ColDbhWeight = 1.83f
private const val ColHeightWeight = 1.83f
private val ColGap = 6.dp

/// The ONE definition of the field-log grid. The header and every row go
/// through it, so the columns cannot drift apart again.
@Composable
private fun FieldLogColumns(
    modifier: Modifier = Modifier,
    treeSlot: @Composable () -> Unit,
    dbhSlot: @Composable () -> Unit,
    heightSlot: @Composable () -> Unit,
) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ColGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(ColTreeWeight), contentAlignment = Alignment.CenterStart) { treeSlot() }
        Box(Modifier.weight(ColDbhWeight), contentAlignment = Alignment.CenterEnd) { dbhSlot() }
        Box(Modifier.weight(ColHeightWeight), contentAlignment = Alignment.CenterEnd) { heightSlot() }
    }
}

/// A column heading: one line, never wrapped, never hyphenated.
@Composable
private fun HeaderLabel(text: String) {
    Text(
        text,
        // 0.6 tracking, not 1.2: across a six-letter heading the extra
        // 0.6 sp per character is ~4 dp, which is most of the margin that
        // decides whether the word survives at a large system font size.
        style = Forestix.type.sectionHead.copy(letterSpacing = 0.6.sp),
        color = Forestix.colors.textTertiary,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun ColumnHeader() = ForestixDenseTextScale {
    FieldLogColumns(
        Modifier.padding(
            start = ForestixSpace.md, end = ForestixSpace.md,
            top = ForestixSpace.md, bottom = ForestixSpace.xs),
        // Same three strings on iOS.
        treeSlot = { HeaderLabel("TREE") },
        dbhSlot = { HeaderLabel("DBH") },
        heightSlot = { HeaderLabel("HEIGHT") },
    )
}

// MARK: - Row -------------------------------------------------------------

// The header and the rows carry their own scale bound, so the grid cannot
// be composed anywhere without it.
@Composable
private fun FieldLogRow(
    row: FieldLogRowModel,
    unitSystem: UnitSystem,
    onClick: () -> Unit,
) = ForestixDenseTextScale {
    val colors = Forestix.colors
    val type = Forestix.type
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = ForestixSpace.md, vertical = ForestixSpace.sm),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        FieldLogColumns(
            treeSlot = {
                Text(
                    row.treeNumber?.let { "#$it" } ?: kindWord(row.entries.first().kind),
                    style = type.data, color = colors.textPrimary,
                    maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            },
            dbhSlot = {
                if (row.treeNumber == null) {
                    // A plot record or a standalone crown / distance has no
                    // diameter-and-height shape to fill. Its reading spans
                    // the two measurement columns rather than being forced
                    // into one of them under a heading it does not match.
                    Text(
                        looseValue(row.entries.first(), unitSystem),
                        style = type.data, color = colors.textPrimary,
                        textAlign = TextAlign.End, maxLines = 3,
                        overflow = TextOverflow.Ellipsis)
                } else {
                    Text(
                        row.dbh?.let { MeasurementFormatter.diameter(it.value, unitSystem) } ?: "—",
                        style = type.data,
                        color = if (row.dbh == null) colors.textTertiary else colors.textPrimary,
                        textAlign = TextAlign.End, maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                }
            },
            heightSlot = {
                if (row.treeNumber != null) {
                    Text(
                        row.height?.let { MeasurementFormatter.height(it.value, unitSystem) } ?: "—",
                        style = type.data,
                        color = if (row.height == null) colors.textTertiary else colors.textPrimary,
                        textAlign = TextAlign.End, maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                }
            },
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The kind words that used to be the TYPE column, kept here only
            // when a row carries something other than the two named columns
            // (a crown on the same tree).
            if (row.treeNumber != null) {
                row.entries
                    .filter { it.kind != MeasureKind.DBH && it.kind != MeasureKind.HEIGHT }
                    .map { kindWord(it.kind) }.distinct().sorted()
                    .forEach {
                        Text(it, style = type.dataSmall, color = colors.textTertiary,
                            maxLines = 1, softWrap = false)
                    }
            }
            speciesName(row)?.let {
                Text(it, style = type.dataSmall, color = colors.textSecondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(relativeAgo(row.latest), style = type.dataSmall, color = colors.textTertiary,
                maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.weight(1f))
            // The standard "there is more behind this" affordance — the row
            // is tappable and this says so.
            Icon(
                Icons.Filled.ChevronRight, contentDescription = null,
                tint = colors.textTertiary, modifier = Modifier.size(14.dp))
        }
    }
}

private fun speciesName(row: FieldLogRowModel): String? =
    row.entries.firstNotNullOfOrNull { it.speciesCode?.takeIf(String::isNotEmpty) }
        ?.let { RegionalSpecies.nameForCode(it) }

private fun looseValue(e: QuickMeasureEntry, system: UnitSystem): String = when (e.kind) {
    MeasureKind.DBH -> MeasurementFormatter.diameter(e.value, system)
    MeasureKind.HEIGHT -> MeasurementFormatter.height(e.value, system)
    MeasureKind.CROWN -> String.format(Locale.US, "%.1f × %.1f m", e.value, e.secondaryValue ?: 0.0)
    MeasureKind.DISTANCE -> MeasurementFormatter.distance(e.value, system)
    MeasureKind.SAMPLING_PLOT -> {
        val area = e.secondaryValue ?: (PI * e.value * e.value)
        String.format(Locale.US, "%.1f m radius · %.1f m²", e.value, area)
    }
}

private fun sigmaText(e: QuickMeasureEntry, system: UnitSystem): String? {
    val s = e.sigma ?: return null
    if (s <= 0) return null
    return when (e.kind) {
        MeasureKind.DBH -> MeasurementFormatter.diameterSigma(s, system)
        MeasureKind.HEIGHT -> MeasurementFormatter.heightSigma(s, system)
        else -> String.format(Locale.US, "±%.2f m", s)
    }
}

// MARK: - Detail sheet ----------------------------------------------------

/// The whole record behind one row.
///
/// FIELD REPORT 5 asked for this: the log table now shows the two numbers a
/// cruiser reads while walking, and everything else — what was typed into
/// the details sheet at capture time, the species, the ± band, where the
/// reading was taken, and in developer mode the ground truth entered against
/// this tree — lives one tap away instead of being squeezed into columns.
@Composable
private fun FieldLogDetailSheet(
    row: FieldLogRowModel,
    unitSystem: UnitSystem,
    developerMode: Boolean,
) {
    val colors = Forestix.colors
    val type = Forestix.type
    val context = LocalContext.current

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = ForestixSpace.md)
            .padding(bottom = ForestixSpace.xl),
        verticalArrangement = Arrangement.spacedBy(ForestixSpace.md),
    ) {
        Text(row.title, style = type.title, color = colors.textPrimary)

        SheetSection("MEASUREMENTS") {
            row.entries.forEach { e ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(kindWord(e.kind), style = type.body, color = colors.textSecondary)
                        Spacer(Modifier.weight(1f))
                        Text(looseValue(e, unitSystem), style = type.data, color = colors.textPrimary)
                    }
                    // The ± band the table used to carry. It is the
                    // measurement's own precision, so it belongs with the
                    // measurement rather than in a column being scaled to
                    // fit on a phone.
                    sigmaText(e, unitSystem)?.let {
                        Text(it, style = type.dataSmall, color = colors.textTertiary)
                    }
                }
            }
        }

        val species = row.entries.firstNotNullOfOrNull {
            it.speciesCode?.takeIf(String::isNotEmpty)
        }
        val position = row.entries.firstNotNullOfOrNull { it.position }
        val damage = row.entries.flatMap { it.damageCodes }.distinct().sorted()
        val note = row.entries.mapNotNull { it.note?.trim()?.takeIf(String::isNotEmpty) }
            .joinToString("\n").takeIf(String::isNotEmpty)

        SheetSection("DETAILS") {
            SheetRow("Species", species?.let { "${RegionalSpecies.nameForCode(it)} · $it" } ?: "—")
            position?.let { SheetRow("Stem position", it.displayName) }
            if (damage.isNotEmpty()) SheetRow("Damage", damage.joinToString(", "))
            note?.let {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Note", style = type.body, color = colors.textSecondary)
                    Text(it, style = type.body, color = colors.textPrimary)
                }
            }
            if (species == null && position == null && damage.isEmpty() && note == null) {
                Text("Nothing was attached to this reading.",
                    style = type.caption, color = colors.textTertiary)
            }
        }

        SheetSection("RECORDED") {
            SheetRow("When", SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date(row.latest)))
            val fix = row.entries.firstOrNull { it.latitude != null && it.longitude != null }
            // Said out loud rather than left blank: a reading with no fix is
            // a different thing from one whose fix was not shown.
            SheetRow(
                "Position",
                fix?.let { String.format(Locale.US, "%.5f, %.5f", it.latitude, it.longitude) }
                    ?: "not recorded")
            row.entries.firstNotNullOfOrNull { it.captureMode }?.let {
                SheetRow("Capture", if (it == "manual") "Adjusted by hand" else "Automatic")
            }
            row.entries.firstNotNullOfOrNull { it.photoPath }?.let { name ->
                FieldLogPhoto(name)
            }
        }

        // Hand-measured values typed against this tree number, read back
        // from the raw-capture bundles — which is where the truth is
        // actually stored. Developer-mode only, because that is the only
        // mode in which the field exists to type into.
        if (developerMode && row.treeNumber != null) {
            val truths = remember(row.id) {
                RawCaptureStore.list(context)
                    .filter { it.treeNumber == row.treeNumber && it.truthValue != null }
                    .map { it.kind to it.truthValue!! }
            }
            if (truths.isNotEmpty()) {
                SheetSection("GROUND TRUTH") {
                    truths.forEach { (kind, value) ->
                        SheetRow(
                            if (kind == "dbh") "Tape diameter" else "Measured height",
                            if (kind == "dbh") String.format(Locale.US, "%.1f cm", value)
                            else String.format(Locale.US, "%.2f m", value))
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetSection(title: String, content: @Composable () -> Unit) {
    val colors = Forestix.colors
    val type = Forestix.type
    Column(verticalArrangement = Arrangement.spacedBy(ForestixSpace.xs)) {
        Text(title, style = type.sectionHead.copy(letterSpacing = 0.6.sp),
            color = colors.textTertiary)
        Column(
            Modifier
                .fillMaxWidth()
                .clip(ForestixRadius.card)
                .background(colors.surface)
                .padding(ForestixSpace.md),
            verticalArrangement = Arrangement.spacedBy(ForestixSpace.sm),
        ) { content() }
    }
}

@Composable
private fun SheetRow(label: String, value: String) {
    val colors = Forestix.colors
    val type = Forestix.type
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = type.body, color = colors.textSecondary)
        Spacer(Modifier.weight(1f))
        Text(value, style = type.body, color = colors.textPrimary, textAlign = TextAlign.End)
    }
}

/// The capture photo, if the file is still there. A missing file says so
/// rather than leaving an empty box — a blank row would read as "no photo
/// was taken".
@Composable
private fun FieldLogPhoto(name: String) {
    val colors = Forestix.colors
    val type = Forestix.type
    val context = LocalContext.current
    val activity = context as? Activity
    val bitmap = remember(name) {
        activity?.let {
            val f = MeasurePhotoStore.file(it, name)
            if (f.exists()) android.graphics.BitmapFactory.decodeFile(f.absolutePath) else null
        }
    }
    if (bitmap != null) {
        Image(
            bitmap.asImageBitmap(),
            contentDescription = "Capture photo",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 220.dp)
                .clip(ForestixRadius.control),
        )
    } else {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Photo", style = type.body, color = colors.textSecondary)
            Spacer(Modifier.weight(1f))
            Text("file missing", style = type.body, color = colors.textTertiary)
        }
    }
}

// MARK: - Compact relative time -------------------------------------------

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
