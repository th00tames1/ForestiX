// Port of iOS InventoryEngine/VolumeEquations/VolumeEquation.swift.
// Spec §7.7 Volume Engine — the interface itself.
//
// NOTE on the name collision: data/cruise declares a persisted record also
// called `VolumeEquation` (§6.2). This interface lives in the inventory
// package, so fully-qualified names (`com.hcjeong.forestix.data.cruise.
// VolumeEquation` vs `com.hcjeong.forestix.inventory.VolumeEquation`)
// disambiguate where both are visible — mirroring the iOS
// `Models.VolumeEquation` / `InventoryEngine.VolumeEquation` split.

package com.hcjeong.forestix.inventory

/// §7.7 Contract for any species-specific volume equation.
///
/// All functions take and return SI units (DBH cm, H m, V m³). Equations that
/// are natively expressed in imperial units (Bruce, Chambers-Foltz) perform
/// unit conversions internally.
interface VolumeEquation {
    /// Total stem volume, ground line to tip.
    fun totalVolumeM3(dbhCm: Float, heightM: Float): Float

    /// Merchantable volume, stump to top-DIB.
    fun merchantableVolumeM3(dbhCm: Float, heightM: Float,
                             topDibCm: Float, stumpHeightCm: Float): Float
}

// MARK: - Common coefficient-dictionary helpers

/// Helper for equations that load coefficients from the cruise VolumeEquation
/// record's `Map<String, Float>` dictionary.
object CoefficientLookup {
    fun required(dict: Map<String, Float>, key: String): Float =
        dict[key] ?: error("VolumeEquation coefficient missing: '$key'")

    fun optional(dict: Map<String, Float>, key: String, default: Float): Float =
        dict[key] ?: default
}

// MARK: - Conversion helpers (imperial equations)

fun cmToInches(cm: Float): Float = cm / 2.54f
fun mToFeet(m: Float): Float = m / 0.3048f
fun ft3ToM3(ft3: Float): Float = ft3 * 0.0283168466f
fun m3ToFt3(m3: Float): Float = m3 / 0.0283168466f
