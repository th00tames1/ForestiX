// Spec §8 Geo/GeoJSONImporter. REQ-PRJ-002: import stratum boundaries from a
// GeoJSON FeatureCollection. Accepts Polygon and MultiPolygon geometries in
// WGS84 decimal degrees.
//
// If a feature's properties supply `name` or `areaAcres`, those values are
// used; otherwise the importer falls back to a sensible name and computes
// area via the spherical-excess formula (§8 "spherical excess area" note in
// the spec). The resulting `ImportedPolygon` carries both the original
// geometry (re-serialised to a `Polygon` GeoJSON string for persistence on
// the Stratum record) and the computed area in acres.

import Foundation

public enum GeoJSONImportError: Error, CustomStringConvertible {
    case malformedJSON(String)
    case unsupportedGeometry(String)
    case emptyFeatureCollection
    case invalidCoordinate(String)

    public var description: String {
        switch self {
        case .malformedJSON(let r): return "Malformed GeoJSON: \(r)"
        case .unsupportedGeometry(let r): return "Unsupported geometry: \(r)"
        case .emptyFeatureCollection: return "GeoJSON has no features"
        case .invalidCoordinate(let r): return "Invalid coordinate: \(r)"
        }
    }
}

/// A polygon resolved from one GeoJSON feature. `rings` stores the outer ring
/// first, followed by any inner (hole) rings; each ring is a closed list of
/// WGS84 points in lon/lat order.
public struct ImportedPolygon: Equatable, Sendable {
    public var name: String
    public var areaAcres: Double
    public var rings: [[CoordinateConversions.LatLon]]   // ring 0 = outer; rest = holes
    public var geoJSONString: String                     // canonical serialisation

    public init(
        name: String,
        areaAcres: Double,
        rings: [[CoordinateConversions.LatLon]],
        geoJSONString: String
    ) {
        self.name = name
        self.areaAcres = areaAcres
        self.rings = rings
        self.geoJSONString = geoJSONString
    }
}

public enum GeoJSONImporter {

    // MARK: - Public API

    /// Parse a GeoJSON document (FeatureCollection / Feature / bare Polygon /
    /// MultiPolygon) into a list of `ImportedPolygon`s.
    public static func importStrata(from data: Data) throws -> [ImportedPolygon] {
        let any: Any
        do {
            any = try JSONSerialization.jsonObject(with: data, options: [])
        } catch {
            throw GeoJSONImportError.malformedJSON(error.localizedDescription)
        }
        guard let object = any as? [String: Any] else {
            throw GeoJSONImportError.malformedJSON("Top-level JSON is not an object")
        }

        let result = try collect(object: object, inheritedProperties: [:])
        guard !result.isEmpty else { throw GeoJSONImportError.emptyFeatureCollection }
        return result
    }

    // MARK: - Recursive collect

    private static func collect(
        object: [String: Any],
        inheritedProperties: [String: Any]
    ) throws -> [ImportedPolygon] {
        let type = (object["type"] as? String) ?? ""
        switch type {
        case "FeatureCollection":
            guard let features = object["features"] as? [[String: Any]] else {
                throw GeoJSONImportError.malformedJSON("FeatureCollection missing features array")
            }
            var out: [ImportedPolygon] = []
            for feature in features {
                out.append(contentsOf: try collect(object: feature, inheritedProperties: [:]))
            }
            return out

        case "Feature":
            let props = (object["properties"] as? [String: Any]) ?? [:]
            guard let geometry = object["geometry"] as? [String: Any] else {
                throw GeoJSONImportError.malformedJSON("Feature missing geometry")
            }
            return try collect(object: geometry, inheritedProperties: props)

        case "Polygon":
            let polygon = try parsePolygon(object: object)
            return [try buildImported(rings: polygon, properties: inheritedProperties)]

        case "MultiPolygon":
            let polygons = try parseMultiPolygon(object: object)
            return try polygons.enumerated().map { idx, rings in
                var props = inheritedProperties
                if props["name"] == nil, let base = inheritedProperties["name"] as? String {
                    props["name"] = "\(base) #\(idx + 1)"
                }
                return try buildImported(rings: rings, properties: props)
            }

        default:
            throw GeoJSONImportError.unsupportedGeometry("type=\(type.isEmpty ? "<missing>" : type)")
        }
    }

    // MARK: - Geometry parsing

    private static func parsePolygon(object: [String: Any]) throws -> [[CoordinateConversions.LatLon]] {
        guard let coords = object["coordinates"] as? [[[Double]]] else {
            throw GeoJSONImportError.malformedJSON("Polygon coordinates malformed")
        }
        return try coords.map(parseRing)
    }

    private static func parseMultiPolygon(object: [String: Any]) throws -> [[[CoordinateConversions.LatLon]]] {
        guard let coords = object["coordinates"] as? [[[[Double]]]] else {
            throw GeoJSONImportError.malformedJSON("MultiPolygon coordinates malformed")
        }
        return try coords.map { polygon in try polygon.map(parseRing) }
    }

    private static func parseRing(_ ring: [[Double]]) throws -> [CoordinateConversions.LatLon] {
        guard ring.count >= 4 else {
            throw GeoJSONImportError.invalidCoordinate("Ring has < 4 positions (must close)")
        }
        return try ring.map { pair in
            guard pair.count >= 2 else {
                throw GeoJSONImportError.invalidCoordinate("Position has < 2 values")
            }
            let lon = pair[0]
            let lat = pair[1]
            guard (-180...180).contains(lon), (-90...90).contains(lat) else {
                throw GeoJSONImportError.invalidCoordinate("lat/lon out of range (\(lat), \(lon))")
            }
            return CoordinateConversions.LatLon(latitude: lat, longitude: lon)
        }
    }

    // MARK: - Assembly

    private static func buildImported(
        rings: [[CoordinateConversions.LatLon]],
        properties: [String: Any]
    ) throws -> ImportedPolygon {
        let name = (properties["name"] as? String).map(trim) ?? "Stratum"

        let suppliedAcres: Double? = {
            if let v = properties["areaAcres"] as? Double { return v }
            if let v = properties["areaAcres"] as? Int { return Double(v) }
            if let s = properties["areaAcres"] as? String, let v = Double(s) { return v }
            return nil
        }()

        let computedAreaM2 = signedPolygonAreaMetersSquared(rings: rings)
        let areaAcres = suppliedAcres ?? metersSquaredToAcres(abs(computedAreaM2))
        let geoJSONString = serialise(rings: rings)

        return ImportedPolygon(
            name: name.isEmpty ? "Stratum" : name,
            areaAcres: areaAcres,
            rings: rings,
            geoJSONString: geoJSONString
        )
    }

    // MARK: - Area (spherical excess)

    /// Polygon area in m² using the spherical-excess formula. Outer ring is
    /// positive; subsequent rings (holes) are subtracted. Sign of each ring
    /// is determined by its winding orientation; absolute value is taken by
    /// the caller when total area is reported.
    public static func signedPolygonAreaMetersSquared(
        rings: [[CoordinateConversions.LatLon]]
    ) -> Double {
        guard let outer = rings.first else { return 0 }
        var area = sphericalRingArea(outer)
        for hole in rings.dropFirst() {
            area -= abs(sphericalRingArea(hole))
        }
        return area
    }

    /// Signed area of a single ring in m² (spherical-excess / L'Huilier-style
    /// reduction). Positive for counter-clockwise rings when viewed from
    /// outside the sphere.
    public static func sphericalRingArea(_ ring: [CoordinateConversions.LatLon]) -> Double {
        guard ring.count >= 4 else { return 0 }
        var total: Double = 0
        let R = CoordinateConversions.earthRadiusMeters
        for i in 0..<(ring.count - 1) {
            let p1 = ring[i]
            let p2 = ring[i + 1]
            let λ1 = p1.longitude * .pi / 180
            let λ2 = p2.longitude * .pi / 180
            let φ1 = p1.latitude * .pi / 180
            let φ2 = p2.latitude * .pi / 180
            total += (λ2 - λ1) * (sin(φ1) + sin(φ2))
        }
        return total * R * R / 2
    }

    public static let metersSquaredPerAcre: Double = 4_046.8564224

    public static func metersSquaredToAcres(_ m2: Double) -> Double {
        m2 / metersSquaredPerAcre
    }

    // MARK: - Serialisation

    /// Canonical Polygon GeoJSON string for persistence on `Stratum.polygonGeoJSON`.
    public static func serialise(rings: [[CoordinateConversions.LatLon]]) -> String {
        let coords: [[[Double]]] = rings.map { ring in
            ring.map { [$0.longitude, $0.latitude] }
        }
        let geometry: [String: Any] = [
            "type": "Polygon",
            "coordinates": coords
        ]
        if let data = try? JSONSerialization.data(withJSONObject: geometry, options: [.sortedKeys]),
           let s = String(data: data, encoding: .utf8) {
            return s
        }
        return "{}"
    }

    // MARK: - Helpers

    private static func trim(_ s: String) -> String {
        s.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}
