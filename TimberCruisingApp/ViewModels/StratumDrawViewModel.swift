// Phase 7.4 — "draw stratum on map" view model.
//
// The Phase 1 flow required the cruiser to prepare a GeoJSON / KML
// file off-device before they could even create a stratum. That's a
// hard barrier for new users — you can't open the app, walk out the
// door, and draw your block on the map. This VM backs a screen that
// lets the cruiser tap points on a map to outline the stratum, closes
// the ring automatically, computes area with the existing spherical-
// excess math, and persists via StratumRepository.

import Foundation
import CoreLocation
import Models
import Persistence
import Geo

@MainActor
public final class StratumDrawViewModel: ObservableObject {

    // MARK: - Editable state

    /// Ordered list of vertices the cruiser has tapped. The closing
    /// edge is implicit — when the cruiser taps Save (or taps within
    /// snapDistancePoints of the first vertex), the polygon is closed.
    @Published public private(set) var vertices: [CLLocationCoordinate2D] = []
    @Published public var name: String = ""

    // MARK: - Derived / display

    @Published public private(set) var areaAcres: Double = 0
    @Published public private(set) var isSaving: Bool = false
    @Published public var errorMessage: String?
    @Published public var didSave: Bool = false

    public let project: Project
    private var stratumRepository: (any StratumRepository)?

    public init(project: Project) { self.project = project }

    public func configure(with environment: AppEnvironment) {
        if stratumRepository == nil {
            stratumRepository = environment.stratumRepository
        }
    }

    // MARK: - Editing

    /// Append a vertex from a map tap. Coordinates are WGS84.
    public func addVertex(_ coord: CLLocationCoordinate2D) {
        vertices.append(coord)
        recomputeArea()
    }

    /// Remove the most recently-added vertex ("undo").
    public func removeLast() {
        guard !vertices.isEmpty else { return }
        vertices.removeLast()
        recomputeArea()
    }

    public func clear() {
        vertices = []
        areaAcres = 0
        name = ""
        errorMessage = nil
        didSave = false
    }

    /// Whether the cruiser has enough vertices for a valid polygon.
    public var canSave: Bool {
        vertices.count >= 3 &&
        !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
        !isSaving
    }

    public var vertexCountLabel: String {
        switch vertices.count {
        case 0: return "지도에서 모서리 점을 찍어주세요"
        case 1: return "1 개 찍힘 — 최소 3 개 필요"
        case 2: return "2 개 찍힘 — 1 개 더"
        default: return "\(vertices.count) 개 찍힘 · 닫힌 도형"
        }
    }

    // MARK: - Save

    public func save() {
        guard let repo = stratumRepository else {
            errorMessage = "저장소에 연결되지 않았습니다. 앱을 재시작 후 다시 시도해 주세요."
            return
        }
        guard canSave else {
            errorMessage = "최소 3 개의 점과 구역 이름이 필요합니다."
            return
        }
        isSaving = true
        defer { isSaving = false }

        let ring = vertices.map {
            CoordinateConversions.LatLon(latitude: $0.latitude, longitude: $0.longitude)
        }
        // Close the ring if the first and last points differ.
        var closedRing = ring
        if let first = ring.first, let last = ring.last,
           first != last {
            closedRing.append(first)
        }

        let geoJSON = GeoJSONImporter.serialise(rings: [closedRing])
        let areaM2 = abs(GeoJSONImporter.signedPolygonAreaMetersSquared(rings: [closedRing]))
        let acres = GeoJSONImporter.metersSquaredToAcres(areaM2)

        do {
            let stratum = Stratum(
                id: UUID(),
                projectId: project.id,
                name: name.trimmingCharacters(in: .whitespacesAndNewlines),
                areaAcres: Float(acres),
                polygonGeoJSON: geoJSON)
            _ = try repo.create(stratum)
            didSave = true
        } catch {
            errorMessage = "저장에 실패했습니다: \(error.localizedDescription). 저장 공간을 확인한 뒤 다시 시도하세요."
        }
    }

    // MARK: - Helpers

    private func recomputeArea() {
        guard vertices.count >= 3 else {
            areaAcres = 0
            return
        }
        let ring = vertices.map {
            CoordinateConversions.LatLon(latitude: $0.latitude, longitude: $0.longitude)
        }
        var closed = ring
        if let first = ring.first, let last = ring.last, first != last {
            closed.append(first)
        }
        let m2 = abs(GeoJSONImporter.signedPolygonAreaMetersSquared(rings: [closed]))
        areaAcres = GeoJSONImporter.metersSquaredToAcres(m2)
    }
}

// MARK: - CLLocationCoordinate2D == convenience

extension CLLocationCoordinate2D: @retroactive Equatable {
    public static func == (lhs: CLLocationCoordinate2D,
                           rhs: CLLocationCoordinate2D) -> Bool {
        lhs.latitude == rhs.latitude && lhs.longitude == rhs.longitude
    }
}
