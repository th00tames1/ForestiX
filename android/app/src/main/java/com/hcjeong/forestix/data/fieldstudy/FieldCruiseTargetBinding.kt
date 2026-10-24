package com.hcjeong.forestix.data.fieldstudy

import java.util.UUID

data class FieldInventoryRow(val id:String,val plotId:String,val treeNumber:Int,val deleted:Boolean=false)
data class FieldCruiseTargetScope(val treeNumber:Int,val existingRowId:String?,val sourceMeasurementIds:List<String>)

/** DBH/height share an inventory row UUID, not a measurement UUID. No label matching. */
object FieldCruiseTargetBinding {
    fun resolve(target:FieldStudyTarget,plotId:String,rows:List<FieldInventoryRow>,records:List<FieldMeasurementRecord>):FieldCruiseTargetScope{
        target.validate()
        fun uuid(s:String):String{fieldRequire(validFieldUuid(s),"inventory UUID");return UUID.fromString(s).toString()}
        val plot=uuid(plotId);val byId=HashMap<String,FieldInventoryRow>();val plots=HashMap<String,String>()
        for(row in rows){val id=uuid(row.id);if(byId.put(id,row)!=null)throw FieldStudyException("conflict","Duplicate inventory UUID");plots[id]=uuid(row.plotId)}
        val matches=HashSet<String>();val sources=HashSet<String>();val owners=HashMap<String,MutableSet<String>>()
        for(record in records){
            if(record.resultStore!="cruise"||record.outcome!=FieldAttemptOutcome.RESULT||
                record.selection !in listOf(FieldSelectionStatus.ACCEPTED,FieldSelectionStatus.SUPERSEDED))continue
            val id=record.resultRecordId?.let{uuid(it)}?:continue;val row=byId[id]?:continue
            if(plots[id]!=plot||row.deleted||record.studyId!=target.studyId)continue
            record.targetId?.let{owners.getOrPut(id){HashSet()}.add(it)}
            if(record.targetId==target.id){matches.add(id);sources.add(uuid(record.id))}
        }
        if(matches.size>1)throw FieldStudyException("conflict","Target is linked to multiple inventory rows")
        matches.singleOrNull()?.let{id->val row=byId.getValue(id)
            if(owners[id]!=setOf(target.id)||row.treeNumber<=0||byId.any{(otherId,other)->otherId!=id&&plots[otherId]==plot&&!other.deleted&&other.treeNumber==row.treeNumber})
                throw FieldStudyException("conflict","Inventory row owner or local number is ambiguous")
            return FieldCruiseTargetScope(row.treeNumber,id,sources.sorted())}
        val maximum=byId.filterKeys{plots[it]==plot}.values.maxOfOrNull{it.treeNumber}?:0
        fieldRequire(maximum<Int.MAX_VALUE,"inventory number capacity")
        return FieldCruiseTargetScope(maxOf(1,maximum+1),null,emptyList())
    }
}
