package com.hcjeong.forestix.data.fieldstudy

import java.util.UUID

object FieldResultTargetBinding {
    fun resolve(resultIds:List<String>,resultStore:String,studyId:String,records:List<FieldMeasurementRecord>,targets:List<FieldStudyTarget>):FieldStudyTarget? {
        fun uuid(s:String):String{fieldRequire(validFieldUuid(s),"result UUID");return UUID.fromString(s).toString()}
        val ids=resultIds.map{uuid(it)}.toSet();val owners=HashSet<String>();var foreign=false
        if(ids.isEmpty())return null
        for(r in records){
            if(r.resultStore!=resultStore||r.outcome!=FieldAttemptOutcome.RESULT||r.selection !in listOf(FieldSelectionStatus.ACCEPTED,FieldSelectionStatus.SUPERSEDED))continue
            val row=r.resultRecordId?:continue;val owner=r.targetId?:continue;if(uuid(row) !in ids)continue
            if(resultStore=="quick_history"&&uuid(r.id)!=uuid(row))throw FieldStudyException("conflict","Quick result UUID does not match its measurement")
            if(r.studyId==studyId)owners.add(owner)else foreign=true
        }
        if(owners.size>1)throw FieldStudyException("conflict","Application row is linked to different targets")
        if(foreign&&owners.isNotEmpty())throw FieldStudyException("conflict","Application row spans different studies")
        val owner=owners.singleOrNull()?:run{
            if(foreign)throw FieldStudyException("conflict","Result belongs to another study; select that study before remeasurement")
            return null}
        return targets.firstOrNull{it.id==owner&&it.studyId==studyId}?.also{it.validate()}
            ?:throw FieldStudyException("missing","Original measurement target is not in the active catalog")
    }
}
object FieldMeasurementReplacementScope {
    fun matches(a:FieldMeasurementRecord,b:FieldMeasurementRecord):Boolean {
        if(a.studyId!=b.studyId||a.operatorId!=b.operatorId||a.kind!=b.kind||a.plannedRepeat!=b.plannedRepeat||
            a.configuration.protocolVersion!=b.configuration.protocolVersion||a.resultStore!=b.resultStore)return false
        if(a.targetId!=null||b.targetId!=null)return a.targetId==b.targetId
        if(a.runtimeSettings["application.plot_id"].isNullOrEmpty()||(a.runtimeSettings["application.tree_number"]?.toIntOrNull()?:0)<=0)return false
        return listOf("application.mode","application.project_id","application.plot_id","application.tree_number").all{a.runtimeSettings[it]==b.runtimeSettings[it]}
    }
}
