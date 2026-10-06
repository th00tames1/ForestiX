package com.hcjeong.forestix.sensors

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SharedGeometryTest {
    private fun fixtures(key: String): JsonArray {
        val text = javaClass.classLoader!!.getResourceAsStream("geometry.json")!!
            .bufferedReader().use { it.readText() }
        return Json.parseToJsonElement(text).jsonObject.getValue(key).jsonArray
    }
    private fun JsonObject.number(key: String) = getValue(key).jsonPrimitive.double

    @Test fun analyticCylinderFixtures() {
        for (item in fixtures("diameter")) {
            val f = item.jsonObject
            val result = DBHEstimator.silhouetteDiameterCm(f.number("span_px"), f.number("depth_m"), f.number("focal_px"))
            assertEquals(f.number("expected_cm"), result!!, 1e-8)
        }
        assertNull(DBHEstimator.silhouetteDiameterCm(0.0, 1.0, 100.0))
        assertNull(DBHEstimator.silhouetteDiameterCm(Double.NaN, 1.0, 100.0))
    }

    @Test fun heightAndWarnCountFixtures() {
        for (item in fixtures("height")) {
            val f = item.jsonObject
            val r = HeightEstimator.estimate(0f, 0f, f.number("d_m").toFloat(), 0f,
                f.number("top_rad").toFloat(), f.number("base_rad").toFloat())
            assertEquals(f.number("height_m"), r.heightM.toDouble(), 0.0001)
            assertEquals(f.number("sigma_m"), r.sigmaHm!!.toDouble(), 0.0001)
            assertEquals(f.getValue("tier").jsonPrimitive.content, r.confidence.name.lowercase())
            assertTrue(HeightEstimator.canAccept(r))
        }
    }

    @Test fun invalidHeightCannotBeAccepted() {
        for (d in listOf(0f, 0.1f, Float.NaN)) {
            val r = HeightEstimator.estimate(0f, 0f, d, 0f, 0.5f, 0f)
            assertFalse(HeightEstimator.canAccept(r))
            assertNull(r.sigmaHm)
        }
        assertFalse(HeightEstimator.canAccept(HeightEstimator.estimate(0f, 0f, 10f, 0f, -0.2f, 0.2f)))
    }

    @Test fun staleCalibrationDoesNotAlterRawDiameter() {
        val stale = ProjectCalibration(dbhCorrectionAlpha=3f, dbhCorrectionBeta=2f,
            dbhCalibrationEpoch=DBHEstimator.ESTIMATOR_EPOCH-1)
        assertEquals(30.0, stale.appliedToRawCm(30.0), 0.0)
        assertEquals(63.0, stale.copy(dbhCalibrationEpoch=DBHEstimator.ESTIMATOR_EPOCH).appliedToRawCm(30.0), 0.0)
    }

    @Test fun aggregationUsesUpperMedianThenThreeNearestSamples() {
        fun sample(d: Float, tier: ConfidenceTier = ConfidenceTier.GREEN) =
            DBHResult(d, 0f, 0f, 60f, 1f, 1f, 20, tier, DBHMethod.LIDAR_CHORD_SILHOUETTE, null)
        val values = listOf(10f, 20f, 32f, 70f).map { sample(it) }
        val result = DBHEstimator.aggregateSamples(values)!!
        assertEquals(62.0/3.0, result.diameterCm.toDouble(), 0.00001)
        assertNull(DBHEstimator.aggregateSamples(listOf(sample(10f), sample(20f), sample(32f, ConfidenceTier.RED))))
        assertEquals(result.diameterCm, DBHEstimator.aggregateSamples(values + sample(500f, ConfidenceTier.RED))!!.diameterCm)
    }
}
