// The imported survey boundary — the shared, normalised shape every
// consumer (map renderer, Map settings sheet, on-disk store) sees.
//
// EVERY coordinate in here is WGS84 lon/lat decimal degrees. Nothing
// reaches these types without passing `SurveyBoundaryImporter`'s CRS
// gate (a WGS84 .prj for shapefiles, plus an absolute lon/lat range
// check on the parsed geometry for every format) — a boundary the app
// could not confirm is refused, never drawn.

import Foundation
import Common

/// One geometry from the imported file.
public struct BoundaryFeature: Equatable, Sendable, Codable {

    public enum Kind: String, Equatable, Sendable, Codable {
        /// Closed area. `rings[0]` is the outer ring; the rest are holes.
        case polygon
        /// Open path. Exactly one entry in `rings`.
        case line
        /// Single position. Exactly one entry in `rings`, holding one point.
        case point
    }

    public var kind: Kind
    public var rings: [[CoordinateConversions.LatLon]]
    /// Feature label from the source file, when it carried one.
    public var name: String?

    public init(kind: Kind,
                rings: [[CoordinateConversions.LatLon]],
                name: String? = nil) {
        self.kind = kind
        self.rings = rings
        self.name = name
    }

    /// Every coordinate in the feature, in order.
    public var allPoints: [CoordinateConversions.LatLon] { rings.flatMap { $0 } }
}

/// WHERE THE COORDINATES CAME FROM — a survey, or a fingertip.
///
/// An imported boundary was surveyed by someone with instruments and
/// arrived in a file; a drawn one was dragged across a satellite tile on a
/// phone. They are the same geometry type and they are not the same
/// evidence, so they are distinguishable forever: in the store's record
/// file (`origin=`), in the persisted GeoJSON (the `forestix:source`
/// member, which travels with the file into any GIS), and in the sheet's
/// current-state row.
///
/// This is the boundary-level analogue of `PositionSource.manual` on a plot
/// centre. The raw values are the strings written to disk and shared with
/// the Android sibling — do not rename them.
public enum BoundaryOrigin: String, Equatable, Sendable, Codable {
    /// Read out of a file the cruiser supplied (SHP / KML / KMZ / GeoJSON).
    case imported
    /// Drawn on the map inside the app.
    case drawn
}

/// The one boundary the app currently has loaded, plus the small record
/// the sheet shows (display name, feature count, import date).
public struct SurveyBoundary: Equatable, Sendable {

    /// `sourceFormat` for a boundary drawn in the app. The field otherwise
    /// names a FILE format ("SHP", "KML", …); a drawn boundary has no file,
    /// and saying so is more use than leaving the slot blank.
    public static let drawnSourceFormat = "Drawn"

    public var displayName: String
    /// When this boundary entered the app — the import for a file, the save
    /// for a drawn one.
    public var importedAt: Date
    /// Source file kind, e.g. "SHP", "KML", "KMZ", "GeoJSON"; `Drawn` when
    /// there was no file.
    public var sourceFormat: String
    /// Survey or sketch. Defaulted to `.imported` at the one call site that
    /// predates drawing, so nothing that reads a stored file changes shape.
    public var origin: BoundaryOrigin
    public var features: [BoundaryFeature]

    public init(displayName: String,
                importedAt: Date,
                sourceFormat: String,
                origin: BoundaryOrigin = .imported,
                features: [BoundaryFeature]) {
        self.displayName = displayName
        self.importedAt = importedAt
        self.sourceFormat = sourceFormat
        self.origin = origin
        self.features = features
    }

    public var featureCount: Int { features.count }

    /// Bounding box of everything in the boundary — used to frame the
    /// camera on the freshly imported file.
    public var boundingBox: (minLat: Double, maxLat: Double,
                             minLon: Double, maxLon: Double)? {
        var minLat = Double.infinity, maxLat = -Double.infinity
        var minLon = Double.infinity, maxLon = -Double.infinity
        var any = false
        for feature in features {
            for p in feature.allPoints {
                any = true
                minLat = Swift.min(minLat, p.latitude)
                maxLat = Swift.max(maxLat, p.latitude)
                minLon = Swift.min(minLon, p.longitude)
                maxLon = Swift.max(maxLon, p.longitude)
            }
        }
        guard any else { return nil }
        return (minLat, maxLat, minLon, maxLon)
    }

    // MARK: - Normalised GeoJSON

    /// RFC 7946 FeatureCollection — the canonical persisted form. Written
    /// with sorted keys so the file is byte-stable across relaunches.
    public func geoJSONData() throws -> Data {
        var features: [[String: Any]] = []
        for feature in self.features {
            let geometry: [String: Any]
            switch feature.kind {
            case .polygon:
                geometry = [
                    "type": "Polygon",
                    "coordinates": feature.rings.map { ring in
                        ring.map { [$0.longitude, $0.latitude] }
                    }
                ]
            case .line:
                geometry = [
                    "type": "LineString",
                    "coordinates": (feature.rings.first ?? []).map {
                        [$0.longitude, $0.latitude]
                    }
                ]
            case .point:
                let p = feature.rings.first?.first
                geometry = [
                    "type": "Point",
                    "coordinates": [p?.longitude ?? 0, p?.latitude ?? 0]
                ]
            }
            var properties: [String: Any] = [:]
            if let name = feature.name { properties["name"] = name }
            features.append([
                "type": "Feature",
                "properties": properties,
                "geometry": geometry
            ])
        }
        // RFC 7946 §6.1 permits foreign members, and this one has to be
        // here rather than only in the app's own record file: the GeoJSON
        // is what leaves the device. A stand outline opened in QGIS six
        // months from now must still say whether it was surveyed or
        // sketched, and a member every other reader ignores is exactly how
        // it says it. Namespaced so it cannot collide with a real
        // attribute; sorts between "features" and "type", so
        // `.sortedKeys` output stays byte-stable.
        let collection: [String: Any] = [
            "type": "FeatureCollection",
            "features": features,
            "forestix:source": origin.rawValue
        ]
        return try JSONSerialization.data(withJSONObject: collection,
                                          options: [.sortedKeys])
    }

    /// Inverse of `geoJSONData()` — reads the persisted collection back.
    public static func features(fromGeoJSON data: Data) throws -> [BoundaryFeature] {
        try SurveyBoundaryGeoJSON.parse(data)
    }
}
