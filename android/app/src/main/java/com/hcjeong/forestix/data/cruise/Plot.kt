// Plot model — direct port of iOS Models/Plot.swift (spec §6.2 Plot).
// REQ-CTR-001..005, REQ-AGG-001..003.
//
// Swift Date -> Long epoch-millis, Swift UUID -> java.util.UUID. Field
// names match 1:1 so cross-platform exports join.

package com.hcjeong.forestix.data.cruise

import java.util.UUID

data class Plot(
    val id: UUID,
    val projectId: UUID,
    var plannedPlotId: UUID?,
    var plotNumber: Int,
    var centerLat: Double,
    var centerLon: Double,
    var positionSource: PositionSource,
    var positionTier: PositionTier,
    var gpsNSamples: Int,
    var gpsMedianHAccuracyM: Float,
    var gpsSampleStdXyM: Float,
    var offsetWalkM: Float?,            // non-null for vioOffset
    var slopeDeg: Float,
    var aspectDeg: Float,
    var plotAreaAcres: Float,           // denormalized from CruiseDesign for robustness
    var startedAt: Long,
    var closedAt: Long?,
    var closedBy: String?,
    var notes: String,
    var coverPhotoPath: String?,
    var panoramaPath: String?,          // for re-navigation
)
