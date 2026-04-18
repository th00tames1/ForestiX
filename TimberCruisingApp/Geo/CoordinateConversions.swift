// Spec §8 Geo/ utilities. Lat/lon ↔ local ENU, haversine distance, initial
// bearing. Tangent-plane conversions use an equirectangular approximation at
// the origin latitude, matching the GPS-averaging and offset-from-opening
// formulas in §7.3.1 / §7.3.2. Acceptable for cruise-sized AOIs (<10 km).

import Foundation

public enum CoordinateConversions {

    // MARK: - Earth model

    /// WGS84 mean Earth radius in metres.
    public static let earthRadiusMeters: Double = 6_371_008.8
    /// §7.3.2 canonical metres-per-degree constant.
    public static let metersPerDegreeLatitude: Double = 111_320.0

    // MARK: - Coordinates

    /// A decimal-degrees geographic coordinate (WGS84).
    public struct LatLon: Equatable, Hashable, Codable, Sendable {
        public var latitude: Double
        public var longitude: Double

        public init(latitude: Double, longitude: Double) {
            self.latitude = latitude
            self.longitude = longitude
        }
    }

    /// A local East-North offset in metres from some origin.
    public struct ENU: Equatable, Hashable, Codable, Sendable {
        public var east: Double
        public var north: Double

        public init(east: Double, north: Double) {
            self.east = east
            self.north = north
        }
    }

    // MARK: - Lat/lon ↔ ENU (equirectangular, origin-centred)

    /// Convert a geographic point to local ENU metres relative to `origin`.
    public static func toENU(point: LatLon, origin: LatLon) -> ENU {
        let cosLat0 = cos(origin.latitude * .pi / 180)
        let north = (point.latitude - origin.latitude) * metersPerDegreeLatitude
        let east = (point.longitude - origin.longitude) * metersPerDegreeLatitude * cosLat0
        return ENU(east: east, north: north)
    }

    /// Convert a local ENU offset in metres back to a geographic point relative to `origin`.
    public static func toLatLon(enu: ENU, origin: LatLon) -> LatLon {
        let cosLat0 = cos(origin.latitude * .pi / 180)
        guard cosLat0 != 0 else { return origin }
        let dLat = enu.north / metersPerDegreeLatitude
        let dLon = enu.east / (metersPerDegreeLatitude * cosLat0)
        return LatLon(latitude: origin.latitude + dLat, longitude: origin.longitude + dLon)
    }

    // MARK: - Great-circle distance / bearing

    /// Haversine great-circle distance in metres between two WGS84 points.
    public static func haversineMeters(_ a: LatLon, _ b: LatLon) -> Double {
        let φ1 = a.latitude * .pi / 180
        let φ2 = b.latitude * .pi / 180
        let dφ = (b.latitude - a.latitude) * .pi / 180
        let dλ = (b.longitude - a.longitude) * .pi / 180
        let h = sin(dφ / 2) * sin(dφ / 2)
              + cos(φ1) * cos(φ2) * sin(dλ / 2) * sin(dλ / 2)
        let c = 2 * atan2(sqrt(h), sqrt(1 - h))
        return earthRadiusMeters * c
    }

    /// Initial (forward) bearing in degrees clockwise from true north, range [0, 360).
    public static func initialBearingDegrees(from a: LatLon, to b: LatLon) -> Double {
        let φ1 = a.latitude * .pi / 180
        let φ2 = b.latitude * .pi / 180
        let dλ = (b.longitude - a.longitude) * .pi / 180
        let y = sin(dλ) * cos(φ2)
        let x = cos(φ1) * sin(φ2) - sin(φ1) * cos(φ2) * cos(dλ)
        let deg = atan2(y, x) * 180 / .pi
        return (deg + 360).truncatingRemainder(dividingBy: 360)
    }
}
