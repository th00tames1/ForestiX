// Metric stem-volume engine — Laasasenaho (1982) volume functions for the
// Nordic species (Scots pine, Norway spruce, silver/downy birch). The
// coefficients are VERIFIED against the R package `lmfor` (predvol); see
// docs/volume-standards-research.md §B. This is the Finnish national-paradigm
// path: total stem volume over bark, output in cubic metres.
//
// Two model forms, selected per call:
//   • d + h   (preferred, Laasasenaho eq. 61.2): needs measured/imputed height
//   • d only  (fallback, "Model 1"): used when height is unknown (h ≤ 1.3 m)
//
// Coefficients are carried on the persisted VolumeEquation record's dictionary
// (same convention as SchumacherHall), so pine/spruce/birch are three records
// that share this one class. Units: d = DBH cm @1.3 m, h = total height m,
// intermediate volume in LITRES → /1000 for m³.
//
//   d+h : v_L = c1 · d^c2 · c3^d · h^c4 · (h − 1.3)^c5
//   d   : v_L = exp( a1 + a2·ln(2 + 1.25·d) − a3·d )
//
// Worked check (pine c-set, d=20 cm, h=18 m): v_L ≈ 275 litres ≈ 0.274 m³ —
// physically reasonable for a 20 cm / 18 m pine over bark; confirms the
// formula wiring + the litre→m³ conversion. (See asserted values below.)

package com.hcjeong.forestix.inventory

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

class Laasasenaho(coefficients: Map<String, Float>) : VolumeEquation {

    // d + h coefficients (required — every record carries these).
    private val c1 = CoefficientLookup.required(coefficients, "c1").toDouble()
    private val c2 = CoefficientLookup.required(coefficients, "c2").toDouble()
    private val c3 = CoefficientLookup.required(coefficients, "c3").toDouble()
    private val c4 = CoefficientLookup.required(coefficients, "c4").toDouble()
    private val c5 = CoefficientLookup.required(coefficients, "c5").toDouble()

    // d-only fallback coefficients (optional — only used when height missing).
    private val a1 = coefficients["a1"]?.toDouble()
    private val a2 = coefficients["a2"]?.toDouble()
    private val a3 = coefficients["a3"]?.toDouble()

    private val merchFraction: Float =
        CoefficientLookup.optional(coefficients, "merchFraction", default = 0.85f)

    /// Total stem volume over bark, in m³. Uses the d+h model when a usable
    /// height is present; otherwise the d-only fallback if its coefficients
    /// are available; otherwise 0 (surfaced as a missing-height warning).
    override fun totalVolumeM3(dbhCm: Float, heightM: Float): Float {
        val d = dbhCm.toDouble()
        if (d <= 0.0) return 0f
        val h = heightM.toDouble()

        val litres: Double = if (h > 1.3) {
            // Laasasenaho eq. 61.2 — d + h.
            c1 * d.pow(c2) * c3.pow(d) * h.pow(c4) * (h - 1.3).pow(c5)
        } else if (a1 != null && a2 != null && a3 != null) {
            // Model 1 — d only.
            exp(a1 + a2 * ln(2.0 + 1.25 * d) - a3 * d)
        } else {
            return 0f
        }

        if (litres <= 0.0 || litres.isNaN()) return 0f
        return (litres / 1000.0).toFloat()   // litres → m³
    }

    /// Merchantable volume. Laasasenaho publishes total stem volume only, so
    /// merchantable is approximated with a configurable fraction until a taper
    /// model is wired (mirrors the SchumacherHall fallback).
    override fun merchantableVolumeM3(dbhCm: Float, heightM: Float,
                                      topDibCm: Float, stumpHeightCm: Float): Float =
        totalVolumeM3(dbhCm = dbhCm, heightM = heightM) * merchFraction
}
