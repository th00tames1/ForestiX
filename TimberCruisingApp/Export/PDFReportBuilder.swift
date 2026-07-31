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
// Diameters and heights are reported in the engine's stored metric base
// (cm, m). Per-area densities (TPA, basal area, volume) honour the caller's
// `PDFLocalization`: the US renders per acre, metric countries per hectare
// with the per-acre values scaled by the hectare density factor. Species
// codes are resolved to common names through the same localisation. Absent a
// localisation, the report falls back to the historical US per-acre output.

import Foundation
import CoreGraphics
import CoreText
import Common
import Models
import InventoryEngine

/// Display localisation for the report, computed by the UI layer and handed
/// down. `AreaUnit`, `Country` and `RegionalSpecies` live in the UI module,
/// which sits ABOVE Export in the dependency graph, so this value type carries
/// the already-resolved density factor, area labels and species-name lookup
/// rather than importing those types here. The default reproduces the
/// historical US per-acre / imperial output byte for byte, keeping existing
/// callers and tests unchanged.
public struct PDFLocalization: Sendable {
    /// Multiply a canonical per-ACRE density by this to express it per the
    /// display area unit (1.0 for acres, 2.4710538147 for hectares).
    public let densityFactor: Double
    /// Density-label suffix: "/ac" or "/ha".
    public let areaSuffix: String
    /// Bare area-unit abbreviation: "ac" or "ha".
    public let areaAbbr: String
    /// Singular area-unit word for prose labels: "acre" or "hectare".
    public let areaWord: String
    /// Code → resolved common name; codes absent here fall back to the code.
    public let speciesNames: [String: String]

    public init(densityFactor: Double = 1.0,
                areaSuffix: String = "/ac",
                areaAbbr: String = "ac",
                areaWord: String = "acre",
                speciesNames: [String: String] = [:]) {
        self.densityFactor = densityFactor
        self.areaSuffix = areaSuffix
        self.areaAbbr = areaAbbr
        self.areaWord = areaWord
        self.speciesNames = speciesNames
    }

    /// Historical US default — per acre, imperial, species codes unresolved.
    public static let imperial = PDFLocalization()

    /// TRUE when densities are expressed per hectare (metric display basis).
    public var isMetric: Bool { areaAbbr == "ha" }

    /// Resolved common name for a species code, or the raw code when unknown.
    public func speciesName(_ code: String) -> String {
        speciesNames[code] ?? code
    }

    /// Express a stored area in acres on the display basis (acres → hectares
    /// divides by the density factor; acres pass through unchanged).
    public func area(fromAcres acres: Double) -> Double {
        acres / densityFactor
    }
}

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
    /// Area/species display localisation. Defaults to `.imperial` so existing
    /// callers and tests keep the historical per-acre / imperial output.
    public let localization: PDFLocalization

    public init(
        project: Project, design: CruiseDesign,
        strata: [Stratum], species: [SpeciesConfig],
        plots: [Plot], trees: [Tree],
        plotStatsByPlot: [UUID: PlotStats],
        tpaStand: StandStat, baStand: StandStat, volStand: StandStat,
        generatedAt: Date,
        localization: PDFLocalization = .imperial
    ) {
        self.project = project; self.design = design
        self.strata = strata; self.species = species
        self.plots = plots; self.trees = trees
        self.plotStatsByPlot = plotStatsByPlot
        self.tpaStand = tpaStand; self.baStand = baStand; self.volStand = volStand
        self.generatedAt = generatedAt
        self.localization = localization
    }
}

public enum PDFReportBuilderError: Error, LocalizedError, CustomStringConvertible {
    case contextCreationFailed
    case writeFailed(String)

    public var description: String {
        switch self {
        case .contextCreationFailed: return "Failed to create CGContext for PDF"
        case .writeFailed(let m):    return "Failed to write PDF: \(m)"
        }
    }

    // The export screens catch every failure and show `localizedDescription`.
    // Without this, a bare Swift Error prints "The operation couldn't be
    // completed (error 0.)" and the reason above never reaches the cruiser.
    public var errorDescription: String? { description }
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
        kv("Units",             Self.unitsWord(inputs.project.units))
        kv("Generated",         df.string(from: inputs.generatedAt))
        kv("# plots (closed)",  "\(inputs.plots.filter { $0.closedAt != nil }.count)")
        kv("# plots (total)",   "\(inputs.plots.count)")
        let loc = inputs.localization
        let totalAreaAc = inputs.strata.reduce(0) { $0 + $1.areaAcres }
        kv("Total area",
           "\(String(format: "%.2f", loc.area(fromAcres: Double(totalAreaAc)))) \(loc.areaAbbr)")
        kv("# strata",          "\(inputs.strata.count)")
        kv("# species",         "\(inputs.species.count)")
        kv("# volume equations","\(Set(inputs.species.map { $0.volumeEquationId }).count)")

        // Dominant species by basal area across stand.
        y -= 20
        drawHeading("Dominant species (by basal area)",
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
                drawBody("\(code) — \(name): \(String(format: "%.3f", Double(ba) * loc.densityFactor)) m²\(loc.areaSuffix)",
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

        // Stratified stats table — three metrics × (mean, CI95, n).
        // The heading no longer cites an internal spec section, and the
        // standard-error / effective-degrees-of-freedom columns are gone:
        // they are still computed and still in the CSV, but a landowner
        // reading this page cannot act on either, and "Eff. plots 6.3"
        // beside "n 8" read as two contradictory plot counts.
        drawHeading("Stand statistics",
                    at: CGPoint(x: frame.minX, y: y), width: frame.width, in: ctx)
        y -= 22
        let loc = inputs.localization
        let metricRows: [(String, StandStat, String)] = [
            ("Trees per \(loc.areaWord)",
             inputs.tpaStand.scaledPerArea(by: loc.densityFactor), "trees\(loc.areaSuffix)"),
            ("Basal area",
             inputs.baStand.scaledPerArea(by: loc.densityFactor),  "m²\(loc.areaSuffix)"),
            ("Gross volume",
             inputs.volStand.scaledPerArea(by: loc.densityFactor), "m³\(loc.areaSuffix)")
        ]
        drawTableRow(cells: ["Measure", "Unit", "Average",
                              "± 95% range", "Plots"],
                     bold: true, at: CGPoint(x: frame.minX, y: y),
                     colWidths: [140, 90, 100, 100, 60],
                     in: ctx)
        y -= 18
        for (name, stat, unit) in metricRows {
            drawTableRow(cells: [
                name, unit,
                String(format: "%.3f", stat.mean),
                String(format: "%.3f", stat.ci95HalfWidth),
                "\(stat.nPlots)"
            ], bold: false, at: CGPoint(x: frame.minX, y: y),
               colWidths: [140, 90, 100, 100, 60], in: ctx)
            y -= 16
        }

        // Basal area by stratum bar chart (manual CG drawing).
        y -= 30
        drawHeading("Basal area by stratum (m²\(loc.areaSuffix))",
                    at: CGPoint(x: frame.minX, y: y), width: frame.width, in: ctx)
        y -= 18
        let strataBars = inputs.baStand.scaledPerArea(by: loc.densityFactor).byStratum
            .sorted { $0.key < $1.key }
            .map { ($0.key, $0.value.mean) }
        let chartRect = CGRect(x: frame.minX, y: y - 140,
                               width: frame.width, height: 130)
        drawBarChart(values: strataBars.map { $0.1 },
                     labels: strataBars.map { shortLabel($0.0) },
                     rect: chartRect, in: ctx)
        y = chartRect.minY - 20

        // Species composition.
        drawHeading("Species composition (top 8 by basal area)",
                    at: CGPoint(x: frame.minX, y: y), width: frame.width, in: ctx)
        y -= 18
        let byCode = Self.speciesBAAcrossStand(plotStats: inputs.plotStatsByPlot)
        let top8 = byCode.sorted { $0.value > $1.value }.prefix(8)
        let spLabels: [String] = top8.map { loc.speciesName($0.key) }
        let spValues: [Double] = top8.map { Double($0.value) * loc.densityFactor }
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
        // A plot with no recorded centre says so IN WORDS — "not
        // recorded", the settled wording on both platforms. It must NOT
        // print "0.000000, 0.000000": that is the sentinel for a centre
        // that was never captured or was cleared from the map, and
        // printed as a coordinate it is a claim that the plot was cruised
        // in the Gulf of Guinea.
        //
        // Words, not the em dash the other "absent" rows below use: those
        // are fields that simply have no value yet, while a plot without
        // a centre is a FACT about the plot, and the reader of a cruise
        // report should not have to infer it from a punctuation mark.
        kv("Center",        plot.hasCentre
                            ? String(format: "%.6f, %.6f",
                                     plot.centerLat, plot.centerLon)
                            : "not recorded")
        // The A/B/C/D position tier was pulled from every screen because a
        // cruiser could neither act on a "C" nor tell what would make it a
        // "B"; it was still printing here, next to the enum case name of
        // the position source and an "H_acc med" code identifier. The tier
        // and source are still stored on the Plot and still exported in the
        // CSV — the client-facing report just states the accuracy plainly.
        kv("GPS accuracy",  "±\(String(format: "%.2f", plot.gpsMedianHAccuracyM)) m, averaged over \(plot.gpsNSamples) fixes")
        let loc = inputs.localization
        kv("Plot area",     "\(String(format: "%.3f", loc.area(fromAcres: Double(plot.plotAreaAcres)))) \(loc.areaAbbr)")
        kv("Slope/Aspect",  "\(String(format: "%.1f", plot.slopeDeg))° / \(String(format: "%.0f", plot.aspectDeg))°")
        kv("Started",       df.string(from: plot.startedAt))
        kv("Closed",        plot.closedAt.map(df.string(from:)) ?? "—")
        kv("Closed by",     plot.closedBy ?? "—")

        y -= 12
        drawHeading("Live stats", at: CGPoint(x: frame.minX, y: y),
                    width: frame.width, in: ctx); y -= 18
        if let s = inputs.plotStatsByPlot[plot.id] {
            kv("Live trees",          "\(s.liveTreeCount)")
            kv("Trees per \(loc.areaWord)",
               "\(String(format: "%.2f", Double(s.tpa) * loc.densityFactor)) trees\(loc.areaSuffix)")
            kv("Basal area",
               "\(String(format: "%.4f", Double(s.baPerAcreM2) * loc.densityFactor)) m²\(loc.areaSuffix)")
            kv("Quadratic mean DBH",  String(format: "%.2f cm", s.qmdCm))
            kv("Gross volume",
               "\(String(format: "%.4f", Double(s.grossVolumePerAcreM3) * loc.densityFactor)) m³\(loc.areaSuffix)")
            kv("Merchantable volume",
               "\(String(format: "%.4f", Double(s.merchVolumePerAcreM3) * loc.densityFactor)) m³\(loc.areaSuffix)")
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
        drawTableRow(cells: ["Species", "n", "Trees\(loc.areaSuffix)",
                              "Basal m²\(loc.areaSuffix)", "Volume m³\(loc.areaSuffix)"],
                     bold: true, at: CGPoint(x: frame.minX, y: y),
                     colWidths: [80, 50, 90, 110, 110], in: ctx); y -= 16
        if let s = inputs.plotStatsByPlot[plot.id] {
            let sortedCodes = s.bySpecies.keys.sorted()
            for code in sortedCodes {
                guard let ss = s.bySpecies[code] else { continue }
                drawTableRow(cells: [
                    loc.speciesName(code), "\(ss.count)",
                    String(format: "%.2f", Double(ss.tpa) * loc.densityFactor),
                    String(format: "%.4f", Double(ss.baPerAcreM2) * loc.densityFactor),
                    String(format: "%.4f", Double(ss.grossVolumePerAcreM3) * loc.densityFactor)
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
        let loc = inputs.localization
        kv("Plot type",         Self.plotTypeWord(inputs.design.plotType))
        kv("Plot area",         inputs.design.plotAreaAcres.map {
            loc.isMetric
                ? "\(String(format: "%.3f", loc.area(fromAcres: Double($0)))) \(loc.areaAbbr)"
                : "\($0) ac"
        } ?? "—")
        kv("Basal area factor", inputs.design.baf.map { "\($0)" } ?? "—")
        kv("Sampling scheme",   Self.schemeWord(inputs.design.samplingScheme))
        kv("Grid spacing",      inputs.design.gridSpacingMeters.map { "\($0) m" } ?? "—")
        kv("Height subsample",  describeSubsample(inputs.design.heightSubsampleRule))
        kv("Breast height taken", Self.breastHeightWord(inputs.project.breastHeightConvention))
        kv("Slope correction",  inputs.project.slopeCorrection ? "on" : "off")
        y -= 12

        drawHeading("Calibration",
                    at: CGPoint(x: frame.minX, y: y),
                    width: frame.width, in: ctx); y -= 18
        // Four device internals — a sensor bias, a raw σ, two Greek
        // correction coefficients and the visual-odometry drift term —
        // in four lines of a document a landowner reads. None of it is
        // interpretable outside the codebase; the numbers still travel
        // in the CSV, which is where an auditor would look for them.
        // "cylinder" is the shape the maths fits, and the app stopped saying
        // it out loud everywhere else — the Calibration screen's tab, header
        // and helper text all say "round post". A client's PDF must not be
        // the one document still using the internal name.
        kv("Device calibration",
           inputs.project.dbhCorrectionAlpha == 0 && inputs.project.dbhCorrectionBeta == 1
               ? "Not calibrated on this device"
               : "Wall and round-post calibration applied")

        y -= 12
        drawHeading("Species list (\(inputs.species.count))",
                    at: CGPoint(x: frame.minX, y: y),
                    width: frame.width, in: ctx); y -= 18
        drawTableRow(cells: ["Code", "Common name", "Volume equation",
                             "Merch. top dia. (cm)", "Stump (cm)"],
                     bold: true, at: CGPoint(x: frame.minX, y: y),
                     colWidths: [50, 150, 105, 120, 70], in: ctx); y -= 16
        for sp in inputs.species.sorted(by: { $0.code < $1.code }).prefix(20) {
            drawTableRow(cells: [
                sp.code,
                sp.commonName,
                sp.volumeEquationId,
                String(format: "%.1f", sp.merchTopDibCm),
                String(format: "%.1f", sp.stumpHeightCm)
            ], bold: false, at: CGPoint(x: frame.minX, y: y),
               colWidths: [50, 150, 105, 120, 70], in: ctx); y -= 16
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
        // "Species" is wider than the old "Sp" code column so resolved common
        // names have room; the surplus comes out of the page's right margin.
        // "H", "Conf" and the del/ms/irr codes are gone: a formula letter,
        // an abbreviation whose cells then printed raw enum names, and three
        // three-letter codes with no key anywhere in the document.
        let headers = ["Plot", "#", "Species", "DBH cm", "Height m",
                       "Status", "Quality", "Flags"]
        let widths: [CGFloat] = [38, 24, 74, 44, 46, 62, 44, 84]
        drawTableRow(cells: headers, bold: true,
                     at: CGPoint(x: frame.minX, y: y),
                     colWidths: widths, in: ctx); y -= 16

        let plotNumberById = Dictionary(uniqueKeysWithValues:
            inputs.plots.map { ($0.id, $0.plotNumber) })

        for t in rows {
            let pno = plotNumberById[t.plotId].map { "\($0)" } ?? "?"
            let flagBits: [String] = [
                t.deletedAt != nil ? "Deleted" : nil,
                t.isMultistem ? "Multistem" : nil,
                t.dbhIsIrregular ? "Irregular" : nil
            ].compactMap { $0 }
            drawTableRow(cells: [
                pno, "\(t.treeNumber)", inputs.localization.speciesName(t.speciesCode),
                String(format: "%.1f", t.dbhCm),
                // Two decimals, matching every on-screen height readout —
                // the appendix is what the client checks the app against.
                t.heightM.map { String(format: "%.2f", $0) } ?? "—",
                Self.statusWord(t.status),
                Self.qualityWord(t.dbhConfidence),
                flagBits.joined(separator: ", ")
            ], bold: false,
               at: CGPoint(x: frame.minX, y: y),
               colWidths: widths, in: ctx); y -= 13
            if y < frame.minY + 60 { break }
        }

        drawFooter("Tree appendix", frame: frame, in: ctx)
    }

    // MARK: - Enum → words

    // Every one of these used to reach the page through
    // `String(describing:)`, so a landowner read "fixedArea",
    // "systematicGrid", "deadStanding" and "imperial" in camelCase. The
    // stored values are untouched — the CSV still carries the raw cases,
    // which is where a pipeline joins on them.

    private static func unitsWord(_ u: UnitSystem) -> String {
        switch u {
        case .imperial: return "Imperial"
        case .metric:   return "Metric"
        }
    }

    private static func plotTypeWord(_ t: PlotType) -> String {
        switch t {
        case .fixedArea:      return "Fixed-area plots"
        case .variableRadius: return "Variable-radius (prism) plots"
        }
    }

    private static func schemeWord(_ s: SamplingScheme) -> String {
        switch s {
        case .systematicGrid:   return "Systematic grid"
        case .stratifiedRandom: return "Stratified random"
        case .manual:           return "Placed by hand"
        }
    }

    /// Where on the trunk breast height is taken — NOT the breast-height
    /// value itself, which is fixed by the region.
    private static func breastHeightWord(_ c: BreastHeightConvention) -> String {
        switch c {
        case .uphill: return "On the uphill side"
        case .mid:    return "Mid-slope"
        case .any:    return "Any side"
        case .custom: return "Custom"
        }
    }

    private static func statusWord(_ s: TreeStatus) -> String {
        switch s {
        case .live:         return "Live"
        case .deadStanding: return "Dead standing"
        case .deadDown:     return "Dead down"
        case .cull:         return "Cull"
        }
    }

    /// The same word the confidence chip shows in the app, so the report
    /// and the phone describe one reading the same way.
    private static func qualityWord(_ t: ConfidenceTier) -> String {
        switch t {
        case .green:  return "Good"
        case .yellow: return "Fair"
        case .red:    return "Check"
        }
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
        // "imputed" is the statistics word the in-app twin already dropped
        // (PlotValidation / the tree report both say "estimated from this
        // project's height curve"); the client reading the PDF gets the
        // same sentence. The RULE is unchanged — only its description.
        case .none:     return "none — every height estimated from the height curve"
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
