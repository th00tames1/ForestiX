// Recon Cruise — port of iOS Screens/ReconCruiseScreen.swift.
// Fast plot-by-plot BA tally for cruise design.
//
// Workflow (single screen):
//   1. Pick BAF (10 / 20 / 40 ft²/ac).
//   2. Stand at plot centre.
//   3. Tap "In-tree" for each prism-in tree; tap the counter to undo.
//   4. Tap "Save plot" → BA = count × BAF lands on the recon log.
//   5. Walk to the next plot, repeat.
//   6. Summary → CV across plots, target sample size for production cruise.

package com.hcjeong.forestix.ui.screens.stand

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.hcjeong.forestix.sensors.SamplingStats
import com.hcjeong.forestix.ui.clickableNoRipple
import com.hcjeong.forestix.ui.screens.ForestixScaffold
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixRadius
import com.hcjeong.forestix.ui.theme.ForestixSpace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID
import kotlin.math.max

// MARK: - Recon session model (port of iOS ReconSession)

class ReconSession {

    data class CompletedPlot(
        val id: UUID = UUID.randomUUID(),
        val number: Int,
        val count: Int,
        val baf: Double,
    ) {
        val baFt2PerAcre: Int get() = (count.toDouble() * baf).toInt()
    }

    val baf = MutableStateFlow(20.0)
    private val _currentCount = MutableStateFlow(0)
    val currentCount: StateFlow<Int> = _currentCount.asStateFlow()
    private val _completedPlots = MutableStateFlow<List<CompletedPlot>>(emptyList())
    val completedPlots: StateFlow<List<CompletedPlot>> = _completedPlots.asStateFlow()

    fun tallyOne() { _currentCount.value += 1 }
    fun undoLast() { _currentCount.value = max(0, _currentCount.value - 1) }

    fun completePlot() {
        if (_currentCount.value <= 0) return
        val p = CompletedPlot(
            number = _completedPlots.value.size + 1,
            count = _currentCount.value, baf = baf.value)
        _completedPlots.value = _completedPlots.value + p
        _currentCount.value = 0
    }

    fun reset() {
        _currentCount.value = 0
        _completedPlots.value = emptyList()
    }
}

// MARK: - Screen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReconCruiseScreen(nav: NavController) {
    val colors = Forestix.colors
    val type = Forestix.type
    val session = remember { ReconSession() }
    val baf by session.baf.collectAsStateWithLifecycle()
    val currentCount by session.currentCount.collectAsStateWithLifecycle()
    val completedPlots by session.completedPlots.collectAsStateWithLifecycle()
    var presentingSummary by remember { mutableStateOf(false) }

    ForestixScaffold(nav, title = "Recon") { padding ->
        Column(Modifier.padding(padding)) {
            // Top strip — BAF picker + running plot number.
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(ForestixSpace.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ForestixSpace.sm),
            ) {
                BafPicker(selected = baf, onSelect = { session.baf.value = it })
                Spacer(Modifier.weight(1f))
                Text(
                    "Plot ${completedPlots.size + 1}",
                    style = type.dataSmall, color = colors.textSecondary)
            }
            HorizontalDivider(color = colors.divider)

            // Main body (current plot tally)
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = ForestixSpace.md),
                verticalArrangement = Arrangement.spacedBy(ForestixSpace.lg),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                TallyCounter(
                    currentCount = currentCount,
                    baf = baf,
                    onTally = { session.tallyOne() },
                    onUndo = { if (currentCount > 0) session.undoLast() },
                    onSave = { session.completePlot() })
                Text(
                    "Tap each in-tree once. Tap the counter centre to undo the last tap.",
                    style = type.caption, color = colors.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = ForestixSpace.lg))
                if (completedPlots.isNotEmpty()) {
                    HorizontalDivider(color = colors.divider)
                    CompletedPlotList(completedPlots)
                }
            }
            HorizontalDivider(color = colors.divider)

            // Bottom action bar
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ForestixSpace.md, vertical = ForestixSpace.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { session.reset() }) {
                    Text("Reset recon", style = type.caption, color = colors.confidenceBad)
                }
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = { presentingSummary = true },
                    enabled = completedPlots.size >= 2,
                ) {
                    val summaryTint =
                        if (completedPlots.size >= 2) colors.primary else colors.textTertiary
                    // iOS Label("Summary", systemImage: "chart.bar.doc.horizontal").
                    Icon(
                        Icons.Filled.Assessment, contentDescription = null,
                        tint = summaryTint, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Summary", style = type.bodyBold, color = summaryTint)
                }
            }
        }
    }

    if (presentingSummary) {
        ModalBottomSheet(
            onDismissRequest = { presentingSummary = false },
            containerColor = colors.canvas,
        ) {
            ReconSummarySheet(
                completedPlots = completedPlots,
                onDone = { presentingSummary = false })
        }
    }
}

// MARK: - BAF picker (M3 segmented, 3 most common values — iOS
// `.pickerStyle(.segmented)` at width 220; selected label rides the
// primary fill in primaryInk, never hardcoded white)

@Composable
private fun BafPicker(selected: Double, onSelect: (Double) -> Unit) {
    val colors = Forestix.colors
    val type = Forestix.type
    val options = listOf(10.0, 20.0, 40.0)
    SingleChoiceSegmentedButtonRow(Modifier.width(220.dp)) {
        options.forEachIndexed { index, v ->
            SegmentedButton(
                selected = v == selected,
                onClick = { onSelect(v) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = colors.primary,
                    activeContentColor = colors.primaryInk,
                    inactiveContentColor = colors.textPrimary,
                ),
            ) { Text("${v.toInt()} ft²/ac", style = type.caption, maxLines = 1) }
        }
    }
}

// MARK: - Tally counter

@Composable
private fun TallyCounter(
    currentCount: Int,
    baf: Double,
    onTally: () -> Unit,
    onUndo: () -> Unit,
    onSave: () -> Unit,
) {
    val colors = Forestix.colors
    val type = Forestix.type
    Column(
        verticalArrangement = Arrangement.spacedBy(ForestixSpace.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "$currentCount",
            style = TextStyle(fontSize = 84.sp, fontWeight = FontWeight.SemiBold),
            color = colors.primary,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .widthIn(min = 160.dp)
                .clickableNoRipple(onUndo),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(ForestixSpace.md)) {
            // iOS Label("In-tree", systemImage: "plus.circle.fill") capsule.
            Row(
                Modifier
                    .clip(CircleShape)
                    .background(colors.primary)
                    .clickableNoRipple(onTally)
                    .padding(horizontal = ForestixSpace.lg, vertical = ForestixSpace.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    Icons.Filled.AddCircle, contentDescription = null,
                    tint = Color.White, modifier = Modifier.size(18.dp))
                Text("In-tree", style = type.bodyBold, color = Color.White)
            }
            val saveEnabled = currentCount > 0
            // iOS Label("Save plot", systemImage: "checkmark.circle") capsule.
            Row(
                Modifier
                    .alpha(if (saveEnabled) 1f else 0.4f)
                    .clip(CircleShape)
                    .border(1.5.dp, colors.primary, CircleShape)
                    .clickableNoRipple { if (saveEnabled) onSave() }
                    .padding(horizontal = ForestixSpace.lg, vertical = ForestixSpace.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    Icons.Filled.CheckCircle, contentDescription = null,
                    tint = colors.primary, modifier = Modifier.size(18.dp))
                Text("Save plot", style = type.bodyBold, color = colors.primary)
            }
        }
        Text(
            "Basal area = $currentCount × ${baf.toInt()} = ${(currentCount.toDouble() * baf).toInt()} ft²/ac",
            style = type.dataSmall, color = colors.textTertiary)
    }
}

// MARK: - Completed plot list

@Composable
private fun CompletedPlotList(plots: List<ReconSession.CompletedPlot>) {
    val colors = Forestix.colors
    val type = Forestix.type
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(ForestixSpace.xs),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            "COMPLETED PLOTS",
            style = type.sectionHead.copy(letterSpacing = 1.2.sp),
            color = colors.textTertiary,
            modifier = Modifier.padding(horizontal = ForestixSpace.md))
        for (p in plots) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ForestixSpace.md, vertical = ForestixSpace.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Plot ${p.number}", style = type.bodyBold, color = colors.textPrimary)
                Spacer(Modifier.weight(1f))
                Text("${p.baFt2PerAcre} ft²/ac", style = type.data, color = colors.textPrimary)
                Spacer(Modifier.padding(2.dp))
                Text("(${p.count} trees)", style = type.dataSmall, color = colors.textTertiary)
            }
        }
    }
}

// MARK: - Summary sheet (port of iOS ReconSummarySheet)

@Composable
private fun ReconSummarySheet(
    completedPlots: List<ReconSession.CompletedPlot>,
    onDone: () -> Unit,
) {
    val colors = Forestix.colors
    val type = Forestix.type
    val bas = completedPlots.map { it.baFt2PerAcre.toDouble() }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(ForestixSpace.md),
        verticalArrangement = Arrangement.spacedBy(ForestixSpace.md),
    ) {
        // Nav-bar chrome inside the sheet — centered inline title +
        // trailing Done (iOS `.navigationTitle` + confirmationAction).
        Box(Modifier.fillMaxWidth()) {
            Text(
                "Recon summary",
                style = type.bodyBold.copy(fontSize = 17.sp),
                color = colors.textPrimary,
                modifier = Modifier.align(Alignment.Center))
            TextButton(onClick = onDone, modifier = Modifier.align(Alignment.CenterEnd)) {
                Text("Done", style = type.bodyBold, color = colors.primary)
            }
        }

        // Overview
        Column(verticalArrangement = Arrangement.spacedBy(ForestixSpace.xs)) {
            Text(
                "OVERVIEW",
                style = type.sectionHead.copy(letterSpacing = 1.5.sp),
                color = colors.textTertiary)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(ForestixRadius.card)
                    .background(colors.surface)
                    .padding(ForestixSpace.md),
            ) {
                SummaryStat("PLOTS", "${completedPlots.size}", Modifier.weight(1f))
                SummaryStat("MEAN BASAL",
                    String.format(Locale.US, "%.0f ft²/ac", SamplingStats.mean(bas)),
                    Modifier.weight(1f))
                SummaryStat("VARIABILITY",
                    String.format(Locale.US, "%.0f%%", SamplingStats.cv(bas)),
                    Modifier.weight(1f))
                SummaryStat("STD ERROR",
                    String.format(Locale.US, "%.1f%%", SamplingStats.sePct(bas)),
                    Modifier.weight(1f))
            }
        }

        // Production cruise sizing
        val cv = SamplingStats.cv(bas)
        val n10 = SamplingStats.requiredSampleSize(targetSEPct = 10.0, cv = cv)
        val n5 = SamplingStats.requiredSampleSize(targetSEPct = 5.0, cv = cv)
        Column(verticalArrangement = Arrangement.spacedBy(ForestixSpace.xs)) {
            Text(
                "PRODUCTION CRUISE SIZING",
                style = type.sectionHead.copy(letterSpacing = 1.5.sp),
                color = colors.textTertiary)
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(ForestixRadius.card)
                    .background(colors.surface)
                    .padding(ForestixSpace.md),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SizingRow("Target ±10 % standard error", "$n10 plots")
                SizingRow("Target ±5 % standard error", "$n5 plots")
            }
            Text(
                "Based on the variability seen in your recon plots. Higher variability → more plots needed for the same precision.",
                style = type.caption.copy(fontStyle = FontStyle.Italic),
                color = colors.textTertiary)
        }

        // Recon plots
        Column(verticalArrangement = Arrangement.spacedBy(ForestixSpace.xs)) {
            Text(
                "RECON PLOTS",
                style = type.sectionHead.copy(letterSpacing = 1.5.sp),
                color = colors.textTertiary)
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(ForestixRadius.card)
                    .background(colors.surface),
            ) {
                completedPlots.forEachIndexed { i, p ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(ForestixSpace.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Plot ${p.number}", style = type.body, color = colors.textPrimary)
                        Spacer(Modifier.weight(1f))
                        Text("${p.baFt2PerAcre} ft²/ac", style = type.data, color = colors.textPrimary)
                    }
                    if (i != completedPlots.lastIndex) {
                        HorizontalDivider(color = colors.divider)
                    }
                }
            }
        }
        Spacer(Modifier.padding(ForestixSpace.md))
    }
}

@Composable
private fun SummaryStat(label: String, value: String, modifier: Modifier = Modifier) {
    val colors = Forestix.colors
    val type = Forestix.type
    Column(
        modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            value, style = type.dataLarge, color = colors.textPrimary,
            maxLines = 1, textAlign = TextAlign.Center)
        Text(
            label, style = type.sectionHead.copy(letterSpacing = 1.2.sp),
            color = colors.textTertiary, maxLines = 1, textAlign = TextAlign.Center)
    }
}

@Composable
private fun SizingRow(k: String, v: String) {
    val colors = Forestix.colors
    val type = Forestix.type
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(k, style = type.body, color = colors.textPrimary)
        Spacer(Modifier.weight(1f))
        Text(v, style = type.dataLarge, color = colors.primary)
    }
}
