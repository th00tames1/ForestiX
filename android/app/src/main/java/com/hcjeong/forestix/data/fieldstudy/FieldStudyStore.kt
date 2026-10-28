package com.hcjeong.forestix.data.fieldstudy

import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.hcjeong.forestix.data.cruise.TreeRepository

data class FieldStudyState(val configuration:FieldStudyConfiguration=FieldStudyConfiguration(),
    val targets:List<FieldStudyTarget> = emptyList(),val referenceObservations:List<FieldReferenceObservation> = emptyList(),
    val issues:List<FieldArchiveIssue> = emptyList(),val loaded:Boolean=false,val error:String?=null,val selectedTargetId:String?=null,
    val measurements:List<FieldMeasurementRecord> = emptyList(),val modelFingerprint:String?=null,
    val unresolvedUndoMeasurementIds:Set<String> = emptySet(),val inventoryUndoInventoryVerified:Boolean=true){
    val activeTargets:List<FieldStudyTarget> get()=targets.filter{it.studyId==configuration.studyId}
    val selectedTarget:FieldStudyTarget? get()=activeTargets.firstOrNull{it.id==selectedTargetId}
    private val adoptedRecords by lazy(LazyThreadSafetyMode.PUBLICATION){if(inventoryUndoInventoryVerified)measurements.filter{it.id.lowercase() !in unresolvedUndoMeasurementIds}else emptyList()}
    val completedTargetIds:Set<String> by lazy(LazyThreadSafetyMode.PUBLICATION){FieldTargetProgress.completed(configuration.studyId,configuration.plannedRepeat,adoptedRecords)}
    val overlayTargets:List<FieldStudyTarget> by lazy(LazyThreadSafetyMode.PUBLICATION){FieldTargetProgress.visible(configuration,targets,adoptedRecords)}
    fun references(targetId:String,kind:FieldStudyKind,plannedRepeat:Int=configuration.plannedRepeat):List<FieldReferenceObservation>{
        val superseded=referenceObservations.mapNotNull{it.supersedesId}.toSet()
        return referenceObservations.filter{it.targetId==targetId&&it.method.kind==kind&&it.id !in superseded&&
            (it.plannedRepeat==null||it.plannedRepeat==plannedRepeat)}.sortedWith(compareBy<FieldReferenceObservation>{it.method.name}.thenBy{it.referenceRepeat})
    }
}

class FieldStudyStore(root:File,private val inventoryUndoRepository:TreeRepository?=null,
    private val resultSaveReader:(suspend (FieldResultSaveIntent)->List<FieldExportApplicationResult>)?=null) {
    val archive=FieldStudyArchive(root)
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val mutations=Mutex()
    private val journalGuard=Any()
    private var journalTail:Job?=null
    private val evidenceGuard=Any()
    private val evidenceJobs=HashSet<Job>()
    private val _state=MutableStateFlow(FieldStudyState())
    val state:StateFlow<FieldStudyState> = _state.asStateFlow()
    init{scope.launch{reload(true)}}
    fun selectTarget(id:String?){
        _state.update{old->
            if(id!=null&&old.activeTargets.none{it.id==id})throw FieldStudyException("missing","Selected target missing")
            old.copy(selectedTargetId=id)}
    }
    suspend fun reload(recoverInterrupted:Boolean=false)=withContext(Dispatchers.IO){mutations.withLock{
        val baselineIds=_state.value.measurements.mapTo(HashSet()){it.id}
        try{
            val undoIssues=inventoryUndoRepository?.let{FieldInventoryUndoCoordinator(it,archive).recover()}?:emptyList()
            val intents=archive.scanInventoryUndoIntents()
            val unavailable=if(inventoryUndoRepository==null)intents.values.filter{it.phase!=FieldInventoryUndoPhase.COMPLETE}.map{
                FieldArchiveIssue("inventory-undos/${it.id}","inventory_repository_unavailable")}else emptyList()
            val saves=archive.scanResultSaves();val saveIssues=saves.issues.toMutableList()
            if(recoverInterrupted){
                var remaining=saves.values.filter{!it.completed};val failures=HashMap<String,String>()
                while(remaining.isNotEmpty()){
                    val next=ArrayList<FieldResultSaveIntent>()
                    for(intent in remaining)try{
                        val reader=resultSaveReader?:throw FieldStudyException("missing","application_result_reader")
                        val native=reader(intent);val proofs=if(FieldResultSaveIntent.usesNativeProof(intent.expected,native))native else
                            FieldResultSaveIntent.proofCandidates(intent.expected,native,archive.completedReplacementProofs(intent))
                        archive.recoverResultSave(intent.id,proofs);failures.remove(intent.id)
                    }catch(e:Exception){next+=intent;failures[intent.id]=e.message?:"Result recovery failed"}
                    if(next.size==remaining.size)break;remaining=next
                }
                saveIssues+=failures.map{FieldArchiveIssue("result-saves/${it.key}",it.value)}
                try{archive.reconcileCompletedReplacements()}catch(e:Exception){saveIssues+=FieldArchiveIssue("result-saves",e.message?:"Replacement lineage unresolved")}
            }else{
                saveIssues+=saves.values.filter{!it.completed}.map{FieldArchiveIssue("result-saves/${it.id}","application_save_pending")}
            }
            if(recoverInterrupted)archive.recoverInterruptedAttempts()
            val c=archive.loadConfiguration().frozenSnapshot();val targets=frozenFieldList(archive.loadTargets().map{it.frozenSnapshot()});val refs=archive.scanReferences();val records=archive.scanMeasurements()
            _state.update{old->val selected=old.selectedTargetId?.takeIf{id->targets.any{it.id==id&&it.studyId==c.studyId}}
                old.copy(configuration=c,targets=targets,referenceObservations=refs.values,issues=(refs.issues+records.issues+undoIssues+intents.issues+unavailable+saveIssues+old.issues.filter{it.file.startsWith("raw-captures")}).distinct(),
                    loaded=true,error=null,selectedTargetId=selected,measurements=FieldMeasurementSnapshot.merge(records.values,old.measurements,baselineIds),
                    unresolvedUndoMeasurementIds=intents.values.flatMap{it.unresolvedMeasurementIds}.toSet(),inventoryUndoInventoryVerified=intents.issues.isEmpty())}
        }catch(e:Exception){_state.update{it.copy(loaded=true,error=e.message?:"Field-study archive unavailable")}}
    }}
    suspend fun performInventoryUndo(id:String,saved:com.hcjeong.forestix.data.cruise.Tree,original:com.hcjeong.forestix.data.cruise.Tree?,measurementIds:List<String>){
        awaitPendingWrites()
        val repository=inventoryUndoRepository?:throw FieldStudyException("missing","Inventory Undo repository")
        try{withContext(NonCancellable+Dispatchers.IO){FieldInventoryUndoCoordinator(repository,archive).execute(id,saved,original,measurementIds)}}
        finally{withContext(NonCancellable){reload()}}
    }
    suspend fun saveConfiguration(next:FieldStudyConfiguration,developerMode:Boolean)=withContext(Dispatchers.IO){mutations.withLock{
        val snapshot=next.frozenSnapshot();archive.saveConfiguration(snapshot,developerMode)
        _state.update{old->val selected=old.selectedTargetId?.takeIf{id->old.targets.any{it.id==id&&it.studyId==snapshot.studyId}}
            old.copy(configuration=snapshot,selectedTargetId=selected,error=null)}
    }}
    suspend fun saveTargets(next:List<FieldStudyTarget>)=withContext(Dispatchers.IO){mutations.withLock{
        val snapshot=frozenFieldList(next.map{it.frozenSnapshot()});archive.saveTargets(snapshot)
        _state.update{old->val selected=old.selectedTargetId?.takeIf{id->snapshot.any{it.id==id&&it.studyId==old.configuration.studyId}}
            old.copy(targets=snapshot,selectedTargetId=selected)}
    }}
    suspend fun appendReference(reference:FieldReferenceObservation,developerMode:Boolean)=withContext(Dispatchers.IO){mutations.withLock{
        archive.appendReference(reference,developerMode)
        _state.update{it.copy(referenceObservations=it.referenceObservations+reference)}
    }}
    suspend fun commitImport(batch:FieldTargetImportBatch,developerMode:Boolean)=withContext(Dispatchers.IO){mutations.withLock{
        archive.commitImport(batch,developerMode);val targets=archive.loadTargets();val refs=archive.scanReferences()
        _state.update{it.copy(targets=targets,referenceObservations=refs.values,issues=refs.issues)}
    }}
    fun loadModelFingerprint(open:()->java.io.InputStream){scope.launch{
        val digest=runCatching{open().use{input->val hash=java.security.MessageDigest.getInstance("SHA-256");val buffer=ByteArray(65536)
            while(true){val n=input.read(buffer);if(n<0)break;hash.update(buffer,0,n)}
            hash.digest().joinToString(""){"%02x".format(it.toInt()and 255)}}}.getOrNull()
        _state.update{it.copy(modelFingerprint=digest)}
    }}
    suspend fun awaitPendingWrites(){synchronized(journalGuard){journalTail}?.join()}
    fun launchCaptureEvidence(block:suspend ()->Unit):Job{
        val job=scope.launch(Dispatchers.Main.immediate,start=CoroutineStart.LAZY){block()}
        synchronized(evidenceGuard){evidenceJobs.add(job)}
        job.invokeOnCompletion{synchronized(evidenceGuard){evidenceJobs.remove(job)}}
        job.start();return job
    }
    suspend fun awaitCaptureEvidence(){
        while(true){
            val pending=synchronized(evidenceGuard){evidenceJobs.toList()}
            if(pending.isEmpty())return
            pending.joinAll()
        }
    }
    fun enqueueMeasurementWrite(id:String,allowMissing:Boolean=false,operation:()->Unit,completion:(Result<FieldMeasurementRecord?>)->Unit){
        synchronized(journalGuard){val previous=journalTail
            journalTail=scope.launch{
                previous?.join()
                val result=runCatching{operation()
                    try{archive.measurement(id)}catch(e:FieldStudyException){if(allowMissing&&e.code=="missing")null else throw e}}
                result.onSuccess{record->if(record!=null)_state.update{old->old.copy(
                    measurements=old.measurements.filter{it.id!=record.id}+record,
                    issues=old.issues.filter{it.file!="measurements/${record.id}"})}}
                completion(result)
            }
        }
    }
    fun reportMeasurementWriteFailure(id:String,reason:String){
        val issue=FieldArchiveIssue("measurements/$id",reason)
        _state.update{if(issue in it.issues)it else it.copy(issues=it.issues+issue)}
    }
    fun reportInfrastructureIssues(next:List<FieldArchiveIssue>){_state.update{it.copy(issues=(it.issues+next).distinct())}}
}
