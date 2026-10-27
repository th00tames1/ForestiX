package com.hcjeong.forestix.fieldstudy

import android.content.Context
import android.content.ContextWrapper
import com.hcjeong.forestix.sensors.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.*

/** Actual recorder/JSON/replay with synthetic circle rays and a private
 * UUID cache root. No camera, GPS, user settings or user database is opened. */
internal object RawRayDepthReplayChecks {
    fun run(base:Context)=runBlocking {
        val root=File(base.cacheDir,"raw-ray-replay-${UUID.randomUUID()}");check(root.mkdir())
        val context=object:ContextWrapper(base){override fun getFilesDir()=root}
        try {
            val w=160;val h=96;val y=48;val focal=180.0;val principal=80.0
            val radius=.075;val range=1.2;val angle=.12;val time=100_000_000_000L
            val left=principal+focal*tan(angle-asin(radius/range))
            val right=principal+focal*tan(angle+asin(radius/range))
            val a=ceil(left+.1*(right-left)).toInt();val b=floor(right-.1*(right-left)).toInt()
            val indexes=setOf(a,a+1,b-1,b) // Deliberately no centre Raw samples.
            fun depth(x:Int):Int {
                val t=(x-principal)/focal;val aa=1+t*t;val bb=range*(t*sin(angle)+cos(angle))
                val disc=bb*bb-aa*(range*range-radius*radius)
                return if(disc<0)0 else (((bb-sqrt(disc))/aa)*1000).roundToInt()
            }
            val raw=DbhRawDepthEvidence(w,h,ShortArray(w*h){depth(it%w).toShort()},
                ByteArray(w*h){if(it%w in indexes)255.toByte()else 0},time-1_000_000_000L,time,time)
            val source=ArDepthFrame(w,h,FloatArray(w*h){2f},ByteArray(w*h){2},focal,focal,principal,y.toDouble(),
                FloatArray(16){if(it%5==0)1f else 0f},
                depthFromViewAffine=floatArrayOf(1f,0f,0f,0f,1f,0f),rawDepthMm=ShortArray(w*h){2000},
                frameTimestampNanos=time,rawDepthEvidence=raw,depthInputPolicy=DbhMeasurementDepth.RAW_RAY)
            RawFrameClockRegistry.register(source,time,time)
            val prior=ArDepthFrame(w,h,source.depth,source.confidence,focal,focal,principal,y.toDouble(),source.pose,
                depthFromViewAffine=source.depthFromViewAffine,
                frameTimestampNanos=time-150_000_000,rawDepthEvidence=raw.copy(timestampNanos=raw.timestampNanos-150_000_000,
                    confidenceTimestampNanos=time-150_000_000,cameraTimestampNanos=time-150_000_000),depthInputPolicy=source.depthInputPolicy)
            val readiness=DbhDepthReadiness()
            check(!readiness.observe(prior,GuideAxis.Row(y),left/w,right/w).ready)
            val decision=readiness.observe(source,GuideAxis.Row(y),left/w,right/w);check(decision.ready&&decision.newInformation)
            val f=source.withDepthReadiness(decision)
            check(f.depth===source.depth&&RawFrameClockRegistry.stamp(f)==RawFrameClockRegistry.stamp(source))
            val g=DBHEstimator.bracketDepthGeometry(f,left.toFloat(),right.toFloat(),y.toFloat())!!
            val result=DBHEstimator.bracketChordEstimate(listOf(f),g.axis,g.leftFraction,g.rightFraction,ProjectCalibration.identity)!!
            check(result.confidence!=ConfidenceTier.RED&&abs(result.diameterCm-radius*200)<.04)
            val id=UUID.randomUUID().toString()
            val ctx=RawCaptureStore.CaptureContext("quick",null,null,1,null)
            val evidence=RawCaptureEvidence(id,resultSeconds=101.0,
                displayedResult=RawCapturedResult(result.diameterCm.toDouble(),null,"yellow","synthetic",true))
            val saved=RawCaptureStore.recordDbh(context,id,listOf(f),principal,y.toDouble(),true,
                RawCaptureStore.BracketSpec(left.toFloat(),right.toFloat(),y.toFloat()),ProjectCalibration.identity,
                "silhouette","metric",ctx,null,captureEvidence=evidence)
            check(saved.saved&&saved.replayStatus=="pass"){"Raw-ray save/replay failed: $saved"}
            val dir=RawCaptureStore.dirOf(context,id);val manifest=RawCaptureStore.manifestOf(context,id)!!
            val modern=RawCaptureEvidence.parse(manifest.getJSONObject("capture_evidence").toString())
            check(modern.version==2&&modern.frames.single().depthInputPolicy==DbhMeasurementDepth.RAW_RAY)
            check(modern.frames.single().depthDiagnostics["readiness_status"]=="ready_new_information")
            check(modern.frames.single().cameraSensorId=="android_camera_ns:$time")
            check(RawCaptureReplay.rerunDbh(dir,manifest)!!.value==result.diameterCm.toDouble())
            check(RawCaptureReplay.rerunCapturedDbh(dir,manifest)==result.diameterCm.toDouble())
            val restored=RawCaptureReplay.reconstructDbhFrames(dir,manifest).single()
            check(restored.rawDepthEvidence!!.millimeters.contentEquals(raw.millimeters))
            check(restored.rawDepthEvidence!!.confidence.contentEquals(raw.confidence))
            check(restored.depth.all{it==2f})
            // The recorder adds its existing comparison diagnostics. Our
            // readiness fields must survive alongside, not replace, those.
            check(f.depthDiagnostics.all{(key,value)->restored.depthDiagnostics[key]==value})
            val noEvidence=JSONObject(manifest.toString());noEvidence.remove("capture_evidence")
            check(RawCaptureReplay.reconstructDbhFrames(dir,noEvidence).isEmpty())
            val confidenceFile=File(dir,modern.frames.single().auxiliaryRawDepth!!.confidence.file)
            val original=confidenceFile.readBytes();confidenceFile.writeBytes(ByteArray(original.size))
            check(RawCaptureReplay.reconstructDbhFrames(dir,manifest).isEmpty())
            confidenceFile.writeBytes(original)
            val refusedId=UUID.randomUUID().toString()
            val refusal=RawCaptureEvidence(refusedId,resultSeconds=102.0,
                displayedResult=RawCapturedResult(null,null,null,null,false),attemptFailureReason="synthetic_rejection")
            val refused=RawCaptureStore.recordRefusedAttempt(context,refusedId,ProjectCalibration.identity,
                "metric",ctx,refusal,RawAIWriteRequest.make(false,refusal.ai,null),
                snapshot=f.withDepthReadiness(DbhDepthReadiness.Assessment("depth_changed",false,true,1,.2,time,time)))
            check(refused.saved)
            val refusedEvidence=RawCaptureEvidence.parse(RawCaptureStore.manifestOf(context,refusedId)!!.getJSONObject("capture_evidence").toString())
            check(refusedEvidence.version==2&&refusedEvidence.frames.single().depthInputPolicy==DbhMeasurementDepth.RAW_RAY)
            check(!refusedEvidence.displayedResult.valid)
            check(refusedEvidence.frames.single().depthDiagnostics["readiness_ready"]=="false")
            check(refusedEvidence.frames.single().depthDiagnostics["readiness_status"]=="depth_changed")
        } finally { check(root.deleteRecursively()) }
    }
}
