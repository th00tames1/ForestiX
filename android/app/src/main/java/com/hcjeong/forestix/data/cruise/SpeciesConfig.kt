// Species / volume-equation / H–D fit models — direct port of iOS
// Models/SpeciesConfig.swift (spec §6.2 SpeciesConfig, VolumeEquation,
// HeightDiameterFit). REQ-PRJ-005, §7.4, §7.7.
//
// Note: the class `VolumeEquation` defined here is the persisted *record* of
// equation coefficients — distinct from any evaluator abstraction in the
// inventory-engine layer (same user-approved name-collision decision as iOS:
// disambiguate by package).

package com.hcjeong.forestix.data.cruise

import java.util.UUID

// MARK: - §6.2 SpeciesConfig

data class SpeciesConfig(
    val code: String,                   // e.g., "DF"
    var commonName: String,
    var scientificName: String,
    var volumeEquationId: String,
    var merchTopDibCm: Float,
    var stumpHeightCm: Float,
    var expectedDbhMinCm: Float,
    var expectedDbhMaxCm: Float,
    var expectedHeightMinM: Float,
    var expectedHeightMaxM: Float,
) {
    val id: String get() = code
}

// MARK: - §6.2 VolumeEquation (record)

data class VolumeEquation(
    val id: String,
    var form: String,                   // e.g., "bruce", "schumacher_hall"
    var coefficients: Map<String, Float>,
    var unitsIn: String,                // e.g., "cm, m"
    var unitsOut: String,               // e.g., "m3"
    var sourceCitation: String,
)

// MARK: - §6.2 HeightDiameterFit

data class HeightDiameterFit(
    val id: UUID,
    val projectId: UUID,
    val speciesCode: String,
    var modelForm: String,              // "naslund"
    var coefficients: Map<String, Float>,
    var nObs: Int,
    var rmse: Float,
    var updatedAt: Long,
)
