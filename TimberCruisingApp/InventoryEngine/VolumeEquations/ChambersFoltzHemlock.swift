// Legacy parameterized imperial log-power wrapper; class name is retained for
// stored equation identifiers, not a verified implementation of its namesake.
// log10(V_ft3) = b0 + b1*log10(D_in) + b2*log10(H_ft).
// This is not the piecewise form-factor equation in Bruce & DeMars (1974).
// Required b0/b1/b2 and optional fixed merchFraction (default .85) must be
// validated for form, units, domain, and volume definition before operational use.
// The fixed fraction ignores top-DIB/stump inputs; it is not a taper model.
// VolumeEquationFactory refuses the starter records marked PLACEHOLDER.
// See docs/VOLUME_EQUATIONS.md.

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
