// Stand-and-stock report — port of iOS Screens/StandStockReport.swift.
// Species × DBH-class table for the active plot. The actual deliverable
// a consulting forester hands to a landowner / mill / agency.
//
// Output:
//   • Rows: species (sorted by tree count desc)
//   • Columns: DBH classes (10 cm bins by default)
//   • Cells: tree count + estimated BF (active log rule)
//   • Footer rows: subtotal per DBH class, grand totals
//
// Pure computation lives in `StandStockComputation`; the view is just
// rendering. The multi-table export bundle reuses this same computation.

package com.hcjeong.forestix.ui.screens.stand

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.hcjeong.forestix.LocalAppEnvironment
import com.hcjeong.forestix.common.UnitSystem
import com.hcjeong.forestix.data.MeasureKind
import com.hcjeong.forestix.data.QuickMeasureEntry
import com.hcjeong.forestix.data.QuickMeasurePlot
import com.hcjeong.forestix.sensors.LogRule
import com.hcjeong.forestix.sensors.VolumeConversion
import com.hcjeong.forestix.ui.screens.ForestixScaffold
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixSpace
import java.util.Locale
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.floor

// MARK: - Computation

object StandStockComputation {

    /// Half-open DBH class `[lowerBound, upperBound)` — mirrors Swift's
    /// `Range<Double>` property names.
    data class DbhClass(val lowerBound: Double, val upperBound: Double)

    data class Row(
        val speciesCode: String,
        val countByClass: Map<Int, Int>,      // class index → count
        val bfByClass: Map<Int, Double>,      // class index → BF
        val totalCount: Int,
        val totalBF: Double,
    )

    data class Table(
        val dbhClassesCm: List<DbhClass>,     // ordered low→high
        val rows: List<Row>,                  // one per species
        val totalsByClass: Map<Int, Int>,     // class → total count
        val bfTotalsByClass: Map<Int, Double>, // class → total BF
        val grandCount: Int,
        val grandBF: Double,
    )

    /// Builds the table from a Quick Measure plot's entries.
    /// `binWidthCm` defaults to 10 cm (≈ 4 in) — common cruise bin.
    fun build(
        entries: List<QuickMeasureEntry>,
        logRule: LogRule,
        binWidthCm: Double = 10.0,
    ): Table {
        // Group by tree number. Each tree bin contributes its DBH +
        // optional height + first non-empty species code.
        val byTree = entries.groupBy { it.treeNumber ?: -1 }

        data class TallyTree(val dbhCm: Double, val heightM: Double?, val species: String)

        val trees: List<TallyTree> = byTree.values.mapNotNull { group ->
            val dbh = group.firstOrNull { it.kind == MeasureKind.DBH }?.value
                ?: return@mapNotNull null
            val h = group.firstOrNull { it.kind == MeasureKind.HEIGHT }?.value
            val sp = group.firstOrNull { !(it.speciesCode ?: "").isEmpty() }
                ?.speciesCode ?: "—"
            TallyTree(dbhCm = dbh, heightM = h, species = sp)
        }
        if (trees.isEmpty()) {
            return Table(
                dbhClassesCm = emptyList(), rows = emptyList(),
                totalsByClass = emptyMap(), bfTotalsByClass = emptyMap(),
                grandCount = 0, grandBF = 0.0)
        }

        // Class breakpoints — start at the DBH-class containing the
        // smallest tree, end at the class containing the biggest.
        val minDBH = trees.minOf { it.dbhCm }
        val maxDBH = trees.maxOf { it.dbhCm }
        val firstClassLo = floor(minDBH / binWidthCm) * binWidthCm
        val lastClassHi = ceil((maxDBH + 0.001) / binWidthCm) * binWidthCm
        val classes = mutableListOf<DbhClass>()
        var lo = firstClassLo
        while (lo < lastClassHi) {
            classes.add(DbhClass(lo, lo + binWidthCm))
            lo += binWidthCm
        }

        fun classIndex(dbh: Double): Int {
            val i = floor((dbh - firstClassLo) / binWidthCm).toInt()
            return i.coerceIn(0, classes.size - 1)
        }

        // Aggregate per-species rows
        val bySpecies = trees.groupBy { it.species }
        val rows = mutableListOf<Row>()
        val totalsByClass = mutableMapOf<Int, Int>()
        val bfTotalsByClass = mutableMapOf<Int, Double>()
        var grandCount = 0
        var grandBF = 0.0

        for ((sp, group) in bySpecies) {
            val c = mutableMapOf<Int, Int>()
            val bf = mutableMapOf<Int, Double>()
            var rowCount = 0
            var rowBF = 0.0
            for (t in group) {
                val idx = classIndex(t.dbhCm)
                c[idx] = (c[idx] ?: 0) + 1
                rowCount += 1
                totalsByClass[idx] = (totalsByClass[idx] ?: 0) + 1
                val h = t.heightM
                val bfTree = if (h != null) {
                    VolumeConversion.boardFeet(dbhCm = t.dbhCm, totalHeightM = h, rule = logRule)
                } else {
                    null
                }
                if (bfTree != null) {
                    bf[idx] = (bf[idx] ?: 0.0) + bfTree
                    rowBF += bfTree
                    bfTotalsByClass[idx] = (bfTotalsByClass[idx] ?: 0.0) + bfTree
                    grandBF += bfTree
                }
            }
            grandCount += rowCount
            rows.add(Row(
                speciesCode = sp,
                countByClass = c, bfByClass = bf,
                totalCount = rowCount, totalBF = rowBF))
        }
        rows.sortByDescending { it.totalCount }

        return Table(
            dbhClassesCm = classes,
            rows = rows,
            totalsByClass = totalsByClass,
            bfTotalsByClass = bfTotalsByClass,
            grandCount = grandCount,
            grandBF = grandBF)
    }
}

// MARK: - Screen

/// Route-friendly wrapper: resolves the plot + entries from history.
/// Suggested route: "standStock?plot={plot}" with a string UUID argument
/// (null / missing → active default plot behavior falls back to all entries
/// with that plotID).
@Composable
fun StandStockReportScreen(nav: NavController, plotID: UUID?) {
    val env = LocalAppEnvironment.current
    val plots by env.history.plots.collectAsStateWithLifecycle()
    val allEntries by env.history.entries.collectAsStateWithLifecycle()
    val plot = plots.firstOrNull { it.id == plotID } ?: plots.firstOrNull { it.isDefault }
    if (plot == null) {
        ForestixScaffold(nav, title = "Stand & stock") { padding ->
            Text(
                "No plot found.",
                style = Forestix.type.caption, color = Forestix.colors.textSecondary,
                modifier = Modifier.padding(padding).padding(ForestixSpace.md))
        }
        return
    }
    val entries = remember(allEntries, plot.id) { env.history.entriesForPlot(plot.id) }
    StandStockReport(nav = nav, plot = plot, entries = entries)
}

/// Port of iOS `StandStockReport` — takes the plot + its entries directly.
@Composable
fun StandStockReport(
    nav: NavController,
    plot: QuickMeasurePlot,
    entries: List<QuickMeasureEntry>,
) {
    val env = LocalAppEnvironment.current
    val settings by env.settings.state.collectAsStateWithLifecycle()
    val colors = Forestix.colors
    val type = Forestix.type

    val table = remember(entries, settings.logRule) {
        StandStockComputation.build(entries = entries, logRule = settings.logRule)
    }

    ForestixScaffold(nav, title = "Stand & stock") { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ForestixSpace.md, vertical = ForestixSpace.md),
            verticalArrangement = Arrangement.spacedBy(ForestixSpace.md),
        ) {
            // Header
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "STAND & STOCK",
                    style = type.sectionHead.copy(letterSpacing = 1.5.sp),
                    color = colors.textTertiary)
                Text(plot.name, style = type.bodyBold, color = colors.textPrimary)
                Text(
                    "${entries.size} reading${if (entries.size == 1) "" else "s"} · ${settings.logRule.displayName}",
                    style = type.caption, color = colors.textSecondary)
            }

            if (table.rows.isEmpty()) {
                Text(
                    "No tallied trees yet. Add DBH (and optionally height) readings to populate this report.",
                    style = type.caption, color = colors.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = ForestixSpace.lg))
            } else {
                Column(
                    Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    ClassHeader(table, settings.unitSystem)
                    for (row in table.rows) {
                        SpeciesRow(row, table)
                    }
                    HorizontalDivider(Modifier.padding(vertical = 4.dp), color = colors.divider)
                    TotalsRow(table)
                }
                Text(
                    "DBH-class width: 10 cm. Heights blank = trees with no height reading; BF computed only when both DBH + height present.",
                    style = type.caption.copy(fontStyle = FontStyle.Italic),
                    color = colors.textTertiary)
            }
        }
    }
}

// MARK: - Row helpers

@Composable
private fun ClassHeader(t: StandStockComputation.Table, unitSystem: UnitSystem) {
    val colors = Forestix.colors
    val style = Forestix.type.sectionHead.copy(letterSpacing = 1.2.sp)
    Row(Modifier.padding(vertical = ForestixSpace.xs)) {
        Text("SPECIES", style = style, color = colors.textTertiary, modifier = Modifier.width(90.dp))
        for (range in t.dbhClassesCm) {
            Text(
                classLabel(range, unitSystem),
                style = style, color = colors.textTertiary,
                textAlign = TextAlign.End, modifier = Modifier.width(80.dp))
        }
        Text(
            "TOTAL", style = style, color = colors.textTertiary,
            textAlign = TextAlign.End, modifier = Modifier.width(96.dp))
    }
}

private fun classLabel(range: StandStockComputation.DbhClass, unitSystem: UnitSystem): String {
    return if (unitSystem == UnitSystem.IMPERIAL) {
        val lo = range.lowerBound / 2.54
        val hi = range.upperBound / 2.54
        String.format(Locale.US, "%.0f-%.0f in", lo, hi)
    } else {
        String.format(Locale.US, "%.0f-%.0f cm", range.lowerBound, range.upperBound)
    }
}

@Composable
private fun SpeciesRow(row: StandStockComputation.Row, table: StandStockComputation.Table) {
    val colors = Forestix.colors
    val type = Forestix.type
    Row(Modifier.padding(vertical = 4.dp)) {
        Text(row.speciesCode, style = type.dataSmall, color = colors.textPrimary, modifier = Modifier.width(90.dp))
        for (i in table.dbhClassesCm.indices) {
            Text(
                cellLabel(count = row.countByClass[i] ?: 0, bf = row.bfByClass[i] ?: 0.0),
                style = type.dataSmall, color = colors.textSecondary,
                textAlign = TextAlign.End, modifier = Modifier.width(80.dp))
        }
        Text(
            totalLabel(count = row.totalCount, bf = row.totalBF),
            style = type.data, color = colors.textPrimary,
            textAlign = TextAlign.End, modifier = Modifier.width(96.dp))
    }
}

@Composable
private fun TotalsRow(t: StandStockComputation.Table) {
    val colors = Forestix.colors
    val type = Forestix.type
    Row(
        Modifier
            .background(colors.surface)
            .padding(vertical = 4.dp),
    ) {
        Text("TOTAL", style = type.bodyBold, color = colors.textPrimary, modifier = Modifier.width(90.dp))
        for (i in t.dbhClassesCm.indices) {
            Text(
                cellLabel(count = t.totalsByClass[i] ?: 0, bf = t.bfTotalsByClass[i] ?: 0.0),
                style = type.dataSmall, color = colors.textPrimary,
                textAlign = TextAlign.End, modifier = Modifier.width(80.dp))
        }
        Text(
            totalLabel(count = t.grandCount, bf = t.grandBF),
            style = type.data, color = colors.textPrimary,
            textAlign = TextAlign.End, modifier = Modifier.width(96.dp))
    }
}

private fun cellLabel(count: Int, bf: Double): String {
    if (count == 0) return "—"
    if (bf <= 0.0) return "$count"
    return "$count · ${bf.toInt()} BF"
}

private fun totalLabel(count: Int, bf: Double): String {
    if (bf <= 0.0) return "$count"
    return "$count · ${bf.toInt()} BF"
}
