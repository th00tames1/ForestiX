package com.hcjeong.forestix.data.fieldstudy

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** Screen identity synchronous; application-owned ordered IO survives navigation. */
class FieldCaptureCoordinator(private val store:FieldStudyStore,private val kind:FieldStudyKind,
    private val uiFlowRegistry:FieldUIFlowRegistry=FieldUIFlowRegistry.shared,
    private val clock:()->Double={android.os.SystemClock.elapsedRealtimeNanos()/1e9}){
    var uiFlow:FieldUIFlowClock?=FieldUIFlowClock(kind);private set
    private var hasAppeared=false
    private var session=FieldMeasurementSession(clock())
    var currentId:String?=null;private set
    private var runtime:Map<String,String> = emptyMap()
    private var contextKey:String?=null
    private var targetSessionKey:String?=null
    private fun targetKey(c:FieldStudyConfiguration,targetId:String?)="${c.studyId}:${c.plannedRepeat}:${targetId.orEmpty()}"
    private val recordingIds=HashSet<String>()
    private val failureGuard=Any();private val failures=HashMap<String,String>()
    private val _error=MutableStateFlow<String?>(null);val error=_error.asStateFlow()
    private val _displayRecord=MutableStateFlow<FieldMeasurementRecord?>(null);val displayRecord=_displayRecord.asStateFlow()
    private val _scopedTarget=MutableStateFlow<FieldStudyTarget?>(null);val scopedTarget=_scopedTarget.asStateFlow()
    private val _usesGlobal=MutableStateFlow(true);val usesGlobalTargetSelection=_usesGlobal.asStateFlow()
    private var hidden=false
    private var inheritedScope:FieldMeasurementScope?=null
    var measurementScope:FieldMeasurementScope?=null;private set
    fun previewReferences(kind:FieldStudyKind,developerMode:Boolean):List<FieldReferenceObservation>{
        if(!store.state.value.configuration.enabled||!store.state.value.configuration.showReferencesDuringMeasurement)return emptyList()
        (measurementScope?:inheritedScope)?.let{return if(it.configuration.studyId==store.state.value.configuration.studyId)FieldMeasurementReferences.visible(it,kind,developerMode)else emptyList()}
        val state=store.state.value
        return FieldMeasurementReferences.preview(state.configuration,_scopedTarget.value?.id?:state.selectedTargetId.takeIf{_usesGlobal.value},
            state.referenceObservations,kind,developerMode)
    }
    fun configure(runtime:Map<String,String>,contextKey:String,targetOverride:FieldStudyTarget?=null,useGlobalTarget:Boolean=true,uiFlow:FieldUIFlowClock?=null,scopeOverride:FieldMeasurementScope?=null){
        val targetScopeChanged=_scopedTarget.value?.id!=targetOverride?.id||_usesGlobal.value!=(useGlobalTarget&&targetOverride==null)||
            (scopeOverride!=null&&scopeOverride.id!=measurementScope?.id&&scopeOverride.id!=inheritedScope?.id)
        inheritedScope=scopeOverride
        val previousFlow=this.uiFlow
        val nextFlow=uiFlow?.takeIf{it.kind==kind}?:this.uiFlow?.takeIf{it.kind==kind}?:FieldUIFlowClock(kind)
        val flowChanged=previousFlow!==nextFlow
        this.runtime=runtime.toMap()
        _scopedTarget.value=targetOverride
        _usesGlobal.value=useGlobalTarget&&targetOverride==null
        if(this.contextKey!=null&&(this.contextKey!=contextKey||(flowChanged&&currentId!=null)||targetScopeChanged)){
            val inherited=session.current?.targetId
            close(if(flowChanged)"ui_flow_changed"else"next_application_tree");session=FieldMeasurementSession(clock());currentId=null;targetSessionKey=null;_displayRecord.value=null;measurementScope=null
            if(inherited!=null&&store.state.value.selectedTargetId==inherited)store.selectTarget(null)
        }
        this.contextKey=contextKey
        this.uiFlow=nextFlow
        if(hasAppeared&&flowChanged)uiFlowRegistry.track(nextFlow,clock())
    }
    fun selectedTargetChanged(){
        if(!_usesGlobal.value)return
        val state=store.state.value;val next=targetKey(state.configuration,state.selectedTargetId)
        if(targetSessionKey!=null&&targetSessionKey!=next&&session.current?.outcome!=FieldAttemptOutcome.STARTED){
            close("target_changed");session=FieldMeasurementSession(clock());currentId=null;targetSessionKey=next
            _displayRecord.value=null;measurementScope=null
        }
    }
    fun appeared(){hasAppeared=true;uiFlow?.let{uiFlowRegistry.track(it,clock())};if(hidden&&session.attemptNumber==0)session=FieldMeasurementSession(clock());hidden=false;foreground(true)}
    fun foreground(active:Boolean){session.setForeground(active,clock());persist()}
    fun tryBegin(rawRequested:Boolean,aiEnabled:Boolean,typed:Boolean=false):String?=try{begin(rawRequested,aiEnabled,typed)}
        catch(e:Exception){protocolFailure(e);null}
    private fun protocolFailure(e:Exception):String{
        val message="Capture not started: ${e.message}"
        synchronized(failureGuard){failures["protocol"]=message;_error.value=message};return message
    }
    fun continueCapture():Boolean{
        val record=session.current?:return true
        if(!record.configuration.protocolLocked||!record.configuration.enabled)return true
        val values=runtime+("input_mode" to (record.runtimeSettings["input_mode"]?:"sensor"))
        if(record.protocolBinding?.matches(record.configuration,kind,values)!=true){
            protocolFailure(FieldStudyException("protocol_locked","Protocol settings changed during capture. Restore the locked settings or retake with a new protocol version."))
            return false
        }
        return true
    }
    fun begin(rawRequested:Boolean,aiEnabled:Boolean,typed:Boolean=false):String{
        val live=store.state.value.configuration
        val config=if(live.enabled)(measurementScope?:inheritedScope)?.configuration?:live else live
        val values=runtime+("input_mode" to if(typed)"typed"else"sensor")
        val binding=if(config.enabled&&config.protocolLocked){
            if(config.pinnedRuntimeSettings!=FieldProtocolBinding.policy)throw FieldStudyException("protocol_locked",
                "Legacy protocol lock has no verified runtime baseline. Use a new protocol version.")
            val state=store.state.value
            if(!state.loaded||state.error!=null)throw FieldStudyException("missing","Field-study configuration unavailable")
            store.archive.bindProtocol(FieldProtocolBinding.create(config,kind,values))
        }else null
        synchronized(failureGuard){failures.remove("protocol");_error.value=failures.values.sorted().firstOrNull()}
        val id=UUID.randomUUID().toString()
        currentId?.let{session.abandon(it,"superseded_by_attempt",clock());persist()};currentId=id;_displayRecord.value=null
        runCatching{
            val state=store.state.value
            if(!state.loaded||state.error!=null)throw FieldStudyException("missing","Field-study configuration unavailable")
            (measurementScope?:inheritedScope)?.let{if(state.configuration.enabled&&it.configuration.studyId!=state.configuration.studyId)
                throw FieldStudyException("conflict","Study changed during capture scope")}
            inheritedScope?.let{if(it.target?.id!=_scopedTarget.value?.id)throw FieldStudyException("conflict","Inherited scope target mismatch")}
            val target=_scopedTarget.value?:state.selectedTarget.takeIf{_usesGlobal.value}
            if(!state.configuration.enabled)measurementScope=null
            else if(inheritedScope==null&&_usesGlobal.value&&measurementScope?.target?.id!=target?.id)measurementScope=null
            if(state.configuration.enabled&&measurementScope==null){
                measurementScope=inheritedScope?.also{it.validate()}?:state.configuration.takeIf{it.enabled}?.let{c->
                    val methods=c.referenceMethods.filter{it.enabled}.map{it.id}.toSet()
                    val refs=target?.let{t->listOf(FieldStudyKind.DBH,FieldStudyKind.HEIGHT).flatMap{state.references(t.id,it,c.plannedRepeat)}.filter{it.method.id in methods}}?:emptyList()
                    FieldMeasurementScope(configuration=c,target=target,references=refs)}
            }
            val config=measurementScope?.configuration?:state.configuration
            val frozenTarget=if(measurementScope!=null)measurementScope!!.target else target
            val refs=measurementScope?.references(kind)?.map{it.id}?:emptyList()
            val next=targetKey(config,frozenTarget?.id)
            if(targetSessionKey!=null&&targetSessionKey!=next)session=FieldMeasurementSession(clock())
            targetSessionKey=next
            session.begin(id,config,frozenTarget,kind,values,refs,rawRequested,aiEnabled,typed,clock(),measurementScope,binding);persist()
        }.onFailure{report(id,it)}
        return id
    }
    fun finish(id:String,value:Double?,sigma:Double?,valid:Boolean,reason:String?,ai:FieldAIProvenance){
        runCatching{session.finish(id,value,sigma,valid,reason,ai,clock());persist()}.onFailure{report(id,it)}}
    fun refuse(id:String,reason:String,ai:FieldAIProvenance?=null){
        runCatching{session.refuse(id,reason,clock(),ai);persist()}.onFailure{report(id,it)}}
    fun stageRefused(id:String,stage:String,reason:String){session.noteStageRefusal(id,stage,reason,clock());persist()}
    fun abandon(id:String,reason:String){runCatching{session.abandon(id,reason,clock());persist()}.onFailure{report(id,it)}}
    fun close(reason:String="screen_closed"){if(reason=="screen_closed")hidden=true;currentId?.let{abandon(it,reason)}}
    fun resultSaveFailed(id:String,reason:String){
        session.saveFailed(id,reason,clock());persist()
        synchronized(failureGuard){val message="Result NOT saved: $reason";failures["application:$id"]=message;_error.value=message}
    }
    fun resultSaved(id:String,resultRecordId:String,resultStore:String,superseded:List<String> = emptyList()){
        runCatching{session.accept(id,resultRecordId,resultStore,clock());
            synchronized(failureGuard){failures.remove("application:$id");_error.value=failures.values.sorted().firstOrNull()};persist()
            if(id in recordingIds){
                superseded.forEach{old->enqueue(old,true){store.archive.supersedeAcceptedMeasurement(old,id);Unit}}
                enqueue(id){store.archive.completeResultSave(id,resultRecordId,resultStore)}
            }}.onFailure{report(id,it)}}
    fun raw(id:String,status:FieldRawStatus,reason:String?=null,replayValue:Double?=null){
        if(id in recordingIds)enqueue(id){store.archive.updateRawEvidence(id,status,reason,replayValue)}}
    fun recordedMeasurementId(id:String):String?=id.takeIf{it in recordingIds}
    suspend fun prepareResultSave(id:String,resultRecordId:String,resultStore:String,value:Double,replacements:List<FieldQuickReplacementSnapshot> = emptyList(),
        inventoryReplacement:FieldInventoryReplacementSnapshot?=null){
        if(id !in recordingIds)return
        val snapshot=session.current?.takeIf{it.id==id}?:throw FieldStudyException("missing","Prepared attempt missing")
        val intent=FieldResultSaveIntent(id,snapshot,FieldExportApplicationResult(resultRecordId,resultStore,snapshot.kind,value,id),replacements=frozenFieldList(replacements),
            inventoryReplacement=inventoryReplacement?.copy(results=frozenFieldList(inventoryReplacement.results)))
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){store.archive.prepareResultSave(intent)}
    }
    private fun persist(){
        session.current?.takeIf{it.id==currentId&&it.configuration.recordTiming}?.let{current->
            runCatching{uiFlow?.evidence(clock(),current.uiFlowTiming,current.outcome==FieldAttemptOutcome.RESULT,
                current.selection in listOf(FieldSelectionStatus.ACCEPTED,FieldSelectionStatus.SUPERSEDED))?.let{session.updateUIFlow(it)}}
                .onFailure{report(current.id,it)}
        }
        _displayRecord.value=session.current?.takeIf{it.id==currentId}
        val record=session.current?.takeIf{session.shouldPersistCurrent&&it.id==currentId}?:return
        recordingIds.add(record.id);enqueue(record.id){store.archive.mergeSessionSnapshot(record)}
    }
    private fun enqueue(id:String,allowMissing:Boolean=false,operation:()->Unit){
        store.enqueueMeasurementWrite(id,allowMissing,operation){result->result.fold(
            onSuccess={record->if(record!=null)synchronized(failureGuard){failures.remove(id);_error.value=failures.values.sorted().firstOrNull()}},
            onFailure={report(id,it)})}
    }
    private fun report(id:String,cause:Throwable){
        val message="Field record NOT saved: ${cause.message?:cause.javaClass.simpleName}"
        synchronized(failureGuard){failures[id]=message;_error.value=message}
        store.reportMeasurementWriteFailure(id,cause.message?:message)
    }
}
