package com.hcjeong.forestix.fieldstudy

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import com.hcjeong.forestix.ar.Vec3
import com.hcjeong.forestix.data.*
import com.hcjeong.forestix.data.fieldstudy.*
import com.hcjeong.forestix.sensors.*
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

/** Synthetic-only actual Android recorder -> SQLite -> CSV matrix. No camera,
 * GPS, app settings or user's database is opened. Requires a connected device. */
internal object FieldRecordingMatrixChecks {
    /** Actual ZIP and FileProvider read, without launching a share destination
     * or opening the user's history. Only fresh UUID-owned cache paths. */
    fun runCompleteExport(base:Context,parent:File){
        val root=File(parent,"complete-export");check(root.mkdir())
        val exports=File(base.cacheDir,"Exports/FieldStudy/native-check-${UUID.randomUUID()}")
        check(!exports.exists()&&exports.mkdirs())
        try{
            val archive=FieldStudyArchive(File(root,"field"))
            val tape=FieldReferenceMethod("tape","Synthetic tape",FieldStudyKind.DBH,FieldReferenceUnit.INCH,FieldReferenceRole.GROUND_TRUTH)
            val laser=FieldReferenceMethod("laser","Synthetic laser",FieldStudyKind.HEIGHT,FieldReferenceUnit.FT,FieldReferenceRole.COMPARISON)
            val config=FieldStudyConfiguration(enabled=true,studyId="SYNTHETIC",operatorId="TEST",referenceMethods=listOf(tape,laser))
            archive.saveConfiguration(config,true)
            val target=FieldStudyTarget(config.studyId,"0003","3");archive.saveTargets(listOf(target))
            val refs=listOf(FieldReferenceObservation(targetId=target.id,method=tape,inputValue=10.0,operatorId="TEST"),
                FieldReferenceObservation(targetId=target.id,method=laser,inputValue=60.0,operatorId="TEST"))
            refs.forEach{archive.appendReference(it,true)}
            val raw=File(root,"raw");val id=UUID.randomUUID().toString();val bundle=File(raw,id);check(bundle.mkdirs())
            val manifest="{truncated synthetic manifest".toByteArray();val payload=byteArrayOf(1,2,3,4,5)
            File(bundle,"manifest.json").writeBytes(manifest);File(bundle,"depth_0.bin").writeBytes(payload)
            val artifact=FieldStudyExport.build(archive,raw,emptyList(),outputDirectory=exports)
            check(artifact.report.rawBundleCount==1&&artifact.report.referenceCount==2&&!artifact.report.integrityPassed)
            check(artifact.report.issues.any{it.code=="raw_manifest_corrupt"})
            ZipFile(artifact.file).use{zip->
                fun bytes(path:String)=zip.getInputStream(requireNotNull(zip.getEntry(path))).use{it.readBytes()}
                check(bytes("raw-captures/$id/manifest.json").contentEquals(manifest))
                check(bytes("raw-captures/$id/depth_0.bin").contentEquals(payload))
                val csv=bytes("reference_index.csv").toString(Charsets.UTF_8)
                refs.forEach{check(csv.contains(it.id))}
                check(csv.contains("\"in\"")&&csv.contains("\"ft\""))
                check(zip.getEntry("audit.json")!=null&&zip.getEntry("file_inventory.json")!=null)
            }
            val uri=FieldStudyExportApplication.uri(base,artifact)
            check(uri.scheme=="content"&&uri.authority=="${base.packageName}.fileprovider")
            check(base.contentResolver.getType(uri)=="application/zip")
            val shared=requireNotNull(base.contentResolver.openInputStream(uri)).use{it.readBytes()}
            check(shared.contentEquals(artifact.file.readBytes())){"Shared ZIP bytes differ from the verified file"}
            check(File(bundle,"manifest.json").readBytes().contentEquals(manifest)&&File(bundle,"depth_0.bin").readBytes().contentEquals(payload))
            check(archive.scanReferences().values.toSet()==refs.toSet()){"Export mutated original reference observations"}
        }finally{check(exports.deleteRecursively()){"Synthetic export cleanup failed"}}
    }
    suspend fun run(base:Context,parent:File){
        val root=File(parent,"recording-matrix");check(root.mkdir())
        val databaseFile=File(root,"results.sqlite")
        fun open()=Room.databaseBuilder(base,ForestixDatabase::class.java,databaseFile.absolutePath).build()
        var db=open();val expected=linkedMapOf<String,Double>()
        val pose=FloatArray(16){if(it%5==0)1f else 0f}.also{it[12]=10f;it[13]=1.5f}
        val frame=ArDepthFrame(128,96,FloatArray(128*96){1.25f},ByteArray(128*96){2},100.0,100.0,64.0,48.0,pose,
            depthFromViewAffine=floatArrayOf(1f,0f,0f,0f,1f,0f),rawDepthMm=ShortArray(128*96){1250},frameTimestampNanos=1_000_000_000L)
        try{
            for(kind in FieldStudyKind.entries)for(study in listOf(false,true))for(raw in listOf(false,true))for(timing in listOf(false,true)){
                val caseRoot=File(root,"case-${kind.raw}-$study-$raw-$timing");check(caseRoot.mkdir())
                val context=object:ContextWrapper(base){override fun getFilesDir()=caseRoot}
                val store=FieldStudyStore(File(caseRoot,"field"));withTimeout(5000){while(!store.state.value.loaded)delay(2)}
                val config=FieldStudyConfiguration(enabled=study,studyId="SYNTHETIC",operatorId="TEST",plannedRepetitions=3,plannedRepeat=2,recordTiming=timing)
                store.saveConfiguration(config,true);val target=FieldStudyTarget(config.studyId,"0003","3");store.saveTargets(listOf(target));store.selectTarget(target.id)
                var now=0.0;val registry=FieldUIFlowRegistry();registry.owner("app",true,now)
                val capture=FieldCaptureCoordinator(store,kind,registry){now};capture.configure(mapOf("application.tree_number" to "3"),"quick:3");capture.appeared()
                now=2.0;val id=capture.begin(raw,false)
                val height=HeightEstimator.estimate(0f,0f,10f,0f,.6f,-.1f)
                val value=if(kind==FieldStudyKind.DBH)requireNotNull(DBHEstimator.bracketChordEstimate(listOf(frame),GuideAxis.Row(48),.25,.75,ProjectCalibration())).diameterCm.toDouble()
                    else height.heightM.toDouble()
                check(value>0);now=4.0;capture.finish(id,value,null,true,null,FieldAIProvenance())
                if(raw){
                    val scalars=if(kind==FieldStudyKind.HEIGHT)mapOf("anchor_x" to 0.0,"anchor_z" to 0.0,"standing_x" to 10.0,"standing_z" to 0.0,
                        "alpha_top_rad" to .6f.toDouble(),"alpha_base_rad" to (-.1f).toDouble())else emptyMap()
                    val evidence=RawCaptureEvidence(id,resultSeconds=4.0,displayedResult=RawCapturedResult(value,null,"yellow","synthetic",true),scalars=scalars)
                    val ctx=RawCaptureStore.CaptureContext("quick",null,null,3,null)
                    val outcome=if(kind==FieldStudyKind.DBH)RawCaptureStore.recordDbh(context,id,listOf(frame),64.0,48.0,true,RawCaptureStore.BracketSpec(32f,96f,48f),
                        ProjectCalibration(),"silhouette","metric",ctx,null,captureEvidence=evidence)
                    else RawCaptureStore.recordHeight(context,id,Vec3(0f,0f,0f),"synthetic",10f,pose,
                        Math.toDegrees((-.1f).toDouble()).toFloat(),pose,Math.toDegrees(.6f.toDouble()).toFloat(),pose,10f,height,ProjectCalibration(),emptyList(),false,"metric",ctx,
                        RawCaptureStore.HeightAim(frame,null),RawCaptureStore.HeightAim(frame,null),evidence)
                    check(outcome.saved){"Recording matrix raw failure: ${outcome.error}"}
                    val manifest=requireNotNull(RawCaptureStore.manifestOf(context,id));val captured=RawCaptureEvidence.parse(manifest.getJSONObject("capture_evidence").toString())
                    check(manifest.getString("measurement_id")==id&&captured.measurementId==id&&captured.displayedResult.value==value&&captured.replayValue==value&&captured.replayStatus=="pass")
                    capture.raw(id,FieldRawStatus.SAVED,replayValue=captured.replayValue)
                }else check(!File(RawCaptureStore.dirOf(context,id),"manifest.json").exists())
                val reading=QuickMeasureEntry(id=UUID.fromString(id),kind=if(kind==FieldStudyKind.DBH)MeasureKind.DBH else MeasureKind.HEIGHT,
                    value=value,sigma=null,confidenceRaw="yellow",method="synthetic",treeNumber=3).identifyingMeasurement()
                capture.prepareResultSave(id,id,"quick_history",value)
                db.dao().saveCapturedEntry(EntryRow.from(reading),false,null,500)
                now=7.0;capture.resultSaved(id,id,"quick_history");store.awaitPendingWrites();check(capture.error.value==null)
                val saved=db.dao().allEntries().single{it.id==id}.toDomain()
                check(saved.id.toString()==id&&saved.measurementId==id&&saved.value==value)
                val csv=QuickMeasureExport.buildCsv(listOf(saved),emptyList()).toString(Charsets.UTF_8)
                check(csv.contains("\"measurement_id\"")&&csv.contains("\"$id\""))
                if(study){
                    val record=store.archive.measurement(id)
                    check(record.id==saved.measurementId&&record.resultRecordId==id&&record.selection==FieldSelectionStatus.ACCEPTED&&record.liveValue==value)
                    check(record.treeId=="0003"&&record.operatorId=="TEST"&&record.plannedRepeat==2&&record.kind==kind)
                    check(record.rawStatus==if(raw)FieldRawStatus.SAVED else FieldRawStatus.NOT_REQUESTED)
                    if(timing)check(record.uiFlowTiming?.atResult?.elapsedSeconds==4.0&&record.uiFlowTiming?.atAccepted?.elapsedSeconds==7.0)
                    else check(record.timing==null&&record.uiFlowTiming==null)
                }else check(capture.recordedMeasurementId(id)==null&&store.archive.scanMeasurements().values.isEmpty()&&store.archive.scanResultSaves().values.isEmpty())
                expected[id]=value
            }
            db.close();db=open()
            val reopened=db.dao().allEntries();check(reopened.size==16&&reopened.size==expected.size)
            reopened.forEach{check(it.measurementId==it.id&&it.value==expected[it.id])}
        }finally{db.close()}
    }
}
