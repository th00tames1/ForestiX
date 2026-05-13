package edu.oregonstate.forestrix.positioning

import edu.oregonstate.forestrix.measurement.PlotCenterResult
import edu.oregonstate.forestrix.models.PositionSource
import edu.oregonstate.forestrix.models.PositionTier
import kotlin.math.cos
import kotlin.math.sqrt

data class LocationSample(
    val latitude: Double,
    val longitude: Double,
    val horizontalAccuracyM: Double,
    val timestampMillis: Long = System.currentTimeMillis()
)

object GpsAveraging {
    fun compute(samples: List<LocationSample>, maxAcceptableAccuracyM: Float = 20f): PlotCenterResult? {
        val accepted = samples.filter {
            it.horizontalAccuracyM > 0.0 && it.horizontalAccuracyM <= maxAcceptableAccuracyM
        }
        if (accepted.size < 30) return null

        val origin = accepted.first()
        val metersPerDegLat = 111_320.0
        val metersPerDegLon = 111_320.0 * cos(Math.toRadians(origin.latitude))
        val easts = accepted.map { (it.longitude - origin.longitude) * metersPerDegLon }
        val norths = accepted.map { (it.latitude - origin.latitude) * metersPerDegLat }

        val medianE = median(easts)
        val medianN = median(norths)
        val meanE = easts.average()
        val meanN = norths.average()
        var varE = 0.0
        var varN = 0.0
        for (i in accepted.indices) {
            val de = easts[i] - meanE
            val dn = norths[i] - meanN
            varE += de * de
            varN += dn * dn
        }
        val sampleStdXy = sqrt(varE / accepted.size + varN / accepted.size)
        val medianHAccuracy = median(accepted.map { it.horizontalAccuracyM }).toFloat()
        val tier = classify(medianHAccuracy, sampleStdXy.toFloat())

        return PlotCenterResult(
            lat = origin.latitude + medianN / metersPerDegLat,
            lon = origin.longitude + medianE / metersPerDegLon,
            source = PositionSource.GPS_AVERAGED,
            tier = tier,
            nSamples = accepted.size,
            medianHAccuracyM = medianHAccuracy,
            sampleStdXyM = sampleStdXy.toFloat()
        )
    }

    fun classify(medianHAccuracyM: Float, sampleStdXyM: Float): PositionTier =
        when {
            medianHAccuracyM < 5f && sampleStdXyM < 3f -> PositionTier.A
            medianHAccuracyM < 10f && sampleStdXyM < 5f -> PositionTier.B
            medianHAccuracyM < 20f -> PositionTier.C
            else -> PositionTier.D
        }

    private fun median(values: List<Double>): Double {
        require(values.isNotEmpty())
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) * 0.5
    }
}
