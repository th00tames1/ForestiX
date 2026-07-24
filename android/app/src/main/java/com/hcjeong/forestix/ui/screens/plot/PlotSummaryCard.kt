// Port of iOS Screens/PlotSummaryCard.swift.
// In-app plot summary card — closes the "is this plot reasonable?"
// loop in the field. An instant post-plot summary with
// standard stand stats. Cruiser doesn't
// need a desktop tool to know whether to re-cruise.
//
// Renders:
//   • Top-line stats — TREES, BASAL/AC, TREES/AC, MEAN DBH
//   • Stocking & Density gauge (Phase 1.2 component)
//   • Species mix breakdown
//
// All math is pure functions on a list of `QuickMeasureEntry`. No
// dependencies on plot acreage for now (variable-radius / unscaled
// presentation); Phase 4 wires this to per-plot acreage when the
// stand-and-stock report needs absolute-unit numbers.

package com.hcjeong.forestix.ui.screens.plot

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hcjeong.forestix.common.AreaUnit
import com.hcjeong.forestix.common.MeasurementFormatter
import com.hcjeong.forestix.common.RegionalSpecies
import com.hcjeong.forestix.common.UnitSystem
import com.hcjeong.forestix.data.MeasureKind
import com.hcjeong.forestix.data.QuickMeasureEntry
import com.hcjeong.forestix.data.QuickMeasurePlot
import com.hcjeong.forestix.sensors.LogRule
import com.hcjeong.forestix.sensors.VolumeConversion
import com.hcjeong.forestix.ui.screens.stand.StockingGauge
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixRadius
import com.hcjeong.forestix.ui.theme.ForestixSpace
import java.util.Locale
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

@Composable
fun PlotSummaryCard(
    plot: QuickMeasurePlot,
    entries: List<QuickMeasureEntry>,
    unitSystem: UnitSystem,
    logRule: LogRule,
    areaUnit: AreaUnit = AreaUnit.ACRE,
) {
    val colors = Forestix.colors
    val type = Forestix.type
    val stats = remember(plot, entries, logRule, unitSystem) {
        computeStats(plot, entries, logRule, unitSystem)
    }
    val densityFactor = areaUnit.perAcreDensityFactor

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
            Text(
                "PLOT SUMMARY",
                style = type.sectionHead.copy(letterSpacing = 1.5.sp),
                color = colors.textTertiary)
            Text(plot.name, style = type.bodyBold, color = colors.textPrimary)
            val subtitle = plotSubtitle(plot, areaUnit)
            if (subtitle.isNotEmpty()) {
                Text(subtitle, style = type.caption, color = colors.textSecondary)
            }
        }

        HorizontalDivider(color = colors.divider, thickness = 0.5.dp)

        // MARK: - Top stats
        Row(
            Modifier.fillMaxWidth().height(48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatsCell("TREES", stats?.distinctTrees?.toString() ?: "—",
                Modifier.weight(1f))
            CellDivider()
            StatsCell("BASAL/${areaUnit.abbreviation.uppercase(Locale.US)}",
                stats?.let { String.format(Locale.US, "%.0f", it.baPerAcre * densityFactor) } ?: "—",
                Modifier.weight(1f))
            CellDivider()
            StatsCell("TREES/${areaUnit.abbreviation.uppercase(Locale.US)}",
                stats?.let { String.format(Locale.US, "%.0f", it.tpa * densityFactor) } ?: "—",
                Modifier.weight(1f))
            CellDivider()
            StatsCell("MEAN DBH",
                stats?.qmd?.let { MeasurementFormatter.diameter(it, unitSystem) } ?: "—",
                Modifier.weight(1f))
        }

        if (stats != null && stats.distinctTrees >= 1) {
            HorizontalDivider(color = colors.divider, thickness = 0.5.dp)

            // MARK: - Stocking gauge
            //
            // Relative density via Reineke SDI / Max SDI ratio. With no
            // species-specific Max SDI table yet, use a generic Max SDI of
            // 717 (USFS PNW conifer mid-range) for the bar position.
            // Refined per-species in Phase 5.
            val maxSDI = 717.0
            val relDensityPct = ((stats.sdi / maxSDI) * 100.0).coerceIn(0.0, 100.0)
            StockingGauge(
                relativeDensityPct = relDensityPct,
                regimeLabel = regimeLabel(relDensityPct))

            HorizontalDivider(color = colors.divider, thickness = 0.5.dp)

            // MARK: - Species mix
            if (stats.speciesMix.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(ForestixSpace.xs)) {
                    Text(
                        "SPECIES MIX",
                        style = type.sectionHead.copy(letterSpacing = 1.5.sp),
                        color = colors.textTertiary)
                    stats.speciesMix.forEach { row ->
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
                                overflow = TextOverflow.Clip)
                        }
                    }
                }
            }
        }
    }
}

// MARK: - Cells

@Composable
private fun StatsCell(label: String, value: String, modifier: Modifier = Modifier) {
    val colors = Forestix.colors
    val type = Forestix.type
    Column(
        modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            value,
            style = type.dataLarge,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis)
        Text(
            label,
            style = type.sectionHead.copy(letterSpacing = 1.2.sp),
            color = colors.textTertiary,
            maxLines = 1)
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

// MARK: - Header subtitle

private fun plotSubtitle(plot: QuickMeasurePlot, areaUnit: AreaUnit): String {
    val parts = mutableListOf<String>()
    if (plot.unitName.isNotEmpty()) parts.add(plot.unitName)
    plot.acres?.let {
        parts.add(String.format(Locale.US, "%.2f %s",
            areaUnit.fromAcres(it.toDouble()), areaUnit.abbreviation))
    }
    return parts.joinToString(" · ")
}

private fun regimeLabel(pct: Double): String = when {
    pct < 25 -> "Understocked"
    pct < 35 -> "Low stocking"
    pct < 60 -> "Adequately stocked"
    else -> "Over-dense"
}

// MARK: - Stats compute

private data class SpeciesShare(val code: String, val share: Double)

private data class Stats(
    val distinctTrees: Int,
    val tpa: Double,
    val baPerAcre: Double,
    val qmd: Double?,
    val meanHeightM: Double?,
    val bfPerAcre: Double?,
    val sdi: Double,
    val speciesMix: List<SpeciesShare>,
)

private fun computeStats(
    plot: QuickMeasurePlot,
    entries: List<QuickMeasureEntry>,
    logRule: LogRule,
    unitSystem: UnitSystem,
): Stats? {
    if (entries.isEmpty()) return null

    // Group entries by tree number; each tree contributes the
    // first DBH it has and the first Height it has.
    data class TreeAgg(val dbhCm: Double?, val hM: Double?, val species: String)
    val byTree = entries.groupBy { it.treeNumber ?: -1 }
    val trees = byTree.map { (_, group) ->
        val dbh = group.firstOrNull { it.kind == MeasureKind.DBH }?.value
        val h = group.firstOrNull { it.kind == MeasureKind.HEIGHT }?.value
        val sp = group.firstOrNull { !it.speciesCode.isNullOrEmpty() }?.speciesCode ?: ""
        TreeAgg(dbhCm = dbh, hM = h, species = sp)
    }

    val dbhTrees = trees.mapNotNull { it.dbhCm }
    if (dbhTrees.isEmpty()) return null

    // BA per tree, in the cruiser's unit: ft² (imperial, the classic
    // 0.005454·dbh_in² rule) or m² (metric, π/4·dbh_m²). Previously always
    // ft², so a metric cruiser saw ft² under a "BASAL/HA" label — the /ha
    // denominator was localized but the numerator was not. With no plot-acres
    // tied to these readings yet, "per acre" means "per tree-bin" — a relative
    // readout, refined in Phase 4 with real acreage.
    val metric = unitSystem == UnitSystem.METRIC
    val baPerTree = dbhTrees.map { cm ->
        if (metric) {
            val m = cm / 100.0
            (Math.PI / 4.0) * m * m
        } else {
            val inches = cm / 2.54
            0.005454 * inches * inches
        }
    }
    val acres = max(plot.acres ?: 0.1, 0.05)   // sane fallback
    val baPerAcre = baPerTree.sum() / acres
    val tpa = dbhTrees.size.toDouble() / acres
    // QMD in cm (display layer converts to inches if needed).
    val qmdSqCm = dbhTrees.sumOf { it * it } / dbhTrees.size.toDouble()
    val qmd = sqrt(qmdSqCm)

    val heights = trees.mapNotNull { it.hM }
    val meanH = if (heights.isEmpty()) null else heights.sum() / heights.size.toDouble()

    // Optional BF/ac when DBH+H+rule available.
    var bfPerAcre: Double? = null
    var totalBF = 0.0
    var bfCount = 0
    for (t in trees) {
        val dbh = t.dbhCm ?: continue
        val h = t.hM ?: continue
        val bf = VolumeConversion.boardFeet(dbhCm = dbh, totalHeightM = h, rule = logRule)
        if (bf != null) {
            totalBF += bf
            bfCount += 1
        }
    }
    if (bfCount > 0) {
        bfPerAcre = totalBF / acres
    }

    // Reineke SDI = TPA × (QMD_in / 10)^1.605
    val qmdIn = qmd / 2.54
    val sdi = tpa * (qmdIn / 10.0).pow(1.605)

    // Species mix
    val totalTrees = trees.size
    val mix = trees.groupBy { it.species }
        .map { (code, group) -> SpeciesShare(code, group.size.toDouble() / totalTrees) }
        .sortedByDescending { it.share }

    return Stats(
        distinctTrees = dbhTrees.size,
        tpa = tpa,
        baPerAcre = baPerAcre,
        qmd = qmd,
        meanHeightM = meanH,
        bfPerAcre = bfPerAcre,
        sdi = sdi,
        speciesMix = mix)
}
