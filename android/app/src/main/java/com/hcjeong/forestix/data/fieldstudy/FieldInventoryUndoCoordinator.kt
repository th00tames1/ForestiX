package com.hcjeong.forestix.data.fieldstudy

import com.hcjeong.forestix.data.cruise.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException

/** Durable ordered recovery. No database migration or estimator/GPS access. */
class FieldInventoryUndoCoordinator(private val repository:TreeRepository,private val archive:FieldStudyArchive){
    companion object{private val mutex=Mutex()}
    suspend fun execute(id:String,saved:Tree,original:Tree?,measurementIds:List<String>)=mutex.withLock{
        resume(archive.prepareInventoryUndo(id,saved,original,measurementIds))
    }
    suspend fun recover():List<FieldArchiveIssue> = mutex.withLock{
        val scan=archive.scanInventoryUndoIntents();val issues=scan.issues.toMutableList()
        if(issues.isNotEmpty())return@withLock issues
        if(scan.values.filter{it.phase in listOf(FieldInventoryUndoPhase.PREPARED,FieldInventoryUndoPhase.APPLICATION_APPLIED)}.groupBy{it.saved.id}.values.any{it.size>1})
            return@withLock listOf(FieldArchiveIssue("inventory-undos","duplicate_pending_inventory_undo"))
        for(intent in scan.values.sortedWith(compareBy<FieldInventoryUndoIntent>{it.createdAt}.thenBy{it.id})){
            if(intent.phase==FieldInventoryUndoPhase.COMPLETE)continue
            try{resume(intent)}catch(e:CancellationException){throw e}catch(e:Exception){issues.add(FieldArchiveIssue("inventory-undos/${intent.id}",e.toString()))}
        }
        issues
    }
    private suspend fun resume(initial:FieldInventoryUndoIntent){
        var intent=initial
        if(intent.phase==FieldInventoryUndoPhase.COMPLETE)return
        if(intent.phase==FieldInventoryUndoPhase.REFUSED)throw FieldStudyException("conflict",intent.failureReason?:"Inventory Undo refused")
        if(intent.phase==FieldInventoryUndoPhase.PREPARED){
            val current=repository.readCurrent(intent.saved.id,true)
            if(current==intent.saved){
                try{if(intent.original!=null)repository.replaceIfUnchanged(requireNotNull(intent.original),intent.saved)else repository.deleteIfUnchanged(intent.saved)}
                catch(e:FieldStudyException){if(e.code=="conflict")archive.transitionInventoryUndo(intent.id,FieldInventoryUndoPhase.REFUSED,e.message);throw e}
            }else if(current!=intent.original){
                val reason="Inventory row changed; Undo recovery refused"
                archive.transitionInventoryUndo(intent.id,FieldInventoryUndoPhase.REFUSED,reason);throw FieldStudyException("conflict",reason)
            }
            intent=archive.transitionInventoryUndo(intent.id,FieldInventoryUndoPhase.APPLICATION_APPLIED)
        }
        if(intent.measurementIds.isNotEmpty())archive.undoApplicationMeasurements(intent.id,intent.saved.id.toString(),intent.measurementIds)
        archive.transitionInventoryUndo(intent.id,FieldInventoryUndoPhase.COMPLETE)
    }
}
