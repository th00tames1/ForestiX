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
    /// Ground elevation at the plot centre, in METRES.
    ///
    /// Metres because every stored length in this app is metric and the
    /// display layer converts once (see `MeasurementFormatter`); a US cruise
    /// reads and types feet, and the sheet does that conversion. Storing the
    /// cruiser's units instead would make the number mean different things in
    /// different projects, which is unrecoverable after the fact.
    ///
    /// null is NOT sea level — it is "nobody recorded an elevation here". The
    /// two are different answers and 0 m is a real coastal elevation, so the
    /// unset case gets its own value rather than a sentinel that a tidewater
    /// plot could legitimately wear.
    var groundElevationM: Float? = null,
    /// Canopy cover over the plot, 0–100 %. Unitless, so it is the same
    /// number in both unit systems. null means not recorded; 0 % is a real
    /// reading on a clearcut.
    var canopyCoverPct: Float? = null,
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

/// Why this plot's site description cannot be stored, in the words the cruiser
/// reads — or null when all four fields are inside their ranges.
///
/// Lives on the model because BOTH the sheet that types these numbers and the
/// repository that writes them ask the same question, and a screen that allows
/// what storage refuses (or the reverse) is a save button that fails for no
/// stated reason. The repository is the one that MUST ask: the sheet is only
/// today's caller, and a canopy cover of 130 % written by tomorrow's importer
/// is just as wrong.
///
/// A NaN fails every one of these comparisons and is therefore refused along
/// with the out-of-range values, which is the intent — a NaN aspect is not a
/// direction.
val Plot.siteDescriptionRejection: String?
    get() {
        if (slopeDeg !in 0f..90f) {
            return "Slope must be between 0 and 90 degrees."
        }
        // Upper bound EXCLUSIVE: 360° and 0° are the same bearing, and two
        // spellings of north is how a north-facing plot sorts into two groups
        // in the same table. Callers wrap before they save.
        if (!(aspectDeg >= 0f && aspectDeg < 360f)) {
            return "Aspect must be a bearing from 0 up to (but not including) 360 degrees."
        }
        // Below the Dead Sea shore or above Everest is a typo, most often a
        // figure in feet typed into a field that stores metres. The sentence
        // names BOTH units because the cruiser reading it may be working in
        // either, and it is the same sentence on both platforms.
        val elevation = groundElevationM
        if (elevation != null && elevation !in -500f..9000f) {
            return "Elevation must be between -500 and 9000 m (-1640 to 29528 ft)."
        }
        val cover = canopyCoverPct
        if (cover != null && cover !in 0f..100f) {
            return "Canopy cover must be between 0 and 100 %."
        }
        return null
    }
