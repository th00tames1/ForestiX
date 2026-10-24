package com.hcjeong.forestix.fieldstudy

import com.hcjeong.forestix.data.*
import com.hcjeong.forestix.data.fieldstudy.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.UUID

/** Production coordinator/journal/CSV matrix. Actual Android raw/SQLite IO
 * is covered by the isolated native fixture, not claimed by this host test. */
class FieldRecordingMatrixTest {
    private suspend fun scenario(kind:FieldStudyKind,study:Boolean,raw:Boolean,timing:Boolean){
        val root=Files.createTempDirectory("forestix-recording-matrix-").toFile()
        try{
            val store=FieldStudyStore(root);withTimeout(5000){while(!store.state.value.loaded)delay(2)}
            val c=FieldStudyConfiguration(enabled=study,studyId="SYNTHETIC",operatorId="TEST",plannedRepetitions=3,plannedRepeat=2,recordTiming=timing)
            store.saveConfiguration(c,true);val target=FieldStudyTarget(c.studyId,"0003","3");store.saveTargets(listOf(target));store.selectTarget(target.id)
            var now=0.0;val registry=FieldUIFlowRegistry();registry.owner("app",true,now)
            val capture=FieldCaptureCoordinator(store,kind,registry){now}
            capture.configure(mapOf("application.tree_number" to "3"),"quick:3");capture.appeared();now=2.0
            val id=capture.begin(raw,false);now=4.0
            val value=if(kind==FieldStudyKind.DBH)25.12345 else 12.34567
            capture.finish(id,value,null,true,null,FieldAIProvenance())
            val reading=QuickMeasureEntry(id=UUID.fromString(id),kind=if(kind==FieldStudyKind.DBH)MeasureKind.DBH else MeasureKind.HEIGHT,
                value=value,sigma=null,confidenceRaw="yellow",method="synthetic",treeNumber=3).identifyingMeasurement()
            capture.prepareResultSave(id,id,"quick_history",value)
            // No native write is fabricated here: this confirms the coordinator
            // contract only. The device fixture confirms AFTER actual SQLite IO.
            now=7.0;capture.resultSaved(id,id,"quick_history");store.awaitPendingWrites()
            assertNull(capture.error.value);assertEquals(id,reading.measurementId)
            val csv=QuickMeasureExport.buildCsv(listOf(reading),emptyList()).toString(Charsets.UTF_8)
            assertTrue(csv.contains("\"measurement_id\""));assertTrue(csv.contains("\"$id\""))
            if(study){
                val record=store.archive.measurement(id)
                assertEquals(id,record.resultRecordId);assertEquals(value,record.liveValue!!,0.0)
                assertEquals("0003",record.treeId);assertEquals("TEST",record.operatorId);assertEquals(2,record.plannedRepeat)
                assertEquals(FieldSelectionStatus.ACCEPTED,record.selection)
                assertEquals(if(raw)FieldRawStatus.PENDING else FieldRawStatus.NOT_REQUESTED,record.rawStatus)
                if(timing){assertEquals(4.0,record.uiFlowTiming!!.atResult!!.elapsedSeconds,0.0);assertEquals(7.0,record.uiFlowTiming!!.atAccepted!!.elapsedSeconds,0.0)}
                else {assertNull(record.timing);assertNull(record.uiFlowTiming)}
            }else{assertNull(capture.recordedMeasurementId(id));assertTrue(store.archive.scanMeasurements().values.isEmpty());assertTrue(store.archive.scanResultSaves().values.isEmpty())}
        }finally{root.deleteRecursively()}
    }
    @Test fun rawOnlyKeepsResultAndCSVUUIDWithoutInventingStudyObservations()=runBlocking{
        for(kind in FieldStudyKind.entries)scenario(kind,false,true,true)
    }
    @Test fun studyRawAndTimingSwitchesRemainIndependentForBothKinds()=runBlocking{
        for(kind in FieldStudyKind.entries)for(raw in listOf(false,true))for(timing in listOf(false,true))scenario(kind,true,raw,timing)
    }
}
