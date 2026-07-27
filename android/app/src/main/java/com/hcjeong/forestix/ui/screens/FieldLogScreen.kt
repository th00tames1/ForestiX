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
import androidx.compose.ui.text.style.TextOverflow
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
import com.hcjeong.forestix.ui.theme.ForestixDenseTextScale
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

// MARK: - Column geometry -------------------------------------------------
//
// FIELD REPORT G3 — the table used to pin FIXED widths (TYPE 52, VALUE 96,
// PRECISION 64) with a letter-spaced 13 sp header on top of them. No phone
// honours that: at 360 dp the row has ~296 dp to give and those widths plus
// the chip and three 12 dp gutters ask for ~300, so the shortfall landed on
// whatever came last and Compose broke the WORDS to fit — "PRECISI/ON",
// "QUALI/TY", and a quality chip reading "GOO/D".
//
// The columns now take WEIGHTS, so they share whatever width the device
// actually has, and each weight is sized to the widest string its column
// has to hold (Roboto Mono ≈ 0.6 em, Roboto SemiBold caps ≈ 0.62 em plus
// tracking). A row has screen − 32 (list inset) − 32 (row padding) − 18
// (three 6 dp gutters) to share:
//
//   column   widest content            needs    360 dp   411 dp   320 dp
//   TYPE     "Height"                   47 dp    57 dp    68 dp    49 dp
//   VALUE    "150.0 cm"                 82 dp    93 dp   111 dp    79 dp
//   RANGE    "±0.08 in"                 62 dp    64 dp    77 dp    55 dp
//   QUAL     chip "CHECK"               56 dp    64 dp    77 dp    55 dp
//
// Every LABEL is single-line with wrapping switched OFF, so a word can
// never be split again. The two NUMERIC cells may take extra lines
// instead — a composite crown or plot reading ("12.4 × 8.2 m",
// "5.6 m radius · 98.5 m²") is wider than any phone column, and a taller
// row is better than a measurement the cruiser cannot read in full. The
// VALUE cell gets THREE: a sampling-plot reading is ~225 dp of text over
// an 86.5 dp column at 360 dp, so at two lines it ellipsised and dropped
// its area unit ("r 5.6 m" / "· 98.5…"). Three lines carry it whole. The
// system font scale is bounded by ForestixDenseTextScale so the ~10 % of
// headroom each column carries at 360 dp is not spent by an accessibility
// text size — but Display size is NOT bounded, and it shrinks dp width, so
// a heading has to survive well under 320 dp rather than only at it.
//
// The VALUE column was re-cut when the sampling-plot row's bare "r" was
// spelled out. The gutters went 8 dp -> 6 dp and the whole 6 dp that frees
// went to VALUE, with the other three weights re-derived so they keep the
// width they had before at 360 dp AND at 320 dp — nothing is taken from a
// column that was already at its content's size. At 360 dp: TYPE 57.1,
// VALUE 92.5, RANGE 64.2, QUAL 64.2. At 320 dp: 48.9 / 79.2 / 55.0 /
// 55.0, every one at or above what it had. Those four numbers are also why
// the RANGE heading had to lose its ± rather than the columns being re-cut
// again: at 320 dp three of the four are already within ~2 dp of their own
// content, so there is nothing left to move.
private const val ColTypeWeight = 1.13f
private const val ColValueWeight = 1.83f
private const val ColPrecisionWeight = 1.27f
private const val ColQualityWeight = 1.27f
private val ColGap = 6.dp

/// The ONE definition of the field-log grid. The header and every row go
/// through it, so the columns cannot drift apart again.
@Composable
private fun FieldLogColumns(
    modifier: Modifier = Modifier,
    typeSlot: @Composable () -> Unit,
    valueSlot: @Composable () -> Unit,
    precisionSlot: @Composable () -> Unit,
    qualitySlot: @Composable () -> Unit,
) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ColGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(ColTypeWeight), contentAlignment = Alignment.CenterStart) { typeSlot() }
        Box(Modifier.weight(ColValueWeight), contentAlignment = Alignment.CenterEnd) { valueSlot() }
        Box(Modifier.weight(ColPrecisionWeight), contentAlignment = Alignment.CenterEnd) { precisionSlot() }
        Box(Modifier.weight(ColQualityWeight), contentAlignment = Alignment.CenterEnd) { qualitySlot() }
    }
}

/// A column heading: one line, never wrapped, never hyphenated.
@Composable
private fun HeaderLabel(text: String) {
    Text(
        text,
        // 0.6 tracking, not 1.2: across a seven-letter heading the extra
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
        typeSlot = { HeaderLabel("TYPE") },
        valueSlot = { HeaderLabel("VALUE") },
        // The two headings that could not fit are SHORTENED, not wrapped.
        // "PRECISION" is nine letter-spaced capitals (~83 dp) over a column
        // whose content needs 62, and "QUALITY" (~65 dp) is wider than the
        // widest chip it sits over (~56 dp) — so both words, not the
        // numbers, were setting the column widths, and both were the words
        // the screenshot showed broken ("PRECISI/ON", "QUALI/TY"). Four
        // letters each fit at every phone width and at the bounded font
        // scale. Same two words on iOS.
        //
        // "PREC" is gone: it was a truncation of "precision", which here did
        // not mean precision in the everyday sense but the propagated
        // standard deviation of the estimate. "RANGE" is what the cells below
        // it actually print ("±1.1 mm"), and every one of those cells carries
        // the ± itself, so the heading does not have to.
        //
        // It is "RANGE", not "± RANGE", because the symbol did not fit. Real
        // Roboto metrics at 13 sp SemiBold + 0.6 sp tracking (measured from
        // the shipped font, not estimated): "± RANGE" is 57.0 dp against the
        // 54.96 dp this column gets at 320 dp — it rendered "± RANG…" on a
        // small phone, and on any phone whose Display size is raised, since
        // that shrinks dp width while ForestixDenseTextScale bounds only the
        // FONT scale. Dropping the symbol costs 11.3 dp: "RANGE" is 45.8 dp,
        // which clears 320 dp with 9.2 dp (17 %) to spare. The weights could
        // not buy this back — at 320 dp TYPE, ± RANGE and QUAL are all within
        // ~2 dp of their own content, so the only donor was VALUE, and VALUE
        // is the column the previous re-cut had to widen.
        precisionSlot = { HeaderLabel("RANGE") },
        qualitySlot = { HeaderLabel("QUAL") },
    )
}

// The header and the rows carry their own scale bound, so the grid cannot
// be composed anywhere without it.
@Composable
private fun FieldLogRow(entry: QuickMeasureEntry, unitSystem: UnitSystem) = ForestixDenseTextScale {
    val colors = Forestix.colors
    val type = Forestix.type
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .padding(horizontal = ForestixSpace.md, vertical = ForestixSpace.sm),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        FieldLogColumns(
            typeSlot = {
                Text(
                    typeLabel(entry), style = type.dataSmall, color = colors.textSecondary,
                    maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            },
            valueSlot = {
                Text(
                    valueText(entry, unitSystem), style = type.data, color = colors.textPrimary,
                    textAlign = TextAlign.End,
                    // Three lines, not an ellipsis: a crown or plot reading
                    // is wider than the column on any phone, and it breaks at
                    // a space, never inside a number. At two lines the plot
                    // reading lost its area unit to the ellipsis.
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
            },
            precisionSlot = {
                Text(
                    sigmaText(entry, unitSystem), style = type.dataSmall, color = colors.textTertiary,
                    textAlign = TextAlign.End, maxLines = 2, overflow = TextOverflow.Ellipsis)
            },
            qualitySlot = { TierChip(entry.confidenceRaw) },
        )
        // Meta line, aligned under the VALUE column — the same weights as
        // the grid above rather than the old hard-coded 52 dp indent. The
        // grid spends THREE gutters before sharing out its weights and this
        // row spends one, so the two it does not spend come off the end;
        // that makes the leading spacer exactly as wide as the TYPE column.
        Row(
            Modifier.fillMaxWidth().padding(end = ColGap * 2),
            horizontalArrangement = Arrangement.spacedBy(ColGap),
        ) {
            Spacer(Modifier.weight(ColTypeWeight))
            Row(
                Modifier.weight(ColValueWeight + ColPrecisionWeight + ColQualityWeight),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                entry.treeNumber?.let {
                    Text("#$it", style = type.dataSmall, color = colors.primary,
                        maxLines = 1, softWrap = false,
                        modifier = Modifier.border(0.5.dp, colors.primary.copy(alpha = 0.4f), CircleShape).padding(horizontal = 5.dp, vertical = 1.dp))
                }
                Text(relativeAgo(entry.createdAt), style = type.dataSmall, color = colors.textTertiary,
                    maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun TierChip(rawTier: String) {
    val d = confidenceDescriptor(rawTier)
    val type = Forestix.type
    Text(
        d.label.uppercase(),
        style = type.sectionHead.copy(letterSpacing = 0.6.sp),
        color = d.color,
        // A four-letter word inside a bordered chip has nowhere to wrap TO:
        // "GOO/D" was the chip being handed less width than its own text.
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        // 6 dp of side padding rather than 8: the chip is the widest thing
        // in its column, so every dp it does not spend on padding is a dp
        // of headroom for the word inside it.
        modifier = Modifier.border(0.75.dp, d.color, ForestixRadius.chip).padding(horizontal = 6.dp, vertical = 3.dp),
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
        String.format(Locale.US, "%.1f m radius · %.1f m²", e.value, area)
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
