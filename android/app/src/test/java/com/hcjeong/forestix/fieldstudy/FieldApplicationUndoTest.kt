package com.hcjeong.forestix.fieldstudy

import com.hcjeong.forestix.data.fieldstudy.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.File
import kotlinx.serialization.json.*

class FieldApplicationUndoTest {
    private val row=newFieldId()
    private fun record(kind:FieldStudyKind=FieldStudyKind.DBH,round:Int=1):FieldMeasurementRecord{
        val c=FieldStudyConfiguration(enabled=true,studyId="SYNTHETIC",operatorId="TEST",plannedRepetitions=3,plannedRepeat=round)
        val target=FieldStudyTarget(c.studyId,"0003","003");val s=FieldMeasurementSession(0.0);val id=newFieldId()
        val flow=FieldUIFlowClock(kind);flow.open(0.0,true)
        s.begin(id,c,target,kind,emptyMap(),emptyList(),true,false,false,1.0)
        s.finish(id,if(kind==FieldStudyKind.DBH)30.0 else 20.0,1.0,true,null,FieldAIProvenance(),2.0)
        s.accept(id,row,"cruise",3.0);s.updateUIFlow(requireNotNull(flow.evidence(3.0,null,true,true)))
        return requireNotNull(s.current).copy(rawStatus=FieldRawStatus.SAVED)
    }
    @Test fun dbhHeightUndoRestoresOnlyExactPriorRecordsAndKeepsCaptureEvidence(){
        val root=Files.createTempDirectory("forestix-undo-").toFile()
        try{
            val a=FieldStudyArchive(root);val oldD=record();val oldH=record(FieldStudyKind.HEIGHT);val newD=record();val newH=record(FieldStudyKind.HEIGHT);val other=record(round=2)
            listOf(oldD,oldH,newD,newH,other).forEach(a::createMeasurement)
            assertTrue(a.supersedeAcceptedMeasurement(oldD.id,newD.id));assertTrue(a.supersedeAcceptedMeasurement(oldH.id,newH.id))
            val op=newFieldId();val changes=a.undoApplicationMeasurements(op,row.lowercase(),listOf(newD.id,newH.id))
            assertEquals(4,changes.size)
            for(r in listOf(newD,newH)){
                val undone=a.measurement(r.id);assertTrue(undone.wasApplicationUndone);assertEquals(FieldSelectionStatus.DISCARDED,undone.selection)
                assertEquals(r.liveValue,undone.liveValue);assertEquals(r.rawBundleId,undone.rawBundleId);assertEquals(FieldRawStatus.SAVED,undone.rawStatus)
                assertEquals(r.ai,undone.ai);assertEquals(r.timing,undone.timing);assertEquals(r.uiFlowTiming,undone.uiFlowTiming)
            }
            listOf(oldD,oldH,other).forEach{assertEquals(FieldSelectionStatus.ACCEPTED,a.measurement(it.id).selection)}
            assertEquals(changes.map{it.revision}.sorted(),a.undoApplicationMeasurements(op,row,listOf(newH.id,newD.id)).map{it.revision}.sorted())
            assertThrows(FieldStudyException::class.java){a.undoApplicationMeasurements(op,newFieldId(),listOf(newD.id,newH.id))}
        }finally{root.deleteRecursively()}
    }
    @Test fun generalMutationCannotForgeUndoAndWrongBindingDoesNothing(){
        val root=Files.createTempDirectory("forestix-undo-").toFile()
        try{
            val a=FieldStudyArchive(root);val current=record();a.createMeasurement(current)
            assertThrows(FieldStudyException::class.java){a.updateMeasurement(current.id){it.copy(selection=FieldSelectionStatus.DISCARDED,events=it.events+FieldMeasurementEvent("application_undo",newFieldId()))}}
            assertThrows(FieldStudyException::class.java){a.undoApplicationMeasurements(newFieldId(),newFieldId(),listOf(current.id))}
            assertThrows(FieldStudyException::class.java){a.undoApplicationMeasurements(newFieldId(),row,listOf(newFieldId()))}
            assertEquals(current,a.measurement(current.id))
        }finally{root.deleteRecursively()}
    }
    @Test fun interruptedMultiRecordBatchCompletesOnFreshArchiveRead(){
        val root=Files.createTempDirectory("forestix-undo-").toFile()
        try{
            val a=FieldStudyArchive(root);val old=record();val current=record();a.createMeasurement(old);a.createMeasurement(current)
            a.supersedeAcceptedMeasurement(old.id,current.id)
            val batch=FieldApplicationUndoBatch.prepare(newFieldId(),row,listOf(current.id),a.scanMeasurements().values)
            val dir=File(root,"application-undos");check(dir.mkdirs());File(dir,batch.id.uppercase()+".json").writeText(batch.encode())
            val first=batch.changes.first();File(root,"measurements/${first.after.id.uppercase()}.json").writeText(FieldStudyJson.measurementJson(first.after).toString())
            val restarted=FieldStudyArchive(root);assertEquals(FieldSelectionStatus.DISCARDED,restarted.measurement(current.id).selection)
            assertEquals(FieldSelectionStatus.ACCEPTED,restarted.measurement(old.id).selection);assertTrue(restarted.scanMeasurements().issues.isEmpty())
            assertEquals(batch,FieldApplicationUndoBatch.decode(batch.encode()))
        }finally{root.deleteRecursively()}
    }
    @Test fun staleAcceptedSnapshotAndRawWriterCannotResurrectUndoneResult(){
        val root=Files.createTempDirectory("forestix-undo-").toFile()
        try{
            val a=FieldStudyArchive(root);val current=record();a.createMeasurement(current)
            a.undoApplicationMeasurements(newFieldId(),row,listOf(current.id));a.mergeSessionSnapshot(current)
            a.updateRawEvidence(current.id,FieldRawStatus.SAVED,null,31.0)
            val final=a.measurement(current.id);assertTrue(final.wasApplicationUndone);assertEquals(31.0,final.replayValue)
            assertEquals(current.uiFlowTiming!!.atAccepted,final.uiFlowTiming!!.atAccepted)
            assertTrue(FieldTargetProgress.completed("SYNTHETIC",1,listOf(final)).isEmpty())
            assertEquals(final,FieldStudyJson.measurement(FieldStudyJson.measurementJson(final).toString()))
        }finally{root.deleteRecursively()}
    }
    @Test fun priorResultSupersededByAnotherReplacementIsNeverRestored(){
        val old=record();val current=record();val other=record()
        val prior=old.copy(selection=FieldSelectionStatus.SUPERSEDED,events=old.events+FieldMeasurementEvent("superseded",other.id))
        val batch=FieldApplicationUndoBatch.prepare(newFieldId(),row,listOf(current.id),listOf(prior,current,other))
        assertEquals(1,batch.changes.size);assertEquals(current.id,batch.changes.single().after.id)
    }
    @Test fun corruptCompletedBatchCannotRewriteCaptureValue(){
        val current=record();val batch=FieldApplicationUndoBatch.prepare(newFieldId(),row,listOf(current.id),listOf(current))
        val o=Json.parseToJsonElement(batch.encode()).jsonObject.toMutableMap()
        val c=o.getValue("changes").jsonArray.first().jsonObject.toMutableMap()
        val after=c.getValue("after").jsonObject.toMutableMap();after["live_value"]=JsonPrimitive(999)
        c["after"]=JsonObject(after);o["changes"]=JsonArray(listOf(JsonObject(c)));o["completed"]=JsonPrimitive(true)
        assertThrows(FieldStudyException::class.java){FieldApplicationUndoBatch.decode(JsonObject(o).toString())}
    }
}
