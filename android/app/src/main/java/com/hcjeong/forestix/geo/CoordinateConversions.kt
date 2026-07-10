// Geo coordinate utilities — 1:1 port of iOS Geo/CoordinateConversions.swift
// (spec §8). Lat/lon ↔ local ENU, haversine distance, initial bearing.
// Tangent-plane conversions use an equirectangular approximation at the
// origin latitude, matching the GPS-averaging and offset-from-opening
// formulas in §7.3.1 / §7.3.2. Acceptable for cruise-sized AOIs (<10 km).

package com.hcjeong.forestix.geo

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object CoordinateConversions {

    // MARK: - Earth model

    /// WGS84 mean Earth radius in metres.
    const val earthRadiusMeters: Double = 6_371_008.8

    /// §7.3.2 canonical metres-per-degree constant.
    const val metersPerDegreeLatitude: Double = 111_320.0

    // MARK: - Coordinates

    /// A decimal-degrees geographic coordinate (WGS84).
    data class LatLon(
        val latitude: Double,
        val longitude: Double,
    )

    /// A local East-North offset in metres from some origin.
    data class ENU(
        val east: Double,
        val north: Double,
    )

    // MARK: - Lat/lon ↔ ENU (equirectangular, origin-centred)

    /// Convert a geographic point to local ENU metres relative to `origin`.
    fun toENU(point: LatLon, origin: LatLon): ENU {
        val cosLat0 = cos(origin.latitude * PI / 180)
        val north = (point.latitude - origin.latitude) * metersPerDegreeLatitude
        val east = (point.longitude - origin.longitude) * metersPerDegreeLatitude * cosLat0
        return ENU(east = east, north = north)
    }

    /// Convert a local ENU offset in metres back to a geographic point relative to `origin`.
    fun toLatLon(enu: ENU, origin: LatLon): LatLon {
        val cosLat0 = cos(origin.latitude * PI / 180)
        if (cosLat0 == 0.0) return origin
        val dLat = enu.north / metersPerDegreeLatitude
        val dLon = enu.east / (metersPerDegreeLatitude * cosLat0)
        return LatLon(latitude = origin.latitude + dLat, longitude = origin.longitude + dLon)
    }

    // MARK: - Great-circle distance / bearing

    /// Haversine great-circle distance in metres between two WGS84 points.
    fun haversineMeters(a: LatLon, b: LatLon): Double {
        val phi1 = a.latitude * PI / 180
        val phi2 = b.latitude * PI / 180
        val dPhi = (b.latitude - a.latitude) * PI / 180
        val dLambda = (b.longitude - a.longitude) * PI / 180
        val h = sin(dPhi / 2) * sin(dPhi / 2) +
            cos(phi1) * cos(phi2) * sin(dLambda / 2) * sin(dLambda / 2)
        val c = 2 * atan2(sqrt(h), sqrt(1 - h))
        return earthRadiusMeters * c
    }

    /// Initial (forward) bearing in degrees clockwise from true north, range [0, 360).
    fun initialBearingDegrees(from: LatLon, to: LatLon): Double {
        val phi1 = from.latitude * PI / 180
        val phi2 = to.latitude * PI / 180
        val dLambda = (to.longitude - from.longitude) * PI / 180
        val y = sin(dLambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLambda)
        val deg = atan2(y, x) * 180 / PI
        return (deg + 360) % 360
    }
}
