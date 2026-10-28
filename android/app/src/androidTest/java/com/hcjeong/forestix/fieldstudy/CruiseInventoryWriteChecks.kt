package com.hcjeong.forestix.fieldstudy

import android.content.Context
import androidx.room.Room
import com.hcjeong.forestix.data.cruise.*
import com.hcjeong.forestix.sensors.*
import kotlinx.coroutines.*
import java.util.UUID
import java.io.File
import com.hcjeong.forestix.data.fieldstudy.*

/** Native SQLite transactions. Never opens the on-device inventory database. */
internal object CruiseInventoryWriteChecks {
    private fun tree(plot:UUID=UUID.randomUUID(),number:Int=7)=Tree(id=UUID.randomUUID(),plotId=plot,treeNumber=number,
        treeName="SYNTHETIC",speciesCode="DF",status=TreeStatus.LIVE,dbhCm=30f,dbhMethod=DBHMethod.MANUAL_CALIPER,
        dbhSigmaMm=null,dbhRmseMm=null,dbhCoverageDeg=null,dbhNInliers=null,dbhConfidence=ConfidenceTier.GREEN,dbhIsIrregular=false,
        heightM=21f,heightMethod=HeightMethod.MANUAL_ENTRY,heightSource="measured",heightSigmaM=null,heightDHM=null,
        heightAlphaTopDeg=null,heightAlphaBaseDeg=null,heightConfidence=ConfidenceTier.GREEN,bearingFromCenterDeg=25f,distanceFromCenterM=4f,
        boundaryCall="in",crownClass="dominant",damageCodes=listOf("OLD"),isMultistem=false,parentTreeId=null,
        notes="old",photoPath="old.jpg",rawScanPath=null,createdAt=100,updatedAt=101,deletedAt=null)
    suspend fun run(context:Context,syntheticRoot:File)=coroutineScope {
        val db=Room.inMemoryDatabaseBuilder(context,CruiseDatabase::class.java).build()
        try {
            val repo=RoomTreeRepository(db.treeDao());val old=repo.createIfNumberFree(tree())
            check(repo.createIfNumberFree(old)==old){"Conditional creation retry was not idempotent"}
            check(runCatching{repo.createIfNumberFree(old.copy(notes="different"))}.isFailure){"Existing UUID was overwritten"}
            check(runCatching{repo.createIfNumberFree(tree(old.plotId))}.isFailure){"Occupied inventory number was reused"}
            check(runCatching{repo.createIfNumberFree(tree(number=0))}.isFailure){"Invalid inventory number accepted"}
            val saved=repo.replaceIfUnchanged(old.copy(dbhCm=42f,photoPath="new.jpg"),old)
            check(repo.readCurrent(old.id)==saved){"Conditional replacement did not reach SQLite"}
            check(runCatching{repo.replaceIfUnchanged(old,old)}.isFailure){"Stale replacement overwrote inventory"}
            check(runCatching{repo.deleteIfUnchanged(old)}.isFailure){"Stale undo deleted inventory"}
            check(runCatching{repo.replaceIfUnchanged(saved.copy(plotId=UUID.randomUUID()),saved)}.isFailure){"Conditional write moved inventory"}
            check(runCatching{repo.replaceIfUnchanged(saved.copy(treeNumber=8),saved)}.isFailure){"Conditional write renumbered inventory"}
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER inventory_test_failure BEFORE INSERT ON TreeEntity WHEN NEW.notes='synthetic_failure' BEGIN SELECT RAISE(ABORT,'synthetic inventory write failure'); END")
            check(runCatching{repo.replaceIfUnchanged(saved.copy(notes="synthetic_failure"),saved)}.isFailure){"Synthetic inventory failure did not execute"}
            check(repo.readCurrent(saved.id)==saved){"Failed inventory replacement did not roll back"}
            val restored=repo.replaceIfUnchanged(old,saved)
            check(restored==old){"Undo did not restore the original full row"}
            val archive=FieldStudyArchive(File(syntheticRoot,"cruise-undo"))
            fun captured(value:Double):FieldMeasurementRecord{
                val config=FieldStudyConfiguration(enabled=true,studyId="SYNTHETIC",operatorId="TEST")
                val target=FieldStudyTarget("SYNTHETIC","0003","003");val session=FieldMeasurementSession(0.0);val id=newFieldId()
                session.begin(id,config,target,FieldStudyKind.DBH,emptyMap(),emptyList(),false,false,true,1.0)
                session.finish(id,value,null,true,null,FieldAIProvenance(),2.0);session.accept(id,old.id.toString(),"cruise",3.0)
                return requireNotNull(session.current)
            }
            val prior=captured(30.0);val replacement=captured(42.0)
            archive.createMeasurement(prior);archive.createMeasurement(replacement);archive.supersedeAcceptedMeasurement(prior.id,replacement.id)
            val operation=newFieldId();archive.validateApplicationUndo(operation,old.id.toString(),listOf(replacement.id))
            archive.undoApplicationMeasurements(operation,old.id.toString(),listOf(replacement.id))
            check(archive.measurement(replacement.id).wasApplicationUndone&&archive.measurement(prior.id).selection==FieldSelectionStatus.ACCEPTED){"Native inventory/journal Undo adoption mismatch"}
            check(repo.readCurrent(old.id)?.dbhCm==archive.measurement(prior.id).liveValue?.toFloat()){"Restored SQLite value differs from restored journal"}
            val independent=repo.update(restored.copy(notes="edited elsewhere"))
            check(runCatching{repo.replaceIfUnchanged(saved,restored)}.isFailure){"Undo overwrote an independent edit"}
            check(runCatching{repo.deleteIfUnchanged(restored)}.isFailure){"Undo deleted an independently edited row"}
            repo.deleteIfUnchanged(independent);check(repo.readCurrent(old.id)==null){"Exact new-row undo failed"}
            check(runCatching{repo.deleteIfUnchanged(independent)}.isFailure){"Missing row undo accepted"}
            val deleted=repo.createIfNumberFree(tree());repo.delete(deleted.id,200)
            check(runCatching{repo.createIfNumberFree(tree(deleted.plotId))}.isFailure){"Soft-deleted number was reused"}
            check(runCatching{repo.replaceIfUnchanged(deleted,deleted)}.isFailure){"Conditional replacement resurrected a deleted row"}
            val plot=UUID.randomUUID()
            val contenders=listOf(tree(plot),tree(plot)).map{candidate->async(Dispatchers.IO){runCatching{repo.createIfNumberFree(candidate)}}}.awaitAll()
            check(contenders.count{it.isSuccess}==1&&repo.listByPlot(plot).size==1){"Concurrent creation did not reserve one number"}
            // A real native row is replaced while the older acceptance receipt
            // is still pending. Both receipts are recovered without undoing the
            // newer value or manufacturing the missing acceptance timestamp.
            val proofArchive=FieldStudyArchive(File(syntheticRoot,"cruise-replacement-proof"))
            val original=tree(number=42).copy(dbhCm=25.12345f,dbhMeasurementId=newFieldId(),heightMeasurementId=newFieldId())
            val config=FieldStudyConfiguration(enabled=true,studyId="SYNTHETIC",operatorId="TEST")
            val fieldTarget=FieldStudyTarget(config.studyId,"00042","42")
            fun attempt(id:String,value:Double)=FieldMeasurementRecord(id=id,sessionId=newFieldId(),treeId=fieldTarget.treeId,
                targetId=fieldTarget.id,targetSnapshot=fieldTarget,kind=FieldStudyKind.DBH,attemptNumber=1,configuration=config,
                runtimeSettings=emptyMap(),outcome=FieldAttemptOutcome.RESULT,liveValue=value)
            val originalSnapshot=FieldInventoryReplacementSnapshot.from(original)
            val older=attempt(original.dbhMeasurementId!!,original.dbhCm.toDouble())
            val oldIntent=FieldResultSaveIntent(older.id,older,originalSnapshot.results[0]);proofArchive.prepareResultSave(oldIntent)
            val originalRow=repo.createIfNumberFree(original)
            val newId=newFieldId();val newer=attempt(newId,28.0)
            val expected=FieldExportApplicationResult(original.id.toString(),"cruise",FieldStudyKind.DBH,28.0,newId)
            proofArchive.prepareResultSave(FieldResultSaveIntent(newId,newer,expected,inventoryReplacement=FieldInventoryReplacementSnapshot.from(originalRow)))
            val replacementRow=repo.replaceIfUnchanged(originalRow.copy(dbhCm=28f,dbhMeasurementId=newId),originalRow)
            proofArchive.recoverResultSave(newId,listOf(expected))
            proofArchive.recoverResultSave(older.id,FieldResultSaveIntent.proofCandidates(oldIntent.expected,listOf(expected),proofArchive.completedReplacementProofs(oldIntent)))
            check(proofArchive.measurement(older.id).selection==FieldSelectionStatus.SUPERSEDED&&proofArchive.measurement(newId).selection==FieldSelectionStatus.ACCEPTED)
            check(repo.readCurrent(original.id)==replacementRow&&replacementRow.heightMeasurementId==original.heightMeasurementId)
            val savedSnapshot=proofArchive.scanResultSaves().values.single{it.id==newId}.inventoryReplacement!!
            check(FieldInventoryTreeJSON.decode(kotlinx.serialization.json.Json.parseToJsonElement(savedSnapshot.payloadJSON).let{it as kotlinx.serialization.json.JsonObject})==originalRow)
            check(proofArchive.measurement(older.id).timing?.acceptedElapsedSeconds==null)
        } finally { db.close() }
    }
}
