// Geodesy helpers — 1:1 port of iOS Positioning/GeoMath.swift.
// Used by NavigationScreen and the track log.
//
// The plot-center math lives in GPSAveraging / OffsetFromOpening
// (local ENU, good to sub-mm over 200 m). These helpers are the
// "how far / which bearing from here to there" piece that drives the
// compass arrow and the distance readout — distances can be
// kilometres across a cruise, so we use haversine + great-circle
// bearing rather than the linear approximation.

package com.hcjeong.forestix.positioning

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object GeoMath {

    /// Earth radius used throughout. WGS-84 authalic, rounded.
    const val earthRadiusM: Double = 6_371_000.0

    /// Haversine distance in metres between two lat/lon points.
    fun distanceM(
        fromLat: Double, fromLon: Double,
        toLat: Double, toLon: Double,
    ): Double {
        val phi1 = fromLat * PI / 180
        val phi2 = toLat * PI / 180
        val dPhi = (toLat - fromLat) * PI / 180
        val dLam = (toLon - fromLon) * PI / 180
        val a = sin(dPhi / 2) * sin(dPhi / 2) +
            cos(phi1) * cos(phi2) * sin(dLam / 2) * sin(dLam / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadiusM * c
    }

    /// Great-circle initial bearing from A to B in degrees true,
    /// normalised to 0 ≤ θ < 360.
    fun bearingDeg(
        fromLat: Double, fromLon: Double,
        toLat: Double, toLon: Double,
    ): Double {
        val phi1 = fromLat * PI / 180
        val phi2 = toLat * PI / 180
        val dLam = (toLon - fromLon) * PI / 180
        val y = sin(dLam) * cos(phi2)
        val x = cos(phi1) * sin(phi2) -
            sin(phi1) * cos(phi2) * cos(dLam)
        val theta = atan2(y, x) * 180 / PI
        return (theta + 360) % 360
    }
}
