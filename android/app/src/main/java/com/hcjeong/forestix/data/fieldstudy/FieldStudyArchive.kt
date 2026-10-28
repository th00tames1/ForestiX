package com.hcjeong.forestix.data.fieldstudy

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale

data class FieldArchiveIssue(val file:String,val reason:String)
data class FieldArchiveScan<T>(val values:List<T>,val issues:List<FieldArchiveIssue>){
    val total:Int? get()=if(issues.any{it.reason=="directory_unreadable"})null else values.size+issues.size}

/** Durable, per-record journal. Use Dispatchers.IO at call sites; no camera/GPS dependency. */
class FieldStudyArchive(val root:File) {
    companion object{private val processLock=Any()}
    /** Export exact bytes under the journal lock. Never recover/repair the source. */
    fun copyReadOnlySnapshot(destination:File)=synchronized(processLock){
        val source=root.canonicalFile;val dest=destination.canonicalFile
        fieldRequire(!dest.exists()&&dest!=source&&!dest.path.startsWith(source.path+File.separator),"export snapshot destination")
        check(dest.mkdirs()){"Export snapshot unavailable"}
        FieldExportFileTree.files(root).forEach{f->val target=File(dest,f.path);check(target.parentFile!!.isDirectory||target.parentFile!!.mkdirs())
            f.file.inputStream().use{input->target.outputStream().use{input.copyTo(it)}}}
    }
    private fun write(text:String,file:File){
        val parent=file.parentFile?:throw FieldStudyException("invalid","Archive parent missing")
        if(!parent.isDirectory&&!parent.mkdirs())throw java.io.IOException("Archive directory unavailable")
        val temp=File.createTempFile(".field-",".tmp",parent)
        try{
            FileOutputStream(temp).use{it.write(text.toByteArray(Charsets.UTF_8));it.fd.sync()}
            try{Files.move(temp.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)}
            catch(_:AtomicMoveNotSupportedException){Files.move(temp.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING)}
        }finally{if(temp.exists())temp.delete()}
    }
    private fun file(id:String,folder:String):File{
        fieldRequire(validFieldUuid(id),"record UUID")
        return File(File(root,folder),id.uppercase(Locale.US)+".json")
    }
    fun loadConfiguration():FieldStudyConfiguration=synchronized(processLock){
        if(root.exists()&&!root.isDirectory)throw FieldStudyException("corrupt","Archive root is not a directory")
        val f=File(root,"configuration.json")
        if(!f.exists())FieldStudyConfiguration() else FieldStudyJson.configuration(f.readText())
    }
    fun saveConfiguration(configuration:FieldStudyConfiguration,developerMode:Boolean)=synchronized(processLock){
        configuration.validateTransition(loadConfiguration(),developerMode)
        write(FieldStudyJson.configurationJson(configuration).toString(),File(root,"configuration.json"))
    }
    fun bindProtocol(candidate:FieldProtocolBinding):FieldProtocolBinding=synchronized(processLock){
        candidate.validate();val f=File(File(root,"protocol_bindings"),candidate.id+".json")
        if(f.exists()){
            val old=FieldStudyJson.protocolBinding(f.readText());old.validate()
            if(old!=candidate)throw FieldStudyException("protocol_locked","Protocol settings changed. Restore the locked settings or use a new protocol version.")
            old
        }else{write(FieldStudyJson.protocolBindingJson(candidate).toString(),f);candidate}
    }
    private fun validateTargets(targets:List<FieldStudyTarget>){
        fieldRequire(targets.size<=10000&&targets.map{it.id}.toSet().size==targets.size,"target count or duplicate tree IDs")
        targets.forEach{it.validate()}
    }
    fun loadTargets():List<FieldStudyTarget> = synchronized(processLock){
        val f=File(root,"targets.json")
        val base=if(!f.exists())emptyList() else FieldStudyJson.targets(f.readText())
        val imports=scanImports()
        if(imports.issues.isNotEmpty())throw FieldStudyException("corrupt","Target import inventory is unreadable")
        (base+imports.values.flatMap{it.targets}).also{validateTargets(it)}
    }
    fun saveTargets(targets:List<FieldStudyTarget>)=synchronized(processLock){
        validateTargets(targets);val imports=scanImports()
        if(imports.issues.isNotEmpty())throw FieldStudyException("corrupt","Target import inventory is unreadable")
        val imported=imports.values.flatMap{it.targets};val nextById=targets.associateBy{it.id}
        if(imported.any{nextById[it.id]!=it})throw FieldStudyException("conflict","Imported target coordinates are immutable")
        val ids=imported.map{it.id}.toSet()
        write(FieldStudyJson.targetsJson(targets.filter{it.id !in ids}).toString(),File(root,"targets.json"))
    }
    fun scanImports():FieldArchiveScan<FieldTargetImportBatch> = scan("imports",FieldStudyJson::importBatch){b,name->
        b.validate(true);fieldRequire(b.id.uppercase(Locale.US)==name,"import file ID mismatch")}
    fun commitImport(batch:FieldTargetImportBatch,developerMode:Boolean)=synchronized(processLock){
        batch.validate(developerMode);val config=loadConfiguration()
        if(batch.studyId!=config.studyId||batch.protocolFingerprint!=config.protocolFingerprint)
            throw FieldStudyException("conflict","Configuration changed during import")
        val f=file(batch.id,"imports")
        if(f.exists()){
            if(FieldStudyJson.importBatch(f.readText())!=batch)throw FieldStudyException("conflict","Import UUID already exists")
            return@synchronized
        }
        validateTargets(loadTargets()+batch.targets)
        val refs=scanReferences()
        if(refs.issues.isNotEmpty()||(refs.values+batch.references).map{it.id}.toSet().size!=refs.values.size+batch.references.size)
            throw FieldStudyException("conflict","Reference import inventory")
        write(FieldStudyJson.importJson(batch).toString(),f)
    }
    fun appendReference(observation:FieldReferenceObservation,developerMode:Boolean)=synchronized(processLock){
        observation.validate(developerMode)
        val f=file(observation.id,"references")
        if(f.exists())throw FieldStudyException("conflict","Reference UUID already exists")
        if(loadTargets().none{it.id==observation.targetId})throw FieldStudyException("missing","Reference target missing")
        val inventory=scanReferences()
        if(inventory.issues.isNotEmpty())throw FieldStudyException("corrupt","Reference inventory is unreadable")
        if(inventory.values.any{it.id.equals(observation.id,ignoreCase=true)})throw FieldStudyException("conflict","Reference UUID already exists")
        val superseded=inventory.values.mapNotNull{it.supersedesId}.toSet()
        val active=inventory.values.filter{it.id !in superseded}
        if(observation.supersedesId==null&&active.any{it.targetId==observation.targetId&&it.method.id==observation.method.id&&
                it.plannedRepeat==observation.plannedRepeat&&it.referenceRepeat==observation.referenceRepeat})
            throw FieldStudyException("conflict","Reference repeat already exists; supersede it or use a new repeat number")
        observation.supersedesId?.let{parent->
            val old=inventory.values.firstOrNull{it.id==parent}?:throw FieldStudyException("missing","Superseded reference missing")
            if(old.targetId!=observation.targetId||old.method.id!=observation.method.id||old.method.kind!=observation.method.kind||
                old.plannedRepeat!=observation.plannedRepeat||old.referenceRepeat!=observation.referenceRepeat||
                parent in superseded)throw FieldStudyException("conflict","Reference supersession conflict")
        }
        write(FieldStudyJson.referenceJson(observation).toString(),f)
    }
    private fun <T> scan(folder:String,decode:(String)->T,validate:(T,String)->Unit):FieldArchiveScan<T> = synchronized(processLock){
        val dir=File(root,folder)
        if(!dir.exists())return@synchronized FieldArchiveScan(emptyList(),emptyList())
        val files=dir.listFiles()?.filter{it.extension=="json"}?.sortedBy{it.name}
            ?:return@synchronized FieldArchiveScan(emptyList(),listOf(FieldArchiveIssue(folder,"directory_unreadable")))
        val values=ArrayList<T>();val issues=ArrayList<FieldArchiveIssue>()
        for(f in files)try{val v=decode(f.readText());validate(v,f.nameWithoutExtension);values.add(v)}
            catch(e:Exception){issues.add(FieldArchiveIssue("$folder/${f.name}",e.toString()))}
        FieldArchiveScan(values,issues)
    }
    fun scanReferences():FieldArchiveScan<FieldReferenceObservation> = synchronized(processLock){
        val typed=scan("references",FieldStudyJson::reference){r,name->r.validate(true);fieldRequire(r.id.uppercase(Locale.US)==name,"reference file ID mismatch")}
        val imports=scanImports();val all=typed.values+imports.values.flatMap{it.references}
        val issues=typed.issues+imports.issues+if(all.map{it.id}.toSet().size!=all.size)listOf(FieldArchiveIssue("references","duplicate_reference_id"))else emptyList()
        FieldArchiveScan(all,issues)
    }
    fun scanMeasurements():FieldArchiveScan<FieldMeasurementRecord> = synchronized(processLock){
        try{recoverApplicationUndos()}catch(e:Exception){return@synchronized FieldArchiveScan(emptyList(),listOf(FieldArchiveIssue("application-undos",e.toString())))}
        scan("measurements",FieldStudyJson::measurement){r,name->r.validate();fieldRequire(r.id.uppercase(Locale.US)==name,"measurement file ID mismatch")}}
    fun createMeasurement(record:FieldMeasurementRecord)=synchronized(processLock){
        record.validate();val f=file(record.id,"measurements")
        if(record.revision!=0||f.exists())throw FieldStudyException("conflict","Measurement UUID already exists")
        write(FieldStudyJson.measurementJson(record).toString(),f)
    }
    fun measurement(id:String):FieldMeasurementRecord=synchronized(processLock){
        recoverApplicationUndos()
        val f=file(id,"measurements")
        if(!f.exists())throw FieldStudyException("missing","Measurement missing")
        FieldStudyJson.measurement(f.readText()).also{fieldRequire(it.id.equals(id,ignoreCase=true),"measurement file ID mismatch")}
    }
    fun updateMeasurement(id:String,expectedRevision:Int?=null,update:(FieldMeasurementRecord)->FieldMeasurementRecord):FieldMeasurementRecord=synchronized(processLock){
        val old=measurement(id)
        if(expectedRevision!=null&&expectedRevision!=old.revision)throw FieldStudyException("conflict","Stale measurement revision")
        val next=update(old)
        if(next.id!=old.id||next.sessionId!=old.sessionId||next.studyId!=old.studyId||next.treeId!=old.treeId||
            next.targetId!=old.targetId||next.targetSnapshot!=old.targetSnapshot||next.operatorId!=old.operatorId||next.kind!=old.kind||
            next.plannedRepeat!=old.plannedRepeat||next.attemptNumber!=old.attemptNumber||next.startedAt!=old.startedAt||
            next.configuration!=old.configuration||next.runtimeSettings!=old.runtimeSettings||next.measurementScope!=old.measurementScope||
            (old.measurementScope!=null&&next.referenceIds!=old.referenceIds)||next.events.take(old.events.size)!=old.events||
            (old.outcome!=FieldAttemptOutcome.STARTED&&next.outcome!=old.outcome)||
            (old.selection!=FieldSelectionStatus.PENDING&&next.selection!=old.selection&&
                !(old.selection==FieldSelectionStatus.ACCEPTED&&next.selection==FieldSelectionStatus.SUPERSEDED))||
            (old.liveValue!=null&&next.liveValue!=old.liveValue)||(old.liveSigma!=null&&next.liveSigma!=old.liveSigma)||
            (old.outcome!=FieldAttemptOutcome.STARTED&&next.ai!=old.ai)||
            (old.resultRecordId!=null&&(next.resultRecordId!=old.resultRecordId||next.resultStore!=old.resultStore))||
            (old.selection!=FieldSelectionStatus.PENDING&&next.referenceIds!=old.referenceIds)||
            (old.uiFlowTiming!=null&&next.uiFlowTiming?.continues(old.uiFlowTiming)!=true))
            throw FieldStudyException("conflict","Immutable capture metadata or terminal state")
        val revised=next.copy(revision=old.revision+1);revised.validate()
        write(FieldStudyJson.measurementJson(revised).toString(),file(id,"measurements"));revised
    }
    fun mergeSessionSnapshot(snapshot:FieldMeasurementRecord)=synchronized(processLock){
        if(!file(snapshot.id,"measurements").exists()){createMeasurement(snapshot.copy(revision=0));return@synchronized}
        updateMeasurement(snapshot.id){old->
            if(old.sessionId!=snapshot.sessionId||old.studyId!=snapshot.studyId||old.treeId!=snapshot.treeId||
                old.targetId!=snapshot.targetId||old.operatorId!=snapshot.operatorId||old.kind!=snapshot.kind||
                old.plannedRepeat!=snapshot.plannedRepeat||old.attemptNumber!=snapshot.attemptNumber||old.startedAt!=snapshot.startedAt||old.targetSnapshot!=snapshot.targetSnapshot||
                old.configuration!=snapshot.configuration||old.runtimeSettings!=snapshot.runtimeSettings||old.measurementScope!=snapshot.measurementScope||old.referenceIds!=snapshot.referenceIds)
                throw FieldStudyException("conflict","Capture snapshot identity")
            val mergeRaw=old.rawStatus==FieldRawStatus.PENDING||old.rawStatus==FieldRawStatus.NOT_REQUESTED
            old.copy(outcome=snapshot.outcome,selection=if((old.selection==FieldSelectionStatus.SUPERSEDED||old.wasApplicationUndone)&&snapshot.selection==FieldSelectionStatus.ACCEPTED)old.selection else snapshot.selection,failureReason=snapshot.failureReason,
                liveValue=snapshot.liveValue,liveSigma=snapshot.liveSigma,ai=snapshot.ai,timing=snapshot.timing,uiFlowTiming=snapshot.uiFlowTiming?:old.uiFlowTiming,
                referenceIds=snapshot.referenceIds,resultRecordId=snapshot.resultRecordId,resultStore=snapshot.resultStore,
                rawStatus=if(mergeRaw)snapshot.rawStatus else old.rawStatus,
                rawBundleId=if(mergeRaw)snapshot.rawBundleId else old.rawBundleId,
                rawFailureReason=if(mergeRaw)snapshot.rawFailureReason else old.rawFailureReason,
                events=old.events+snapshot.events.filter{it !in old.events})
        }
    }
    fun updateRawEvidence(id:String,status:FieldRawStatus,reason:String?,replayValue:Double?=null){
        updateMeasurement(id){it.copy(rawStatus=status,rawBundleId=id,rawFailureReason=reason,
            replayValue=replayValue?.takeIf{value->value.isFinite()&&value>0}?:it.replayValue,events=it.events+FieldMeasurementEvent("raw_${status.raw}",reason))}
    }
    fun supersedeAcceptedMeasurement(id:String,replacement:String):Boolean=synchronized(processLock){
        if(id.equals(replacement,true))return@synchronized false
        if(!file(id,"measurements").exists())return@synchronized false
        val previous=measurement(id)
        if(previous.selection!=FieldSelectionStatus.ACCEPTED)return@synchronized false
        val newer=measurement(replacement)
        if(newer.selection!=FieldSelectionStatus.ACCEPTED||newer.outcome!=FieldAttemptOutcome.RESULT||newer.resultRecordId==null)
            throw FieldStudyException("conflict","Replacement must be an accepted application result")
        if(!FieldMeasurementReplacementScope.matches(previous,newer)){
            updateMeasurement(id){it.copy(events=it.events+FieldMeasurementEvent("application_row_replaced_retained",replacement))}
            return@synchronized false
        }
        updateMeasurement(id){it.copy(selection=FieldSelectionStatus.SUPERSEDED,events=it.events+FieldMeasurementEvent("superseded",replacement))};true
    }
    fun recoverInterruptedAttempts():Int=synchronized(processLock){
        var count=0
        val prepared=scanResultSaves().values.filter{!it.completed}.map{it.id.lowercase()}.toSet()
        for(r in scanMeasurements().values)if(r.outcome==FieldAttemptOutcome.STARTED&&r.id.lowercase() !in prepared){
            updateMeasurement(r.id){it.copy(outcome=FieldAttemptOutcome.INTERRUPTED,failureReason="process_interrupted",
                events=it.events+FieldMeasurementEvent("interrupted","process_restart"))};count++
        };count
    }
    fun scanResultSaves():FieldArchiveScan<FieldResultSaveIntent> = scan("result-saves",FieldResultSaveIntent::decode){v,name->
        v.validate();fieldRequire(v.id.uppercase(Locale.US)==name,"Result save file UUID")}
    fun prepareResultSave(value:FieldResultSaveIntent)=synchronized(processLock){
        value.validate();val f=file(value.id,"result-saves")
        if(f.exists()){
            val old=FieldResultSaveIntent.decode(f.readText())
            if(!old.matches(value))throw FieldStudyException("conflict","Prepared result save changed")
        }else write(value.encode(),f)
    }
    fun completeResultSave(id:String,resultRecordId:String,resultStore:String)=synchronized(processLock){
        val f=file(id,"result-saves");if(!f.exists())return@synchronized
        val value=FieldResultSaveIntent.decode(f.readText());val r=measurement(id)
        if(!value.expected.id.equals(resultRecordId,true)||value.expected.store!=resultStore||r.resultRecordId?.equals(resultRecordId,true)!=true||
            r.resultStore!=resultStore||r.liveValue!=value.expected.value||FieldResultSaveIntent.capture(r)!=FieldResultSaveIntent.capture(value.snapshot)||
            !(r.selection in listOf(FieldSelectionStatus.ACCEPTED,FieldSelectionStatus.SUPERSEDED)||r.wasApplicationUndone))
            throw FieldStudyException("conflict","Result save confirmation does not match journal")
        if(!value.completed){
            applyQuickReplacementLineage(value)
            if(r.selection==FieldSelectionStatus.ACCEPTED&&r.events.none{it.action=="application_row_replaced_retained"})for(old in scanMeasurements().values)if(old.id!=id&&old.selection==FieldSelectionStatus.ACCEPTED&&
                old.kind==value.expected.kind&&old.resultStore==resultStore&&old.resultRecordId?.equals(resultRecordId,true)==true&&
                old.events.none{it.action=="application_row_replaced_retained"&&it.detail==id})supersedeAcceptedMeasurement(old.id,id)
            write(value.copy(completed=true).encode(),f)
        }
    }
    private fun applyQuickReplacementLineage(intent:FieldResultSaveIntent){
        val sources=intent.replacements.map{it.result}+intent.inventoryReplacement?.results.orEmpty().filter{it.kind==intent.expected.kind}
        if(sources.isEmpty())return
        val newer=measurement(intent.id)
        if(!intent.hasCommittedBinding(newer))return
        for(source in sources){
            val oldId=source.measurementId?.takeIf(::validFieldUuid)?:continue
            if(!file(oldId,"measurements").exists())continue
            val old=measurement(oldId)
            if(old.outcome!=FieldAttemptOutcome.RESULT||old.resultRecordId==null)continue
            if(old.kind!=source.kind||old.resultStore!=source.store||!old.resultRecordId.equals(source.id,true)||old.liveValue!=source.value)
                throw FieldStudyException("conflict","Replacement journal identity mismatch")
            if(old.selection!=FieldSelectionStatus.ACCEPTED)continue
            val matches=FieldMeasurementReplacementScope.matches(old,newer)
            if(!matches&&old.events.any{it.action=="application_row_replaced_retained"&&it.detail==intent.id})continue
            updateMeasurement(oldId){it.copy(selection=if(matches)FieldSelectionStatus.SUPERSEDED else it.selection,
                events=it.events+FieldMeasurementEvent(if(matches)"superseded"else"application_row_replaced_retained",intent.id))}
        }
    }
    fun completedReplacementProofs(intent:FieldResultSaveIntent):List<FieldExportApplicationResult> = synchronized(processLock){
        val inventory=scanResultSaves()
        if(inventory.issues.isNotEmpty())throw FieldStudyException("corrupt","Replacement receipt inventory")
        inventory.values.filter{it.completed&&file(it.id,"measurements").exists()&&it.hasCommittedBinding(measurement(it.id))}.flatMap{it.replacements.map{r->r.result}+it.inventoryReplacement?.results.orEmpty()}.filter{
            it.id.equals(intent.expected.id,true)&&it.store==intent.expected.store&&it.kind==intent.expected.kind}
    }
    fun reconcileCompletedReplacements()=synchronized(processLock){scanResultSaves().values.filter{it.completed}.forEach{applyQuickReplacementLineage(it)}}
    private fun reconcileInventoryReplacement(intent:FieldResultSaveIntent){
        if(intent.expected.store!="cruise")return
        val scan=scanResultSaves();if(scan.issues.isNotEmpty())throw FieldStudyException("corrupt","Inventory replacement receipts")
        scan.values.filter{newer->newer.completed&&newer.expected.kind==intent.expected.kind&&newer.inventoryReplacement?.results?.any{
            it.kind==intent.expected.kind&&it.measurementId?.equals(intent.id,true)==true}==true}.forEach{applyQuickReplacementLineage(it)}
    }
    /** Read-only native proof; no result mutation and no invented completion duration. */
    fun recoverResultSave(id:String,results:List<FieldExportApplicationResult>)=synchronized(processLock){
        val intent=FieldResultSaveIntent.decode(file(id,"result-saves").readText())
        if(intent.completed)return@synchronized
        reconcileInventoryReplacement(intent)
        val current=if(file(id,"measurements").exists())measurement(id)else null
        if(current!=null&&(current.selection in listOf(FieldSelectionStatus.ACCEPTED,FieldSelectionStatus.SUPERSEDED)||current.wasApplicationUndone)){
            completeResultSave(id,intent.expected.id,intent.expected.store);return@synchronized
        }
        if(!intent.proves(results))throw FieldStudyException("conflict",if(results.isEmpty())"application_result_missing"else"application_result_identity_mismatch")
        if(current!=null){
            if(current.selection!=FieldSelectionStatus.PENDING||current.outcome !in listOf(FieldAttemptOutcome.STARTED,FieldAttemptOutcome.RESULT))
                throw FieldStudyException("conflict","Prepared attempt was retired")
            if(current.outcome==FieldAttemptOutcome.RESULT){
                if(FieldResultSaveIntent.capture(current)!=FieldResultSaveIntent.capture(intent.snapshot))throw FieldStudyException("conflict","Prepared attempt changed")
            }else mergeSessionSnapshot(intent.snapshot)
        }else mergeSessionSnapshot(intent.snapshot)
        updateMeasurement(id){it.copy(selection=FieldSelectionStatus.ACCEPTED,resultRecordId=intent.expected.id,resultStore=intent.expected.store,
            events=it.events+FieldMeasurementEvent("result_save_recovered","acceptance_time_unrecorded"))}
        reconcileInventoryReplacement(intent)
        completeResultSave(id,intent.expected.id,intent.expected.store)
    }
    /** Only after a successful conditional inventory restore/delete. */
    fun validateApplicationUndo(operationId:String,resultRecordId:String,measurementIds:List<String>)=synchronized(processLock){
        val inventory=scanMeasurements()
        if(inventory.issues.isNotEmpty())throw FieldStudyException("corrupt","Undo measurement inventory")
        FieldApplicationUndoBatch.prepare(operationId,resultRecordId,measurementIds,inventory.values);Unit
    }
    fun undoApplicationMeasurements(operationId:String,resultRecordId:String,measurementIds:List<String>):List<FieldMeasurementRecord> = synchronized(processLock){
        recoverApplicationUndos();val f=file(operationId,"application-undos")
        if(f.exists()){
            val old=FieldApplicationUndoBatch.decode(f.readText())
            if(!old.resultRecordId.equals(resultRecordId,true)||old.measurementIds.map{it.lowercase()}.toSet()!=measurementIds.map{it.lowercase()}.toSet())
                throw FieldStudyException("conflict","Undo operation UUID reused")
            return@synchronized old.changes.map{measurement(it.after.id)}
        }
        val inventory=scanMeasurements()
        if(inventory.issues.isNotEmpty())throw FieldStudyException("corrupt","Undo measurement inventory")
        val batch=FieldApplicationUndoBatch.prepare(operationId,resultRecordId,measurementIds,inventory.values)
        write(batch.encode(),f);recoverApplicationUndos();batch.changes.map{measurement(it.after.id)}
    }
    private fun recoverApplicationUndos(){
        val dir=File(root,"application-undos");if(!dir.exists())return
        val files=dir.listFiles()?.filter{it.extension=="json"}?.sortedBy{it.name}
            ?:throw FieldStudyException("corrupt","Undo directory unreadable")
        for(f in files){
            val batch=FieldApplicationUndoBatch.decode(f.readText())
            fieldRequire(batch.id.uppercase(Locale.US)==f.nameWithoutExtension,"Undo file UUID")
            if(batch.completed)continue
            for(c in batch.changes){
                val target=file(c.before.id,"measurements");val current=FieldStudyJson.measurement(target.readText())
                if(current!=c.before&&current!=c.after)throw FieldStudyException("conflict","Interrupted Undo journal changed")
            }
            batch.changes.forEach{write(FieldStudyJson.measurementJson(it.after).toString(),file(it.after.id,"measurements"))}
            write(batch.copy(completed=true).encode(),f)
        }
    }
    fun scanInventoryUndoIntents():FieldArchiveScan<FieldInventoryUndoIntent> = scan("inventory-undos",FieldInventoryUndoIntent::decode){v,name->
        v.validate();fieldRequire(v.id.uppercase(Locale.US)==name,"Inventory Undo file UUID")}
    fun prepareInventoryUndo(id:String,saved:com.hcjeong.forestix.data.cruise.Tree,original:com.hcjeong.forestix.data.cruise.Tree?,measurementIds:List<String>):FieldInventoryUndoIntent=synchronized(processLock){
        val candidate=FieldInventoryUndoIntent(id,FieldCruiseTreeUpdate.snapshot(saved),original?.let(FieldCruiseTreeUpdate::snapshot),measurementIds.toList()).also{it.validate()}
        val f=file(id,"inventory-undos")
        if(f.exists()){
            val old=FieldInventoryUndoIntent.decode(f.readText())
            if(!old.matches(id,saved,original,measurementIds))throw FieldStudyException("conflict","Inventory Undo UUID reused")
            return@synchronized old
        }
        val inventory=scanInventoryUndoIntents()
        if(inventory.issues.isNotEmpty())throw FieldStudyException("corrupt","Inventory Undo intent inventory")
        if(inventory.values.any{it.saved.id==saved.id&&it.phase in listOf(FieldInventoryUndoPhase.PREPARED,FieldInventoryUndoPhase.APPLICATION_APPLIED)})
            throw FieldStudyException("conflict","Another Undo is pending for this inventory row")
        if(measurementIds.isNotEmpty())validateApplicationUndo(id,saved.id.toString(),measurementIds)
        write(candidate.encode(),f);candidate
    }
    fun transitionInventoryUndo(id:String,phase:FieldInventoryUndoPhase,reason:String?=null):FieldInventoryUndoIntent=synchronized(processLock){
        val f=file(id,"inventory-undos");val old=FieldInventoryUndoIntent.decode(f.readText())
        if(old.phase==phase)return@synchronized old
        if(!(old.phase==FieldInventoryUndoPhase.PREPARED&&phase in listOf(FieldInventoryUndoPhase.APPLICATION_APPLIED,FieldInventoryUndoPhase.REFUSED)||
            old.phase==FieldInventoryUndoPhase.APPLICATION_APPLIED&&phase==FieldInventoryUndoPhase.COMPLETE))throw FieldStudyException("conflict","Inventory Undo phase transition")
        val next=old.copy(phase=phase,failureReason=reason).also{it.validate()};write(next.encode(),f);next
    }
}
