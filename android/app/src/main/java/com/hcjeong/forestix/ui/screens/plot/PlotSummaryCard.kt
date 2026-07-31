// Port of iOS Screens/PlotSummaryCard.swift.
// In-app summary card — closes the "is this reasonable?" loop in the field. A
// cruiser gets an instant post-plot readout with standard stand stats and
// doesn't need a desktop tool to know whether to re-cruise.
//
// THE CARD IS NOW A RENDERER. It used to hold the quick-measure math itself
// and could therefore only ever describe a quick-measure plot — which is why
// it appeared for one special case and went blank or, worse, stale under any
// other filter. Every number it draws now arrives finished in a
// [FieldLogSummary], built for whatever the field log's filter is currently
// set to; see FieldLogSummary.kt for the three branches and for why the cruise
// ones reuse the cruise view models rather than recomputing.
//
// Renders:
//   • A tappable heading — it opens the detail sheet, which carries the values
//     this card has no room for AND the settings behind them. A volume with no
//     log rule or equation named beside it is a number a cruiser cannot check,
//     and this card has never had space to name them.
//   • Four top-line stats in a 2 × 2 grid so the labels and the values fit a
//     small phone (G3)
//   • Species mix breakdown
//
// The Stocking & Density gauge is gone — see the note at the foot of this
// file for why.

package com.hcjeong.forestix.ui.screens.plot

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hcjeong.forestix.common.RegionalSpecies
import com.hcjeong.forestix.ui.screens.FieldLogSummary
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixDenseTextScale
import com.hcjeong.forestix.ui.theme.ForestixRadius
import com.hcjeong.forestix.ui.theme.ForestixSpace
import java.util.Locale

@Composable
fun PlotSummaryCard(
    summary: FieldLogSummary,
    /// Raised by the heading. The host owns the detail sheet because the card
    /// sits inside a LazyColumn item.
    onOpenDetail: () -> Unit,
    // The card is a dense readout of labelled numbers, so it carries the same
    // bound on the system font scale as the field-log table below it (G3).
    // Nothing else on the screen is affected.
) = ForestixDenseTextScale {
    val colors = Forestix.colors
    val type = Forestix.type

    Column(
        Modifier
            .fillMaxWidth()
            .clip(ForestixRadius.card)
            .background(colors.surface)
            .border(0.5.dp, colors.divider, ForestixRadius.card)
            .padding(ForestixSpace.md),
        verticalArrangement = Arrangement.spacedBy(ForestixSpace.md),
    ) {
        // MARK: - Header
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // The heading is the control. A chevron rather than an info glyph:
            // it opens a view, which is what a chevron means everywhere else in
            // this app, and the row it sits on is the only thing on the card a
            // tap could plausibly be aimed at.
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onOpenDetail),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    summary.heading,
                    style = type.sectionHead.copy(letterSpacing = 1.5.sp),
                    color = colors.textTertiary)
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = FieldLogSummary.DETAIL_TITLE,
                    tint = colors.textTertiary, modifier = Modifier.size(14.dp))
            }
            Text(
                summary.title, style = type.bodyBold, color = colors.textPrimary,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            summary.subtitle?.let {
                Text(
                    it, style = type.caption, color = colors.textSecondary,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }

        HorizontalDivider(color = colors.divider, thickness = 0.5.dp)

        // MARK: - Top stats
        //
        // FIELD REPORT G3 — these four used to sit in ONE row of four cells
        // with no gutters between them. On a 360 dp phone that is ~62 dp a
        // cell: "BASAL/HA" and "TREES/HA" ran straight into their neighbours,
        // "MEAN DBH" clipped to "MEAN", and a 26 sp value came out as "12....".
        // Two rows of two give each cell ~136 dp — room for the longest label
        // AND for "12.4 cm" at full size — with a gutter around every divider.
        Column(verticalArrangement = Arrangement.spacedBy(ForestixSpace.sm)) {
            summary.cells.chunked(2).forEachIndexed { index, pair ->
                if (index > 0) {
                    HorizontalDivider(color = colors.divider, thickness = 0.5.dp)
                }
                StatsRow(pair)
            }
        }

        summary.note?.let {
            Text(it, style = type.caption, color = colors.textSecondary)
        }

        if (summary.speciesMix.isNotEmpty()) {
            HorizontalDivider(color = colors.divider, thickness = 0.5.dp)

            // MARK: - Species mix
            Column(verticalArrangement = Arrangement.spacedBy(ForestixSpace.xs)) {
                Text(
                    "SPECIES MIX",
                    style = type.sectionHead.copy(letterSpacing = 1.5.sp),
                    color = colors.textTertiary)
                summary.speciesMix.forEach { row ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(ForestixSpace.xs),
                    ) {
                        Text(
                            if (row.code.isEmpty()) "—"
                            else RegionalSpecies.nameForCode(row.code),
                            style = type.dataSmall,
                            color = colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.width(96.dp))
                        // Bar viz of the share, with a numeric label.
                        Box(
                            Modifier
                                .weight(1f)
                                .height(8.dp)
                                .clip(CircleShape)
                                .background(colors.surfaceRaised),
                        ) {
                            Box(
                                Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(row.share.toFloat().coerceIn(0f, 1f))
                                    .clip(CircleShape)
                                    .background(colors.primary.copy(alpha = 0.5f)))
                        }
                        Text(
                            String.format(Locale.US, "%.0f%%", row.share * 100),
                            style = type.dataSmall,
                            color = colors.textPrimary,
                            modifier = Modifier.width(44.dp),
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

// MARK: - Cells

/// One half of the 2 × 2 stat grid. `heightIn(min = )` rather than a fixed
/// height, so a larger system text size makes the row TALLER instead of
/// cutting the label off, and a gutter on either side of the hairline so
/// "TREES/HA" and "MEAN DBH" can never touch (G3).
@Composable
private fun StatsRow(cells: List<FieldLogSummary.Cell>) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ForestixSpace.sm),
    ) {
        cells.forEachIndexed { index, cell ->
            if (index > 0) CellDivider()
            StatsCell(cell, Modifier.weight(1f))
        }
    }
}

@Composable
private fun StatsCell(
    cell: FieldLogSummary.Cell,
    modifier: Modifier = Modifier,
) {
    val colors = Forestix.colors
    val type = Forestix.type
    Column(
        modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Neither line may wrap: a stat split as "12.4/cm" or "BASAL//HA" is
        // the defect this card was fixed for.
        //
        // FIELD REPORT — these values were `dataLarge` (26 sp), which the
        // cruiser read as awkwardly oversized: a value carrying its unit
        // ("31.4 cm") is far wider at that step than the bare counts it was
        // drawn for. One step down the SAME data scale (`data`, 17 sp) is the
        // size the log's own row values already use, so the card and the rows
        // beneath it read as one sheet. iOS matches.
        Text(
            cell.value,
            style = type.data,
            color = colors.textPrimary,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis)
        Text(
            cell.label,
            style = type.sectionHead.copy(letterSpacing = 1.2.sp),
            color = colors.textTertiary,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CellDivider() {
    Box(
        Modifier
            .width(0.5.dp)
            .height(32.dp)
            .background(Forestix.colors.divider))
}

// MARK: - Detail sheet

/// The full set of computed values, and the settings that produced them.
///
/// It exists because the card cannot name a log rule, a volume equation or a
/// plot size, and a volume read without them is a number the cruiser has no
/// way to check — the same figure means different things under Doyle and under
/// Scribner, and under a 0.1 ac plot and a BAF 20 sweep. Everything here is
/// text the builder already finished; this sheet only lays it out.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FieldLogSummaryDetailSheet(
    summary: FieldLogSummary,
    onDismiss: () -> Unit,
) {
    val colors = Forestix.colors
    val type = Forestix.type
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.canvas,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = ForestixSpace.lg, end = ForestixSpace.lg,
                    bottom = ForestixSpace.xl),
            verticalArrangement = Arrangement.spacedBy(ForestixSpace.md),
        ) {
            Text(
                FieldLogSummary.DETAIL_TITLE,
                style = type.title, color = colors.textPrimary)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(summary.title, style = type.bodyBold, color = colors.textPrimary)
                summary.subtitle?.let {
                    Text(it, style = type.caption, color = colors.textSecondary)
                }
                summary.note?.let {
                    Text(it, style = type.caption, color = colors.textSecondary)
                }
            }
            summary.groups.filter { it.rows.isNotEmpty() }.forEach { group ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(ForestixRadius.card)
                        .background(colors.surface)
                        .padding(ForestixSpace.md),
                    verticalArrangement = Arrangement.spacedBy(ForestixSpace.sm),
                ) {
                    Text(
                        group.title.uppercase(Locale.US),
                        style = type.sectionHead.copy(letterSpacing = 1.2.sp),
                        color = colors.textTertiary)
                    group.rows.forEach { row ->
                        // Label above value, not beside it: several of these
                        // values are sentences, and a two-column row would
                        // squeeze them into a column three words wide.
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                row.label, style = type.caption,
                                color = colors.textSecondary)
                            Text(
                                row.value, style = type.body,
                                color = colors.textPrimary)
                        }
                    }
                }
            }
        }
    }
}

// MARK: - Why the stocking gauge is gone
//
// FIELD REPORT — "Over-dense" was shown with nothing on screen saying what
// it crossed, so the cruiser asked for the rule to be named. The rule turned
// out to be un-nameable:
//
//   • The verdict was `Reineke SDI / 717 >= 60 %`, and 717 was one hard-coded
//     "generic PNW conifer mid-range" maximum applied to every species on
//     every continent this app ships to. A maximum SDI is species-specific;
//     against the wrong species the same stand is understocked or over-dense
//     purely by which constant is in the source.
//   • SDI is built from trees-per-acre, and the quick branch divides by
//     `max(plot.acres ?: 0.1, 0.05)` — a plot with no acreage entered gets an
//     invented tenth of an acre. The density the word was computed from is
//     therefore not a density of anything the cruiser measured.
//
// So there is no threshold to print, and printing "60 % of SDI 717" would
// have dressed up a number nobody can defend as if it were a finding. The
// whole gauge went with the word: the coloured band and the marker encode
// the SAME percentage, so leaving them would have kept the verdict and only
// removed its name. TREES, BASAL, TREES/AC, MEAN DBH and the species mix are
// unchanged — they are counts and diameters, not a judgement.
//
// If a per-species maximum-SDI table ever lands, this comes back WITH the
// species and the maximum on screen beside the word. The invented tenth of an
// acre is now at least DISCLOSED, on the detail sheet's "Plot area" line.
