// Tree Measurement hub — port of iOS TreeMeasurementHubScreen.
// 2-column x 3-row grid, five tiles + an intentionally blank 6th slot,
// then a Field Log row. Tiles route to the AR measurement screens.

package com.hcjeong.forestix.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CenterFocusWeak
import androidx.compose.material.icons.filled.Height
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.hcjeong.forestix.LocalAppEnvironment
import com.hcjeong.forestix.ui.Routes
import com.hcjeong.forestix.ui.clickableNoRipple
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixRadius
import com.hcjeong.forestix.ui.theme.ForestixSpace

private data class Tool(val title: String, val subtitle: String, val icon: ImageVector, val route: String?)

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
        Tool("Distance Measurement", "Device-to-object + 2-point", Icons.Filled.SwapHoriz, Routes.DISTANCE),
    )

    ForestixScaffold(
        nav, title = "Tree Measurement",
        actions = {
            androidx.compose.material3.IconButton(onClick = { nav.navigate(Routes.SETTINGS) }) {
                androidx.compose.material3.Icon(
                    Icons.Filled.Settings, contentDescription = "Settings", tint = colors.primary,
                )
            }
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = padding,
            horizontalArrangement = Arrangement.spacedBy(ForestixSpace.sm),
            verticalArrangement = Arrangement.spacedBy(ForestixSpace.sm),
            modifier = Modifier.padding(horizontal = ForestixSpace.md),
        ) {
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) {
                Text(
                    "Direct measurement tools \u2014 readings save to the field log automatically.",
                    style = type.caption, color = colors.textSecondary,
                    modifier = Modifier.padding(top = ForestixSpace.sm),
                )
            }
            items(tools) { tool ->
                if (tool.route == null) {
                    Spacer(Modifier.heightIn(min = 1.dp))
                } else {
                    ToolTile(tool) { nav.navigate(tool.route) }
                }
            }
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) {
                FieldLogRowLink(
                    subtitle = if (entries.isEmpty()) "No readings yet" else "${entries.size} readings \u2014 view & export",
                ) { nav.navigate(Routes.FIELD_LOG) }
            }
        }
    }
}

@Composable
private fun ToolTile(tool: Tool, onClick: () -> Unit) {
    val colors = Forestix.colors
    val type = Forestix.type
    Surface(
        color = colors.surface,
        shape = ForestixRadius.card,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 140.dp)
            .clip(ForestixRadius.card)
            .border(0.5.dp, colors.primary.copy(alpha = 0.18f), ForestixRadius.card)
            .clickableNoRipple(onClick),
    ) {
        Column(Modifier.padding(ForestixSpace.md), verticalArrangement = Arrangement.spacedBy(ForestixSpace.sm)) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(ForestixRadius.controlDp)).background(colors.primaryMuted),
                contentAlignment = Alignment.Center,
            ) {
                Icon(tool.icon, contentDescription = null, tint = colors.primary, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.weight(1f))
            Text(tool.title, style = type.bodyBold, color = colors.textPrimary)
            Text(tool.subtitle, style = type.caption, color = colors.textSecondary)
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
            .padding(top = ForestixSpace.md)
            .clip(ForestixRadius.card)
            .border(0.5.dp, colors.divider, ForestixRadius.card)
            .clickableNoRipple(onClick),
    ) {
        Row(Modifier.padding(ForestixSpace.md), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ForestixSpace.sm)) {
            Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, tint = colors.primary, modifier = Modifier.size(16.dp))
            Column(Modifier.weight(1f)) {
                Text("Field log", style = type.bodyBold, color = colors.textPrimary)
                Text(subtitle, style = type.caption, color = colors.textSecondary)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(12.dp))
        }
    }
}
