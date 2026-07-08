// Shared list chrome for the project flow cluster — Compose stand-ins for
// the iOS `List { Section(header:footer:) }` styling used across
// ProjectDashboardScreen / CruiseDesignScreen / PreFieldChecklistScreen,
// and (post parity pass) SettingsScreen / ExportScreen.

package com.hcjeong.forestix.ui.screens.project

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Surface
import com.hcjeong.forestix.ui.clickableNoRipple
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixRadius
import com.hcjeong.forestix.ui.theme.ForestixSpace
import java.util.Locale

/// iOS grouped-list section: ALL-CAPS sectionHead header (iOS grouped Form
/// headers auto-uppercase), rounded surface card of rows, optional caption
/// footer. `contentPadding`/`rowSpacing` let full-bleed row lists (swipe-to-
/// delete rows with hairline dividers) opt out of the default insets.
@Composable
fun FormSection(
    header: String? = null,
    footer: String? = null,
    contentPadding: PaddingValues = PaddingValues(ForestixSpace.md),
    rowSpacing: Dp = ForestixSpace.sm,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = Forestix.colors
    val type = Forestix.type
    Column(Modifier.fillMaxWidth()) {
        if (header != null) {
            Text(
                header.uppercase(Locale.US),
                style = type.sectionHead,
                color = colors.textTertiary,
                modifier = Modifier.padding(
                    start = ForestixSpace.xs, bottom = ForestixSpace.xxs),
            )
        }
        Surface(
            color = colors.surface,
            shape = ForestixRadius.card,
            modifier = Modifier
                .fillMaxWidth()
                .clip(ForestixRadius.card)
                .border(0.5.dp, colors.divider, ForestixRadius.card),
        ) {
            Column(
                Modifier.padding(contentPadding),
                verticalArrangement = Arrangement.spacedBy(rowSpacing),
            ) {
                content()
            }
        }
        if (footer != null) {
            Text(
                footer,
                style = type.caption,
                color = colors.textSecondary,
                modifier = Modifier.padding(
                    start = ForestixSpace.xs, top = ForestixSpace.xxs),
            )
        }
    }
}

/// iOS grouped-list row separator — 0.5 hairline in the divider token,
/// optionally inset to the row content leading edge like insetGrouped.
@Composable
fun FormDivider(startIndent: Dp = 0.dp) {
    HorizontalDivider(
        color = Forestix.colors.divider,
        thickness = 0.5.dp,
        modifier = Modifier.padding(start = startIndent),
    )
}

/// iOS `LabeledContent(title, value:)` — label left, value right.
@Composable
fun LabeledContentRow(title: String, value: String) {
    val colors = Forestix.colors
    val type = Forestix.type
    Row(Modifier.fillMaxWidth()) {
        Text(title, style = type.body, color = colors.textPrimary, modifier = Modifier.weight(1f))
        Text(value, style = type.body, color = colors.textSecondary)
    }
}

/// iOS default Form `Picker` — a row showing the selected value with an
/// up/down affordance that opens a menu (Region / Log rule / Scheme).
@Composable
fun MenuPickerRow(
    title: String,
    value: String,
    options: List<String>,
    onSelect: (Int) -> Unit,
) {
    val colors = Forestix.colors
    val type = Forestix.type
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickableNoRipple { expanded = true },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = type.body, color = colors.textPrimary, modifier = Modifier.weight(1f))
            Text(value, style = type.body, color = colors.textSecondary)
            Spacer(Modifier.width(ForestixSpace.xxs))
            Icon(
                Icons.Filled.UnfoldMore, contentDescription = null,
                tint = colors.textTertiary, modifier = Modifier.size(16.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEachIndexed { index, label ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        expanded = false
                        onSelect(index)
                    },
                )
            }
        }
    }
}
