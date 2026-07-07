// Settings — Developer / Research mode toggle plus the display preferences
// (units, log rule) that the field log and formatters consume. When
// developer mode is on, the AR measurement screens overlay a live internals
// HUD (depth source, intrinsics, point counts, raw chord, pitch, distance,
// σ) for the validation study and unlock the experiment tooling.
// Also hosts the iOS SettingsScreen regionSection (FIA species pre-load),
// calibrationSection (project-less calibration entry) and basemapSection
// (XYZ tile template + provider label + usage acknowledgement).

package com.hcjeong.forestix.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.hcjeong.forestix.LocalAppEnvironment
import com.hcjeong.forestix.common.Region
import com.hcjeong.forestix.data.ResearchLog
import com.hcjeong.forestix.common.UnitSystem
import com.hcjeong.forestix.sensors.ChordAlgorithm
import com.hcjeong.forestix.sensors.LogRule
import com.hcjeong.forestix.ui.Routes
import com.hcjeong.forestix.ui.shareFile
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixSpace

@Composable
fun SettingsScreen(nav: NavController) {
    val env = LocalAppEnvironment.current
    val context = LocalContext.current
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
            // MARK: - Region (iOS regionSection)
            RegionSetting(
                selected = Region.fromRaw(settings.region) ?: Region.ALL,
                onSelect = { r ->
                    env.settings.setRegion(r.raw)
                    env.settings.setRegionPickerSeen(true)
                },
            )

            HorizontalDivider(color = colors.textSecondary.copy(alpha = 0.15f))

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

            if (settings.developerMode) {
                // Research CSV — per-measurement diagnostic rows (value, true
                // value, error, distance, pitch/α, n, σ, tier) appended by the
                // scan/distance screens while developer mode is on. Identical
                // schema to the iOS ResearchLog so the exports concatenate.
                Column(verticalArrangement = Arrangement.spacedBy(ForestixSpace.sm)) {
                    Text("Research CSV", style = type.bodyBold, color = colors.textPrimary)
                    Text(
                        "${ResearchLog.rowCount(context)} rows recorded on this device.",
                        style = type.caption, color = colors.textSecondary,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(ForestixSpace.sm)) {
                        OutlinedButton(
                            onClick = { ResearchLog.exportUri(context)?.let { shareFile(context, it, "text/csv") } },
                            enabled = ResearchLog.hasData(context),
                        ) { Text("Export") }
                        OutlinedButton(
                            onClick = { ResearchLog.clear(context) },
                            enabled = ResearchLog.hasData(context),
                        ) { Text("Clear") }
                    }
                }
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

            HorizontalDivider(color = colors.textSecondary.copy(alpha = 0.15f))

            // MARK: - Calibration (iOS calibrationSection)
            Column(verticalArrangement = Arrangement.spacedBy(ForestixSpace.sm)) {
                Text("Calibration", style = type.bodyBold, color = colors.textPrimary)
                Text(
                    "Wall fit captures the LiDAR depth noise and bias; " +
                        "cylinder fit estimates a linear DBH correction. " +
                        "Run both before your first field pilot.",
                    style = type.caption, color = colors.textSecondary,
                )
                OutlinedButton(onClick = { nav.navigate(Routes.CALIBRATION) }) {
                    Text("Run Calibration")
                }
            }

            HorizontalDivider(color = colors.textSecondary.copy(alpha = 0.15f))

            // MARK: - Basemap tiles (iOS basemapSection)
            BasemapSetting()
        }
    }
}

/// iOS regionSection — Picker over Region.allCases; selecting also stamps
/// regionPickerSeen so the first-run sheet stops auto-presenting.
@Composable
private fun RegionSetting(selected: Region, onSelect: (Region) -> Unit) {
    val colors = Forestix.colors
    val type = Forestix.type
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(ForestixSpace.sm)) {
        Text("Region", style = type.bodyBold, color = colors.textPrimary)
        Text(
            "Pre-loads the FIA species set for your timber region — affects " +
                "which species appear in scan-time pickers.",
            style = type.caption, color = colors.textSecondary,
        )
        Box {
            OutlinedButton(onClick = { expanded = true }) {
                Text(selected.displayName)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                Region.entries.forEach { r ->
                    DropdownMenuItem(
                        text = { Text(r.displayName) },
                        onClick = {
                            expanded = false
                            onSelect(r)
                        },
                    )
                }
            }
        }
    }
}

/// iOS basemapSection — XYZ template field, provider label, usage-policy
/// acknowledgement. Local text state write-through mirrors the iOS @State
/// fields seeded from settings.
@Composable
private fun BasemapSetting() {
    val env = LocalAppEnvironment.current
    val settings by env.settings.state.collectAsStateWithLifecycle()
    val colors = Forestix.colors
    val type = Forestix.type
    var tileTemplate by remember { mutableStateOf(settings.tileURLTemplate ?: "") }
    var providerLabel by remember { mutableStateOf(settings.tileProviderLabel ?: "") }
    Column(verticalArrangement = Arrangement.spacedBy(ForestixSpace.sm)) {
        Text("Basemap tiles", style = type.bodyBold, color = colors.textPrimary)
        Text(
            "No tile provider ships by default. Paste an XYZ template that " +
                "substitutes {z}/{x}/{y}. Confirm you've reviewed the provider's " +
                "usage policy before downloading.",
            style = type.caption, color = colors.textSecondary,
        )
        OutlinedTextField(
            value = tileTemplate,
            onValueChange = { new ->
                tileTemplate = new
                env.settings.setTileURLTemplate(new.ifEmpty { null })
            },
            placeholder = { Text("https://tile.example.com/{z}/{x}/{y}.png") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = providerLabel,
            onValueChange = { new ->
                providerLabel = new
                env.settings.setTileProviderLabel(new.ifEmpty { null })
            },
            placeholder = { Text("Provider name (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "I have reviewed this provider's usage policy",
                style = type.body, color = colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = settings.providerUsageAcknowledged,
                onCheckedChange = { env.settings.setProviderUsageAcknowledged(it) },
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
