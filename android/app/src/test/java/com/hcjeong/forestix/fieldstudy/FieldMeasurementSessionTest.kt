package com.hcjeong.forestix.fieldstudy

import com.hcjeong.forestix.data.fieldstudy.*
import kotlinx.coroutines.*
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class FieldMeasurementSessionTest{
    @Test fun recordingOffRetainsEarlierRecordWithoutMissingReplacementError()=runBlocking<Unit>{
        val root=Files.createTempDirectory("forestix-unrecorded-replacement-").toFile()
        try{
            val store=FieldStudyStore(root);withTimeout(5000){while(!store.state.value.loaded)delay(2)}
            store.saveConfiguration(config(),true)
            val capture=FieldCaptureCoordinator(store,FieldStudyKind.DBH){1.0};capture.configure(emptyMap(),"quick:3")
            val old=capture.begin(false,false);capture.finish(old,25.0,1.0,true,null,FieldAIProvenance())
            assertEquals(old,capture.recordedMeasurementId(old))
            capture.resultSaved(old,old,"quick_history");store.awaitPendingWrites()
            store.saveConfiguration(config().copy(enabled=false),true)
            val next=capture.begin(false,false);capture.finish(next,26.0,1.0,true,null,FieldAIProvenance())
            assertNull(capture.recordedMeasurementId(next))
            capture.resultSaved(next,next,"quick_history",listOf(old));store.awaitPendingWrites()
            assertNull(capture.error.value);assertEquals(FieldSelectionStatus.ACCEPTED,store.archive.measurement(old).selection)
            assertThrows(FieldStudyException::class.java){store.archive.measurement(next)}
        }finally{root.deleteRecursively()}
    }
    @Test fun knownUnassignedScopeIgnoresAnotherGlobalTarget()=runBlocking<Unit>{
        val root=Files.createTempDirectory("forestix-unassigned-scope-").toFile()
        try{
            val store=FieldStudyStore(root);withTimeout(5000){while(!store.state.value.loaded)delay(2)}
            store.saveConfiguration(config(),true)
            val a=FieldStudyTarget("SYNTHETIC","0003","003");val b=FieldStudyTarget("SYNTHETIC","0004","004")
            store.saveTargets(listOf(a,b));store.selectTarget(a.id)
            val capture=FieldCaptureCoordinator(store,FieldStudyKind.HEIGHT){1.0}
            capture.configure(emptyMap(),"quick:3",useGlobalTarget=false)
            assertFalse(capture.usesGlobalTargetSelection.value);assertNull(capture.scopedTarget.value)
            val id=capture.begin(false,false);assertNull(capture.displayRecord.value?.targetId)
            store.selectTarget(b.id);capture.selectedTargetChanged()
            assertEquals(id,capture.currentId);assertNull(capture.displayRecord.value?.targetId)
            capture.finish(id,20.0,1.0,true,null,FieldAIProvenance());capture.resultSaved(id,id,"quick_history")
            store.awaitPendingWrites()
            val saved=store.archive.measurement(id);assertEquals(FieldSelectionStatus.ACCEPTED,saved.selection)
            assertNull(saved.targetId);assertNull(saved.targetSnapshot);assertEquals(b.id,store.state.value.selectedTargetId)
        }finally{root.deleteRecursively()}
    }
    @Test fun chainedHeightUsesSavedTargetAfterTallyAdvancesAndGlobalSelectionChanges()=runBlocking<Unit>{
        val root=Files.createTempDirectory("forestix-chain-target-").toFile()
        try{
            val store=FieldStudyStore(root);withTimeout(5000){while(!store.state.value.loaded)delay(2)}
            store.saveConfiguration(config(),true)
            val a=FieldStudyTarget("SYNTHETIC","0003","003");val b=FieldStudyTarget("SYNTHETIC","0004","004")
            store.saveTargets(listOf(a,b));store.selectTarget(a.id)
            val dbh=FieldCaptureCoordinator(store,FieldStudyKind.DBH){1.0};dbh.configure(emptyMap(),"cruise:3")
            val id=dbh.begin(false,false);dbh.finish(id,25.0,1.0,true,null,FieldAIProvenance())
            val row=newFieldId();dbh.resultSaved(id,row,"cruise")
            val pinned=requireNotNull(dbh.displayRecord.value?.targetSnapshot)
            dbh.configure(emptyMap(),"cruise:4");assertNull(store.state.value.selectedTargetId);store.selectTarget(b.id)
            val height=FieldCaptureCoordinator(store,FieldStudyKind.HEIGHT){2.0}
            height.configure(emptyMap(),"cruise:3",pinned)
            val h=height.begin(false,false);assertEquals(a,height.displayRecord.value?.targetSnapshot);assertEquals(a,height.scopedTarget.value)
            height.finish(h,20.0,1.0,true,null,FieldAIProvenance());height.resultSaved(h,row,"cruise")
            store.awaitPendingWrites()
            assertEquals(a,store.archive.measurement(h).targetSnapshot);assertEquals(row,store.archive.measurement(h).resultRecordId)
            assertTrue(a.id in store.state.value.completedTargetIds);assertEquals(b.id,store.state.value.selectedTargetId)
        }finally{root.deleteRecursively()}
    }
    @Test fun invalidScopedStudyCannotExposeAnOldAcceptedRecordAsANewAttempt()=runBlocking<Unit>{
        val root=Files.createTempDirectory("forestix-invalid-scope-").toFile()
        try{
            val store=FieldStudyStore(root);withTimeout(5000){while(!store.state.value.loaded)delay(2)}
            store.saveConfiguration(config(),true)
            val a=FieldStudyTarget("SYNTHETIC","0003","003");store.saveTargets(listOf(a))
            val capture=FieldCaptureCoordinator(store,FieldStudyKind.DBH){1.0};capture.configure(emptyMap(),"quick:3",a)
            val first=capture.begin(false,false);capture.finish(first,25.0,1.0,true,null,FieldAIProvenance());capture.resultSaved(first,first,"quick_history")
            store.awaitPendingWrites();val other=config().copy(studyId="OTHER");store.saveConfiguration(other,true)
            val next=capture.begin(false,false);capture.finish(next,26.0,1.0,true,null,FieldAIProvenance())
            assertEquals(next,capture.currentId);assertNull(capture.displayRecord.value);assertNotNull(capture.error.value)
            assertNull(FieldScanIdentity.snapshot(other,a,null,FieldStudyKind.DBH)!!.treeId)
            store.awaitPendingWrites();assertEquals(FieldSelectionStatus.ACCEPTED,store.archive.measurement(first).selection)
            assertThrows(FieldStudyException::class.java){store.archive.measurement(next)}
        }finally{root.deleteRecursively()}
    }
    @Test fun nextApplicationTreeClearsInheritedSelectionButKeepsExplicitNewTarget()=runBlocking{
        val root=Files.createTempDirectory("forestix-next-target-").toFile()
        try{
            val store=FieldStudyStore(root);withTimeout(5000){while(!store.state.value.loaded)delay(2)}
            store.saveConfiguration(config(),true)
            val a=FieldStudyTarget("SYNTHETIC","0003","003");val b=FieldStudyTarget("SYNTHETIC","0004","004")
            store.saveTargets(listOf(a,b));store.selectTarget(a.id)
            val capture=FieldCaptureCoordinator(store,FieldStudyKind.DBH){1.0};capture.configure(emptyMap(),"quick:3")
            val first=capture.begin(false,false);capture.finish(first,25.0,1.0,true,null,FieldAIProvenance())
            capture.configure(emptyMap(),"quick:4")
            assertNull(store.state.value.selectedTargetId);assertNull(capture.displayRecord.value)
            store.selectTarget(b.id);capture.begin(false,false);store.selectTarget(a.id)
            capture.configure(emptyMap(),"quick:5")
            assertEquals(a.id,store.state.value.selectedTargetId);assertNull(capture.displayRecord.value)
            store.awaitPendingWrites()
        }finally{root.deleteRecursively()}
    }
    private fun config()=FieldStudyConfiguration(enabled=true,studyId="SYNTHETIC",operatorId="TEST")
    private fun begin(s:FieldMeasurementSession,id:String,time:Double,c:FieldStudyConfiguration=config()){
        s.begin(id,c,FieldStudyTarget(c.studyId,"003","003"),FieldStudyKind.DBH,mapOf("field_session_version" to "1"),emptyList(),true,false,false,time)}
    @Test fun acceptanceTimingRequiresSuccessfulSaveAndKeepsBackgroundDuration(){
        val s=FieldMeasurementSession(0.0);val id=newFieldId();begin(s,id,2.0)
        s.setForeground(false,4.0);s.setForeground(true,8.0)
        s.finish(id,25.0,1.0,true,null,FieldAIProvenance(),10.0);s.saveFailed(id,"synthetic_failure",12.0)
        assertNull(s.current!!.timing!!.acceptedElapsedSeconds)
        s.accept(id,id,"quick_history",14.0)
        val t=s.current!!.timing!!
        assertEquals(10.0,t.firstValidResultElapsedSeconds!!,0.0);assertEquals(8.0,t.attemptFirstResultSeconds!!,0.0)
        assertEquals(14.0,t.acceptedElapsedSeconds!!,0.0);assertEquals(4.0,t.backgroundSeconds,0.0)
        assertEquals(10.0,t.activeElapsedSeconds,0.0);assertEquals(12.0,t.attemptElapsedSeconds!!,0.0)
        assertEquals(8.0,t.attemptActiveSeconds!!,0.0)
        s.abandon(id,"screen_closed",15.0);assertEquals(FieldSelectionStatus.ACCEPTED,s.current!!.selection)
    }
    @Test fun retryIDsAndFailurePolicyDoNotResetScreenTimeOrInventCommonTreeIDs(){
        val s=FieldMeasurementSession(0.0);val first=newFieldId();begin(s,first,1.0)
        s.refuse(first,"depth_unavailable",2.0);assertEquals(FieldRawStatus.FAILED,s.current!!.rawStatus)
        val next=newFieldId();begin(s,next,3.0);s.finish(next,24.0,null,true,null,FieldAIProvenance(),6.0)
        s.abandon(next,"retake",7.0)
        val third=newFieldId();begin(s,third,8.0);s.finish(third,25.0,null,true,null,FieldAIProvenance(),10.0)
        assertEquals(3,s.attemptNumber);assertEquals(6.0,s.current!!.timing!!.firstValidResultElapsedSeconds!!,0.0)
        assertEquals(2.0,s.current!!.timing!!.attemptFirstResultSeconds!!,0.0)
        s.abandon(third,"retake",11.0)
        val c=config().copy(recordFailedAttempts=false);val fail=newFieldId();begin(s,fail,12.0,c)
        assertFalse(s.shouldPersistCurrent);s.refuse(fail,"no_depth",13.0);assertFalse(s.shouldPersistCurrent)
        val unassigned=newFieldId();s.begin(unassigned,c,null,FieldStudyKind.HEIGHT,mapOf("application.tree_number" to "3"),emptyList(),false,false,false,14.0)
        s.noteStageRefusal(unassigned,"top","camera_not_ready",15.0)
        assertEquals("unassigned:$unassigned",s.current!!.treeId);assertNull(s.current!!.targetId)
        assertEquals(FieldAttemptOutcome.STARTED,s.current!!.outcome)
        assertThrows(FieldStudyException::class.java){s.accept(unassigned,unassigned,"quick_history",16.0)}
    }
    @Test fun actualCoordinatorOrderedJournalPreservesRawAndExplicitResultLinks()=runBlocking{
        val root=Files.createTempDirectory("forestix-coordinator-test-").toFile()
        try{
            val store=FieldStudyStore(root);withTimeout(5000){while(!store.state.value.loaded)delay(2)}
            store.saveConfiguration(config(),true)
            val target=FieldStudyTarget("SYNTHETIC","0003","0003");store.saveTargets(listOf(target));store.selectTarget(target.id)
            var now=0.0;val capture=FieldCaptureCoordinator(store,FieldStudyKind.DBH){now}
            capture.configure(mapOf("application.tree_number" to "3"),"quick:3");capture.appeared()
            now=1.0;val id=capture.begin(true,false)
            capture.raw(id,FieldRawStatus.SAVED,replayValue=0.0);store.awaitPendingWrites()
            assertEquals(FieldRawStatus.SAVED,store.archive.measurement(id).rawStatus);assertNull(store.archive.measurement(id).replayValue)
            capture.raw(id,FieldRawStatus.SAVED,replayValue=25.01)
            now=3.0;capture.finish(id,25.0,1.0,true,null,FieldAIProvenance())
            now=4.0;capture.resultSaveFailed(id,"synthetic_failure");store.awaitPendingWrites()
            assertEquals(FieldSelectionStatus.PENDING,store.archive.measurement(id).selection)
            assertNull(store.archive.measurement(id).timing!!.acceptedElapsedSeconds)
            assertNotNull(capture.error.value) // Journal success cannot hide an application save failure.
            now=6.0;capture.resultSaved(id,id,"quick_history");store.awaitPendingWrites()
            val r=store.archive.measurement(id)
            assertEquals(FieldSelectionStatus.ACCEPTED,r.selection);assertEquals(id,r.resultRecordId)
            assertEquals("0003",r.treeId);assertEquals(target,r.targetSnapshot)
            assertEquals(FieldRawStatus.SAVED,r.rawStatus);assertEquals(25.01,r.replayValue!!,0.0)
            assertEquals(6.0,r.timing!!.acceptedElapsedSeconds!!,0.0);assertNull(capture.error.value)
            assertEquals(r,FieldStudyJson.measurement(FieldStudyJson.measurementJson(r).toString()))
            now=7.0;val newer=capture.begin(false,false,true)
            now=8.0;capture.finish(newer,26.0,null,true,null,FieldAIProvenance(reason="typed_measurement"))
            now=9.0;capture.resultSaved(newer,newer,"quick_history",listOf(id));store.awaitPendingWrites()
            assertEquals(FieldSelectionStatus.SUPERSEDED,store.archive.measurement(id).selection)
            assertEquals(25.0,store.archive.measurement(id).liveValue!!,0.0)
            assertEquals(FieldRawStatus.SAVED,store.archive.measurement(id).rawStatus)
        }finally{root.deleteRecursively()}
    }
    @Test fun targetChangeNeverRelabelsAnchoredAttemptAndNextTargetHasFreshSession()=runBlocking{
        val root=Files.createTempDirectory("forestix-target-session-").toFile()
        try{
            val store=FieldStudyStore(root);withTimeout(5000){while(!store.state.value.loaded)delay(2)}
            store.saveConfiguration(config(),true)
            val a=FieldStudyTarget("SYNTHETIC","0003","0003");val b=FieldStudyTarget("SYNTHETIC","0004","0004")
            store.saveTargets(listOf(a,b));store.selectTarget(a.id)
            var now=0.0;val capture=FieldCaptureCoordinator(store,FieldStudyKind.HEIGHT){now}
            capture.configure(emptyMap(),"synthetic");now=1.0;val first=capture.begin(false,false)
            now=2.0;store.selectTarget(b.id);capture.selectedTargetChanged()
            assertEquals(a,capture.displayRecord.value?.targetSnapshot)
            capture.stageRefused(first,"top","no_pose")
            now=3.0;capture.finish(first,20.0,0.5,true,null,FieldAIProvenance());store.awaitPendingWrites()
            val original=store.archive.measurement(first)
            assertEquals(a,original.targetSnapshot);assertEquals(first,capture.currentId)
            now=4.0;val second=capture.begin(false,false)
            now=5.0;capture.finish(second,21.0,0.5,true,null,FieldAIProvenance());store.awaitPendingWrites()
            val next=store.archive.measurement(second)
            assertEquals(b,capture.displayRecord.value?.targetSnapshot)
            assertEquals(b,next.targetSnapshot);assertEquals(1,next.attemptNumber);assertNotEquals(original.sessionId,next.sessionId)
            assertEquals(1.0,next.timing!!.screenElapsedSeconds,0.0)
            capture.selectedTargetChanged();assertEquals(second,capture.currentId) // Same target is not another session.
        }finally{root.deleteRecursively()}
    }
    @Test fun failedJournalCannotClaimSavedButDoesNotPreventMeasurementIdentity()=runBlocking{
        val root=Files.createTempFile("forestix-coordinator-unavailable-",".file").toFile()
        try{
            val store=FieldStudyStore(root);withTimeout(5000){while(!store.state.value.loaded)delay(2)}
            val capture=FieldCaptureCoordinator(store,FieldStudyKind.DBH){1.0}
            val id=capture.begin(false,false)
            assertNotNull(java.util.UUID.fromString(id));assertNotNull(capture.error.value)
            capture.finish(id,25.0,1.0,true,null,FieldAIProvenance());store.awaitPendingWrites()
            assertFalse(root.isDirectory);assertNotNull(capture.error.value)
        }finally{root.delete()}
    }
}
