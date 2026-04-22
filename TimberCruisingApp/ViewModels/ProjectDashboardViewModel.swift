// Spec §3.1 REQ-PRJ-002. Lists strata + planned plots and imports stratum
// boundaries from GeoJSON or KML files. Area for imported polygons comes
// from either the GeoJSON `areaAcres` property or a spherical-excess fallback.

import Foundation
import Models
import Persistence
import Geo

@MainActor
public final class ProjectDashboardViewModel: ObservableObject {

    public enum ImportFormat { case geoJSON, kml }

    @Published public private(set) var strata: [Stratum] = []
    @Published public private(set) var plannedPlots: [PlannedPlot] = []
    @Published public private(set) var design: CruiseDesign?
    /// Count of plots that have been closed (have `closedAt != nil`).
    /// Drives the step-3 "Measure in the field" progress check — the
    /// old dashboard hard-coded `done: false` here so the step never
    /// turned green even after a full cruise.
    @Published public private(set) var closedPlotCount: Int = 0
    /// Count of any Plot rows (closed or not) that exist for this
    /// project. Used so the Close banner can say "8 of 12 plots done".
    @Published public private(set) var totalPlotCount: Int = 0
    @Published public var errorMessage: String?
    @Published public var toastMessage: String?

    public let project: Project
    private var stratumRepository: (any StratumRepository)?
    private var plannedPlotRepository: (any PlannedPlotRepository)?
    private var designRepository: (any CruiseDesignRepository)?
    private var plotRepository: (any PlotRepository)?

    public init(project: Project) { self.project = project }

    public func configure(with environment: AppEnvironment) {
        if stratumRepository == nil { stratumRepository = environment.stratumRepository }
        if plannedPlotRepository == nil { plannedPlotRepository = environment.plannedPlotRepository }
        if designRepository == nil { designRepository = environment.cruiseDesignRepository }
        if plotRepository == nil { plotRepository = environment.plotRepository }
    }

    public func refresh() {
        refreshStrata()
        refreshPlannedPlots()
        refreshDesign()
        refreshPlotProgress()
    }

    /// Reloads the closed / total plot counts. Called on every
    /// dashboard appear so the step-3 progress check reflects work
    /// the cruiser did inside Go Cruise without needing a full
    /// `refresh()` round-trip on each detail-screen pop.
    public func refreshPlotProgress() {
        guard let repo = plotRepository else { return }
        do {
            let plots = try repo.listByProject(project.id)
            totalPlotCount = plots.count
            closedPlotCount = plots.filter { $0.closedAt != nil }.count
        } catch {
            // Non-fatal — dashboard progress is informational only.
        }
    }

    // MARK: - Cruise design

    public func refreshDesign() {
        guard let repo = designRepository else { return }
        do { design = try repo.forProject(project.id).first }
        catch { errorMessage = "Failed to load cruise design: \(error)" }
    }

    // MARK: - Strata

    public func refreshStrata() {
        guard let repo = stratumRepository else { return }
        do { strata = try repo.listByProject(project.id).sorted { $0.name < $1.name } }
        catch { errorMessage = "Failed to load strata: \(error)" }
    }

    public func importStrata(fileURL: URL, format: ImportFormat) {
        guard let repo = stratumRepository else { return }
        do {
            let data = try Data(contentsOf: fileURL)
            let imported: [ImportedPolygon]
            switch format {
            case .geoJSON: imported = try GeoJSONImporter.importStrata(from: data)
            case .kml:     imported = try KMLImporter.importStrata(from: data)
            }
            var created: [Stratum] = []
            for poly in imported {
                let stratum = Stratum(
                    id: UUID(),
                    projectId: project.id,
                    name: poly.name,
                    areaAcres: Float(poly.areaAcres),
                    polygonGeoJSON: poly.geoJSONString
                )
                created.append(try repo.create(stratum))
            }
            toastMessage = "Imported \(created.count) stratum\(created.count == 1 ? "" : "s")."
            refreshStrata()
        } catch {
            errorMessage = "Import failed: \(error)"
        }
    }

    public func delete(stratumId: UUID) {
        guard let repo = stratumRepository else { return }
        do {
            try repo.delete(id: stratumId)
            refreshStrata()
        } catch {
            errorMessage = "Failed to delete stratum: \(error)"
        }
    }

    // MARK: - Planned plots

    public func refreshPlannedPlots() {
        guard let repo = plannedPlotRepository else { return }
        do { plannedPlots = try repo.listByProject(project.id) }
        catch { errorMessage = "Failed to load planned plots: \(error)" }
    }

    // MARK: - Totals

    public var totalAcres: Double {
        strata.reduce(0) { $0 + Double($1.areaAcres) }
    }
}
