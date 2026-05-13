// Spec §7.7 Volume Engine — the protocol itself.
//
// NOTE on the name collision: Models/ declares a persisted record called
// `VolumeEquation` (§6.2). This protocol lives in the InventoryEngine module,
// so full-qualified names (`Models.VolumeEquation` vs
// `InventoryEngine.VolumeEquation`) disambiguate where both are visible.
// Inside this file we deliberately do *not* import Models so the bare name
// `VolumeEquation` refers unambiguously to the protocol.

import Foundation

/// §7.7 Contract for any species-specific volume equation.
///
/// All functions take and return SI units (DBH cm, H m, V m³). Equations that
/// are natively expressed in imperial units (Bruce, Chambers-Foltz) perform
/// unit conversions internally.
public protocol VolumeEquation {
    /// Total stem volume, ground line to tip.
    func totalVolumeM3(dbhCm: Float, heightM: Float) -> Float

    /// Merchantable volume, stump to top-DIB.
    func merchantableVolumeM3(dbhCm: Float, heightM: Float,
                              topDibCm: Float, stumpHeightCm: Float) -> Float
}

// MARK: - Common coefficient-dictionary helpers

/// Helper for equations that load coefficients from the Models.VolumeEquation
/// record's `[String: Float]` dictionary.
public enum CoefficientLookup {
    public static func required(_ dict: [String: Float], _ key: String) -> Float {
        guard let v = dict[key] else {
            fatalError("VolumeEquation coefficient missing: '\(key)'")
        }
        return v
    }

    public static func optional(_ dict: [String: Float], _ key: String, default def: Float) -> Float {
        dict[key] ?? def
    }
}

// MARK: - Conversion helpers (imperial equations)

@inlinable public func cmToInches(_ cm: Float) -> Float { cm / 2.54 }
@inlinable public func mToFeet(_ m: Float) -> Float { m / 0.3048 }
@inlinable public func ft3ToM3(_ ft3: Float) -> Float { ft3 * 0.0283168466 }
@inlinable public func m3ToFt3(_ m3: Float) -> Float { m3 / 0.0283168466 }
