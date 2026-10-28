package com.hcjeong.forestix.fieldstudy

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import com.hcjeong.forestix.data.*
import com.hcjeong.forestix.data.fieldstudy.*
import java.io.File
import java.util.UUID

/** Actual SQLite retention/CAS/rollback/reopen, exclusively an owned fixture. */
internal object QuickHistoryRetentionPersistenceChecks {
    suspend fun run(context:Context,parent:File){
        val root=File(parent,"quick-retention");check(root.mkdir());val path=File(root,"quick.sqlite")
        fun open()=Room.databaseBuilder(context,ForestixDatabase::class.java,path.absolutePath).build()
        var db=open()
        try{
            val plot=UUID.randomUUID()
            val originals=(1..1001).map{n->EntryRow.from(QuickMeasureEntry(id=UUID.randomUUID(),kind=MeasureKind.DBH,value=25+n/1000.0,sigma=1.0,
                confidenceRaw="green",method="manualVisual",createdAt=1700000000000L+n,treeNumber=n,treeName="00$n",plotID=plot,
                note="SYNTHETIC,\n🌲",photoPath=if(n==1)"old.jpg"else null,truth=26.5).identifyingMeasurement())}
            db.withTransaction{originals.forEach{db.dao().upsertEntry(it)}}
            val original=originals[0];val newId=newFieldId()
            val next=original.copy(id=newId,measurementId=newId,value=30.0,createdAt=1700000010000L,truth=27.0)
            val plan=QuickCaptureReplacement.snapshots(listOf(original));val edited=original.copy(note="independent edit")
            db.dao().upsertEntry(edited)
            check(runCatching{db.dao().saveCapturedEntry(next,true,plot.toString(),3,plan)}.isFailure)
            check(db.dao().resultSaveRows(original.id).single()==edited&&db.dao().allEntries().size==1001)
            db.dao().upsertEntry(original)
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER retention_failure BEFORE INSERT ON entries WHEN NEW.id='$newId' BEGIN SELECT RAISE(ABORT,'synthetic retention failure'); END")
            check(runCatching{db.dao().saveCapturedEntry(next,true,plot.toString(),3,plan)}.isFailure)
            check(db.dao().allEntries().toSet()==originals.toSet()){"Failed native transaction changed original rows"}
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER retention_failure")
            val c=FieldStudyConfiguration(enabled=true,studyId="SYNTHETIC",operatorId="TEST")
            val field=File(root,"field");val a=FieldStudyArchive(field);a.saveConfiguration(c,true)
            val runtime=mapOf("application.mode" to "quick","application.plot_id" to plot.toString(),"application.tree_number" to "1")
            val old=FieldMeasurementRecord(id=original.measurementId!!,sessionId=newFieldId(),treeId="unassigned:${original.id}",kind=FieldStudyKind.DBH,attemptNumber=1,
                configuration=c,runtimeSettings=runtime,outcome=FieldAttemptOutcome.RESULT,selection=FieldSelectionStatus.ACCEPTED,liveValue=original.value,resultRecordId=original.id,resultStore="quick_history")
            a.createMeasurement(old)
            val record=old.copy(id=newId,sessionId=newFieldId(),treeId="unassigned:$newId",selection=FieldSelectionStatus.PENDING,liveValue=30.0,resultRecordId=null,resultStore=null)
            val expected=FieldExportApplicationResult(newId,"quick_history",FieldStudyKind.DBH,30.0,newId)
            a.prepareResultSave(FieldResultSaveIntent(newId,record,expected,replacements=plan))
            db.dao().saveCapturedEntry(next,true,plot.toString(),3,plan)
            db.close();db=open()
            val rows=db.dao().allEntries()
            check(rows.size==1001&&rows.single{it.id==newId}==next)
            originals.drop(1).forEach{oldRow->check(rows.single{it.id==oldRow.id}==oldRow)}
            val proofs=db.dao().resultSaveRowsForIds(listOf(newId,original.id)).map{FieldResultSaveIntent.quickProof(it.id,it.kind,it.value,it.measurementId)!!}
            val reopened=FieldStudyArchive(field);reopened.recoverResultSave(newId,proofs)
            check(reopened.measurement(old.id).selection==FieldSelectionStatus.SUPERSEDED)
            check(reopened.measurement(newId).selection==FieldSelectionStatus.ACCEPTED)
            val restored=reopened.scanResultSaves().values.single().replacements.single()
            check(restored==plan.single()&&restored.payloadJSON.contains("26.5"))
        }finally{db.close()}
    }
}
