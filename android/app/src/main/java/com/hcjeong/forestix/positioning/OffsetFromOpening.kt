// Offset-from-opening — 1:1 port of iOS Positioning/OffsetFromOpening.swift
// (spec §7.3.2, REQ-CTR-002).
//
// Under canopy GPS is often unusable, but AR VIO holds sub-metre
// relative accuracy over ≤ 200 m walks. So: get a clean GPS fix at a
// nearby opening, record the AR pose there, walk back to the plot
// center under continuous AR tracking, and subtract the pose
// difference to recover the plot center in lat/lon.
//
// Pure function: all state (opening fix, opening pose, plot pose,
// tracking-was-normal flag) is packed into `Input`. The screen layer
// snapshots these from the live AR session at each step then hands
// the tuple in here for the final geometry.
//
// Compass note: iOS runs ARKit with `.gravityAndHeading`, so world-X
// ≈ East and world-−Z ≈ North to within a degree, and the spec says
// NOT to apply an additional compass rotation. ARCore's world frame is
// gravity-aligned but NOT heading-aligned — the capture layer must
// rotate its poses into an east-north-up frame (e.g. from the
// rotation-vector azimuth at anchor time) before calling in here so
// the same X≈E / −Z≈N convention holds. TODO(capture layer): apply
// that yaw alignment when wiring the offset flow to ARCore.

package com.hcjeong.forestix.positioning

import com.hcjeong.forestix.ar.Vec3
import com.hcjeong.forestix.data.cruise.PlotCenterResult
import com.hcjeong.forestix.data.cruise.PositionSource
import com.hcjeong.forestix.data.cruise.PositionTier
import kotlin.math.PI
import kotlin.math.cos

object OffsetFromOpening {

    data class Input(
        val openingFix: PlotCenterResult,
        val openingPointWorld: Vec3,
        val plotPointWorld: Vec3,
        val trackingStateWasNormalThroughout: Boolean,
    )

    /// §7.3.2 algorithm. Returns null when tracking was not normal at
    /// some point in the walk (REQ-CTR-002).
    fun compute(input: Input): PlotCenterResult? {
        if (!input.trackingStateWasNormalThroughout) return null

        val delta = input.plotPointWorld - input.openingPointWorld
        val walkDistance = delta.length()

        // Spec step 5: world X ≈ east, world −Z ≈ north under the
        // heading-aligned frame. No extra rotation.
        val east = delta.x.toDouble()
        val north = (-delta.z).toDouble()

        // Spec step 6: convert ENU displacement to lat/lon delta at
        // the opening fix using the same linear approximation as
        // GPSAveraging. The approximation holds to <1 ppm over 200 m.
        val metersPerDegLat = 111_320.0
        val metersPerDegLon = 111_320.0 *
            cos(input.openingFix.lat * PI / 180)
        val dLat = north / metersPerDegLat
        val dLon = east / metersPerDegLon

        val tier = demote(
            base = input.openingFix.tier,
            walkDistanceM = walkDistance,
        )

        return PlotCenterResult(
            lat = input.openingFix.lat + dLat,
            lon = input.openingFix.lon + dLon,
            source = PositionSource.VIO_OFFSET,
            tier = tier,
            nSamples = input.openingFix.nSamples,
            medianHAccuracyM = input.openingFix.medianHAccuracyM,
            sampleStdXyM = input.openingFix.sampleStdXyM,
            offsetWalkM = walkDistance,
        )
    }

    /// §7.3.2 tier rule: inherit the opening fix tier, demote one
    /// step if the walk was > 100 m, and clamp to D if > 200 m (the
    /// spec says "too much drift expected").
    fun demote(base: PositionTier, walkDistanceM: Float): PositionTier {
        if (walkDistanceM > 200f) return PositionTier.D
        if (walkDistanceM > 100f) return oneStepDown(base)
        return base
    }

    internal fun oneStepDown(t: PositionTier): PositionTier = when (t) {
        PositionTier.A -> PositionTier.B
        PositionTier.B -> PositionTier.C
        PositionTier.C -> PositionTier.D
        PositionTier.D -> PositionTier.D
    }
}
