package edu.oregonstate.forestrix

import edu.oregonstate.forestrix.measurement.ConfidenceTier
import edu.oregonstate.forestrix.measurement.HeightEstimator
import edu.oregonstate.forestrix.measurement.HeightMeasureInput
import edu.oregonstate.forestrix.measurement.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class HeightEstimatorTest {
    @Test
    fun walkoffTangentComputesHeightAndUncertainty() {
        val result = HeightEstimator.estimate(
            HeightMeasureInput(
                anchorPointWorld = Vec3(0f, 0f, 0f),
                standingPointWorld = Vec3(0f, 0f, -22f),
                alphaTopRad = deg(50f),
                alphaBaseRad = deg(-6f),
                trackingStateWasNormalThroughout = true
            )
        )

        assertEquals(28.5f, result.heightM, 0.4f)
        assertTrue(result.sigmaHm > 0f)
        assertTrue(result.confidence == ConfidenceTier.GREEN || result.confidence == ConfidenceTier.YELLOW)
    }

    @Test
    fun rejectsTooCloseMeasurement() {
        val result = HeightEstimator.estimate(
            HeightMeasureInput(
                anchorPointWorld = Vec3(0f, 0f, 0f),
                standingPointWorld = Vec3(0f, 0f, -2f),
                alphaTopRad = deg(50f),
                alphaBaseRad = deg(-6f),
                trackingStateWasNormalThroughout = true
            )
        )

        assertEquals(ConfidenceTier.RED, result.confidence)
        assertTrue(result.rejectionReason?.contains("Too close") == true)
    }

    private fun deg(value: Float): Float = (value * PI / 180.0).toFloat()
}
