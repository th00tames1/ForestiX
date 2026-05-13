package edu.oregonstate.forestrix.positioning

import edu.oregonstate.forestrix.measurement.PlotCenterResult
import edu.oregonstate.forestrix.measurement.Vec3
import edu.oregonstate.forestrix.models.PositionSource
import edu.oregonstate.forestrix.models.PositionTier
import kotlin.math.cos
import kotlin.math.sqrt

object OffsetFromOpening {
    fun compute(
        openingFix: PlotCenterResult,
        openingPointWorld: Vec3,
        plotPointWorld: Vec3,
        trackingStateWasNormalThroughout: Boolean
    ): PlotCenterResult? {
        if (!trackingStateWasNormalThroughout) return null

        val dx = plotPointWorld.x - openingPointWorld.x
        val dz = plotPointWorld.z - openingPointWorld.z
        val walkDistance = sqrt(dx * dx + dz * dz)

        val east = dx.toDouble()
        val north = (-dz).toDouble()
        val metersPerDegLat = 111_320.0
        val metersPerDegLon = 111_320.0 * cos(Math.toRadians(openingFix.lat))

        return PlotCenterResult(
            lat = openingFix.lat + north / metersPerDegLat,
            lon = openingFix.lon + east / metersPerDegLon,
            source = PositionSource.VIO_OFFSET,
            tier = demote(openingFix.tier, walkDistance),
            nSamples = openingFix.nSamples,
            medianHAccuracyM = openingFix.medianHAccuracyM,
            sampleStdXyM = openingFix.sampleStdXyM,
            offsetWalkM = walkDistance
        )
    }

    fun demote(base: PositionTier, walkDistanceM: Float): PositionTier =
        when {
            walkDistanceM > 200f -> PositionTier.D
            walkDistanceM > 100f -> oneStepDown(base)
            else -> base
        }

    private fun oneStepDown(tier: PositionTier): PositionTier =
        when (tier) {
            PositionTier.A -> PositionTier.B
            PositionTier.B -> PositionTier.C
            PositionTier.C -> PositionTier.D
            PositionTier.D -> PositionTier.D
        }
}
