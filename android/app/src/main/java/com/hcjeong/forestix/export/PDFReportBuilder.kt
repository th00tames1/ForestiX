// Port of iOS Export/PDFReportBuilder.swift (spec §8 — Phase 6).
//
// The iOS builder renders with Core Graphics; here android.graphics.pdf.
// PdfDocument + Canvas do the same job. Layout is an approximation of the
// iOS report (Android canvases are top-down where CG PDF pages are
// bottom-up), but every section, heading, table, and formatted value is
// content-identical.
//
// ## What the report contains
//
//   Page 1 — Cover
//     Project name, owner, export timestamp, total n_plots, total area,
//     dominant species (top 3 by BA).
//
//   Page 2 — Stand summary
//     Stratified stats (TPA, BA/ac, V/ac), a "BA by stratum" bar chart,
//     species composition (top 8 by BA).
//
//   Page 3..N — Per-plot pages
//     For each closed plot: plot number, tier, area, stats, per-species
//     breakdown table.
//
//   Page N+1 — Methodology
//     Cruise design (plot type, area, sampling scheme), subsample rule,
//     breast-height convention, calibration meta (LiDAR bias, depth
//     noise, VIO drift fraction).
//
//   Appendix — Tree-level raw table, paginated.

package com.hcjeong.forestix.export

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.hcjeong.forestix.common.AreaUnit
import com.hcjeong.forestix.common.MeasurementFormatter
import com.hcjeong.forestix.common.Units
import com.hcjeong.forestix.common.RegionalSpecies
import com.hcjeong.forestix.data.cruise.BreastHeightConvention
import com.hcjeong.forestix.data.cruise.CruiseDesign
import com.hcjeong.forestix.data.cruise.HeightSubsampleRule
import com.hcjeong.forestix.data.cruise.Plot
import com.hcjeong.forestix.data.cruise.PlotType
import com.hcjeong.forestix.data.cruise.Project
import com.hcjeong.forestix.data.cruise.SamplingScheme
import com.hcjeong.forestix.data.cruise.SpeciesConfig
import com.hcjeong.forestix.data.cruise.Stratum
import com.hcjeong.forestix.data.cruise.Tree
import com.hcjeong.forestix.data.cruise.TreeStatus
import com.hcjeong.forestix.data.cruise.UnitSystem
import com.hcjeong.forestix.data.cruise.hasCentre
import com.hcjeong.forestix.inventory.PlotStats
import com.hcjeong.forestix.sensors.ConfidenceTier
import com.hcjeong.forestix.inventory.StandStat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

data class PDFReportInputs(
    val project: Project,
    val design: CruiseDesign,
    val strata: List<Stratum>,
    val species: List<SpeciesConfig>,
    val plots: List<Plot>,
    val trees: List<Tree>,             // include deleted for appendix completeness
    val plotStatsByPlot: Map<UUID, PlotStats>,
    val tpaStand: StandStat,
    val baStand: StandStat,
    val volStand: StandStat,
    val generatedAt: Long,
    /// The unit system the REPORT is written in — the cruiser's live Units
    /// setting, not `project.units`.
    ///
    /// `project.units` is stamped once when the project is created and never
    /// written again. Every screen reads the live toggle, so a cruiser who
    /// flipped Units saw "28.4 m²/ha" on the phone and got "11.49 m²/ac" in the
    /// report for the same plot — numbers 2.47x apart, with the cover page
    /// still naming the system they had left behind. A report that does not say
    /// what the cruiser is looking at is not a deliverable.
    ///
    /// Defaults to imperial so a caller that never sets it gets the historical
    /// per-acre output rather than a surprise. iOS `PDFLocalization` 1:1.
    val displayUnits: UnitSystem = UnitSystem.IMPERIAL,
)

sealed class PDFReportBuilderError(message: String) : Exception(message) {
    class ContextCreationFailed : PDFReportBuilderError("Failed to create canvas for PDF")
    class WriteFailed(m: String) : PDFReportBuilderError("Failed to write PDF: $m")
}

// US Letter, PostScript points (PdfDocument PageInfo is 1/72" units).
private const val PAGE_WIDTH = 612
private const val PAGE_HEIGHT = 792

object PDFReportBuilder {

    /// Render a full cruise report to a PDF file at the supplied location.
    /// Returns the number of pages written so the caller can sanity-check.
    fun write(inputs: PDFReportInputs, to: File): Int {
        val doc = PdfDocument()
        try {
            val pageCount = render(inputs, doc)
            try {
                FileOutputStream(to).use { doc.writeTo(it) }
            } catch (e: IOException) {
                throw PDFReportBuilderError.WriteFailed(e.toString())
            }
            return pageCount
        } finally {
            doc.close()
        }
    }

    /// Render to an in-memory byte blob, used by tests.
    fun data(inputs: PDFReportInputs): Pair<ByteArray, Int> {
        val doc = PdfDocument()
        try {
            val pageCount = render(inputs, doc)
            val out = ByteArrayOutputStream()
            doc.writeTo(out)
            return out.toByteArray() to pageCount
        } finally {
            doc.close()
        }
    }

    // MARK: - Rendering orchestration

    private fun render(inputs: PDFReportInputs, doc: PdfDocument): Int {
        var pages = 0
        val pager = Pager(doc)

        pager.newPage { c, f -> drawCover(inputs, f, c) }; pages += 1
        pager.newPage { c, f -> drawStandSummary(inputs, f, c) }; pages += 1

        // Per-plot pages — one page per closed plot for readability.
        val closed = inputs.plots
            .filter { it.closedAt != null }
            .sortedBy { it.plotNumber }
        for (plot in closed) {
            pager.newPage { c, f -> drawPlotPage(inputs, plot, f, c) }
            pages += 1
        }

        pager.newPage { c, f -> drawMethodology(inputs, f, c) }; pages += 1

        // Appendix: tree-level raw table, paginated.
        val treeChunks = chunkTreesForAppendix(inputs.trees)
        for ((idx, chunk) in treeChunks.withIndex()) {
            pager.newPage { c, f ->
                drawTreeAppendix(inputs,
                    page = idx + 1,
                    totalPages = treeChunks.size,
                    rows = chunk,
                    frame = f,
                    canvas = c)
            }
            pages += 1
        }

        return pages
    }

    // MARK: - Page layouts

    private fun drawCover(inputs: PDFReportInputs, frame: RectF, canvas: Canvas) {
        drawTitle(canvas, "Forestix Cruise Report", frame.left, frame.top + 80f, frame.width())
        drawSubtitle(canvas, inputs.project.name, frame.left, frame.top + 120f, frame.width())
        var y = frame.top + 180f
        fun kv(k: String, v: String) {
            drawKeyValue(canvas, k, v, frame.left, y, frame.width())
            y += 22f
        }
        val areaUnit = areaUnitFor(inputs)
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)  // local time zone
        kv("Owner",             inputs.project.owner)
        // WHAT THE BODY IS ACTUALLY IN, not the stamp taken when the project
        // was created. `project.units` is written once and never again; the
        // display system is the cruiser's live setting, and it is what every
        // number under this line is rendered with. The cover cannot be allowed
        // to declare one system while the tables use the other.
        kv("Units",             unitsLabel(inputs.displayUnits))
        kv("Generated",         df.format(Date(inputs.generatedAt)))
        kv("# plots (closed)",  "${inputs.plots.count { it.closedAt != null }}")
        kv("# plots (total)",   "${inputs.plots.size}")
        val totalAreaAc = inputs.strata.fold(0f) { acc, s -> acc + s.areaAcres }
        kv("Total area",        "${String.format(Locale.US, "%.2f", areaUnit.fromAcres(totalAreaAc.toDouble()))} ${areaUnit.abbreviation}")
        kv("# strata",          "${inputs.strata.size}")
        kv("# species",         "${inputs.species.size}")
        kv("# volume equations", "${inputs.species.map { it.volumeEquationId }.toSet().size}")

        // Dominant species by basal area across stand.
        y += 20f
        drawHeading(canvas, "Dominant species (by basal area)", frame.left, y, frame.width())
        y += 22f
        val byCode = speciesBAAcrossStand(inputs.plotStatsByPlot)
        val top3 = byCode.entries.sortedByDescending { it.value }.take(3)
        if (top3.isEmpty()) {
            drawBody(canvas, "(no tallied species)", frame.left, y, frame.width())
        } else {
            for ((code, ba) in top3) {
                val name = inputs.species.firstOrNull { it.code == code }?.commonName ?: code
                drawBody(canvas,
                    "$code — $name: ${String.format(Locale.US, "%.3f", ba * basalAreaFactor(areaUnit))} " +
                        MeasurementFormatter.basalAreaDensityUnit(areaUnit),
                    frame.left + 12f, y, frame.width())
                y += 18f
            }
        }

        drawFooter(canvas, "Forestix • confidential cruise output", frame)
    }

    private fun drawStandSummary(inputs: PDFReportInputs, frame: RectF, canvas: Canvas) {
        drawTitle(canvas, "Stand Summary", frame.left, frame.top + 50f, frame.width())

        var y = frame.top + 100f

        // Per-area basis follows the project's units (US acre, metric hectare).
        // Engine stats are per acre; scale + relabel at display only.
        val areaUnit = areaUnitFor(inputs)
        val f = areaUnit.perAcreDensityFactor
        val suffix = areaUnit.densitySuffix
        val areaWord = if (areaUnit == AreaUnit.HECTARE) "hectare" else "acre"

        // Stratified stats table — three metrics × (mean, SE, CI95, df).
        drawHeading(canvas, "Stand statistics", frame.left, y, frame.width())
        y += 22f
        // Basal area gets its OWN factor: the engine's figure is m² per acre,
        // so the numerator converts too. Mean and 95 % half-width are scaled by
        // the same number, or the range stops bracketing the average.
        val baUnit = basalAreaUnit(areaUnit)
        val baStandScaled = inputs.baStand.scaledPerArea(basalAreaFactor(areaUnit))
        val metricRows = listOf(
            Triple("Trees per $areaWord", inputs.tpaStand.scaledPerArea(f), "trees$suffix"),
            Triple("Basal area",          baStandScaled,                    "$baUnit$suffix"),
            // And volume gets its own for the same reason — m³ per acre is
            // half a metric fraction, and both halves turn together.
            Triple("Gross volume",
                inputs.volStand.scaledPerArea(volumeFactor(areaUnit)),
                "${volumeUnit(areaUnit)}$suffix"),
        )
        // The standard-error and Satterthwaite effective-degrees-of-freedom
        // columns are gone: this page is read by a landowner, and neither is a
        // figure anyone acts on. The ± 95% range carries the precision.
        val colWidths = listOf(140f, 80f, 90f, 100f, 50f)
        drawTableRow(canvas,
            listOf("Measure", "Unit", "Average", "± 95% range", "Plots"),
            bold = true, frame.left, y, colWidths)
        y += 18f
        for ((name, stat, unit) in metricRows) {
            drawTableRow(canvas, listOf(
                name, unit,
                String.format(Locale.US, "%.3f", stat.mean),
                String.format(Locale.US, "%.3f", stat.ci95HalfWidth),
                "${stat.nPlots}",
            ), bold = false, frame.left, y, colWidths)
            y += 16f
        }

        // Basal area by stratum bar chart.
        y += 30f
        drawHeading(canvas, "Basal area by stratum ($baUnit$suffix)", frame.left, y, frame.width())
        y += 18f
        val strataBars = baStandScaled.byStratum.entries
            .sortedBy { it.key }
            .map { it.key to it.value.mean }
        val chartRect = RectF(frame.left, y + 10f, frame.left + frame.width(), y + 140f)
        drawBarChart(canvas,
            values = strataBars.map { it.second },
            labels = strataBars.map { shortLabel(stratumName(inputs, it.first)) },
            rect = chartRect)
        y = chartRect.bottom + 20f

        // Species composition.
        drawHeading(canvas, "Species composition (top 8 by basal area)", frame.left, y, frame.width())
        y += 18f
        val byCode = speciesBAAcrossStand(inputs.plotStatsByPlot)
        val top8 = byCode.entries.sortedByDescending { it.value }.take(8)
        val spLabels: List<String> = top8.map { speciesLabel(inputs, it.key) }
        val spValues: List<Double> = top8.map { it.value.toDouble() * f }
        val spRect = RectF(frame.left, y + 10f, frame.left + frame.width(), y + 120f)
        drawBarChart(canvas, values = spValues, labels = spLabels, rect = spRect)

        drawFooter(canvas, "Stand summary", frame)
    }

    private fun drawPlotPage(inputs: PDFReportInputs, plot: Plot, frame: RectF, canvas: Canvas) {
        drawTitle(canvas, "Plot ${plot.plotNumber}", frame.left, frame.top + 50f, frame.width())
        var y = frame.top + 90f
        fun kv(k: String, v: String) {
            drawKeyValue(canvas, k, v, frame.left, y, frame.width())
            y += 18f
        }
        // Per-area basis follows the project's units (US acre, metric hectare).
        val areaUnit = areaUnitFor(inputs)
        val f = areaUnit.perAcreDensityFactor
        val suffix = areaUnit.densitySuffix
        val areaWord = if (areaUnit == AreaUnit.HECTARE) "hectare" else "acre"
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)  // local time zone
        // A plot with no recorded centre says so IN WORDS — "not recorded",
        // the settled wording on both platforms. Printing the (0,0) sentinel
        // put "0.000000, 0.000000" in the document handed to the client — a
        // coordinate off West Africa, indistinguishable from a real one.
        // Everything else on this page (the tally, the stats, the species
        // table) is unaffected.
        //
        // Words, not the em dash the other "absent" rows use: those are
        // fields that simply have no value yet, while a plot without a
        // centre is a FACT about the plot, and the reader of a cruise
        // report should not have to infer it from a punctuation mark.
        kv("Center",
            if (plot.hasCentre) {
                String.format(Locale.US, "%.6f, %.6f", plot.centerLat, plot.centerLon)
            } else {
                "not recorded"
            })
        // "Position tier" (tierB) and "Source" (vioWalk) printed the raw enum
        // cases of the A/B/C/D position grade that was pulled from the UI for
        // being unactionable — in the document handed to the client. Both
        // fields are UNCHANGED on the Plot and in the CSV export; they are
        // simply not printed here. What is left is the accuracy, as a quantity.
        // The only statement in the report of how well the plot was located —
        // in the same unit as everything else the reader has in front of them.
        kv("GPS accuracy",  String.format(Locale.US, "±%.2f %s, averaged over %d fixes",
                                lengthFromMetres(inputs, plot.gpsMedianHAccuracyM.toDouble()),
                                lengthUnit(inputs), plot.gpsNSamples))
        kv("Plot area",     "${String.format(Locale.US, "%.3f", areaUnit.fromAcres(plot.plotAreaAcres.toDouble()))} ${areaUnit.abbreviation}")
        kv("Slope/Aspect",  "${String.format(Locale.US, "%.1f", plot.slopeDeg)}° / ${String.format(Locale.US, "%.0f", plot.aspectDeg)}°")
        kv("Started",       df.format(Date(plot.startedAt)))
        kv("Closed",        plot.closedAt?.let { df.format(Date(it)) } ?: "—")
        kv("Closed by",     plot.closedBy ?: "—")

        y += 12f
        drawHeading(canvas, "Live stats", frame.left, y, frame.width())
        y += 18f
        val s = inputs.plotStatsByPlot[plot.id]
        if (s != null) {
            kv("Live trees",          "${s.liveTreeCount}")
            kv("Trees per $areaWord", String.format(Locale.US, "%.2f trees$suffix", s.tpa * f))
            kv("Basal area",          String.format(Locale.US, "%.4f %s$suffix",
                                          s.baPerAcreM2 * basalAreaFactor(areaUnit),
                                          basalAreaUnit(areaUnit)))
            kv("Quadratic mean DBH",  String.format(Locale.US, "%.2f %s",
                                          diameterFromCm(inputs, s.qmdCm.toDouble()),
                                          diameterUnit(inputs)))
            kv("Gross volume",        String.format(Locale.US, "%.4f %s$suffix",
                                          s.grossVolumePerAcreM3 * volumeFactor(areaUnit),
                                          volumeUnit(areaUnit)))
            kv("Merchantable volume", String.format(Locale.US, "%.4f %s$suffix",
                                          s.merchVolumePerAcreM3 * volumeFactor(areaUnit),
                                          volumeUnit(areaUnit)))
        } else {
            drawBody(canvas, "(no stats available)", frame.left, y, frame.width())
            y += 18f
        }

        // Per-species breakdown.
        y += 12f
        drawHeading(canvas, "Per-species breakdown", frame.left, y, frame.width())
        y += 18f
        val colWidths = listOf(80f, 50f, 90f, 110f, 110f)
        drawTableRow(canvas,
            listOf("Species", "n", "Trees$suffix",
                "Basal ${basalAreaUnit(areaUnit)}$suffix",
                "Volume ${volumeUnit(areaUnit)}$suffix"),
            bold = true, frame.left, y, colWidths)
        y += 16f
        if (s != null) {
            val sortedCodes = s.bySpecies.keys.sorted()
            for (code in sortedCodes) {
                val ss = s.bySpecies[code] ?: continue
                drawTableRow(canvas, listOf(
                    speciesLabel(inputs, code), "${ss.count}",
                    String.format(Locale.US, "%.2f", ss.tpa * f),
                    String.format(Locale.US, "%.4f", ss.baPerAcreM2 * basalAreaFactor(areaUnit)),
                    String.format(Locale.US, "%.4f", ss.grossVolumePerAcreM3 * volumeFactor(areaUnit)),
                ), bold = false, frame.left, y, colWidths)
                y += 16f
            }
        }

        drawFooter(canvas, "Plot ${plot.plotNumber}", frame)
    }

    private fun drawMethodology(inputs: PDFReportInputs, frame: RectF, canvas: Canvas) {
        drawTitle(canvas, "Methodology", frame.left, frame.top + 50f, frame.width())
        var y = frame.top + 100f
        fun kv(k: String, v: String) {
            drawKeyValue(canvas, k, v, frame.left, y, frame.width())
            y += 18f
        }
        val areaUnit = areaUnitFor(inputs)
        kv("Plot type",         plotTypeLabel(inputs.design.plotType))
        kv("Plot area",         inputs.design.plotAreaAcres?.let {
                                    if (areaUnit == AreaUnit.HECTARE)
                                        String.format(Locale.US, "%.3f ha", areaUnit.fromAcres(it.toDouble()))
                                    else "$it ac"
                                } ?: "—")
        // The stored BAF is ft²/ac (see `CruiseDesign.baf`), and the row now
        // says so. Printed bare, a "20" on a client report is a number the
        // reader cannot check the stand table against.
        // STORED IN ft²/ac, PRINTED IN THE READER'S OWN. A metric prism is a
        // round 4 m²/ha, which is 17.424215 stored — interpolating the raw
        // Float put exactly that on a client report. It converts and it
        // rounds, like every other number on this page.
        kv("Basal area factor", inputs.design.baf?.let {
            String.format(Locale.US, "%.4g %s",
                bafFromStored(inputs, it.toDouble()), bafLabel(inputs))
        } ?: "—")
        kv("Sampling scheme",   samplingSchemeLabel(inputs.design.samplingScheme))
        // Stored in metres; printed in the reader's unit, like the plot area
        // directly above it. Left bare it was the one row on this page that
        // stayed metric on an imperial report.
        kv("Grid spacing",      inputs.design.gridSpacingMeters?.let {
            String.format(Locale.US, "%.1f %s",
                lengthFromMetres(inputs, it.toDouble()), lengthUnit(inputs))
        } ?: "—")
        kv("Height subsample",  describeSubsample(inputs.design.heightSubsampleRule))
        kv("Breast height",     breastHeightLabel(inputs.project.breastHeightConvention))
        kv("Slope correction",  if (inputs.project.slopeCorrection) "on" else "off")
        y += 12f

        drawHeading(canvas, "Calibration", frame.left, y, frame.width())
        y += 18f
        // The four device internals that used to print here — a sensor bias, a
        // raw σ, the two Greek correction coefficients and the visual-odometry
        // drift term — are interpretable by neither a cruiser nor a client.
        // Every one of them still ships in full in the CSV export.
        kv("Device calibration", if (inputs.project.lidarBiasMm != 0f ||
                                     inputs.project.dbhCorrectionBeta != 1f) {
                                     "Wall and round-post calibration applied"
                                 } else {
                                     "Not calibrated on this device"
                                 })

        y += 12f
        drawHeading(canvas, "Species list (${inputs.species.size})", frame.left, y, frame.width())
        y += 18f
        val colWidths = listOf(50f, 150f, 110f, 110f, 75f)
        // Both dimensions are stored in centimetres and both are printed in
        // the reader's unit — header and cells move together, so the column
        // can never be labelled one way and filled the other.
        val dia = diameterUnit(inputs)
        drawTableRow(canvas,
            listOf("Code", "Common name", "Volume equation",
                   "Merch. top dia. ($dia)", "Stump ($dia)"),
            bold = true, frame.left, y, colWidths)
        y += 16f
        for (sp in inputs.species.sortedBy { it.code }.take(20)) {
            drawTableRow(canvas, listOf(
                sp.code,
                sp.commonName,
                sp.volumeEquationId,
                String.format(Locale.US, "%.1f",
                    diameterFromCm(inputs, sp.merchTopDibCm.toDouble())),
                String.format(Locale.US, "%.1f",
                    diameterFromCm(inputs, sp.stumpHeightCm.toDouble())),
            ), bold = false, frame.left, y, colWidths)
            y += 16f
        }

        drawFooter(canvas, "Methodology", frame)
    }

    private fun drawTreeAppendix(
        inputs: PDFReportInputs,
        page: Int, totalPages: Int,
        rows: List<Tree>,
        frame: RectF,
        canvas: Canvas,
    ) {
        drawTitle(canvas, "Appendix — tree-level (page $page/$totalPages)",
            frame.left, frame.top + 50f, frame.width())
        var y = frame.top + 90f
        // The two measurement columns are headed AND filled in the reader's
        // unit. Headed "DBH cm" over raw centimetres on a report whose cover
        // declared the cruise Imperial, the landowner had to know to divide by
        // 2.54 and nothing in the document said so.
        val headers = listOf(
            "Plot", "Tree", "Species",
            "DBH ${diameterUnit(inputs)}", "Height ${lengthUnit(inputs)}",
            "Status", "Quality", "Flags")
        val widths = listOf(40f, 40f, 55f, 50f, 55f, 60f, 50f, 90f)
        drawTableRow(canvas, headers, bold = true, frame.left, y, widths)
        y += 16f

        val plotNumberById = inputs.plots.associate { it.id to it.plotNumber }

        for (t in rows) {
            val pno = plotNumberById[t.plotId]?.let { "$it" } ?: "?"
            // Spelled out. "del / ms / irr" was three codes with no key
            // anywhere in the document.
            // "Deleted", not "Removed": the same flag prints "Deleted" in the
            // iOS appendix and the CSV column beside it is `deleted_at`, so
            // one word means one thing across both platforms and both files.
            val flagBits = listOfNotNull(
                if (t.deletedAt != null) "Deleted" else null,
                if (t.isMultistem) "Multistem" else null,
                if (t.dbhIsIrregular) "Irregular" else null,
            )
            drawTableRow(canvas, listOf(
                pno, "${t.treeNumber}", speciesLabel(inputs, t.speciesCode),
                String.format(Locale.US, "%.1f", diameterFromCm(inputs, t.dbhCm.toDouble())),
                // Two decimals, matching every on-screen height readout —
                // the appendix is what the client checks the app against.
                t.heightM?.let {
                    String.format(Locale.US, "%.2f", lengthFromMetres(inputs, it.toDouble()))
                } ?: "—",
                statusLabel(t.status),
                qualityLabel(t.dbhConfidence),
                flagBits.joinToString(", "),
            ), bold = false, frame.left, y, widths)
            y += 13f
            if (y > frame.bottom - 60f) break
        }

        drawFooter(canvas, "Tree appendix", frame)
    }

    // MARK: - Helper draws (text + layout)

    private fun drawTitle(canvas: Canvas, text: String, x: Float, y: Float, width: Float) {
        drawText(canvas, text, x, y, width, fontSize = 24f, bold = true)
    }

    private fun drawSubtitle(canvas: Canvas, text: String, x: Float, y: Float, width: Float) {
        drawText(canvas, text, x, y, width, fontSize = 16f, bold = false)
    }

    private fun drawHeading(canvas: Canvas, text: String, x: Float, y: Float, width: Float) {
        drawText(canvas, text, x, y, width, fontSize = 13f, bold = true)
    }

    private fun drawBody(canvas: Canvas, text: String, x: Float, y: Float, width: Float) {
        drawText(canvas, text, x, y, width, fontSize = 11f, bold = false)
    }

    private fun drawKeyValue(canvas: Canvas, k: String, v: String, x: Float, y: Float, width: Float) {
        drawText(canvas, k, x, y, 200f, fontSize = 11f, bold = true)
        drawText(canvas, v, x + 200f, y, width - 200f, fontSize = 11f, bold = false)
    }

    private fun drawTableRow(
        canvas: Canvas, cells: List<String>, bold: Boolean,
        originX: Float, y: Float, colWidths: List<Float>,
    ) {
        var x = originX
        for ((i, c) in cells.withIndex()) {
            val w = colWidths.getOrElse(i) { 60f }
            drawText(canvas, c, x, y, w, fontSize = 10f, bold = bold)
            x += w
        }
    }

    private fun drawFooter(canvas: Canvas, text: String, frame: RectF) {
        drawText(canvas, text, frame.left, frame.bottom - 20f, frame.width(),
            fontSize = 9f, bold = false)
    }

    /// Draw a single line of text whose *top* sits at `y`, clipped to
    /// `width` like the iOS CoreText frame path.
    private fun drawText(
        canvas: Canvas, text: String,
        x: Float, y: Float, width: Float,
        fontSize: Float, bold: Boolean,
    ) {
        if (text.isEmpty()) return
        val paint = textPaint(fontSize, bold)
        canvas.save()
        canvas.clipRect(x, y - 2f, x + width, y + fontSize * 1.4f + 2f)
        canvas.drawText(text, x, y + fontSize, paint)
        canvas.restore()
    }

    private fun textPaint(fontSize: Float, bold: Boolean): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = fontSize
            typeface = if (bold) Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                       else Typeface.SANS_SERIF
            color = Color.BLACK
        }

    // MARK: - Bar chart

    private fun drawBarChart(
        canvas: Canvas,
        values: List<Double>,
        labels: List<String>,
        rect: RectF,
    ) {
        if (values.isEmpty()) return
        val maxV = max(values.maxOrNull() ?: 1.0, 0.0001)
        val barArea = RectF(rect.left + 10f, rect.top + 20f, rect.right - 10f, rect.bottom - 20f)
        val barCount = values.size.toFloat()
        val gap = 6f
        val barW = (barArea.width() - gap * (barCount - 1)) / barCount
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(77, 77, 77)          // CG gray 0.3
            style = Paint.Style.FILL
        }
        val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(153, 153, 153)       // CG gray 0.6
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }
        // Axis baseline
        canvas.drawLine(barArea.left, barArea.bottom, barArea.right, barArea.bottom, axis)

        for ((i, v) in values.withIndex()) {
            val h = ((v / maxV) * barArea.height()).toFloat()
            val x = barArea.left + i * (barW + gap)
            canvas.drawRect(x, barArea.bottom - h, x + barW, barArea.bottom, fill)

            // Value label on top.
            drawText(canvas, String.format(Locale.US, "%.2f", v),
                x, barArea.bottom - h - 12f, barW, fontSize = 8f, bold = false)
            // Category label below axis.
            drawText(canvas, labels.getOrElse(i) { "" },
                x, barArea.bottom + 2f, barW, fontSize = 8f, bold = false)
        }
    }

    // MARK: - Localization helpers

    /// The per-area basis the report renders in, from the DISPLAY unit system
    /// the caller passed (metric → hectares, imperial → acres) rather than the
    /// project's creation-time stamp.
    private fun areaUnitFor(inputs: PDFReportInputs): AreaUnit =
        if (inputs.displayUnits == UnitSystem.METRIC) AreaUnit.HECTARE else AreaUnit.ACRE

    // MARK: Linear units (the other half of the same setting)

    /// The report used to convert only the DENOMINATOR of its densities and
    /// leave every LENGTH in the engine's metric base, so a cover page that
    /// declared the cruise Imperial was followed by a tree appendix headed
    /// "DBH cm" / "Height m" with raw centimetres and metres in the cells. The
    /// landowner had to know to divide by 2.54, and nothing in the document
    /// said so. These turn the linear half of the same setting, so the
    /// declaration on the cover and every number under it agree.
    ///
    /// The STORED values are untouched — the CSVs beside this PDF still carry
    /// cm and m, which is where a pipeline joins on them.

    private fun diameterUnit(inputs: PDFReportInputs): String =
        if (inputs.displayUnits == UnitSystem.METRIC) "cm" else "in"

    private fun lengthUnit(inputs: PDFReportInputs): String =
        if (inputs.displayUnits == UnitSystem.METRIC) "m" else "ft"

    private fun diameterFromCm(inputs: PDFReportInputs, cm: Double): Double =
        if (inputs.displayUnits == UnitSystem.METRIC) cm else Units.cmToInches(cm)

    /// A BASAL AREA FACTOR IS THE ONE STORED NUMBER THAT IS NOT METRIC. It is
    /// what is etched on the prism, and every project on disk was typed under
    /// the US convention, so `CruiseDesign.baf` is ft²/ac wherever it appears.
    /// A metric cruiser's prism is a round 4 m²/ha, which is 17.424215 stored
    /// — and the report interpolated that Float straight into the page.
    private fun bafLabel(inputs: PDFReportInputs): String =
        if (inputs.displayUnits == UnitSystem.METRIC) "m²/ha" else "ft²/ac"

    private fun bafFromStored(inputs: PDFReportInputs, ft2PerAcre: Double): Double =
        if (inputs.displayUnits == UnitSystem.METRIC) Units.baPerAcreToBaPerHa(ft2PerAcre)
        else ft2PerAcre

    private fun lengthFromMetres(inputs: PDFReportInputs, m: Double): Double =
        if (inputs.displayUnits == UnitSystem.METRIC) m else Units.metersToFeet(m)

    /// Basal area arrives as SQUARE METRES per ACRE. Both halves of that
    /// fraction convert or neither is right: an imperial report that scaled
    /// only the denominator printed "m²/ac", a unit no cruise sheet uses and
    /// 10.76x away from the ft²/ac the app shows for the same stand.
    private fun basalAreaUnit(unit: AreaUnit): String =
        MeasurementFormatter.basalAreaNumeratorUnit(unit)

    private fun basalAreaFactor(unit: AreaUnit): Double =
        MeasurementFormatter.basalAreaDensityFactor(unit)

    /// Volume arrives as CUBIC METRES per ACRE, and had the same half-converted
    /// fraction basal area had: an imperial report scaled the denominator and
    /// printed "m³/ac", 35.3x away from the cubic feet per acre the page is
    /// read in — on the one line a landowner is paid on. The numerator follows
    /// the AREA unit, as the basal-area pair above it does, so the two rows of
    /// the same table cannot declare different systems.
    private fun volumeUnit(unit: AreaUnit): String =
        MeasurementFormatter.volumeNumeratorUnit(unit)

    private fun volumeFactor(unit: AreaUnit): Double =
        MeasurementFormatter.volumeDensityFactor(unit)

    /// Resolve a species code to a common name for metric reports (their
    /// country-prefixed codes like "FI-PISY" are opaque). The US report keeps
    /// the bare FIA code it has always printed so its bytes don't change.
    /// Prefers the project's own SpeciesConfig name, then the shared resolver.
    private fun speciesLabel(inputs: PDFReportInputs, code: String): String {
        if (inputs.displayUnits != UnitSystem.METRIC) return code
        inputs.species.firstOrNull { it.code == code }?.commonName
            ?.takeIf { it.isNotBlank() }?.let { return it }
        return RegionalSpecies.nameForCode(code)
    }

    // MARK: - Utilities

    private fun speciesBAAcrossStand(plotStats: Map<UUID, PlotStats>): Map<String, Float> {
        // Aggregate species BA across plots (simple sum; plots are
        // equal-weight for cover/summary display — proper weighting is
        // already in StandStat).
        val acc = mutableMapOf<String, Float>()
        for (stats in plotStats.values) {
            for ((code, ss) in stats.bySpecies) {
                acc[code] = (acc[code] ?: 0f) + ss.baPerAcreM2
            }
        }
        return acc
    }

    private fun chunkTreesForAppendix(trees: List<Tree>): List<List<Tree>> {
        // Sort for stable output and page-chunk by ~40 rows per page.
        val sorted = trees.sortedWith(
            compareBy({ it.plotId.uuidString }, { it.treeNumber })
        )
        val pageSize = 40
        val pages = mutableListOf<List<Tree>>()
        var i = 0
        while (i < sorted.size) {
            val end = min(i + pageSize, sorted.size)
            pages.add(sorted.subList(i, end).toList())
            i = end
        }
        return if (pages.isEmpty()) listOf(emptyList()) else pages
    }

    private fun describeSubsample(rule: HeightSubsampleRule): String = when (rule) {
        is HeightSubsampleRule.AllTrees -> "all trees"
        is HeightSubsampleRule.None -> "none (all heights imputed)"
        is HeightSubsampleRule.EveryKth -> "every ${rule.k}th tree"
        is HeightSubsampleRule.PerSpeciesCount -> "per species, first ${rule.minPerSpeciesOnPlot} on plot"
    }

    private fun shortLabel(s: String): String {
        // A length cap on a REAL name — the bar chart used to be labelled with
        // the first six characters of a stratum UUID.
        if (s.length > 14) return s.take(13) + "…"
        return s
    }

    /// Stratum key -> the name the cruiser gave it. Falls back to the word the
    /// in-app stand summary uses for the unstratified bucket, never to a UUID.
    private fun stratumName(inputs: PDFReportInputs, key: String): String {
        if (key.isEmpty()) return "Unstratified"
        return inputs.strata.firstOrNull { it.id.toString() == key }?.name
            ?.takeIf { it.isNotBlank() }
            ?: "Unstratified"
    }

    // Enum cases are stored (and exported) as camelCase identifiers so the two
    // platforms' files join. They are not English, so the report prints words.
    private fun unitsLabel(u: UnitSystem): String = when (u) {
        UnitSystem.IMPERIAL -> "Imperial"
        UnitSystem.METRIC -> "Metric"
    }

    private fun plotTypeLabel(t: PlotType): String = when (t) {
        PlotType.FIXED_AREA -> "Fixed-area plots"
        PlotType.VARIABLE_RADIUS -> "Variable radius (prism)"
    }

    private fun samplingSchemeLabel(sc: SamplingScheme): String = when (sc) {
        SamplingScheme.SYSTEMATIC_GRID -> "Systematic grid"
        SamplingScheme.STRATIFIED_RANDOM -> "Stratified random"
        SamplingScheme.MANUAL -> "Placed by hand"
    }

    private fun breastHeightLabel(c: BreastHeightConvention): String = when (c) {
        BreastHeightConvention.UPHILL -> "Uphill side"
        BreastHeightConvention.MID -> "Mid-slope"
        BreastHeightConvention.ANY -> "Any side"
        BreastHeightConvention.CUSTOM -> "Custom"
    }

    private fun statusLabel(st: TreeStatus): String = when (st) {
        TreeStatus.LIVE -> "Live"
        TreeStatus.DEAD_STANDING -> "Dead standing"
        TreeStatus.DEAD_DOWN -> "Dead down"
        TreeStatus.CULL -> "Cull"
    }

    /// The same word the confidence chip shows in the app, so the report and
    /// the screen cannot disagree about the same reading.
    private fun qualityLabel(t: ConfidenceTier): String = when (t.raw) {
        "green" -> "Good"
        "yellow" -> "Fair"
        "red" -> "Check"
        else -> t.raw
    }

    // MARK: - Pager

    private class Pager(private val doc: PdfDocument) {
        private val margin = 48f
        private var pageNumber = 0

        fun newPage(draw: (Canvas, RectF) -> Unit) {
            pageNumber += 1
            val info = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create()
            val page = doc.startPage(info)
            val frame = RectF(margin, margin, PAGE_WIDTH - margin, PAGE_HEIGHT - margin)
            draw(page.canvas, frame)
            doc.finishPage(page)
        }
    }
}
