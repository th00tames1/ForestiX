package com.hcjeong.forestix.fieldstudy

import android.content.Context
import android.content.ContextWrapper
import com.hcjeong.forestix.sensors.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Native JSON/filesystem and recorder checks; all writes use a fresh cache
 * UUID. No camera, user database, preferences, measurements or GPS is opened. */
internal object QualifiedDepthReplayChecks {
    fun run(base:Context)=runBlocking {
        val root=File(base.cacheDir,"qualified-depth-check-${UUID.randomUUID()}");check(root.mkdir())
        val context=object:ContextWrapper(base){override fun getFilesDir()=root}
        try{
            val time=20_000_000_000L;val n=128*96
            val raw=DbhRawDepthEvidence(128,96,ShortArray(n){1000},ByteArray(n){255.toByte()},
                time-5_000_000_000L,time,time)
            val f=ArDepthFrame(128,96,FloatArray(n){2f},ByteArray(n){2},100.0,100.0,64.0,48.0,
                FloatArray(16){if(it%5==0)1f else 0f},depthFromViewAffine=floatArrayOf(1f,0f,0f,0f,1f,0f),
                rawDepthMm=ShortArray(n){2000},frameTimestampNanos=time,rawDepthEvidence=raw,
                depthInputPolicy=DbhMeasurementDepth.QUALIFIED_RAW)
            RawFrameClockRegistry.register(f,time,time)
            val r=DBHEstimator.bracketChordEstimate(listOf(f),GuideAxis.Row(48),.25,.75,ProjectCalibration.identity)!!
            check(r.confidence!=ConfidenceTier.RED)
            val expected=BoundaryAlignment.sampledDiameterCm(64.0,1.0,100.0)!!
            check(kotlin.math.abs(r.diameterCm-expected)<1e-4){"Dense depth used instead of qualified Raw"}
            val id=UUID.randomUUID().toString();val ctx=RawCaptureStore.CaptureContext("quick",null,null,1,null)
            val evidence=RawCaptureEvidence(id,resultSeconds=21.0,
                displayedResult=RawCapturedResult(r.diameterCm.toDouble(),null,"yellow","synthetic",true))
            val outcome=RawCaptureStore.recordDbh(context,id,listOf(f),64.0,48.0,true,
                RawCaptureStore.BracketSpec(32f,96f,48f),ProjectCalibration.identity,"silhouette","metric",ctx,null,captureEvidence=evidence)
            check(outcome.saved&&outcome.replayStatus=="pass"){"Qualified record failed: $outcome"}
            val dir=RawCaptureStore.dirOf(context,id);val m=RawCaptureStore.manifestOf(context,id)!!
            val saved=RawCaptureEvidence.parse(m.getJSONObject("capture_evidence").toString())
            check(saved.version==2&&saved.frames.single().depthInputPolicy==DbhMeasurementDepth.QUALIFIED_RAW)
            check(m.getJSONObject("settings").getString("dbh_depth_input_policy")==DbhMeasurementDepth.QUALIFIED_RAW)
            val reconstructed=RawCaptureReplay.reconstructDbhFrames(dir,m).single()
            check(reconstructed.depthInputPolicy==DbhMeasurementDepth.QUALIFIED_RAW)
            check(reconstructed.depth.all{it==2f}&&reconstructed.rawDepthEvidence!!.millimeters.all{it.toInt()==1000})
            check(RawCaptureReplay.rerunDbh(dir,m)!!.value==r.diameterCm.toDouble())
            check(RawCaptureReplay.rerunCapturedDbh(dir,m)==r.diameterCm.toDouble())
            val noModern=JSONObject(m.toString());noModern.remove("capture_evidence")
            check(RawCaptureReplay.reconstructDbhFrames(dir,noModern).isEmpty()){"New policy silently became legacy dense input"}
            val proof=saved.frames.single().auxiliaryRawDepth!!.confidence
            val file=File(dir,proof.file);val original=file.readBytes();file.writeBytes(ByteArray(original.size))
            check(RawCaptureReplay.reconstructDbhFrames(dir,m).isEmpty()){"Corrupt Raw source fell back to dense depth"}
            file.writeBytes(original)
            val refusedId=UUID.randomUUID().toString()
            val refusal=RawCaptureEvidence(refusedId,resultSeconds=22.0,
                displayedResult=RawCapturedResult(null,null,null,null,false),attemptFailureReason="synthetic_insufficient_depth",
                scalars=mapOf("left_view_fraction" to .25,"right_view_fraction" to .75))
            val refused=RawCaptureStore.recordRefusedAttempt(context,refusedId,ProjectCalibration.identity,"metric",ctx,
                refusal,RawAIWriteRequest.make(false,refusal.ai,null),snapshot=f)
            check(refused.saved)
            val stored=RawCaptureEvidence.parse(RawCaptureStore.manifestOf(context,refusedId)!!.getJSONObject("capture_evidence").toString())
            check(stored.version==2&&!stored.displayedResult.valid&&stored.displayedResult.value==null)
            check(stored.frames.single().auxiliaryRawDepth!=null&&stored.scalars==refusal.scalars)
        }finally{check(root.deleteRecursively())}
    }
}
