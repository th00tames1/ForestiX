// First-run Country → Region cascade (internationalization framework).
// Replaces the US-only region picker: the cruiser picks a Country first, then
// — only for countries that have sub-national regions (the US) — a Region.
// The volume standard and, for the US, the default log rule are derived from
// the selection automatically (US West → Scribner, East → Doyle); metric
// countries carry no log rule.
//
// Shown once after install (and re-openable from Settings → Country & region).
// Dismissing without finishing is allowed — `regionPickerSeen = true` is
// stamped on any selection / skip / dismiss so the sheet never auto-presents
// again. Named `LocaleSetupSheet` to match the iOS screen of the same name
// (the persisted `regionPickerSeen` flag keeps its name across both platforms).
//
// Host it in a ModalBottomSheet from the caller:
//   if (!settings.regionPickerSeen) {
//       ModalBottomSheet(onDismissRequest = { ... }) { LocaleSetupSheet(onDismiss = { ... }) }
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.Park
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hcjeong.forestix.LocalAppEnvironment
import com.hcjeong.forestix.common.Country
import com.hcjeong.forestix.common.Region
import com.hcjeong.forestix.common.defaultLogRule
import com.hcjeong.forestix.ui.clickableNoRipple
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixRadius
import com.hcjeong.forestix.ui.theme.ForestixSpace

@Composable
fun LocaleSetupSheet(onDismiss: () -> Unit) {
    val env = LocalAppEnvironment.current
    val settings by env.settings.state.collectAsStateWithLifecycle()
    val colors = Forestix.colors
    val type = Forestix.type

    // Two-step cascade: null = country step; a US country = region step.
    var regionStepCountry by remember { mutableStateOf<Country?>(null) }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = ForestixSpace.md)
            .padding(top = ForestixSpace.sm, bottom = ForestixSpace.xl),
        verticalArrangement = Arrangement.spacedBy(ForestixSpace.lg),
    ) {
        val inRegionStep = regionStepCountry != null

        // MARK: - Title bar (Back on the region step; Skip always)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (inRegionStep) {
                TextButton(onClick = { regionStepCountry = null }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back",
                        modifier = Modifier.size(16.dp))
                    Text("  Country")
                }
            }
            Text(
                if (inRegionStep) "Pick your region" else "Where do you cruise?",
                style = type.bodyBold, color = colors.textPrimary,
                modifier = Modifier.weight(1f))
            TextButton(onClick = {
                env.settings.setRegionPickerSeen(true)
                onDismiss()
            }) {
                Text("Skip")
            }
        }

        if (!inRegionStep) {
            // MARK: - Country step
            Column(verticalArrangement = Arrangement.spacedBy(ForestixSpace.xs)) {
                Text("Which country?", style = type.bodyBold, color = colors.textPrimary)
                Text(
                    "Sets the species presets and the volume standard (board-foot " +
                        "log rules for the US, cubic metres elsewhere). You can change " +
                        "this any time in Settings.",
                    style = type.caption, color = colors.textSecondary)
            }
            Column(verticalArrangement = Arrangement.spacedBy(ForestixSpace.sm)) {
                Country.entries.forEach { c ->
                    PickerRow(
                        glyph = Icons.Filled.Public,
                        title = c.displayName,
                        subtitle = c.subtitle,
                        isSelected = settings.country == c && !inRegionStep,
                        trailingChevron = c.hasRegions,
                    ) {
                        env.settings.setCountry(c)
                        if (c.hasRegions) {
                            // Advance to the region step (US).
                            regionStepCountry = c
                        } else {
                            // Metric country: no region, no log rule — done.
                            env.settings.setRegion(null)
                            env.settings.setRegionPickerSeen(true)
                            onDismiss()
                        }
                    }
                }
            }
        } else {
            // MARK: - Region step (US only)
            val selectedRegion = Region.fromRaw(settings.region)
            Column(verticalArrangement = Arrangement.spacedBy(ForestixSpace.xs)) {
                Text("Which forest do you cruise?", style = type.bodyBold,
                    color = colors.textPrimary)
                Text(
                    "Pre-loads the right species and sets the default log rule for " +
                        "your region.",
                    style = type.caption, color = colors.textSecondary)
            }
            Column(verticalArrangement = Arrangement.spacedBy(ForestixSpace.sm)) {
                Region.entries.forEach { r ->
                    PickerRow(
                        glyph = glyph(r),
                        title = r.displayName,
                        subtitle = r.subtitle,
                        isSelected = selectedRegion == r,
                        trailingChevron = false,
                    ) {
                        env.settings.setRegion(r.raw)
                        // Derive the US default log rule from the region.
                        env.settings.setLogRule(r.defaultLogRule)
                        env.settings.setRegionPickerSeen(true)
                        onDismiss()
                    }
                }
            }
        }
    }
}

// MARK: - Row

@Composable
private fun PickerRow(
    glyph: ImageVector,
    title: String,
    subtitle: String,
    isSelected: Boolean,
    trailingChevron: Boolean,
    action: () -> Unit,
) {
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
                Icon(glyph, contentDescription = null,
                    tint = colors.primary, modifier = Modifier.size(15.dp))
            }
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(title, style = type.bodyBold, color = colors.textPrimary)
                Text(subtitle, style = type.caption, color = colors.textSecondary,
                    maxLines = 2)
            }
            if (isSelected) {
                Icon(Icons.Filled.CheckCircle, contentDescription = "Selected",
                    tint = colors.primary, modifier = Modifier.size(17.dp))
            } else if (trailingChevron) {
                Icon(Icons.Filled.ChevronRight, contentDescription = null,
                    tint = colors.textTertiary, modifier = Modifier.size(12.dp))
            }
        }
    }
}

/// SF Symbol → Material icon mapping for the US region glyphs
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
