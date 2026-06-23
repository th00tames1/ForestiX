// Timber Cruising hub — port of iOS TimberCruisingHubScreen. Standard
// cruising workflow tiles. Tree Measurement is reachable from here too.
// The deeper project/stratum/cruise-design screens are part of the staged
// follow-up port; tiles that aren't wired yet route to a stub.

package com.hcjeong.forestix.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.CenterFocusWeak
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Straighten
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

@Composable
fun TimberCruisingHubScreen(nav: NavController) {
    val colors = Forestix.colors
    val type = Forestix.type
    val env = LocalAppEnvironment.current
    val entries by env.history.entries.collectAsStateWithLifecycle()
    val logSubtitle = if (entries.isEmpty()) "No readings yet" else "${entries.size} readings"

    ForestixScaffold(nav, title = "Timber Cruising") { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ForestixSpace.md),
            verticalArrangement = Arrangement.spacedBy(ForestixSpace.md),
        ) {
            Text(
                "Standard cruising workflow \u2014 projects, strata, plots, stand statistics.",
                style = type.caption, color = colors.textSecondary,
                modifier = Modifier.padding(top = ForestixSpace.sm),
            )
            TileLink("Projects", "Stratum \u00B7 cruise design \u00B7 field tally", Icons.Filled.Folder) { /* staged */ }
            TileLink("Recon cruise", "Quick basal area tally \u00B7 sample sizing", Icons.Filled.CenterFocusWeak) { /* staged */ }
            TileLink("Tree Measurement", "Direct DBH \u00B7 height \u00B7 crown \u00B7 distance \u00B7 sampling", Icons.Filled.Straighten) {
                nav.navigate(Routes.TREE_HUB)
            }
            TileLink("Field log", logSubtitle, Icons.AutoMirrored.Filled.List) { nav.navigate(Routes.FIELD_LOG) }
            TileLink("Reference", "Formulas \u00B7 log rules \u00B7 conversions", Icons.Filled.Book) { /* staged */ }
            TileLink("Settings", "Region \u00B7 units \u00B7 calibration \u00B7 backup", Icons.Filled.Settings) { /* staged */ }
        }
    }
}

@Composable
private fun TileLink(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
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
        Row(Modifier.padding(ForestixSpace.md), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ForestixSpace.md)) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(ForestixRadius.controlDp)).background(colors.primaryMuted),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = colors.primary, modifier = Modifier.size(17.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = type.bodyBold, color = colors.textPrimary)
                Text(subtitle, style = type.caption, color = colors.textSecondary)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(12.dp))
        }
    }
}
