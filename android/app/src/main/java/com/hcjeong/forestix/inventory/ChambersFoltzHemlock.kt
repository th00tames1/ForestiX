// Legacy parameterized imperial log-power wrapper; class name is retained for
// stored equation identifiers, not a verified implementation of its namesake.
// log10(V_ft3) = b0 + b1*log10(D_in) + b2*log10(H_ft).
// This is not the piecewise form-factor equation in Bruce & DeMars (1974).
// Required b0/b1/b2 and optional fixed merchFraction (default .85) must be
// validated for form, units, domain, and volume definition before operational use.
// The fixed fraction ignores top-DIB/stump inputs; it is not a taper model.
// VolumeEquationFactory refuses the starter records marked PLACEHOLDER.
// See docs/VOLUME_EQUATIONS.md.

package com.hcjeong.forestix.inventory

import kotlin.math.log10
import kotlin.math.pow

class ChambersFoltzHemlock(coefficients: Map<String, Float>) : VolumeEquation {
    val b0: Float = CoefficientLookup.required(coefficients, "b0")
    val b1: Float = CoefficientLookup.required(coefficients, "b1")
    val b2: Float = CoefficientLookup.required(coefficients, "b2")
    val merchFraction: Float = CoefficientLookup.optional(coefficients, "merchFraction", default = 0.85f)

    override fun totalVolumeM3(dbhCm: Float, heightM: Float): Float {
        if (dbhCm <= 0f || heightM <= 0f) return 0f
        val dIn = cmToInches(dbhCm)
        val hFt = mToFeet(heightM)
        val logV = b0 + b1 * log10(dIn) + b2 * log10(hFt)
        val vCf = 10f.pow(logV)
        return ft3ToM3(vCf)
    }

    override fun merchantableVolumeM3(dbhCm: Float, heightM: Float,
                                      topDibCm: Float, stumpHeightCm: Float): Float =
        totalVolumeM3(dbhCm = dbhCm, heightM = heightM) * merchFraction
}
