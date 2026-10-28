package com.hcjeong.forestix.sensors

import org.junit.Assert.*
import org.junit.Test

/** Synthetic grids only. No cup reference, field records or fitted constants. */
class DbhMeasurementDepthTest {
    private val time=100_000_000_000L
    private fun frame(full:Float=2f,rawMM:Int=1000,score:Int=255,policy:String=DbhMeasurementDepth.QUALIFIED_RAW,
                      rawTime:Long=time,confidenceTime:Long=time,hasRaw:Boolean=true,
                      scoreAt:((Int,Int)->Int)?=null,depthAt:((Int,Int)->Int)?=null):ArDepthFrame {
        val w=64;val h=48
        return ArDepthFrame(w,h,FloatArray(w*h){full},ByteArray(w*h){2},100.0,100.0,32.0,24.0,
            FloatArray(16){if(it%5==0)1f else 0f},depthFromViewAffine=floatArrayOf(1f,0f,0f,0f,1f,0f),
            frameTimestampNanos=time,rawDepthEvidence=if(hasRaw)DbhRawDepthEvidence(w,h,
                ShortArray(w*h){(depthAt?.invoke(it%w,it/w)?:rawMM).toShort()},
                ByteArray(w*h){(scoreAt?.invoke(it%w,it/w)?:score).toByte()},rawTime,confidenceTime,time)else null,
            depthInputPolicy=policy)
    }
    private val axis=GuideAxis.Row(24)
    private fun result(f:ArDepthFrame)=DBHEstimator.bracketChordEstimate(listOf(f),axis,.25,.75,ProjectCalibration.identity)
    private fun rejected(f:ArDepthFrame){val r=result(f);assertTrue(r==null||r.confidence==ConfidenceTier.RED)}

    @Test fun qualifiedSourceReplacesBiasedDenseDistanceNotTheGeometry() {
        val f=frame();val selected=result(f)!!
        val expected=BoundaryAlignment.sampledDiameterCm(32.0,1.0,100.0)!!
        assertEquals(expected,selected.diameterCm.toDouble(),1e-5)
        assertEquals(2*expected,result(frame(policy=DbhMeasurementDepth.LEGACY_FULL))!!.diameterCm.toDouble(),1e-5)
        assertTrue(f.depth.all{it==2f});assertTrue(f.confidence.all{it.toInt()==2})
    }
    @Test fun noRawAndLowConfidenceNeverFallBackToDenseValidityTwo() {
        rejected(frame(hasRaw=false));rejected(frame(score=127));rejected(frame(score=0))
        assertNotNull(result(frame(score=128)))
    }
    @Test fun aSingleApparentlyCorrectPointIsNotAMeasurement() {
        rejected(frame(scoreAt={x,y->if(x==32&&y==24)255 else 0}))
    }
    @Test fun clusteredHighConfidenceCannotStandInForWidthCoverage() {
        rejected(frame(scoreAt={x,_->if(x<35)255 else 0}))
    }
    @Test fun aMajorityOfVerticalNeighboursIsRequiredAtEachAxisPosition() {
        rejected(frame(scoreAt={_,y->if(y in 22..23)255 else 0}))
        assertEquals(ConfidenceTier.YELLOW,result(frame(scoreAt={_,y->if(y in 22..24)255 else 0}))!!.confidence)
    }
    @Test fun oneOrTwoVerticalOutliersCannotMoveTheRawMedian() {
        val clean=result(frame())!!
        val outliers=result(frame(depthAt={_,y->if(y in 22..23)7000 else 1000}))!!
        assertEquals(clean.diameterCm,outliers.diameterCm,0f)
    }
    @Test fun eachAxisPositionHasEqualWeightNotTheNumberOfValidVerticalPixels() {
        val f=frame(scoreAt={x,y->if(x<32||y<=24)255 else 0},depthAt={x,_->if(x<32)1000 else 1020})
        val p=DbhMeasurementDepth.profile(f,axis,16.0,48.0)
        assertTrue(p.qualified)
        assertEquals(1.01,p.medianM!!,.011)
    }
    @Test fun oldInformationReprojectedToCurrentViewIsAllowedButStaleProjectionIsNot() {
        assertNotNull(result(frame(rawTime=time-30_000_000_000L)))
        rejected(frame(rawTime=time-30_000_000_000L,confidenceTime=time-1_000_000_000L))
        rejected(frame(rawTime=time+60_000_000L))
    }
    @Test fun measuredRawRangeUsesHalfToFiveMetres() {
        for(mm in listOf(499,5001))rejected(frame(rawMM=mm))
        for(mm in listOf(500,1000,5000))assertEquals(ConfidenceTier.YELLOW,result(frame(rawMM=mm))!!.confidence)
    }
    @Test fun unknownInputPolicyIsNotLegacyFallback() {
        rejected(frame(policy="unrecognized"));assertFalse(DbhDepthInputPolicy.acceptsFrame(frame(policy="unrecognized")))
    }
    @Test fun liveAdjustAndCapturedAdjustUseTheSameQualifiedDepth() {
        val f=frame();val live=DBHEstimator.constrainedEstimate(f,16f,48f,24f,ProjectCalibration.identity)!!
        assertTrue(live.locked);assertEquals(result(f)!!.diameterCm,live.diameterCm,0f)
        val bad=DBHEstimator.constrainedEstimate(frame(hasRaw=false),16f,48f,24f,ProjectCalibration.identity)!!
        assertFalse(bad.locked);assertEquals(DbhMeasurementDepth.RETRY_MESSAGE,bad.depthRejection)
    }
    @Test fun incompleteDeveloperWindowCannotCircumventDepthSourceQualification() {
        val frames=List(5){frame(hasRaw=false)}
        val r=DBHEstimator.bracketChordEstimate(frames,axis,.25,.75,ProjectCalibration.identity)
        assertTrue(r==null||r.confidence==ConfidenceTier.RED)
    }
    @Test fun mixedLegacyAndQualifiedFramesCannotBypassRawQualification() {
        val frames=List(4){frame(policy=DbhMeasurementDepth.LEGACY_FULL)}+frame(hasRaw=false)
        assertFalse(DbhDepthInputPolicy.acceptsWindow(frames))
        assertNull(DBHEstimator.bracketChordEstimate(frames,axis,.25,.75,ProjectCalibration.identity))
        assertEquals(ConfidenceTier.RED,DBHEstimator.estimateChord(frames,32.0,24.0,axis,ProjectCalibration.identity)!!.confidence)
    }
    @Test fun invalidAndTooNarrowBracketsDoNotInventSamples() {
        assertFalse(DbhMeasurementDepth.profile(frame(),axis,Double.NaN,20.0).qualified)
        assertFalse(DbhMeasurementDepth.profile(frame(),axis,31.9,32.1).qualified)
    }
}
