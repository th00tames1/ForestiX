// Port of iOS Screens/ProjectDashboardScreen.swift.
// Spec §3.1 REQ-PRJ-002/003/004. Project dashboard — strata list,
// planned-plot summary, entry points into CruiseDesign / PlotMap /
// Export / Settings.
//
// Phase 7.4 redesign: whole screen reads top-to-bottom as a step-by-step
// guide (①→②→③→④). Each step shows its own friendly description and a
// primary action. The strata step supports both "draw on map" (no file
// needed) and legacy "import from file" paths.

package com.hcjeong.forestix.ui.screens.project

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.hcjeong.forestix.LocalAppEnvironment
import com.hcjeong.forestix.data.cruise.Project
import com.hcjeong.forestix.ui.Routes
import com.hcjeong.forestix.ui.clickableNoRipple
import com.hcjeong.forestix.ui.screens.ForestixScaffold
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixSpace
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.launch

@Composable
fun ProjectDashboardScreen(nav: NavController, projectId: String) {
    val env = LocalAppEnvironment.current
    var project by remember { mutableStateOf<Project?>(null) }
    LaunchedEffect(projectId) {
        project = env.projectRepository.read(UUID.fromString(projectId))
    }
    val loaded = project
    if (loaded == null) {
        ForestixScaffold(nav, title = "Project") { padding ->
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        return
    }
    ProjectDashboardContent(nav, loaded)
}

@Composable
private fun ProjectDashboardContent(nav: NavController, project: Project) {
    val env = LocalAppEnvironment.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = Forestix.colors
    val type = Forestix.type
    val viewModel = remember(project.id) { ProjectDashboardViewModel(project) }

    val strata by viewModel.strata.collectAsState()
    val plannedPlots by viewModel.plannedPlots.collectAsState()
    val design by viewModel.design.collectAsState()
    val closedPlotCount by viewModel.closedPlotCount.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val toastMessage by viewModel.toastMessage.collectAsState()

    var importFormat by remember { mutableStateOf(ProjectDashboardViewModel.ImportFormat.GEO_JSON) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = try {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            } catch (e: Exception) {
                viewModel.errorMessage.value = "Import failed: $e"
                null
            }
            if (bytes != null) viewModel.importStrata(bytes, importFormat)
        }
    }

    // iOS `.task` + `.onAppear` — runs on first appear and again each time
    // the dashboard re-enters composition (e.g. popping back from
    // CruiseDesign or PlotTally).
    LaunchedEffect(Unit) {
        viewModel.configure(env)
        viewModel.refresh()
    }

    LaunchedEffect(toastMessage) {
        val message = toastMessage ?: return@LaunchedEffect
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        viewModel.toastMessage.value = null
    }

    ForestixScaffold(nav, title = project.name) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ForestixSpace.md),
            verticalArrangement = Arrangement.spacedBy(ForestixSpace.md),
        ) {
            // MARK: - Getting started (progress guide)
            FormSection(header = "GUIDE") {
                Text("How this works", style = type.bodyBold, color = colors.textPrimary)
                StepRow(
                    n = 1, done = strata.isNotEmpty(),
                    title = "Define strata",
                    hint = "Draw boundaries on the map, or import GeoJSON / KML.")
                StepRow(
                    n = 2, done = design != null && plannedPlots.isNotEmpty(),
                    title = "Design cruise + generate plots",
                    hint = "Pick plot size and spacing — sample plots are generated automatically.")
                StepRow(
                    n = 3, done = closedPlotCount > 0,
                    title = "Measure in the field (Go Cruise)",
                    hint = "Walk to each plot and measure trees one by one. DBH via LiDAR, height via AR.")
                StepRow(
                    n = 4, done = plannedPlots.isNotEmpty() && closedPlotCount >= plannedPlots.size,
                    title = "Review + export",
                    hint = "Check stand statistics and export to PDF / CSV / GeoJSON.")
            }

            // MARK: - Summary
            FormSection(header = "Summary") {
                LabeledContentRow(
                    "Units",
                    project.units.raw.replaceFirstChar { it.uppercase(Locale.US) })
                LabeledContentRow("Total area", formatAcres(viewModel.totalAcres))
                LabeledContentRow("Strata", "${strata.size}")
                LabeledContentRow("Planned plots", "${plannedPlots.size}")
            }

            // MARK: - Strata (step 1)
            FormSection(
                header = "① Strata",
                footer = if (strata.isNotEmpty())
                    "Tap the trash icon to delete. Area is computed automatically from lat/lon."
                else null,
            ) {
                if (strata.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(ForestixSpace.xs)) {
                        Icon(Icons.Filled.Map, contentDescription = null,
                            tint = colors.textPrimary, modifier = Modifier.size(16.dp))
                        Text("No strata yet", style = type.bodyBold, color = colors.textPrimary)
                    }
                    Text(
                        "A stratum is a cutting block you want to measure. Draw corners on the map, or import a prepared GeoJSON / KML file.",
                        style = type.caption, color = colors.textSecondary)
                    Button(
                        onClick = { nav.navigate(ProjectFlowRoutes.stratumDraw(project.id.toString())) },
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                    ) {
                        Text("Draw stratum on map")
                    }
                    Box {
                        var importMenuOpen by remember { mutableStateOf(false) }
                        OutlinedButton(
                            onClick = { importMenuOpen = true },
                            modifier = Modifier.fillMaxWidth().height(40.dp),
                        ) {
                            Icon(Icons.Filled.FileDownload, contentDescription = null,
                                modifier = Modifier.size(16.dp))
                            Spacer(Modifier.size(ForestixSpace.xs))
                            Text("Import from file")
                        }
                        DropdownMenu(
                            expanded = importMenuOpen,
                            onDismissRequest = { importMenuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Import GeoJSON") },
                                onClick = {
                                    importMenuOpen = false
                                    importFormat = ProjectDashboardViewModel.ImportFormat.GEO_JSON
                                    importLauncher.launch(arrayOf("application/json", "application/geo+json", "application/octet-stream", "*/*"))
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Import KML") },
                                onClick = {
                                    importMenuOpen = false
                                    importFormat = ProjectDashboardViewModel.ImportFormat.KML
                                    importLauncher.launch(arrayOf("application/vnd.google-earth.kml+xml", "application/xml", "text/xml", "*/*"))
                                },
                            )
                        }
                    }
                } else {
                    strata.forEach { stratum ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(stratum.name, style = type.body, color = colors.textPrimary)
                                Text(formatAcres(stratum.areaAcres.toDouble()),
                                    style = type.caption, color = colors.textSecondary)
                            }
                            IconButton(onClick = { scope.launch { viewModel.delete(stratum.id) } }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete",
                                    tint = colors.confidenceBad, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickableNoRipple { nav.navigate(ProjectFlowRoutes.stratumDraw(project.id.toString())) },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(ForestixSpace.xs),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null,
                            tint = colors.primary, modifier = Modifier.size(16.dp))
                        Text("Draw new stratum", style = type.body, color = colors.primary)
                    }
                }
            }

            // MARK: - Plan (step 2)
            FormSection(
                header = "② Cruise plan",
                footer = if (strata.isEmpty()) "Register at least one stratum in ① first." else null,
            ) {
                NavRow(
                    title = "Cruise design",
                    subtitle = "Choose plot size + sampling method → plots are generated automatically",
                    enabled = strata.isNotEmpty(),
                ) {
                    nav.navigate(ProjectFlowRoutes.cruiseDesign(project.id.toString()))
                }
                NavRow(
                    title = "Plot map",
                    subtitle = "Review generated plot locations",
                ) {
                    nav.navigate(ProjectFlowRoutes.plotMap(project.id.toString()))
                }
            }

            // MARK: - Cruise (step 3)
            FormSection(header = "③ Field measurement") {
                // Go Cruise unlocks only with BOTH a saved design AND
                // generated plots — otherwise CruiseFlowScreen hits the
                // "No planned plots yet" dead-end.
                if (design != null && plannedPlots.isNotEmpty()) {
                    NavRow(
                        title = "Go Cruise",
                        subtitle = cruiseSubtitle(plannedPlots.size, closedPlotCount),
                        bold = true,
                    ) {
                        nav.navigate(ProjectFlowRoutes.cruiseFlow(project.id.toString()))
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(ForestixSpace.xs)) {
                        Icon(Icons.Filled.Lock, contentDescription = null,
                            tint = colors.textSecondary, modifier = Modifier.size(18.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Finish ② first", style = type.bodyBold, color = colors.textPrimary)
                            Text(
                                "You need a saved cruise design and generated plots before field measurement can begin.",
                                style = type.caption, color = colors.textSecondary)
                        }
                    }
                }
            }

            // MARK: - Tools (step 4 + misc)
            FormSection(
                header = "④ Tools",
                footer = "Calibration only needs to be done once — it noticeably improves accuracy.",
            ) {
                NavRow(title = "Pre-field checklist") {
                    nav.navigate(ProjectFlowRoutes.preFieldChecklist(project.id.toString()))
                }
                NavRow(title = "Calibrate this project") {
                    nav.navigate(ProjectFlowRoutes.calibration(project.id.toString()))
                }
                NavRow(title = "Export results (PDF · CSV · GeoJSON)") {
                    nav.navigate(ProjectFlowRoutes.export(project.id.toString()))
                }
                NavRow(title = "Settings") {
                    nav.navigate(Routes.SETTINGS)
                }
            }

            Spacer(Modifier.height(ForestixSpace.xl))
        }
    }

    if (errorMessage != null) {
        AlertDialog(
            onDismissRequest = { viewModel.errorMessage.value = null },
            title = { Text("Something went wrong") },
            text = { Text(errorMessage ?: "") },
            confirmButton = {
                TextButton(onClick = { viewModel.errorMessage.value = null }) { Text("OK") }
            },
        )
    }
}

// MARK: - Helpers

/// Second line of the Go Cruise row — shows progress vs total so the
/// cruiser doesn't have to drill into the plot list to see how many
/// plots remain.
private fun cruiseSubtitle(total: Int, closed: Int): String {
    if (closed == 0) {
        return "Navigate to plot → record center → AR boundary → add trees"
    }
    return "$closed of $total plot${if (total == 1) "" else "s"} closed"
}

private fun formatAcres(value: Double): String =
    String.format(Locale.US, "%.2f acres", value)

// MARK: - Rows

@Composable
private fun StepRow(n: Int, done: Boolean, title: String, hint: String) {
    val colors = Forestix.colors
    val type = Forestix.type
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier
                .size(28.dp)
                .background(
                    if (done) colors.confidenceOk else colors.primaryMuted,
                    CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (done) {
                Icon(Icons.Filled.Check, contentDescription = null,
                    tint = Color.White, modifier = Modifier.size(14.dp))
            } else {
                Text("$n", style = type.caption, color = colors.primary)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = type.bodyBold, color = colors.textPrimary)
            Text(hint, style = type.caption, color = colors.textSecondary)
        }
    }
}

@Composable
private fun NavRow(
    title: String,
    subtitle: String? = null,
    enabled: Boolean = true,
    bold: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = Forestix.colors
    val type = Forestix.type
    val alpha = if (enabled) 1f else 0.4f
    Row(
        Modifier
            .fillMaxWidth()
            .clickableNoRipple { if (enabled) onClick() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = if (bold) type.bodyBold else type.body,
                color = colors.textPrimary.copy(alpha = alpha))
            if (subtitle != null) {
                Text(subtitle, style = type.caption,
                    color = colors.textSecondary.copy(alpha = alpha))
            }
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
            tint = colors.textTertiary, modifier = Modifier.size(14.dp))
    }
}

/// Suppress unused-import style warnings for FontStyle if the italic
/// species row moves; kept because CruiseDesignScreen shares this package.
private val unusedFontStyle = FontStyle.Italic
