package com.hcjeong.forestix.data.fieldstudy

/** Pure screen/target lifecycle. No IO, estimator, GPS or AR dependency. */
class FieldMeasurementSession(now:Double,val sessionId:String=newFieldId()){
    fun updateUIFlow(evidence:FieldUIFlowTiming){
        val record=current?.takeIf{it.configuration.recordTiming}?:return
        if(!evidence.continues(record.uiFlowTiming))throw FieldStudyException("conflict","UI flow snapshot transition")
        current=record.copy(uiFlowTiming=evidence).also{it.validate()}
    }
    var currentId:String?=null;private set
    var current:FieldMeasurementRecord?=null;private set
    var attemptNumber=0;private set
    private val clock=FieldMeasurementClock(now)
    fun setForeground(foreground:Boolean,now:Double){clock.setForeground(foreground,now)
        current?.let{if(it.configuration.recordTiming)current=it.copy(timing=clock.snapshot(now))}}
    fun begin(id:String,configuration:FieldStudyConfiguration,target:FieldStudyTarget?,kind:FieldStudyKind,
        runtime:Map<String,String>,references:List<String>,rawRequested:Boolean,aiEnabled:Boolean,typed:Boolean,now:Double,scope:FieldMeasurementScope?=null,
        protocolBinding:FieldProtocolBinding?=null){
        fieldRequire(validFieldUuid(id),"attempt UUID")
        current?.let{if(it.selection==FieldSelectionStatus.PENDING&&it.outcome in listOf(FieldAttemptOutcome.STARTED,FieldAttemptOutcome.RESULT))
            throw FieldStudyException("conflict","Unfinished attempt must be retired before a new one")}
        clock.beginAttempt(now);attemptNumber++;currentId=id
        if(!configuration.enabled){current=null;return}
        configuration.validate();target?.let{it.validate();fieldRequire(it.studyId==configuration.studyId,"capture target study")}
        val raw=rawRequested&&!typed
        current=FieldMeasurementRecord(id=id,sessionId=sessionId,treeId=target?.treeId?:"unassigned:$id",targetId=target?.id,
            targetSnapshot=target?.frozenSnapshot(),kind=kind,attemptNumber=attemptNumber,configuration=configuration.frozenSnapshot(),runtimeSettings=frozenFieldMap(runtime),
            referenceIds=frozenFieldList(references),measurementScope=scope,protocolBinding=protocolBinding,rawStatus=if(raw)FieldRawStatus.PENDING else FieldRawStatus.NOT_REQUESTED,rawBundleId=if(raw)id else null,
            ai=FieldAIProvenance(enabled=aiEnabled&&!typed,reason=if(typed)"typed_measurement"else null),
            timing=if(configuration.recordTiming)clock.snapshot(now)else null,
            events=listOf(FieldMeasurementEvent("attempt_started",if(typed)"typed"else"sensor"))+
                if(target==null)listOf(FieldMeasurementEvent("target_unassigned"))else emptyList()).also{it.validate()}
    }
    fun finish(id:String,value:Double?,sigma:Double?,valid:Boolean,reason:String?,ai:FieldAIProvenance,now:Double){
        val record=current?.takeIf{it.id==id&&it.outcome==FieldAttemptOutcome.STARTED}?:return
        clock.noteResult(valid,now)
        val v=value?.takeIf{it.isFinite()&&it>0};val ok=valid&&v!=null
        current=record.copy(ai=ai,liveValue=v,liveSigma=sigma?.takeIf{it.isFinite()&&it>=0},
            outcome=if(ok)FieldAttemptOutcome.RESULT else FieldAttemptOutcome.FAILED,
            failureReason=if(ok)null else reason?:"no_valid_result",
            timing=if(record.configuration.recordTiming)clock.snapshot(now)else null,
            events=record.events+FieldMeasurementEvent(if(ok)"result_produced"else"attempt_failed",reason)).also{it.validate()}
    }
    fun refuse(id:String,reason:String,now:Double,ai:FieldAIProvenance?=null){
        val record=current?.takeIf{it.id==id&&it.outcome==FieldAttemptOutcome.STARTED}?:return
        val pending=record.rawStatus==FieldRawStatus.PENDING
        current=record.copy(outcome=FieldAttemptOutcome.FAILED,failureReason=reason,ai=ai?:record.ai,
            rawStatus=if(pending)FieldRawStatus.FAILED else record.rawStatus,
            rawFailureReason=if(pending)"capture_refused: $reason"else record.rawFailureReason,
            timing=if(record.configuration.recordTiming)clock.snapshot(now)else null,
            events=record.events+FieldMeasurementEvent("attempt_failed",reason)).also{it.validate()}
    }
    fun abandon(id:String,reason:String,now:Double){
        val record=current?.takeIf{it.id==id&&it.selection==FieldSelectionStatus.PENDING}?:return
        val cancelled=record.outcome==FieldAttemptOutcome.STARTED
        val failedRaw=cancelled&&record.rawStatus==FieldRawStatus.PENDING
        current=record.copy(outcome=if(cancelled)FieldAttemptOutcome.CANCELLED else record.outcome,
            failureReason=if(cancelled)reason else record.failureReason,selection=FieldSelectionStatus.DISCARDED,
            rawStatus=if(failedRaw)FieldRawStatus.FAILED else record.rawStatus,
            rawFailureReason=if(failedRaw)"capture_cancelled"else record.rawFailureReason,
            timing=if(record.configuration.recordTiming)clock.snapshot(now)else null,
            events=record.events+FieldMeasurementEvent("discarded",reason)).also{it.validate()}
    }
    fun saveFailed(id:String,reason:String,now:Double){
        val record=current?.takeIf{it.id==id&&it.selection==FieldSelectionStatus.PENDING}?:return
        current=record.copy(timing=if(record.configuration.recordTiming)clock.snapshot(now)else null,
            events=record.events+FieldMeasurementEvent("result_save_failed",reason))
    }
    fun noteStageRefusal(id:String,stage:String,reason:String,now:Double){
        val record=current?.takeIf{it.id==id&&it.outcome==FieldAttemptOutcome.STARTED}?:return
        current=record.copy(timing=if(record.configuration.recordTiming)clock.snapshot(now)else null,
            events=record.events+FieldMeasurementEvent("stage_refused","$stage: $reason"))
    }
    fun accept(id:String,resultRecordId:String,resultStore:String,now:Double){
        val record=current?.takeIf{it.id==id}?:return
        fieldRequire(validFieldUuid(resultRecordId)&&resultStore in listOf("quick_history","cruise"),"application result binding")
        if(record.outcome!=FieldAttemptOutcome.RESULT||record.liveValue==null)throw FieldStudyException("conflict","Cannot accept an invalid attempt")
        if(record.selection==FieldSelectionStatus.ACCEPTED)return
        if(record.selection!=FieldSelectionStatus.PENDING)throw FieldStudyException("conflict","Discarded attempt")
        clock.noteAccepted(now)
        current=record.copy(selection=FieldSelectionStatus.ACCEPTED,resultRecordId=resultRecordId,resultStore=resultStore,
            timing=if(record.configuration.recordTiming)clock.snapshot(now)else null,
            events=record.events+FieldMeasurementEvent("result_saved","$resultStore:$resultRecordId")).also{it.validate()}
    }
    val shouldPersistCurrent:Boolean get()=current?.let{it.configuration.recordFailedAttempts||it.outcome==FieldAttemptOutcome.RESULT}?:false
}
