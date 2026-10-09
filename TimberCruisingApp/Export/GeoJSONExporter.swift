// Spec §8 Export/GeoJSONExporter. Phase 1 shipped plan-only export (strata
// polygons + planned-plot points). Phase 6 adds a full-cruise export:
//
//   * Stratum polygons (unchanged, re-exported for map validation).
//   * PlannedPlot points with `visited: Bool` property so downstream GIS
//     tools can distinguish visited vs. skipped plots at a glance.
//   * Measured-Plot points for every Plot that has been recorded, with the
//     full position-tier + tally metadata in their `properties`.
//
// All coordinates are WGS84 decimal degrees. Keys are sorted so two runs
// with identical inputs yield byte-identical files (important for
// reviewer diffs and golden-file tests).

import Foundation
import Models

public enum GeoJSONExporterError: Error, LocalizedError, CustomStringConvertible {
    case serializationFailed

    public var description: String {
        switch self {
        case .serializationFailed:
            return "The cruise could not be written as GeoJSON."
        }
    }

    // The export screens catch every failure and show `localizedDescription`.
    // Without this, a bare Swift Error prints "The operation couldn't be
    // completed (error 0.)", which tells a cruiser nothing about what to fix.
    public var errorDescription: String? { description }
}

public enum GeoJSONExporter {

    // MARK: - Phase 1 plan-only export

    public static func plan(
        strata: [Stratum],
        plannedPlots: [PlannedPlot]
    ) throws -> String {
        var features: [[String: Any]] = []
        features.append(contentsOf: strata.compactMap(stratumFeature))
        features.append(contentsOf: plannedPlots.map(plannedPlotFeature))

        return try serialize(featureCollection: features)
    }

    // MARK: - Phase 6 full-cruise export

    /// Full cruise export. Includes:
    ///   * strata polygons,
    ///   * all planned plots (with `visited` distinguishing planned-and-
    ///     visited from planned-and-skipped),
    ///   * every measured plot centre as its own Point feature.
    public static func cruise(
        strata: [Stratum],
        plannedPlots: [PlannedPlot],
        plots: [Plot]
    ) throws -> String {
        var features: [[String: Any]] = []
        features.append(contentsOf: strata.compactMap(stratumFeature))
        features.append(contentsOf: plannedPlots
            .sorted { $0.plotNumber < $1.plotNumber }
            .map(plannedPlotFeature))
        features.append(contentsOf: plots
            .sorted { $0.plotNumber < $1.plotNumber }
            .map(measuredPlotFeature))
        return try serialize(featureCollection: features)
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

    static func plannedPlotFeature(_ p: PlannedPlot) -> [String: Any] {
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
                "visited": p.visited,
                "skipped": p.skipped
            ]
        ]
    }

    static func measuredPlotFeature(_ p: Plot) -> [String: Any] {
        // NULL, not 0, below two samples: a spread needs two fixes to exist,
        // and zero here would read as "the fixes agreed perfectly" on a row
        // where none was ever measured. Same rule and same reasoning as
        // `gps_sample_std_xy_m` in plots.csv, and the same null this file
        // already uses for `offsetWalkM`. Spelled into a local because the
        // dictionary literal below is already at the size where the Swift
        // type-checker starts to labour.
        var stdXY: Any = NSNull()
        if p.gpsNSamples >= 2 { stdXY = Double(p.gpsSampleStdXyM) }
        var props: [String: Any] = [
            "kind": "measuredPlot",
            "id": p.id.uuidString,
            "plotNumber": p.plotNumber,
            "plannedPlotId": p.plannedPlotId?.uuidString ?? NSNull(),
            "positionSource": String(describing: p.positionSource),
            "positionTier": String(describing: p.positionTier),
            "gpsNSamples": p.gpsNSamples,
            "gpsMedianHAccuracyM": Double(p.gpsMedianHAccuracyM),
            "gpsSampleStdXyM": stdXY,
            "offsetWalkM": p.offsetWalkM.map { Double($0) } ?? NSNull(),
            "slopeDeg": Double(p.slopeDeg),
            "aspectDeg": Double(p.aspectDeg),
            "plotAreaAcres": Double(p.plotAreaAcres),
            "startedAt": iso8601(p.startedAt),
            "closedAt": p.closedAt.map(iso8601) ?? NSNull(),
            "closedBy": p.closedBy ?? NSNull()
        ]
        if !p.notes.isEmpty { props["notes"] = p.notes }
        // A plot with no recorded centre is an UNLOCATED feature, never a
        // point. RFC 7946 §3.2 allows `"geometry": null` for exactly this
        // — a thing that exists but has no position — and that is the
        // honest export: the plot, its tally metadata and every tree
        // measured in it survive the round trip, while nothing claims it
        // was cruised at 0°N 0°E in the Gulf of Guinea.
        //
        // The (0, 0) sentinel used to fall straight through into
        // `coordinates`, so a plot removed from the map (and any plot
        // that never got a centre) landed 600 km off the coast of Ghana,
        // dragging the file's bounding box across the Atlantic with it.
        let geometry: Any = p.hasCentre
            ? ["type": "Point", "coordinates": [p.centerLon, p.centerLat]]
            : NSNull()
        return [
            "type": "Feature",
            "geometry": geometry,
            "properties": props
        ]
    }

    // MARK: - Helpers

    private static func serialize(featureCollection: [[String: Any]]) throws -> String {
        let collection: [String: Any] = [
            "type": "FeatureCollection",
            "features": featureCollection
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

    private static let iso8601Formatter: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        return f
    }()

    private static func iso8601(_ d: Date) -> String { iso8601Formatter.string(from: d) }
}
