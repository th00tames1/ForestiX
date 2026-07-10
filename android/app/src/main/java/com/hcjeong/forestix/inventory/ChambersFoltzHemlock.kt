// Port of iOS InventoryEngine/VolumeEquations/ChambersFoltzHemlock.swift.
// Spec §7.7. Chambers & Foltz (1979) total cubic volume equation for
// western hemlock (PNW coastal). Like the Bruce form, it is log-linear:
//
//     log10(V_cf) = b0 + b1 · log10(D_in) + b2 · log10(H_ft)
//
// ** Coefficient provenance **
// Default coefficients loaded from the VolumeEquationsPNW resource are
// placeholders pending verification against Chambers & Foltz (1979). See
// Phase 0 open questions.

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
