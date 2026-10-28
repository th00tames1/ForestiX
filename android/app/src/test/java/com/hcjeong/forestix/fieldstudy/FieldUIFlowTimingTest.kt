package com.hcjeong.forestix.fieldstudy

import com.hcjeong.forestix.data.fieldstudy.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class FieldUIFlowTimingTest {
    @Test fun lazyOriginalOpeningSurvivesRetargetAndFreezesEvents(){
        val flow=FieldUIFlowClock(FieldStudyKind.DBH);assertNull(flow.evidence(2.0,null,false,false))
        flow.open(3.0,true,"2026-01-01T00:00:00Z");flow.foreground(false,4.0);flow.open(8.0,false,"SHOULD-NOT-REPLACE")
        val midway=requireNotNull(flow.evidence(8.0,null,false,false))
        assertEquals(5.0,midway.current.elapsedSeconds,0.0);assertEquals(4.0,midway.current.inactiveSeconds,0.0)
        assertEquals("2026-01-01T00:00:00Z",midway.openedAt);flow.foreground(true,9.0)
        val result=requireNotNull(flow.evidence(12.0,midway,true,false));val saved=requireNotNull(flow.evidence(14.0,result,true,true))
        assertEquals(9.0,result.atResult!!.elapsedSeconds,0.0);assertEquals(11.0,saved.atAccepted!!.elapsedSeconds,0.0)
        assertEquals(6.0,saved.atAccepted!!.foregroundSeconds,0.0);assertEquals(5.0,saved.atAccepted!!.inactiveSeconds,0.0)
        flow.foreground(false,16.0);val later=requireNotNull(flow.evidence(20.0,saved,true,true))
        assertEquals(result.atResult,later.atResult);assertEquals(saved.atAccepted,later.atAccepted);assertEquals(17.0,later.current.elapsedSeconds,0.0)
    }
    @Test fun appOwnersAndRouteGapsNeverResetOrInventBackground(){
        val registry=FieldUIFlowRegistry();val flow=FieldUIFlowClock(FieldStudyKind.HEIGHT)
        registry.owner("old",true,0.0);registry.track(flow,0.0);registry.owner("new",true,2.0);registry.owner("old",false,3.0)
        assertEquals(0.0,flow.evidence(4.0,null,false,false)!!.current.inactiveSeconds,0.0)
        registry.owner("new",false,5.0);registry.track(flow,7.0);registry.owner("active",true,9.0)
        val t=requireNotNull(flow.evidence(10.0,null,false,false));assertEquals(10.0,t.current.elapsedSeconds,0.0)
        assertEquals(6.0,t.current.foregroundSeconds,0.0);assertEquals(4.0,t.current.inactiveSeconds,0.0)
    }
    @Test fun invalidClockAndMalformedEvidenceCannotClaimValidTiming(){
        val flow=FieldUIFlowClock(FieldStudyKind.DBH);flow.open(0.0,true)
        val first=requireNotNull(flow.evidence(8.0,null,true,false));val backwards=requireNotNull(flow.evidence(3.0,first,true,false))
        assertFalse(backwards.current.clockValid);assertEquals(8.0,backwards.current.elapsedSeconds,0.0)
        assertFalse(first.copy(id=newFieldId()).continues(first))
        assertThrows(FieldStudyException::class.java){first.copy(current=first.current.copy(foregroundSeconds=9.0)).validate()}
        assertThrows(FieldStudyException::class.java){first.copy(atResult=first.atResult!!.copy(elapsedSeconds=9.0)).validate()}
        assertThrows(FieldStudyException::class.java){first.copy(schema=99).validate()}
        assertFalse(backwards.copy(current=backwards.current.copy(clockValid=true)).continues(backwards))
        val rounded=first.copy(current=first.current.copy(foregroundSeconds=first.current.foregroundSeconds-.0000000001,
            inactiveSeconds=first.current.inactiveSeconds+.0000000001));rounded.validate();assertTrue(first.continues(rounded))
    }
    @Test fun codecAndArchiveFreezeProtectOriginalFlow(){
        val root=Files.createTempDirectory("forestix-flow-freeze-").toFile()
        try{
            val c=FieldStudyConfiguration(enabled=true,studyId="SYNTHETIC",operatorId="TEST");val id=newFieldId()
            val s=FieldMeasurementSession(0.0);val flow=FieldUIFlowClock(FieldStudyKind.DBH);flow.open(0.0,true,"2026-01-01T00:00:00Z")
            s.begin(id,c,null,FieldStudyKind.DBH,emptyMap(),emptyList(),false,false,true,1.0)
            s.finish(id,25.0,null,true,null,FieldAIProvenance(),2.0);s.accept(id,id,"quick_history",3.0)
            s.updateUIFlow(requireNotNull(flow.evidence(3.0,null,true,true)))
            val record=requireNotNull(s.current);assertEquals(record,FieldStudyJson.measurement(FieldStudyJson.measurementJson(record).toString()))
            val archive=FieldStudyArchive(root);archive.mergeSessionSnapshot(record)
            assertThrows(FieldStudyException::class.java){archive.updateMeasurement(id){it.copy(uiFlowTiming=it.uiFlowTiming!!.copy(id=newFieldId()))}}
            assertThrows(FieldStudyException::class.java){archive.updateMeasurement(id){it.copy(uiFlowTiming=it.uiFlowTiming!!.copy(atAccepted=null))}}
            assertThrows(FieldStudyException::class.java){archive.updateMeasurement(id){it.copy(uiFlowTiming=null)}}
            archive.mergeSessionSnapshot(record.copy(uiFlowTiming=null));assertEquals(record.uiFlowTiming,archive.measurement(id).uiFlowTiming)
        }finally{root.deleteRecursively()}
    }
}
