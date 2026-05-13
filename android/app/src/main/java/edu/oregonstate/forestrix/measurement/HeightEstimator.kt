package edu.oregonstate.forestrix.measurement

import edu.oregonstate.forestrix.models.HeightMethod
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tan

data class Vec3(val x: Float, val y: Float, val z: Float)

data class HeightMeasureInput(
    val anchorPointWorld: Vec3,
    val standingPointWorld: Vec3,
    val alphaTopRad: Float,
    val alphaBaseRad: Float,
    val trackingStateWasNormalThroughout: Boolean,
    val projectCalibration: ProjectCalibration = ProjectCalibration.Identity
)

object HeightEstimator {
    const val MinDhMeters = 3.0f
    const val YellowDhMeters = 25.0f
    const val HighDriftDhMeters = 30.0f
    val MaxAlphaRadRed: Float = (85.0 * Math.PI / 180.0).toFloat()
    val MaxAlphaRadYellow: Float = (75.0 * Math.PI / 180.0).toFloat()
    const val MinHMeters = 1.5f
    const val MaxHMeters = 100.0f
    val SigmaAlphaRad: Float = (0.3 * Math.PI / 180.0).toFloat()
    const val SigmaRatioYellow = 0.05f

    fun estimate(input: HeightMeasureInput): HeightResult {
        val dx = input.standingPointWorld.x - input.anchorPointWorld.x
        val dz = input.standingPointWorld.z - input.anchorPointWorld.z
        val dh = sqrt(dx * dx + dz * dz)

        if (!input.trackingStateWasNormalThroughout) {
            return red(input, dh, "AR tracking was lost during the measurement")
        }
        if (dh < MinDhMeters) {
            return red(input, dh, "Too close; step back before aiming")
        }
        if (abs(input.alphaTopRad) > MaxAlphaRadRed) {
            return red(input, dh, "Top angle too steep; step back")
        }
        if (abs(input.alphaBaseRad) > MaxAlphaRadRed) {
            return red(input, dh, "Base angle too steep; step back")
        }

        val tanTop = tan(input.alphaTopRad)
        val tanBase = tan(input.alphaBaseRad)
        if (tanTop <= tanBase) {
            return red(input, dh, "Top aim was at or below the base")
        }

        val heightM = dh * (tanTop - tanBase)
        if (heightM !in MinHMeters..MaxHMeters) {
            return red(input, dh, "Computed height is outside the accepted tree range")
        }

        val sigmaD = input.projectCalibration.vioDriftFraction * dh
        val tanDiff = tanTop - tanBase
        val secTop = 1.0f / cos(input.alphaTopRad)
        val secBase = 1.0f / cos(input.alphaBaseRad)
        val term1 = tanDiff * tanDiff * sigmaD * sigmaD
        val term2 = dh * dh * secTop.pow(4) * SigmaAlphaRad * SigmaAlphaRad
        val term3 = dh * dh * secBase.pow(4) * SigmaAlphaRad * SigmaAlphaRad
        val sigmaH = sqrt(term1 + term2 + term3)

        val checks = listOf(
            check(sigmaH / heightM <= SigmaRatioYellow, Severity.WARN, "Height precision worse than +/-5%"),
            check(dh <= YellowDhMeters, Severity.WARN, "Walked back more than 25 m"),
            check(abs(input.alphaTopRad) <= MaxAlphaRadYellow, Severity.WARN, "Top aim angle steeper than 75 degrees"),
            check(dh <= HighDriftDhMeters, Severity.WARN, "Walked back more than 30 m; tracking drift risk")
        )

        return HeightResult(
            heightM = heightM,
            dHm = dh,
            alphaTopRad = input.alphaTopRad,
            alphaBaseRad = input.alphaBaseRad,
            sigmaHm = sigmaH,
            confidence = combineChecks(checks),
            method = HeightMethod.ARCORE_VIO_WALKOFF_TANGENT
        )
    }

    private fun red(input: HeightMeasureInput, dh: Float, reason: String): HeightResult {
        val heightM = dh * (tan(input.alphaTopRad) - tan(input.alphaBaseRad))
        return HeightResult(
            heightM = heightM,
            dHm = dh,
            alphaTopRad = input.alphaTopRad,
            alphaBaseRad = input.alphaBaseRad,
            sigmaHm = 0f,
            confidence = ConfidenceTier.RED,
            method = HeightMethod.ARCORE_VIO_WALKOFF_TANGENT,
            rejectionReason = reason
        )
    }
}
