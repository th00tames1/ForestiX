// Nordic metric stem-volume — Laasasenaho (1982) total stem volume over bark
// for Scots pine, Norway spruce and birch. The verified m³ path for Finland
// (see docs/volume-standards-research.md §B).
//
// Source of the coefficients: the R package `lmfor` function `predvol`, which
// documents itself as predicting individual-tree volumes using the functions
// of Laasasenaho (1982), Communicationes Instituti Forestalis Fenniae 108.
// Coefficients were copied verbatim from the lmfor source and cross-checked
// against Kangas et al. 2020 (Silva Fennica 54(4):10269).
//
// Coefficients are carried on the persisted VolumeEquation record's dictionary
// (same convention as SchumacherHall) — pine / spruce / birch are three
// records that share this one class, so nothing is hard-coded here and the
// numbers stay in the verifiable seed JSON. This mirrors the Android sibling
// byte-for-byte so a record backed up on one platform computes identically on
// the other.
//
// Conventions (from the predvol docs + code):
//   • d = DBH in cm, over bark, at 1.3 m.  h = total tree height in m.
//   • Intermediate volume is in LITRES (dm³) → divide by 1000 for m³.
//   • Total stem volume, over bark.
//
// Two models, selected per call:
//   d+h (preferred, Laasasenaho eq. 61.2):
//       v_litres = c1 · d^c2 · c3^d · h^c4 · (h − 1.3)^c5      (c1..c5 required)
//   d-only fallback when height is unknown (h ≤ 1.3 m):
//       v_litres = exp( a1 + a2·ln(2 + 1.25·d) − a3·d )        (a1..a3 optional)
//
// Worked check (pine c-set, d = 20 cm, h = 18 m):
//   v = 0.036089 · 20^2.01395 · 0.99676^20 · 18^2.07025 · 16.7^(−1.07209)
//     ≈ 273.7 litres ≈ 0.274 m³ — physically reasonable for a 20 cm / 18 m
//   pine over bark; confirms the formula wiring + the litre→m³ conversion.

import Foundation

public struct Laasasenaho: VolumeEquation {

    // d+h coefficients — required (every record carries these).
    public let c1: Float
    public let c2: Float
    public let c3: Float
    public let c4: Float
    public let c5: Float

    // d-only fallback coefficients — optional (only used when height missing).
    public let a1: Float?
    public let a2: Float?
    public let a3: Float?

    public let merchFraction: Float

    public init(coefficients: [String: Float]) {
        self.c1 = CoefficientLookup.required(coefficients, "c1")
        self.c2 = CoefficientLookup.required(coefficients, "c2")
        self.c3 = CoefficientLookup.required(coefficients, "c3")
        self.c4 = CoefficientLookup.required(coefficients, "c4")
        self.c5 = CoefficientLookup.required(coefficients, "c5")
        self.a1 = coefficients["a1"]
        self.a2 = coefficients["a2"]
        self.a3 = coefficients["a3"]
        self.merchFraction = CoefficientLookup.optional(coefficients, "merchFraction", default: 0.85)
    }

    /// Total stem volume over bark (m³). Uses the d+h model when a usable
    /// height is present; otherwise the d-only fallback if its coefficients are
    /// available; otherwise 0 (surfaced as a missing-height warning elsewhere).
    public func totalVolumeM3(dbhCm d: Float, heightM h: Float) -> Float {
        guard d > 0 else { return 0 }
        let litres: Float
        if h > 1.3 {
            litres = c1 * pow(d, c2) * pow(c3, d) * pow(h, c4) * pow(h - 1.3, c5)
        } else if let a1, let a2, let a3 {
            litres = exp(a1 + a2 * log(2 + 1.25 * d) - a3 * d)
        } else {
            return 0
        }
        guard litres.isFinite, litres > 0 else { return 0 }
        return litres / 1000.0   // litres (dm³) → m³
    }

    public func merchantableVolumeM3(dbhCm: Float, heightM: Float,
                                     topDibCm: Float, stumpHeightCm: Float) -> Float {
        // Laasasenaho publishes total stem volume only; approximate merchantable
        // with a configurable fraction until a taper model is wired in (mirrors
        // the SchumacherHall fallback).
        totalVolumeM3(dbhCm: dbhCm, heightM: heightM) * merchFraction
    }
}
