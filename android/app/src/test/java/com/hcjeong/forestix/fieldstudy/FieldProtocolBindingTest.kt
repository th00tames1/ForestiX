package com.hcjeong.forestix.fieldstudy

import com.hcjeong.forestix.data.fieldstudy.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.File

class FieldProtocolBindingTest{
    private fun config()=FieldStudyConfiguration(enabled=true,studyId="SYNTHETIC",operatorId="TEST",protocolLocked=true,pinnedRuntimeSettings=FieldProtocolBinding.policy)
    private fun runtime(mode:String="sensor")=mapOf("platform" to "android","input_mode" to mode,"app_version" to "1.0","app_build" to "17",
        "measurement_source" to "arcore_depth","dbh_estimator_epoch" to "9","dbh_method" to "chord","dbh_chord_algorithm" to "silhouette",
        "capture_frame_count" to "1","depth_sampling_fraction" to FieldFingerprint.number(.8),"depth_sampling_statistic" to "median",
        "depth_surface_correction" to "cylinder_median_v1","model_sha256" to "a".repeat(64),"calibration.depth_noise_mm" to "noise",
        "calibration.vio_drift_fraction" to "drift","calibration.alpha" to "alpha","calibration.beta" to "beta","calibration.epoch" to "0",
        "calibration.depth_discontinuity_m" to "discontinuity","height_method" to "vio_walkoff_tangent","height_procedure_version" to "1","application.tree_number" to "3")
    @Test fun reopenedArchiveNeverRepinsChangedSettings(){
        val root=Files.createTempDirectory("forestix-protocol-").toFile()
        try{
            val c=config();val r=runtime();val first=FieldProtocolBinding.create(c,FieldStudyKind.DBH,r)
            assertEquals(first,FieldStudyArchive(root).bindProtocol(first))
            for(key in listOf("model_sha256","dbh_method","dbh_chord_algorithm","capture_frame_count","depth_sampling_fraction","calibration.beta","app_build")){
                val next=FieldProtocolBinding.create(c,FieldStudyKind.DBH,r+(key to if(key=="model_sha256")"b".repeat(64)else"changed"))
                assertEquals(first.id,next.id);assertThrows(Exception::class.java){FieldStudyArchive(root).bindProtocol(next)}
                assertEquals(first,FieldStudyJson.protocolBinding(File(root,"protocol_bindings/${first.id}.json").readText()))
            }
        }finally{root.deleteRecursively()}
    }
    @Test fun contextOperatorRepeatAndGpsAreNotProcedureParameters(){
        val c=config();val first=FieldProtocolBinding.create(c,FieldStudyKind.DBH,runtime())
        assertEquals(first,FieldProtocolBinding.create(c.copy(operatorId="OTHER",plannedRepeat=2),FieldStudyKind.DBH,
            runtime()+mapOf("application.tree_number" to "4","gps_accuracy" to "9999")))
        assertNotEquals(first.id,FieldProtocolBinding.create(c.copy(protocolVersion="2"),FieldStudyKind.DBH,runtime()).id)
    }
    @Test fun sharedProcedureNormalizesPlatformArcNameButNotSampling(){
        val c=config();val ios=FieldProtocolBinding.create(c,FieldStudyKind.DBH,runtime()+mapOf("platform" to "ios","dbh_method" to "partialArcCircleFit"))
        val r=runtime()+mapOf("dbh_method" to "arc","model_sha256" to "b".repeat(64),"calibration.beta" to "other")
        val android=FieldProtocolBinding.create(c,FieldStudyKind.DBH,r)
        assertNotEquals(ios.id,android.id);assertEquals(ios.procedureFingerprint,android.procedureFingerprint)
        assertNotEquals(ios.procedureFingerprint,FieldProtocolBinding.create(c,FieldStudyKind.DBH,r+("depth_sampling_fraction" to FieldFingerprint.number(1.0))).procedureFingerprint)
    }
    @Test fun heightAndTypedAreIndependentAndUnavailableModelCannotPinDbh(){
        val c=config();val h=FieldProtocolBinding.create(c,FieldStudyKind.HEIGHT,runtime())
        val r=runtime()+mapOf("model_sha256" to "unavailable","dbh_method" to "arc")
        assertTrue(h.matches(c,FieldStudyKind.HEIGHT,r));assertThrows(Exception::class.java){FieldProtocolBinding.create(c,FieldStudyKind.DBH,r)}
        val typed=FieldProtocolBinding.create(c,FieldStudyKind.DBH,r+("input_mode" to "typed"))
        assertEquals(4,typed.settings.size);assertNotEquals(typed.id,h.id)
        assertFalse(h.matches(c,FieldStudyKind.HEIGHT,r+("calibration.vio_drift_fraction" to "new")))
    }
    @Test fun legacyVersionCannotBeSilentlyRepinnedAndRecordRoundTrips(){
        val c=config().copy(pinnedRuntimeSettings=mapOf("epoch" to "9"))
        assertEquals(c,FieldProtocolBinding.preparing(c,c))
        val next=FieldProtocolBinding.preparing(c.copy(protocolVersion="2"),c);assertEquals(FieldProtocolBinding.policy,next.pinnedRuntimeSettings)
        val r=FieldMeasurementRecord(sessionId=newFieldId(),treeId="T",kind=FieldStudyKind.DBH,attemptNumber=1,configuration=next,runtimeSettings=runtime())
        assertFalse(r.protocolConforms)
        val bound=r.copy(protocolBinding=FieldProtocolBinding.create(next,FieldStudyKind.DBH,r.runtimeSettings))
        assertTrue(bound.protocolConforms);assertEquals(bound,FieldStudyJson.measurement(FieldStudyJson.measurementJson(bound).toString()))
        assertFalse(bound.copy(runtimeSettings=runtime()+("capture_frame_count" to "5")).protocolConforms)
    }
    @Test fun corruptBaselineNeverOverwritten(){
        val root=Files.createTempDirectory("forestix-protocol-corrupt-").toFile()
        try{val b=FieldProtocolBinding.create(config(),FieldStudyKind.DBH,runtime());val archive=FieldStudyArchive(root)
            archive.bindProtocol(b);val file=File(root,"protocol_bindings/${b.id}.json");file.writeText("partial")
            assertThrows(Exception::class.java){archive.bindProtocol(b)};assertEquals("partial",file.readText())
        }finally{root.deleteRecursively()}
    }
    @Test fun guideHeightLocksNewDbhButPreservesHistoricalAndIndependentBindings(){
        val c=config();val oldRuntime=runtime();val old=FieldProtocolBinding.create(c,FieldStudyKind.DBH,oldRuntime)
        val decoded=FieldStudyJson.protocolBinding(FieldStudyJson.protocolBindingJson(old).toString())
        decoded.validate();assertTrue(decoded.matches(c,FieldStudyKind.DBH,oldRuntime))
        var current=oldRuntime+("breast_height_guide_height_m" to FieldFingerprint.number(1.37))
        val baseline=FieldProtocolBinding.create(c,FieldStudyKind.DBH,current)
        assertFalse(old.matches(c,FieldStudyKind.DBH,current));assertNotEquals(old.procedureFingerprint,baseline.procedureFingerprint)
        assertEquals(FieldFingerprint.number(1.37),baseline.settings["breast_height_guide_height_m"])
        for(meters in listOf(1.30,1.40)){
            current=oldRuntime+("breast_height_guide_height_m" to FieldFingerprint.number(meters))
            assertFalse(baseline.matches(c,FieldStudyKind.DBH,current))
            assertNotEquals(baseline.procedureFingerprint,FieldProtocolBinding.create(c,FieldStudyKind.DBH,current).procedureFingerprint)
        }
        for(bad in listOf("","1.40",FieldFingerprint.number(0.0),FieldFingerprint.number(-1.0),FieldFingerprint.number(Double.POSITIVE_INFINITY),FieldFingerprint.number(Double.NaN))){
            current=oldRuntime+("breast_height_guide_height_m" to bad)
            assertThrows(Exception::class.java){FieldProtocolBinding.create(c,FieldStudyKind.DBH,current)}
        }
        assertEquals(FieldProtocolBinding.create(c,FieldStudyKind.HEIGHT,oldRuntime),FieldProtocolBinding.create(c,FieldStudyKind.HEIGHT,current))
        assertEquals(4,FieldProtocolBinding.create(c,FieldStudyKind.DBH,current+("input_mode" to "typed")).settings.size)
    }
    @Test fun completeExportContainsOriginalBaselineAndAuditsMissingProof(){
        val root=Files.createTempDirectory("forestix-protocol-export-").toFile()
        try{
            val archive=FieldStudyArchive(File(root,"field"));val c=config();val r=runtime("typed");archive.saveConfiguration(c,true)
            val b=archive.bindProtocol(FieldProtocolBinding.create(c,FieldStudyKind.DBH,r))
            val record=FieldMeasurementRecord(sessionId=newFieldId(),treeId="T",kind=FieldStudyKind.DBH,attemptNumber=1,configuration=c,
                runtimeSettings=r,protocolBinding=b,outcome=FieldAttemptOutcome.FAILED,failureReason="synthetic")
            archive.createMeasurement(record);val file=File(archive.root,"protocol_bindings/${b.id}.json");val bytes=file.readBytes()
            val first=FieldStudyExport.build(archive,File(root,"raw"),emptyList(),outputDirectory=File(root,"exports"))
            assertFalse(first.report.issues.any{it.code.startsWith("protocol_")})
            java.util.zip.ZipFile(first.file).use{zip->assertArrayEquals(bytes,zip.getInputStream(zip.getEntry("field-study/protocol_bindings/${b.id}.json")).use{it.readBytes()})}
            assertTrue(file.delete())
            val second=FieldStudyExport.build(archive,File(root,"raw"),emptyList(),outputDirectory=File(root,"exports"))
            assertTrue(second.report.issues.any{it.code=="protocol_baseline_missing_or_mismatch"});assertTrue(archive.measurement(record.id).protocolConforms)
        }finally{root.deleteRecursively()}
    }
    @Test fun actualCoordinatorDeniesBeforeRetiringResultAndDetectsMidCaptureChanges()=runBlocking<Unit>{
        val root=Files.createTempDirectory("forestix-protocol-coordinator-").toFile()
        try{
            val store=FieldStudyStore(root);withTimeout(5000){while(!store.state.value.loaded)delay(2)};store.saveConfiguration(config(),true)
            val capture=FieldCaptureCoordinator(store,FieldStudyKind.DBH,clock={1.0});capture.configure(runtime(),"tree:3")
            val id=capture.tryBegin(false,false)!!;capture.finish(id,25.0,null,true,null,FieldAIProvenance())
            val original=capture.displayRecord.value
            capture.configure(runtime()+("capture_frame_count" to "5"),"tree:3")
            assertNull(capture.tryBegin(false,false));assertEquals(id,capture.currentId);assertEquals(original,capture.displayRecord.value)
            assertFalse(capture.continueCapture());assertNotNull(capture.error.value)
            capture.configure(runtime()+("gps_accuracy" to "9999"),"tree:3")
            assertTrue(capture.continueCapture());assertNotNull(capture.tryBegin(false,false))
            store.awaitPendingWrites()
            assertEquals(FieldSelectionStatus.DISCARDED,store.archive.measurement(id).selection)
            assertTrue(store.archive.measurement(capture.currentId!!).protocolConforms)
            store.saveConfiguration(config().copy(enabled=false),true)
            capture.configure(runtime()+("calibration.beta" to "changed"),"tree:3")
            val off=capture.tryBegin(false,false);assertNotNull(off);assertNull(capture.displayRecord.value)
            assertNull(capture.recordedMeasurementId(off!!));store.awaitPendingWrites()
        }finally{root.deleteRecursively()}
    }
}
