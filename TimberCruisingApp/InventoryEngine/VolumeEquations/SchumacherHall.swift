// Spec §7.7. Generic Schumacher-Hall form:
//     V = a · D^b · H^c
// Units are configurable through the coefficient record — the default here
// treats D in cm and H in m, returning V in m³ (the typical SI metric-form
// Schumacher-Hall parameterization).
//
// Required coefficients: "a", "b", "c".
// Optional: "merchFraction" — simple proportion fallback for the
// merchantable volume when a full taper model is not available.

import Foundation

public struct SchumacherHall: VolumeEquation {
    public let a: Float
    public let b: Float
    public let c: Float
    public let merchFraction: Float   // default 0.85 if not provided

    public init(coefficients: [String: Float]) {
        self.a = CoefficientLookup.required(coefficients, "a")
        self.b = CoefficientLookup.required(coefficients, "b")
        self.c = CoefficientLookup.required(coefficients, "c")
        self.merchFraction = CoefficientLookup.optional(coefficients, "merchFraction", default: 0.85)
    }

    public func totalVolumeM3(dbhCm: Float, heightM: Float) -> Float {
        guard dbhCm > 0, heightM > 0 else { return 0 }
        return a * pow(dbhCm, b) * pow(heightM, c)
    }

    public func merchantableVolumeM3(dbhCm: Float, heightM: Float,
                                     topDibCm: Float, stumpHeightCm: Float) -> Float {
        // Closed-form taper requires species-specific taper coefficients
        // (deferred to Phase 5+). Approximate with a configurable fraction.
        totalVolumeM3(dbhCm: dbhCm, heightM: heightM) * merchFraction
    }
}
