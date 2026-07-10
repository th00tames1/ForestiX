// Imperial <-> metric conversions. Direct port of iOS Common/Units.swift.
// Pure functions, no platform dependencies.

package com.hcjeong.forestix.common

import kotlin.math.PI

/// Per-project unit system (REQ-CAL-001). Storage is always metric; this
/// only governs display + export rendering.
enum class UnitSystem { METRIC, IMPERIAL }

object Units {
    const val METERS_PER_FOOT = 0.3048
    fun metersToFeet(m: Double) = m / METERS_PER_FOOT
    fun feetToMeters(ft: Double) = ft * METERS_PER_FOOT

    const val CM_PER_INCH = 2.54
    fun cmToInches(cm: Double) = cm / CM_PER_INCH
    fun inchesToCm(inches: Double) = inches * CM_PER_INCH

    fun squareMetersToSquareFeet(m2: Double) = m2 * (1.0 / METERS_PER_FOOT) * (1.0 / METERS_PER_FOOT)
    fun squareFeetToSquareMeters(ft2: Double) = ft2 * METERS_PER_FOOT * METERS_PER_FOOT

    const val SQUARE_METERS_PER_ACRE = 4046.8564224
    fun acresToSquareMeters(ac: Double) = ac * SQUARE_METERS_PER_ACRE
    fun squareMetersToAcres(m2: Double) = m2 / SQUARE_METERS_PER_ACRE

    fun cubicMetersToCubicFeet(m3: Double) =
        m3 * (1.0 / METERS_PER_FOOT) * (1.0 / METERS_PER_FOOT) * (1.0 / METERS_PER_FOOT)

    fun circleAreaM2(radiusM: Double) = PI * radiusM * radiusM
}
