// Spec §3.1 & §8. Phase 1 shipped plan-only export; Phase 6 adds the full
// cruise bundle (CSV × 5, GeoJSON × 2, Shapefile × 3, PDF report) via
// `FullCruiseExporter`.
//
// The view model:
//   • assembles an ExportBundle (reads through every repo),
//   • runs FullCruiseExporter on a background queue,
//   • publishes progress (0…1) and a cumulative artefact list,
//   • hands each artefact's URL to the owning view for share-sheet use.

import Foundation
import Models
import Persistence
import InventoryEngine
import Export

@MainActor
public final class ExportViewModel: ObservableObject {

    public struct ExportedFile: Identifiable, Hashable {
        public var id: URL { url }
        public let url: URL
        public let displayName: String
    }

    // MARK: - Published state

    @Published public private(set) var exportedFiles: [ExportedFile] = []
    @Published public private(set) var lastSessionFolder: URL?
    @Published public private(set) var progress: Double = 0  // 0…1
    @Published public private(set) var progressLabel: String = ""
    @Published public private(set) var isExporting: Bool = false
    @Published public var errorMessage: String?
    @Published public var shareURL: URL?

    // MARK: - Dependencies

    public let project: Project
    private var appEnv: AppEnvironment?

    public init(project: Project) { self.project = project }

    public func configure(with environment: AppEnvironment) {
        self.appEnv = environment
    }

    // MARK: - Phase 1 entry points (plan-only)

    public func exportCSV() {
        guard let env = appEnv else { return }
        do {
            let strata = try env.stratumRepository.listByProject(project.id)
            let plots = try env.plannedPlotRepository.listByProject(project.id)
            let csv = CSVExporter.plannedPlotsCSV(plannedPlots: plots, strata: strata)
            let url = try writeLegacy(data: Data(csv.utf8), suffix: "planned-plots.csv")
            appendExport(url: url, displayName: "Planned plots (CSV)")
        } catch {
            errorMessage = "CSV export failed: \(error.localizedDescription)"
        }
    }

    public func exportStratumCSV() {
        guard let env = appEnv else { return }
        do {
            let strata = try env.stratumRepository.listByProject(project.id)
            let csv = CSVExporter.stratumListCSV(strata: strata)
            let url = try writeLegacy(data: Data(csv.utf8), suffix: "strata.csv")
            appendExport(url: url, displayName: "Strata (CSV)")
        } catch {
            errorMessage = "Stratum CSV export failed: \(error.localizedDescription)"
        }
    }

    public func exportGeoJSON() {
        guard let env = appEnv else { return }
        do {
            let strata = try env.stratumRepository.listByProject(project.id)
            let plots = try env.plannedPlotRepository.listByProject(project.id)
            let text = try GeoJSONExporter.plan(strata: strata, plannedPlots: plots)
            let url = try writeLegacy(data: Data(text.utf8), suffix: "plan.geojson")
            appendExport(url: url, displayName: "Plan (GeoJSON)")
        } catch {
            errorMessage = "GeoJSON export failed: \(error.localizedDescription)"
        }
    }

    // MARK: - Phase 6 full-cruise export

    public func exportAll() {
        guard let env = appEnv, !isExporting else { return }
        isExporting = true
        progress = 0
        progressLabel = "Preparing…"

        // Run on MainActor — Core Data's viewContext is main-queue-bound and
        // a typical cruise (tens of plots, hundreds of trees) exports in a
        // few hundred milliseconds. If profiling shows the PDF/zip writes
        // blocking the UI for >1s, revisit and off-thread them with a
        // sendable snapshot copy of the bundle.
        Task { @MainActor in
            do {
                let bundle = try ExportBundleBuilder.build(
                    using: RepositoryExportDataSource(project: project, env: env))
                let base = try documentsRoot()
                let result = try FullCruiseExporter.write(
                    bundle: bundle,
                    into: base,
                    localization: PDFLocalization.forProject(
                        units: project.units,
                        species: bundle.species,
                        trees: bundle.trees),
                    progress: { [weak self] done, total, label in
                        // Already on MainActor (caller is too).
                        self?.progress = total == 0 ? 1 : Double(done) / Double(total)
                        self?.progressLabel = label
                    })
                self.lastSessionFolder = result.folder
                for art in result.artefacts {
                    self.exportedFiles.insert(
                        ExportedFile(url: art.url, displayName: art.displayName),
                        at: 0)
                }
                self.shareURL = result.folder
                self.isExporting = false
                self.progress = 1
                self.progressLabel = "Done"
            } catch {
                self.errorMessage = "Export failed: \(error.localizedDescription)"
                self.isExporting = false
                self.progress = 0
            }
        }
    }

    // MARK: - Single-artefact exports (accessible from the picker UI)

    public func exportTreesCSV() { runSingle(.csvTrees) }
    public func exportPlotsCSV() { runSingle(.csvPlots) }
    public func exportStandSummaryCSV() { runSingle(.csvStandSummary) }
    public func exportCruiseGeoJSON() { runSingle(.geojsonCruise) }
    public func exportShapefilePlots() { runSingle(.shapefilePlots) }
    public func exportPDFReport() { runSingle(.pdfReport) }

    private func runSingle(_ kind: ExportArtefact.Kind) {
        guard let env = appEnv else { return }
        do {
            let bundle = try ExportBundleBuilder.build(
                using: RepositoryExportDataSource(project: project, env: env))
            let base = try documentsRoot()
            let result = try FullCruiseExporter.write(
                bundle: bundle, into: base,
                localization: PDFLocalization.forProject(
                    units: project.units,
                    species: bundle.species,
                    trees: bundle.trees),
                progress: nil)
            lastSessionFolder = result.folder
            if let art = result.artefacts.first(where: { $0.kind == kind }) {
                appendExport(url: art.url, displayName: art.displayName)
            } else {
                // The cruiser asked for ONE file and it was not written.
                // A geographic layer with nothing to put in it is the
                // reachable case — every plot in the cruise has had its
                // centre removed from the map, or none was ever
                // recorded — and an empty shapefile is not a valid one.
                // Say so: a button that silently does nothing is
                // indistinguishable from a broken one.
                errorMessage = Self.missingArtefactMessage(kind)
            }
        } catch {
            errorMessage = "Export failed: \(error.localizedDescription)"
        }
    }

    /// Why a single-artefact export produced no file. Names what is
    /// missing and what to do, never just "failed".
    private static func missingArtefactMessage(
        _ kind: ExportArtefact.Kind
    ) -> String {
        switch kind {
        case .shapefilePlots:
            return "No plot in this cruise has a recorded centre, so there "
                + "is nothing to put in the shapefile. Record a centre from "
                + "the map, or export the plots as CSV — every tree you "
                + "tallied is still there."
        case .shapefileStrata:
            return "No stratum has a usable boundary, so there is nothing "
                + "to put in the shapefile."
        default:
            return "That file could not be written from this cruise."
        }
    }

    // MARK: - Sandbox paths

    private func documentsRoot() throws -> URL {
        try FileManager.default.url(
            for: .documentDirectory, in: .userDomainMask,
            appropriateFor: nil, create: true)
    }

    /// Legacy writer for the Phase 1 plan-only buttons — they still drop
    /// files into a per-project folder for quick access.
    private func writeLegacy(data: Data, suffix: String) throws -> URL {
        let docs = try documentsRoot()
        let folder = docs.appendingPathComponent("exports/\(project.id.uuidString)",
                                                 isDirectory: true)
        try FileManager.default.createDirectory(
            at: folder, withIntermediateDirectories: true)
        let timestamp = ISO8601DateFormatter().string(from: Date())
            .replacingOccurrences(of: ":", with: "-")
        let url = folder.appendingPathComponent("\(timestamp)-\(suffix)")
        try data.write(to: url, options: .atomic)
        return url
    }

    private func appendExport(url: URL, displayName: String) {
        let file = ExportedFile(url: url, displayName: displayName)
        exportedFiles.insert(file, at: 0)
        shareURL = url
    }
}

// MARK: - ExportDataSource adapter over AppEnvironment

/// Reads all repositories synchronously on the calling queue. `env` is
/// MainActor-isolated (its Core Data viewContext lives on the main
/// thread), so the callers must invoke this from the MainActor — the
/// Swift 5 compiler will warn about the cross-isolation reads rather
/// than error, which is acceptable until the whole package graduates
/// to strict concurrency.
public struct RepositoryExportDataSource: ExportDataSource {
    let cachedProject: Project
    let env: AppEnvironment

    public init(project: Project, env: AppEnvironment) {
        self.cachedProject = project; self.env = env
    }

    public func project() throws -> Project { cachedProject }

    public func cruiseDesign(forProjectId id: UUID) throws -> CruiseDesign {
        let designs = try env.cruiseDesignRepository.forProject(id)
        guard let d = designs.first else {
            throw ExportBundleError.designNotFound
        }
        return d
    }

    public func strata(forProjectId id: UUID) throws -> [Stratum] {
        try env.stratumRepository.listByProject(id)
    }

    public func plannedPlots(forProjectId id: UUID) throws -> [PlannedPlot] {
        try env.plannedPlotRepository.listByProject(id)
    }

    public func plots(forProjectId id: UUID) throws -> [Plot] {
        try env.plotRepository.listByProject(id)
    }

    public func trees(forPlotId id: UUID) throws -> [Tree] {
        try env.treeRepository.listByPlot(id, includeDeleted: true)
    }

    public func species() throws -> [SpeciesConfig] {
        try env.speciesRepository.list()
    }

    public func volumeEquations() throws -> [Models.VolumeEquation] {
        try env.volumeEquationRepository.list()
    }

    public func hdFits(forProjectId id: UUID) throws -> [HeightDiameterFit] {
        try env.hdFitRepository.listByProject(id)
    }
}

// MARK: - Report localisation

extension PDFLocalization {
    /// Build the PDF report's display localisation for an export bundle.
    /// `AreaUnit` and `RegionalSpecies` live in this (UI) layer, above the
    /// Export module, so the resolved area basis and species-name lookup are
    /// computed here and handed down as plain data. The area basis follows the
    /// project's own unit system (metric → per hectare, imperial → per acre),
    /// which mirrors the country the project was created under.
    static func forProject(units: UnitSystem,
                           species: [SpeciesConfig],
                           trees: [Tree]) -> PDFLocalization {
        let areaUnit: AreaUnit = units == .metric ? .hectare : .acre
        var names: [String: String] = [:]
        for sp in species where names[sp.code] == nil {
            names[sp.code] = RegionalSpecies.name(forCode: sp.code)
        }
        for t in trees where names[t.speciesCode] == nil {
            names[t.speciesCode] = RegionalSpecies.name(forCode: t.speciesCode)
        }
        return PDFLocalization(
            densityFactor: areaUnit.perAcreDensityFactor,
            areaSuffix: areaUnit.densitySuffix,
            areaAbbr: areaUnit.abbreviation,
            areaWord: areaUnit == .hectare ? "hectare" : "acre",
            speciesNames: names)
    }
}
