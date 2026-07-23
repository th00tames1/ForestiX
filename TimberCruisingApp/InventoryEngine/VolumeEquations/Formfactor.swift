// Central-European metric stem-volume — generic form-factor (Formzahl) volume.
// This is the approximate m³ path for Germany (see
// docs/volume-standards-research.md §C.2).
//
//     V_m3 = g · h · f
//       g = π · (d/100)² / 4     basal area at breast height (m²), d in cm
//       h = total height (m)
//       f = form factor (Formzahl) = actual stem volume / reference cylinder
//
// The whole-tree form factor is "commonly in the order of magnitude of
// 0.45–0.55" (AWF-Wiki, Univ. Göttingen); we default to f = 0.50. This is
// deliberately labelled an APPROXIMATE standard — production-grade German
// volume would call the BDAT taper library (no short closed form exists), but
// the form factor gives a defensible first-order estimate with a real,
// citable formula.

import Foundation

public struct Formfactor: VolumeEquation {

    /// Form factor (Formzahl). Documented range 0.45–0.55; default 0.50.
    public let f: Float
    public let merchFraction: Float

    public init(formFactor: Float = 0.50, merchFraction: Float = 0.85) {
        self.f = formFactor
        self.merchFraction = merchFraction
    }

    /// Factory path — reads an optional `f` override (else 0.50).
    public init(coefficients: [String: Float]) {
        self.f = CoefficientLookup.optional(coefficients, "f", default: 0.50)
        self.merchFraction = CoefficientLookup.optional(coefficients, "merchFraction", default: 0.85)
    }

    public func totalVolumeM3(dbhCm d: Float, heightM h: Float) -> Float {
        guard d > 0, h > 0 else { return 0 }
        let basalAreaM2 = Float.pi * pow(d / 100.0, 2) / 4.0
        return basalAreaM2 * h * f
    }

    public func merchantableVolumeM3(dbhCm: Float, heightM: Float,
                                     topDibCm: Float, stumpHeightCm: Float) -> Float {
        totalVolumeM3(dbhCm: dbhCm, heightM: heightM) * merchFraction
    }
}
