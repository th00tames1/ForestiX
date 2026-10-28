package com.hcjeong.forestix.fieldstudy

import android.content.Context
import androidx.room.Room
import com.hcjeong.forestix.data.cruise.*
import com.hcjeong.forestix.data.fieldstudy.*
import com.hcjeong.forestix.sensors.*
import java.io.File
import java.util.UUID

/** Real on-disk Room close/reopen and opt-in process-death checks. All stores
 * live inside a newly owned UUID cache directory, never the user's database. */
internal object InventoryUndoRecoveryChecks {
    private data class World(val original:Tree,val saved:Tree,val old:FieldMeasurementRecord,val replacement:FieldMeasurementRecord,val operation:String)
    private fun open(context:Context,root:File)=Room.databaseBuilder(context,CruiseDatabase::class.java,File(root,"inventory.sqlite").absolutePath).build()
    private fun tree()=Tree(id=UUID.randomUUID(),plotId=UUID.randomUUID(),treeNumber=7,treeName="0003",speciesCode="DF",status=TreeStatus.DEAD_STANDING,
        dbhCm=30f,dbhMethod=DBHMethod.MANUAL_CALIPER,dbhSigmaMm=Float.fromBits(0x3f800001),dbhRmseMm=2f,dbhCoverageDeg=90f,dbhNInliers=60,
        dbhConfidence=ConfidenceTier.GREEN,dbhIsIrregular=true,dbhCaptureMode="typed",dbhEstimatorEpoch=7,
        heightM=21f,heightMethod=HeightMethod.MANUAL_ENTRY,heightSource="measured",heightSigmaM=0.2f,heightDHM=13.625f,
        heightAlphaTopDeg=50f,heightAlphaBaseDeg=-5f,heightConfidence=ConfidenceTier.YELLOW,bearingFromCenterDeg=25f,distanceFromCenterM=4f,
        boundaryCall="in",crownClass="dominant",damageCodes=listOf("a,b","x\ny","🌲"),isMultistem=true,parentTreeId=UUID.randomUUID(),
        notes="original\n\"note\"",photoPath="old.jpg",rawScanPath="old.raw",latitude=45.12345678901234,longitude=-123.1234567890123,
        createdAt=1700000000123,updatedAt=1700000000456,deletedAt=null)
    private fun record(row:UUID,value:Double):FieldMeasurementRecord{
        val s=FieldMeasurementSession(0.0);val id=newFieldId();val flow=FieldUIFlowClock(FieldStudyKind.DBH);flow.open(0.0,true)
        s.begin(id,FieldStudyConfiguration(enabled=true,studyId="SYNTHETIC",operatorId="TEST"),FieldStudyTarget("SYNTHETIC","0003","003"),
            FieldStudyKind.DBH,emptyMap(),emptyList(),true,false,false,1.0)
        s.finish(id,value,1.0,true,null,FieldAIProvenance(),2.0);s.accept(id,row.toString(),"cruise",3.0)
        s.updateUIFlow(requireNotNull(flow.evidence(3.0,null,true,true)))
        return requireNotNull(s.current).copy(rawStatus=FieldRawStatus.SAVED)
    }
    private suspend fun world(repo:TreeRepository,archive:FieldStudyArchive):World{
        val source=tree();val original=repo.createIfNumberFree(source);check(repo.readCurrent(source.id,true)==source){"Full native SQLite snapshot changed"}
        val saved=repo.replaceIfUnchanged(original.copy(dbhCm=44f,photoPath="new.jpg",rawScanPath="new.raw",updatedAt=1700000000789),original)
        val old=record(saved.id,30.0);val replacement=record(saved.id,44.0)
        archive.createMeasurement(old);archive.createMeasurement(replacement);archive.supersedeAcceptedMeasurement(old.id,replacement.id)
        return World(original,saved,old,replacement,newFieldId())
    }
    private suspend fun verify(repo:TreeRepository,archive:FieldStudyArchive,w:World){
        check(FieldInventoryUndoCoordinator(repo,archive).recover().isEmpty()){"Native recovery reported an issue"}
        check(repo.readCurrent(w.saved.id,true)==w.original){"Recovery did not restore every SQLite field"}
        check(archive.measurement(w.old.id).selection==FieldSelectionStatus.ACCEPTED){"Original adoption not restored"}
        val undone=archive.measurement(w.replacement.id)
        check(undone.wasApplicationUndone&&undone.liveValue==44.0&&undone.uiFlowTiming==w.replacement.uiFlowTiming&&undone.rawBundleId==w.replacement.rawBundleId){"Recovered evidence changed"}
        check(archive.scanInventoryUndoIntents().values.single().phase==FieldInventoryUndoPhase.COMPLETE){"Recovery did not finish durable intent"}
    }
    suspend fun run(context:Context,syntheticRoot:File){
        for(cut in 0..3){
            val root=File(syntheticRoot,"recovery-cut-$cut");check(root.mkdir())
            var db=open(context,root)
            try{
                var repo=RoomTreeRepository(db.treeDao());var archive=FieldStudyArchive(File(root,"study"));val w=world(repo,archive)
                val intent=archive.prepareInventoryUndo(w.operation,w.saved,w.original,listOf(w.replacement.id))
                check(FieldInventoryUndoIntent.decode(intent.encode())==intent){"Native snapshot round-trip changed"}
                if(cut>=1)repo.replaceIfUnchanged(w.original,w.saved)
                if(cut>=2)archive.transitionInventoryUndo(w.operation,FieldInventoryUndoPhase.APPLICATION_APPLIED)
                if(cut>=3)archive.undoApplicationMeasurements(w.operation,w.saved.id.toString(),listOf(w.replacement.id))
                db.close();db=open(context,root);repo=RoomTreeRepository(db.treeDao());archive=FieldStudyArchive(File(root,"study"))
                verify(repo,archive,w)
                val edited=repo.update(w.original.copy(notes="later independent edit"))
                FieldInventoryUndoCoordinator(repo,archive).execute(w.operation,w.saved,w.original,listOf(w.replacement.id))
                check(repo.readCurrent(edited.id,true)==edited){"Completed recovery overwrote later edit"}
            }finally{db.close()}
        }
        for(alreadyDeleted in listOf(false,true)){
            val root=File(syntheticRoot,"recovery-new-$alreadyDeleted");check(root.mkdir());var db=open(context,root)
            try{
                val repo=RoomTreeRepository(db.treeDao());val saved=repo.createIfNumberFree(tree());val archive=FieldStudyArchive(File(root,"study"))
                archive.prepareInventoryUndo(newFieldId(),saved,null,emptyList());if(alreadyDeleted)repo.deleteIfUnchanged(saved)
                db.close();db=open(context,root);val fresh=RoomTreeRepository(db.treeDao())
                check(FieldInventoryUndoCoordinator(fresh,FieldStudyArchive(archive.root)).recover().isEmpty())
                check(fresh.readCurrent(saved.id,true)==null){"New-row recovery did not delete exact synthetic row"}
            }finally{db.close()}
        }
        for(deleted in listOf(false,true)){
            val root=File(syntheticRoot,"recovery-conflict-$deleted");check(root.mkdir());var db=open(context,root)
            try{
                val repo=RoomTreeRepository(db.treeDao());val archive=FieldStudyArchive(File(root,"study"));val w=world(repo,archive)
                archive.prepareInventoryUndo(w.operation,w.saved,w.original,listOf(w.replacement.id))
                val edited=repo.update(w.saved.copy(notes="independent edit",deletedAt=if(deleted)1700000000999 else null))
                db.close();db=open(context,root);val fresh=RoomTreeRepository(db.treeDao());val journal=FieldStudyArchive(archive.root)
                check(FieldInventoryUndoCoordinator(fresh,journal).recover().isNotEmpty())
                check(fresh.readCurrent(edited.id,true)==edited){"Recovery overwrote independent SQLite edit or tombstone"}
                check(journal.measurement(w.replacement.id).selection==FieldSelectionStatus.ACCEPTED)
                check(journal.scanInventoryUndoIntents().values.single().phase==FieldInventoryUndoPhase.REFUSED)
            }finally{db.close()}
        }
    }
    private fun crashRoot(context:Context,token:String):File{
        check(UUID.fromString(token).toString()==token){"Expected a canonical UUID for synthetic crash check"}
        return File(context.cacheDir,"field-capture-crash-$token")
    }
    /** Deliberately leaves SQLite/WAL open and kills only this test process,
     * after the application CAS but before its durable phase marker. */
    suspend fun prepareAndCrash(context:Context,token:String){
        val root=crashRoot(context,token);check(!root.exists()&&root.mkdir()){"Synthetic crash directory already exists"}
        val db=open(context,root);val repo=RoomTreeRepository(db.treeDao());val archive=FieldStudyArchive(File(root,"study"));val w=world(repo,archive)
        archive.prepareInventoryUndo(w.operation,w.saved,w.original,listOf(w.replacement.id))
        repo.replaceIfUnchanged(w.original,w.saved)
        android.os.Process.killProcess(android.os.Process.myPid())
        error("Synthetic process death did not occur")
    }
    suspend fun recoverAfterCrash(context:Context,token:String){
        val root=crashRoot(context,token);check(root.isDirectory){"Synthetic crash directory missing"}
        val db=open(context,root)
        try{
            val archive=FieldStudyArchive(File(root,"study"));val intent=archive.scanInventoryUndoIntents().values.single()
            check(intent.phase==FieldInventoryUndoPhase.PREPARED){"Crash cut was not the intended window"}
            val next=archive.measurement(intent.measurementIds.single())
            val previous=archive.scanMeasurements().values.single{it.events.lastOrNull{event->event.action=="superseded"}?.detail==next.id}
            val repo=RoomTreeRepository(db.treeDao());check(repo.readCurrent(intent.saved.id,true)==intent.original){"Pre-crash CAS was not durable"}
            verify(repo,archive,World(requireNotNull(intent.original),intent.saved,previous,next,intent.id))
        }finally{db.close()}
        check(root.deleteRecursively()){"Synthetic crash artifacts could not be removed"}
    }
}
