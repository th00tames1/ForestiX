package com.hcjeong.forestix.data.fieldstudy

import java.util.UUID

data class FieldApplicationReading(val id:String,val kind:FieldStudyKind?,val plotId:String?,val treeNumber:Int?)
data class FieldTargetLocalScope(val treeNumber:Int,val existing:Boolean,val sourceResultIds:List<String>)

/** No coordinate, name, timestamp or value heuristics. */
object FieldTargetApplicationBinding {
    fun resolve(target:FieldStudyTarget,plotId:String?,readings:List<FieldApplicationReading>,records:List<FieldMeasurementRecord>):FieldTargetLocalScope {
        target.validate()
        fun uuid(value:String):String {
            fieldRequire(validFieldUuid(value),"application UUID")
            return UUID.fromString(value).toString()
        }
        val plot=plotId?.let{uuid(it)}
        val rows=HashMap<String,FieldApplicationReading>();val plots=HashMap<String,String?>()
        for(row in readings){val id=uuid(row.id)
            if(rows.put(id,row)!=null)throw FieldStudyException("conflict","Duplicate application result UUID")
            plots[id]=row.plotId?.let{uuid(it)}}
        val owners=HashMap<String,String>();val numbers=HashSet<Int>();val sources=HashSet<String>()
        val links=ArrayList<Pair<FieldMeasurementRecord,FieldApplicationReading>>()
        for(r in records){
            if(r.resultStore!="quick_history"||r.outcome!=FieldAttemptOutcome.RESULT||r.selection !in listOf(FieldSelectionStatus.ACCEPTED,FieldSelectionStatus.SUPERSEDED))continue
            val id=r.resultRecordId?.let{uuid(it)}?:continue;val row=rows[id]?:continue
            if(id!=uuid(r.id)||row.kind!=r.kind)throw FieldStudyException("conflict","Application result UUID/kind does not match its measurement")
            r.targetId?.let{owner->val previous=owners.put(id,owner)
                if(previous!=null&&previous!=owner)throw FieldStudyException("conflict","Result UUID belongs to multiple targets")}
            if(plots[id]!=plot)continue
            links.add(r to row)
            if(r.studyId!=target.studyId||r.targetId!=target.id)continue
            val number=row.treeNumber?.takeIf{it>0}?:throw FieldStudyException("conflict","Linked result has no valid local tree number")
            numbers.add(number);sources.add(id)
        }
        if(numbers.size>1)throw FieldStudyException("conflict","Target is linked to multiple local trees in this plot")
        numbers.singleOrNull()?.let{number->
            if(links.any{(r,row)->r.studyId==target.studyId&&r.targetId!=null&&r.targetId!=target.id&&row.treeNumber==number})
                throw FieldStudyException("conflict","Local tree is linked to another target in this study")
            return FieldTargetLocalScope(number,true,sources.sorted())}
        val maximum=readings.mapNotNull{it.treeNumber}.maxOrNull()?:0
        fieldRequire(maximum<Int.MAX_VALUE,"local tree number capacity")
        return FieldTargetLocalScope(maxOf(1,maximum+1),false,emptyList())
    }
}
