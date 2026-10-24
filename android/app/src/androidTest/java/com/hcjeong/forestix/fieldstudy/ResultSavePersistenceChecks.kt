package com.hcjeong.forestix.fieldstudy

import android.content.Context
import androidx.room.Room
import com.hcjeong.forestix.data.*
import com.hcjeong.forestix.data.fieldstudy.*
import java.io.File
import java.util.UUID

/** Owned synthetic on-disk Room + archive reopen cuts; never a live database. */
internal object ResultSavePersistenceChecks {
    suspend fun run(context:Context,parent:File){
        val root=File(parent,"result-save-reopen");check(root.mkdir())
        val file=File(root,"quick.sqlite");fun open()=Room.databaseBuilder(context,ForestixDatabase::class.java,file.absolutePath).build()
        var db=open()
        try{
            val archiveRoot=File(root,"field");val a=FieldStudyArchive(archiveRoot)
            val config=FieldStudyConfiguration(enabled=true,studyId="SYNTHETIC",operatorId="TEST")
            a.saveConfiguration(config,true)
            val s=FieldMeasurementSession(0.0);val id=newFieldId()
            s.begin(id,config,null,FieldStudyKind.HEIGHT,emptyMap(),emptyList(),false,false,true,1.0)
            a.createMeasurement(s.current!!)
            s.finish(id,20.0,null,true,null,FieldAIProvenance(reason="typed_measurement"),3.0)
            val entry=QuickMeasureEntry(id=UUID.fromString(id),kind=MeasureKind.HEIGHT,value=20.0,sigma=null,
                confidenceRaw="green",method="manualEntry",treeNumber=7).identifyingMeasurement()
            val intent=FieldResultSaveIntent(id,s.current!!,FieldExportApplicationResult(entry.id.toString(),"quick_history",FieldStudyKind.HEIGHT,20.0,id))
            a.prepareResultSave(intent)
            db.dao().saveCapturedEntry(EntryRow.from(entry),false,null,500)
            db.close();db=open()
            val rows=db.dao().resultSaveRows(entry.id.toString().uppercase())
            // identifyingMeasurement stores UUID.toString() (lowercase),
            // while newFieldId uses uppercase. Verify the exact native row,
            // not a different casing of the same logical measurement UUID.
            check(rows.singleOrNull()==EntryRow.from(entry)){"Native reading changed after SQLite reopen"}
            val proofs=rows.mapNotNull{FieldResultSaveIntent.quickProof(it.id,it.kind,it.value,it.measurementId)}
            val reopened=FieldStudyArchive(archiveRoot)
            check(reopened.recoverInterruptedAttempts()==0)
            reopened.recoverResultSave(id,proofs)
            val recovered=reopened.measurement(id)
            check(recovered.selection==FieldSelectionStatus.ACCEPTED&&recovered.configuration==config&&recovered.timing?.acceptedElapsedSeconds==null)
            check(reopened.scanResultSaves().values.single().completed)
            reopened.recoverResultSave(id,emptyList());check(reopened.measurement(id)==recovered)
            check(db.dao().resultSaveRows(entry.id.toString())==rows){"Recovery changed native result"}
            val other=sessionIntent(config)
            reopened.prepareResultSave(other)
            check(runCatching{reopened.recoverResultSave(other.id,emptyList())}.isFailure)
            check(runCatching{reopened.measurement(other.id)}.isFailure){"Missing native result invented acceptance"}
        }finally{db.close()}
    }
    private fun sessionIntent(c:FieldStudyConfiguration):FieldResultSaveIntent{
        val s=FieldMeasurementSession(0.0);val id=newFieldId()
        s.begin(id,c,null,FieldStudyKind.DBH,emptyMap(),emptyList(),false,false,true,1.0)
        s.finish(id,25.0,null,true,null,FieldAIProvenance(),3.0)
        return FieldResultSaveIntent(id,s.current!!,FieldExportApplicationResult(id,"quick_history",FieldStudyKind.DBH,25.0,id))
    }
}
