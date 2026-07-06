// GPS averaging — 1:1 port of iOS Positioning/GPSAveraging.swift
// (spec §7.3.1). REQ-CTR-001.
//
// Pure function: takes a buffer of location samples (1 Hz, from the
// LocationService subscription) and returns a PlotCenterResult or null.
// The caller (LocationService / PlotCenterViewModel) owns the location
// callbacks and the 60 s wall clock — this module only does the
// ENU-plane median + tier decision so the math is testable with
// synthetic snapshots.
//
// ENU conversion rationale: lat/lon median at high latitudes distorts
// east-west distances. We project every sample to a local east/north
// frame centered on sample 0 using a small-angle linear approximation
// (WGS-84 semi-major / degree: 111_320 m/°), take medians there, then
// invert the projection. Across a 50 m averaging area this is
// sub-millimetre vs. a full geodesic.

package com.hcjeong.forestix.positioning

import com.hcjeong.forestix.data.cruise.PlotCenterResult
import com.hcjeong.forestix.data.cruise.PositionSource
import com.hcjeong.forestix.data.cruise.PositionTier
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

// MARK: - CLLocationSnapshot

/// POD snapshot of the fields we read out of a platform location fix.
/// Name kept as `CLLocationSnapshot` (iOS origin) so cross-references
/// between the two codebases line up. Swift `Date` -> epoch millis.
/// A negative `horizontalAccuracyM` means the platform couldn't
/// compute one — treat as "no fix" (mirrors CoreLocation semantics).
data class CLLocationSnapshot(
    val latitude: Double,
    val longitude: Double,
    val horizontalAccuracyM: Double,
    val timestamp: Long,
)

// MARK: - GPSAveraging

object GPSAveraging {

    data class Input(
        val samples: List<CLLocationSnapshot>,
        val maxAcceptableAccuracyM: Float = 20f,
    )

    /// §7.3.1 algorithm. Returns null if fewer than 30 samples survive
    /// the accuracy filter (spec requires ≥ 30 for any tier).
    fun compute(input: Input): PlotCenterResult? {
        val accepted = input.samples.filter {
            it.horizontalAccuracyM > 0 &&
                it.horizontalAccuracyM.toFloat() <= input.maxAcceptableAccuracyM
        }
        if (accepted.size < 30) return null

        // Project every sample to local ENU centered on the first
        // accepted sample. Small-angle approximation good to sub-mm
        // over a 50 m averaging radius.
        val origin = accepted[0]
        val metersPerDegLat = 111_320.0
        val metersPerDegLon = 111_320.0 * cos(origin.latitude * PI / 180)

        val easts = ArrayList<Double>(accepted.size)
        val norths = ArrayList<Double>(accepted.size)
        for (s in accepted) {
            easts.add((s.longitude - origin.longitude) * metersPerDegLon)
            norths.add((s.latitude - origin.latitude) * metersPerDegLat)
        }

        val medianE = median(easts)
        val medianN = median(norths)

        // Sample standard deviation in the XY plane, pooled across
        // east + north (spec: sqrt(var_east + var_north)).
        val meanE = easts.sum() / easts.size
        val meanN = norths.sum() / norths.size
        var varE = 0.0
        var varN = 0.0
        for (i in easts.indices) {
            val de = easts[i] - meanE
            val dn = norths[i] - meanN
            varE += de * de
            varN += dn * dn
        }
        val n = easts.size.toDouble()
        val sampleStdXY = sqrt(varE / n + varN / n)

        // Median horizontal accuracy over accepted samples.
        val medianHAcc = median(accepted.map { it.horizontalAccuracyM }).toFloat()

        // Invert back to lat/lon.
        val resultLat = origin.latitude + medianN / metersPerDegLat
        val resultLon = origin.longitude + medianE / metersPerDegLon

        val tier = classify(
            medianHAccuracyM = medianHAcc,
            sampleStdXyM = sampleStdXY.toFloat(),
        )

        return PlotCenterResult(
            lat = resultLat,
            lon = resultLon,
            source = PositionSource.GPS_AVERAGED,
            tier = tier,
            nSamples = accepted.size,
            medianHAccuracyM = medianHAcc,
            sampleStdXyM = sampleStdXY.toFloat(),
            offsetWalkM = null,
        )
    }

    /// §7.3.1 tier table. Exposed so OffsetFromOpening can inherit +
    /// demote the tier without reinventing thresholds.
    fun classify(medianHAccuracyM: Float, sampleStdXyM: Float): PositionTier {
        if (medianHAccuracyM < 5f && sampleStdXyM < 3f) return PositionTier.A
        if (medianHAccuracyM < 10f && sampleStdXyM < 5f) return PositionTier.B
        if (medianHAccuracyM < 20f) return PositionTier.C
        return PositionTier.D
    }

    // MARK: - Median helper

    internal fun median(xs: List<Double>): Double {
        require(xs.isNotEmpty()) { "median of empty list" }
        val sorted = xs.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2]
        else (sorted[n / 2 - 1] + sorted[n / 2]) * 0.5
    }
}
