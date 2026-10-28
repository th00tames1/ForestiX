package com.hcjeong.forestix.sensors

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/** Synthetic circular rays only; no private field measurements or fitted GT. */
class DbhDepthCandidatesTest {
    private val time=100_000_000_000L
    private val w=160;private val h=96;private val y=48;private val f=180.0;private val principal=80.0
    private val radius=.075;private val distance=1.2;private val angle=.12
    private val left=principal+f*tan(angle-asin(radius/distance))
    private val right=principal+f*tan(angle+asin(radius/distance))
    private val a=ceil(left+.1*(right-left)).toInt();private val b=floor(right-.1*(right-left)).toInt()
    private val positions=setOf(a,(a+b)/2,b)
    private fun z(x:Int):Double {
        val t=(x-principal)/f;val aa=1+t*t;val bb=distance*(t*sin(angle)+cos(angle))
        val d=bb*bb-aa*(distance*distance-radius*radius)
        return if(d<0)0.0 else (bb-sqrt(d))/aa
    }
    private fun frame(sparse:Boolean=false,raw:Boolean=true,fullOffset:Double=.2,score:Int=255,
                      age:Long=0,at:((Int,Int)->Boolean)?=null):ArDepthFrame {
        val zs=FloatArray(w*h){(z(it%w).takeIf{d->d>0}?.plus(fullOffset)?:3.0).toFloat()}
        return ArDepthFrame(w,h,zs,ByteArray(w*h){2},f,f,principal,y.toDouble(),FloatArray(16){if(it%5==0)1f else 0f},
            frameTimestampNanos=time,depthAgeNanos=age,depthInputPolicy=DbhMeasurementDepth.QUALIFIED_RAW,
            rawDepthEvidence=if(raw)DbhRawDepthEvidence(w,h,ShortArray(w*h){(z(it%w)*1000).roundToInt().toShort()},
                ByteArray(w*h){p->if((!sparse||p%w in positions)&&(at?.invoke(p%w,p/w)?:true))score.toByte()else 0},time,time,time)else null)
    }
    private fun estimates(frame:ArDepthFrame)=DbhDepthCandidates.evaluate(frame,GuideAxis.Row(y),left,right).estimates.associateBy{it.id}

    @Test fun sparseRayRecoversKnownCircleWithoutDemandingDenseRawCoverage(){
        val e=estimates(frame(sparse=true))
        assertNull(e.getValue(DbhDepthCandidates.STRICT_RAW).diameterCm)
        val ray=e.getValue(DbhDepthCandidates.RAY_RAW)
        assertEquals("experimental_spatial_support",ray.status)
        assertEquals(radius*200,ray.diameterCm!!,.03)
        assertTrue(e.getValue(DbhDepthCandidates.DENSE).diameterCm!!>radius*200+1)
    }
    @Test fun inversionUsesActualOffCentreRayRatherThanUniformSampleMedian(){
        for(pixel in listOf(a,(a+b)/2,b))
            assertEquals(radius*200,DbhDepthCandidates.rayDiameterCm(left,right,pixel.toDouble(),z(pixel),f,principal)!!,1e-10)
        assertNull(DbhDepthCandidates.rayDiameterCm(left,right,left-1,1.0,f,principal))
        assertNull(DbhDepthCandidates.rayDiameterCm(left,right,Double.NaN,1.0,f,principal))
    }
    @Test fun pairedOffsetCancelsSameFrameAdditiveBiasNotAFittedScale(){
        val e=estimates(frame(sparse=true));val hybrid=e.getValue(DbhDepthCandidates.PAIRED_OFFSET)
        val clean=estimates(frame(fullOffset=0.0)).getValue(DbhDepthCandidates.DENSE)
        assertEquals("experimental_spatial_support",hybrid.status)
        assertEquals(-.2,hybrid.offsetM!!,.001)
        assertEquals(clean.diameterCm!!,hybrid.diameterCm!!,.02)
    }
    @Test fun noRawNeverSilentlyProducesARawCandidateFromDenseValidity(){
        val e=estimates(frame(raw=false));assertNotNull(e.getValue(DbhDepthCandidates.DENSE).diameterCm)
        for(id in DbhDepthCandidates.RAW_IDS){
            assertNull(e.getValue(id).diameterCm);assertEquals("raw_unavailable",e.getValue(id).status)
        }
    }
    @Test fun onePointCanBeExaminedButCannotMasqueradeAsSupportedEvidence(){
        val e=estimates(frame(at={x,q->x==(a+b)/2&&q==y}))
        assertNull(e.getValue(DbhDepthCandidates.STRICT_RAW).diameterCm)
        for(id in listOf(DbhDepthCandidates.RAY_RAW,DbhDepthCandidates.PAIRED_OFFSET)){
            assertNotNull(e.getValue(id).diameterCm);assertEquals("experimental_weak_support",e.getValue(id).status)
        }
    }
    @Test fun lowConfidenceAndStaleFullInputsDoNotBecomeNewMeasurements(){
        val low=estimates(frame(score=127))
        assertTrue(DbhDepthCandidates.RAW_IDS.all{low.getValue(it).diameterCm==null})
        assertTrue(estimates(frame(age=13_000_000_000L)).values.all{it.diameterCm==null&&it.status=="stale_or_unknown_input_policy"})
    }
    @Test fun rowAndRotatedColumnHaveIdenticalPhysicalResults(){
        val original=frame(sparse=true);val raw=original.rawDepthEvidence!!
        val transpose=ArDepthFrame(h,w,FloatArray(w*h){p->original.depth[(p%h)*w+p/h]},ByteArray(w*h){2},
            f,f,y.toDouble(),principal,original.pose,frameTimestampNanos=time,depthInputPolicy=original.depthInputPolicy,
            rawDepthEvidence=DbhRawDepthEvidence(h,w,ShortArray(w*h){p->raw.millimeters[(p%h)*w+p/h]},
                ByteArray(w*h){p->raw.confidence[(p%h)*w+p/h]},time,time,time))
        val row=DbhDepthCandidates.evaluate(original,GuideAxis.Row(y),left,right)
        val col=DbhDepthCandidates.evaluate(transpose,GuideAxis.Col(y),left,right)
        assertEquals(row.estimates,col.estimates)
    }
    @Test fun evaluationDoesNotMutateTheOriginalOrChangeTheDefaultEstimate(){
        val input=frame();val bits=input.depth.map{it.toRawBits()};val mm=input.rawDepthEvidence!!.millimeters.clone()
        val before=DBHEstimator.bracketChordEstimate(listOf(input),GuideAxis.Row(y),left/w,right/w,ProjectCalibration.identity)
        estimates(input)
        val after=DBHEstimator.bracketChordEstimate(listOf(input),GuideAxis.Row(y),left/w,right/w,ProjectCalibration.identity)
        assertEquals(before,after);assertEquals(bits,input.depth.map{it.toRawBits()});assertArrayEquals(mm,input.rawDepthEvidence.millimeters)
    }
    @Test fun unavailableBoundaryHasAllExplicitMissingValuesNotAnInventedBracket(){
        val r=DbhDepthCandidates.evaluate(frame(),GuideAxis.Row(y),null,null)
        assertEquals(DbhDepthCandidates.IDS,r.estimates.map{it.id})
        assertTrue(r.estimates.all{it.status=="boundary_unavailable"&&it.diameterCm==null})
    }
    @Test fun denseQuartileWorksWithoutRawButNeverClaimsRawSupportOrSensorCorrection(){
        val clean=estimates(frame(raw=false,fullOffset=0.0)).getValue(DbhDepthCandidates.DENSE_Q25)
        assertEquals(radius*200,clean.diameterCm!!,.03)
        assertEquals("experimental_dense_only",clean.status);assertEquals(0,clean.rawPixels)
        val biased=estimates(frame(raw=false,fullOffset=.5)).getValue(DbhDepthCandidates.DENSE_Q25)
        assertTrue(biased.diameterCm!!>radius*200+3)
        assertEquals("experimental_dense_only",biased.status)
    }
}
