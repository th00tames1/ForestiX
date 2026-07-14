// Port of iOS Screens/ExportScreen.swift + ViewModels/ExportViewModel.swift.
// Spec §3.1 & §8. Phase 1 shipped plan-only export; Phase 6 adds the full
// cruise bundle (CSV x 5, GeoJSON x 2, Shapefile x 3, PDF report) via
// FullCruiseExporter.
//
// The view model:
//   - assembles an ExportBundle (reads through every repo),
//   - runs FullCruiseExporter on Dispatchers.IO,
//   - publishes progress (0…1) and a cumulative artefact list,
//   - hands each artefact's File to the screen for share-sheet use.
//
// Android adaptations: files land under <cacheDir>/Exports/ (the only tree
// the manifest FileProvider exposes) and the iOS UIActivityViewController
// becomes an ACTION_SEND chooser. iOS shares the whole session *folder*;
// Android content URIs are per-file, so the folder share zips the session
// folder first (ZipWriter) and shares the archive.

package com.hcjeong.forestix.ui.screens

import android.content.Context
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.hcjeong.forestix.AppEnvironment
import com.hcjeong.forestix.LocalAppEnvironment
import com.hcjeong.forestix.data.cruise.CruiseDesign
import com.hcjeong.forestix.data.cruise.HeightDiameterFit
import com.hcjeong.forestix.data.cruise.PlannedPlot
import com.hcjeong.forestix.data.cruise.Plot
import com.hcjeong.forestix.data.cruise.Project
import com.hcjeong.forestix.data.cruise.SpeciesConfig
import com.hcjeong.forestix.data.cruise.Stratum
import com.hcjeong.forestix.data.cruise.Tree
import com.hcjeong.forestix.export.CSVExporter
import com.hcjeong.forestix.export.ExportArtefact
import com.hcjeong.forestix.export.ExportBundleBuilder
import com.hcjeong.forestix.export.ExportDataSource
import com.hcjeong.forestix.export.FullCruiseExporter
import com.hcjeong.forestix.export.GeoJSONExporter
import com.hcjeong.forestix.export.ZipWriter
import com.hcjeong.forestix.export.uuidString
import com.hcjeong.forestix.ui.clickableNoRipple
import com.hcjeong.forestix.ui.screens.project.FormDivider
import com.hcjeong.forestix.ui.screens.project.FormSection
import com.hcjeong.forestix.ui.shareFile
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixSpace
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// MARK: - View model (port of iOS ExportViewModel)

class ExportViewModel(val project: Project) {

    data class ExportedFile(val url: File, val displayName: String) {
        val id: File get() = url
    }

    // MARK: - Published state

    private val _exportedFiles = MutableStateFlow<List<ExportedFile>>(emptyList())
    val exportedFiles: StateFlow<List<ExportedFile>> = _exportedFiles.asStateFlow()

    private val _lastSessionFolder = MutableStateFlow<File?>(null)
    val lastSessionFolder: StateFlow<File?> = _lastSessionFolder.asStateFlow()

    private val _progress = MutableStateFlow(0.0)   // 0…1
    val progress: StateFlow<Double> = _progress.asStateFlow()

    private val _progressLabel = MutableStateFlow("")
    val progressLabel: StateFlow<String> = _progressLabel.asStateFlow()

    private val _isExporting = MutableStateFlow(false)
    val isExporting: StateFlow<Boolean> = _isExporting.asStateFlow()

    val errorMessage = MutableStateFlow<String?>(null)

    /// File (or session folder) the screen should hand to the share
    /// chooser. The composable observes it, fires the intent, then
    /// resets to null — the Android analogue of the iOS `.sheet(item:)`.
    val shareURL = MutableStateFlow<File?>(null)

    // MARK: - Dependencies

    private var appEnv: AppEnvironment? = null

    fun configure(environment: AppEnvironment) {
        appEnv = environment
    }

    // MARK: - Phase 1 entry points (plan-only)

    suspend fun exportCSV(context: Context) {
        val env = appEnv ?: return
        try {
            val strata = env.stratumRepository.listByProject(project.id)
            val plots = env.plannedPlotRepository.listByProject(project.id)
            val csv = CSVExporter.plannedPlotsCSV(plannedPlots = plots, strata = strata)
            val url = writeLegacy(context, csv.toByteArray(Charsets.UTF_8), suffix = "planned-plots.csv")
            appendExport(url = url, displayName = "Planned plots (CSV)")
        } catch (e: Exception) {
            errorMessage.value = "CSV export failed: ${e.message ?: e}"
        }
    }

    suspend fun exportStratumCSV(context: Context) {
        val env = appEnv ?: return
        try {
            val strata = env.stratumRepository.listByProject(project.id)
            val csv = CSVExporter.stratumListCSV(strata = strata)
            val url = writeLegacy(context, csv.toByteArray(Charsets.UTF_8), suffix = "strata.csv")
            appendExport(url = url, displayName = "Strata (CSV)")
        } catch (e: Exception) {
            errorMessage.value = "Stratum CSV export failed: ${e.message ?: e}"
        }
    }

    suspend fun exportGeoJSON(context: Context) {
        val env = appEnv ?: return
        try {
            val strata = env.stratumRepository.listByProject(project.id)
            val plots = env.plannedPlotRepository.listByProject(project.id)
            val text = GeoJSONExporter.plan(strata = strata, plannedPlots = plots)
            val url = writeLegacy(context, text.toByteArray(Charsets.UTF_8), suffix = "plan.geojson")
            appendExport(url = url, displayName = "Plan (GeoJSON)")
        } catch (e: Exception) {
            errorMessage.value = "GeoJSON export failed: ${e.message ?: e}"
        }
    }

    // MARK: - Phase 6 full-cruise export

    suspend fun exportAll(context: Context) {
        val env = appEnv ?: return
        if (_isExporting.value) return
        _isExporting.value = true
        _progress.value = 0.0
        _progressLabel.value = "Preparing…"
        try {
            // Repo reads are suspend (Room); snapshot them first, then run
            // the file writes off the main thread.
            val ds = RepositoryExportDataSource.load(project = project, env = env)
            val result = withContext(Dispatchers.IO) {
                val bundle = ExportBundleBuilder.build(ds)
                FullCruiseExporter.writeToCache(context, bundle) { done, total, label ->
                    _progress.value = if (total == 0) 1.0 else done.toDouble() / total
                    _progressLabel.value = label
                }
            }
            _lastSessionFolder.value = result.folder
            var files = _exportedFiles.value
            for (art in result.artefacts) {
                files = listOf(ExportedFile(url = art.url, displayName = art.displayName)) + files
            }
            _exportedFiles.value = files
            shareURL.value = result.folder
            _isExporting.value = false
            _progress.value = 1.0
            _progressLabel.value = "Done"
        } catch (e: Exception) {
            errorMessage.value = "Export failed: ${e.message ?: e}"
            _isExporting.value = false
            _progress.value = 0.0
        }
    }

    // MARK: - Single-artefact exports (accessible from the picker UI)

    suspend fun exportTreesCSV(context: Context) = runSingle(context, ExportArtefact.Kind.CSV_TREES)
    suspend fun exportPlotsCSV(context: Context) = runSingle(context, ExportArtefact.Kind.CSV_PLOTS)
    suspend fun exportStandSummaryCSV(context: Context) = runSingle(context, ExportArtefact.Kind.CSV_STAND_SUMMARY)
    suspend fun exportCruiseGeoJSON(context: Context) = runSingle(context, ExportArtefact.Kind.GEOJSON_CRUISE)
    suspend fun exportShapefilePlots(context: Context) = runSingle(context, ExportArtefact.Kind.SHAPEFILE_PLOTS)
    suspend fun exportPDFReport(context: Context) = runSingle(context, ExportArtefact.Kind.PDF_REPORT)

    private suspend fun runSingle(context: Context, kind: ExportArtefact.Kind) {
        val env = appEnv ?: return
        try {
            val ds = RepositoryExportDataSource.load(project = project, env = env)
            val result = withContext(Dispatchers.IO) {
                val bundle = ExportBundleBuilder.build(ds)
                FullCruiseExporter.writeToCache(context, bundle, progress = null)
            }
            _lastSessionFolder.value = result.folder
            result.artefacts.firstOrNull { it.kind == kind }?.let { art ->
                appendExport(url = art.url, displayName = art.displayName)
            }
        } catch (e: Exception) {
            errorMessage.value = "Export failed: ${e.message ?: e}"
        }
    }

    // MARK: - Sandbox paths

    /// Legacy writer for the Phase 1 plan-only buttons — they still drop
    /// files into a per-project folder for quick access. Lives under
    /// `<cacheDir>/Exports/` so the manifest FileProvider can share it.
    private fun writeLegacy(context: Context, data: ByteArray, suffix: String): File {
        val folder = File(File(context.cacheDir, "Exports"), project.id.uuidString)
        folder.mkdirs()
        // ISO-8601 UTC with ":" replaced by "-" (filename-safe), matching
        // the iOS `ISO8601DateFormatter` + replacingOccurrences step.
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH-mm-ss'Z'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        val timestamp = f.format(Date())
        val url = File(folder, "$timestamp-$suffix")
        url.writeBytes(data)
        return url
    }

    private fun appendExport(url: File, displayName: String) {
        _exportedFiles.value = listOf(ExportedFile(url = url, displayName = displayName)) + _exportedFiles.value
        shareURL.value = url
    }
}

// MARK: - ExportDataSource adapter over AppEnvironment

/// Port of the iOS RepositoryExportDataSource. The Kotlin ExportDataSource
/// surface is synchronous while the Room repositories are suspend, so
/// `load` snapshots every table up front and the adapter serves the
/// in-memory copies.
class RepositoryExportDataSource private constructor(
    private val cachedProject: Project,
    private val designs: List<CruiseDesign>,
    private val strataList: List<Stratum>,
    private val plannedList: List<PlannedPlot>,
    private val plotsList: List<Plot>,
    private val treesByPlot: Map<UUID, List<Tree>>,
    private val speciesList: List<SpeciesConfig>,
    private val volList: List<com.hcjeong.forestix.data.cruise.VolumeEquation>,
    private val hdFitList: List<HeightDiameterFit>,
) : ExportDataSource {

    override fun project(): Project = cachedProject

    /// Ad-hoc cruising (v3 "Start plot") never creates a design row, so
    /// the bundle falls back to a fixed-area stub around the plots' own
    /// denormalized areas — the same rule the cruise map's live stats use.
    override fun cruiseDesign(forProjectId: UUID): CruiseDesign =
        designs.firstOrNull() ?: CruiseDesign(
            id = UUID.randomUUID(),
            projectId = forProjectId,
            plotType = com.hcjeong.forestix.data.cruise.PlotType.FIXED_AREA,
            plotAreaAcres = plotsList.maxByOrNull { it.startedAt }?.plotAreaAcres ?: 0.1f,
            baf = null,
            samplingScheme = com.hcjeong.forestix.data.cruise.SamplingScheme.MANUAL,
            gridSpacingMeters = null,
        )

    override fun strata(forProjectId: UUID): List<Stratum> = strataList
    override fun plannedPlots(forProjectId: UUID): List<PlannedPlot> = plannedList
    override fun plots(forProjectId: UUID): List<Plot> = plotsList
    override fun trees(forPlotId: UUID): List<Tree> = treesByPlot[forPlotId] ?: emptyList()
    override fun species(): List<SpeciesConfig> = speciesList
    override fun volumeEquations(): List<com.hcjeong.forestix.data.cruise.VolumeEquation> = volList
    override fun hdFits(forProjectId: UUID): List<HeightDiameterFit> = hdFitList

    companion object {
        suspend fun load(project: Project, env: AppEnvironment): RepositoryExportDataSource {
            val plots = env.plotRepository.listByProject(project.id)
            val treesByPlot = mutableMapOf<UUID, List<Tree>>()
            for (p in plots) {
                treesByPlot[p.id] = env.treeRepository.listByPlot(p.id, includeDeleted = true)
            }
            return RepositoryExportDataSource(
                cachedProject = project,
                designs = env.cruiseDesignRepository.forProject(project.id),
                strataList = env.stratumRepository.listByProject(project.id),
                plannedList = env.plannedPlotRepository.listByProject(project.id),
                plotsList = plots,
                treesByPlot = treesByPlot,
                speciesList = env.speciesConfigRepository.list(),
                volList = env.volumeEquationRepository.list(),
                hdFitList = env.heightDiameterFitRepository.listByProject(project.id),
            )
        }
    }
}

// MARK: - Screen

@Composable
fun ExportScreen(nav: NavController, projectId: String) {
    val env = LocalAppEnvironment.current
    var project by remember { mutableStateOf<Project?>(null) }
    LaunchedEffect(projectId) {
        project = env.projectRepository.read(UUID.fromString(projectId))
    }
    val loaded = project
    if (loaded == null) {
        ForestixScaffold(nav, title = "Export") { padding ->
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        return
    }
    ExportContent(nav, loaded)
}

@Composable
private fun ExportContent(nav: NavController, project: Project) {
    val env = LocalAppEnvironment.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = Forestix.colors
    val type = Forestix.type
    val viewModel = remember(project.id) { ExportViewModel(project) }

    LaunchedEffect(Unit) { viewModel.configure(env) }

    val exportedFiles by viewModel.exportedFiles.collectAsStateWithLifecycle()
    val lastSessionFolder by viewModel.lastSessionFolder.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val progressLabel by viewModel.progressLabel.collectAsStateWithLifecycle()
    val isExporting by viewModel.isExporting.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val shareURL by viewModel.shareURL.collectAsStateWithLifecycle()

    // iOS `.sheet(item:)` → fire the share chooser once per shareURL set.
    // Directories can't ride a content URI, so a session folder gets
    // zipped (stored, no compression) and the archive is shared instead.
    LaunchedEffect(shareURL) {
        val target = shareURL ?: return@LaunchedEffect
        try {
            val file = withContext(Dispatchers.IO) {
                if (target.isDirectory) zipFolderForShare(target) else target
            }
            if (file != null) {
                shareFile(context, FullCruiseExporter.shareUri(context, file), exportMimeFor(file))
            }
        } finally {
            // Clear the trigger only AFTER the chooser has fired: nulling
            // the key first restarts this keyed effect and cancels the
            // in-flight zip, so the share sheet never appears.
            viewModel.shareURL.value = null
        }
    }

    ForestixScaffold(nav, title = "Export") { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(ForestixSpace.md),
            verticalArrangement = Arrangement.spacedBy(ForestixSpace.md),
        ) {
            FormSection(header = "Full cruise export") {
                ExportActionRow(
                    label = "Export all (PDF, CSV, GeoJSON, Shapefile)",
                    icon = Icons.Filled.IosShare,
                    enabled = !isExporting,
                    trailing = if (isExporting) {
                        {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                    } else null,
                ) {
                    scope.launch { viewModel.exportAll(context) }
                }
                if (isExporting || progress > 0) {
                    FormDivider()
                    Column(verticalArrangement = Arrangement.spacedBy(ForestixSpace.xxs)) {
                        LinearProgressIndicator(
                            progress = { progress.toFloat() },
                            color = colors.primary,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(progressLabel, style = type.caption, color = colors.textSecondary)
                    }
                }
            }

            FormSection(header = "Individual formats") {
                ExportActionRow("PDF report") { scope.launch { viewModel.exportPDFReport(context) } }
                FormDivider()
                ExportActionRow("Trees (CSV)") { scope.launch { viewModel.exportTreesCSV(context) } }
                FormDivider()
                ExportActionRow("Plots (CSV)") { scope.launch { viewModel.exportPlotsCSV(context) } }
                FormDivider()
                ExportActionRow("Stand summary (CSV)") { scope.launch { viewModel.exportStandSummaryCSV(context) } }
                FormDivider()
                ExportActionRow("Cruise (GeoJSON)") { scope.launch { viewModel.exportCruiseGeoJSON(context) } }
                FormDivider()
                ExportActionRow("Plot centres (Shapefile ZIP)") { scope.launch { viewModel.exportShapefilePlots(context) } }
            }

            FormSection(header = "Plan exports") {
                ExportActionRow("Planned plots CSV") { scope.launch { viewModel.exportCSV(context) } }
                FormDivider()
                ExportActionRow("Strata CSV") { scope.launch { viewModel.exportStratumCSV(context) } }
                FormDivider()
                ExportActionRow("Plan GeoJSON") { scope.launch { viewModel.exportGeoJSON(context) } }
            }

            val folder = lastSessionFolder
            if (folder != null) {
                FormSection(header = "Last export folder") {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickableNoRipple { viewModel.shareURL.value = folder },
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text("Open", style = type.body, color = colors.primary)
                        Text(folder.name, style = type.caption, color = colors.textSecondary)
                    }
                }
            }

            if (exportedFiles.isNotEmpty()) {
                FormSection(header = "Recent files") {
                    exportedFiles.forEachIndexed { index, file ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickableNoRipple { viewModel.shareURL.value = file.url },
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(file.displayName, style = type.body, color = colors.primary)
                            Text(file.url.name, style = type.caption, color = colors.textSecondary)
                        }
                        if (index != exportedFiles.lastIndex) FormDivider()
                    }
                }
            }

            Spacer(Modifier.height(ForestixSpace.xl))
        }
    }

    // iOS "Something went wrong" alert.
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

// MARK: - Row helper

@Composable
private fun ExportActionRow(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val colors = Forestix.colors
    val tint = if (enabled) colors.primary else colors.textTertiary
    Row(
        Modifier
            .fillMaxWidth()
            .clickableNoRipple { if (enabled) onClick() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ForestixSpace.xs),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        }
        Text(label, style = Forestix.type.body, color = tint)
        if (trailing != null) {
            Spacer(Modifier.weight(1f))
            trailing()
        }
    }
}

// MARK: - Share helpers (shared with the cruise project sheet's Export all)

/// Zip a session folder's files into `<folder>.zip` beside it (still under
/// the FileProvider-visible Exports/ tree) so the whole export session can
/// ride one ACTION_SEND intent — the Android stand-in for iOS folder share.
internal fun zipFolderForShare(folder: File): File? {
    val files = folder.listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: return null
    if (files.isEmpty()) return null
    val entries = files.map { it.name to it.readBytes() }
    val zip = File(folder.parentFile, "${folder.name}.zip")
    zip.writeBytes(ZipWriter.storedArchive(entries))
    return zip
}

internal fun exportMimeFor(file: File): String = when (file.extension.lowercase(Locale.US)) {
    "csv" -> "text/csv"
    "geojson", "json" -> "application/geo+json"
    "zip" -> "application/zip"
    "pdf" -> "application/pdf"
    "gpx" -> "application/gpx+xml"
    else -> "application/octet-stream"
}
