package com.hcjeong.forestix.sensors

import org.junit.Assert.*
import org.junit.Test

class DbhDepthInputPolicyTest {
    private fun frame(depth: Float = 1f, age: Long = 0L) = ArDepthFrame(
        256, 192, FloatArray(256*192) { depth }, ByteArray(256*192) { 2 },
        210.0, 210.0, 128.0, 96.0, FloatArray(16) { if (it%5==0) 1f else 0f },
        depthFromViewAffine=floatArrayOf(0f,.5f,0f,-.5f,0f,192f),
        frameTimestampNanos=12_000_000_000L, depthAgeNanos=age,
    )
    private val axis = GuideAxis.Col(128)
    private val cal = ProjectCalibration.identity

    @Test fun distanceWindowIsInclusiveAndRejectsNonfiniteValues() {
        for (z in listOf(0.5, 1.0, 4.0, 5.0)) assertTrue(DbhDepthInputPolicy.acceptsDistance(z))
        for (z in listOf(0.0, 0.499, 5.001, Double.NaN, Double.POSITIVE_INFINITY))
            assertFalse(DbhDepthInputPolicy.acceptsDistance(z))
        assertEquals(0.5f..5.0f, DBHEstimator.USABLE_TAP_DEPTH_M)
    }

    @Test fun realSensorTimestampsAreRequiredAndHalfSecondIsTheLimit() {
        val now = 20_000_000_000L
        assertTrue(DbhDepthInputPolicy.acceptsSensorTimes(now, now))
        assertTrue(DbhDepthInputPolicy.acceptsSensorTimes(now, now-500_000_000L))
        assertFalse(DbhDepthInputPolicy.acceptsSensorTimes(now, now-500_000_001L))
        assertFalse(DbhDepthInputPolicy.acceptsSensorTimes(now, now-13_333_443_516L))
        assertFalse(DbhDepthInputPolicy.acceptsSensorTimes(0L, now))
        assertFalse(DbhDepthInputPolicy.acceptsSensorTimes(now, 0L))
    }

    @Test fun smallClockOffsetIsNotMistakenForStaleDepthButLargeFutureTimeIsRejected() {
        val now=20_000_000_000L
        assertTrue(DbhDepthInputPolicy.acceptsSensorTimes(now,now+16_000_000L))
        assertTrue(DbhDepthInputPolicy.acceptsSensorTimes(now,now+50_000_000L))
        assertFalse(DbhDepthInputPolicy.acceptsSensorTimes(now,now+50_000_001L))
    }

    @Test fun frozenARFrameCannotPassJustBecauseItsTwoSensorTimesMatch() {
        assertTrue(DbhDepthInputPolicy.acceptsArrival(1_000_000_000L,1_500_000_000L))
        assertFalse(DbhDepthInputPolicy.acceptsArrival(1_000_000_000L,1_500_000_001L))
        assertFalse(DbhDepthInputPolicy.acceptsArrival(0L,1_000_000_000L))
        assertFalse(DbhDepthInputPolicy.acceptsArrival(1_000_000_000L,999_999_999L))
    }

    @Test fun bracketLiveCaptureAndDeveloperBurstUseTheSameDistanceGate() {
        for (z in listOf(.499f,5.001f)) {
            val f=frame(z)
            assertNull(DBHEstimator.bracketChordFit(f,axis,.35,.65))
            assertNull(DBHEstimator.bracketChordEstimate(listOf(f),axis,.35,.65,cal))
            val burst=DBHEstimator.bracketChordEstimate(List(5){f},axis,.35,.65,cal)
            assertTrue(burst == null || burst.confidence == ConfidenceTier.RED)
            assertFalse(DBHEstimator.constrainedEstimate(f,100f,180f,256f,cal)?.locked == true)
        }
        for (z in listOf(.5f,1f,4f,5f)) {
            val f=frame(z)
            val expected=BoundaryAlignment.sampledDiameterCm(.3*192,z.toDouble(),210.0)!!
            val result=DBHEstimator.bracketChordEstimate(listOf(f),axis,.35,.65,cal)!!
            assertEquals(expected,result.diameterCm.toDouble(),1e-4)
            assertTrue(DBHEstimator.constrainedEstimate(f,100f,180f,256f,cal)!!.locked)
        }
    }

    @Test fun staleDepthIsRejectedEvenWhenGeometryAndDistanceLookValid() {
        val f=frame(1f,13_318_058_552L)
        assertNull(DBHEstimator.bracketChordFit(f,axis,.35,.65))
        assertNull(DBHEstimator.bracketChordEstimate(listOf(f),axis,.35,.65,cal))
        assertNull(DBHEstimator.bracketChordEstimate(listOf(frame(),frame(),f,frame(),frame()),axis,.35,.65,cal))
        assertNull(DBHEstimator.constrainedEstimate(f,100f,180f,256f,cal))
        assertNull(DBHEstimator.livePreview(f,128.0,96.0,GuideAxis.Row(96),cal))
        assertEquals(ConfidenceTier.RED,DBHEstimator.estimateChord(listOf(f),128.0,96.0,GuideAxis.Row(96),cal)!!.confidence)
        assertEquals(ConfidenceTier.RED,DBHEstimator.estimate(DbhScanInput(listOf(f),128.0,96.0,axis,cal))!!.confidence)
    }

    @Test fun acquisitionAgeIsNotInferenceRuntimeAndValidGeometryStaysUnchanged() {
        val f=frame(1f,500_000_000L)
        // The fixture's frame time is old relative to this process's clock.
        // Frozen capture admission must NOT compare it to inference finish time.
        assertNotNull(DBHEstimator.bracketChordEstimate(listOf(f),axis,.35,.65,cal))
        assertNull(DBHEstimator.bracketChordEstimate(listOf(frame(1f,-1L)),axis,.35,.65,cal))
    }
}
