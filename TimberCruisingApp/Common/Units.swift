// Spec §8 (Common/Units.swift) + REQ-CAL-001 (per-project unit system).
// Imperial ↔ metric conversions. Pure functions, no imports beyond Foundation.

import Foundation

public enum Units {

    // MARK: Length — meters ↔ feet
    public static let metersPerFoot: Double = 0.3048
    public static func metersToFeet(_ m: Double) -> Double { m / metersPerFoot }
    public static func feetToMeters(_ ft: Double) -> Double { ft * metersPerFoot }

    // MARK: Length — cm ↔ inches
    public static let cmPerInch: Double = 2.54
    public static func cmToInches(_ cm: Double) -> Double { cm / cmPerInch }
    public static func inchesToCm(_ inches: Double) -> Double { inches * cmPerInch }

    // MARK: Area — m² ↔ ft²
    public static func squareMetersToSquareFeet(_ m2: Double) -> Double {
        m2 * (1.0 / metersPerFoot) * (1.0 / metersPerFoot)
    }
    public static func squareFeetToSquareMeters(_ ft2: Double) -> Double {
        ft2 * metersPerFoot * metersPerFoot
    }

    // MARK: Area — acres ↔ m²
    // 1 acre = 43560 ft² (exact) = 4046.8564224 m² (exact by definition of intl. foot)
    public static let squareMetersPerAcre: Double = 4046.8564224
    public static func acresToSquareMeters(_ ac: Double) -> Double { ac * squareMetersPerAcre }
    public static func squareMetersToAcres(_ m2: Double) -> Double { m2 / squareMetersPerAcre }

    // MARK: Volume — m³ ↔ ft³
    public static func cubicMetersToCubicFeet(_ m3: Double) -> Double {
        m3 * (1.0 / metersPerFoot) * (1.0 / metersPerFoot) * (1.0 / metersPerFoot)
    }
    public static func cubicFeetToCubicMeters(_ ft3: Double) -> Double {
        ft3 * metersPerFoot * metersPerFoot * metersPerFoot
    }

    // MARK: Per-acre basal area m²/ha ↔ ft²/ac (useful convenience)
    // 1 m²/ha = 4.35600 ft²/ac (derived from 1 ha = 2.47105 ac, 1 m² = 10.7639 ft²)
    public static func baPerHaToBaPerAcre(_ m2PerHa: Double) -> Double {
        m2PerHa * (squareMetersToSquareFeet(1.0) / 2.4710538147)
    }
    public static func baPerAcreToBaPerHa(_ ft2PerAc: Double) -> Double {
        ft2PerAc * (2.4710538147 / squareMetersToSquareFeet(1.0))
    }
}

// Float convenience overloads (spec uses Float for most measurement fields).
public extension Units {
    static func metersToFeet(_ m: Float) -> Float { Float(metersToFeet(Double(m))) }
    static func feetToMeters(_ ft: Float) -> Float { Float(feetToMeters(Double(ft))) }
    static func cmToInches(_ cm: Float) -> Float { Float(cmToInches(Double(cm))) }
    static func inchesToCm(_ inches: Float) -> Float { Float(inchesToCm(Double(inches))) }
    static func acresToSquareMeters(_ ac: Float) -> Float { Float(acresToSquareMeters(Double(ac))) }
    static func squareMetersToAcres(_ m2: Float) -> Float { Float(squareMetersToAcres(Double(m2))) }
}
