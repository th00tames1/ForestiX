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
import com.hcjeong.forestix.data.cruise.CruiseDesign
import com.hcjeong.forestix.data.cruise.HeightSubsampleRule
import com.hcjeong.forestix.data.cruise.Plot
import com.hcjeong.forestix.data.cruise.Project
import com.hcjeong.forestix.data.cruise.SpeciesConfig
import com.hcjeong.forestix.data.cruise.Stratum
import com.hcjeong.forestix.data.cruise.Tree
import com.hcjeong.forestix.inventory.PlotStats
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
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)  // local time zone
        kv("Owner",             inputs.project.owner)
        kv("Units",             inputs.project.units.raw)
        kv("Generated",         df.format(Date(inputs.generatedAt)))
        kv("# plots (closed)",  "${inputs.plots.count { it.closedAt != null }}")
        kv("# plots (total)",   "${inputs.plots.size}")
        val totalAreaAc = inputs.strata.fold(0f) { acc, s -> acc + s.areaAcres }
        kv("Total area",        "${String.format(Locale.US, "%.2f", totalAreaAc)} ac")
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
                    "$code — $name: ${String.format(Locale.US, "%.3f", ba)} m²/ac",
                    frame.left + 12f, y, frame.width())
                y += 18f
            }
        }

        drawFooter(canvas, "Forestix • confidential cruise output", frame)
    }

    private fun drawStandSummary(inputs: PDFReportInputs, frame: RectF, canvas: Canvas) {
        drawTitle(canvas, "Stand Summary", frame.left, frame.top + 50f, frame.width())

        var y = frame.top + 100f

        // Stratified stats table — three metrics × (mean, SE, CI95, df).
        drawHeading(canvas, "Stratified statistics (§7.5)", frame.left, y, frame.width())
        y += 22f
        val metricRows = listOf(
            Triple("Trees per acre", inputs.tpaStand, "trees/ac"),
            Triple("Basal area",     inputs.baStand,  "m²/ac"),
            Triple("Gross volume",   inputs.volStand, "m³/ac"),
        )
        val colWidths = listOf(110f, 70f, 80f, 60f, 70f, 45f, 40f)
        drawTableRow(canvas,
            listOf("Metric", "Unit", "Mean", "Std error", "95% conf ±", "Eff. plots", "n"),
            bold = true, frame.left, y, colWidths)
        y += 18f
        for ((name, stat, unit) in metricRows) {
            drawTableRow(canvas, listOf(
                name, unit,
                String.format(Locale.US, "%.3f", stat.mean),
                String.format(Locale.US, "%.3f", stat.seMean),
                String.format(Locale.US, "%.3f", stat.ci95HalfWidth),
                String.format(Locale.US, "%.1f", stat.dfSatterthwaite),
                "${stat.nPlots}",
            ), bold = false, frame.left, y, colWidths)
            y += 16f
        }

        // Basal area by stratum bar chart.
        y += 30f
        drawHeading(canvas, "Basal area by stratum (m²/ac)", frame.left, y, frame.width())
        y += 18f
        val strataBars = inputs.baStand.byStratum.entries
            .sortedBy { it.key }
            .map { it.key to it.value.mean }
        val chartRect = RectF(frame.left, y + 10f, frame.left + frame.width(), y + 140f)
        drawBarChart(canvas,
            values = strataBars.map { it.second },
            labels = strataBars.map { shortLabel(it.first) },
            rect = chartRect)
        y = chartRect.bottom + 20f

        // Species composition.
        drawHeading(canvas, "Species composition (top 8 by basal area)", frame.left, y, frame.width())
        y += 18f
        val byCode = speciesBAAcrossStand(inputs.plotStatsByPlot)
        val top8 = byCode.entries.sortedByDescending { it.value }.take(8)
        val spLabels: List<String> = top8.map { it.key }
        val spValues: List<Double> = top8.map { it.value.toDouble() }
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
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)  // local time zone
        kv("Center",        String.format(Locale.US, "%.6f, %.6f", plot.centerLat, plot.centerLon))
        kv("Position tier", plot.positionTier.raw)
        kv("Source",        plot.positionSource.raw)
        kv("GPS samples",   "${plot.gpsNSamples} (H_acc med ${String.format(Locale.US, "%.2f", plot.gpsMedianHAccuracyM)} m)")
        kv("Plot area",     String.format(Locale.US, "%.3f ac", plot.plotAreaAcres))
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
            kv("Trees per acre",      String.format(Locale.US, "%.2f trees/ac", s.tpa))
            kv("Basal area",          String.format(Locale.US, "%.4f m²/ac", s.baPerAcreM2))
            kv("Quadratic mean DBH",  String.format(Locale.US, "%.2f cm", s.qmdCm))
            kv("Gross volume",        String.format(Locale.US, "%.4f m³/ac", s.grossVolumePerAcreM3))
            kv("Merchantable volume", String.format(Locale.US, "%.4f m³/ac", s.merchVolumePerAcreM3))
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
            listOf("Species", "n", "Trees/ac", "Basal m²/ac", "Volume m³/ac"),
            bold = true, frame.left, y, colWidths)
        y += 16f
        if (s != null) {
            val sortedCodes = s.bySpecies.keys.sorted()
            for (code in sortedCodes) {
                val ss = s.bySpecies[code] ?: continue
                drawTableRow(canvas, listOf(
                    code, "${ss.count}",
                    String.format(Locale.US, "%.2f", ss.tpa),
                    String.format(Locale.US, "%.4f", ss.baPerAcreM2),
                    String.format(Locale.US, "%.4f", ss.grossVolumePerAcreM3),
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
        kv("Plot type",         inputs.design.plotType.raw)
        kv("Plot area",         inputs.design.plotAreaAcres?.let { "$it ac" } ?: "—")
        kv("Basal area factor", inputs.design.baf?.let { "$it" } ?: "—")
        kv("Sampling scheme",   inputs.design.samplingScheme.raw)
        kv("Grid spacing",      inputs.design.gridSpacingMeters?.let { "$it m" } ?: "—")
        kv("Height subsample",  describeSubsample(inputs.design.heightSubsampleRule))
        kv("BH convention",     inputs.project.breastHeightConvention.raw)
        kv("Slope correction",  if (inputs.project.slopeCorrection) "on" else "off")
        y += 12f

        drawHeading(canvas, "Calibration", frame.left, y, frame.width())
        y += 18f
        kv("LiDAR bias",         String.format(Locale.US, "%.2f mm", inputs.project.lidarBiasMm))
        kv("Depth noise (σ)",    String.format(Locale.US, "%.2f mm", inputs.project.depthNoiseMm))
        kv("DBH α, β",           String.format(Locale.US, "α=%.3f β=%.3f",
                                     inputs.project.dbhCorrectionAlpha,
                                     inputs.project.dbhCorrectionBeta))
        kv("VIO drift fraction", String.format(Locale.US, "%.4f", inputs.project.vioDriftFraction))

        y += 12f
        drawHeading(canvas, "Species list (${inputs.species.size})", frame.left, y, frame.width())
        y += 18f
        val colWidths = listOf(55f, 180f, 80f, 95f, 85f)
        drawTableRow(canvas,
            listOf("Code", "Common name", "Vol eqn", "Top DIB (cm)", "Stump (cm)"),
            bold = true, frame.left, y, colWidths)
        y += 16f
        for (sp in inputs.species.sortedBy { it.code }.take(20)) {
            drawTableRow(canvas, listOf(
                sp.code,
                sp.commonName,
                sp.volumeEquationId,
                String.format(Locale.US, "%.1f", sp.merchTopDibCm),
                String.format(Locale.US, "%.1f", sp.stumpHeightCm),
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
        val headers = listOf("Plot", "#", "Sp", "DBH cm", "H m", "Status", "Conf", "Flag")
        val widths = listOf(55f, 35f, 35f, 55f, 55f, 55f, 45f, 60f)
        drawTableRow(canvas, headers, bold = true, frame.left, y, widths)
        y += 16f

        val plotNumberById = inputs.plots.associate { it.id to it.plotNumber }

        for (t in rows) {
            val pno = plotNumberById[t.plotId]?.let { "$it" } ?: "?"
            val flagBits = listOfNotNull(
                if (t.deletedAt != null) "del" else null,
                if (t.isMultistem) "ms" else null,
                if (t.dbhIsIrregular) "irr" else null,
            )
            drawTableRow(canvas, listOf(
                pno, "${t.treeNumber}", t.speciesCode,
                String.format(Locale.US, "%.1f", t.dbhCm),
                t.heightM?.let { String.format(Locale.US, "%.1f", it) } ?: "—",
                t.status.raw,
                t.dbhConfidence.raw,
                flagBits.joinToString(","),
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
        // Stratum keys are often UUIDs; trim for axis labels.
        if (s.length > 8) return s.take(6) + "…"
        return s
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
