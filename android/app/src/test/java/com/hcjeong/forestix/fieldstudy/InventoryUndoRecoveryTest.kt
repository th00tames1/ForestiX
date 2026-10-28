package com.hcjeong.forestix.fieldstudy

import com.hcjeong.forestix.data.fieldstudy.*
import com.hcjeong.forestix.data.cruise.*
import com.hcjeong.forestix.sensors.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID

/** JVM service/codec tests use an explicit value repository. Actual Room
 * persistence/reopen checks live in the opt-in native test runner. */
class InventoryUndoRecoveryTest {
    private class MemoryTrees:TreeRepository {
        val rows=HashMap<UUID,Tree>();var writes=0
        override suspend fun create(t:Tree):Tree{writes++;rows[t.id]=FieldCruiseTreeUpdate.snapshot(t);return FieldCruiseTreeUpdate.snapshot(t)}
        override suspend fun read(id:UUID,includeDeleted:Boolean)=rows[id]?.takeIf{includeDeleted||it.deletedAt==null}?.let(FieldCruiseTreeUpdate::snapshot)
        override suspend fun update(t:Tree)=create(t)
        override suspend fun replaceIfUnchanged(next:Tree,expected:Tree):Tree{
            if(rows[expected.id]!=expected||next.id!=expected.id)throw FieldStudyException("conflict","Inventory row changed")
            return create(next)
        }
        override suspend fun deleteIfUnchanged(expected:Tree){
            if(rows[expected.id]!=expected)throw FieldStudyException("conflict","Inventory row changed")
            writes++;rows.remove(expected.id)
        }
        override suspend fun delete(id:UUID,at:Long){update(requireNotNull(rows[id]).copy(deletedAt=at,updatedAt=at))}
        override suspend fun hardDelete(id:UUID){writes++;rows.remove(id)}
        override suspend fun listByPlot(plotId:UUID,includeDeleted:Boolean)=rows.values.filter{it.plotId==plotId&&(includeDeleted||it.deletedAt==null)}.map(FieldCruiseTreeUpdate::snapshot)
        override suspend fun bySpeciesInProject(projectId:UUID,speciesCode:String,includeDeleted:Boolean)=emptyList<Tree>()
        override suspend fun recentSpeciesCodes(projectId:UUID,limit:Int)=emptyList<String>()
        override fun observeByPlot(plotId:UUID):Flow<List<Tree>> = flowOf(emptyList())
    }
    private data class World(val repo:MemoryTrees,val archive:FieldStudyArchive,val original:Tree,val saved:Tree,val old:FieldMeasurementRecord,val current:FieldMeasurementRecord)
    private fun tree()=Tree(id=UUID.randomUUID(),plotId=UUID.randomUUID(),treeNumber=7,treeName="0003",speciesCode="DF",status=TreeStatus.DEAD_STANDING,
        dbhCm=30f,dbhMethod=DBHMethod.MANUAL_CALIPER,dbhSigmaMm=Float.fromBits(0x3f800001),dbhRmseMm=2f,dbhCoverageDeg=90f,dbhNInliers=60,
        dbhConfidence=ConfidenceTier.GREEN,dbhIsIrregular=true,dbhCaptureMode="typed",dbhEstimatorEpoch=7,
        heightM=21f,heightMethod=HeightMethod.MANUAL_ENTRY,heightSource="measured",heightSigmaM=0.2f,heightDHM=13.625f,heightAlphaTopDeg=50f,heightAlphaBaseDeg=-5f,
        heightConfidence=ConfidenceTier.YELLOW,bearingFromCenterDeg=25f,distanceFromCenterM=4f,boundaryCall="in",crownClass="dominant",damageCodes=listOf("a,b","x\ny","🌲"),
        isMultistem=true,parentTreeId=UUID.randomUUID(),notes="original\n\"note\"",photoPath="old.jpg",rawScanPath="old.raw",
        latitude=45.12345678901234,longitude=-123.1234567890123,createdAt=1700000000123,updatedAt=1700000000456,deletedAt=null)
    private fun record(row:UUID,value:Double,kind:FieldStudyKind=FieldStudyKind.DBH):FieldMeasurementRecord{
        val c=FieldStudyConfiguration(enabled=true,studyId="SYNTHETIC",operatorId="TEST");val s=FieldMeasurementSession(0.0);val id=newFieldId()
        val flow=FieldUIFlowClock(kind);flow.open(0.0,true)
        s.begin(id,c,FieldStudyTarget(c.studyId,"0003","003"),kind,emptyMap(),emptyList(),true,false,false,1.0)
        s.finish(id,value,1.0,true,null,FieldAIProvenance(),2.0);s.accept(id,row.toString(),"cruise",3.0);s.updateUIFlow(requireNotNull(flow.evidence(3.0,null,true,true)))
        return requireNotNull(s.current).copy(rawStatus=FieldRawStatus.SAVED)
    }
    private suspend fun world(root:File):World{
        val repo=MemoryTrees();val original=repo.create(tree());val saved=repo.replaceIfUnchanged(original.copy(dbhCm=44f,photoPath="new.jpg",rawScanPath="new.raw",updatedAt=1700000000789),original)
        val a=FieldStudyArchive(root);val old=record(saved.id,30.0);val current=record(saved.id,44.0)
        a.createMeasurement(old);a.createMeasurement(current);a.supersedeAcceptedMeasurement(old.id,current.id)
        return World(repo,a,original,saved,old,current)
    }
    @Test fun everyCrashCutRecreatesServiceAndRestoresExactTreeAndJournal()=runBlocking<Unit>{
        for(cut in 0..3){val root=Files.createTempDirectory("forestix-undo-recovery-").toFile()
            try{
                val w=world(root);val id=newFieldId();val prepared=w.archive.prepareInventoryUndo(id,w.saved,w.original,listOf(w.current.id))
                assertEquals(prepared,FieldInventoryUndoIntent.decode(prepared.encode()));assertEquals(w.saved,w.archive.scanInventoryUndoIntents().values.single().saved)
                assertEquals(13.625f,w.archive.scanInventoryUndoIntents().values.single().original?.heightDHM)
                if(cut>=1)w.repo.replaceIfUnchanged(w.original,w.saved)
                if(cut>=2)w.archive.transitionInventoryUndo(id,FieldInventoryUndoPhase.APPLICATION_APPLIED)
                if(cut>=3)w.archive.undoApplicationMeasurements(id,w.saved.id.toString(),listOf(w.current.id))
                val fresh=FieldStudyArchive(root);assertTrue(FieldInventoryUndoCoordinator(w.repo,fresh).recover().isEmpty())
                assertEquals(w.original,w.repo.readCurrent(w.saved.id,true));assertEquals(FieldSelectionStatus.ACCEPTED,fresh.measurement(w.old.id).selection)
                val undone=fresh.measurement(w.current.id);assertTrue(undone.wasApplicationUndone);assertEquals(w.current.rawBundleId,undone.rawBundleId);assertEquals(w.current.uiFlowTiming,undone.uiFlowTiming)
                assertEquals(FieldInventoryUndoPhase.COMPLETE,fresh.scanInventoryUndoIntents().values.single().phase)
            }finally{root.deleteRecursively()}}
    }
    @Test fun newRowDeletionRecoversBeforeAndAfterDeleteWithoutRewritingUnrecordedJournal()=runBlocking<Unit>{
        for(deleted in listOf(false,true)){val root=Files.createTempDirectory("forestix-undo-recovery-").toFile()
            try{val w=world(root);w.archive.prepareInventoryUndo(newFieldId(),w.saved,null,emptyList());if(deleted)w.repo.deleteIfUnchanged(w.saved)
                assertTrue(FieldInventoryUndoCoordinator(w.repo,FieldStudyArchive(root)).recover().isEmpty());assertNull(w.repo.readCurrent(w.saved.id,true))
                assertEquals(FieldSelectionStatus.ACCEPTED,w.archive.measurement(w.current.id).selection)
            }finally{root.deleteRecursively()}}
    }
    @Test fun intentWriteFailureCannotMutateApplicationOrJournal()=runBlocking<Unit>{
        val root=Files.createTempDirectory("forestix-undo-recovery-").toFile()
        try{val w=world(root);File(root,"inventory-undos").writeText("not a directory");val count=w.repo.writes
            assertTrue(runCatching{FieldInventoryUndoCoordinator(w.repo,w.archive).execute(newFieldId(),w.saved,w.original,listOf(w.current.id))}.isFailure)
            assertEquals(count,w.repo.writes);assertEquals(w.saved,w.repo.readCurrent(w.saved.id));assertEquals(w.current,w.archive.measurement(w.current.id))
        }finally{root.deleteRecursively()}
    }
    @Test fun independentEditOrTombstoneIsRefusedNotOverwritten()=runBlocking<Unit>{
        for(deleted in listOf(false,true)){val root=Files.createTempDirectory("forestix-undo-recovery-").toFile()
            try{val w=world(root);val id=newFieldId();w.archive.prepareInventoryUndo(id,w.saved,w.original,listOf(w.current.id))
                val changed=w.saved.copy(notes="independent edit",deletedAt=if(deleted)1700000000900 else null);w.repo.update(changed)
                assertFalse(FieldInventoryUndoCoordinator(w.repo,w.archive).recover().isEmpty());assertEquals(changed,w.repo.readCurrent(changed.id,true))
                val intent=w.archive.scanInventoryUndoIntents().values.single();assertEquals(FieldInventoryUndoPhase.REFUSED,intent.phase)
                assertTrue(w.current.id.lowercase() in intent.unresolvedMeasurementIds);assertEquals(FieldSelectionStatus.ACCEPTED,w.archive.measurement(w.current.id).selection)
                assertThrows(FieldStudyException::class.java){w.archive.transitionInventoryUndo(id,FieldInventoryUndoPhase.COMPLETE)}
            }finally{root.deleteRecursively()}}
    }
    @Test fun completedRetryDoesNotReapplyOrOverwriteLaterChanges()=runBlocking<Unit>{
        val root=Files.createTempDirectory("forestix-undo-recovery-").toFile()
        try{val w=world(root);val id=newFieldId();val c=FieldInventoryUndoCoordinator(w.repo,w.archive);c.execute(id,w.saved,w.original,listOf(w.current.id))
            val edited=w.original.copy(notes="after Undo");w.repo.update(edited);val count=w.repo.writes
            c.execute(id.lowercase(),w.saved,w.original,listOf(w.current.id.lowercase()));assertTrue(c.recover().isEmpty());assertEquals(count,w.repo.writes)
            assertEquals(edited,w.repo.readCurrent(edited.id));assertTrue(runCatching{c.execute(id,edited,w.original,listOf(w.current.id))}.isFailure)
        }finally{root.deleteRecursively()}
    }
    @Test fun duplicateOrCorruptIntentInventoryFailsClosedBeforeAnyMutation()=runBlocking<Unit>{
        for(corrupt in listOf(false,true)){val root=Files.createTempDirectory("forestix-undo-recovery-").toFile()
            try{val w=world(root);w.archive.prepareInventoryUndo(newFieldId(),w.saved,w.original,listOf(w.current.id))
                val extra=FieldInventoryUndoIntent(newFieldId(),w.saved,w.original,listOf(w.current.id))
                File(root,"inventory-undos/${extra.id}.json").writeText(if(corrupt)"{invalid"else extra.encode());val count=w.repo.writes
                assertFalse(FieldInventoryUndoCoordinator(w.repo,w.archive).recover().isEmpty());assertEquals(count,w.repo.writes)
                assertEquals(w.saved,w.repo.readCurrent(w.saved.id));assertEquals(FieldSelectionStatus.ACCEPTED,w.archive.measurement(w.current.id).selection)
            }finally{root.deleteRecursively()}}
    }
    @Test fun startupRecoveryCompletesBeforePublishingAdoptedResults()=runBlocking<Unit>{
        val root=Files.createTempDirectory("forestix-undo-store-").toFile()
        try{val w=world(root);val height=record(w.saved.id,21.0,FieldStudyKind.HEIGHT);w.archive.createMeasurement(height)
            val c=w.current.configuration.copy(hideCompletedTargets=true);w.archive.saveConfiguration(c,true);w.archive.saveTargets(listOf(requireNotNull(w.current.targetSnapshot)))
            w.archive.prepareInventoryUndo(newFieldId(),w.saved,w.original,listOf(w.current.id,height.id));w.repo.replaceIfUnchanged(w.original,w.saved)
            val store=FieldStudyStore(root,w.repo);withTimeout(5000){while(!store.state.value.loaded)delay(2)}
            val state=store.state.value;assertNull(state.error);assertTrue(state.issues.isEmpty());assertTrue(state.unresolvedUndoMeasurementIds.isEmpty())
            assertTrue(state.completedTargetIds.isEmpty());assertEquals(1,state.overlayTargets.size);assertTrue(store.archive.measurement(w.current.id).wasApplicationUndone)
        }finally{root.deleteRecursively()}
    }
    @Test fun refusedRecoveryLeavesTargetVisibleWithoutDisablingMeasurement()=runBlocking<Unit>{
        val root=Files.createTempDirectory("forestix-undo-store-").toFile()
        try{val w=world(root);val height=record(w.saved.id,21.0,FieldStudyKind.HEIGHT);w.archive.createMeasurement(height)
            val c=w.current.configuration.copy(hideCompletedTargets=true);w.archive.saveConfiguration(c,true);w.archive.saveTargets(listOf(requireNotNull(w.current.targetSnapshot)))
            w.archive.prepareInventoryUndo(newFieldId(),w.saved,w.original,listOf(w.current.id,height.id));w.repo.update(w.saved.copy(notes="independent edit"))
            val store=FieldStudyStore(root,w.repo);withTimeout(5000){while(!store.state.value.loaded)delay(2)}
            val state=store.state.value;assertNull(state.error);assertTrue(state.issues.isNotEmpty());assertEquals(2,state.unresolvedUndoMeasurementIds.size)
            assertTrue(state.completedTargetIds.isEmpty());assertEquals(1,state.overlayTargets.size)
            val capture=FieldCaptureCoordinator(store,FieldStudyKind.DBH,clock={10.0});capture.configure(emptyMap(),"cruise:7")
            val id=capture.begin(false,false,true);capture.finish(id,25.0,null,true,null,FieldAIProvenance());store.awaitPendingWrites()
            assertEquals(25.0,capture.displayRecord.value?.liveValue);assertNull(capture.error.value);assertEquals(FieldAttemptOutcome.RESULT,store.archive.measurement(id).outcome)
        }finally{root.deleteRecursively()}
    }
    @Test fun unreadableUndoCannotHidePotentiallyUnfinishedTargets()=runBlocking<Unit>{
        val root=Files.createTempDirectory("forestix-undo-store-").toFile()
        try{val w=world(root);val height=record(w.saved.id,21.0,FieldStudyKind.HEIGHT);w.archive.createMeasurement(height)
            w.archive.saveConfiguration(w.current.configuration.copy(hideCompletedTargets=true),true)
            w.archive.saveTargets(listOf(requireNotNull(w.current.targetSnapshot)))
            val directory=File(root,"inventory-undos");check(directory.mkdir());File(directory,newFieldId().uppercase()+".json").writeText("{invalid")
            val store=FieldStudyStore(root,w.repo);withTimeout(5000){while(!store.state.value.loaded)delay(2)}
            val state=store.state.value;assertNull(state.error);assertFalse(state.inventoryUndoInventoryVerified);assertTrue(state.issues.isNotEmpty())
            assertTrue(state.completedTargetIds.isEmpty());assertEquals(1,state.overlayTargets.size)
            assertEquals(FieldSelectionStatus.ACCEPTED,store.archive.measurement(w.current.id).selection)
        }finally{root.deleteRecursively()}
    }
}
