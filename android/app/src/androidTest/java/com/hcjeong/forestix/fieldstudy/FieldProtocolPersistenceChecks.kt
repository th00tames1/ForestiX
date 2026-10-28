package com.hcjeong.forestix.fieldstudy

import android.content.Context
import com.hcjeong.forestix.data.fieldstudy.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.zip.ZipFile

/** Filesystem/coordinator proof on Android, exclusively in the runner's fresh
 * UUID cache directory. No default DB, settings, camera or positioning access. */
internal object FieldProtocolPersistenceChecks {
    suspend fun run(context:Context,parent:File){
        val root=File(parent,"protocol-runtime");check(root.mkdir())
        val store=FieldStudyStore(File(root,"field"))
        withTimeout(5000){while(!store.state.value.loaded)delay(2)}
        val config=FieldStudyConfiguration(enabled=true,studyId="SYNTHETIC",operatorId="TEST",
            protocolLocked=true,pinnedRuntimeSettings=FieldProtocolBinding.policy)
        store.saveConfiguration(config,true)
        val runtime=mapOf("platform" to "android","app_version" to "synthetic-1","app_build" to "17",
            "measurement_source" to "synthetic_depth","dbh_estimator_epoch" to "9","dbh_method" to "chord",
            "dbh_chord_algorithm" to "silhouette","capture_frame_count" to "1",
            "depth_sampling_fraction" to FieldFingerprint.number(.8),"depth_sampling_statistic" to "median",
            "depth_surface_correction" to "cylinder_median_v1","model_sha256" to "a".repeat(64),
            "calibration.depth_noise_mm" to FieldFingerprint.number(5.0),"calibration.vio_drift_fraction" to FieldFingerprint.number(.02),
            "calibration.alpha" to FieldFingerprint.number(0.0),"calibration.beta" to FieldFingerprint.number(1.0),
            "calibration.epoch" to "0","calibration.depth_discontinuity_m" to FieldFingerprint.number(.04))
        val capture=FieldCaptureCoordinator(store,FieldStudyKind.DBH)
        capture.configure(runtime,"synthetic:3")
        val first=checkNotNull(capture.tryBegin(false,false))
        capture.finish(first,25.0,1.0,true,null,FieldAIProvenance())
        val before=checkNotNull(capture.displayRecord.value);check(before.protocolConforms)
        val baseline=checkNotNull(before.protocolBinding)
        val baselineFile=File(store.archive.root,"protocol_bindings/${baseline.id}.json");val originalBytes=baselineFile.readBytes()
        capture.configure(runtime+("capture_frame_count" to "5"),"synthetic:3")
        check(capture.tryBegin(false,false)==null&&capture.currentId==first&&capture.displayRecord.value==before){"Refused start retired the prior result"}
        check(!capture.continueCapture()){"Changed runtime passed the continuation gate"}
        capture.configure(runtime+mapOf("application.tree_number" to "4","gps_accuracy" to "9999"),"synthetic:4")
        val next=checkNotNull(capture.tryBegin(false,false));check(next!=first)
        check(capture.displayRecord.value?.protocolBinding==baseline){"Tree/GPS change altered the protocol baseline"}
        capture.refuse(next,"synthetic_no_frame");store.awaitPendingWrites()
        val reopened=FieldStudyStore(store.archive.root)
        withTimeout(5000){while(!reopened.state.value.loaded)delay(2)}
        val again=FieldCaptureCoordinator(reopened,FieldStudyKind.DBH)
        again.configure(runtime+("model_sha256" to "b".repeat(64)),"synthetic:5")
        check(again.tryBegin(false,false)==null&&again.currentId==null){"Reopen silently repinned the model"}
        check(baselineFile.readBytes().contentEquals(originalBytes))
        val export=FieldStudyExport.build(reopened.archive,File(root,"raw"),emptyList(),outputDirectory=File(root,"exports"))
        check(export.report.issues.none{it.code.startsWith("protocol_")}){"Export lost protocol proof"}
        ZipFile(export.file).use{zip->
            val entry=checkNotNull(zip.getEntry("field-study/protocol_bindings/${baseline.id}.json"))
            check(zip.getInputStream(entry).use{it.readBytes()}.contentEquals(originalBytes))
        }
        baselineFile.writeText("synthetic partial write")
        again.configure(runtime,"synthetic:5");check(again.tryBegin(false,false)==null)
        check(baselineFile.readText()=="synthetic partial write"){"Corrupt baseline was overwritten"}
        // Restore only this owned synthetic fixture, never an application file.
        baselineFile.writeBytes(originalBytes)
        reopened.saveConfiguration(config.copy(enabled=false),true)
        val off=checkNotNull(again.tryBegin(false,false))
        check(again.displayRecord.value==null&&again.recordedMeasurementId(off)==null)
        reopened.awaitPendingWrites()
    }
}
