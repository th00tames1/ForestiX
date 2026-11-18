package com.hcjeong.forestix.sensors

import org.junit.Assert.*
import org.junit.Test

/** Owned synthetic grids, deliberately contradictory dense/Raw depths. */
class DbhMeasurementRangeGuidanceTest {
    private val time = 100_000_000_000L
    private fun frame(full: Float = .3f, rawMM: Int = 1200, score: Int = 255,
                      hasRaw: Boolean = true, policy: String = DbhMeasurementDepth.RAW_RAY,
                      projectionAge: Long = 0, informationAge: Long = 0,
                      frameAge: Long = 0, timestamp: Long = time, column: Boolean = false): ArDepthFrame {
        val w = if (column) 9 else 64; val h = if (column) 64 else 9
        return ArDepthFrame(w,h,FloatArray(w*h){full},ByteArray(w*h){2},100.0,100.0,
            if(column)4.0 else 32.0,if(column)32.0 else 4.0,
            FloatArray(16){if(it%5==0)1f else 0f},
            depthFromViewAffine=if(column)floatArrayOf(0f,1f,0f,1f,0f,0f) else floatArrayOf(1f,0f,0f,0f,1f,0f),
            frameTimestampNanos=timestamp,depthAgeNanos=frameAge,depthInputPolicy=policy,
            rawDepthEvidence=if(hasRaw)DbhRawDepthEvidence(w,h,ShortArray(w*h){rawMM.toShort()},
                ByteArray(w*h){score.toByte()},time-informationAge,time-projectionAge,time)else null)
    }
    private fun estimate(f: ArDepthFrame) = DBHEstimator.constrainedEstimate(f,24f,40f,4f,ProjectCalibration.identity)
    private fun message(f: ArDepthFrame, imperial: Boolean = false): String? {
        val guide=DbhMeasurementRangeGuidance()
        guide.observe(f,estimate(f),10.0,bracketed=true)
        return guide.message(10.0,imperial)
    }

    @Test fun validRawDistanceDoesNotInheritDenseNearOrFarWarnings() {
        for(dense in listOf(.25f,6f)) {
            val f=frame(full=dense)
            assertTrue(estimate(f)!!.locked)
            assertEquals(1.2f,estimate(f)!!.distanceM,.00001f)
            assertNull(message(f))
        }
    }
    @Test fun tooCloseMessageUsesExactlyTheRejectedMeasurementDistance() {
        for(policy in listOf(DbhMeasurementDepth.RAW_RAY,DbhMeasurementDepth.QUALIFIED_RAW)) {
            val f=frame(full=2f,rawMM=499,policy=policy)
            val preview=estimate(f)!!
            assertFalse(preview.locked);assertEquals(.499f,preview.distanceM,.00001f)
            assertEquals("Move back — keep at least 0.5 m from the target.",message(f))
            assertNull(DBHEstimator.bracketChordFit(f,GuideAxis.Row(4),24.0/64,40.0/64))
        }
    }
    @Test fun tooFarRawDepthCannotBeHiddenByNearDenseDepth() {
        val f=frame(full=.25f,rawMM=5001)
        assertFalse(estimate(f)!!.locked)
        assertEquals("Move closer — keep within 5 m of the target.",message(f))
        assertNull(DBHEstimator.bracketChordFit(f,GuideAxis.Row(4),24.0/64,40.0/64))
    }
    @Test fun exactMeasurementRangeEndpointsRemainInclusive() {
        for(mm in listOf(500,5000)) {
            val f=frame(rawMM=mm)
            assertTrue(estimate(f)!!.locked)
            assertNull(message(f))
        }
    }
    @Test fun missingLowConfidenceAndStaleRawDoNotFallBackToDenseDepth() {
        for(f in listOf(frame(hasRaw=false),frame(score=127),frame(projectionAge=1_000_000_000L))) {
            assertFalse(estimate(f)!!.locked)
            assertNull(message(f))
        }
    }
    @Test fun staleFullFramesAndUnknownPoliciesStayUnknown() {
        for(f in listOf(frame(frameAge=500_000_001L),frame(policy="unknown"))) {
            assertNull(estimate(f));assertNull(message(f))
        }
    }
    @Test fun repeatedFrameCannotRenewAnExpiredWarning() {
        val f=frame(rawMM=499);val p=estimate(f);val guide=DbhMeasurementRangeGuidance()
        guide.observe(f,p,10.0,true);assertNotNull(guide.message(10.0,false))
        guide.observe(f,p,10.49,true)
        assertNull(guide.message(10.501,false))
        val next=frame(rawMM=499,timestamp=time+1)
        guide.observe(next,estimate(next),10.6,true)
        assertNotNull(guide.message(10.6,false))
    }
    @Test fun missingBracketImmediatelyClearsThePreviousRangeNotice() {
        val f=frame(rawMM=499);val guide=DbhMeasurementRangeGuidance()
        guide.observe(f,estimate(f),10.0,true)
        assertNotNull(guide.message(10.0,false))
        guide.observe(f,null,10.1,true)
        assertNull(guide.message(10.1,false))
    }
    @Test fun unlockedLegacyTapSeedIsNotTreatedAsMeasurementDepth() {
        val f=frame();val guide=DbhMeasurementRangeGuidance()
        val tapFallback=DBHEstimator.DbhPreview(0f,.25f,false,0)
        guide.observe(f,tapFallback,10.0,bracketed=false)
        assertNull(guide.message(10.0,false))
    }
    @Test fun resetAndInvalidTimeCannotLeaveAnOldWarning() {
        val f=frame(rawMM=499);val guide=DbhMeasurementRangeGuidance()
        guide.observe(f,estimate(f),10.0,true);guide.reset()
        assertNull(guide.message(10.1,false))
        guide.observe(f,estimate(f),Double.NaN,true)
        assertNull(guide.message(10.1,false))
    }
    @Test fun imperialChangesOnlyTheWording() {
        assertEquals("Move back — keep at least 2 ft from the target.",message(frame(rawMM=499),true))
        assertEquals("Move closer — keep within 16 ft of the target.",message(frame(rawMM=5001),true))
        assertTrue(estimate(frame(rawMM=500))!!.locked)
    }
    @Test fun reprojectedOldInformationUsesTheCurrentConfidenceProjectionClock() {
        val f=frame(rawMM=499,informationAge=30_000_000_000L)
        assertNotNull(message(f))
        val aged=frame(rawMM=499,informationAge=30_000_000_000L,projectionAge=490_000_000L)
        val guide=DbhMeasurementRangeGuidance()
        guide.observe(aged,estimate(aged),10.0,true)
        assertNotNull(guide.message(10.0,false))
        assertNull(guide.message(10.02,false))
    }
    @Test fun rotatedColumnMappingUsesTheSameActualMeasurementDepth() {
        val row=frame();val column=frame(column=true)
        assertTrue(DBHEstimator.bracketDepthGeometry(column,24f,40f,4f)!!.axis is GuideAxis.Col)
        assertEquals(estimate(row)!!.distanceM,estimate(column)!!.distanceM,0f)
        assertNull(message(column))
    }
    @Test fun observingGuidanceCannotChangePreviewCaptureOrInputBytes() {
        val f=frame();val before=estimate(f)!!
        val dense=f.depth.clone();val raw=f.rawDepthEvidence!!.millimeters.clone()
        val confidence=f.rawDepthEvidence!!.confidence.clone()
        val captureBefore=DBHEstimator.bracketChordEstimate(listOf(f),GuideAxis.Row(4),24.0/64,40.0/64,ProjectCalibration.identity)!!
        val guide=DbhMeasurementRangeGuidance();guide.observe(f,before,10.0,true);guide.message(10.0,false)
        assertEquals(before,estimate(f))
        val captureAfter=DBHEstimator.bracketChordEstimate(listOf(f),GuideAxis.Row(4),24.0/64,40.0/64,ProjectCalibration.identity)!!
        assertEquals(captureBefore.diameterCm,captureAfter.diameterCm,0f)
        assertEquals(before.diameterCm,captureAfter.diameterCm,0f)
        assertArrayEquals(dense,f.depth,0f)
        assertArrayEquals(raw,f.rawDepthEvidence!!.millimeters)
        assertArrayEquals(confidence,f.rawDepthEvidence!!.confidence)
    }
}
