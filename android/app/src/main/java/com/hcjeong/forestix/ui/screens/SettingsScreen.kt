// Settings — grouped-card sections in the shared cross-platform order (iOS
// parity), REGROUPED from the old per-control sections:
//   1. Region & units   — Country/Region → Units → Log rule
//   2. Display          — Appearance (Light/Dark)
//   3. Calibration      — wall/cylinder fit wizard
//   4. Data & backup    — .tcproj export + restore; pointer to per-project exports
//   5. Advanced         — Basemap tiles behind a disclosure (ordinary field
//                          setup, so it sits ABOVE the developer group)
//   6. Developer & research — GATED behind the developer-mode toggle: DBH
//                          algorithm, research CSV / diagnostic log EXPORTS,
//                          raw captures
//   7. Clear developer data — GATED; the destructive Clears, kept in their own
//                          card away from the Export rows, each confirmed
//   8. Danger zone      — erase-all, always last
//
// This is a re-grouping + reorder + gating change only; every control keeps
// its exact binding/action and its committed copy. When developer mode is on,
// the AR measurement screens also overlay a live internals HUD (depth source,
// intrinsics, point counts, raw chord, pitch, distance, σ) for the validation
// study and unlock the experiment tooling.

package com.hcjeong.forestix.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.KeyboardArrowDown
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
import com.hcjeong.forestix.backup.BackupViewModel
import com.hcjeong.forestix.common.Country
import com.hcjeong.forestix.common.ForestixLogger
import com.hcjeong.forestix.common.Region
import com.hcjeong.forestix.common.UnitSystem
import com.hcjeong.forestix.common.defaultLogRule
import com.hcjeong.forestix.data.ResearchExport
import com.hcjeong.forestix.data.ResearchLog
import com.hcjeong.forestix.data.TruthBackfill
import com.hcjeong.forestix.data.TruthUnitRepair
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
import com.hcjeong.forestix.ui.theme.ForestixBorderedButton
import com.hcjeong.forestix.ui.theme.ForestixSpace
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    // Developer-data clears — confirmed, and deliberately housed in their own
    // card away from the Export rows (a mis-tap used to wipe the corpus).
    var confirmClearResearch by remember { mutableStateOf(false) }
    var confirmClearEvents by remember { mutableStateOf(false) }

    // Research-CSV export: the run is in flight, and what the last one held
    // back. The sentence stays on screen after the share sheet closes — the
    // counts are the point, and a toast the cruiser dismissed is not a record.
    var researchExportRunning by remember { mutableStateOf(false) }
    var researchExportResult by remember { mutableStateOf<String?>(null) }

    // Ground-truth recovery: the run is in flight, and what the last one did.
    var truthBackfillRunning by remember { mutableStateOf(false) }
    var truthBackfillResult by remember { mutableStateOf<String?>(null) }

    // Ground-truth unit repair. The PLAN is held between the preview and the
    // confirm so what the cruiser agreed to is what gets written — recomputing
    // it after the tap would let the corpus move under the sentence they read.
    var truthRepairRunning by remember { mutableStateOf(false) }
    var truthRepairPlan by remember { mutableStateOf<TruthUnitRepair.Plan?>(null) }
    var truthRepairPreview by remember { mutableStateOf<String?>(null) }
    var truthRepairResult by remember { mutableStateOf<String?>(null) }

    // Backup / restore (Data & backup group).
    val backup = remember(env) { BackupViewModel(env) }
    var backupBusy by remember { mutableStateOf(false) }
    var backupError by remember { mutableStateOf<String?>(null) }
    var restoreSummary by remember { mutableStateOf<String?>(null) }

    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        backupBusy = true
        scope.launch {
            try {
                val result = backup.restore(context, uri)
                restoreSummary = "Restored ${result.importedProjectIds.size} project" +
                    (if (result.importedProjectIds.size == 1) "" else "s") +
                    " — ${result.plotCount} plots, ${result.treeCount} trees."
                storeRefresh++
            } catch (e: Exception) {
                backupError = e.message
                    ?: "Restore failed. Check the file is a valid .tcproj, then try again."
            } finally {
                backupBusy = false
            }
        }
    }

    fun runExport() {
        if (backupBusy) return
        backupBusy = true
        scope.launch {
            try {
                val outcome = backup.exportAllProjects(context)
                shareFile(context, outcome.shareUri, "application/zip")
            } catch (e: Exception) {
                backupError = e.message
                    ?: "Backup failed. Free up some storage, then try again."
            } finally {
                backupBusy = false
            }
        }
    }

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
                File(context.filesDir, "restored-media").deleteRecursively()
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
            // MARK: - 1. Region & units (internationalization framework —
            // Country/Region, the derived Volume standard, the Units override,
            // and the US-only Log-rule override, in dependency order).
            FormSection(
                header = "Region & units",
                footer = "Sets your units, species list, and volume standard. The US " +
                    "uses board-foot log rules; elsewhere is cubic metres.",
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
                // The read-only "Volume standard" row lived here. Dropped as
                // redundant: it only restated what the Log rule picker below
                // already says. Country.volumeStandardLabel and the volume
                // logic behind it are untouched.
                // Units override (Imperial → Metric). Committed footer kept as
                // an inline caption now that this is no longer its own section.
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Units", style = type.body, color = colors.textPrimary)
                    val unitOptions = listOf(
                        UnitSystem.IMPERIAL to "Imperial",
                        UnitSystem.METRIC to "Metric")
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
                    Text(
                        "Display DBH, height and distance in metric or imperial.",
                        style = type.caption, color = colors.textSecondary,
                    )
                }
                // Log rule — US only (board-foot is a North-America concept;
                // metric countries express volume in m³ and hide this). Placed
                // last so it reads as an override of the region-derived default.
                if (settings.country.usesLogRule) {
                    FormDivider()
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        MenuPickerRow(
                            title = "Log rule",
                            value = settings.logRule.displayName,
                            options = LogRule.entries.map { it.displayName },
                        ) { index -> env.settings.setLogRule(LogRule.entries[index]) }
                        Text(
                            "Sets board-foot volume from DBH and height. Scribner (West), " +
                                "Doyle (East), or International ¼″ (most accurate).",
                            style = type.caption, color = colors.textSecondary,
                        )
                    }
                }
            }

            // MARK: - 2. Display (Appearance segmented, Light → Dark — the map
            // home's retired sun/moon chrome button, relocated; LOCKED strings,
            // iOS parity).
            FormSection(header = "Display") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Appearance", style = type.body, color = colors.textPrimary)
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

                // Breast-height guide — answers "how does the phone know it is
                // reading the stem AT breast height?" by putting breast height
                // in the world where the cruiser can see it: a marker on the
                // ground, a riser, and a ring at 1.37 m to bring around the
                // trunk.
                //
                // IT LIVED IN THE DEVELOPER BLOCK, which is the wrong shelf
                // for the only answer the app offers to a question every
                // cruiser has. It is a Display setting because that is all it
                // is — drawn on the Diameter scan, never read by the
                // estimator, never stored, never exported. Still off by
                // default. iOS SettingsScreen 1:1.
                FormDivider()
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Breast-height guide", style = type.body, color = colors.textPrimary)
                        Text(
                            "Tap the ground at the foot of the tree on the Diameter scan and " +
                                "the app draws a line up to 1.37 m (4.5 ft) with a ring at the " +
                                "top, so you can see where breast height crosses the trunk. " +
                                "Guide only — it never changes the recorded diameter. " +
                                "Off by default.",
                            style = type.caption, color = colors.textSecondary,
                        )
                    }
                    Switch(
                        checked = settings.breastHeightGuide,
                        onCheckedChange = { env.settings.setBreastHeightGuide(it) },
                    )
                }
            }

            // MARK: - 2b. Measuring
            // FIELD REPORT F10 — the cruise tally chains diameter → height by
            // default. Cruisers who only want diameters turn it off here.
            // Strings and the stored key are identical on iOS.
            FormSection(
                header = "Measuring",
                footer = "After you accept a diameter in cruise, Height opens for " +
                    "the same tree. Skip returns to the tally.",
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Measure height after diameter",
                        style = type.body,
                        color = colors.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = settings.measureHeightAfterDiameter,
                        onCheckedChange = { env.settings.setMeasureHeightAfterDiameter(it) },
                    )
                }
            }

            // MARK: - 3. Calibration (navigation row, iOS NavigationLink)
            FormSection(
                header = "Calibration",
                footer = "Scan a flat wall, then a round post you have measured. " +
                    "This tells Forestix how far off your phone's depth sensor runs, " +
                    "so diameters come out right. Do both before your first cruise.",
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

            // MARK: - 4. Data & backup (.tcproj export/restore; per-project
            // PDF/CSV/GeoJSON exports live on each project's own screen).
            FormSection(
                header = "Data & backup",
                footer = "Saves every project, photo, and scan to a .tcproj file you can " +
                    "restore on this device. Per-project PDF, CSV and GeoJSON exports live " +
                    "on each project's screen.",
            ) {
                SettingsActionRow(
                    title = if (backupBusy) "Working…" else "Export backup",
                    icon = Icons.Filled.IosShare,
                    enabled = !backupBusy,
                ) { runExport() }
                FormDivider()
                SettingsActionRow(
                    title = "Restore from .tcproj…",
                    icon = Icons.Filled.Download,
                    enabled = !backupBusy,
                ) { restoreLauncher.launch(arrayOf("*/*")) }
            }

            // MARK: - 5. Basemap tiles (was inside "Advanced", BELOW the
            // developer group). Moved ABOVE it: it is ordinary field setup,
            // not developer tooling. Content unchanged — still a disclosure,
            // still folded by default because the built-in satellite base
            // works with nothing set.
            FormSection(header = "Advanced") {
                var basemapExpanded by remember { mutableStateOf(false) }
                Row(
                    Modifier.fillMaxWidth().clickableNoRipple { basemapExpanded = !basemapExpanded },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Basemap tiles", style = type.body, color = colors.textPrimary,
                        modifier = Modifier.weight(1f))
                    Icon(
                        if (basemapExpanded) Icons.Filled.KeyboardArrowDown
                        else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = colors.textTertiary, modifier = Modifier.size(14.dp))
                }
                if (basemapExpanded) {
                    var tileTemplate by remember { mutableStateOf(settings.tileURLTemplate ?: "") }
                    var providerLabel by remember { mutableStateOf(settings.tileProviderLabel ?: "") }
                    FormDivider()
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
                    // Draw/hide the configured overlay. It lives here beside
                    // the template it belongs to — the map's own sheet keeps
                    // to map type, boundary and offline maps.
                    FormDivider()
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Show overlay on the map",
                            style = type.body, color = colors.textPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = settings.overlayEnabled,
                            onCheckedChange = { env.settings.setOverlayEnabled(it) },
                            enabled = settings.tileURLTemplate != null,
                        )
                    }
                    Text(
                        "Paste the address of a map-tile service (it will contain " +
                            "{z}/{x}/{y}) to draw contour or forest-service maps over the " +
                            "base map. It shows only after you confirm the provider's " +
                            "usage policy above.",
                        style = type.caption, color = colors.textSecondary,
                    )
                }
            }

            // MARK: - 6. Developer & research (GATED — the developer-mode
            // toggle is the only always-visible row; when on it holds ALL dev /
            // study tooling: DBH algorithm, research CSV, diagnostic log, and
            // the raw-capture recorder). The destructive Clears live in their
            // own card AFTER this one.
            val hasData = remember(storeRefresh, settings.developerMode) {
                settings.developerMode && ResearchLog.hasData(context)
            }
            val rowCount = remember(storeRefresh, settings.developerMode) {
                if (settings.developerMode) ResearchLog.rowCount(context) else 0
            }
            val hasEvents = remember(storeRefresh, settings.developerMode) {
                settings.developerMode && ForestixLogger.hasEvents()
            }
            FormSection(header = "Developer & research") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Developer / research mode", style = type.body, color = colors.textPrimary)
                        Text(
                            "Overlay live measurement internals on the AR screens for the validation study.",
                            style = type.caption, color = colors.textSecondary,
                        )
                    }
                    Switch(
                        checked = settings.developerMode,
                        onCheckedChange = { env.settings.setDeveloperMode(it) },
                    )
                }
                if (settings.developerMode) {
                    // DBH algorithm — depth-method diameter fit. Moved in from
                    // its own former section; developer-only, since normal
                    // users get the single blessed path (iOS gates it the same).
                    FormDivider()
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("DBH algorithm", style = type.body, color = colors.textPrimary)
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
                        Text(
                            "Depth-method diameter fit. Silhouette matches iOS " +
                                "(pixel-width); Depth-band is the point-cloud diagonal.",
                            style = type.caption, color = colors.textSecondary,
                        )
                    }

                    // Research CSV — per-measurement diagnostic rows (value,
                    // true value, error, distance, pitch/α, n, σ, tier)
                    // appended by the scan/distance screens while developer
                    // mode is on. Identical schema to the iOS ResearchLog so
                    // the exports concatenate.
                    //
                    // Field fix: Clear used to sit DIRECTLY under Export for
                    // both logs, so one mis-tap destroyed the research corpus.
                    // Only the non-destructive Exports live here now; both
                    // Clears moved to their own "Clear developer data" card
                    // below, behind a confirmation.
                    FormDivider()
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(ForestixSpace.xs),
                    ) {
                        Text("Research CSV", style = type.body, color = colors.textPrimary,
                            modifier = Modifier.weight(1f))
                        Text("$rowCount rows", style = type.body, color = colors.textSecondary)
                    }
                    // THE EXPORT IS CLASSIFIED, NOT COPIED. The log is
                    // append-only, so it still holds the row a retake replaced
                    // and the ground truth a correction moved on from.
                    // [ResearchExport] splits it into what the FIELD LOG shows
                    // and what it no longer does, ships BOTH files in one
                    // archive, and returns the counts — which go on screen
                    // below, because a quiet filter is how someone later
                    // concludes data went missing. Nothing on disk is touched.
                    FormDivider()
                    SettingsActionRow(
                        title = if (researchExportRunning) "Exporting…"
                                else "Export research CSV",
                        icon = Icons.Filled.IosShare,
                        enabled = hasData && !researchExportRunning,
                    ) {
                        researchExportRunning = true
                        researchExportResult = null
                        scope.launch {
                            // Off the main thread: the pass parses the whole
                            // research CSV, which is a field season of rows.
                            val outcome = withContext(Dispatchers.IO) {
                                ResearchExport.run(context, env.history.entries.value)
                            }
                            researchExportResult = outcome.message
                            researchExportRunning = false
                            // Shared only when a file was actually written — an
                            // empty log and a failed write both leave the
                            // sentence on screen and no share sheet, rather
                            // than sharing a file that is not there.
                            outcome.uri?.let { shareFile(context, it, "application/zip") }
                        }
                    }
                    researchExportResult?.let {
                        Text(it, style = type.caption, color = colors.textSecondary)
                    }

                    // Event log — local-only structured analytics (plot open,
                    // backup create/restore, crash-recovery prompt). JSONL,
                    // never uploaded; share mirrors the iOS Settings row.
                    FormDivider()
                    SettingsActionRow(
                        title = "Export diagnostic log",
                        icon = Icons.Filled.IosShare,
                        enabled = hasEvents,
                    ) {
                        ForestixLogger.exportUri(context)?.let {
                            shareFile(context, it, "application/json")
                        }
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
                    // Developer mode on, recorder off: the AR screens show
                    // internals but keep NOTHING for the validation corpus.
                    if (!settings.rawCaptureEnabled) {
                        Text(
                            "NOT RECORDING — measurements are not being stored for replay.",
                            style = type.caption, color = colors.confidenceWarn,
                        )
                    }
                    // Readable bundles + the ones that exist on disk but whose
                    // manifest won't parse: the latter are in NO list and no
                    // total, so the count alone used to under-report the
                    // corpus without a word.
                    val rawInv = remember(storeRefresh) { RawCaptureStore.inventory(context) }
                    val rawCount = rawInv.parsed
                    val rawUnreadable = rawInv.unparseable
                    val rawBytes = remember(storeRefresh) { RawCaptureStore.totalSizeBytes(context) }
                    val rawFree = remember(storeRefresh) { RawCaptureStore.freeSpaceBytes(context) }
                    FormDivider()
                    Row(
                        Modifier.fillMaxWidth().clickableNoRipple { nav.navigate(Routes.RAW_CAPTURES) },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(ForestixSpace.xs),
                    ) {
                        Icon(
                            Icons.Filled.Inventory2, contentDescription = null,
                            tint = colors.primary, modifier = Modifier.size(18.dp))
                        Text(
                            "Raw captures — $rawCount" +
                                (if (rawUnreadable > 0) " (+$rawUnreadable unreadable)" else "") +
                                " · ${rawBytesLabel(rawBytes)} · " +
                                "${rawBytesLabel(rawFree)} free",
                            style = type.body,
                            color = if (rawFree < RawCaptureStore.MIN_FREE_BYTES ||
                                rawUnreadable > 0
                            ) {
                                colors.confidenceWarn
                            } else {
                                colors.textPrimary
                            },
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
                            tint = colors.textTertiary, modifier = Modifier.size(14.dp))
                    }

                    // GROUND-TRUTH RECOVERY. A truth typed for a CAPTURE (on a
                    // scan screen before the truth moved onto the reading, or
                    // in the raw-captures console at any time since) lives only
                    // in that capture's manifest, so the field log shows a
                    // blank True field for a tree the cruiser taped. This
                    // attaches those to the readings they belong to.
                    //
                    // DELIBERATELY NOT A LAUNCH MIGRATION. It reads every
                    // manifest on disk — a few hundred after two field days,
                    // each a whole JSON document with pose trails in it — and a
                    // blocking pass on a cold morning is its own bug. It also
                    // has no end date: the console can strand a new truth
                    // today, so a run-once-at-version-N flag would be wrong by
                    // design. It is idempotent, so running it again is free and
                    // changes nothing.
                    FormDivider()
                    ForestixBorderedButton(
                        label = "Recover ground truths",
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !truthBackfillRunning,
                    ) {
                        truthBackfillRunning = true
                        truthBackfillResult = null
                        scope.launch {
                            val text = withContext(Dispatchers.IO) {
                                TruthBackfill.run(context, env.history)
                            }
                            truthBackfillResult = text
                            truthBackfillRunning = false
                        }
                    }
                    Text(
                        "Attach truths typed for a raw capture to the reading they belong to. Never overwrites a truth already on a reading.",
                        style = type.caption, color = colors.textSecondary,
                    )
                    truthBackfillResult?.let {
                        // The corpus was just rewritten — say by how much, so
                        // the cruiser can check it against their own backup. A
                        // silent repair of research data is the wrong shape
                        // even when the arithmetic is right.
                        Text(it, style = type.caption, color = colors.textSecondary)
                    }

                    // GROUND-TRUTH UNIT REPAIR. Before the truth field had a
                    // unit toggle the cruiser typed inches and feet off the
                    // tape into a field the app stored as centimetres and
                    // metres, so a stem taped at 27 in went into the corpus as
                    // 27 cm. This multiplies those back into the base they
                    // meant.
                    //
                    // PREVIEW, THEN WRITE. It rewrites research data in three
                    // stores at once and the correction cannot be read back out
                    // of the number afterwards, so the cruiser sees the counts
                    // and worked examples first and nothing moves until they
                    // confirm.
                    //
                    // Like the recovery above it is an action rather than a
                    // launch migration, and for the same reason: it reads every
                    // manifest on disk. It is one-shot by construction, not by
                    // a version flag — repairing a truth records the unit it
                    // was typed in, which is the very marker that selects an
                    // unrepaired one.
                    FormDivider()
                    ForestixBorderedButton(
                        label = "Repair imperial ground truths",
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !truthRepairRunning,
                    ) {
                        truthRepairRunning = true
                        truthRepairResult = null
                        scope.launch {
                            val plan = withContext(Dispatchers.IO) {
                                TruthUnitRepair.preview(context, env.history)
                            }
                            truthRepairPlan = plan
                            truthRepairPreview = TruthUnitRepair.previewText(plan)
                            truthRepairRunning = false
                        }
                    }
                    Text(
                        "Ground truths typed before the unit toggle were stored as if the digits were metric. This re-bases them — inches to centimetres, feet to metres. A truth that records the unit it was typed in is never touched.",
                        style = type.caption, color = colors.textSecondary,
                    )
                    truthRepairResult?.let {
                        // Same reason the recovery says what it did: research
                        // data moved, so say by how much and where the full
                        // list is.
                        Text(it, style = type.caption, color = colors.textSecondary)
                    }
                }
            }

            // MARK: - 7. Clear developer data (GATED). The two destructive
            // Clears used to sit immediately under their own Export rows, one
            // mis-tap from wiping the research corpus. They live here now — a
            // separate card, away from every Export, each behind a confirm.
            if (settings.developerMode) {
                FormSection(
                    header = "Clear developer data",
                    footer = "Clearing is permanent. Export first — the exported file is the " +
                        "only copy once these are cleared.",
                ) {
                    SettingsActionRow(
                        title = "Clear research CSV",
                        icon = Icons.Filled.Delete,
                        enabled = hasData,
                        destructive = true,
                    ) { confirmClearResearch = true }
                    FormDivider()
                    SettingsActionRow(
                        title = "Clear diagnostic log",
                        icon = Icons.Filled.Delete,
                        enabled = hasEvents,
                        destructive = true,
                    ) { confirmClearEvents = true }
                }
            }

            // MARK: - 8. Danger zone (iOS dangerZoneSection — ALWAYS last)
            FormSection(
                header = "Danger zone",
                footer = "Permanently erases every project on this device.",
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

    // MARK: - Backup / restore result dialogs
    if (backupError != null) {
        AlertDialog(
            onDismissRequest = { backupError = null },
            title = { Text("Something went wrong") },
            text = { Text(backupError ?: "") },
            confirmButton = {
                TextButton(onClick = { backupError = null }) { Text("OK") }
            },
        )
    }
    if (restoreSummary != null) {
        AlertDialog(
            onDismissRequest = { restoreSummary = null },
            title = { Text("Restore complete") },
            text = { Text(restoreSummary ?: "") },
            confirmButton = {
                TextButton(onClick = { restoreSummary = null }) { Text("OK") }
            },
        )
    }

    // THE CONFIRM THE REPAIR MUST PASS BEFORE IT WRITES ANYTHING. The plan is
    // read out of state rather than recomputed on the tap, so what the cruiser
    // agreed to is what lands. The Repair button is offered only when there IS
    // something to write — a confirm on an empty plan invites a tap that means
    // nothing. iOS shows the same text in a `.alert`.
    truthRepairPreview?.let { previewText ->
        val plan = truthRepairPlan
        AlertDialog(
            onDismissRequest = { truthRepairPreview = null; truthRepairPlan = null },
            title = { Text("Repair imperial ground truths") },
            text = { Text(previewText) },
            confirmButton = {
                if (plan != null && !plan.isEmpty) {
                    TextButton(onClick = {
                        truthRepairPreview = null
                        truthRepairPlan = null
                        truthRepairRunning = true
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                TruthUnitRepair.applyPlan(context, plan, env.history)
                            }
                            truthRepairResult = TruthUnitRepair.resultText(result)
                            truthRepairRunning = false
                        }
                    }) { Text("Repair") }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    truthRepairPreview = null
                    truthRepairPlan = null
                }) { Text("Cancel") }
            },
        )
    }

    // MARK: - Destructive reset dialogs (iOS confirmationDialog chain)
    if (confirmClearResearch) {
        AlertDialog(
            onDismissRequest = { confirmClearResearch = false },
            title = { Text("Clear research CSV?") },
            text = {
                Text(
                    "This deletes every research row on this device. Anything not " +
                        "already exported is gone for good.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClearResearch = false
                    ResearchLog.clear(context)
                    storeRefresh++
                }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearResearch = false }) { Text("Cancel") }
            },
        )
    }
    if (confirmClearEvents) {
        AlertDialog(
            onDismissRequest = { confirmClearEvents = false },
            title = { Text("Clear diagnostic log?") },
            text = {
                Text(
                    "This deletes every logged event on this device. Anything not " +
                        "already exported is gone for good.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClearEvents = false
                    ForestixLogger.clear()
                    storeRefresh++
                }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearEvents = false }) { Text("Cancel") }
            },
        )
    }
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
