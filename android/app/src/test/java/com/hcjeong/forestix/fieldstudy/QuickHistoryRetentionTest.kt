package com.hcjeong.forestix.fieldstudy

import com.hcjeong.forestix.data.*
import com.hcjeong.forestix.data.fieldstudy.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.lang.reflect.Proxy
import java.lang.reflect.InvocationTargetException

class QuickHistoryRetentionTest {
    private fun row(n:Int,id:String=newFieldId())=EntryRow(id=id,kind="dbh",value=25+n/1000.0,secondaryValue=null,sigma=1.0,
        confidenceRaw="green",method="manualVisual",createdAt=1700000000000L+n,treeNumber=n,treeName="00$n",plotID="SYNTHETIC-PLOT",speciesCode="DF",position="dbh",
        damageCodes="[\"x,y\",\"🌲\"]",note="SYNTHETIC,\noriginal",latitude=null,longitude=null,photoPath="old.jpg",captureMode="typed",truth=26.5,truthSource="typed",truthUnit="cm",
        positionSource=null,timeSource=null,measurementId=id)
    /** Executes the real production DAO default method against a test double.
     * This is not a native Room transaction/rollback certification. */
    private class BodyHarness {
        val rows=LinkedHashMap<String,EntryRow>()
        val dao:QuickMeasureDao=Proxy.newProxyInstance(QuickMeasureDao::class.java.classLoader,arrayOf(QuickMeasureDao::class.java)){proxy,method,args->
            when(method.name){
                "allEntries"->rows.values.sortedByDescending{it.createdAt}
                "upsertEntry"->{val row=args!![0] as EntryRow;rows[row.id]=row;Unit}
                "deleteEntry"->{rows.remove(args!![0] as String);Unit}
                "saveCapturedEntry"->try{
                    Class.forName("com.hcjeong.forestix.data.QuickMeasureDao\$DefaultImpls").methods.single{it.name=="saveCapturedEntry"}.invoke(null,proxy,*args!!)
                }catch(e:InvocationTargetException){throw e.targetException}
                "toString"->"SyntheticQuickDao"
                else->error("Unexpected DAO call ${method.name}")
            }
        } as QuickMeasureDao
    }
    @Test fun realDaoBodyKeeps1001RowsBeyondThresholdAndReplacementTouchesOnlyItsPlan()=runBlocking<Unit>{
        val h=BodyHarness();val all=(1..1001).map{row(it)}
        for(r in all)h.dao.saveCapturedEntry(r,false,"SYNTHETIC-PLOT",3)
        assertEquals(1001,h.rows.size)
        val next=row(1);val plan=QuickCaptureReplacement.snapshots(listOf(all[0]))
        val removed=h.dao.saveCapturedEntry(next,true,"SYNTHETIC-PLOT",3,plan)
        assertEquals(listOf(all[0]),removed);assertEquals(1001,h.rows.size)
        all.drop(1).forEach{assertEquals(it,h.rows[it.id])}
        assertEquals(next,h.rows[next.id])
    }
    @Test fun realDaoBodyRejectsStaleReplacementMetadataBeforeAnyDeletion()=runBlocking<Unit>{
        val h=BodyHarness();val old=row(7);h.dao.saveCapturedEntry(old,false,"SYNTHETIC-PLOT",2)
        val plan=QuickCaptureReplacement.snapshots(listOf(old));val edit=old.copy(note="independent edit");h.rows[old.id]=edit
        val next=row(7);var failed=false
        try{h.dao.saveCapturedEntry(next,true,"SYNTHETIC-PLOT",2,plan)}catch(_:FieldStudyException){failed=true}
        assertTrue(failed);assertEquals(mapOf(old.id to edit),h.rows)
    }
    @Test fun originalNativePayloadAndLegacyNilIdentityRoundTripWithoutUnitReinterpretation(){
        val old=row(7).copy(measurementId=null)
        val snapshot=QuickCaptureReplacement.snapshots(listOf(old)).single();val json=Json.parseToJsonElement(snapshot.payloadJSON).jsonObject
        assertNull(snapshot.result.measurementId);assertEquals(26.5,json.getValue("truth").jsonPrimitive.double,0.0)
        assertEquals(old.damageCodes,json.getValue("damageCodes").jsonPrimitive.content)
        assertEquals(old.note,json.getValue("note").jsonPrimitive.content);assertEquals(old.createdAt,json.getValue("createdAt").jsonPrimitive.long)
        assertEquals(snapshot,FieldQuickReplacementSnapshot.decode(snapshot.json()))
        assertThrows(FieldStudyException::class.java){FieldQuickReplacementSnapshot(snapshot.result,snapshot.payloadFormat,
            JsonObject(json+("value" to JsonPrimitive(99.0))).toString()).validate()}
    }
    @Test fun startupRecoversThreeTornReplacementsThroughCompletedOriginalSnapshots()=runBlocking<Unit>{
        val root=Files.createTempDirectory("forestix-retained-chain-").toFile()
        try{
            val archive=FieldStudyArchive(root);val c=FieldStudyConfiguration(enabled=true,studyId="SYNTHETIC",operatorId="TEST")
            archive.saveConfiguration(c,true);val native=BodyHarness();val ids=ArrayList<String>()
            repeat(3){n->
                val row=row(7);val plan=QuickCaptureReplacement.snapshots(QuickCaptureReplacement.rows(native.rows.values.toList(),row,n>0,"SYNTHETIC-PLOT"))
                val s=FieldMeasurementSession(0.0)
                s.begin(row.id,c,null,FieldStudyKind.DBH,mapOf("application.mode" to "quick","application.plot_id" to "SYNTHETIC-PLOT","application.tree_number" to "7"),emptyList(),false,false,true,1.0)
                archive.createMeasurement(s.current!!);s.finish(row.id,row.value,1.0,true,null,FieldAIProvenance(),3.0)
                archive.prepareResultSave(FieldResultSaveIntent(row.id,s.current!!,FieldExportApplicationResult(row.id,"quick_history",FieldStudyKind.DBH,row.value,row.id),replacements=plan))
                native.dao.saveCapturedEntry(row,n>0,"SYNTHETIC-PLOT",2,plan);ids+=row.id
            }
            val store=FieldStudyStore(root,resultSaveReader={intent->
                val wanted=(listOf(intent.expected.id)+intent.replacements.map{it.result.id}).toSet()
                native.rows.values.filter{it.id in wanted}.map{FieldResultSaveIntent.quickProof(it.id,it.kind,it.value,it.measurementId)!!}})
            withTimeout(10000){while(!store.state.value.loaded)delay(2)}
            assertTrue(store.state.value.issues.toString(),store.state.value.issues.isEmpty())
            assertTrue(store.archive.scanResultSaves().values.all{it.completed})
            ids.dropLast(1).forEach{assertEquals(FieldSelectionStatus.SUPERSEDED,store.archive.measurement(it).selection)}
            assertEquals(FieldSelectionStatus.ACCEPTED,store.archive.measurement(ids.last()).selection)
            assertNull(store.archive.measurement(ids.last()).timing!!.acceptedElapsedSeconds)
            assertEquals(1,native.rows.size)
        }finally{root.deleteRecursively()}
    }
    @Test fun archiveExportKeepsDifferentRoundOriginalAndValidatesPhotoPayload() {
        val root=Files.createTempDirectory("forestix-retained-photo-").toFile()
        try{
            val a=FieldStudyArchive(File(root,"field"));val c=FieldStudyConfiguration(enabled=true,studyId="SYNTHETIC",operatorId="TEST",plannedRepetitions=2)
            a.saveConfiguration(c,true);val old=row(7);val snapshot=QuickCaptureReplacement.snapshots(listOf(old)).single()
            val first=FieldMeasurementRecord(id=old.id,sessionId=newFieldId(),treeId="unassigned:${old.id}",kind=FieldStudyKind.DBH,attemptNumber=1,configuration=c,runtimeSettings=emptyMap(),
                outcome=FieldAttemptOutcome.RESULT,selection=FieldSelectionStatus.ACCEPTED,liveValue=old.value,resultRecordId=old.id,resultStore="quick_history")
            a.createMeasurement(first);val id=newFieldId();val next=first.copy(id=id,sessionId=newFieldId(),treeId="unassigned:$id",configuration=c.copy(plannedRepeat=2),plannedRepeat=2,
                selection=FieldSelectionStatus.PENDING,liveValue=26.0,resultRecordId=null,resultStore=null)
            val expected=FieldExportApplicationResult(id,"quick_history",FieldStudyKind.DBH,26.0,id)
            a.prepareResultSave(FieldResultSaveIntent(id,next,expected,replacements=listOf(snapshot)))
            assertThrows(FieldStudyException::class.java){a.recoverResultSave(id,listOf(expected,snapshot.result))}
            a.recoverResultSave(id,listOf(expected));assertEquals(FieldSelectionStatus.ACCEPTED,a.measurement(old.id).selection)
            val photos=File(root,"photos");check(photos.mkdir());File(photos,"old.jpg").writeBytes(byteArrayOf(1,2,3))
            val output=FieldStudyExport.build(a,File(root,"raw"),listOf(expected),outputDirectory=File(root,"exports"),applicationDirectories=listOf("measure-photos" to photos))
            assertTrue(output.report.issues.toString(),output.report.integrityPassed)
            java.util.zip.ZipFile(output.file).use{zip->
                assertArrayEquals(byteArrayOf(1,2,3),zip.getInputStream(zip.getEntry("application/measure-photos/old.jpg")).use{it.readBytes()})
                val index=Json.parseToJsonElement(zip.getInputStream(zip.getEntry("application/replaced_result_index.json")).bufferedReader().use{it.readText()}).jsonArray
                assertEquals("replaced_snapshot",index[0].jsonObject.getValue("storage_state").jsonPrimitive.content)
                assertEquals(id,index[0].jsonObject.getValue("source_measurement_id").jsonPrimitive.content)
                val inventory=Json.parseToJsonElement(zip.getInputStream(zip.getEntry("file_inventory.json")).bufferedReader().use{it.readText()}).jsonArray
                for(proof in inventory){val p=proof.jsonObject;val bytes=zip.getInputStream(zip.getEntry(p.getValue("path").jsonPrimitive.content)).use{it.readBytes()}
                    assertEquals(p.getValue("bytes").jsonPrimitive.long,bytes.size.toLong())
                    val hash=java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt()and 255)}
                    assertEquals(p.getValue("sha256").jsonPrimitive.content,hash)}
            }
            val absent=FieldStudyExport.build(a,File(root,"raw"),listOf(expected),outputDirectory=File(root,"exports"))
            assertTrue(absent.report.issues.any{it.code=="application_photo_missing"})
        }finally{root.deleteRecursively()}
    }
    @Test fun photoDirectoryMutationAndSymlinkRefuseExportWithoutTouchingOriginals(){
        val root=Files.createTempDirectory("forestix-retained-photo-mutation-").toFile()
        try{
            val photos=File(root,"photos");check(photos.mkdir());val original=File(photos,"one.jpg");original.writeBytes(byteArrayOf(1,2,3))
            val a=FieldStudyArchive(File(root,"field"))
            assertThrows(IllegalStateException::class.java){FieldStudyExport.build(a,File(root,"raw"),emptyList(),outputDirectory=File(root,"exports"),applicationDirectories=listOf("measure-photos" to photos),progress={path->
                if(path=="application/measure-photos/one.jpg")File(photos,"two.jpg").writeBytes(byteArrayOf(4))})}
            assertArrayEquals(byteArrayOf(1,2,3),original.readBytes())
            Files.createSymbolicLink(File(photos,"link.jpg").toPath(),original.toPath())
            assertThrows(IllegalArgumentException::class.java){FieldStudyExport.build(a,File(root,"raw"),emptyList(),outputDirectory=File(root,"exports"),applicationDirectories=listOf("measure-photos" to photos))}
        }finally{root.deleteRecursively()}
    }
}
