// Port of iOS InventoryEngine/VolumeEquations/BruceDouglasFir.swift.
// Spec §7.7.  Bruce form for Douglas-fir total cubic volume.
//
// Form (log-linear Schumacher–Hall, as published by Bruce & DeMars and later
// Flewelling & McFadden for coastal PNW Douglas-fir):
//
//     log10(V_cf) = b0 + b1 · log10(D_in) + b2 · log10(H_ft)
//     ⇒  V_cf = 10^b0 · D_in^b1 · H_ft^b2
//
// The equation is stored in imperial units; this wrapper converts DBH cm → in,
// H m → ft, V ft³ → m³.
//
// ** Coefficient provenance **
// The canonical Bruce DF coefficients for coastal Douglas-fir total cubic
// volume (CVTS) are widely cited in USFS/BLM cruising handbooks. Default
// coefficients loaded from the VolumeEquationsPNW resource are placeholders
// pending verification against a primary source (see open-questions in Phase 0
// final report). Callers that instantiate this class directly can pass their
// own coefficient dictionary.
//
// Required coefficients: "b0", "b1", "b2".
// Optional: "merchFraction" (default 0.85).

package com.hcjeong.forestix.inventory

import kotlin.math.log10
import kotlin.math.pow

class BruceDouglasFir(coefficients: Map<String, Float>) : VolumeEquation {
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
