package com.hcjeong.forestix.fieldstudy

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import com.hcjeong.forestix.data.*
import com.hcjeong.forestix.data.fieldstudy.*
import com.hcjeong.forestix.sensors.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Called by the opt-in native runner. The Context redirects ALL raw files
 * into its UUID cache directory; no user measurement or settings are opened. */
internal object RawAICaptureChecks {
    suspend fun run(base:Context,root:File) {
        val context=object:ContextWrapper(base){override fun getFilesDir()=root}
        val id=UUID.randomUUID().toString();val inferenceId=UUID.randomUUID().toString()
        val ai=FieldAIProvenance(inferenceRan=true,inferenceId=inferenceId)
        val frame=ArDepthFrame(4,3,FloatArray(12){1.25f},ByteArray(12){2},1.0,1.0,0.0,0.0,
            FloatArray(16){if(it%5==0)1f else 0f},frameTimestampNanos=123250000000)
        val chw=FloatArray(3*640*640){.25f};val workspace=YoloInferenceWorkspace()
        workspace.prepareInput(chw,0)
        val source=YoloOwnedInference(inferenceId,chw,Letterbox(20,12,640),0,workspace.inputSha256(),frame,null,null,null,
            200f,300f,123.25,123.3,"synthetic-model-sha","test",2)
        val evidence=RawCaptureEvidence(id,resultSeconds=124.0,displayedResult=RawCapturedResult(null,null,null,null,false),
            ai=ai,attemptFailureReason="synthetic_no_depth")
        val ctx=RawCaptureStore.CaptureContext("quick",null,null,null,null)
        // Explicitly fail on insufficient test storage; never delete user data to make room.
        check(!RawCaptureStore.lowOnSpace(context)){"Insufficient storage for synthetic raw test"}
        val result=RawCaptureStore.recordRefusedAttempt(context,id,ProjectCalibration(),"metric",ctx,evidence,RawAIWriteRequest.make(true,ai,source))
        check(result.saved&&result.id==id&&result.frameCount==0){"Input-only raw write failed: ${result.error}"}
        val dir=File(root,"raw-captures/$id");val m=JSONObject(File(dir,"manifest.json").readText())
        val stored=RawCaptureEvidence.parse(m.getJSONObject("capture_evidence").toString())
        check(stored.measurementId==id&&stored.displayedResult.value==null&&!stored.displayedResult.valid){"Failed attempt became a numeric measurement"}
        check(stored.aiEvidenceStatus=="saved"&&stored.attemptFailureReason=="synthetic_no_depth"){"Attempt input evidence missing"}
        check(m.getJSONObject("settings").getString("capture_mode")=="attempt_only"&&m.isNull("dbh")){"Failure was mislabeled as DBH capture"}
        check(!m.getJSONObject("result_live").getBoolean("accepted")&&m.getJSONObject("truth").isNull("value")){"Failure was accepted"}
        RawAIInferenceIO.verifiedTensor(requireNotNull(stored.aiInference),dir)
        // Cancellation before Dispatchers.IO starts must release the retained input/budget.
        val cancelled=RawAIWriteRequest.make(true,ai,source)
        coroutineScope {
            launch(start=CoroutineStart.UNDISPATCHED){
                currentCoroutineContext().cancel()
                RawCaptureStore.recordDbh(context,UUID.randomUUID().toString(),emptyList(),0.0,0.0,true,null,
                    ProjectCalibration(),"silhouette","metric",ctx,null,aiRequest=cancelled)
            }.join()
        }
        val first=RawAIWriteRequest.make(true,ai,source);val second=RawAIWriteRequest.make(true,ai,source)
        try{check(first.status=="pending"&&second.status=="pending"){"Cancelled raw dispatch leaked AI budget"}}
        finally{first.cancel();second.cancel();cancelled.cancel()}
        // A failure AFTER native raw bytes reached disk must keep those
        // bytes, not tear the bundle down. No valid view affine -> explicit
        // bracket-mapping refusal, independent of estimator outputs.
        val partialId=UUID.randomUUID().toString()
        val rawFrame=ArDepthFrame(4,3,FloatArray(12){1.25f},ByteArray(12){2},1.0,1.0,0.0,0.0,
            frame.pose,rawDepthMm=ShortArray(12){1250})
        val partial=RawCaptureStore.recordDbh(context,partialId,listOf(rawFrame),1.0,1.0,true,
            RawCaptureStore.BracketSpec(1f,3f,1f),ProjectCalibration(),"silhouette","metric",ctx,null)
        check(!partial.saved&&partial.error?.contains("partial raw files retained")==true){"Partial write claimed success"}
        val partialDir=File(root,"raw-captures/$partialId")
        val originalBytes=ByteArray(24){if(it%2==0)0xe2.toByte()else 0x04}
        check(File(partialDir,"depth_0.bin").readBytes().contentEquals(originalBytes)){"Failed capture erased original raw bytes"}
        val failure=JSONObject(File(partialDir,"write_failure.json").readText())
        check(failure.getString("measurement_id")==partialId&&failure.getString("status")=="partial_write_failed")
        check(!File(partialDir,"manifest.json").exists()&&RawCaptureStore.inventory(context).unparseable>=1){"Partial capture disappeared from accounting"}
        val blockedId=UUID.randomUUID().toString();val blocked=File(root,"raw-captures/$blockedId")
        check(File(blocked,"write_failure.json").mkdirs());val payload=File(blocked,"depth_0.bin");payload.writeBytes(originalBytes)
        RawCaptureStore.preserveFailedBundle(context,blockedId,"synthetic marker failure")
        check(payload.readBytes().contentEquals(originalBytes)){"Failure-note error erased evidence"}
    }

    /** A storage/linkage stress test, not an estimator or field-accuracy
     * benchmark. One hundred explicitly synthetic inputs and result UUIDs. */
    suspend fun runBatch(base:Context,root:File){
        val batch=File(root,"batch");check(batch.mkdir())
        val context=object:ContextWrapper(base){override fun getFilesDir()=batch}
        fun open()=Room.databaseBuilder(base,ForestixDatabase::class.java,File(batch,"results.sqlite").absolutePath).build()
        var db=open();val archive=FieldStudyArchive(File(batch,"study"));val plot=UUID.randomUUID();val ids=ArrayList<String>()
        val pose=FloatArray(16){if(it%5==0)1f else 0f}
        val rgb=byteArrayOf(0xff.toByte(),0xd8.toByte(),0xff.toByte(),0xd9.toByte()) // Synthetic byte fixture.
        try{
            for(number in 1..100){
                val id=UUID.randomUUID().toString();ids.add(id);val value=20.0+number/100.0
                val config=FieldStudyConfiguration(enabled=true,studyId="BATCH_SYNTHETIC",operatorId="TEST")
                val target=FieldStudyTarget(config.studyId,"T%03d".format(number),number.toString());val session=FieldMeasurementSession(0.0)
                session.begin(id,config,target,FieldStudyKind.DBH,emptyMap(),emptyList(),true,false,false,1.0)
                archive.createMeasurement(requireNotNull(session.current))
                session.finish(id,value,1.0,true,null,FieldAIProvenance(),2.0);archive.mergeSessionSnapshot(requireNotNull(session.current))
                val frame=ArDepthFrame(4,3,FloatArray(12){1.25f},ByteArray(12){2},1.0,1.0,0.0,0.0,pose,
                    rawDepthMm=ShortArray(12){1250},frameTimestampNanos=number*1_000_000_000L)
                val image=RawCapturedImage("dbh_reference",null,4,3,number.toDouble(),null,"synthetic",List(9){if(it%4==0)1.0 else 0.0},pose.map{it.toDouble()},null)
                val evidence=RawCaptureEvidence(id,resultSeconds=2.0,displayedResult=RawCapturedResult(value,1.0,"green","synthetic-test",true))
                val raw=RawCaptureStore.recordDbh(context,id,listOf(frame),1.0,1.0,true,null,ProjectCalibration(),"silhouette","metric",
                    RawCaptureStore.CaptureContext("quick",null,plot.toString(),number,null),rgb,image,evidence)
                check(raw.saved&&raw.id==id){"Synthetic batch raw write failed at $number: ${raw.error}"}
                archive.updateRawEvidence(id,FieldRawStatus.SAVED,null,raw.replayValue)
                val reading=QuickMeasureEntry(id=UUID.fromString(id),kind=MeasureKind.DBH,value=value,sigma=1.0,confidenceRaw="green",
                    method="synthetic-test",treeNumber=number,plotID=plot)
                db.dao().saveCapturedEntry(EntryRow.from(reading),false,plot.toString(),500)
                session.accept(id,id,"quick_history",3.0);archive.mergeSessionSnapshot(requireNotNull(session.current))
            }
            db.close();db=open()
            val saved=db.dao().allEntries().associateBy{it.id};val reopened=FieldStudyArchive(archive.root)
            check(saved.size==100&&reopened.scanMeasurements().values.size==100&&RawCaptureStore.directoryCount(context)==100){"Synthetic batch count mismatch after reopen"}
            for((index,id) in ids.withIndex()){
                val journal=reopened.measurement(id);val reading=requireNotNull(saved[id]);val dir=RawCaptureStore.dirOf(context,id)
                val manifest=requireNotNull(RawCaptureStore.manifestOf(context,id))
                val evidence=RawCaptureEvidence.parse(manifest.getJSONObject("capture_evidence").toString())
                check(journal.selection==FieldSelectionStatus.ACCEPTED&&journal.resultRecordId==id&&journal.rawBundleId==id&&journal.rawStatus==FieldRawStatus.SAVED)
                check(manifest.getString("measurement_id")==id&&evidence.measurementId==id&&evidence.displayedResult.value==reading.value&&journal.liveValue==reading.value){"Synthetic batch UUID/value mismatch"}
                val restored=RawCaptureEvidenceIO.readFrame(evidence.frames.single(),dir)
                check(restored.depth.all{it.toRawBits()==1.25f.toRawBits()}&&restored.confidence.all{it.toInt()==2}){"Synthetic batch original depth/confidence changed"}
                check(evidence.images!!.single().file!!.read(dir).contentEquals(rgb)){"Synthetic batch RGB changed"}
                check(journal.treeId=="T%03d".format(index+1)&&journal.timing?.acceptedElapsedSeconds!=null){"Synthetic batch target/timing missing"}
            }
        }finally{db.close()}
    }
}
