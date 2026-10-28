package com.hcjeong.forestix.fieldstudy

import com.hcjeong.forestix.data.fieldstudy.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class FieldUIFlowCaptureTest {
    @Test fun actualRetargetKeepsOriginalFlowButIndependentTargetAndHeightTimes()=runBlocking<Unit>{
        val root=Files.createTempDirectory("forestix-flow-capture-").toFile()
        try{
            val store=FieldStudyStore(root);withTimeout(5000){while(!store.state.value.loaded)delay(2)}
            val c=FieldStudyConfiguration(enabled=true,studyId="SYNTHETIC",operatorId="TEST");store.saveConfiguration(c,true)
            val a=FieldStudyTarget(c.studyId,"0003","003");val b=FieldStudyTarget(c.studyId,"0004","004");store.saveTargets(listOf(a,b))
            val registry=FieldUIFlowRegistry();val flow=FieldUIFlowClock(FieldStudyKind.DBH);var now=0.0;registry.owner("app",true,now)
            val first=FieldCaptureCoordinator(store,FieldStudyKind.DBH,registry){now};first.configure(emptyMap(),"quick:3",a,uiFlow=flow);first.appeared()
            now=1.0;val old=first.begin(false,false,true);now=2.0;first.finish(old,25.0,null,true,null,FieldAIProvenance())
            now=3.0;first.close("target_changed");now=4.0;registry.owner("app",false,now)
            now=6.0;val next=FieldCaptureCoordinator(store,FieldStudyKind.DBH,registry){now}
            next.configure(emptyMap(),"quick:4",b,uiFlow=flow);next.appeared();next.foreground(false)
            now=8.0;registry.owner("app",true,now);next.foreground(true)
            now=9.0;val id=next.begin(false,false,true);now=11.0;next.finish(id,30.0,null,true,null,FieldAIProvenance())
            now=14.0;next.resultSaved(id,id,"quick_history");now=20.0;next.foreground(false);next.close();store.awaitPendingWrites()
            val saved=store.archive.measurement(id);val prior=store.archive.measurement(old)
            assertEquals(b,saved.targetSnapshot);assertEquals(a,prior.targetSnapshot);assertEquals(FieldSelectionStatus.DISCARDED,prior.selection)
            assertEquals(flow.id,saved.uiFlowTiming!!.id);assertEquals(flow.id,prior.uiFlowTiming!!.id)
            assertEquals(2.0,prior.uiFlowTiming!!.atResult!!.elapsedSeconds,0.0)
            assertEquals(11.0,saved.uiFlowTiming!!.atResult!!.elapsedSeconds,0.0);assertEquals(14.0,saved.uiFlowTiming!!.atAccepted!!.elapsedSeconds,0.0)
            assertEquals(10.0,saved.uiFlowTiming!!.atAccepted!!.foregroundSeconds,0.0);assertEquals(4.0,saved.uiFlowTiming!!.atAccepted!!.inactiveSeconds,0.0)
            assertEquals(5.0,saved.timing!!.firstValidResultElapsedSeconds!!,0.0);assertEquals(2.0,saved.timing!!.attemptFirstResultSeconds!!,0.0)
            assertEquals(8.0,saved.timing!!.acceptedElapsedSeconds!!,0.0)
            now=23.0;val height=FieldCaptureCoordinator(store,FieldStudyKind.HEIGHT,registry){now}
            height.configure(emptyMap(),"quick:4",b,uiFlow=flow);height.appeared()
            now=24.0;val h=height.begin(false,false,true);now=25.0;height.finish(h,20.0,null,true,null,FieldAIProvenance())
            now=26.0;height.resultSaved(h,h,"quick_history");store.awaitPendingWrites();val hr=store.archive.measurement(h)
            assertNotEquals(flow.id,hr.uiFlowTiming!!.id);assertEquals(FieldStudyKind.HEIGHT,hr.uiFlowTiming!!.kind)
            assertEquals(3.0,hr.uiFlowTiming!!.atAccepted!!.elapsedSeconds,0.0)
            assertNull(first.error.value);assertNull(next.error.value);assertNull(height.error.value)
        }finally{root.deleteRecursively()}
    }
    @Test fun disabledTimingWritesNeitherTimingField()=runBlocking<Unit>{
        val root=Files.createTempDirectory("forestix-flow-off-").toFile()
        try{
            val store=FieldStudyStore(root);withTimeout(5000){while(!store.state.value.loaded)delay(2)}
            store.saveConfiguration(FieldStudyConfiguration(enabled=true,studyId="SYNTHETIC",operatorId="TEST",recordTiming=false),true)
            val registry=FieldUIFlowRegistry();registry.owner("app",true,0.0);var now=0.0
            val capture=FieldCaptureCoordinator(store,FieldStudyKind.DBH,registry){now};capture.configure(emptyMap(),"quick:1");capture.appeared()
            now=1.0;val id=capture.begin(false,false,true);now=2.0;capture.finish(id,25.0,null,true,null,FieldAIProvenance())
            now=3.0;capture.resultSaved(id,id,"quick_history");store.awaitPendingWrites()
            val saved=store.archive.measurement(id);assertNull(saved.timing);assertNull(saved.uiFlowTiming);assertNull(capture.error.value)
        }finally{root.deleteRecursively()}
    }
}
