package com.hcjeong.forestix.fieldstudy

import android.content.Context
import android.content.ContextWrapper
import com.hcjeong.forestix.sensors.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.json.JSONObject

/** Actual native recorder, original-byte replay and ZIP inclusion checks.
 * Only synthetic inputs in a fresh redirected cache UUID; no AR/user data. */
internal object DbhCandidateComparisonChecks {
    fun run(base:Context)=runBlocking {
        val root=File(base.cacheDir,"Exports/depth-candidates-${UUID.randomUUID()}");check(root.mkdirs())
        val context=object:ContextWrapper(base){override fun getFilesDir()=root;override fun getCacheDir()=root}
        try{
            val time=100_000_000_000L;val n=64*48
            val f=ArDepthFrame(64,48,FloatArray(n){1.5f},ByteArray(n){2},100.0,100.0,32.0,24.0,
                FloatArray(16){if(it%5==0)1f else 0f},depthFromViewAffine=floatArrayOf(1f,0f,0f,0f,1f,0f),
                frameTimestampNanos=time,depthInputPolicy=DbhMeasurementDepth.QUALIFIED_RAW,
                rawDepthEvidence=DbhRawDepthEvidence(64,48,ShortArray(n){1000},ByteArray(n){255.toByte()},time-5_000_000_000,time,time))
            RawFrameClockRegistry.register(f,time,time)
            val ctx=RawCaptureStore.CaptureContext("quick",null,null,1,null)
            val id=UUID.randomUUID().toString()
            val measured=DBHEstimator.bracketChordEstimate(listOf(f),GuideAxis.Row(24),.25,.75,ProjectCalibration.identity)!!
            val evidence=RawCaptureEvidence(id,resultSeconds=101.0,displayedResult=RawCapturedResult(measured.diameterCm.toDouble(),null,"yellow","synthetic",true))
            val bracket=RawCaptureStore.BracketSpec(16f,48f,24f)
            val outcome=RawCaptureStore.recordDbh(context,id,listOf(f),32.0,24.0,true,bracket,ProjectCalibration.identity,
                "silhouette","metric",ctx,null,captureEvidence=evidence)
            check(outcome.saved&&outcome.replayStatus=="pass")
            val dir=RawCaptureStore.dirOf(context,id);val manifest=RawCaptureStore.manifestOf(context,id)!!
            check(manifest.getString("dbh_depth_comparison_status")=="saved")
            check(manifest.getJSONObject("result_live").getDouble("value")==measured.diameterCm.toDouble())
            val compared=RawCaptureReplay.replayDepthComparison(dir,manifest)!!
            check(!compared.getValue("automatic_selection").jsonPrimitive.boolean)
            val rows=compared.getValue("frames").jsonArray.single().jsonObject.getValue("estimates").jsonArray.map{it.jsonObject}
            check(rows.map{it.getValue("candidate_id").jsonPrimitive.content}==DbhDepthCandidates.IDS)
            check(rows.all{!it.getValue("applied_to_result").jsonPrimitive.boolean})
            check(rows.first().getValue("diameter_cm").jsonPrimitive.double>measured.diameterCm)
            val wrong=JSONObject(manifest.toString())
            wrong.getJSONObject("capture_evidence").getJSONArray("frames").getJSONObject(0).put("role","wrong_role")
            check(runCatching{RawCaptureReplay.replayDepthComparison(dir,wrong)}.isFailure)
            val csv=File(dir,"dbh_depth_candidates.csv").readText()
            check(csv.trimEnd().lines().size==DbhDepthCandidates.IDS.size+1&&csv.contains(id))
            val refusedId=UUID.randomUUID().toString()
            val refusal=RawCaptureEvidence(refusedId,resultSeconds=102.0,displayedResult=RawCapturedResult(null,null,null,null,false),
                attemptFailureReason="synthetic_refusal")
            val refused=RawCaptureStore.recordRefusedAttempt(context,refusedId,ProjectCalibration.identity,"metric",ctx,refusal,
                RawAIWriteRequest.make(false,refusal.ai,null),f,comparisonBracket=bracket)
            check(refused.saved)
            val refusedManifest=RawCaptureStore.manifestOf(context,refusedId)!!
            val refusedReport=RawCaptureReplay.replayDepthComparison(RawCaptureStore.dirOf(context,refusedId),refusedManifest)!!
            check(refusedReport.getValue("frames").jsonArray.size==1)
            val stored=RawCaptureEvidence.parse(refusedManifest.getJSONObject("capture_evidence").toString())
            check(!stored.displayedResult.valid&&stored.displayedResult.value==null)
            // Comparison I/O failure must not erase/replace an existing primary
            // manifest or original depth. No recorder fault hooks are installed.
            val failed=File(root,"synthetic-comparison-failure");check(failed.mkdir())
            val source=RawCaptureEvidenceIO.writeFrame(f,"dbh_0",failed)
            check(File(failed,"dbh_depth_candidates.csv").mkdir())
            val primary=JSONObject().put("measurement_id",id).put("result_live",JSONObject().put("value",measured.diameterCm.toDouble()))
            RawCaptureStore.writeDepthComparison(failed,id,primary,listOf(DbhDepthComparisonIO.Input(f,source,
                DbhDepthComparisonIO.Geometry(GuideAxis.Row(24),.25,.75))),ProjectCalibration.identity)
            check(primary.getString("dbh_depth_comparison_status")=="failed")
            check(primary.getJSONObject("result_live").getDouble("value")==measured.diameterCm.toDouble())
            check(source.depth.read(failed).isNotEmpty())
            val legacyDir=File(root,"synthetic-v1-comparison");check(legacyDir.mkdir())
            val legacySource=RawCaptureEvidenceIO.writeFrame(f,"dbh_0",legacyDir)
            val legacy=DbhDepthComparisonIO.write(legacyDir,id,listOf(DbhDepthComparisonIO.Input(f,legacySource,
                DbhDepthComparisonIO.Geometry(GuideAxis.Row(24),.25,.75))),ProjectCalibration.identity,version=1)
            check(DbhDepthComparisonIO.replay(legacyDir,id,legacy).getValue("version").jsonPrimitive.int==1)
            val originalCsv=File(dir,"dbh_depth_candidates.csv").readBytes()
            File(dir,"dbh_depth_candidates.csv").appendText("corrupt")
            check(runCatching{RawCaptureReplay.replayDepthComparison(dir,manifest)}.isFailure)
            File(dir,"dbh_depth_candidates.csv").writeBytes(originalCsv)
            val export=RawCaptureStore.exportZip(context);check(export.error==null&&export.uri!=null)
            val zip=context.contentResolver.openInputStream(export.uri!!)!!
            val names=java.util.zip.ZipInputStream(zip).use{stream->
                val out=HashSet<String>();while(true){val entry=stream.nextEntry?:break;out.add(entry.name)};out}
            check(names.any{it.endsWith("$id/dbh_depth_candidates.csv")}&&names.any{it.endsWith("$refusedId/dbh_depth_candidates.json")})
        }finally{check(root.deleteRecursively())}
    }
}
