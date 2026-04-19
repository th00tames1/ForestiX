// Spec §8 Export/PDFReportBuilder — Phase 6.
//
// A self-contained PDF report generator that does NOT depend on PDFKit
// or SwiftUI, so it compiles and runs on every target (including macOS
// test runs and Linux CI if ever needed via a CoreGraphics shim).
//
// ## Why Core Graphics directly?
// PDFKit is great for parsing/viewing but its authoring API
// (UIGraphicsPDFRenderer) is iOS-only; on macOS the corresponding type
// is different. Core Graphics' `CGContext(consumer:mediaBox:...)` and
// `CGContext(url:mediaBox:...)` are cross-platform and produce real
// conforming PDFs.
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
//
// ## Unit handling
// Respects Project.units: metric (cm, m, m³/ac) or imperial (in, ft,
// ft³/ac). Conversions are done once at render time.

import Foundation
import CoreGraphics
import CoreText
import Models
import InventoryEngine

public struct PDFReportInputs {
    public let project: Project
    public let design: CruiseDesign
    public let strata: [Stratum]
    public let species: [SpeciesConfig]
    public let plots: [Plot]
    public let trees: [Tree]           // include deleted for appendix completeness
    public let plotStatsByPlot: [UUID: PlotStats]
    public let tpaStand: StandStat
    public let baStand: StandStat
    public let volStand: StandStat
    public let generatedAt: Date

    public init(
        project: Project, design: CruiseDesign,
        strata: [Stratum], species: [SpeciesConfig],
        plots: [Plot], trees: [Tree],
        plotStatsByPlot: [UUID: PlotStats],
        tpaStand: StandStat, baStand: StandStat, volStand: StandStat,
        generatedAt: Date
    ) {
        self.project = project; self.design = design
        self.strata = strata; self.species = species
        self.plots = plots; self.trees = trees
        self.plotStatsByPlot = plotStatsByPlot
        self.tpaStand = tpaStand; self.baStand = baStand; self.volStand = volStand
        self.generatedAt = generatedAt
    }
}

public enum PDFReportBuilderError: Error, CustomStringConvertible {
    case contextCreationFailed
    case writeFailed(String)

    public var description: String {
        switch self {
        case .contextCreationFailed: return "Failed to create CGContext for PDF"
        case .writeFailed(let m):    return "Failed to write PDF: \(m)"
        }
    }
}

public enum PDFReportBuilder {

    /// Render a full cruise report to a PDF file at the supplied URL.
    /// Returns the number of pages written so the caller can sanity-check.
    @discardableResult
    public static func write(_ inputs: PDFReportInputs, to url: URL) throws -> Int {
        let data = NSMutableData()
        guard let consumer = CGDataConsumer(data: data) else {
            throw PDFReportBuilderError.contextCreationFailed
        }
        var mediaBox = CGRect(x: 0, y: 0, width: 612, height: 792) // US Letter
        guard let ctx = CGContext(consumer: consumer,
                                  mediaBox: &mediaBox,
                                  nil) else {
            throw PDFReportBuilderError.contextCreationFailed
        }
        let pageCount = render(inputs, into: ctx, mediaBox: mediaBox)
        ctx.closePDF()
        do { try (data as Data).write(to: url, options: .atomic) }
        catch { throw PDFReportBuilderError.writeFailed(String(describing: error)) }
        return pageCount
    }

    /// Render to an in-memory Data blob, used by tests.
    public static func data(_ inputs: PDFReportInputs) throws -> (Data, Int) {
        let data = NSMutableData()
        guard let consumer = CGDataConsumer(data: data) else {
            throw PDFReportBuilderError.contextCreationFailed
        }
        var mediaBox = CGRect(x: 0, y: 0, width: 612, height: 792)
        guard let ctx = CGContext(consumer: consumer,
                                  mediaBox: &mediaBox,
                                  nil) else {
            throw PDFReportBuilderError.contextCreationFailed
        }
        let pageCount = render(inputs, into: ctx, mediaBox: mediaBox)
        ctx.closePDF()
        return (data as Data, pageCount)
    }

    // MARK: - Rendering orchestration

    private static func render(_ inputs: PDFReportInputs,
                               into ctx: CGContext,
                               mediaBox: CGRect) -> Int {
        var pages = 0
        let pager = Pager(mediaBox: mediaBox)

        pager.newPage(into: ctx) { c, f in drawCover(inputs, frame: f, ctx: c) }; pages += 1
        pager.newPage(into: ctx) { c, f in drawStandSummary(inputs, frame: f, ctx: c) }; pages += 1

        // Per-plot pages — one page per closed plot for readability.
        let closed = inputs.plots
            .filter { $0.closedAt != nil }
            .sorted { $0.plotNumber < $1.plotNumber }
        for plot in closed {
            pager.newPage(into: ctx) { c, f in
                drawPlotPage(inputs, plot: plot, frame: f, ctx: c)
            }
            pages += 1
        }

        pager.newPage(into: ctx) { c, f in drawMethodology(inputs, frame: f, ctx: c) }; pages += 1

        // Appendix: tree-level raw table, paginated.
        let treeChunks = chunkTreesForAppendix(inputs.trees)
        for (idx, chunk) in treeChunks.enumerated() {
            pager.newPage(into: ctx) { c, f in
                drawTreeAppendix(inputs,
                                 page: idx + 1,
                                 totalPages: treeChunks.count,
                                 rows: chunk,
                                 frame: f,
                                 ctx: c)
            }
            pages += 1
        }

        return pages
    }

    // MARK: - Page layouts

    private static func drawCover(_ inputs: PDFReportInputs, frame: CGRect, ctx: CGContext) {
        drawTitle("Forestix Cruise Report", at: CGPoint(x: frame.minX, y: frame.maxY - 80),
                  width: frame.width, in: ctx)
        drawSubtitle(inputs.project.name,
                     at: CGPoint(x: frame.minX, y: frame.maxY - 120),
                     width: frame.width, in: ctx)
        var y = frame.maxY - 180
        func kv(_ k: String, _ v: String) {
            drawKeyValue(k, v, at: CGPoint(x: frame.minX, y: y),
                         width: frame.width, in: ctx)
            y -= 22
        }
        let df = DateFormatter()
        df.dateFormat = "yyyy-MM-dd HH:mm:ss"
        df.timeZone = TimeZone.current
        kv("Owner",            inputs.project.owner)
        kv("Units",             String(describing: inputs.project.units))
        kv("Generated",         df.string(from: inputs.generatedAt))
        kv("# plots (closed)",  "\(inputs.plots.filter { $0.closedAt != nil }.count)")
        kv("# plots (total)",   "\(inputs.plots.count)")
        let totalAreaAc = inputs.strata.reduce(0) { $0 + $1.areaAcres }
        kv("Total area",        "\(String(format: "%.2f", totalAreaAc)) ac")
        kv("# strata",          "\(inputs.strata.count)")
        kv("# species",         "\(inputs.species.count)")
        kv("# volume equations","\(Set(inputs.species.map { $0.volumeEquationId }).count)")

        // Dominant species by BA across stand.
        y -= 20
        drawHeading("Dominant species (by BA)",
                    at: CGPoint(x: frame.minX, y: y), width: frame.width, in: ctx)
        y -= 22
        let byCode = Self.speciesBAAcrossStand(plotStats: inputs.plotStatsByPlot)
        let top3 = byCode.sorted { $0.value > $1.value }.prefix(3)
        if top3.isEmpty {
            drawBody("(no tallied species)", at: CGPoint(x: frame.minX, y: y),
                     width: frame.width, in: ctx)
        } else {
            for (code, ba) in top3 {
                let name = inputs.species.first(where: { $0.code == code })?.commonName ?? code
                drawBody("\(code) — \(name): \(String(format: "%.3f", ba)) m²/ac",
                         at: CGPoint(x: frame.minX + 12, y: y),
                         width: frame.width, in: ctx)
                y -= 18
            }
        }

        drawFooter("Forestix • confidential cruise output",
                   frame: frame, in: ctx)
    }

    private static func drawStandSummary(_ inputs: PDFReportInputs, frame: CGRect, ctx: CGContext) {
        drawTitle("Stand Summary", at: CGPoint(x: frame.minX, y: frame.maxY - 50),
                  width: frame.width, in: ctx)

        var y = frame.maxY - 100

        // Stratified stats table — three metrics × (mean, SE, CI95, df).
        drawHeading("Stratified statistics (§7.5)",
                    at: CGPoint(x: frame.minX, y: y), width: frame.width, in: ctx)
        y -= 22
        let metricRows: [(String, StandStat, String)] = [
            ("TPA",           inputs.tpaStand, "trees/ac"),
            ("BA",            inputs.baStand,  "m²/ac"),
            ("Gross volume",  inputs.volStand, "m³/ac")
        ]
        drawTableRow(cells: ["Metric", "Unit", "Mean", "SE", "CI95 ±", "df", "n"],
                     bold: true, at: CGPoint(x: frame.minX, y: y),
                     colWidths: [110, 70, 80, 60, 70, 45, 40],
                     in: ctx)
        y -= 18
        for (name, stat, unit) in metricRows {
            drawTableRow(cells: [
                name, unit,
                String(format: "%.3f", stat.mean),
                String(format: "%.3f", stat.seMean),
                String(format: "%.3f", stat.ci95HalfWidth),
                String(format: "%.1f", stat.dfSatterthwaite),
                "\(stat.nPlots)"
            ], bold: false, at: CGPoint(x: frame.minX, y: y),
               colWidths: [110, 70, 80, 60, 70, 45, 40], in: ctx)
            y -= 16
        }

        // BA by stratum bar chart (manual CG drawing).
        y -= 30
        drawHeading("BA by stratum (m²/ac)",
                    at: CGPoint(x: frame.minX, y: y), width: frame.width, in: ctx)
        y -= 18
        let strataBars = inputs.baStand.byStratum
            .sorted { $0.key < $1.key }
            .map { ($0.key, $0.value.mean) }
        let chartRect = CGRect(x: frame.minX, y: y - 140,
                               width: frame.width, height: 130)
        drawBarChart(values: strataBars.map { $0.1 },
                     labels: strataBars.map { shortLabel($0.0) },
                     rect: chartRect, in: ctx)
        y = chartRect.minY - 20

        // Species composition.
        drawHeading("Species composition (top 8 by BA)",
                    at: CGPoint(x: frame.minX, y: y), width: frame.width, in: ctx)
        y -= 18
        let byCode = Self.speciesBAAcrossStand(plotStats: inputs.plotStatsByPlot)
        let top8 = byCode.sorted { $0.value > $1.value }.prefix(8)
        let spLabels: [String] = top8.map { "\($0.key)" }
        let spValues: [Double] = top8.map { Double($0.value) }
        let spRect = CGRect(x: frame.minX, y: y - 120,
                            width: frame.width, height: 110)
        drawBarChart(values: spValues, labels: spLabels, rect: spRect, in: ctx)

        drawFooter("Stand summary", frame: frame, in: ctx)
    }

    private static func drawPlotPage(_ inputs: PDFReportInputs,
                                     plot: Plot, frame: CGRect, ctx: CGContext) {
        drawTitle("Plot \(plot.plotNumber)",
                  at: CGPoint(x: frame.minX, y: frame.maxY - 50),
                  width: frame.width, in: ctx)
        var y = frame.maxY - 90
        func kv(_ k: String, _ v: String) {
            drawKeyValue(k, v, at: CGPoint(x: frame.minX, y: y),
                         width: frame.width, in: ctx); y -= 18
        }
        let df = DateFormatter()
        df.dateFormat = "yyyy-MM-dd HH:mm"
        df.timeZone = TimeZone.current
        kv("Center",        String(format: "%.6f, %.6f", plot.centerLat, plot.centerLon))
        kv("Position tier", String(describing: plot.positionTier))
        kv("Source",        String(describing: plot.positionSource))
        kv("GPS samples",   "\(plot.gpsNSamples) (H_acc med \(String(format: "%.2f", plot.gpsMedianHAccuracyM)) m)")
        kv("Plot area",     String(format: "%.3f ac", plot.plotAreaAcres))
        kv("Slope/Aspect",  "\(String(format: "%.1f", plot.slopeDeg))° / \(String(format: "%.0f", plot.aspectDeg))°")
        kv("Started",       df.string(from: plot.startedAt))
        kv("Closed",        plot.closedAt.map(df.string(from:)) ?? "—")
        kv("Closed by",     plot.closedBy ?? "—")

        y -= 12
        drawHeading("Live stats", at: CGPoint(x: frame.minX, y: y),
                    width: frame.width, in: ctx); y -= 18
        if let s = inputs.plotStatsByPlot[plot.id] {
            kv("Live trees",    "\(s.liveTreeCount)")
            kv("TPA",           String(format: "%.2f trees/ac", s.tpa))
            kv("BA",            String(format: "%.4f m²/ac", s.baPerAcreM2))
            kv("QMD",           String(format: "%.2f cm", s.qmdCm))
            kv("Gross V",       String(format: "%.4f m³/ac", s.grossVolumePerAcreM3))
            kv("Merch V",       String(format: "%.4f m³/ac", s.merchVolumePerAcreM3))
        } else {
            drawBody("(no stats available)",
                     at: CGPoint(x: frame.minX, y: y),
                     width: frame.width, in: ctx); y -= 18
        }

        // Per-species breakdown.
        y -= 12
        drawHeading("Per-species breakdown",
                    at: CGPoint(x: frame.minX, y: y),
                    width: frame.width, in: ctx); y -= 18
        drawTableRow(cells: ["Species", "n", "TPA", "BA m²/ac", "V m³/ac"],
                     bold: true, at: CGPoint(x: frame.minX, y: y),
                     colWidths: [80, 50, 90, 110, 110], in: ctx); y -= 16
        if let s = inputs.plotStatsByPlot[plot.id] {
            let sortedCodes = s.bySpecies.keys.sorted()
            for code in sortedCodes {
                guard let ss = s.bySpecies[code] else { continue }
                drawTableRow(cells: [
                    code, "\(ss.count)",
                    String(format: "%.2f", ss.tpa),
                    String(format: "%.4f", ss.baPerAcreM2),
                    String(format: "%.4f", ss.grossVolumePerAcreM3)
                ], bold: false, at: CGPoint(x: frame.minX, y: y),
                   colWidths: [80, 50, 90, 110, 110], in: ctx); y -= 16
            }
        }

        drawFooter("Plot \(plot.plotNumber)", frame: frame, in: ctx)
    }

    private static func drawMethodology(_ inputs: PDFReportInputs, frame: CGRect, ctx: CGContext) {
        drawTitle("Methodology",
                  at: CGPoint(x: frame.minX, y: frame.maxY - 50),
                  width: frame.width, in: ctx)
        var y = frame.maxY - 100
        func kv(_ k: String, _ v: String) {
            drawKeyValue(k, v, at: CGPoint(x: frame.minX, y: y),
                         width: frame.width, in: ctx); y -= 18
        }
        kv("Plot type",         String(describing: inputs.design.plotType))
        kv("Plot area",         inputs.design.plotAreaAcres.map { "\($0) ac" } ?? "—")
        kv("BAF",               inputs.design.baf.map { "\($0)" } ?? "—")
        kv("Sampling scheme",   String(describing: inputs.design.samplingScheme))
        kv("Grid spacing",      inputs.design.gridSpacingMeters.map { "\($0) m" } ?? "—")
        kv("Height subsample",  describeSubsample(inputs.design.heightSubsampleRule))
        kv("BH convention",     String(describing: inputs.project.breastHeightConvention))
        kv("Slope correction",  inputs.project.slopeCorrection ? "on" : "off")
        y -= 12

        drawHeading("Calibration",
                    at: CGPoint(x: frame.minX, y: y),
                    width: frame.width, in: ctx); y -= 18
        kv("LiDAR bias",        String(format: "%.2f mm", inputs.project.lidarBiasMm))
        kv("Depth noise (σ)",   String(format: "%.2f mm", inputs.project.depthNoiseMm))
        kv("DBH α, β",          String(format: "α=%.3f β=%.3f",
                                       inputs.project.dbhCorrectionAlpha,
                                       inputs.project.dbhCorrectionBeta))
        kv("VIO drift fraction",String(format: "%.4f", inputs.project.vioDriftFraction))

        y -= 12
        drawHeading("Species list (\(inputs.species.count))",
                    at: CGPoint(x: frame.minX, y: y),
                    width: frame.width, in: ctx); y -= 18
        drawTableRow(cells: ["Code", "Common name", "Vol eqn", "Top DIB (cm)", "Stump (cm)"],
                     bold: true, at: CGPoint(x: frame.minX, y: y),
                     colWidths: [55, 180, 80, 95, 85], in: ctx); y -= 16
        for sp in inputs.species.sorted(by: { $0.code < $1.code }).prefix(20) {
            drawTableRow(cells: [
                sp.code,
                sp.commonName,
                sp.volumeEquationId,
                String(format: "%.1f", sp.merchTopDibCm),
                String(format: "%.1f", sp.stumpHeightCm)
            ], bold: false, at: CGPoint(x: frame.minX, y: y),
               colWidths: [55, 180, 80, 95, 85], in: ctx); y -= 16
        }

        drawFooter("Methodology", frame: frame, in: ctx)
    }

    private static func drawTreeAppendix(_ inputs: PDFReportInputs,
                                         page: Int, totalPages: Int,
                                         rows: [Tree],
                                         frame: CGRect,
                                         ctx: CGContext) {
        drawTitle("Appendix — tree-level (page \(page)/\(totalPages))",
                  at: CGPoint(x: frame.minX, y: frame.maxY - 50),
                  width: frame.width, in: ctx)
        var y = frame.maxY - 90
        let headers = ["Plot", "#", "Sp", "DBH cm", "H m",
                       "Status", "Conf", "Flag"]
        let widths: [CGFloat] = [55, 35, 35, 55, 55, 55, 45, 60]
        drawTableRow(cells: headers, bold: true,
                     at: CGPoint(x: frame.minX, y: y),
                     colWidths: widths, in: ctx); y -= 16

        let plotNumberById = Dictionary(uniqueKeysWithValues:
            inputs.plots.map { ($0.id, $0.plotNumber) })

        for t in rows {
            let pno = plotNumberById[t.plotId].map { "\($0)" } ?? "?"
            let flagBits: [String] = [
                t.deletedAt != nil ? "del" : nil,
                t.isMultistem ? "ms" : nil,
                t.dbhIsIrregular ? "irr" : nil
            ].compactMap { $0 }
            drawTableRow(cells: [
                pno, "\(t.treeNumber)", t.speciesCode,
                String(format: "%.1f", t.dbhCm),
                t.heightM.map { String(format: "%.1f", $0) } ?? "—",
                String(describing: t.status),
                String(describing: t.dbhConfidence),
                flagBits.joined(separator: ",")
            ], bold: false,
               at: CGPoint(x: frame.minX, y: y),
               colWidths: widths, in: ctx); y -= 13
            if y < frame.minY + 60 { break }
        }

        drawFooter("Tree appendix", frame: frame, in: ctx)
    }

    // MARK: - Helper draws (text + layout)

    private static func drawTitle(_ text: String, at origin: CGPoint,
                                  width: CGFloat, in ctx: CGContext) {
        drawText(text, at: origin, width: width,
                 fontSize: 24, bold: true, in: ctx)
    }
    private static func drawSubtitle(_ text: String, at origin: CGPoint,
                                     width: CGFloat, in ctx: CGContext) {
        drawText(text, at: origin, width: width,
                 fontSize: 16, bold: false, in: ctx)
    }
    private static func drawHeading(_ text: String, at origin: CGPoint,
                                    width: CGFloat, in ctx: CGContext) {
        drawText(text, at: origin, width: width,
                 fontSize: 13, bold: true, in: ctx)
    }
    private static func drawBody(_ text: String, at origin: CGPoint,
                                 width: CGFloat, in ctx: CGContext) {
        drawText(text, at: origin, width: width,
                 fontSize: 11, bold: false, in: ctx)
    }
    private static func drawKeyValue(_ k: String, _ v: String,
                                     at origin: CGPoint, width: CGFloat,
                                     in ctx: CGContext) {
        drawText(k, at: origin, width: 200, fontSize: 11, bold: true, in: ctx)
        drawText(v, at: CGPoint(x: origin.x + 200, y: origin.y),
                 width: width - 200, fontSize: 11, bold: false, in: ctx)
    }

    private static func drawTableRow(cells: [String], bold: Bool,
                                     at origin: CGPoint,
                                     colWidths: [CGFloat],
                                     in ctx: CGContext) {
        var x = origin.x
        for (i, c) in cells.enumerated() {
            let w = colWidths.indices.contains(i) ? colWidths[i] : 60
            drawText(c, at: CGPoint(x: x, y: origin.y),
                     width: w, fontSize: 10, bold: bold, in: ctx)
            x += w
        }
    }

    private static func drawFooter(_ text: String, frame: CGRect,
                                   in ctx: CGContext) {
        drawText(text,
                 at: CGPoint(x: frame.minX, y: frame.minY + 20),
                 width: frame.width, fontSize: 9, bold: false, in: ctx)
    }

    private static func drawText(_ text: String, at origin: CGPoint,
                                 width: CGFloat, fontSize: CGFloat,
                                 bold: Bool, in ctx: CGContext) {
        guard !text.isEmpty else { return }
        let fontName = bold ? "Helvetica-Bold" : "Helvetica"
        let font = CTFontCreateWithName(fontName as CFString, fontSize, nil)
        // Use CT attribute-name CFStrings directly so we don't depend on
        // UIKit/AppKit (the `.font`/`.foregroundColor` extension keys).
        let attrs: [NSAttributedString.Key: Any] = [
            NSAttributedString.Key(kCTFontAttributeName as String): font,
            NSAttributedString.Key(kCTForegroundColorAttributeName as String):
                CGColor(gray: 0, alpha: 1)
        ]
        let attr = NSAttributedString(string: text, attributes: attrs)
        let framesetter = CTFramesetterCreateWithAttributedString(attr)
        let path = CGMutablePath()
        path.addRect(CGRect(x: origin.x, y: origin.y - fontSize - 2,
                            width: width, height: fontSize + 4))
        let ctFrame = CTFramesetterCreateFrame(framesetter,
                                               CFRange(location: 0, length: 0),
                                               path, nil)
        CTFrameDraw(ctFrame, ctx)
    }

    // MARK: - Bar chart

    private static func drawBarChart(values: [Double],
                                     labels: [String],
                                     rect: CGRect, in ctx: CGContext) {
        guard !values.isEmpty else { return }
        let maxV = max(values.max() ?? 1, 0.0001)
        let barArea = rect.insetBy(dx: 10, dy: 20)
        let barCount = CGFloat(values.count)
        let gap: CGFloat = 6
        let barW = (barArea.width - gap * (barCount - 1)) / barCount
        ctx.setFillColor(CGColor(gray: 0.3, alpha: 1))
        ctx.setStrokeColor(CGColor(gray: 0.6, alpha: 1))
        // Axis baseline
        ctx.move(to: CGPoint(x: barArea.minX, y: barArea.minY))
        ctx.addLine(to: CGPoint(x: barArea.maxX, y: barArea.minY))
        ctx.strokePath()

        for (i, v) in values.enumerated() {
            let h = CGFloat(v / maxV) * barArea.height
            let x = barArea.minX + CGFloat(i) * (barW + gap)
            let barRect = CGRect(x: x, y: barArea.minY, width: barW, height: h)
            ctx.fill(barRect)

            // Value label on top.
            drawText(String(format: "%.2f", v),
                     at: CGPoint(x: x, y: barArea.minY + h + 12),
                     width: barW, fontSize: 8, bold: false, in: ctx)
            // Category label below axis.
            drawText(labels.indices.contains(i) ? labels[i] : "",
                     at: CGPoint(x: x, y: barArea.minY - 2),
                     width: barW, fontSize: 8, bold: false, in: ctx)
        }
    }

    // MARK: - Utilities

    private static func speciesBAAcrossStand(
        plotStats: [UUID: PlotStats]
    ) -> [String: Float] {
        // Aggregate species BA across plots (simple sum; plots are
        // equal-weight for cover/summary display — proper weighting is
        // already in StandStat).
        var acc: [String: Float] = [:]
        for stats in plotStats.values {
            for (code, ss) in stats.bySpecies {
                acc[code, default: 0] += ss.baPerAcreM2
            }
        }
        return acc
    }

    private static func chunkTreesForAppendix(_ trees: [Tree]) -> [[Tree]] {
        // Sort for stable output and page-chunk by ~40 rows per page.
        let sorted = trees.sorted {
            if $0.plotId != $1.plotId {
                return $0.plotId.uuidString < $1.plotId.uuidString
            }
            return $0.treeNumber < $1.treeNumber
        }
        let pageSize = 40
        var pages: [[Tree]] = []
        var i = 0
        while i < sorted.count {
            let end = min(i + pageSize, sorted.count)
            pages.append(Array(sorted[i..<end]))
            i = end
        }
        return pages.isEmpty ? [[]] : pages
    }

    private static func describeSubsample(_ rule: HeightSubsampleRule) -> String {
        switch rule {
        case .allTrees: return "all trees"
        case .none:     return "none (all heights imputed)"
        case .everyKth(let k): return "every \(k)th tree"
        case .perSpeciesCount(let n): return "per species, first \(n) on plot"
        }
    }

    private static func shortLabel(_ s: String) -> String {
        // Stratum keys are often UUIDs; trim for axis labels.
        if s.count > 8 { return String(s.prefix(6)) + "…" }
        return s
    }
}

// MARK: - Pager

private final class Pager {
    private let mediaBox: CGRect
    private let margin: CGFloat = 48

    init(mediaBox: CGRect) { self.mediaBox = mediaBox }

    func newPage(into ctx: CGContext,
                 draw: (CGContext, CGRect) -> Void) {
        ctx.beginPDFPage(nil)
        draw(ctx, mediaBox.insetBy(dx: margin, dy: margin))
        ctx.endPDFPage()
    }
}
