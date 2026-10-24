package com.hcjeong.forestix.fieldstudy

import com.hcjeong.forestix.data.fieldstudy.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipFile

class FieldStudyExportTest {
    @Test fun legacyConfidenceReferencesArePreservedAndMissingOrMalformedFilesAreAudited(){
        val base=Files.createTempDirectory("forestix-confidence-export-").toFile()
        try{
            val(a,_,_)=archive(base);val id=newFieldId();val raw=File(base,"raw");val bundle=File(raw,id).apply{mkdirs()}
            File(bundle,"depth.bin").writeBytes(byteArrayOf(0,0,-128,63))
            fun manifest(name:String){File(bundle,"manifest.json").writeText(buildJsonObject{
                put("schema",2);put("kind","dbh");put("measurement_id",id)
                put("dbh",buildJsonObject{put("frames",JsonArray(listOf(buildJsonObject{
                    put("depth_file","depth.bin");put("confidence_file",name);put("width",1);put("height",1);put("format","f32m")
                })))})}.toString())}
            fun build()=FieldStudyExport.build(a,raw,emptyList(),outputDirectory=File(base,"exports"))
            manifest("depth.conf")
            assertTrue(build().report.issues.any{it.code=="raw_file_missing"&&it.path.endsWith("depth.conf")})
            File(bundle,"depth.conf").writeBytes(byteArrayOf(2,2))
            assertTrue(build().report.issues.any{it.code=="raw_confidence_size_mismatch"})
            File(bundle,"depth.conf").writeBytes(byteArrayOf(1))
            val valid=build();assertFalse(valid.report.issues.any{it.path.endsWith("depth.conf")})
            assertArrayEquals(byteArrayOf(1),files(valid.file)["raw-captures/$id/depth.conf"])
            manifest("../depth.conf")
            assertTrue(build().report.issues.any{it.code=="unsafe_raw_file_reference"})
        }finally{base.deleteRecursively()}
    }
    @Test fun actualIOSAndAndroidExportCodecsInteroperateWithScopeAndTiming(){
        for(producer in listOf("ios","android")){
            val text=javaClass.classLoader!!.getResourceAsStream("field-export-scope-$producer.json")!!.use{it.readBytes().toString(Charsets.UTF_8)}
            val record=FieldStudyJson.measurement(text);record.validate()
            assertEquals("SYNTHETIC",record.studyId);assertEquals("OP-1",record.operatorId);assertEquals("0007",record.treeId)
            assertEquals(25.0,record.measurementScope!!.references[0].inputValue,0.0);assertEquals(6.0,record.uiFlowTiming!!.atAccepted!!.elapsedSeconds,0.0)
            assertEquals(record,FieldStudyJson.measurement(FieldStudyJson.measurementJson(record).toString()))
        }
    }
    private fun config()=FieldStudyConfiguration(enabled=true,studyId="SYNTHETIC",operatorId="OP-1")
    private fun archive(base:File):Triple<FieldStudyArchive,FieldStudyConfiguration,FieldStudyTarget>{
        val a=FieldStudyArchive(File(base,"field"));val c=config();a.saveConfiguration(c,true)
        val t=FieldStudyTarget(c.studyId,"0007","007");a.saveTargets(listOf(t));return Triple(a,c,t)
    }
    private fun record(a:FieldStudyArchive,c:FieldStudyConfiguration,t:FieldStudyTarget,outcome:FieldAttemptOutcome=FieldAttemptOutcome.RESULT,selection:FieldSelectionStatus=FieldSelectionStatus.ACCEPTED):FieldMeasurementRecord{
        val id=newFieldId();val r=FieldMeasurementRecord(id=id,sessionId=newFieldId(),treeId=t.treeId,targetId=t.id,targetSnapshot=t,kind=FieldStudyKind.DBH,attemptNumber=1,configuration=c,runtimeSettings=emptyMap(),outcome=outcome,selection=selection,
            liveValue=25.0.takeIf{outcome==FieldAttemptOutcome.RESULT},failureReason="synthetic_failure".takeIf{outcome==FieldAttemptOutcome.FAILED},resultRecordId=id.takeIf{selection==FieldSelectionStatus.ACCEPTED},resultStore="quick_history".takeIf{selection==FieldSelectionStatus.ACCEPTED})
        a.createMeasurement(r);return r
    }
    private fun files(zip:File):Map<String,ByteArray> = ZipFile(zip).use{z->z.entries().asSequence().associate{it.name to z.getInputStream(it).use{input->input.readBytes()}}}
    private fun hash(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt()and 255)}
    @Test fun allOutcomesOriginalUnitsGTRevisionsAndChecksummedBytesLeaveTogether(){
        val base=Files.createTempDirectory("forestix-export-test-").toFile()
        try{
            val(a,c,t)=archive(base);val method=FieldReferenceMethod(id="tape",name="Tape, original",kind=FieldStudyKind.DBH,unit=FieldReferenceUnit.INCH)
            val gt=FieldReferenceObservation(targetId=t.id,method=method,inputValue=10.0,operatorId="OP-1");a.appendReference(gt,true)
            val revision=FieldReferenceObservation(targetId=t.id,method=method,inputValue=11.0,operatorId="OP-2",supersedesId=gt.id);a.appendReference(revision,true)
            val r=record(a,c,t);listOf(FieldAttemptOutcome.FAILED,FieldAttemptOutcome.CANCELLED,FieldAttemptOutcome.STARTED).forEach{record(a,c,t,it,FieldSelectionStatus.PENDING)}
            val result=FieldExportApplicationResult(r.id,"quick_history",FieldStudyKind.DBH,25.0,r.id)
            val journal=File(base,"original-journal.csv").apply{writeText("original,unchanged\n")}
            val out=FieldStudyExport.build(a,File(base,"raw"),listOf(result),listOf("quick-android.json" to "original application bytes".toByteArray()),File(base,"exports"),applicationFiles=listOf("original.csv" to journal))
            assertEquals(4,out.report.measurementCount);assertEquals(2,out.report.referenceCount)
            val files=files(out.file);assertArrayEquals("original application bytes".toByteArray(),files["application/quick-android.json"])
            assertArrayEquals("original,unchanged\n".toByteArray(),files["application/original.csv"])
            val inventory=Json.parseToJsonElement(files.getValue("file_inventory.json").toString(Charsets.UTF_8)).jsonArray
            assertEquals(files.size-1,inventory.size)
            for(item in inventory){val p=item.jsonObject;val bytes=files.getValue(p.getValue("path").jsonPrimitive.content)
                assertEquals(bytes.size.toLong(),p.getValue("bytes").jsonPrimitive.long);assertEquals(hash(bytes),p.getValue("sha256").jsonPrimitive.content)}
            val csv=files.getValue("reference_index.csv").toString(Charsets.UTF_8);assertTrue(csv.contains("Tape, original"));assertTrue(csv.contains(gt.id));assertTrue(csv.contains(revision.id));assertTrue(csv.contains("\"in\""))
            assertEquals(r,a.measurement(r.id));assertFalse(out.report.integrityPassed);assertTrue(out.report.issues.any{it.code=="unfinished_attempt"})
        }finally{base.deleteRecursively()}
    }
    @Test fun missingRawMismatchDuplicateAndCorruptRecordsRemainVisibleWithOriginalBytes(){
        val base=Files.createTempDirectory("forestix-export-test-").toFile()
        try{
            val(a,c,t)=archive(base);val first=record(a,c,t);val r=a.updateMeasurement(first.id){it.copy(rawStatus=FieldRawStatus.SAVED,rawBundleId=first.id)}
            val raw=File(base,"raw");val bundle=File(raw,r.id).apply{mkdirs()}
            val manifest=buildJsonObject{put("measurement_id",newFieldId());put("kind","height");put("dbh",buildJsonObject{put("rgb_file","missing.jpg")})}.toString().toByteArray()
            File(bundle,"manifest.json").writeBytes(manifest)
            val corrupt=File(a.root,"measurements/${newFieldId()}.json");corrupt.writeText("broken-original")
            val original=File(a.root,"measurements/${r.id.uppercase()}.json");File(a.root,"measurements/duplicate/${r.id}.json").apply{parentFile!!.mkdirs();writeBytes(original.readBytes())}
            val out=FieldStudyExport.build(a,raw,emptyList(),outputDirectory=File(base,"exports"));val codes=out.report.issues.map{it.code}.toSet()
            assertTrue(codes.containsAll(listOf("corrupt_metadata","duplicate_measurement_id","raw_identity_mismatch","raw_file_missing","measurement_raw_binding_mismatch","application_result_missing_or_duplicate")))
            val files=files(out.file);assertArrayEquals(manifest,files["raw-captures/${r.id}/manifest.json"]);assertEquals("broken-original",files.getValue("field-study/measurements/${corrupt.name}").toString(Charsets.UTF_8));assertEquals("broken-original",corrupt.readText())
            assertFalse(out.report.integrityPassed)
        }finally{base.deleteRecursively()}
    }
    @Test fun legacyBundleAndChecksumsAreAuditedWithoutValueTimeOrTreeNumberFallback(){
        val base=Files.createTempDirectory("forestix-export-test-").toFile()
        try{
            val(a,c,t)=archive(base);val r=record(a,c,t);val raw=File(base,"raw");val bundle=File(raw,newFieldId()).apply{mkdirs()};File(bundle,"input.bin").writeBytes(byteArrayOf(1,2,3))
            val manifest=buildJsonObject{put("kind","dbh");put("result_live",buildJsonObject{put("value",25)});put("capture_evidence",buildJsonObject{
                put("measurement_id",r.id);put("frames",JsonArray(listOf(buildJsonObject{put("depth",buildJsonObject{put("file","input.bin");put("bytes",3);put("sha256","0".repeat(64))})})))})}
            File(bundle,"manifest.json").writeText(manifest.toString())
            val out=FieldStudyExport.build(a,raw,listOf(FieldExportApplicationResult(newFieldId(),"quick_history",FieldStudyKind.DBH,25.0)),outputDirectory=File(base,"exports"))
            assertTrue(out.report.issues.map{it.code}.containsAll(listOf("legacy_raw_identity_unrecorded","raw_evidence_identity_mismatch","raw_without_measurement","raw_file_checksum_mismatch","application_result_missing_or_duplicate")))
        }finally{base.deleteRecursively()}
    }
    @Test fun sourceChangeAndSymlinkRefuseExportAndRemoveOnlyOwnedOutput(){
        val base=Files.createTempDirectory("forestix-export-test-").toFile()
        try{
            val(a,_,_)=archive(base);val raw=File(base,"raw");val bundle=File(raw,newFieldId()).apply{mkdirs()};val file=File(bundle,"payload.bin");file.writeBytes(byteArrayOf(1,2,3));val output=File(base,"exports")
            assertThrows(Exception::class.java){FieldStudyExport.build(a,raw,emptyList(),outputDirectory=output){file.writeBytes(byteArrayOf(4,5))}}
            assertArrayEquals(byteArrayOf(4,5),file.readBytes());assertTrue(output.listFiles()!!.isEmpty())
            Files.createSymbolicLink(File(bundle,"outside").toPath(),base.toPath())
            assertThrows(Exception::class.java){FieldStudyExport.build(a,raw,emptyList(),outputDirectory=output)};assertTrue(output.listFiles()!!.isEmpty())
            assertThrows(Exception::class.java){FieldStudyExport.build(a,raw,emptyList(),outputDirectory=raw)}
        }finally{base.deleteRecursively()}
    }
    @Test fun exactLinkedEvidenceOriginalReferenceScopeAndUIFlowExportWithoutReplaying(){
        val base=Files.createTempDirectory("forestix-export-test-").toFile()
        try{
            val(a,c,t)=archive(base);val gt=FieldReferenceObservation(targetId=t.id,method=c.referenceMethods[0],inputValue=25.0,operatorId="OP-1");a.appendReference(gt,true)
            val scope=FieldMeasurementScope(configuration=c,target=t,references=listOf(gt));val id=newFieldId()
            fun duration(s:Double)=FieldUIFlowDuration(s,s,0.0,true)
            val r=FieldMeasurementRecord(id=id,sessionId=newFieldId(),treeId=t.treeId,targetId=t.id,targetSnapshot=t,kind=FieldStudyKind.DBH,attemptNumber=1,configuration=c,runtimeSettings=emptyMap(),outcome=FieldAttemptOutcome.RESULT,selection=FieldSelectionStatus.ACCEPTED,
                liveValue=25.0,liveSigma=1.0,resultRecordId=id,resultStore="quick_history",referenceIds=listOf(gt.id),measurementScope=scope,rawStatus=FieldRawStatus.SAVED,rawBundleId=id,
                uiFlowTiming=FieldUIFlowTiming(id=newFieldId(),kind=FieldStudyKind.DBH,openedAt="2026-01-01T00:00:00.000Z",current=duration(6.0),atResult=duration(3.0),atAccepted=duration(6.0)))
            a.createMeasurement(r);a.appendReference(FieldReferenceObservation(targetId=t.id,method=gt.method,inputValue=30.0,operatorId="OP-2",supersedesId=gt.id),true)
            val raw=File(base,"raw");val bundle=File(raw,id).apply{mkdirs()};val depth=byteArrayOf(0,0,-128,63);val confidence=byteArrayOf(2)
            File(bundle,"depth.bin").writeBytes(depth);File(bundle,"confidence.bin").writeBytes(confidence)
            fun proof(name:String,bytes:ByteArray)=buildJsonObject{put("file",name);put("bytes",bytes.size);put("sha256",hash(bytes))}
            val manifest=buildJsonObject{put("schema",2);put("kind","dbh");put("measurement_id",id);put("context",buildJsonObject{});put("settings",buildJsonObject{});put("result_live",buildJsonObject{put("value",25)})
                put("dbh",buildJsonObject{put("frames",JsonArray(listOf(buildJsonObject{put("depth_file","depth.bin");put("width",1);put("height",1);put("format","f32m")})))})
                put("capture_evidence",buildJsonObject{put("measurement_id",id);put("displayed_result",buildJsonObject{put("value",25)})
                    put("frames",JsonArray(listOf(buildJsonObject{put("depth",proof("depth.bin",depth));put("confidence",proof("confidence.bin",confidence))})))})}
            File(bundle,"manifest.json").writeText(manifest.toString())
            val output=FieldStudyExport.build(a,raw,listOf(FieldExportApplicationResult(id,"quick_history",FieldStudyKind.DBH,25.0,id)),outputDirectory=File(base,"exports"))
            assertTrue(output.report.issues.toString(),output.report.integrityPassed);val files=files(output.file)
            val exported=FieldStudyJson.measurement(files.getValue("field-study/measurements/${id.uppercase()}.json").toString(Charsets.UTF_8))
            assertEquals(r,exported);assertEquals(25.0,exported.measurementScope!!.references[0].inputValue,0.0)
            val csv=files.getValue("measurement_index.csv").toString(Charsets.UTF_8);assertTrue(csv.contains(r.uiFlowTiming!!.id));assertTrue(csv.contains("\"3.0\",\"6.0\""));assertTrue(csv.contains("\"mm\""))
            System.getenv("FORESTIX_FIELD_EXPORT_FIXTURE_OUT")?.let{output.file.copyTo(File(it))}
        }finally{base.deleteRecursively()}
    }
    @Test fun wrongTreeMissingReferenceAndRuntimeMismatchCannotHideBehindSimilarValues(){
        val base=Files.createTempDirectory("forestix-export-test-").toFile()
        try{
            val(a,initial,t)=archive(base);val c=initial.copy(protocolLocked=true,pinnedRuntimeSettings=mapOf("procedure" to "p1"));a.saveConfiguration(c,true)
            val other=FieldStudyTarget(c.studyId,"0008","008");a.saveTargets(listOf(t,other));val gt=FieldReferenceObservation(targetId=other.id,method=c.referenceMethods[0],inputValue=25.0,operatorId="OP-1");a.appendReference(gt,true)
            val id=newFieldId();val r=FieldMeasurementRecord(id=id,sessionId=newFieldId(),treeId=t.treeId,targetId=t.id,targetSnapshot=t,kind=FieldStudyKind.DBH,attemptNumber=1,configuration=c,runtimeSettings=mapOf("procedure" to "p2"),outcome=FieldAttemptOutcome.RESULT,selection=FieldSelectionStatus.ACCEPTED,
                liveValue=25.0,resultRecordId=id,resultStore="quick_history",referenceIds=listOf(gt.id,newFieldId()));a.createMeasurement(r)
            val output=FieldStudyExport.build(a,File(base,"raw"),listOf(FieldExportApplicationResult(id,"quick_history",FieldStudyKind.DBH,25.0,id)),outputDirectory=File(base,"exports"))
            assertTrue(output.report.issues.map{it.code}.containsAll(listOf("reference_binding_mismatch","reference_missing_or_duplicate","protocol_runtime_mismatch")));assertEquals(r,a.measurement(id))
        }finally{base.deleteRecursively()}
    }
    @Test fun changingApplicationSourceAndReservedPathsRefusePartialArchive(){
        val base=Files.createTempDirectory("forestix-export-test-").toFile()
        try{
            val(a,_,_)=archive(base);val journal=File(base,"journal.csv").apply{writeBytes(byteArrayOf(1,2,3))};val output=File(base,"exports")
            assertThrows(Exception::class.java){FieldStudyExport.build(a,File(base,"raw"),emptyList(),outputDirectory=output,applicationFiles=listOf("original.csv" to journal)){journal.writeBytes(byteArrayOf(8,9))}}
            assertArrayEquals(byteArrayOf(8,9),journal.readBytes());assertTrue(output.listFiles()!!.isEmpty())
            assertThrows(Exception::class.java){FieldStudyExport.build(a,File(base,"raw"),emptyList(),listOf("result_index.json" to byteArrayOf()),output)}
            assertTrue(output.listFiles()!!.isEmpty())
        }finally{base.deleteRecursively()}
    }
}
