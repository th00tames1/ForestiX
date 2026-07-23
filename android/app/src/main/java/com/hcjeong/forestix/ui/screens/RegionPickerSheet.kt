// Port of iOS Screens/RegionPickerSheet.swift.
// First-run region picker. Shown once after install (and re-openable from
// Settings) — picks one of 11 US timber regions and pre-loads the matching
// FIA species codes into the species selection used across the app.
// A first-run onboarding pattern.
//
// Dismissing without picking is allowed — the cruiser can pick later in
// Settings → Region. We mark `regionPickerSeen = true` either way so the
// sheet doesn't auto-present every launch.
//
// Host it in a ModalBottomSheet (or a dialog/route) from the caller:
//   if (!settings.regionPickerSeen) {
//       ModalBottomSheet(onDismissRequest = { ... }) { RegionPickerSheet(onDismiss = { ... }) }
//   }

package com.hcjeong.forestix.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.Park
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hcjeong.forestix.LocalAppEnvironment
import com.hcjeong.forestix.common.Region
import com.hcjeong.forestix.ui.clickableNoRipple
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixRadius
import com.hcjeong.forestix.ui.theme.ForestixSpace

@Composable
fun RegionPickerSheet(onDismiss: () -> Unit) {
    val env = LocalAppEnvironment.current
    val settings by env.settings.state.collectAsStateWithLifecycle()
    val colors = Forestix.colors
    val type = Forestix.type
    val selectedRegion = Region.fromRaw(settings.region)

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = ForestixSpace.md)
            .padding(top = ForestixSpace.sm, bottom = ForestixSpace.xl),
        verticalArrangement = Arrangement.spacedBy(ForestixSpace.lg),
    ) {
        // MARK: - Title bar (iOS navigation title + Skip toolbar button)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Pick your region", style = type.bodyBold, color = colors.textPrimary,
                modifier = Modifier.weight(1f))
            TextButton(onClick = {
                env.settings.setRegionPickerSeen(true)
                onDismiss()
            }) {
                Text("Skip")
            }
        }

        // MARK: - Intro
        Column(verticalArrangement = Arrangement.spacedBy(ForestixSpace.xs)) {
            Text("Which forest do you cruise?", style = type.bodyBold,
                color = colors.textPrimary)
            Text(
                "Pre-loads the right species for your region. You can change this any time in Settings.",
                style = type.caption, color = colors.textSecondary)
        }

        // MARK: - Region list
        Column(verticalArrangement = Arrangement.spacedBy(ForestixSpace.sm)) {
            Region.entries.forEach { r ->
                RegionRow(
                    region = r,
                    isSelected = selectedRegion == r,
                ) {
                    env.settings.setRegion(r.raw)
                    env.settings.setRegionPickerSeen(true)
                    onDismiss()
                }
            }
        }
    }
}

// MARK: - Row

@Composable
private fun RegionRow(region: Region, isSelected: Boolean, action: () -> Unit) {
    val colors = Forestix.colors
    val type = Forestix.type
    Surface(
        color = colors.surface,
        shape = ForestixRadius.card,
        modifier = Modifier
            .fillMaxWidth()
            .clip(ForestixRadius.card)
            .border(
                width = if (isSelected) 1.5.dp else 0.5.dp,
                color = if (isSelected) colors.primary else colors.divider,
                shape = ForestixRadius.card,
            )
            .clickableNoRipple(action),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(ForestixSpace.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ForestixSpace.md),
        ) {
            Box(
                Modifier
                    .size(36.dp)
                    .background(colors.primaryMuted, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(glyph(region), contentDescription = null,
                    tint = colors.primary, modifier = Modifier.size(15.dp))
            }
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(region.displayName, style = type.bodyBold, color = colors.textPrimary)
                Text(region.subtitle, style = type.caption, color = colors.textSecondary,
                    maxLines = 2)
            }
            if (isSelected) {
                Icon(Icons.Filled.CheckCircle, contentDescription = "Selected",
                    tint = colors.primary, modifier = Modifier.size(17.dp))
            } else {
                Icon(Icons.Filled.ChevronRight, contentDescription = null,
                    tint = colors.textTertiary, modifier = Modifier.size(12.dp))
            }
        }
    }
}

/// SF Symbol → Material icon mapping for the iOS glyphs
/// (tree / mountain.2 / sun.max / drop / leaf / list.bullet).
private fun glyph(region: Region): ImageVector = when (region) {
    Region.PNW_WEST, Region.PNW_EAST, Region.N_ROCKIES -> Icons.Filled.Park
    Region.N_SIERRA, Region.S_SIERRA -> Icons.Filled.Landscape
    Region.CA_COAST -> Icons.Filled.Park
    Region.SOUTHWEST -> Icons.Filled.WbSunny
    Region.COASTAL_PLAIN, Region.BOTTOMLAND -> Icons.Filled.WaterDrop
    Region.PIEDMONT, Region.APPALACHIAN -> Icons.Filled.Eco
    Region.ALL -> Icons.AutoMirrored.Filled.List
}
