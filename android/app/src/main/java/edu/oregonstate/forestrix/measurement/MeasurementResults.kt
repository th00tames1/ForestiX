package edu.oregonstate.forestrix.measurement

import edu.oregonstate.forestrix.models.DbhMethod
import edu.oregonstate.forestrix.models.HeightMethod
import edu.oregonstate.forestrix.models.PositionSource
import edu.oregonstate.forestrix.models.PositionTier

data class DbhResult(
    val diameterCm: Float,
    val arcCoverageDeg: Float = 0f,
    val rmseMm: Float = 0f,
    val sigmaRmm: Float = 0f,
    val nInliers: Int = 0,
    val confidence: ConfidenceTier,
    val method: DbhMethod,
    val rejectionReason: String? = null
)

data class HeightResult(
    val heightM: Float,
    val dHm: Float,
    val alphaTopRad: Float,
    val alphaBaseRad: Float,
    val sigmaHm: Float,
    val confidence: ConfidenceTier,
    val method: HeightMethod,
    val rejectionReason: String? = null
)

data class PlotCenterResult(
    val lat: Double,
    val lon: Double,
    val source: PositionSource,
    val tier: PositionTier,
    val nSamples: Int,
    val medianHAccuracyM: Float,
    val sampleStdXyM: Float,
    val offsetWalkM: Float? = null
)
