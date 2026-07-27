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

/// TRUE when this plot has a RECORDED CENTRE — i.e. when its lat/lon may be
/// used as a position.
///
/// (0, 0) is the app's sentinel for "no centre". Two different plots wear
/// it: one that never got a centre (saved with no fix), and one whose
/// centre the map's "Remove plot" cleared. They are the SAME state and are
/// treated identically everywhere — a plot with no centre is not a plot at
/// a place.
///
/// The sentinel is a real coordinate on paper: Null Island, in the Gulf of
/// Guinea about 600 km south of Ghana. Anything that reads it as a position
/// puts the plot 10 000 km from the stand — on the map, in a GeoJSON, in a
/// shapefile — and a false point is worse than a missing one, because a
/// missing one is obviously missing.
///
/// Every plot-centre consumer (map overlay + pins, exporters, reports)
/// tests THIS, so there is one rule and not five spellings of it.
val Plot.hasCentre: Boolean
    get() = centerLat.isFinite() && centerLon.isFinite() &&
        (centerLat != 0.0 || centerLon != 0.0)
