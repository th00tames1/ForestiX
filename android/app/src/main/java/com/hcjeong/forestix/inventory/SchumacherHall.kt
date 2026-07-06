// Port of iOS InventoryEngine/VolumeEquations/SchumacherHall.swift.
// Spec §7.7. Generic Schumacher-Hall form:
//     V = a · D^b · H^c
// Units are configurable through the coefficient record — the default here
// treats D in cm and H in m, returning V in m³ (the typical SI metric-form
// Schumacher-Hall parameterization).
//
// Required coefficients: "a", "b", "c".
// Optional: "merchFraction" — simple proportion fallback for the
// merchantable volume when a full taper model is not available.

package com.hcjeong.forestix.inventory

import kotlin.math.pow

class SchumacherHall(coefficients: Map<String, Float>) : VolumeEquation {
    val a: Float = CoefficientLookup.required(coefficients, "a")
    val b: Float = CoefficientLookup.required(coefficients, "b")
    val c: Float = CoefficientLookup.required(coefficients, "c")
    val merchFraction: Float =   // default 0.85 if not provided
        CoefficientLookup.optional(coefficients, "merchFraction", default = 0.85f)

    override fun totalVolumeM3(dbhCm: Float, heightM: Float): Float {
        if (dbhCm <= 0f || heightM <= 0f) return 0f
        return a * dbhCm.pow(b) * heightM.pow(c)
    }

    override fun merchantableVolumeM3(dbhCm: Float, heightM: Float,
                                      topDibCm: Float, stumpHeightCm: Float): Float {
        // Closed-form taper requires species-specific taper coefficients
        // (deferred to Phase 5+). Approximate with a configurable fraction.
        return totalVolumeM3(dbhCm = dbhCm, heightM = heightM) * merchFraction
    }
}
