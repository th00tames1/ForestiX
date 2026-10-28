package com.hcjeong.forestix.fieldstudy

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import com.hcjeong.forestix.ar.Vec3
import com.hcjeong.forestix.common.TruthInput
import com.hcjeong.forestix.data.*
import com.hcjeong.forestix.data.fieldstudy.*
import com.hcjeong.forestix.sensors.*
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Synthetic height storage/identity checks, not camera UI or field accuracy.
 * Every file and SQLite database is redirected to a test-owned cache UUID. */
internal object HeightRawPersistenceChecks {
    private fun redirected(base:Context,root:File)=object:ContextWrapper(base){override fun getFilesDir()=root}
    private val pose=FloatArray(16){if(it%5==0)1f else 0f}
    private fun result()=HeightEstimator.estimate(0f,0f,10f,0f,.6f,-.1f)
    private suspend fun record(context:Context,id:String):RawCaptureStore.RecordOutcome {
        val r=result()
        val frame=ArDepthFrame(4,3,FloatArray(12){1.25f},ByteArray(12){2},1.0,1.0,0.0,0.0,pose,
            rawDepthMm=ShortArray(12){1250},frameTimestampNanos=123000000000)
        val rgb=byteArrayOf(0xff.toByte(),0xd8.toByte(),0xff.toByte(),0xd9.toByte())
        fun aim(role:String)=RawCaptureStore.HeightAim(frame,rgb,RawCapturedImage(role,null,4,3,123.0,null,"synthetic",
            List(9){if(it%4==0)1.0 else 0.0},pose.map{it.toDouble()},null))
        val evidence=RawCaptureEvidence(id,resultSeconds=124.0,
            displayedResult=RawCapturedResult(r.heightM.toDouble(),r.sigmaHm?.toDouble(),r.confidence.raw,r.method.raw,true),
            scalars=mapOf("anchor_x" to 0.0,"anchor_y" to 0.0,"anchor_z" to 0.0,
                "standing_x" to 10.0,"standing_y" to 1.5,"standing_z" to 0.0,
                "alpha_top_rad" to .6f.toDouble(),"alpha_base_rad" to (-.1f).toDouble()))
        return RawCaptureStore.recordHeight(context,id,Vec3(0f,0f,0f),"synthetic",1f,pose,
            Math.toDegrees(-.1).toFloat(),pose,Math.toDegrees(.6).toFloat(),pose,10f,r,ProjectCalibration(),emptyList(),false,
            "metric",RawCaptureStore.CaptureContext("quick",null,null,7,null),aim("base"),aim("top"),evidence)
    }
    private fun payloads(dir:File)=dir.walkTopDown().filter{it.isFile}.associate{it.relativeTo(dir).path to RawCaptureEvidenceIO.hash(it.readBytes())}
    private fun verifyRaw(context:Context,id:String){
        val dir=RawCaptureStore.dirOf(context,id);val m=requireNotNull(RawCaptureStore.manifestOf(context,id))
        check(m.getString("measurement_id")==id&&m.getString("kind")=="height")
        val e=RawCaptureEvidence.parse(m.getJSONObject("capture_evidence").toString())
        check(e.measurementId==id&&e.displayedResult.value==result().heightM.toDouble()&&e.replayStatus=="pass")
        check(e.frames.map{it.role}==listOf("base","top")&&e.images!!.map{it.role}==listOf("base","top"))
        for(f in e.frames){val input=RawCaptureEvidenceIO.readFrame(f,dir)
            check(input.depth.all{it.toRawBits()==1.25f.toRawBits()}&&input.confidence.all{it.toInt()==2})}
        for(image in e.images)check(image.file!!.read(dir).contentEquals(byteArrayOf(-1,-40,-1,-39)))
        check(m.getJSONObject("truth").getDouble("value")==19.125&&m.getJSONObject("truth").getString("truth_unit")=="m")
        check(m.getJSONObject("result_live").getBoolean("operator_accepted")&&!File(dir,"pending_patch.json").exists())
    }
    suspend fun run(base:Context,parent:File){
        val root=File(parent,"height");check(root.mkdir());val context=redirected(base,root);val id=UUID.randomUUID().toString()
        val dbFile=File(root,"results.sqlite")
        fun open()=Room.databaseBuilder(base,ForestixDatabase::class.java,dbFile.absolutePath).build()
        var db=open();val archive=FieldStudyArchive(File(root,"study"));val session=FieldMeasurementSession(0.0)
        try{
            session.begin(id,FieldStudyConfiguration(enabled=true,studyId="SYNTHETIC_HEIGHT",operatorId="TEST"),
                FieldStudyTarget("SYNTHETIC_HEIGHT","0007","7"),FieldStudyKind.HEIGHT,emptyMap(),emptyList(),true,false,false,1.0)
            archive.createMeasurement(requireNotNull(session.current))
            session.finish(id,result().heightM.toDouble(),result().sigmaHm?.toDouble(),true,null,FieldAIProvenance(),2.0)
            archive.mergeSessionSnapshot(requireNotNull(session.current))
            // Immediate Accept and GT BEFORE the recorder creates its manifest.
            check(RawCaptureStore.setTruth(context,id,19.125,TruthInput.Unit.METERS)==RawCaptureStore.TruthWrite.QUEUED)
            check(RawCaptureStore.markAccepted(context,id));check(File(RawCaptureStore.dirOf(context,id),"pending_patch.json").isFile)
            val entry=QuickMeasureEntry(id=UUID.fromString(id),kind=MeasureKind.HEIGHT,value=result().heightM.toDouble(),
                sigma=result().sigmaHm?.toDouble()?:0.0,confidenceRaw="green",method="synthetic-test",treeNumber=7)
            db.dao().saveCapturedEntry(EntryRow.from(entry),false,null,500)
            session.accept(id,id,"quick_history",3.0);archive.mergeSessionSnapshot(requireNotNull(session.current))
            val outcome=record(context,id);check(outcome.saved&&outcome.id==id&&outcome.frameCount==2){"Height raw: ${outcome.error}"}
            archive.updateRawEvidence(id,FieldRawStatus.SAVED,null,outcome.replayValue)
            // A later session/next target cannot redirect the in-flight bundle.
            val next=FieldMeasurementSession(4.0)
            next.begin(UUID.randomUUID().toString(),FieldStudyConfiguration(enabled=true,studyId="SYNTHETIC_HEIGHT",operatorId="TEST"),
                FieldStudyTarget("SYNTHETIC_HEIGHT","0008","8"),FieldStudyKind.HEIGHT,emptyMap(),emptyList(),true,false,false,5.0)
            db.close();db=open();verifyRaw(context,id)
            val row=db.dao().allEntries().single();val saved=FieldStudyArchive(archive.root).measurement(id)
            check(row.id==id&&row.value==saved.liveValue&&saved.treeId=="0007"&&saved.resultRecordId==id&&saved.rawBundleId==id)
            check(saved.selection==FieldSelectionStatus.ACCEPTED&&saved.rawStatus==FieldRawStatus.SAVED)
            val before=payloads(RawCaptureStore.dirOf(context,id));val duplicate=record(context,id)
            check(!duplicate.saved&&duplicate.error!!.contains("Existing raw capture preserved"))
            check(before==payloads(RawCaptureStore.dirOf(context,id))){"Duplicate height writer changed existing evidence"}
        }finally{db.close()}
    }
    private fun crashRoot(base:Context,token:String):File{
        check(UUID.fromString(token).toString()==token)
        return File(base.cacheDir,"field-height-operator-crash-$token")
    }
    suspend fun prepareAndCrash(base:Context,token:String){
        val root=crashRoot(base,token);check(!root.exists()&&root.mkdir());val context=redirected(base,root)
        val id=UUID.randomUUID().toString();check(record(context,id).saved)
        val dir=RawCaptureStore.dirOf(context,id);val manifest=File(dir,"manifest.json");val intermediate=File(dir,"intermediate.json")
        check(manifest.renameTo(intermediate))
        check(RawCaptureStore.setTruth(context,id,19.125,TruthInput.Unit.METERS)==RawCaptureStore.TruthWrite.QUEUED)
        check(RawCaptureStore.markAccepted(context,id));check(intermediate.renameTo(manifest))
        val pending=JSONObject(File(dir,"pending_patch.json").readText())
        val snapshot=JSONObject().put("measurement_id",id).put("entered_at",pending.getJSONObject("truth").getString("entered_at"))
            .put("files",JSONObject(payloads(dir).filterKeys{it!="manifest.json"&&it!="pending_patch.json"}))
        File(root,"expected.json").outputStream().use{it.write(snapshot.toString().toByteArray());it.flush()}
        android.os.Process.killProcess(android.os.Process.myPid())
        error("Synthetic process death did not occur")
    }
    fun recoverAfterCrash(base:Context,token:String){
        val root=crashRoot(base,token);check(root.isDirectory);val context=redirected(base,root)
        val expected=JSONObject(File(root,"expected.json").readText());val id=expected.getString("measurement_id")
        check(File(RawCaptureStore.dirOf(context,id),"pending_patch.json").isFile){"Intended crash window not present"}
        check(RawCaptureStore.recoverPendingOperatorEdits(context).isEmpty());verifyRaw(context,id)
        val m=requireNotNull(RawCaptureStore.manifestOf(context,id))
        check(m.getJSONObject("truth").getString("entered_at")==expected.getString("entered_at"))
        val originals=expected.getJSONObject("files");val after=payloads(RawCaptureStore.dirOf(context,id))
        for(name in originals.keys())check(after[name]==originals.getString(name)){"Process recovery changed original $name"}
        check(RawCaptureStore.recoverPendingOperatorEdits(context).isEmpty())
        check(root.deleteRecursively()){"Owned synthetic crash artifacts could not be removed"}
    }
}
