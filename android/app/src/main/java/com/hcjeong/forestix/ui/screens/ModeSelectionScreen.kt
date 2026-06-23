// Top-level mode picker — port of iOS ModeSelectionScreen. Two large
// vertically-stacked cards: Tree Measurement (direct tools) and Timber
// Cruising (full standard workflow, also contains Tree Measurement).

package com.hcjeong.forestix.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.outlined.Forest
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.hcjeong.forestix.ui.Routes
import com.hcjeong.forestix.ui.clickableNoRipple
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixRadius
import com.hcjeong.forestix.ui.theme.ForestixSpace

@Composable
fun ModeSelectionScreen(nav: NavController) {
    val colors = Forestix.colors
    val type = Forestix.type
    Box(
        Modifier
            .fillMaxSize()
            .background(colors.canvas),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            Text(
                "FORESTIX",
                style = type.sectionHead.copy(fontSize = 14.sp, letterSpacing = 2.4.sp, fontWeight = FontWeight.SemiBold),
                color = colors.primary,
            )
            Spacer(Modifier.size(ForestixSpace.xxs))
            Text("Choose a workflow", style = type.title, color = colors.textPrimary)
            Spacer(Modifier.size(ForestixSpace.xl))

            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ForestixSpace.md),
                verticalArrangement = Arrangement.spacedBy(ForestixSpace.md),
            ) {
                ModeButton(
                    title = "Tree Measurement",
                    subtitle = "Direct DBH \u00B7 height \u00B7 crown \u00B7 distance \u00B7 sampling plots",
                    icon = Icons.Filled.Straighten,
                ) { nav.navigate(Routes.TREE_HUB) }
                ModeButton(
                    title = "Timber Cruising",
                    subtitle = "Standard cruise: projects \u00B7 strata \u00B7 plots \u00B7 stand stats",
                    icon = Icons.Outlined.Forest,
                ) { nav.navigate(Routes.TIMBER_HUB) }
            }
            Spacer(Modifier.weight(2f))
        }
    }
}

@Composable
private fun ModeButton(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    val colors = Forestix.colors
    val type = Forestix.type
    Surface(
        color = colors.surface,
        shape = ForestixRadius.card,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp)
            .clip(ForestixRadius.card)
            .border(0.75.dp, colors.primary.copy(alpha = 0.18f), ForestixRadius.card)
            .clickableNoRipple(onClick),
    ) {
        Row(
            Modifier.padding(ForestixSpace.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ForestixSpace.md),
        ) {
            Box(
                Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(ForestixRadius.controlDp))
                    .background(colors.primaryMuted),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = colors.primary, modifier = Modifier.size(28.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = type.title.copy(fontSize = 22.sp, fontWeight = FontWeight.SemiBold), color = colors.textPrimary)
                Text(subtitle, style = type.caption, color = colors.textSecondary, textAlign = TextAlign.Start)
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.textTertiary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
