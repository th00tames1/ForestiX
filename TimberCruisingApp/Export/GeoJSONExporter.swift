// Spec §8 Export/GeoJSONExporter. Phase 1 scope: emit a FeatureCollection
// containing Stratum polygons (re-packaged from their stored `polygonGeoJSON`
// blob) and PlannedPlot point features. Phase 6 will layer measured plots
// and tree points on top.
//
// All coordinates are WGS84 decimal degrees; keys are sorted so two runs with
// identical inputs yield byte-identical files (important for change review).

import Foundation
import Models

public enum GeoJSONExporterError: Error {
    case serializationFailed
}

public enum GeoJSONExporter {

    public static func plan(
        strata: [Stratum],
        plannedPlots: [PlannedPlot]
    ) throws -> String {
        var features: [[String: Any]] = []
        features.append(contentsOf: strata.compactMap(stratumFeature))
        features.append(contentsOf: plannedPlots.map(plotFeature))

        let collection: [String: Any] = [
            "type": "FeatureCollection",
            "features": features
        ]
        let data = try JSONSerialization.data(
            withJSONObject: collection,
            options: [.sortedKeys, .prettyPrinted]
        )
        guard let string = String(data: data, encoding: .utf8) else {
            throw GeoJSONExporterError.serializationFailed
        }
        return string
    }

    // MARK: - Features

    static func stratumFeature(_ s: Stratum) -> [String: Any]? {
        guard let geometry = parseGeometry(s.polygonGeoJSON) else { return nil }
        return [
            "type": "Feature",
            "geometry": geometry,
            "properties": [
                "kind": "stratum",
                "id": s.id.uuidString,
                "name": s.name,
                "areaAcres": Double(s.areaAcres)
            ]
        ]
    }

    static func plotFeature(_ p: PlannedPlot) -> [String: Any] {
        [
            "type": "Feature",
            "geometry": [
                "type": "Point",
                "coordinates": [p.plannedLon, p.plannedLat]
            ],
            "properties": [
                "kind": "plannedPlot",
                "id": p.id.uuidString,
                "plotNumber": p.plotNumber,
                "stratumId": p.stratumId?.uuidString ?? NSNull(),
                "visited": p.visited
            ]
        ]
    }

    // MARK: - Helpers

    /// Strata store their polygon as a GeoJSON Geometry string. Round-trip it
    /// through JSONSerialization so the output is guaranteed-valid JSON — we
    /// don't want a malformed string in one stratum to poison the whole file.
    private static func parseGeometry(_ text: String) -> [String: Any]? {
        guard let data = text.data(using: .utf8),
              let any = try? JSONSerialization.jsonObject(with: data),
              let dict = any as? [String: Any],
              dict["type"] is String else {
            return nil
        }
        return dict
    }
}
