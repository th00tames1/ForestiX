package com.hcjeong.forestix.sensors

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/** Synthetic current projections of one static circular stem. No device GT. */
class DbhDepthReadinessTest {
    private val start=100_000_000_000L
    private data class Input(val frame:ArDepthFrame,val left:Double,val right:Double){
        fun read(r:DbhDepthReadiness)=r.observe(frame,GuideAxis.Row(20),left,right)
    }
    private fun input(t:Long=0,info:Long=t,z:Double=1.2,tx:Double=0.0,ty:Double=0.0,tz:Double=0.0,
                      yaw:Double=0.0,scale:Double=1.0,score:Int=255,radius:Double=.075,
                      support:(Int)->Boolean={true}):Input {
        val w=160;val h=41;val f=180.0;val cx=80.0;val cy=20.0
        // World cylinder centre (0,0,z); camera rotates around +Y.
        val dx=-tx;val dz=z-tz;val px=cos(yaw)*dx-sin(yaw)*dz;val pz=sin(yaw)*dx+cos(yaw)*dz
        val distance=hypot(px,pz);val mid=atan2(px,pz);val half=asin(radius/distance)
        val left=cx+f*tan(mid-half);val right=cx+f*tan(mid+half)
        val pose=floatArrayOf(cos(yaw).toFloat(),0f,-sin(yaw).toFloat(),0f,0f,1f,0f,0f,
            sin(yaw).toFloat(),0f,cos(yaw).toFloat(),0f,tx.toFloat(),ty.toFloat(),tz.toFloat(),1f)
        val raw=ShortArray(w*h){p->
            val ray=(p%w-cx)/f;val a=1+ray*ray;val b=ray*px+pz;val d=b*b-a*(distance*distance-radius*radius)
            if(d<0)0 else (((b-sqrt(d))/a)*scale*1000).roundToInt().toShort()
        }
        return Input(ArDepthFrame(w,h,FloatArray(w*h){2f},ByteArray(w*h){2},f,f,cx,cy,pose,
            frameTimestampNanos=start+t,rawDepthEvidence=DbhRawDepthEvidence(w,h,raw,ByteArray(w*h){if(support(it%w))score.toByte()else 0},
                start+info,start+t,start+t),depthInputPolicy=DbhMeasurementDepth.RAW_RAY),left/w,right/w)
    }
    private fun settle(r:DbhDepthReadiness){assertFalse(input().read(r).ready);assertTrue(input(t=150_000_000).read(r).ready)}
    @Test fun actualNewInformationAndShortStableSpanUnlock(){
        val r=DbhDepthReadiness();assertEquals("waiting_new_information",input().read(r).status)
        val p=input(t=150_000_000).read(r);assertTrue(p.ready);assertTrue(p.newInformation);assertEquals(2,p.independentSamples)
    }
    @Test fun repeatedReprojectionsNeverManufactureInitialReadiness(){
        val r=DbhDepthReadiness();input().read(r)
        for(t in 1..20){val p=input(t=t*150_000_000L,info=0).read(r);assertFalse(p.ready);assertEquals(1,p.independentSamples)}
    }
    @Test fun duplicateCameraFrameDoesNotCountAsNewInformation(){
        val r=DbhDepthReadiness();input().read(r)
        val p=input(info=1).read(r);assertFalse(p.ready);assertEquals(1,p.independentSamples)
    }
    @Test fun repeatedProjectionCannotCompleteIndependentStabilitySpan(){
        val r=DbhDepthReadiness();input().read(r);assertFalse(input(t=20_000_000).read(r).ready)
        assertFalse(input(t=200_000_000,info=20_000_000).read(r).ready)
        assertTrue(input(t=300_000_000).read(r).ready)
    }
    @Test fun readinessSurvivesCurrentReprojectionWithoutNewTimestamp(){
        val r=DbhDepthReadiness();settle(r)
        for(t in 2..100){val p=input(t=t*150_000_000L,info=150_000_000).read(r);assertTrue(p.ready);assertEquals("ready_reprojected",p.status)}
    }
    @Test fun ordinarySidewaysMotionDoesNotReset(){
        val r=DbhDepthReadiness();settle(r)
        for(i in 2..15)assertTrue(input(t=i*150_000_000L,tx=.015*sin(i.toDouble())).read(r).ready)
    }
    @Test fun ordinaryVerticalMotionDoesNotReset(){
        val r=DbhDepthReadiness();settle(r)
        for(i in 2..15)assertTrue(input(t=i*150_000_000L,ty=.025*sin(i.toDouble())).read(r).ready)
    }
    @Test fun forwardMotionUsesPoseInsteadOfRawRangeDifference(){
        val r=DbhDepthReadiness();settle(r)
        for(i in 2..8)assertTrue(input(t=i*150_000_000L,tz=.025*i).read(r).ready)
    }
    @Test fun smallYawAndTranslationAreCompensated(){
        val r=DbhDepthReadiness();settle(r)
        for(i in 2..10)assertTrue(input(t=i*150_000_000L,tx=.012*sin(i.toDouble()),yaw=.015*cos(i.toDouble())).read(r).ready)
    }
    @Test fun smallUpDownPitchStillMeasuresTheSameVerticalStem(){
        val readiness=DbhDepthReadiness();settle(readiness)
        val w=160;val h=41;val focal=180.0;val range=1.2;val radius=.075
        for(i in 2..12){
            val pitch=.07*sin(i.toDouble());val c=cos(pitch);val s=sin(pitch)
            val edge=focal*c*radius/sqrt(range*range-radius*radius)
            val raw=ShortArray(w*h){p->
                val t=(p%w-80)/focal;val aa=t*t+c*c;val b=range*c
                val d=b*b-aa*(range*range-radius*radius)
                if(d<0)0 else (((b-sqrt(d))/aa)*1000).roundToInt().toShort()
            }
            val time=start+i*150_000_000L
            val pose=floatArrayOf(1f,0f,0f,0f,0f,c.toFloat(),s.toFloat(),0f,0f,-s.toFloat(),c.toFloat(),0f,0f,0f,0f,1f)
            val frame=ArDepthFrame(w,h,FloatArray(w*h){2f},ByteArray(w*h){2},focal,focal,80.0,20.0,pose,
                frameTimestampNanos=time,rawDepthEvidence=DbhRawDepthEvidence(w,h,raw,ByteArray(w*h){255.toByte()},time,time,time),
                depthInputPolicy=DbhMeasurementDepth.RAW_RAY)
            assertTrue(readiness.observe(frame,GuideAxis.Row(20),(80-edge)/w,(80+edge)/w).ready)
        }
    }
    @Test fun unexplainedDepthJumpRechecksThenRecoversWithoutPermanentLockout(){
        val r=DbhDepthReadiness();settle(r)
        val bad=input(t=300_000_000,scale=1.2).read(r);assertFalse(bad.ready);assertEquals("depth_changed",bad.status)
        assertEquals(start,bad.referenceInformationNanos);assertEquals(start,bad.referenceCameraNanos)
        assertNotNull(bad.expectedDepthM)
        assertFalse(input(t=450_000_000).read(r).ready)
        assertTrue(input(t=600_000_000).read(r).ready)
    }
    @Test fun depthChangeInSameInformationReprojectionDoesNotVote(){
        val r=DbhDepthReadiness();settle(r)
        assertFalse(input(t=300_000_000,info=150_000_000,scale=1.2).read(r).ready)
        assertFalse(input(t=600_000_000,info=150_000_000,scale=1.2).read(r).ready)
        assertTrue(input(t=750_000_000,scale=1.2).read(r).ready)
    }
    @Test fun smallDepthNoiseDoesNotRelock(){
        val r=DbhDepthReadiness();settle(r)
        for(i in 2..15)assertTrue(input(t=i*150_000_000L,scale=1+.008*sin(i.toDouble())).read(r).ready)
    }
    @Test fun changingRawSurfacePositionsOnALargeStemDoesNotPretendRangeChanged(){
        val r=DbhDepthReadiness()
        // Also include separated support so both profiles pass the SAME
        // spatial support criteria; no relaxed confidence or quorum.
        val baseline=input(radius=.3,support={it in 70..90||it in 46..48||it in 112..114})
        val edgeHeavy=input(t=150_000_000,radius=.3,support={it in 46..48||it in 112..114})
        val p=DbhMeasurementDepth.measurementProfile(baseline.frame,GuideAxis.Row(20),baseline.left*160,baseline.right*160)
        val q=DbhMeasurementDepth.measurementProfile(edgeHeavy.frame,GuideAxis.Row(20),edgeHeavy.left*160,edgeHeavy.right*160)
        assertTrue(p.qualified);assertTrue(q.qualified);assertTrue(abs(p.medianM!!-q.medianM!!)>.025)
        assertFalse(baseline.read(r).ready);assertTrue(edgeHeavy.read(r).ready)
    }
    @Test fun confidenceHoleDoesNotEraseEstablishedReadiness(){
        val r=DbhDepthReadiness();settle(r)
        assertFalse(input(t=300_000_000,score=0).read(r).ready)
        assertTrue(input(t=450_000_000).read(r).ready)
    }
    @Test fun longObservationOutageNeedsNewConfirmation(){
        val r=DbhDepthReadiness();settle(r)
        assertFalse(input(t=3_000_000_000).read(r).ready);assertTrue(input(t=3_150_000_000).read(r).ready)
    }
    @Test fun backwardInformationClockRestartsSafely(){
        val r=DbhDepthReadiness();settle(r)
        assertFalse(input(t=300_000_000,info=10).read(r).ready)
        assertTrue(input(t=450_000_000).read(r).ready)
    }
    @Test fun outOfOrderFrozenFrameDoesNotRewindLiveState(){
        val r=DbhDepthReadiness();settle(r)
        assertEquals("out_of_order",input().read(r).status);assertTrue(input(t=300_000_000).read(r).ready)
    }
    @Test fun explicitLifecycleResetClearsReadiness(){val r=DbhDepthReadiness();settle(r);r.reset();assertFalse(input(t=300_000_000).read(r).ready)}
    @Test fun coherentWrongDepthCanStillStabilizeAndIsNotClaimedAccurate(){
        val r=DbhDepthReadiness();assertFalse(input(scale=1.2).read(r).ready);assertTrue(input(t=150_000_000,scale=1.2).read(r).ready)
    }
    @Test fun annotatedFrameSharesBuffersAndMeasurementIsBitIdentical(){
        val r=DbhDepthReadiness();settle(r);val input=input(t=300_000_000)
        val original=input.frame;val a=input.read(r)
        RawFrameClockRegistry.register(original,start+290_000_000,start+295_000_000)
        val tagged=original.withDepthReadiness(a)
        assertSame(original.depth,tagged.depth);assertSame(original.confidence,tagged.confidence)
        assertSame(original.rawDepthEvidence,tagged.rawDepthEvidence);assertEquals(original.frameTimestampNanos,tagged.frameTimestampNanos)
        assertEquals(RawFrameClockRegistry.stamp(original),RawFrameClockRegistry.stamp(tagged))
        val x=DBHEstimator.bracketChordEstimate(listOf(original),GuideAxis.Row(20),input.left,input.right,ProjectCalibration.identity)!!
        val y=DBHEstimator.bracketChordEstimate(listOf(tagged),GuideAxis.Row(20),input.left,input.right,ProjectCalibration.identity)!!
        assertEquals(x,y);assertEquals("true",tagged.depthDiagnostics["readiness_ready"])
    }
    @Test fun gradualRangeDriftCannotHideBehindLastSampleComparison(){
        val r=DbhDepthReadiness();settle(r)
        assertTrue((2..20).any{!input(t=it*150_000_000L,scale=1+.004*it).read(r).ready})
    }
    @Test fun columnAndRowReadinessHaveIdenticalVotes(){
        fun transpose(c:Input):ArDepthFrame {
            val f=c.frame;val raw=f.rawDepthEvidence!!;val w=f.width;val h=f.height
            fun old(i:Int)=(i%h)*w+i/h
            return ArDepthFrame(h,w,FloatArray(w*h){f.depth[old(it)]},ByteArray(w*h){f.confidence[old(it)]},
                f.fy,f.fx,f.cy,f.cx,f.pose,frameTimestampNanos=f.frameTimestampNanos,
                rawDepthEvidence=raw.copy(width=h,height=w,millimeters=ShortArray(w*h){raw.millimeters[old(it)]},
                    confidence=ByteArray(w*h){raw.confidence[old(it)]}),depthInputPolicy=f.depthInputPolicy)
        }
        val row=DbhDepthReadiness();val col=DbhDepthReadiness()
        for(i in 0..3){val c=input(t=i*150_000_000L)
            assertEquals(c.read(row),col.observe(transpose(c),GuideAxis.Col(20),c.left,c.right))}
    }
    @Test fun unknownAndStaleInputsNeverBecomeReady(){
        val r=DbhDepthReadiness();val clean=input().frame;val raw=clean.rawDepthEvidence!!
        val stale=ArDepthFrame(clean.width,clean.height,clean.depth,clean.confidence,clean.fx,clean.fy,clean.cx,clean.cy,clean.pose,
            rawDepthEvidence=raw.copy(confidenceTimestampNanos=start-1_000_000_000),depthInputPolicy=DbhMeasurementDepth.RAW_RAY)
        assertFalse(r.observe(stale,GuideAxis.Row(20),.4,.6).ready)
        val unknown=ArDepthFrame(clean.width,clean.height,clean.depth,clean.confidence,clean.fx,clean.fy,clean.cx,clean.cy,clean.pose,
            rawDepthEvidence=raw,depthInputPolicy="unknown")
        assertEquals("unsupported_input_policy",r.observe(unknown,GuideAxis.Row(20),.4,.6).status)
    }
}
