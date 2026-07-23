// Project + cruise-design models — direct port of iOS Models/Project.swift
// (spec §6.1 project-related enums + §6.2 Project, Stratum, CruiseDesign,
// PlannedPlot). REQ-PRJ-001..006, REQ-CAL-001..005.
//
// Enum raw strings match the Swift rawValue byte-for-byte so CSV / JSON
// exports join across platforms. Swift Date -> Long epoch-millis,
// Swift UUID -> java.util.UUID.

package com.hcjeong.forestix.data.cruise

import java.util.UUID
import org.json.JSONObject

// MARK: - §6.1 Enumerations (project/design/position-related)

enum class UnitSystem(val raw: String) {
    IMPERIAL("imperial"),
    METRIC("metric");

    companion object {
        fun fromRaw(s: String?) = entries.firstOrNull { it.raw == s }
    }
}

enum class PlotType(val raw: String) {
    FIXED_AREA("fixedArea"),
    VARIABLE_RADIUS("variableRadius");

    companion object {
        fun fromRaw(s: String?) = entries.firstOrNull { it.raw == s }
    }
}

enum class SamplingScheme(val raw: String) {
    SYSTEMATIC_GRID("systematicGrid"),
    STRATIFIED_RANDOM("stratifiedRandom"),
    MANUAL("manual");

    companion object {
        fun fromRaw(s: String?) = entries.firstOrNull { it.raw == s }
    }
}

enum class BreastHeightConvention(val raw: String) {
    UPHILL("uphill"),
    MID("mid"),
    ANY("any"),
    CUSTOM("custom");

    companion object {
        fun fromRaw(s: String?) = entries.firstOrNull { it.raw == s }
    }
}

/// Spec REQ-HGT-007 + §7.4. Rule for when a tree gets a measured height vs.
/// imputed from the H–D model. Stored on `CruiseDesign`.
///
/// Cases:
///   - `AllTrees`  — measure every live tree.
///   - `None`      — impute every tree (historical datasets, dense stands).
///   - `EveryKth(k)` — measure one in every k live trees, indexed by
///     `treeNumber` within the plot. First tree of a plot always measured.
///   - `PerSpeciesCount(minPerSpeciesOnPlot)` — keep measuring until at
///     least N live trees of that species on the plot have a measured
///     height; subsequent trees of that species get imputed.
///
/// JSON shape matches Swift's synthesized Codable for an enum with
/// associated values: {"allTrees":{}}, {"everyKth":{"k":5}}, ...
sealed class HeightSubsampleRule {
    object AllTrees : HeightSubsampleRule()
    object None : HeightSubsampleRule()
    data class EveryKth(val k: Int) : HeightSubsampleRule()
    data class PerSpeciesCount(val minPerSpeciesOnPlot: Int) : HeightSubsampleRule()

    /// Encode to the same JSON string Swift's synthesized Codable produces.
    fun toJsonString(): String {
        val obj = JSONObject()
        when (this) {
            is AllTrees -> obj.put("allTrees", JSONObject())
            is None -> obj.put("none", JSONObject())
            is EveryKth -> obj.put("everyKth", JSONObject().put("k", k))
            is PerSpeciesCount ->
                obj.put("perSpeciesCount", JSONObject().put("minPerSpeciesOnPlot", minPerSpeciesOnPlot))
        }
        return obj.toString()
    }

    companion object {
        /// Decode from the Swift-Codable JSON shape. Returns null on any
        /// unrecognized shape so callers can fall back to a default.
        fun fromJsonString(s: String?): HeightSubsampleRule? {
            if (s.isNullOrBlank()) return null
            return try {
                val obj = JSONObject(s)
                when {
                    obj.has("allTrees") -> AllTrees
                    obj.has("none") -> None
                    obj.has("everyKth") -> EveryKth(k = obj.getJSONObject("everyKth").getInt("k"))
                    obj.has("perSpeciesCount") -> PerSpeciesCount(
                        minPerSpeciesOnPlot = obj.getJSONObject("perSpeciesCount")
                            .getInt("minPerSpeciesOnPlot")
                    )
                    else -> null
                }
            } catch (_: Exception) {
                null
            }
        }
    }
}

enum class PositionSource(val raw: String) {
    GPS_AVERAGED("gpsAveraged"),
    VIO_OFFSET("vioOffset"),
    VIO_CHAIN("vioChain"),
    EXTERNAL_RTK("externalRTK"),
    MANUAL("manual");

    companion object {
        fun fromRaw(s: String?) = entries.firstOrNull { it.raw == s }
    }
}

enum class PositionTier(val raw: String) {
    A("A"), B("B"), C("C"), D("D");

    companion object {
        fun fromRaw(s: String?) = entries.firstOrNull { it.raw == s }
    }
}

// MARK: - §6.2 Project

data class Project(
    val id: UUID,
    var name: String,
    var description: String,
    var owner: String,
    var createdAt: Long,
    var updatedAt: Long,
    var units: UnitSystem,
    var breastHeightConvention: BreastHeightConvention,
    var slopeCorrection: Boolean,
    // Calibration
    var lidarBiasMm: Float,
    var depthNoiseMm: Float,
    var dbhCorrectionAlpha: Float,      // from cylinder calibration; default 0
    var dbhCorrectionBeta: Float,       // default 1
    var vioDriftFraction: Float,        // default 0.02
)

// MARK: - §6.2 Stratum

data class Stratum(
    val id: UUID,
    val projectId: UUID,
    var name: String,
    var areaAcres: Float,
    var polygonGeoJSON: String,         // WGS84
)

// MARK: - §6.2 CruiseDesign

data class CruiseDesign(
    val id: UUID,
    val projectId: UUID,
    var plotType: PlotType,
    var plotAreaAcres: Float?,          // required if fixedArea
    var baf: Float?,                    // required if variableRadius
    var samplingScheme: SamplingScheme,
    var gridSpacingMeters: Float?,      // required if systematicGrid
    var heightSubsampleRule: HeightSubsampleRule = HeightSubsampleRule.EveryKth(k = 5),
)

// MARK: - §6.2 PlannedPlot

data class PlannedPlot(
    val id: UUID,
    val projectId: UUID,
    var stratumId: UUID?,
    var plotNumber: Int,
    var plannedLat: Double,
    var plannedLon: Double,
    var visited: Boolean,
    /// Intentionally skipped by the cruiser (inaccessible — cliff, water,
    /// private land). Distinct from `visited`: a skipped plot is a documented
    /// decision, so the (+) "nearest unvisited" navigation passes over it and
    /// exports mark it skipped rather than merely pending. `= false` default
    /// mirrors iOS `skipped: Bool = false` so existing PlannedPlot(...) sites
    /// compile unchanged.
    var skipped: Boolean = false,
)
