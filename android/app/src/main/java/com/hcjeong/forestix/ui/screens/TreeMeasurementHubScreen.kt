// Tree Measurement hub — port of iOS TreeMeasurementHubScreen.
// 2-column grid of four tool tiles, then a Field Log row. Tiles route to
// the AR measurement screens. Layout rhythm mirrors the iOS VStack:
// caption → grid → field-log all 16 apart, 12 inside the grid.

package com.hcjeong.forestix.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CenterFocusWeak
import androidx.compose.material.icons.filled.Height
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.hcjeong.forestix.LocalAppEnvironment
import com.hcjeong.forestix.ui.Routes
import com.hcjeong.forestix.ui.clickableNoRipple
import com.hcjeong.forestix.ui.softDropShadow
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixRadius
import com.hcjeong.forestix.ui.theme.ForestixSpace

private data class Tool(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val route: String,
    /// Icon rotation in degrees — the Distance tile turns the vertical
    /// Height glyph into iOS's single ↔ double-headed arrow.
    val iconRotation: Float = 0f,
)

@Composable
fun TreeMeasurementHubScreen(nav: NavController) {
    val colors = Forestix.colors
    val type = Forestix.type
    val env = LocalAppEnvironment.current
    val entries by env.history.entries.collectAsStateWithLifecycle()

    val tools = listOf(
        Tool("Tree Diameter", "DBH via LiDAR scan", Icons.Filled.Straighten, Routes.DBH),
        Tool("Tree Height", "Height + crown, real scale", Icons.Filled.Height, Routes.HEIGHT),
        Tool("Sampling Plots", "Plot center + boundary", Icons.Filled.CenterFocusWeak, Routes.SAMPLING),
        Tool(
            "Distance Measurement", "Device-to-object + 2-point",
            Icons.Filled.Height, Routes.DISTANCE, iconRotation = 90f),
    )

    ForestixScaffold(nav, title = "Tree Measurement") { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ForestixSpace.md),
            verticalArrangement = Arrangement.spacedBy(ForestixSpace.md),
        ) {
            Text(
                "Direct measurement tools — readings save to the field log automatically.",
                style = type.caption, color = colors.textSecondary,
                modifier = Modifier.padding(top = ForestixSpace.sm),
            )
            Column(verticalArrangement = Arrangement.spacedBy(ForestixSpace.sm)) {
                tools.chunked(2).forEach { rowTools ->
                    Row(horizontalArrangement = Arrangement.spacedBy(ForestixSpace.sm)) {
                        rowTools.forEach { tool ->
                            ToolTile(tool, Modifier.weight(1f)) { nav.navigate(tool.route) }
                        }
                        if (rowTools.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
            FieldLogRowLink(
                subtitle = if (entries.isEmpty()) "No readings yet" else "${entries.size} readings — view & export",
            ) { nav.navigate(Routes.FIELD_LOG) }
            Spacer(Modifier.height(ForestixSpace.lg))
        }
    }
}

@Composable
private fun ToolTile(tool: Tool, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = Forestix.colors
    val type = Forestix.type
    Surface(
        color = colors.surface,
        shape = ForestixRadius.card,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 140.dp)
            // iOS `.shadow(color: .black.opacity(0.04), radius: 6, y: 1)`.
            .softDropShadow(
                Color.Black.copy(alpha = 0.04f),
                blurRadius = 6.dp, offsetY = 1.dp,
                cornerRadius = ForestixRadius.cardDp)
            .clip(ForestixRadius.card)
            .border(0.5.dp, colors.primary.copy(alpha = 0.18f), ForestixRadius.card)
            .clickableNoRipple(onClick),
    ) {
        Column(Modifier.padding(ForestixSpace.md), verticalArrangement = Arrangement.spacedBy(ForestixSpace.sm)) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(ForestixRadius.controlDp)).background(colors.primaryMuted),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    tool.icon, contentDescription = null, tint = colors.primary,
                    modifier = Modifier.size(20.dp).rotate(tool.iconRotation))
            }
            Spacer(Modifier.weight(1f))
            // iOS inner VStack(spacing: 2) — title sits 2 above subtitle.
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(tool.title, style = type.bodyBold, color = colors.textPrimary)
                Text(
                    tool.subtitle, style = type.caption, color = colors.textSecondary,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun FieldLogRowLink(subtitle: String, onClick: () -> Unit) {
    val colors = Forestix.colors
    val type = Forestix.type
    Surface(
        color = colors.surface,
        shape = ForestixRadius.card,
        modifier = Modifier
            .fillMaxWidth()
            .clip(ForestixRadius.card)
            .border(0.5.dp, colors.divider, ForestixRadius.card)
            .clickableNoRipple(onClick),
    ) {
        Row(Modifier.padding(ForestixSpace.md), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ForestixSpace.sm)) {
            Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, tint = colors.primary, modifier = Modifier.size(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Field log", style = type.bodyBold, color = colors.textPrimary)
                Text(subtitle, style = type.caption, color = colors.textSecondary)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(12.dp))
        }
    }
}
