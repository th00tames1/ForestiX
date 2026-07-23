// Settings — grouped-card sections mirroring the iOS inset-grouped Form:
// Region, Units, Log rule, Developer (+research CSV rows and, in developer
// mode, the DBH-algorithm picker), Calibration, Basemap tiles, Danger zone.
// When developer mode is on, the AR measurement screens overlay a live
// internals HUD (depth source, intrinsics, point counts, raw chord, pitch,
// distance, σ) for the validation study and unlock the experiment tooling.

package com.hcjeong.forestix.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Dangerous
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.hcjeong.forestix.LocalAppEnvironment
import com.hcjeong.forestix.common.Country
import com.hcjeong.forestix.common.Region
import com.hcjeong.forestix.common.UnitSystem
import com.hcjeong.forestix.common.defaultLogRule
import com.hcjeong.forestix.data.ResearchLog
import com.hcjeong.forestix.sensors.ChordAlgorithm
import com.hcjeong.forestix.sensors.LogRule
import com.hcjeong.forestix.sensors.RawCaptureStore
import com.hcjeong.forestix.ui.Routes
import com.hcjeong.forestix.ui.clickableNoRipple
import com.hcjeong.forestix.ui.screens.project.FormDivider
import com.hcjeong.forestix.ui.screens.project.FormSection
import com.hcjeong.forestix.ui.screens.project.MenuPickerRow
import com.hcjeong.forestix.ui.shareFile
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixSpace
import java.io.File
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(nav: NavController) {
    val env = LocalAppEnvironment.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by env.settings.state.collectAsStateWithLifecycle()
    val colors = Forestix.colors
    val type = Forestix.type

    // Bumped after research-CSV clear / danger-zone erase so the row
    // count and button enablement re-read the backing stores.
    var storeRefresh by remember { mutableIntStateOf(0) }

    // Destructive flow (iOS two-step confirmationDialog chain).
    var resetStep1 by remember { mutableStateOf(false) }
    var resetStep2 by remember { mutableStateOf(false) }
    var resetError by remember { mutableStateOf<String?>(null) }

    fun performFullReset() {
        scope.launch {
            try {
                // Delete every project through the repository (iOS parity —
                // cascades to stratum / design / planned / plot / tree rows).
                for (p in env.projectRepository.list()) {
                    env.projectRepository.delete(p.id)
                }
                // Quick-measure history + auto-captured photos + exports +
                // research CSV — the Android counterparts of the iOS
                // Attachments / Exports / Backups wipe.
                env.history.clearAll()
                File(context.filesDir, "measure-photos").deleteRecursively()
                File(context.cacheDir, "Exports").deleteRecursively()
                ResearchLog.clear(context)
                storeRefresh++
            } catch (e: Exception) {
                resetError = "Reset failed: ${e.message ?: e}. Some data may remain; " +
                    "try again or reinstall the app."
            }
        }
    }

    ForestixScaffold(nav, title = "Settings") { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(ForestixSpace.md),
            verticalArrangement = Arrangement.spacedBy(ForestixSpace.md),
        ) {
            // MARK: - Country & region (internationalization framework)
            FormSection(
                header = "Country & region",
                footer = "Country sets your units, the species presets and the volume " +
                    "standard — imperial + board-foot log rules for the US, metric + " +
                    "cubic metres elsewhere. Region further tunes the US species set and " +
                    "its default log rule. You can still override units below.",
            ) {
                MenuPickerRow(
                    title = "Country",
                    value = settings.country.displayName,
                    options = Country.entries.map { it.displayName },
                ) { index ->
                    val c = Country.entries[index]
                    env.settings.setCountry(c)
                    // Country drives the unit system (US→imperial, metric
                    // countries→metric); the manual Units toggle below still
                    // overrides it afterwards. Makes the footer claim true.
                    env.settings.setUnitSystem(c.defaultUnitSystem)
                    // Metric countries have no region; clear the US region so
                    // the scan picker falls back to the country species preset.
                    if (!c.hasRegions) env.settings.setRegion(null)
                    env.settings.setRegionPickerSeen(true)
                }
                // Region row only for countries that have regions (the US).
                if (settings.country.hasRegions) {
                    FormDivider()
                    val selected = Region.fromRaw(settings.region) ?: Region.ALL
                    MenuPickerRow(
                        title = "Region",
                        value = selected.displayName,
                        options = Region.entries.map { it.displayName },
                    ) { index ->
                        val r = Region.entries[index]
                        env.settings.setRegion(r.raw)
                        // Derive the US default log rule (West→Scribner, East→Doyle).
                        env.settings.setLogRule(r.defaultLogRule)
                        env.settings.setRegionPickerSeen(true)
                    }
                }
                FormDivider()
                // Volume standard — read-only, derived from the country.
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Volume standard", style = type.body, color = colors.textPrimary,
                        modifier = Modifier.weight(1f))
                    Text(settings.country.volumeStandardLabel, style = type.caption,
                        color = colors.textSecondary)
                }
            }

            // MARK: - Appearance (segmented, Light → Dark — the map home's
            // retired sun/moon chrome button, relocated; LOCKED strings +
            // placement, iOS parity)
            FormSection(header = "Appearance") {
                val appearanceOptions = listOf("light" to "Light", "dark" to "Dark")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    appearanceOptions.forEachIndexed { index, (value, label) ->
                        SegmentedButton(
                            selected = settings.appearance == value,
                            onClick = { env.settings.setAppearance(value) },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index, count = appearanceOptions.size),
                        ) { Text(label, style = type.caption, maxLines = 1) }
                    }
                }
            }

            // MARK: - Units (segmented, Imperial → Metric)
            FormSection(
                header = "Units",
                footer = "Display DBH, height and distance in metric or imperial.",
            ) {
                val unitOptions = listOf(UnitSystem.IMPERIAL to "Imperial", UnitSystem.METRIC to "Metric")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    unitOptions.forEachIndexed { index, (value, label) ->
                        SegmentedButton(
                            selected = settings.unitSystem == value,
                            onClick = { env.settings.setUnitSystem(value) },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index, count = unitOptions.size),
                        ) { Text(label, style = type.caption, maxLines = 1) }
                    }
                }
            }

            // MARK: - Log rule (US only — board-foot is a North-America concept;
            // metric countries express volume in m³ and hide this entirely)
            if (settings.country.usesLogRule) {
                FormSection(
                    header = "Log rule",
                    footer = "Determines board-foot volume from DBH + height. Scribner is " +
                        "the USFS Western default; Doyle dominates the Eastern US; " +
                        "International ¼″ is the most accurate but rarely used in practice.",
                ) {
                    MenuPickerRow(
                        title = "Log rule",
                        value = settings.logRule.displayName,
                        options = LogRule.entries.map { it.displayName },
                    ) { index -> env.settings.setLogRule(LogRule.entries[index]) }
                }
            }

            // MARK: - Developer (+ research CSV rows)
            FormSection(header = "Developer") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Developer / research mode", style = type.body, color = colors.textPrimary)
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
                    // Research CSV — per-measurement diagnostic rows (value,
                    // true value, error, distance, pitch/α, n, σ, tier)
                    // appended by the scan/distance screens while developer
                    // mode is on. Identical schema to the iOS ResearchLog so
                    // the exports concatenate.
                    val hasData = remember(storeRefresh, settings.developerMode) {
                        ResearchLog.hasData(context)
                    }
                    val rowCount = remember(storeRefresh, settings.developerMode) {
                        ResearchLog.rowCount(context)
                    }
                    FormDivider()
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(ForestixSpace.xs),
                    ) {
                        Icon(
                            Icons.Filled.GridOn, contentDescription = null,
                            tint = colors.primary, modifier = Modifier.size(18.dp))
                        Text("Research CSV", style = type.body, color = colors.textPrimary,
                            modifier = Modifier.weight(1f))
                        Text("$rowCount rows", style = type.body, color = colors.textSecondary)
                    }
                    FormDivider()
                    SettingsActionRow(
                        title = "Export research CSV",
                        icon = Icons.Filled.IosShare,
                        enabled = hasData,
                    ) {
                        ResearchLog.exportUri(context)?.let { shareFile(context, it, "text/csv") }
                    }
                    FormDivider()
                    SettingsActionRow(
                        title = "Clear research CSV",
                        icon = Icons.Filled.Delete,
                        enabled = hasData,
                        destructive = true,
                    ) {
                        ResearchLog.clear(context)
                        storeRefresh++
                    }

                    // Raw-capture replay recorder — serialize the raw inputs
                    // of every DBH burst / Height compute for offline
                    // estimator iteration against field ground truth.
                    FormDivider()
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Record raw captures", style = type.body, color = colors.textPrimary)
                            Text(
                                "Store raw depth, intrinsics, poses and calibration for each " +
                                    "measurement so the estimator can be re-run offline. Off by default.",
                                style = type.caption, color = colors.textSecondary,
                            )
                        }
                        Switch(
                            checked = settings.rawCaptureEnabled,
                            onCheckedChange = { env.settings.setRawCaptureEnabled(it) },
                        )
                    }
                    val rawCount = remember(storeRefresh) { RawCaptureStore.count(context) }
                    val rawBytes = remember(storeRefresh) { RawCaptureStore.totalSizeBytes(context) }
                    FormDivider()
                    Row(
                        Modifier.fillMaxWidth().clickableNoRipple { nav.navigate(Routes.RAW_CAPTURES) },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(ForestixSpace.xs),
                    ) {
                        Icon(
                            Icons.Filled.GridOn, contentDescription = null,
                            tint = colors.primary, modifier = Modifier.size(18.dp))
                        Text(
                            "Raw captures — $rawCount · ${rawBytesLabel(rawBytes)}",
                            style = type.body, color = colors.textPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
                            tint = colors.textTertiary, modifier = Modifier.size(14.dp))
                    }
                }
            }

            // MARK: - DBH algorithm (developer-only; normal users get the
            // single blessed path — same gating as iOS)
            if (settings.developerMode) {
                FormSection(
                    header = "DBH algorithm",
                    footer = "Depth-method diameter fit. Silhouette matches iOS " +
                        "(pixel-width); Depth-band is the point-cloud diagonal.",
                ) {
                    val algoOptions = listOf(
                        ChordAlgorithm.SILHOUETTE to "Silhouette",
                        ChordAlgorithm.DEPTH_BAND to "Depth-band",
                    )
                    val current = ChordAlgorithm.fromRaw(settings.dbhChordAlgorithm)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        algoOptions.forEachIndexed { index, (value, label) ->
                            SegmentedButton(
                                selected = current == value,
                                onClick = { env.settings.setDbhChordAlgorithm(value.raw) },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index, count = algoOptions.size),
                            ) { Text(label, style = type.caption, maxLines = 1) }
                        }
                    }
                }
            }

            // MARK: - Calibration (navigation row, iOS NavigationLink)
            FormSection(
                header = "Calibration",
                footer = "Wall fit captures the LiDAR depth noise and bias; " +
                    "cylinder fit estimates a linear DBH correction. " +
                    "Run both before your first field pilot.",
            ) {
                Row(
                    Modifier.fillMaxWidth().clickableNoRipple { nav.navigate(Routes.CALIBRATION) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Run Calibration", style = type.body, color = colors.textPrimary,
                        modifier = Modifier.weight(1f))
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
                        tint = colors.textTertiary, modifier = Modifier.size(14.dp))
                }
            }

            // MARK: - Basemap tiles
            FormSection(
                header = "Basemap tiles",
                footer = "The map ships with a built-in satellite base layer (Esri World " +
                    "Imagery) — nothing to set up. A template pasted here draws as an " +
                    "OVERLAY on top of that imagery (contour or forest-service tiles, " +
                    "for example). Use an XYZ template that substitutes {z}/{x}/{y}, " +
                    "and confirm you've reviewed the provider's usage policy before " +
                    "the overlay will draw.",
            ) {
                var tileTemplate by remember { mutableStateOf(settings.tileURLTemplate ?: "") }
                var providerLabel by remember { mutableStateOf(settings.tileProviderLabel ?: "") }
                FormTextField(
                    value = tileTemplate,
                    onValueChange = { new ->
                        tileTemplate = new
                        env.settings.setTileURLTemplate(new.ifEmpty { null })
                    },
                    placeholder = "https://tile.example.com/{z}/{x}/{y}.png",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                FormDivider()
                FormTextField(
                    value = providerLabel,
                    onValueChange = { new ->
                        providerLabel = new
                        env.settings.setTileProviderLabel(new.ifEmpty { null })
                    },
                    placeholder = "Provider name (optional)",
                )
                FormDivider()
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

            // MARK: - Danger zone (iOS dangerZoneSection)
            FormSection(
                header = "Danger zone",
                footer = "Two-step confirmation. Used for onboarding a new cruiser on a shared device.",
            ) {
                Row(
                    Modifier.fillMaxWidth().clickableNoRipple { resetStep1 = true },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ForestixSpace.xs),
                ) {
                    Icon(
                        Icons.Filled.Dangerous, contentDescription = null,
                        tint = colors.confidenceBad, modifier = Modifier.size(18.dp))
                    Text("Erase all Forestix data", style = type.body, color = colors.confidenceBad)
                }
            }

            Spacer(Modifier.height(ForestixSpace.lg))
        }
    }

    // MARK: - Destructive reset dialogs (iOS confirmationDialog chain)
    if (resetStep1) {
        AlertDialog(
            onDismissRequest = { resetStep1 = false },
            title = { Text("Reset Forestix data?") },
            text = {
                Text("This deletes every project, plot, tree, photo, and scan. Back up anything you need to keep first. This cannot be undone.")
            },
            confirmButton = {
                TextButton(onClick = { resetStep1 = false; resetStep2 = true }) {
                    Text("Continue", color = colors.confidenceBad)
                }
            },
            dismissButton = {
                TextButton(onClick = { resetStep1 = false }) { Text("Cancel") }
            },
        )
    }
    if (resetStep2) {
        AlertDialog(
            onDismissRequest = { resetStep2 = false },
            title = { Text("Are you absolutely sure?") },
            text = { Text("Last chance to back out. All local data will be erased.") },
            confirmButton = {
                TextButton(onClick = { resetStep2 = false; performFullReset() }) {
                    Text("Delete everything", color = colors.confidenceBad)
                }
            },
            dismissButton = {
                TextButton(onClick = { resetStep2 = false }) { Text("Cancel") }
            },
        )
    }
    if (resetError != null) {
        AlertDialog(
            onDismissRequest = { resetError = null },
            title = { Text("Something went wrong") },
            text = { Text(resetError ?: "") },
            confirmButton = {
                TextButton(onClick = { resetError = null }) { Text("OK") }
            },
        )
    }
}

/// Compact byte-size label for the "Raw captures — N · X" row.
private fun rawBytesLabel(bytes: Long): String = when {
    bytes >= 1_048_576 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1_048_576.0)
    bytes >= 1024 -> String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

/// Grouped-form action row — iOS Form `Button` with a `Label`: icon + text
/// in the accent colour (or destructive red), dimmed when disabled.
@Composable
private fun SettingsActionRow(
    title: String,
    icon: ImageVector,
    enabled: Boolean = true,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = Forestix.colors
    val type = Forestix.type
    val tint = when {
        !enabled -> colors.textTertiary
        destructive -> colors.confidenceBad
        else -> colors.primary
    }
    Row(
        Modifier.fillMaxWidth().clickableNoRipple { if (enabled) onClick() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ForestixSpace.xs),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Text(title, style = type.body, color = tint)
    }
}
