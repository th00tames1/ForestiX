package com.hcjeong.forestix.sensors

import org.junit.Assert.*
import org.junit.Test

class DbhRawDepthEvidenceTest {
    private val time=20_000_000_000L
    private fun frame(raw:Int?=1000,score:Int=255,rawTime:Long=time,confidenceTime:Long=rawTime,
                      fill:((Int)->Int)?=null):ArDepthFrame {
        val w=32;val h=24
        return ArDepthFrame(w,h,FloatArray(w*h){1f},ByteArray(w*h){2},100.0,100.0,16.0,12.0,
            FloatArray(16){if(it%5==0)1f else 0f},frameTimestampNanos=time,
            rawDepthEvidence=raw?.let{DbhRawDepthEvidence(w,h,ShortArray(w*h){(fill?.invoke(it)?:raw).toShort()},
                ByteArray(w*h){score.toByte()},rawTime,confidenceTime,time)})
    }
    @Test fun missingRawIsUnknownRatherThanAZeroDistanceOrHighConfidence() {
        assertEquals("raw_not_available",DbhRawDepthSupport.assess(frame(null),GuideAxis.Row(12),.2,.8).status)
    }
    @Test fun confidenceThresholdRetainsRealUnsignedScores() {
        for(score in listOf(0,127))assertEquals("raw_support_insufficient",DbhRawDepthSupport.assess(frame(score=score),GuideAxis.Row(12),.2,.8).status)
        for(score in listOf(128,255))assertEquals("raw_full_agree",DbhRawDepthSupport.assess(frame(score=score),GuideAxis.Row(12),.2,.8).status)
    }
    @Test fun sparseHolesCannotBeInventedFromFullDepth() {
        assertEquals("raw_support_insufficient",DbhRawDepthSupport.assess(frame(raw=0),GuideAxis.Row(12),.2,.8).status)
        val a=DbhRawDepthSupport.assess(frame(fill={if(it%32<12)1000 else 0}),GuideAxis.Row(12),.2,.8)
        assertEquals("raw_support_insufficient",a.status)
    }
    @Test fun currentProjectionIsDistinctFromOriginalInformationTime() {
        for(f in listOf(frame(rawTime=time-13_333_443_516L),frame(confidenceTime=time-1_000_000_000L),frame(rawTime=time+60_000_000L)))
            assertEquals("raw_not_current_or_aligned",DbhRawDepthSupport.assess(f,GuideAxis.Row(12),.2,.8).status)
        // Reprojected but recent depth is legitimate, not automatically invalid.
        assertEquals("raw_full_agree",DbhRawDepthSupport.assess(frame(rawTime=time-100_000_000L),GuideAxis.Row(12),.2,.8).status)
        assertEquals("raw_full_agree",DbhRawDepthSupport.assess(frame(rawTime=time-15_000_000_000L,confidenceTime=time),GuideAxis.Row(12),.2,.8).status)
    }
    @Test fun nativeRowAndPortraitColumnCompareTheSamePixels() {
        val a=DbhRawDepthSupport.assess(frame(600),GuideAxis.Row(12),.2,.8)
        val b=DbhRawDepthSupport.assess(frame(600),GuideAxis.Col(16),.2,.8)
        assertEquals("raw_full_disagreement",a.status);assertEquals(a.status,b.status)
        assertEquals(.4,a.pairedDifferenceM!!,1e-8);assertEquals(.6,b.rawMedianM!!,1e-8)
    }
    @Test fun aSmallNumberOfOutliersDoesNotChangeTheDiagnosticMedian() {
        val a=DbhRawDepthSupport.assess(frame(fill={if(it==12*32+16)7000 else 1000}),GuideAxis.Row(12),.2,.8)
        assertEquals("raw_full_agree",a.status);assertEquals(0.0,a.pairedDifferenceM!!,0.0)
    }
    @Test fun diagnosticsCannotChangeTheProductionMeasurement() {
        val a=DBHEstimator.bracketChordEstimate(listOf(frame(null)),GuideAxis.Row(12),.2,.8,ProjectCalibration.identity)!!
        val b=DBHEstimator.bracketChordEstimate(listOf(frame(600)),GuideAxis.Row(12),.2,.8,ProjectCalibration.identity)!!
        assertEquals(a.diameterCm,b.diameterCm,0f);assertEquals(a.confidence,b.confidence)
    }
    @Test fun invalidBracketIsNotSampled() {
        assertEquals("invalid_bracket",DbhRawDepthSupport.assess(frame(),GuideAxis.Row(12),Double.NaN,.8).status)
    }
}
