// Spec §7.7. Chambers & Foltz (1979) total cubic volume equation for
// western hemlock (PNW coastal). Like the Bruce form, it is log-linear:
//
//     log10(V_cf) = b0 + b1 · log10(D_in) + b2 · log10(H_ft)
//
// ** Coefficient provenance **
// Default coefficients loaded from Resources/VolumeEquationsPNW.json are
// placeholders pending verification against Chambers & Foltz (1979). See
// Phase 0 open questions.

import Foundation

public struct ChambersFoltzHemlock: VolumeEquation {
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
