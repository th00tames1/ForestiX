// Shared list chrome for the project flow cluster — Compose stand-ins for
// the iOS `List { Section(header:footer:) }` styling used across
// ProjectDashboardScreen / CruiseDesignScreen / PreFieldChecklistScreen.

package com.hcjeong.forestix.ui.screens.project

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Surface
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixRadius
import com.hcjeong.forestix.ui.theme.ForestixSpace

/// iOS grouped-list section: uppercase-ish header, rounded card of rows,
/// optional caption footer.
@Composable
fun FormSection(
    header: String? = null,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = Forestix.colors
    val type = Forestix.type
    Column(Modifier.fillMaxWidth()) {
        if (header != null) {
            Text(
                header,
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
                Modifier.padding(ForestixSpace.md),
                verticalArrangement = Arrangement.spacedBy(ForestixSpace.sm),
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
