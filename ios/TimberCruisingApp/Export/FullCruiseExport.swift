// Phase 6 orchestration — spec §8 & §9.2 "all three export formats
// produce valid files."
//
// Takes a fully-populated `ExportBundle` and writes every artefact for a
// single cruise-export session into a single timestamped folder inside
// the sandbox. The folder is:
//
//   Documents/Exports/<project_name>_<yyyyMMdd_HHmmss>/
//
// Each artefact is written atomically. Progress callbacks fire before
// each file so the UI can show a determinate progress bar.

import Foundation
import Models
import InventoryEngine

public struct ExportArtefact: Identifiable, Hashable {
    public var id: URL { url }
    public let url: URL
    public let displayName: String
    public let kind: Kind

    public enum Kind: String, Sendable {
        case csvTrees, csvPlots, csvStandSummary
        case csvStrata, csvPlanned
        case geojsonCruise, geojsonPlan
        case shapefilePlots, shapefilePlanned, shapefileStrata
        case pdfReport
        case gpxTrack
    }

    public init(url: URL, displayName: String, kind: Kind) {
        self.url = url
        self.displayName = displayName
        self.kind = kind
    }
}

public struct FullCruiseExportResult {
    public let folder: URL
    public let artefacts: [ExportArtefact]
}

public enum FullCruiseExporter {

    /// Write the full cruise export bundle into a timestamped folder.
    /// `progress` fires with `(completedSteps, totalSteps, currentLabel)`.
    public static func write(
        bundle: ExportBundle,
        into baseFolder: URL,
        fileManager: FileManager = .default,
        progress: ((Int, Int, String) -> Void)? = nil
    ) throws -> FullCruiseExportResult {

        let folder = try sessionFolder(
            base: baseFolder, projectName: bundle.project.name,
            generatedAt: bundle.generatedAt, fileManager: fileManager)

        // Steps (PDF writes last because it is the slowest). Order reflects
        // a natural progress sequence; items may be dropped if the cruise
        // has nothing to write for that kind (e.g. no strata ⇒ no polygon
        // shapefile).
        var steps: [(String, ExportArtefact.Kind)] = []
        steps.append(("Trees CSV",                .csvTrees))
        steps.append(("Plots CSV",                .csvPlots))
        steps.append(("Stand summary CSV",        .csvStandSummary))
        steps.append(("Strata CSV",               .csvStrata))
        steps.append(("Planned plots CSV",        .csvPlanned))
        steps.append(("Cruise GeoJSON",           .geojsonCruise))
        steps.append(("Plan GeoJSON",             .geojsonPlan))
        if !bundle.plots.isEmpty {
            steps.append(("Plot centres shapefile", .shapefilePlots))
        }
        if !bundle.plannedPlots.isEmpty {
            steps.append(("Planned plots shapefile", .shapefilePlanned))
        }
        if !bundle.strata.isEmpty {
            steps.append(("Strata shapefile",      .shapefileStrata))
        }
        steps.append(("PDF report", .pdfReport))

        var artefacts: [ExportArtefact] = []
        let total = steps.count

        for (i, step) in steps.enumerated() {
            progress?(i, total, step.0)
            if let art = try writeOne(step.1, into: folder, bundle: bundle) {
                artefacts.append(art)
            }
        }
        progress?(total, total, "Done")
        return FullCruiseExportResult(folder: folder, artefacts: artefacts)
    }

    // MARK: - Per-artefact writers

    private static func writeOne(
        _ kind: ExportArtefact.Kind,
        into folder: URL,
        bundle: ExportBundle
    ) throws -> ExportArtefact? {
        switch kind {
        case .csvTrees:
            let csv = CSVExporter.treesCSV(trees: bundle.trees)
            return try writeData(Data(csv.utf8), name: "trees.csv",
                                 display: "Trees (CSV)", kind: kind,
                                 folder: folder)
        case .csvPlots:
            let csv = CSVExporter.plotsCSV(
                plots: bundle.plots, statsByPlot: bundle.plotStatsByPlot)
            return try writeData(Data(csv.utf8), name: "plots.csv",
                                 display: "Plots (CSV)", kind: kind,
                                 folder: folder)
        case .csvStandSummary:
            let csv = CSVExporter.standSummaryCSV(
                tpa: bundle.tpaStand, ba: bundle.baStand,
                volume: bundle.volStand,
                stratumNamesByKey: bundle.stratumNamesByKey)
            return try writeData(Data(csv.utf8), name: "stand-summary.csv",
                                 display: "Stand summary (CSV)",
                                 kind: kind, folder: folder)
        case .csvStrata:
            let csv = CSVExporter.stratumListCSV(strata: bundle.strata)
            return try writeData(Data(csv.utf8), name: "strata.csv",
                                 display: "Strata (CSV)",
                                 kind: kind, folder: folder)
        case .csvPlanned:
            let csv = CSVExporter.plannedPlotsCSV(
                plannedPlots: bundle.plannedPlots, strata: bundle.strata)
            return try writeData(Data(csv.utf8), name: "planned-plots.csv",
                                 display: "Planned plots (CSV)",
                                 kind: kind, folder: folder)
        case .geojsonCruise:
            let s = try GeoJSONExporter.cruise(
                strata: bundle.strata,
                plannedPlots: bundle.plannedPlots,
                plots: bundle.plots)
            return try writeData(Data(s.utf8), name: "cruise.geojson",
                                 display: "Cruise (GeoJSON)",
                                 kind: kind, folder: folder)
        case .geojsonPlan:
            let s = try GeoJSONExporter.plan(
                strata: bundle.strata,
                plannedPlots: bundle.plannedPlots)
            return try writeData(Data(s.utf8), name: "plan.geojson",
                                 display: "Plan (GeoJSON)",
                                 kind: kind, folder: folder)
        case .shapefilePlots:
            let data = try ShapefileExporter.plotCentersZip(plots: bundle.plots)
            return try writeData(data, name: "plots-shp.zip",
                                 display: "Plot centres (Shapefile)",
                                 kind: kind, folder: folder)
        case .shapefilePlanned:
            let data = try ShapefileExporter.plannedPlotsZip(
                plannedPlots: bundle.plannedPlots)
            return try writeData(data, name: "planned-shp.zip",
                                 display: "Planned plots (Shapefile)",
                                 kind: kind, folder: folder)
        case .shapefileStrata:
            do {
                let data = try ShapefileExporter.strataZip(strata: bundle.strata)
                return try writeData(data, name: "strata-shp.zip",
                                     display: "Strata (Shapefile)",
                                     kind: kind, folder: folder)
            } catch ShapefileExporterError.emptyLayer {
                return nil  // All strata had invalid geometry — silently skip.
            }
        case .pdfReport:
            let inputs = PDFReportInputs(
                project: bundle.project,
                design: bundle.design,
                strata: bundle.strata,
                species: bundle.species,
                plots: bundle.plots,
                trees: bundle.trees,
                plotStatsByPlot: bundle.plotStatsByPlot,
                tpaStand: bundle.tpaStand,
                baStand: bundle.baStand,
                volStand: bundle.volStand,
                generatedAt: bundle.generatedAt)
            let url = folder.appendingPathComponent("report.pdf")
            try PDFReportBuilder.write(inputs, to: url)
            return ExportArtefact(
                url: url, displayName: "PDF report", kind: kind)
        case .gpxTrack:
            return nil  // Reserved for a later pass; GPXExporter needs track data.
        }
    }

    // MARK: - Folder + file helpers

    private static let folderTimestampFormatter: DateFormatter = {
        let f = DateFormatter()
        f.calendar = Calendar(identifier: .gregorian)
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "UTC")
        f.dateFormat = "yyyyMMdd_HHmmss"
        return f
    }()

    /// Produces `<baseFolder>/Exports/<sanitizedProjectName>_<UTC timestamp>`.
    public static func sessionFolder(
        base baseFolder: URL,
        projectName: String,
        generatedAt: Date,
        fileManager: FileManager = .default
    ) throws -> URL {
        let exports = baseFolder.appendingPathComponent("Exports",
                                                        isDirectory: true)
        let sanitized = sanitizeForFilename(projectName)
        let stamp = folderTimestampFormatter.string(from: generatedAt)
        let folder = exports.appendingPathComponent(
            "\(sanitized)_\(stamp)", isDirectory: true)
        try fileManager.createDirectory(
            at: folder, withIntermediateDirectories: true)
        return folder
    }

    private static func writeData(
        _ data: Data, name: String, display: String,
        kind: ExportArtefact.Kind, folder: URL
    ) throws -> ExportArtefact {
        let url = folder.appendingPathComponent(name)
        try data.write(to: url, options: .atomic)
        return ExportArtefact(url: url, displayName: display, kind: kind)
    }

    private static func sanitizeForFilename(_ s: String) -> String {
        let allowed = CharacterSet.alphanumerics
            .union(CharacterSet(charactersIn: "-_"))
        var out = ""
        for scalar in s.unicodeScalars {
            if allowed.contains(scalar) { out.unicodeScalars.append(scalar) }
            else { out.append("-") }
        }
        let collapsed = out.replacingOccurrences(
            of: "-+", with: "-", options: .regularExpression)
        return collapsed.isEmpty ? "project" : collapsed
    }
}
