package com.hcjeong.forestix.fieldstudy

import com.hcjeong.forestix.data.fieldstudy.*
import org.junit.Assert.*
import org.junit.Test

class FieldTargetProgressTest {
    private fun config()=FieldStudyConfiguration(enabled=true,studyId="SYNTHETIC",operatorId="TEST")
    private fun accepted(kind:FieldStudyKind,c:FieldStudyConfiguration=config()):FieldMeasurementRecord {
        val s=FieldMeasurementSession(0.0);val id=newFieldId()
        s.begin(id,c,FieldStudyTarget(c.studyId,"0003","003"),kind,emptyMap(),emptyList(),false,false,false,1.0)
        s.finish(id,25.0,1.0,true,null,FieldAIProvenance(),2.0);s.accept(id,id,"quick_history",3.0)
        return requireNotNull(s.current)
    }
    @Test fun completionNeedsBothAcceptedKindsInCurrentStudyAndRound(){
        val c=config();val dbh=accepted(FieldStudyKind.DBH);val height=accepted(FieldStudyKind.HEIGHT)
        assertEquals(setOf(dbh.targetId!!),FieldTargetProgress.completed(c.studyId,c.plannedRepeat,listOf(dbh,height)))
        assertTrue(FieldTargetProgress.completed(c.studyId,c.plannedRepeat+1,listOf(dbh,height)).isEmpty())
        assertTrue(FieldTargetProgress.completed("OTHER",c.plannedRepeat,listOf(dbh,height)).isEmpty())
        assertTrue(FieldTargetProgress.completed(c.studyId,c.plannedRepeat,listOf(dbh)).isEmpty())
    }
    @Test fun pendingFailedSupersededOrMissingBindingNeverHideTarget(){
        val c=config();val dbh=accepted(FieldStudyKind.DBH);val h=accepted(FieldStudyKind.HEIGHT)
        val variants=listOf(h.copy(selection=FieldSelectionStatus.PENDING),h.copy(selection=FieldSelectionStatus.SUPERSEDED),
            h.copy(outcome=FieldAttemptOutcome.FAILED),h.copy(resultRecordId=null),h.copy(liveValue=null))
        variants.forEach{assertTrue(FieldTargetProgress.completed(c.studyId,c.plannedRepeat,listOf(dbh,it)).isEmpty())}
    }
    @Test fun hideToggleAndRoundChangeOnlyFilterOverlayNotSelectionOrCatalog(){
        val c=config();val dbh=accepted(FieldStudyKind.DBH);val h=accepted(FieldStudyKind.HEIGHT)
        val a=requireNotNull(dbh.targetSnapshot);val b=FieldStudyTarget(c.studyId,"0004","004");val targets=listOf(a,b)
        val state=FieldStudyState(configuration=c,targets=targets,measurements=listOf(dbh,h),selectedTargetId=a.id)
        assertEquals(targets,state.overlayTargets)
        val hidden=state.copy(configuration=c.copy(hideCompletedTargets=true))
        assertEquals(listOf(b),hidden.overlayTargets);assertEquals(targets,hidden.activeTargets);assertEquals(a,hidden.selectedTarget)
        assertEquals(targets,hidden.copy(configuration=hidden.configuration.copy(plannedRepeat=c.plannedRepeat+1)).overlayTargets)
    }
    @Test fun displayIdentityUsesAttemptSnapshotNotLaterSelectionOrGps(){
        val record=accepted(FieldStudyKind.HEIGHT);val c=config().copy(plannedRepeat=2)
        val selected=FieldStudyTarget(c.studyId,"0004","004")
        val identity=requireNotNull(FieldScanIdentity.snapshot(c,selected,record,FieldStudyKind.DBH))
        assertEquals("0003",identity.treeId);assertEquals(record.plannedRepeat,identity.plannedRepeat)
        assertEquals(record.id,identity.measurementId);assertEquals(FieldStudyKind.HEIGHT,identity.kind)
        assertNull(FieldScanIdentity.snapshot(c,selected,record.copy(targetId=null,treeId="unassigned:${record.id}"),FieldStudyKind.HEIGHT)!!.treeId)
        assertEquals("0004",FieldScanIdentity.snapshot(c,selected,record.copy(selection=FieldSelectionStatus.DISCARDED),FieldStudyKind.HEIGHT)!!.treeId)
        assertNull(FieldScanIdentity.snapshot(c.copy(enabled=false),null,null,FieldStudyKind.DBH))
        assertFalse(FieldScanIdentity.snapshot(c.copy(enabled=false),selected,null,FieldStudyKind.DBH)!!.recordingEnabled)
    }
}
