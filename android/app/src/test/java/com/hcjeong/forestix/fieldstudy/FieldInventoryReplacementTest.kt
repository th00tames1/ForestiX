package com.hcjeong.forestix.fieldstudy

import com.hcjeong.forestix.data.fieldstudy.*
import com.hcjeong.forestix.data.cruise.*
import com.hcjeong.forestix.sensors.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay

/** Synthetic repeated capture histories; no field samples. */
class FieldInventoryReplacementTest {
    @Test fun actualStoreStartupRecoversOlderReceiptWithoutRetiringNewerResult()=fixture{root->runBlocking{
        val a=FieldStudyArchive(root);a.saveConfiguration(config(),true);a.saveTargets(listOf(target))
        val original=tree();val snapshot=FieldInventoryReplacementSnapshot.from(original)
        val old=record(original.dbhMeasurementId!!,original,FieldStudyKind.DBH,1,original.dbhCm.toDouble(),false)
        val id=newFieldId();val newer=record(id,original,FieldStudyKind.DBH,1,28.0,false)
        a.prepareResultSave(FieldResultSaveIntent(old.id,old,snapshot.results[0]))
        val current=FieldExportApplicationResult(original.id.toString(),"cruise",FieldStudyKind.DBH,28.0,id)
        a.prepareResultSave(FieldResultSaveIntent(id,newer,current,inventoryReplacement=snapshot))
        val store=FieldStudyStore(root,resultSaveReader={listOf(current)})
        withTimeout(5000){while(!store.state.value.loaded)delay(2)}
        assertTrue(store.state.value.issues.toString(),store.state.value.issues.isEmpty())
        assertEquals(FieldSelectionStatus.SUPERSEDED,a.measurement(old.id).selection);assertEquals(FieldSelectionStatus.ACCEPTED,a.measurement(id).selection)
        assertTrue(a.scanResultSaves().values.all{it.completed});assertNull(a.measurement(old.id).timing?.acceptedElapsedSeconds)
    }}
    private fun tree()=Tree(id=UUID.randomUUID(),plotId=UUID.randomUUID(),treeNumber=7,treeName="SYNTHETIC-0007",speciesCode="DF",status=TreeStatus.LIVE,
        dbhCm=25.12345f,dbhMethod=DBHMethod.MANUAL_VISUAL,dbhSigmaMm=1f,dbhRmseMm=null,dbhCoverageDeg=null,dbhNInliers=null,
        dbhConfidence=ConfidenceTier.YELLOW,dbhIsIrregular=false,heightM=12.34567f,heightMethod=HeightMethod.MANUAL_ENTRY,heightSource="measured",
        heightSigmaM=null,heightDHM=null,heightAlphaTopDeg=null,heightAlphaBaseDeg=null,heightConfidence=ConfidenceTier.YELLOW,
        bearingFromCenterDeg=32f,distanceFromCenterM=4f,boundaryCall=null,crownClass="D",damageCodes=listOf("SYNTHETIC"),isMultistem=false,parentTreeId=null,
        notes="Original note, with \"quote\"\nand line",photoPath="old.jpg",rawScanPath=null,createdAt=100000,updatedAt=101000,deletedAt=null,
        latitude=45.0,longitude=-123.0,dbhMeasurementId=newFieldId(),heightMeasurementId=newFieldId())
    private val target=FieldStudyTarget("SYNTHETIC","0007","7")
    private fun config(round:Int=1)=FieldStudyConfiguration(enabled=true,studyId="SYNTHETIC",operatorId="TEST",plannedRepetitions=3,plannedRepeat=round)
    private fun record(id:String,tree:Tree,kind:FieldStudyKind,round:Int,value:Double,accepted:Boolean)=FieldMeasurementRecord(
        id=id,sessionId=newFieldId(),treeId=target.treeId,targetId=target.id,targetSnapshot=target,kind=kind,attemptNumber=1,configuration=config(round),runtimeSettings=emptyMap(),
        outcome=FieldAttemptOutcome.RESULT,liveValue=value,selection=if(accepted)FieldSelectionStatus.ACCEPTED else FieldSelectionStatus.PENDING,
        resultStore=if(accepted)"cruise"else null,resultRecordId=if(accepted)tree.id.toString()else null)
    private fun fixture(block:(File)->Unit){val root=Files.createTempDirectory("forestix-inventory-proof-").toFile();try{block(root)}finally{root.deleteRecursively()}}
    @Test fun nativeRowCodecPreservesBothFloatValuesIDsAndEveryOriginalField(){
        val original=tree();val snapshot=FieldInventoryReplacementSnapshot.from(original)
        assertEquals(original,FieldInventoryTreeJSON.decode(Json.parseToJsonElement(snapshot.payloadJSON).jsonObject))
        assertEquals(listOf(original.dbhCm.toDouble(),original.heightM!!.toDouble()),snapshot.results.map{it.value})
        assertEquals(listOf(original.dbhMeasurementId,original.heightMeasurementId),snapshot.results.map{it.measurementId})
        assertEquals(snapshot,FieldInventoryReplacementSnapshot.decode(snapshot.json()))
    }
    @Test fun malformedNativePayloadIsRefusedWhileUnknownLegacyIdentitiesStayUnknown(){
        val original=tree().copy(dbhMeasurementId=null,heightMeasurementId="legacy-unrecorded")
        val snapshot=FieldInventoryReplacementSnapshot.from(original)
        assertNull(snapshot.results[0].measurementId);assertEquals("legacy-unrecorded",snapshot.results[1].measurementId)
        val row=Json.parseToJsonElement(snapshot.payloadJSON).jsonObject
        for(field in listOf("notes","plotId","heightM"))assertThrows(Exception::class.java){snapshot.copy(payloadJSON=JsonObject(row-field).toString()).validate()}
        assertThrows(Exception::class.java){snapshot.copy(payloadJSON=JsonObject(row+("dbhCm" to JsonPrimitive(true))).toString()).validate()}
        assertThrows(Exception::class.java){FieldInventoryReplacementSnapshot.from(original.copy(deletedAt=200000))}
    }
    @Test fun differentRoundKeepsBothKindsAndOriginalPhotoInVerifiedExport()=fixture{root->
        val a=FieldStudyArchive(File(root,"field"));val original=tree();a.saveConfiguration(config(),true);a.saveTargets(listOf(target))
        val snapshot=FieldInventoryReplacementSnapshot.from(original)
        snapshot.results.forEach{a.createMeasurement(record(it.measurementId!!,original,it.kind,1,it.value,true))}
        val id=newFieldId();val next=record(id,original,FieldStudyKind.DBH,2,27.34567f.toDouble(),false)
        val expected=FieldExportApplicationResult(original.id.toString(),"cruise",FieldStudyKind.DBH,next.liveValue!!,id)
        val intent=FieldResultSaveIntent(id,next,expected,inventoryReplacement=snapshot)
        a.prepareResultSave(intent);a.recoverResultSave(id,listOf(expected))
        assertEquals(FieldSelectionStatus.ACCEPTED,a.measurement(original.dbhMeasurementId!!).selection)
        assertEquals(FieldSelectionStatus.ACCEPTED,a.measurement(original.heightMeasurementId!!).selection)
        val photos=File(root,"photos").apply{mkdir()};val photo="synthetic original photo bytes".toByteArray();File(photos,"old.jpg").writeBytes(photo)
        val output=FieldStudyExport.build(a,File(root,"raw"),listOf(expected,snapshot.results[1]),outputDirectory=File(root,"exports"),applicationDirectories=listOf("measure-photos" to photos))
        assertTrue(output.report.issues.toString(),output.report.integrityPassed)
        ZipFile(output.file).use{z->
            assertArrayEquals(photo,z.getInputStream(z.getEntry("application/measure-photos/old.jpg")).readBytes())
            val index=Json.parseToJsonElement(z.getInputStream(z.getEntry("application/replaced_inventory_index.json")).reader().readText()).jsonArray
            assertEquals(snapshot.payloadJSON,index[0].jsonObject.getValue("snapshot").jsonObject.getValue("payload_json").jsonPrimitive.content)
        }
        val oldIntent=FieldResultSaveIntent(original.dbhMeasurementId!!,record(original.dbhMeasurementId!!,original,FieldStudyKind.DBH,1,original.dbhCm.toDouble(),false),snapshot.results[0])
        assertEquals(snapshot.results.filter{it.kind==FieldStudyKind.DBH},a.completedReplacementProofs(oldIntent))
    }
    @Test fun preparedSnapshotIsImmutableAndCannotCrossTreeKindOrMeasurement()=fixture{root->
        val a=FieldStudyArchive(root);val original=tree();val snapshot=FieldInventoryReplacementSnapshot.from(original)
        val id=newFieldId();val r=record(id,original,FieldStudyKind.DBH,2,28.0,false)
        val expected=FieldExportApplicationResult(original.id.toString(),"cruise",FieldStudyKind.DBH,28.0,id)
        val intent=FieldResultSaveIntent(id,r,expected,inventoryReplacement=snapshot);a.prepareResultSave(intent)
        assertThrows(FieldStudyException::class.java){a.prepareResultSave(intent.copy(inventoryReplacement=FieldInventoryReplacementSnapshot.from(original.copy(notes="changed"))))}
        assertThrows(FieldStudyException::class.java){intent.copy(expected=expected.copy(id=newFieldId())).validate()}
        assertThrows(FieldStudyException::class.java){intent.copy(inventoryReplacement=FieldInventoryReplacementSnapshot.from(original.copy(dbhMeasurementId=id))).validate()}
        assertTrue(a.completedReplacementProofs(intent).isEmpty())
    }
    @Test fun proofSelectionCannotHideCurrentEditsDeletionDuplicatesOrConflictingHistory(){
        val old=FieldInventoryReplacementSnapshot.from(tree()).results[0];val newer=old.copy(value=30.0,measurementId=newFieldId())
        fun select(native:List<FieldExportApplicationResult>,archive:List<FieldExportApplicationResult>)=FieldResultSaveIntent.proofCandidates(old,native,archive)
        assertEquals(listOf(old),select(listOf(newer),listOf(old,old)))
        assertEquals(listOf(newer),select(listOf(newer),emptyList()))
        val edit=old.copy(value=99.0)
        assertEquals(listOf(edit),select(listOf(edit),listOf(old)));assertEquals(listOf(old,old),select(listOf(old,old),listOf(old)))
        val deleted=newer.copy(deleted=true)
        assertEquals(listOf(deleted),select(listOf(deleted),listOf(old)));assertEquals(2,select(listOf(newer),listOf(old,edit)).size)
    }
    @Test fun missingHistoricalNativeSnapshotIsReportedNotInventedFromValueOrTime()=fixture{root->
        val a=FieldStudyArchive(File(root,"field"));val original=tree();a.saveConfiguration(config(),true);a.saveTargets(listOf(target))
        val old=record(original.dbhMeasurementId!!,original,FieldStudyKind.DBH,1,original.dbhCm.toDouble(),true)
        val id=newFieldId();val next=record(id,original,FieldStudyKind.DBH,2,28.0,true);a.createMeasurement(old);a.createMeasurement(next)
        val output=FieldStudyExport.build(a,File(root,"raw"),listOf(FieldExportApplicationResult(original.id.toString(),"cruise",FieldStudyKind.DBH,28.0,id)),outputDirectory=File(root,"exports"))
        assertTrue(output.report.issues.toString(),output.report.issues.any{it.measurementId==old.id&&it.code in listOf("application_measurement_identity_mismatch","application_result_mismatch")});assertFalse(output.report.integrityPassed)
    }
    @Test fun lateRecoveryCannotSupersedeTheNewerSameRoundAndDoesNotInventAcceptanceTime(){
        for(round in listOf(1,2))fixture{root->
            val a=FieldStudyArchive(root);val original=tree();val snapshot=FieldInventoryReplacementSnapshot.from(original)
            val old=record(original.dbhMeasurementId!!,original,FieldStudyKind.DBH,1,original.dbhCm.toDouble(),false)
            val oldIntent=FieldResultSaveIntent(old.id,old,snapshot.results[0]);a.prepareResultSave(oldIntent)
            val id=newFieldId();val newer=record(id,original,FieldStudyKind.DBH,round,28.0,false)
            val expected=FieldExportApplicationResult(original.id.toString(),"cruise",FieldStudyKind.DBH,28.0,id)
            a.prepareResultSave(FieldResultSaveIntent(id,newer,expected,inventoryReplacement=snapshot));a.recoverResultSave(id,listOf(expected))
            val proof=FieldResultSaveIntent.proofCandidates(oldIntent.expected,listOf(expected),a.completedReplacementProofs(oldIntent))
            a.recoverResultSave(old.id,proof)
            assertEquals(FieldSelectionStatus.ACCEPTED,a.measurement(id).selection)
            val recovered=a.measurement(old.id)
            assertEquals(if(round==1)FieldSelectionStatus.SUPERSEDED else FieldSelectionStatus.ACCEPTED,recovered.selection)
            assertNull(recovered.timing?.acceptedElapsedSeconds);assertNull(recovered.uiFlowTiming?.atAccepted)
            a.reconcileCompletedReplacements();assertEquals(FieldSelectionStatus.ACCEPTED,a.measurement(id).selection)
        }
    }
    @Test fun undoRetainsSnapshotButDoesNotUseUndoneReplacementAsCurrentProof()=fixture{root->
        val a=FieldStudyArchive(File(root,"field"));val original=tree();a.saveConfiguration(config(),true);a.saveTargets(listOf(target))
        val snapshot=FieldInventoryReplacementSnapshot.from(original)
        snapshot.results.forEach{a.createMeasurement(record(it.measurementId!!,original,it.kind,1,it.value,true))}
        val id=newFieldId();val newer=record(id,original,FieldStudyKind.DBH,2,28.0,false)
        val expected=FieldExportApplicationResult(original.id.toString(),"cruise",FieldStudyKind.DBH,28.0,id)
        a.prepareResultSave(FieldResultSaveIntent(id,newer,expected,inventoryReplacement=snapshot));a.recoverResultSave(id,listOf(expected))
        a.undoApplicationMeasurements(newFieldId(),original.id.toString(),listOf(id))
        val output=FieldStudyExport.build(a,File(root,"raw"),snapshot.results,outputDirectory=File(root,"exports"))
        assertTrue(output.report.issues.toString(),output.report.integrityPassed);assertTrue(a.measurement(id).wasApplicationUndone)
    }
}
