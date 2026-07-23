// Metric stem-volume engine — generic form-factor volume for Central-European
// (German) species. An APPROXIMATION, deliberately labelled as such: Germany's
// production standard is the spline-based BDAT taper program, which has no
// short closed form (see docs/volume-standards-research.md §C). The form
// factor gives a usable first-order m³ estimate when a full taper model is
// not bundled.
//
//   V_m³ = g · h · f
//     g = π · (d/100)² / 4   (basal area at breast height, m², d in cm)
//     h = total height, m
//     f = form factor (Formzahl) = actual stem volume / reference-cylinder
//         volume. Whole-tree f is "commonly 0.45–0.55"; default 0.50.
//
// The form factor is carried on the persisted VolumeEquation record's
// dictionary under "f" (overridable per species when better values exist);
// absent, it defaults to 0.50.

package com.hcjeong.forestix.inventory

import kotlin.math.PI

class Formfactor(coefficients: Map<String, Float>) : VolumeEquation {

    /// Whole-tree form factor (Formzahl). Default 0.50 (0.45–0.55 range).
    private val f: Float = CoefficientLookup.optional(coefficients, "f", default = 0.50f)

    private val merchFraction: Float =
        CoefficientLookup.optional(coefficients, "merchFraction", default = 0.85f)

    override fun totalVolumeM3(dbhCm: Float, heightM: Float): Float {
        if (dbhCm <= 0f || heightM <= 0f) return 0f
        val dM = dbhCm / 100f                       // cm → m
        val g = (PI.toFloat() * dM * dM) / 4f       // basal area, m²
        return g * heightM * f
    }

    override fun merchantableVolumeM3(dbhCm: Float, heightM: Float,
                                      topDibCm: Float, stumpHeightCm: Float): Float =
        totalVolumeM3(dbhCm = dbhCm, heightM = heightM) * merchFraction
}
