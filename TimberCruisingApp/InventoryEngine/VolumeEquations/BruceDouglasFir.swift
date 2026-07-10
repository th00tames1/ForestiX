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
// coefficients loaded from `Resources/VolumeEquationsPNW.json` are placeholders
// pending verification against a primary source (see open-questions in Phase 0
// final report). Callers that instantiate this class directly can pass their
// own coefficient dictionary.
//
// Required coefficients: "b0", "b1", "b2".
// Optional: "merchFraction" (default 0.85).

import Foundation

public struct BruceDouglasFir: VolumeEquation {
    public let b0: Float
    public let b1: Float
    public let b2: Float
    public let merchFraction: Float

    public init(coefficients: [String: Float]) {
        self.b0 = CoefficientLookup.required(coefficients, "b0")
        self.b1 = CoefficientLookup.required(coefficients, "b1")
        self.b2 = CoefficientLookup.required(coefficients, "b2")
        self.merchFraction = CoefficientLookup.optional(coefficients, "merchFraction", default: 0.85)
    }

    public func totalVolumeM3(dbhCm: Float, heightM: Float) -> Float {
        guard dbhCm > 0, heightM > 0 else { return 0 }
        let dIn = cmToInches(dbhCm)
        let hFt = mToFeet(heightM)
        let logV = b0 + b1 * log10(dIn) + b2 * log10(hFt)
        let vCf = pow(10, logV)
        return ft3ToM3(vCf)
    }

    public func merchantableVolumeM3(dbhCm: Float, heightM: Float,
                                     topDibCm: Float, stumpHeightCm: Float) -> Float {
        totalVolumeM3(dbhCm: dbhCm, heightM: heightM) * merchFraction
    }
}
