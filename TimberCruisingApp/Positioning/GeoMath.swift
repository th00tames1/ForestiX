// Geodesy helpers used by NavigationScreen and the track log.
//
// The plot-center math lives in GPSAveraging / OffsetFromOpening
// (local ENU, good to sub-mm over 200 m). These helpers are the
// "how far / which bearing from here to there" piece that drives the
// compass arrow and the distance readout — distances can be
// kilometres across a cruise, so we use haversine + great-circle
// bearing rather than the linear approximation.

import Foundation

public enum GeoMath {

    /// Earth radius used throughout. WGS-84 authalic, rounded.
    public static let earthRadiusM: Double = 6_371_000

    /// Haversine distance in metres between two lat/lon points.
    public static func distanceM(
        fromLat: Double, fromLon: Double,
        toLat: Double,   toLon: Double
    ) -> Double {
        let phi1 = fromLat * .pi / 180
        let phi2 = toLat   * .pi / 180
        let dPhi = (toLat  - fromLat) * .pi / 180
        let dLam = (toLon  - fromLon) * .pi / 180
        let a = sin(dPhi / 2) * sin(dPhi / 2)
              + cos(phi1) * cos(phi2) * sin(dLam / 2) * sin(dLam / 2)
        let c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadiusM * c
    }

    /// Great-circle initial bearing from A to B in degrees true,
    /// normalised to 0 ≤ θ < 360.
    public static func bearingDeg(
        fromLat: Double, fromLon: Double,
        toLat: Double,   toLon: Double
    ) -> Double {
        let phi1 = fromLat * .pi / 180
        let phi2 = toLat   * .pi / 180
        let dLam = (toLon  - fromLon) * .pi / 180
        let y = sin(dLam) * cos(phi2)
        let x = cos(phi1) * sin(phi2)
              - sin(phi1) * cos(phi2) * cos(dLam)
        let theta = atan2(y, x) * 180 / .pi
        return fmod(theta + 360, 360)
    }
}
