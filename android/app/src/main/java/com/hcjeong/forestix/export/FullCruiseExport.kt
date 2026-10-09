// Port of iOS Export/FullCruiseExport.swift — Phase 6 orchestration,
// spec §8 & §9.2 "all three export formats produce valid files."
//
// Takes a fully-populated `ExportBundle` and writes every artefact for a
// single cruise-export session into a single timestamped folder. On
// Android the folder lives under the app cache dir (shared out through
// FileProvider, following the data/QuickMeasureHistory.kt writeExport
// pattern):
//
//   <cacheDir>/Exports/<project_name>_<yyyyMMdd_HHmmss>/
//
// Progress callbacks fire before each file so the UI can show a
// determinate progress bar.

package com.hcjeong.forestix.export

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.hcjeong.forestix.data.cruise.UnitSystem
import com.hcjeong.forestix.data.cruise.hasCentre
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class ExportArtefact(
    val url: File,
    val displayName: String,
    val kind: Kind,
) {
    val id: File get() = url

    /// Raw strings match the Swift `ExportArtefact.Kind` rawValues.
    enum class Kind(val raw: String) {
        CSV_TREES("csvTrees"),
        CSV_PLOTS("csvPlots"),
        CSV_STAND_SUMMARY("csvStandSummary"),
        CSV_STRATA("csvStrata"),
        CSV_PLANNED("csvPlanned"),
        GEOJSON_CRUISE("geojsonCruise"),
        GEOJSON_PLAN("geojsonPlan"),
        SHAPEFILE_PLOTS("shapefilePlots"),
        SHAPEFILE_PLANNED("shapefilePlanned"),
        SHAPEFILE_STRATA("shapefileStrata"),
        PDF_REPORT("pdfReport"),
        GPX_TRACK("gpxTrack"),
    }
}

data class FullCruiseExportResult(
    val folder: File,
    val artefacts: List<ExportArtefact>,
)

object FullCruiseExporter {

    /// Write the full cruise export bundle into a timestamped folder.
    /// `progress` fires with `(completedSteps, totalSteps, currentLabel)`.
    fun write(
        bundle: ExportBundle,
        into: File,
        /// The unit system the PDF REPORT is written in — the cruiser's live
        /// Units setting, not the stamp `project.units` took at creation. A
        /// report that does not say what the cruiser is looking at is not a
        /// deliverable; see `PDFReportInputs.displayUnits`. Defaults to the
        /// project's own stamp so a caller that has no settings to hand (tests,
        /// the CSV-only paths) keeps the historical output.
        displayUnits: UnitSystem = bundle.project.units,
        progress: ((Int, Int, String) -> Unit)? = null,
    ): FullCruiseExportResult {

        val folder = sessionFolder(
            base = into, projectName = bundle.project.name,
            generatedAt = bundle.generatedAt)

        // Steps (PDF writes last because it is the slowest). Order reflects
        // a natural progress sequence; items may be dropped if the cruise
        // has nothing to write for that kind (e.g. no strata ⇒ no polygon
        // shapefile).
        val steps = mutableListOf<Pair<String, ExportArtefact.Kind>>()
        steps.add("Trees CSV" to ExportArtefact.Kind.CSV_TREES)
        steps.add("Plots CSV" to ExportArtefact.Kind.CSV_PLOTS)
        steps.add("Stand summary CSV" to ExportArtefact.Kind.CSV_STAND_SUMMARY)
        steps.add("Strata CSV" to ExportArtefact.Kind.CSV_STRATA)
        steps.add("Planned plots CSV" to ExportArtefact.Kind.CSV_PLANNED)
        steps.add("Cruise GeoJSON" to ExportArtefact.Kind.GEOJSON_CRUISE)
        steps.add("Plan GeoJSON" to ExportArtefact.Kind.GEOJSON_PLAN)
        // A point layer needs at least one LOCATED plot. Plots whose centre
        // was never recorded (or was cleared from the map) are not features
        // in it, so a cruise of nothing but centre-less plots writes no
        // plot shapefile at all rather than a layer of (0,0) points.
        if (bundle.plots.any { it.hasCentre }) {
            steps.add("Plot centres shapefile" to ExportArtefact.Kind.SHAPEFILE_PLOTS)
        }
        if (bundle.plannedPlots.isNotEmpty()) {
            steps.add("Planned plots shapefile" to ExportArtefact.Kind.SHAPEFILE_PLANNED)
        }
        if (bundle.strata.isNotEmpty()) {
            steps.add("Strata shapefile" to ExportArtefact.Kind.SHAPEFILE_STRATA)
        }
        steps.add("PDF report" to ExportArtefact.Kind.PDF_REPORT)

        val artefacts = mutableListOf<ExportArtefact>()
        val total = steps.size

        for ((i, step) in steps.withIndex()) {
            progress?.invoke(i, total, step.first)
            writeOne(step.second, folder, bundle, displayUnits)?.let { artefacts.add(it) }
        }
        progress?.invoke(total, total, "Done")
        return FullCruiseExportResult(folder = folder, artefacts = artefacts)
    }

    /// Convenience wrapper following the QuickMeasureHistory cache-dir
    /// pattern: writes under `<cacheDir>/Exports/...`.
    fun writeToCache(
        context: Context,
        bundle: ExportBundle,
        displayUnits: UnitSystem = bundle.project.units,
        progress: ((Int, Int, String) -> Unit)? = null,
    ): FullCruiseExportResult =
        write(bundle, into = context.cacheDir, displayUnits = displayUnits, progress = progress)

    /// Content URI for sharing one artefact through the manifest
    /// FileProvider (same authority QuickMeasureHistory.writeExport uses).
    fun shareUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    // MARK: - Per-artefact writers

    private fun writeOne(
        kind: ExportArtefact.Kind,
        folder: File,
        bundle: ExportBundle,
        displayUnits: UnitSystem,
    ): ExportArtefact? = when (kind) {
        ExportArtefact.Kind.CSV_TREES -> {
            val csv = CSVExporter.treesCSV(trees = bundle.trees)
            writeData(csv.toByteArray(Charsets.UTF_8), name = "trees.csv",
                display = "Trees (CSV)", kind = kind, folder = folder)
        }
        ExportArtefact.Kind.CSV_PLOTS -> {
            val csv = CSVExporter.plotsCSV(
                plots = bundle.plots, statsByPlot = bundle.plotStatsByPlot)
            writeData(csv.toByteArray(Charsets.UTF_8), name = "plots.csv",
                display = "Plots (CSV)", kind = kind, folder = folder)
        }
        ExportArtefact.Kind.CSV_STAND_SUMMARY -> {
            val csv = CSVExporter.standSummaryCSV(
                tpa = bundle.tpaStand, ba = bundle.baStand,
                volume = bundle.volStand,
                stratumNamesByKey = bundle.stratumNamesByKey)
            writeData(csv.toByteArray(Charsets.UTF_8), name = "stand-summary.csv",
                display = "Stand summary (CSV)", kind = kind, folder = folder)
        }
        ExportArtefact.Kind.CSV_STRATA -> {
            val csv = CSVExporter.stratumListCSV(strata = bundle.strata)
            writeData(csv.toByteArray(Charsets.UTF_8), name = "strata.csv",
                display = "Strata (CSV)", kind = kind, folder = folder)
        }
        ExportArtefact.Kind.CSV_PLANNED -> {
            val csv = CSVExporter.plannedPlotsCSV(
                plannedPlots = bundle.plannedPlots, strata = bundle.strata)
            writeData(csv.toByteArray(Charsets.UTF_8), name = "planned-plots.csv",
                display = "Planned plots (CSV)", kind = kind, folder = folder)
        }
        ExportArtefact.Kind.GEOJSON_CRUISE -> {
            val s = GeoJSONExporter.cruise(
                strata = bundle.strata,
                plannedPlots = bundle.plannedPlots,
                plots = bundle.plots)
            writeData(s.toByteArray(Charsets.UTF_8), name = "cruise.geojson",
                display = "Cruise (GeoJSON)", kind = kind, folder = folder)
        }
        ExportArtefact.Kind.GEOJSON_PLAN -> {
            val s = GeoJSONExporter.plan(
                strata = bundle.strata,
                plannedPlots = bundle.plannedPlots)
            writeData(s.toByteArray(Charsets.UTF_8), name = "plan.geojson",
                display = "Plan (GeoJSON)", kind = kind, folder = folder)
        }
        ExportArtefact.Kind.SHAPEFILE_PLOTS -> {
            try {
                val data = ShapefileExporter.plotCentersZip(plots = bundle.plots)
                writeData(data, name = "plots-shp.zip",
                    display = "Plot centres (Shapefile)", kind = kind, folder = folder)
            } catch (_: ShapefileExporterError.EmptyLayer) {
                null  // No plot has a centre — skip, same as the strata layer.
            }
        }
        ExportArtefact.Kind.SHAPEFILE_PLANNED -> {
            val data = ShapefileExporter.plannedPlotsZip(
                plannedPlots = bundle.plannedPlots)
            writeData(data, name = "planned-shp.zip",
                display = "Planned plots (Shapefile)", kind = kind, folder = folder)
        }
        ExportArtefact.Kind.SHAPEFILE_STRATA -> {
            try {
                val data = ShapefileExporter.strataZip(strata = bundle.strata)
                writeData(data, name = "strata-shp.zip",
                    display = "Strata (Shapefile)", kind = kind, folder = folder)
            } catch (_: ShapefileExporterError.EmptyLayer) {
                null  // All strata had invalid geometry — silently skip.
            }
        }
        ExportArtefact.Kind.PDF_REPORT -> {
            val inputs = PDFReportInputs(
                project = bundle.project,
                design = bundle.design,
                strata = bundle.strata,
                species = bundle.species,
                plots = bundle.plots,
                trees = bundle.trees,
                plotStatsByPlot = bundle.plotStatsByPlot,
                tpaStand = bundle.tpaStand,
                baStand = bundle.baStand,
                volStand = bundle.volStand,
                generatedAt = bundle.generatedAt,
                displayUnits = displayUnits)
            val url = File(folder, "report.pdf")
            PDFReportBuilder.write(inputs, to = url)
            ExportArtefact(url = url, displayName = "PDF report", kind = kind)
        }
        ExportArtefact.Kind.GPX_TRACK ->
            null  // Reserved for a later pass; GPXExporter needs track data.
    }

    // MARK: - Folder + file helpers

    /// Produces `<baseFolder>/Exports/<sanitizedProjectName>_<UTC timestamp>`.
    fun sessionFolder(
        base: File,
        projectName: String,
        generatedAt: Long,
    ): File {
        val exports = File(base, "Exports")
        val sanitized = sanitizeForFilename(projectName)
        val stamp = folderTimestamp(generatedAt)
        val folder = File(exports, "${sanitized}_$stamp")
        folder.mkdirs()
        return folder
    }

    private fun folderTimestamp(epochMs: Long): String {
        val f = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date(epochMs))
    }

    private fun writeData(
        data: ByteArray, name: String, display: String,
        kind: ExportArtefact.Kind, folder: File,
    ): ExportArtefact {
        val url = File(folder, name)
        url.writeBytes(data)
        return ExportArtefact(url = url, displayName = display, kind = kind)
    }

    private fun sanitizeForFilename(s: String): String {
        val out = StringBuilder()
        for (c in s) {
            if (c.isLetterOrDigit() || c == '-' || c == '_') out.append(c)
            else out.append('-')
        }
        val collapsed = out.toString().replace(Regex("-+"), "-")
        return collapsed.ifEmpty { "project" }
    }
}
