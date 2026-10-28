package com.hcjeong.forestix.fieldstudy

import com.hcjeong.forestix.data.fieldstudy.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.*

class FieldResultSaveRecoveryTest {
    private fun session(kind:FieldStudyKind=FieldStudyKind.DBH)=FieldMeasurementSession(0.0).also{
        it.begin(newFieldId(),FieldStudyConfiguration(enabled=true,studyId="SYNTHETIC",operatorId="TEST"),null,kind,emptyMap(),emptyList(),true,false,false,1.0)}
    private fun finish(s:FieldMeasurementSession,store:String="quick_history"):FieldResultSaveIntent{
        val id=s.currentId!!;s.finish(id,25.0,1.0,true,null,FieldAIProvenance(),3.0)
        return FieldResultSaveIntent(id,s.current!!,FieldExportApplicationResult(newFieldId(),store,s.current!!.kind,25.0,id))
    }
    private fun fixture(block:(File,FieldStudyArchive)->Unit){
        val dir=Files.createTempDirectory("forestix-result-save-").toFile()
        try{block(dir,FieldStudyArchive(dir))}finally{dir.deleteRecursively()}
    }
    @Test fun restartAfterNativeSaveRestoresFullAttemptWithUnknownAcceptanceTime()=fixture{dir,a->
        val intent=finish(session(FieldStudyKind.HEIGHT));a.prepareResultSave(intent)
        val reopened=FieldStudyArchive(dir)
        assertThrows(FieldStudyException::class.java){reopened.recoverResultSave(intent.id,emptyList())}
        assertThrows(FieldStudyException::class.java){reopened.measurement(intent.id)}
        reopened.recoverResultSave(intent.id,listOf(intent.expected));val saved=reopened.measurement(intent.id)
        assertEquals(FieldSelectionStatus.ACCEPTED,saved.selection);assertEquals(intent.snapshot.configuration,saved.configuration)
        assertEquals(1,saved.attemptNumber);assertEquals(3.0,saved.timing!!.firstValidResultElapsedSeconds!!,0.0)
        assertNull(saved.timing!!.acceptedElapsedSeconds);assertNull(saved.uiFlowTiming?.atAccepted)
        assertTrue(saved.events.any{it.action=="result_save_recovered"&&it.detail=="acceptance_time_unrecorded"})
        assertTrue(reopened.scanResultSaves().values.single().completed)
        reopened.recoverResultSave(intent.id,emptyList());assertEquals(saved,reopened.measurement(intent.id))
    }
    @Test fun startedOnlyJournalUsesPreparedSnapshotAndKeepsRawEvidence()=fixture{_,a->
        val s=session();a.createMeasurement(s.current!!);val intent=finish(s,"cruise");a.prepareResultSave(intent)
        a.updateRawEvidence(intent.id,FieldRawStatus.SAVED,null,24.9)
        assertEquals(0,a.recoverInterruptedAttempts());a.recoverResultSave(intent.id,listOf(intent.expected))
        val saved=a.measurement(intent.id);assertEquals(FieldRawStatus.SAVED,saved.rawStatus);assertEquals(24.9,saved.replayValue!!,0.0)
        assertEquals(FieldAttemptOutcome.RESULT,saved.outcome);assertEquals(FieldSelectionStatus.ACCEPTED,saved.selection)
        assertThrows(FieldStudyException::class.java){a.mergeSessionSnapshot(intent.snapshot)}
        assertEquals(saved,a.measurement(intent.id))
    }
    @Test fun nativeProofRequiresExactRowKindStoreValueUUIDAndNotDeletedOrDuplicate()=fixture{_,a->
        val intent=finish(session());a.prepareResultSave(intent);a.createMeasurement(intent.snapshot);val e=intent.expected
        val wrong=listOf(emptyList(),listOf(e.copy(measurementId=null)),listOf(e.copy(measurementId=newFieldId())),listOf(e.copy(id=newFieldId())),
            listOf(e.copy(kind=FieldStudyKind.HEIGHT)),listOf(e.copy(store="cruise")),listOf(e.copy(value=26.0)),listOf(e.copy(deleted=true)),listOf(e,e))
        for(proof in wrong){assertThrows(FieldStudyException::class.java){a.recoverResultSave(intent.id,proof)}
            assertEquals(intent.snapshot,a.measurement(intent.id));assertFalse(a.scanResultSaves().values.single().completed)}
    }
    @Test fun normalConfirmationKeepsRealTimingAndDiscardedAttemptIsNotResurrected()=fixture{_,a->
        val s=session();val intent=finish(s,"cruise");a.prepareResultSave(intent)
        s.accept(intent.id,intent.expected.id,"cruise",6.0);a.mergeSessionSnapshot(s.current!!)
        a.recoverResultSave(intent.id,emptyList());assertEquals(6.0,a.measurement(intent.id).timing!!.acceptedElapsedSeconds!!,0.0)
        val other=session();val retired=finish(other);a.prepareResultSave(retired);other.abandon(retired.id,"user_discarded",4.0);a.mergeSessionSnapshot(other.current!!)
        assertThrows(FieldStudyException::class.java){a.recoverResultSave(retired.id,listOf(retired.expected))}
        assertEquals(FieldSelectionStatus.DISCARDED,a.measurement(retired.id).selection)
    }
    @Test fun immutablePreparationAndCorruptReceiptsRemainVisibleAndUnmodified()=fixture{dir,a->
        val intent=finish(session());a.prepareResultSave(intent);a.prepareResultSave(intent)
        assertThrows(FieldStudyException::class.java){a.prepareResultSave(intent.copy(expected=intent.expected.copy(id=newFieldId())))}
        assertEquals(intent,FieldResultSaveIntent.decode(intent.encode()))
        val json=Json.parseToJsonElement(intent.encode()).jsonObject
        assertThrows(FieldStudyException::class.java){FieldResultSaveIntent.decode(JsonObject(json+ ("completed" to JsonPrimitive("false"))).toString())}
        val file=File(File(dir,"result-saves"),newFieldId().uppercase()+".json");file.writeText("{partial")
        assertEquals(1,a.scanResultSaves().issues.size);assertEquals("{partial",file.readText())
    }
    @Test fun exportReportsUnresolvedSaveAndPreservesTheReceipt()=fixture{dir,a->
        val intent=finish(session());a.saveConfiguration(intent.snapshot.configuration,true);a.prepareResultSave(intent)
        val output=Files.createTempDirectory("forestix-save-export-").toFile()
        try{
            val export=FieldStudyExport.build(a,File(dir,"raw"),emptyList(),outputDirectory=output)
            assertTrue(export.report.issues.any{it.code=="unresolved_application_save"&&it.measurementId==intent.id})
            assertFalse(export.report.integrityPassed);assertEquals(listOf(intent),a.scanResultSaves().values)
        }finally{output.deleteRecursively()}
    }
    @Test fun actualCoordinatorPreparesBeforeWriteAndCompletesInJournalOrder()=runBlocking<Unit>{
        val dir=Files.createTempDirectory("forestix-save-coordinator-").toFile()
        try{
            val store=FieldStudyStore(dir);withTimeout(5000){while(!store.state.value.loaded)delay(2)}
            store.saveConfiguration(FieldStudyConfiguration(enabled=true,studyId="SYNTHETIC",operatorId="TEST"),true)
            var now=0.0;val c=FieldCaptureCoordinator(store,FieldStudyKind.DBH,FieldUIFlowRegistry()){now};c.configure(emptyMap(),"quick:1")
            now=1.0;val id=c.begin(false,false);now=3.0;c.finish(id,25.0,1.0,true,null,FieldAIProvenance())
            c.prepareResultSave(id,id,"quick_history",25.0);assertFalse(store.archive.scanResultSaves().values.single().completed)
            now=6.0;c.resultSaved(id,id,"quick_history");store.awaitPendingWrites()
            assertNull(c.error.value);assertTrue(store.archive.scanResultSaves().values.single().completed)
            assertEquals(6.0,store.archive.measurement(id).timing!!.acceptedElapsedSeconds!!,0.0)
        }finally{dir.deleteRecursively()}
    }
    @Test fun startupReaderRunsBeforeInterruptedClassificationAndReportsMissingProof()=runBlocking<Unit>{
        val dir=Files.createTempDirectory("forestix-save-startup-").toFile()
        try{
            val a=FieldStudyArchive(dir);val s=session();a.createMeasurement(s.current!!);val intent=finish(s)
            a.saveConfiguration(intent.snapshot.configuration,true);a.prepareResultSave(intent)
            var reads=0
            val store=FieldStudyStore(dir,resultSaveReader={reads++;listOf(intent.expected)})
            withTimeout(5000){while(!store.state.value.loaded)delay(2)}
            assertEquals(1,reads);assertEquals(FieldSelectionStatus.ACCEPTED,store.archive.measurement(intent.id).selection)
            assertTrue(store.state.value.issues.isEmpty());assertNull(store.archive.measurement(intent.id).timing!!.acceptedElapsedSeconds)
        }finally{dir.deleteRecursively()}
    }
    @Test fun cruiseRecoveryFinishesExactRowSupersessionAfterAcceptedJournalWrite()=fixture{_,a->
        val first=finish(session(),"cruise")
        val old=first.snapshot.copy(runtimeSettings=mapOf("application.mode" to "cruise","application.plot_id" to newFieldId(),"application.tree_number" to "7"),
            selection=FieldSelectionStatus.ACCEPTED,resultRecordId=first.expected.id,resultStore="cruise")
        a.createMeasurement(old);val id=newFieldId()
        val next=old.copy(id=id,sessionId=newFieldId(),treeId="unassigned:$id",rawBundleId=id,selection=FieldSelectionStatus.PENDING,resultRecordId=null,resultStore=null)
        val intent=FieldResultSaveIntent(id,next,FieldExportApplicationResult(first.expected.id,"cruise",FieldStudyKind.DBH,25.0,id))
        a.prepareResultSave(intent);a.mergeSessionSnapshot(next.copy(selection=FieldSelectionStatus.ACCEPTED,resultRecordId=first.expected.id,resultStore="cruise"))
        a.recoverResultSave(id,emptyList());assertEquals(FieldSelectionStatus.SUPERSEDED,a.measurement(old.id).selection)
        assertEquals(FieldSelectionStatus.ACCEPTED,a.measurement(id).selection);assertTrue(a.scanResultSaves().values.single().completed)
    }
    @Test fun nativeQuickProofDoesNotUseLegacyUnknownKindFallback(){
        val id=newFieldId()
        assertNull(FieldResultSaveIntent.quickProof(id,"unknown",25.0,id))
        assertNull(FieldResultSaveIntent.quickProof(id,"crown",25.0,id))
        assertEquals(FieldStudyKind.HEIGHT,FieldResultSaveIntent.quickProof(id,"height",25.0,id)!!.kind)
        assertEquals(id,FieldResultSaveIntent.quickProof(id,"dbh",25.0,id)!!.measurementId)
    }
}
