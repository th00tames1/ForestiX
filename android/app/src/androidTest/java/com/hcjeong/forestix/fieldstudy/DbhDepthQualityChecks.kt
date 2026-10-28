package com.hcjeong.forestix.fieldstudy

import android.content.Context
import android.content.ContextWrapper
import com.hcjeong.forestix.sensors.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Synthetic-only actual Android JSON/filesystem/recorder round trips. A fresh
 * cache UUID redirects every write; no user DB, settings, AR or GPS is opened. */
internal object DbhDepthQualityChecks {
    fun run(base:Context)=runBlocking {
        val root=File(base.cacheDir,"dbh-quality-check-${UUID.randomUUID()}");check(root.mkdir())
        val context=object:ContextWrapper(base){override fun getFilesDir()=root}
        try {
            val n=128*96;val time=20_000_000_000L
            val raw=DbhRawDepthEvidence(128,96,ShortArray(n){1000},ByteArray(n){if(it%2==0)128.toByte()else 255.toByte()},time,time,time)
            val frame=ArDepthFrame(128,96,FloatArray(n){1.2f},ByteArray(n){2},100.0,100.0,64.0,48.0,
                FloatArray(16){if(it%5==0)1f else 0f},depthFromViewAffine=floatArrayOf(1f,0f,0f,0f,1f,0f),
                frameTimestampNanos=time,depthAgeNanos=0,rawDepthEvidence=raw)
            RawFrameClockRegistry.register(frame,time,time)
            val id=UUID.randomUUID().toString()
            val result=DBHEstimator.bracketChordEstimate(listOf(frame),GuideAxis.Row(48),.25,.75,ProjectCalibration.identity)!!
            val trace=buildJsonObject{put("version",1);put("focus_mode","SYNTHETIC");put("samples",JsonArray(emptyList()))}
            val evidence=RawCaptureEvidence(id,resultSeconds=21.0,displayedResult=RawCapturedResult(
                result.diameterCm.toDouble(),null,"yellow","synthetic",true),depthDiagnostics=trace)
            val ctx=RawCaptureStore.CaptureContext("quick",null,null,1,null)
            val out=RawCaptureStore.recordDbh(context,id,listOf(frame),64.0,48.0,true,
                RawCaptureStore.BracketSpec(32f,96f,48f),ProjectCalibration.identity,"silhouette","metric",ctx,null,captureEvidence=evidence)
            check(out.saved&&out.replayStatus=="pass"){"Synthetic raw record: $out"}
            val dir=RawCaptureStore.dirOf(context,id);val manifest=RawCaptureStore.manifestOf(context,id)!!
            val saved=RawCaptureEvidence.parse(manifest.getJSONObject("capture_evidence").toString())
            val descriptor=saved.frames.single()
            check(saved.depthDiagnostics==trace&&descriptor.depthDiagnostics["raw_comparison_status"]=="raw_full_disagreement")
            val restored=RawCaptureReplay.reconstructDbhFrames(dir,manifest).single()
            check(restored.rawDepthEvidence!!.millimeters.contentEquals(raw.millimeters))
            check(restored.rawDepthEvidence.confidence.contentEquals(raw.confidence))
            check(restored.confidence.contentEquals(frame.confidence))
            check(RawCaptureReplay.rerunDbh(dir,manifest)!!.value==result.diameterCm.toDouble())

            // Exact inputs survive, but known stale sensor clocks cannot
            // silently become a fresh legacy frame during replay.
            fun withEvidence(e:RawCaptureEvidence)=JSONObject(manifest.toString()).put("capture_evidence",JSONObject(e.json().toString()))
            val stale=withEvidence(saved.copy(frames=listOf(descriptor.copy(depthAgeNanoseconds="13318058552"))))
            check(RawCaptureReplay.reconstructDbhFrames(dir,stale).single().depthAgeNanos==13_318_058_552L)
            check(RawCaptureReplay.rerunDbh(dir,stale)==null&&RawCaptureReplay.rerunCapturedDbh(dir,stale)==null)
            val malformed=withEvidence(saved.copy(frames=listOf(descriptor.copy(depthAgeNanoseconds="broken"))))
            check(RawCaptureReplay.reconstructDbhFrames(dir,malformed).isEmpty())
            val wrong=withEvidence(saved.copy(measurementId=UUID.randomUUID().toString()))
            check(RawCaptureReplay.reconstructDbhFrames(dir,wrong).isEmpty())

            // Even with no RGB or depth available, the refusal's diagnostic
            // is saved without inventing a result or changing any user setting.
            val refusedId=UUID.randomUUID().toString()
            val refusal=RawCaptureEvidence(refusedId,resultSeconds=22.0,displayedResult=RawCapturedResult(null,null,null,null,false),
                attemptFailureReason="synthetic_depth_stale",depthDiagnostics=trace)
            val request=RawAIWriteRequest.make(false,refusal.ai,null)
            val refused=RawCaptureStore.recordRefusedAttempt(context,refusedId,ProjectCalibration.identity,"metric",ctx,refusal,request)
            check(refused.saved&&refused.frameCount==0){"Refusal trace: $refused"}
            val rm=RawCaptureStore.manifestOf(context,refusedId)!!
            val re=RawCaptureEvidence.parse(rm.getJSONObject("capture_evidence").toString())
            check(!re.displayedResult.valid&&re.displayedResult.value==null&&re.frames.isEmpty()&&re.depthDiagnostics==trace)
        } finally {check(root.deleteRecursively()){"Synthetic cache cleanup failed"}}
    }
}
