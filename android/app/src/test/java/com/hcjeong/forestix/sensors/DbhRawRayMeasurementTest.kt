package com.hcjeong.forestix.sensors

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/** Ray-traced synthetic circles, not field or cup-specific fitted inputs. */
class DbhRawRayMeasurementTest {
    private val w=160;private val h=11;private val y=5;private val f=180.0;private val c=80.0
    private val radius=.075;private val range=1.2;private val angle=.12;private val time=100_000_000_000L
    private val left=c+f*tan(angle-asin(radius/range))
    private val right=c+f*tan(angle+asin(radius/range))
    private val a=ceil(left+.1*(right-left)).toInt();private val b=floor(right-.1*(right-left)).toInt()
    private val positions=setOf(a,(a+b)/2,b)
    private fun z(x:Int):Double {
        val t=(x-c)/f;val aa=1+t*t;val bb=range*(t*sin(angle)+cos(angle))
        val d=bb*bb-aa*(range*range-radius*radius)
        return if(d<0)0.0 else (bb-sqrt(d))/aa
    }
    private fun frame(policy:String=DbhMeasurementDepth.RAW_RAY,score:Int=255,raw:Boolean=true,
                      indexes:Set<Int> = positions,projectionAge:Long=0,
                      depth:((Int)->Double)?=null):ArDepthFrame = ArDepthFrame(w,h,
        FloatArray(w*h){2f},ByteArray(w*h){2},f,f,c,y.toDouble(),
        FloatArray(16){if(it%5==0)1f else 0f},
        depthFromViewAffine=floatArrayOf(1f,0f,0f,0f,1f,0f),frameTimestampNanos=time,
        rawDepthEvidence=if(raw)DbhRawDepthEvidence(w,h,
            ShortArray(w*h){((depth?.invoke(it%w)?:z(it%w))*1000).roundToInt().toShort()},
            ByteArray(w*h){if(it%w in indexes)score.toByte()else 0},time,time-projectionAge,time)else null,
        depthInputPolicy=policy)
    private fun fit(frame:ArDepthFrame)=DBHEstimator.bracketChordFit(frame,GuideAxis.Row(y),left/w,right/w)

    @Test fun sparseSupportedRaysRecoverKnownOffAxisCircleWithoutDenseRawCoverage() {
        val f=frame();val measured=fit(f)!!
        assertEquals(radius*200,measured.diameterCm,.04)
        assertNull(fit(frame(policy=DbhMeasurementDepth.QUALIFIED_RAW)))
        assertTrue(f.depth.all{it==2f})
        val live=DBHEstimator.constrainedEstimate(f,left.toFloat(),right.toFloat(),y.toFloat(),ProjectCalibration.identity)!!
        val geometry=DBHEstimator.bracketDepthGeometry(f,left.toFloat(),right.toFloat(),y.toFloat())!!
        val saved=DBHEstimator.bracketChordEstimate(listOf(f),geometry.axis,geometry.leftFraction,geometry.rightFraction,ProjectCalibration.identity)!!
        assertTrue(live.locked);assertEquals(saved.diameterCm,live.diameterCm,0f)
        assertEquals(measured.diameterCm,saved.diameterCm.toDouble(),1e-5)
    }
    @Test fun clusterOnePixelLowConfidenceMissingRawAndOldProjectionAreNotFallbacks() {
        assertNull(fit(frame(indexes=setOf((a+b)/2))))
        assertNull(fit(frame(indexes=setOf(a,a+1,a+2))))
        assertNull(fit(frame(score=127)));assertNull(fit(frame(raw=false)))
        assertNull(fit(frame(projectionAge=1_000_000_000L)))
    }
    @Test fun aCentreHoleDoesNotRejectConsistentRaysOnBothSides() {
        val measured=fit(frame(indexes=setOf(a,a+1,b-1,b)))!!
        assertEquals(radius*200,measured.diameterCm,.04)
    }
    @Test fun columnWalkUsesTransposedRawConfidenceFocalAndPrincipal() {
        val source=frame();val raw=source.rawDepthEvidence!!
        fun oldIndex(i:Int)=(i%h)*w+i/h
        val transposed=ArDepthFrame(h,w,FloatArray(w*h){source.depth[oldIndex(it)]},
            ByteArray(w*h){source.confidence[oldIndex(it)]},90.0,f,y.toDouble(),c,source.pose,
            frameTimestampNanos=time,depthInputPolicy=DbhMeasurementDepth.RAW_RAY,
            rawDepthEvidence=DbhRawDepthEvidence(h,w,
                ShortArray(w*h){raw.millimeters[oldIndex(it)]},ByteArray(w*h){raw.confidence[oldIndex(it)]},
                time,time,time))
        val row=fit(source)!!
        val column=DBHEstimator.bracketChordFit(transposed,GuideAxis.Col(y),left/w,right/w)!!
        assertEquals(row.diameterCm,column.diameterCm,0.0)
        assertEquals(row.depthM,column.depthM,0.0)
    }
    @Test fun inconsistentDiameterClustersAreRefused() {
        val all=(a..b).toSet()
        assertNull(fit(frame(indexes=all,depth={x->z(x)*(if(x<(a+b)/2)1.0 else 1.5)})))
    }
    @Test fun majorityOnlyAtCentreCannotBorrowOutlierWidthCoverage() {
        val all=(a..b).toSet()
        assertNull(fit(frame(indexes=all,depth={x->z(x)*when {
            x<a+(b-a)*.3 -> .6
            x>b-(b-a)*.3 -> 1.4
            else -> 1.0
        }})))
    }
    @Test fun mixedSourcePolicyWindowsAndUnknownPoliciesFailClosed() {
        val mixed=listOf(frame(),frame(policy=DbhMeasurementDepth.QUALIFIED_RAW))
        assertFalse(DbhDepthInputPolicy.acceptsWindow(mixed))
        assertNull(DBHEstimator.bracketChordEstimate(mixed,GuideAxis.Row(y),left/w,right/w,ProjectCalibration.identity))
        assertNull(fit(frame(policy="unsupported_future_policy")))
    }
    @Test fun consistentSensorScaleBiasIsNotClaimedToBeCorrected() {
        val clean=fit(frame())!!
        val scaled=fit(frame(depth={z(it)*1.2}))!!
        assertEquals(1.2,scaled.diameterCm/clean.diameterCm,.003)
    }
    @Test fun oldGenerationCalibrationDoesNotDoubleCorrectNewRays() {
        val f=frame();val g=DBHEstimator.bracketDepthGeometry(f,left.toFloat(),right.toFloat(),y.toFloat())!!
        val cal=ProjectCalibration(dbhCorrectionAlpha=2f,dbhCorrectionBeta=1.2f,dbhCalibrationEpoch=9)
        val clean=DBHEstimator.bracketChordEstimate(listOf(f),g.axis,g.leftFraction,g.rightFraction,ProjectCalibration.identity)!!
        val stale=DBHEstimator.bracketChordEstimate(listOf(f),g.axis,g.leftFraction,g.rightFraction,cal)!!
        val live=DBHEstimator.constrainedEstimate(f,left.toFloat(),right.toFloat(),y.toFloat(),cal)!!
        assertEquals(clean.diameterCm,stale.diameterCm,0f)
        assertEquals(clean.diameterCm,live.diameterCm,0f)
    }
    @Test fun archivedComparisonMethodsKeepEpochNineCalibrationAfterAppUpgrade() {
        val source=frame();val axis=GuideAxis.Row(y)
        val baseline=DbhDepthCandidates.evaluate(source,axis,left,right).estimates.associateBy{it.id}
        val old=ProjectCalibration(dbhCorrectionAlpha=2f,dbhCorrectionBeta=1.25f,dbhCalibrationEpoch=9)
        val replay=DbhDepthCandidates.evaluate(source,axis,left,right,old).estimates.associateBy{it.id}
        for(id in DbhDepthCandidates.IDS){
            val raw=baseline.getValue(id).rawDiameterCm?:continue
            assertEquals((2+1.25*raw).toFloat().toDouble(),replay.getValue(id).diameterCm!!,0.0)
        }
    }
}
