// Settings — Developer / Research mode toggle plus the display preferences
// (units, log rule) that the field log and formatters consume. When
// developer mode is on, the AR measurement screens overlay a live internals
// HUD (depth source, intrinsics, point counts, raw chord, pitch, distance,
// σ) for the validation study and unlock the experiment tooling.

package com.hcjeong.forestix.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.hcjeong.forestix.LocalAppEnvironment
import com.hcjeong.forestix.common.UnitSystem
import com.hcjeong.forestix.sensors.ChordAlgorithm
import com.hcjeong.forestix.sensors.LogRule
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixSpace

@Composable
fun SettingsScreen(nav: NavController) {
    val env = LocalAppEnvironment.current
    val settings by env.settings.state.collectAsStateWithLifecycle()
    val colors = Forestix.colors
    val type = Forestix.type

    ForestixScaffold(nav, title = "Settings") { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(ForestixSpace.md),
            verticalArrangement = Arrangement.spacedBy(ForestixSpace.md),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Developer / research mode", style = type.bodyBold, color = colors.textPrimary)
                    Text(
                        "Show live measurement internals (depth source, intrinsics, points, raw Ø, pitch, distance, σ) on the AR screens for the validation study.",
                        style = type.caption, color = colors.textSecondary,
                    )
                }
                Switch(
                    checked = settings.developerMode,
                    onCheckedChange = { env.settings.setDeveloperMode(it) },
                )
            }

            HorizontalDivider(color = colors.textSecondary.copy(alpha = 0.15f))

            ChoiceSetting(
                title = "Units",
                subtitle = "Display DBH, height and distance in metric or imperial.",
                options = listOf(UnitSystem.METRIC to "Metric", UnitSystem.IMPERIAL to "Imperial"),
                selected = settings.unitSystem,
                onSelect = { env.settings.setUnitSystem(it) },
            )

            ChoiceSetting(
                title = "Log rule",
                subtitle = "Board-foot volume rule used when scaling logs.",
                options = LogRule.entries.map { it to it.displayName },
                selected = settings.logRule,
                onSelect = { env.settings.setLogRule(it) },
            )

            ChoiceSetting(
                title = "DBH algorithm",
                subtitle = "Depth-method diameter fit. Silhouette matches iOS " +
                    "(pixel-width); Depth-band is the point-cloud diagonal.",
                options = listOf(
                    ChordAlgorithm.SILHOUETTE to "Silhouette",
                    ChordAlgorithm.DEPTH_BAND to "Depth-band",
                ),
                selected = ChordAlgorithm.fromRaw(settings.dbhChordAlgorithm),
                onSelect = { env.settings.setDbhChordAlgorithm(it.raw) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> ChoiceSetting(
    title: String,
    subtitle: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    val colors = Forestix.colors
    val type = Forestix.type
    Column(verticalArrangement = Arrangement.spacedBy(ForestixSpace.sm)) {
        Text(title, style = type.bodyBold, color = colors.textPrimary)
        Text(subtitle, style = type.caption, color = colors.textSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(ForestixSpace.sm)) {
            options.forEach { (value, label) ->
                FilterChip(
                    selected = value == selected,
                    onClick = { onSelect(value) },
                    label = { Text(label) },
                )
            }
        }
    }
}
