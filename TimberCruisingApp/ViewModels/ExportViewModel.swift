// Spec §3.1 REQ-PRJ (plan-only export surface for Phase 1). Produces two
// files — planned-plots.csv and plan.geojson — in the app's Documents
// directory, and surfaces their URLs so the owning view can hand them to a
// share sheet.

import Foundation
import Models
import Persistence
import Export

@MainActor
public final class ExportViewModel: ObservableObject {

    public struct ExportedFile: Identifiable, Hashable {
        public var id: URL { url }
        public let url: URL
        public let displayName: String
    }

    @Published public private(set) var exportedFiles: [ExportedFile] = []
    @Published public var errorMessage: String?
    @Published public var shareURL: URL?

    public let project: Project
    private var stratumRepository: (any StratumRepository)?
    private var plannedPlotRepository: (any PlannedPlotRepository)?

    public init(project: Project) { self.project = project }

    public func configure(with environment: AppEnvironment) {
        if stratumRepository == nil { stratumRepository = environment.stratumRepository }
        if plannedPlotRepository == nil { plannedPlotRepository = environment.plannedPlotRepository }
    }

    // MARK: - Actions

    public func exportCSV() {
        guard let strataRepo = stratumRepository,
              let plotRepo = plannedPlotRepository
        else { return }
        do {
            let strata = try strataRepo.listByProject(project.id)
            let plots = try plotRepo.listByProject(project.id)
            let csv = CSVExporter.plannedPlotsCSV(plannedPlots: plots, strata: strata)
            let url = try write(data: Data(csv.utf8), suffix: "planned-plots.csv")
            appendExport(url: url, displayName: "Planned plots (CSV)")
        } catch {
            errorMessage = "CSV export failed: \(error)"
        }
    }

    public func exportStratumCSV() {
        guard let strataRepo = stratumRepository else { return }
        do {
            let strata = try strataRepo.listByProject(project.id)
            let csv = CSVExporter.stratumListCSV(strata: strata)
            let url = try write(data: Data(csv.utf8), suffix: "strata.csv")
            appendExport(url: url, displayName: "Strata (CSV)")
        } catch {
            errorMessage = "Stratum CSV export failed: \(error)"
        }
    }

    public func exportGeoJSON() {
        guard let strataRepo = stratumRepository,
              let plotRepo = plannedPlotRepository
        else { return }
        do {
            let strata = try strataRepo.listByProject(project.id)
            let plots = try plotRepo.listByProject(project.id)
            let text = try GeoJSONExporter.plan(strata: strata, plannedPlots: plots)
            let url = try write(data: Data(text.utf8), suffix: "plan.geojson")
            appendExport(url: url, displayName: "Plan (GeoJSON)")
        } catch {
            errorMessage = "GeoJSON export failed: \(error)"
        }
    }

    // MARK: - File writing

    private func write(data: Data, suffix: String) throws -> URL {
        let fm = FileManager.default
        let docs = try fm.url(for: .documentDirectory, in: .userDomainMask,
                              appropriateFor: nil, create: true)
        let folder = docs.appendingPathComponent("exports/\(project.id.uuidString)",
                                                 isDirectory: true)
        try fm.createDirectory(at: folder, withIntermediateDirectories: true)
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
