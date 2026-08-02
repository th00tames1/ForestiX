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

    /// Breast height, US convention: 4.5 ft written in metres.
    ///
    /// THE ONE PLACE THIS NUMBER LIVES ON ANDROID. Anything that draws,
    /// labels or reasons about breast height reads it from here, so the
    /// convention can be changed in a single edit. Kept equal to the iOS
    /// `Units.breastHeightM` — one rule, two platforms.
    ///
    /// It is NOT the 1.3 m that appears throughout the inventory engine
    /// (Näslund's asymptote in the H–D model, the `h > 1.3` volume guards,
    /// Laasasenaho's `(h − 1.3)` form). Those 1.3s belong to published
    /// equations and mean the international convention; retuning them to
    /// this value would move every fitted height and every volume in the
    /// corpus, which is a study-level decision, not a constant swap.
    const val BREAST_HEIGHT_M = 1.37

    const val CM_PER_INCH = 2.54
    fun cmToInches(cm: Double) = cm / CM_PER_INCH
    fun inchesToCm(inches: Double) = inches * CM_PER_INCH

    fun squareMetersToSquareFeet(m2: Double) = m2 * (1.0 / METERS_PER_FOOT) * (1.0 / METERS_PER_FOOT)
    fun squareFeetToSquareMeters(ft2: Double) = ft2 * METERS_PER_FOOT * METERS_PER_FOOT

    const val SQUARE_METERS_PER_ACRE = 4046.8564224
    fun acresToSquareMeters(ac: Double) = ac * SQUARE_METERS_PER_ACRE
    fun squareMetersToAcres(m2: Double) = m2 / SQUARE_METERS_PER_ACRE

    /// 1 hectare = 2.4710538147 acres (from the exact international-foot acre).
    /// Canonical single source for per-acre → per-hectare density conversion —
    /// AreaUnit multiplies a canonical per-acre figure by this to express it
    /// per hectare, and divides an area in acres by it to get hectares.
    const val ACRES_PER_HECTARE = 2.4710538147

    fun cubicMetersToCubicFeet(m3: Double) =
        m3 * (1.0 / METERS_PER_FOOT) * (1.0 / METERS_PER_FOOT) * (1.0 / METERS_PER_FOOT)

    fun circleAreaM2(radiusM: Double) = PI * radiusM * radiusM
}
