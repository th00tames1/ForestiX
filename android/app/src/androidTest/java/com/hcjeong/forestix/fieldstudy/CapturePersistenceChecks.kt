package com.hcjeong.forestix.fieldstudy

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import androidx.room.Room
import com.hcjeong.forestix.data.*
import com.hcjeong.forestix.data.fieldstudy.*
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID

/** Opt-in, synthetic-only native SQLite checks. Uses isolated in-memory/on-disk
 * databases and a fresh UUID cache directory; never opens or edits the user's database, raw
 * recordings, photographs, settings or surveyed targets. No AR/GPS is started. */
class CapturePersistenceChecks:Instrumentation(){
    private var recoveryPhase:String?=null
    private var recoveryToken:String?=null
    private var checkSelection:String?=null
    private var disposableToken:String?=null
    override fun onCreate(arguments:Bundle?){
        recoveryPhase=arguments?.getString("recovery_phase");recoveryToken=arguments?.getString("recovery_token")
        checkSelection=arguments?.getString("check_selection")
        disposableToken=arguments?.getString("disposable_token")
        super.onCreate(arguments);start()
    }
    override fun onStart(){
        if(checkSelection=="raw_ray_replay"){
            val result=Bundle()
            try{RawRayDepthReplayChecks.run(targetContext);result.putBoolean("all_passed",true)
                result.putString("checks","actual_sparse_ray_result,versioned_save,exact_raw_replay,missing_or_corrupt_source_refused,failed_attempt_preserved,no_user_data_access")
            }catch(e:Throwable){result.putBoolean("all_passed",false);result.putString("failure",e.stackTraceToString())}
            finish(if(result.getBoolean("all_passed"))Activity.RESULT_OK else Activity.RESULT_CANCELED,result)
            return
        }
        if(checkSelection=="dbh_candidate_comparison"){
            val result=Bundle()
            try{DbhCandidateComparisonChecks.run(targetContext);result.putBoolean("all_passed",true)
                result.putString("checks","five_same_input_candidates,version_one_compatibility,shadow_only_no_result_mutation,refused_attempt_comparison,original_byte_replay,CSV_integrity,diagnostic_failure_preserves_primary,whole_bundle_ZIP_inclusion,no_user_data_access")
            }catch(e:Throwable){result.putBoolean("all_passed",false);result.putString("failure",e.stackTraceToString())}
            finish(if(result.getBoolean("all_passed"))Activity.RESULT_OK else Activity.RESULT_CANCELED,result)
            return
        }
        if(checkSelection=="qualified_depth_replay"){
            val result=Bundle()
            try{QualifiedDepthReplayChecks.run(targetContext);result.putBoolean("all_passed",true)
                result.putString("checks","qualified_Raw_not_dense,versioned_original_input,exact_native_replay,missing_or_corrupt_original_fail_closed,refused_input_preservation,no_user_data_access")
            }catch(e:Throwable){result.putBoolean("all_passed",false);result.putString("failure",e.stackTraceToString())}
            finish(if(result.getBoolean("all_passed"))Activity.RESULT_OK else Activity.RESULT_CANCELED,result)
            return
        }
        if(checkSelection=="camera_input_lease"){
            val result=Bundle()
            try{CameraInputLeaseChecks.run();result.putBoolean("all_passed",true)
                result.putString("checks","real_offscreen_YUV_image,bit_identical_RGB_tensor,closed_before_inference,once_only_close,failed_preparation_release,no_camera_AR_or_user_data_access")
            }catch(e:Throwable){result.putBoolean("all_passed",false);result.putString("failure",e.stackTraceToString())}
            finish(if(result.getBoolean("all_passed"))Activity.RESULT_OK else Activity.RESULT_CANCELED,result)
            return
        }
        if(checkSelection=="dbh_depth_quality"){
            val result=Bundle()
            try{DbhDepthQualityChecks.run(targetContext);result.putBoolean("all_passed",true)
                result.putString("checks","actual_Android_JSON_and_recorder,raw_u16_and_real_confidence_roundtrip,unchanged_measurement,stale_age_preserved,malformed_and_wrong_ID_fail_closed,refusal_trace_only,no_user_data_access")
            }catch(e:Throwable){result.putBoolean("all_passed",false);result.putString("failure",e.stackTraceToString())}
            finish(if(result.getBoolean("all_passed"))Activity.RESULT_OK else Activity.RESULT_CANCELED,result)
            return
        }
        if(checkSelection=="connected_capture_deadline"){
            val result=Bundle()
            try{ConnectedCaptureDeadlineChecks.run();result.putBoolean("all_passed",true)
                result.putString("checks","ARM_native_like_timeout,input_ownership,no_overlapping_workers,late_output_discarded,recovery_after_native_finishes,no_user_data_access")
            }catch(e:Throwable){result.putBoolean("all_passed",false);result.putString("failure",e.stackTraceToString())}
            finish(if(result.getBoolean("all_passed"))Activity.RESULT_OK else Activity.RESULT_CANCELED,result)
            return
        }
        if(checkSelection=="navigation_safety"){
            val result=Bundle()
            try{NavigationSafetyChecks.run(this);result.putBoolean("all_passed",true)
                result.putString("checks","real_AndroidX_100_plot_roundtrips_2000_stale_backs,root_retention,nested_parent_retention,stale_async_callback,empty_stack_recovery")
            }catch(e:Throwable){result.putBoolean("all_passed",false);result.putString("failure",e.stackTraceToString())}
            finish(if(result.getBoolean("all_passed"))Activity.RESULT_OK else Activity.RESULT_CANCELED,result)
            return
        }
        if(checkSelection=="remote_ui"){
            val result=RemoteFieldUiChecks(this).run(disposableToken?:"")
            finish(if(result.getBoolean("all_passed"))Activity.RESULT_OK else Activity.RESULT_CANCELED,result)
            return
        }
        val result=Bundle()
        try{runBlocking{
            if(recoveryPhase!=null){
                val token=requireNotNull(recoveryToken)
                when(recoveryPhase){
                    "prepare_crash"->InventoryUndoRecoveryChecks.prepareAndCrash(targetContext,token)
                    "recover"->InventoryUndoRecoveryChecks.recoverAfterCrash(targetContext,token)
                    "operator_prepare_crash"->HeightRawPersistenceChecks.prepareAndCrash(targetContext,token)
                    "operator_recover"->HeightRawPersistenceChecks.recoverAfterCrash(targetContext,token)
                    else->error("Unknown synthetic recovery phase")
                }
                result.putString("checks",if(recoveryPhase!!.startsWith("operator_"))"actual_process_death_height_operator_recovery"else"actual_process_death_sqlite_journal_recovery")
                result.putBoolean("all_passed",true)
                return@runBlocking
            }
            val db=Room.inMemoryDatabaseBuilder(targetContext,ForestixDatabase::class.java).build()
            val root=File(targetContext.cacheDir,"field-capture-check-"+UUID.randomUUID())
            check(root.mkdirs()){"Synthetic test directory unavailable"}
            try{
                if(checkSelection=="complete_export"){
                    FieldRecordingMatrixChecks.runCompleteExport(targetContext,root)
                    result.putString("checks","corrupt_only_complete_ZIP,original_raw_bytes,DBH_height_reference_units,integrity_audit,actual_FileProvider_content_URI_MIME_and_read_bytes")
                    result.putBoolean("all_passed",true)
                    return@runBlocking
                }
                if(checkSelection=="recording_matrix"){
                    FieldRecordingMatrixChecks.run(targetContext,root)
                    result.putString("checks","sixteen_dbh_height_study_raw_timing_combinations,actual_raw_replay_sqlite_CSV_UUIDs,SQLite_reopen_exact_values,recording_off_optout,timing_off_optout")
                    result.putBoolean("all_passed",true)
                    return@runBlocking
                }
                if(checkSelection=="raw_depth_fallback"){
                    RawDepthFallbackChecks.run(targetContext,root)
                    result.putString("checks","f32_only_dbh,mixed_ordered_five_frames,exact_confidence,legacy_and_modern_replay,no_partial_subset,queued_GT_count_refusal,height_f32_and_rgb_without_depth")
                    result.putBoolean("all_passed",true)
                    return@runBlocking
                }
                if(checkSelection=="protocol_retention"){
                    QuickHistoryRetentionPersistenceChecks.run(targetContext,root)
                    FieldProtocolPersistenceChecks.run(targetContext,root)
                    result.putString("checks","native_sqlite_1001_retention_cas_rollback_reopen,protocol_first_use_reopen_refusal_preserves_result,protocol_export_original_bytes,recording_off_bypass")
                    result.putBoolean("all_passed",true)
                    return@runBlocking
                }
                val dao=db.dao();val plot=UUID.randomUUID()
                fun reading(id:UUID,value:Double)=QuickMeasureEntry(id=id,kind=MeasureKind.DBH,value=value,sigma=1.0,
                    confidenceRaw="green",method="synthetic-test",treeNumber=1,plotID=plot)
                val first=reading(UUID.randomUUID(),25.0)
                dao.saveCapturedEntry(EntryRow.from(first),false,plot.toString(),500)
                dao.saveCapturedEntry(EntryRow.from(first),false,plot.toString(),500)
                check(dao.allEntries().size==1){"Retry duplicated a result ID"}
                val second=reading(UUID.randomUUID(),26.0)
                val old=dao.saveCapturedEntry(EntryRow.from(second),true,plot.toString(),500)
                check(old.map{it.id}==listOf(first.id.toString())){"Replacement lost old result identity"}
                check(dao.allEntries().single().id==second.id.toString()){"Replacement did not reach SQLite"}
                val failed=reading(UUID.randomUUID(),27.0)
                db.openHelper.writableDatabase.execSQL("CREATE TRIGGER capture_test_failure BEFORE INSERT ON entries WHEN NEW.id='${failed.id}' BEGIN SELECT RAISE(ABORT,'synthetic capture write failure'); END")
                check(runCatching{dao.saveCapturedEntry(EntryRow.from(failed),true,plot.toString(),500)}.isFailure){"Synthetic failure did not execute"}
                check(dao.allEntries().single().id==second.id.toString()){"Failed replacement did not roll back old row"}
                val store=FieldStudyStore(root)
                withTimeout(5000){while(!store.state.value.loaded)delay(2)}
                store.saveConfiguration(FieldStudyConfiguration(enabled=true,studyId="SYNTHETIC",operatorId="TEST"),true)
                val target=FieldStudyTarget("SYNTHETIC","0003","0003")
                store.saveTargets(listOf(target));store.selectTarget(target.id)
                val capture=FieldCaptureCoordinator(store,FieldStudyKind.DBH)
                capture.configure(mapOf("application.tree_number" to "3"),"synthetic:3");capture.appeared()
                val id=capture.begin(false,false,true)
                capture.finish(id,28.0,null,true,null,FieldAIProvenance(reason="typed_measurement"))
                val saved=reading(UUID.fromString(id),28.0)
                dao.saveCapturedEntry(EntryRow.from(saved),false,plot.toString(),500)
                capture.resultSaved(id,saved.id.toString(),"quick_history");store.awaitPendingWrites()
                val journal=store.archive.measurement(id)
                check(journal.resultRecordId==saved.id.toString()&&journal.id==id){"Result/journal identity mismatch"}
                check(journal.selection==FieldSelectionStatus.ACCEPTED&&journal.timing?.acceptedElapsedSeconds!=null){"Accepted-save evidence missing"}
                check(journal.treeId=="0003"&&journal.targetSnapshot==target){"External tree ID was coerced or overwritten"}
                check(dao.allEntries().any{it.id==id&&it.value==journal.liveValue}){"Journal live value differs from SQLite"}
                check(capture.error.value==null){"Journal write failed"}
                RawAICaptureChecks.run(targetContext,root)
                RawAICaptureChecks.runBatch(targetContext,root)
                RawDepthFallbackChecks.run(targetContext,root)
                HeightRawPersistenceChecks.run(targetContext,root)
                LegacyIdentityPersistenceChecks.run(targetContext,root)
                LegacyCruiseIdentityPersistenceChecks.run(targetContext,root)
                ResultSavePersistenceChecks.run(targetContext,root)
                QuickHistoryRetentionPersistenceChecks.run(targetContext,root)
                CruiseInventoryWriteChecks.run(targetContext,root)
                InventoryUndoRecoveryChecks.run(targetContext,root)
                result.putString("checks","idempotent_save,replacement,rollback,result_id_link,timing,external_tree_id,live_value,raw_ai_attempt,raw_ai_cancel,raw_partial_preservation,raw_100_batch_exact_uuid_hashes_reopen,height_immediate_accept_pending_truth_reopen_exact_uuid_inputs,height_duplicate_preserves_evidence,sqlite_v9_to_v10_preserves_all_legacy_columns,identified_result_legacy_mutators_refused,identified_log_original_GT_preserved,cruise_conditional_create,cruise_compare_and_swap,cruise_undo,cruise_rollback,cruise_concurrent_number,inventory_undo_reopen_all_cuts,inventory_undo_new_row,inventory_undo_tombstone_conflict")
                result.putBoolean("all_passed",true)
            }finally{db.close();root.deleteRecursively()}
        };finish(Activity.RESULT_OK,result)}catch(e:Throwable){
            result.putBoolean("all_passed",false);result.putString("failure",e.stackTraceToString());finish(Activity.RESULT_CANCELED,result)
        }
    }
}
