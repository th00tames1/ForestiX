// Tree model — direct port of iOS Models/Tree.swift (spec §6.1 tree-related
// enums + §6.2 Tree). REQ-TAL-001..006, REQ-DBH-001..009, REQ-HGT-001..007.
//
// Reuses the enums the sensor layer already ports 1:1 from iOS:
//   - ConfidenceTier  (sensors/Confidence.kt   <- iOS Common/ConfidenceTier.swift)
//   - DBHMethod       (sensors/DbhEstimator.kt <- iOS Models/Tree.swift)
//   - HeightMethod    (sensors/HeightEstimator.kt <- iOS Models/Tree.swift)
// so there is a single source of truth for those raw strings.
//
// Swift Date -> Long epoch-millis, Swift UUID -> java.util.UUID.

package com.hcjeong.forestix.data.cruise

import com.hcjeong.forestix.sensors.ConfidenceTier
import com.hcjeong.forestix.sensors.DBHMethod
import com.hcjeong.forestix.sensors.HeightMethod
import java.util.UUID

// MARK: - §6.1 Enumerations (tree)

enum class TreeStatus(val raw: String) {
    LIVE("live"),
    DEAD_STANDING("deadStanding"),
    DEAD_DOWN("deadDown"),
    CULL("cull");

    companion object {
        fun fromRaw(s: String?) = entries.firstOrNull { it.raw == s }
    }
}

// MARK: - §6.2 Tree

data class Tree(
    val id: UUID,
    val plotId: UUID,
    var treeNumber: Int,
    var speciesCode: String,
    var status: TreeStatus,

    // DBH
    var dbhCm: Float,
    var dbhMethod: DBHMethod,
    var dbhSigmaMm: Float?,             // uncertainty
    var dbhRmseMm: Float?,
    var dbhCoverageDeg: Float?,
    var dbhNInliers: Int?,
    var dbhConfidence: ConfidenceTier,
    var dbhIsIrregular: Boolean,

    // Height
    var heightM: Float?,
    var heightMethod: HeightMethod?,
    var heightSource: String?,          // "measured" or "imputed"
    var heightSigmaM: Float?,
    var heightDHM: Float?,
    var heightAlphaTopDeg: Float?,
    var heightAlphaBaseDeg: Float?,
    var heightConfidence: ConfidenceTier?,

    // Geometry within plot
    var bearingFromCenterDeg: Float?,
    var distanceFromCenterM: Float?,
    var boundaryCall: String?,          // "in" / "borderline" / "out" for BAF plots

    // Attributes
    var crownClass: String?,
    var damageCodes: List<String>,
    var isMultistem: Boolean,
    var parentTreeId: UUID?,

    var notes: String,
    var photoPath: String?,
    var rawScanPath: String?,           // optional .ply

    // Cruise-mode map pin (v3 redesign): GPS fix captured at Accept.
    var latitude: Double? = null,
    var longitude: Double? = null,

    var createdAt: Long,
    var updatedAt: Long,
    var deletedAt: Long?,               // soft-delete
)
