// Port of iOS Screens/TreeIdentitySheet.swift.
// Pre-scan tree identity picker.
//
// Cruiser feedback: "DBH랑 Height를 따로 재면 같은 나무인지 다른
// 나무인지 모르잖아? 재기 전에 미리 tree를 추가하는지, 기존에 있는
// tree에서 정보를 업데이트 하는지 선택하게 해야 할 듯".
//
// This sheet appears between the cruiser tapping a measurement card
// (Diameter / Height) on the Quick Measure home and the actual scan
// opening. It offers three choices:
//
//   1. Continue last tree (#N) — one-tap quick path, the most common
//      case when the cruiser is doing DBH then Height back-to-back
//      on the same stem.
//   2. New tree (#N+1) — auto-incremented, also one-tap.
//   3. Pick from existing — opens an inline picker listing every
//      distinct tree number with the readings already attached.
//
// The chosen tree number is passed back via `onPick` and ends up on
// the QuickMeasureEntry that the scan screen writes into history.
//
// Host inside a ModalBottomSheet (see ReconCruiseScreen for the pattern);
// the caller dismisses the sheet from both callbacks.

package com.hcjeong.forestix.ui.screens.tree

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.hcjeong.forestix.data.QuickMeasureHistory
import com.hcjeong.forestix.ui.clickableNoRipple
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixRadius
import com.hcjeong.forestix.ui.theme.ForestixSpace

/// What the sheet is gating: a Diameter scan, a Height scan, or
/// (later) something else. Shown in the header copy so the
/// cruiser knows what they're about to start.
enum class ScanKind {
    DIAMETER,
    HEIGHT;

    val title: String
        get() = when (this) {
            DIAMETER -> "Diameter scan"
            HEIGHT -> "Height scan"
        }

    val subtitle: String
        get() = when (this) {
            DIAMETER -> "Pick which tree this reading is for."
            HEIGHT -> "Pick which tree this reading is for."
        }
}

@Composable
fun TreeIdentitySheet(
    scanKind: ScanKind,
    history: QuickMeasureHistory,
    onPick: (Int) -> Unit,
    onCancel: () -> Unit,
) {
    val colors = Forestix.colors
    val type = Forestix.type
    val entries by history.entries.collectAsState()
    var showAllTrees by remember { mutableStateOf(false) }

    /// Most recently used tree number across the log (iOS
    /// `history.lastTreeNumber`; entries are newest-first).
    val lastTreeNumber = entries.firstOrNull { it.treeNumber != null }?.treeNumber
    val distinctTreeNumbers = history.distinctTreeNumbers

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = ForestixSpace.md)
            .padding(top = ForestixSpace.sm, bottom = ForestixSpace.xl),
        verticalArrangement = Arrangement.spacedBy(ForestixSpace.md),
    ) {
        // Title bar (iOS inline nav title + Cancel toolbar button).
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(scanKind.title, style = type.bodyBold, color = colors.textPrimary)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onCancel) { Text("Cancel") }
        }

        // MARK: Header
        Text(
            scanKind.subtitle,
            style = type.body,
            color = colors.textSecondary,
        )

        // MARK: Primary choices
        //
        // Two-row stack: continue last tree, or start a new one. These
        // are the two paths a cruiser hits 95 % of the time. A third
        // "Pick from existing" row expands a list when tapped.
        Column(verticalArrangement = Arrangement.spacedBy(ForestixSpace.sm)) {
            lastTreeNumber?.let { last ->
                ChoiceRow(
                    title = "Continue tree #$last",
                    subtitle = history.summary(last) ?: "Add another reading to this tree",
                    icon = Icons.Filled.Autorenew,
                ) { onPick(last) }
            }
            ChoiceRow(
                title = "New tree #${history.suggestedNextTreeNumber}",
                subtitle = "Start a fresh tree on this reading",
                icon = Icons.Filled.AddCircleOutline,
            ) { onPick(history.suggestedNextTreeNumber) }
            if (distinctTreeNumbers.isNotEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(ForestixRadius.card)
                        .border(0.75.dp, colors.primary.copy(alpha = 0.4f), ForestixRadius.card)
                        .clickableNoRipple { showAllTrees = !showAllTrees }
                        .padding(horizontal = ForestixSpace.md, vertical = ForestixSpace.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (showAllTrees) "Hide all trees" else "Pick from existing trees",
                        style = type.bodyBold,
                        color = colors.primary,
                    )
                    Spacer(Modifier.weight(1f))
                    Icon(
                        if (showAllTrees) Icons.Filled.KeyboardArrowUp
                        else Icons.Filled.KeyboardArrowDown,
                        contentDescription = null,
                        tint = colors.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        // MARK: All trees expanded list
        if (showAllTrees) {
            Column {
                Text(
                    "ALL TREES",
                    style = type.sectionHead,
                    color = colors.textTertiary,
                    modifier = Modifier.padding(bottom = ForestixSpace.xs, start = ForestixSpace.xs),
                )
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(ForestixRadius.card)
                        .background(colors.surface),
                ) {
                    distinctTreeNumbers.forEachIndexed { index, n ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickableNoRipple { onPick(n) }
                                .padding(horizontal = ForestixSpace.md, vertical = ForestixSpace.sm),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(ForestixSpace.sm),
                        ) {
                            Text(
                                "#$n",
                                style = type.dataSmall,
                                color = colors.textPrimary,
                                modifier = Modifier.width(44.dp),
                            )
                            Text(
                                history.summary(n) ?: "—",
                                style = type.caption,
                                color = colors.textSecondary,
                            )
                            Spacer(Modifier.weight(1f))
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = colors.textTertiary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        if (index != distinctTreeNumbers.lastIndex) {
                            HorizontalDivider(
                                color = colors.divider,
                                thickness = 0.5.dp,
                                modifier = Modifier.padding(
                                    start = ForestixSpace.md + 44.dp + ForestixSpace.sm),
                            )
                        }
                    }
                }
            }
        }
    }
}

// MARK: - Choice row component

@Composable
private fun ChoiceRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    action: () -> Unit,
) {
    val colors = Forestix.colors
    val type = Forestix.type
    Row(
        Modifier
            .fillMaxWidth()
            .clip(ForestixRadius.card)
            .background(colors.surface)
            .border(0.5.dp, colors.divider, ForestixRadius.card)
            .clickableNoRipple(action)
            .padding(ForestixSpace.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ForestixSpace.md),
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(ForestixRadius.control)
                .background(colors.primary.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = colors.primary, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = type.bodyBold, color = colors.textPrimary)
            Text(subtitle, style = type.caption, color = colors.textSecondary, maxLines = 2)
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = colors.textTertiary,
            modifier = Modifier.size(16.dp),
        )
    }
}
