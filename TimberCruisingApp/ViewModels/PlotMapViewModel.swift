// Spec §3.1 REQ-PRJ-004 — render stratum polygons + planned plot markers.

import Foundation
import Models
import Persistence
import Geo

@MainActor
public final class PlotMapViewModel: ObservableObject {

    public struct StratumShape: Identifiable {
        public let id: UUID
        public let name: String
        public let rings: [[CoordinateConversions.LatLon]]
    }

    @Published public private(set) var strataShapes: [StratumShape] = []
    @Published public private(set) var plannedPlots: [PlannedPlot] = []
    @Published public var errorMessage: String?

    public let project: Project
    private var stratumRepository: (any StratumRepository)?
    private var plannedPlotRepository: (any PlannedPlotRepository)?

    public init(project: Project) { self.project = project }

    public func configure(with environment: AppEnvironment) {
        if stratumRepository == nil { stratumRepository = environment.stratumRepository }
        if plannedPlotRepository == nil { plannedPlotRepository = environment.plannedPlotRepository }
    }

    public func refresh() {
        guard let stratumRepository, let plannedPlotRepository else { return }
        do {
            let strata = try stratumRepository.listByProject(project.id)
            strataShapes = strata.map { s in
                let rings = (try? parseRings(from: s.polygonGeoJSON)) ?? []
                return StratumShape(id: s.id, name: s.name, rings: rings)
            }
            plannedPlots = try plannedPlotRepository.listByProject(project.id)
        } catch {
            errorMessage = "Failed to load map data: \(error)"
        }
    }

    public var centroid: CoordinateConversions.LatLon? {
        let points = strataShapes.flatMap { $0.rings.first ?? [] }
        guard !points.isEmpty else {
            if let plot = plannedPlots.first {
                return .init(latitude: plot.plannedLat, longitude: plot.plannedLon)
            }
            return nil
        }
        let lat = points.map(\.latitude).reduce(0, +) / Double(points.count)
        let lon = points.map(\.longitude).reduce(0, +) / Double(points.count)
        return .init(latitude: lat, longitude: lon)
    }

    // MARK: - Helpers

    private func parseRings(from geojson: String) throws -> [[CoordinateConversions.LatLon]] {
        guard let data = geojson.data(using: .utf8),
              let obj = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              obj["type"] as? String == "Polygon",
              let coords = obj["coordinates"] as? [[[Double]]]
        else { return [] }
        return coords.map { ring in
            ring.compactMap { pair in
                guard pair.count >= 2 else { return nil }
                return CoordinateConversions.LatLon(latitude: pair[1], longitude: pair[0])
            }
        }
    }
}
