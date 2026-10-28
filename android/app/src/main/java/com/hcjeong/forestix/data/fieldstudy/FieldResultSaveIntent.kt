package com.hcjeong.forestix.data.fieldstudy

import kotlinx.serialization.json.*

/** Full immutable attempt, durably prepared before the native result write. */
data class FieldResultSaveIntent(val id:String,val snapshot:FieldMeasurementRecord,val expected:FieldExportApplicationResult,
    val completed:Boolean=false,val schema:Int=1,val replacements:List<FieldQuickReplacementSnapshot> = emptyList(),
    val inventoryReplacement:FieldInventoryReplacementSnapshot?=null){
    fun validate(){
        snapshot.validate()
        fieldRequire(schema==1&&validFieldUuid(id)&&id==snapshot.id&&snapshot.outcome==FieldAttemptOutcome.RESULT&&
            snapshot.selection==FieldSelectionStatus.PENDING&&snapshot.resultRecordId==null&&snapshot.resultStore==null&&
            expected.measurementId?.equals(id,true)==true&&validFieldUuid(expected.id)&&expected.store in listOf("quick_history","cruise")&&
            expected.kind==snapshot.kind&&expected.value==snapshot.liveValue&&!expected.deleted,"prepared application save")
        replacements.forEach{it.validate()};val rows=replacements.map{it.result.id.lowercase()}
        inventoryReplacement?.let{old->old.validate();fieldRequire(expected.store=="cruise"&&old.results.all{
            it.id.equals(expected.id,true)&&(it.kind!=expected.kind||it.measurementId?.equals(id,true)!=true)},"inventory replacement result scope")}
        fieldRequire(rows.size<=10000&&replacements.sumOf{it.payloadJSON.toByteArray(Charsets.UTF_8).size.toLong()}<=16*1024*1024&&rows.distinct().size==rows.size&&expected.id.lowercase() !in rows&&
            (replacements.isEmpty()||expected.store=="quick_history")&&replacements.all{it.result.kind==expected.kind&&it.result.measurementId?.equals(id,true)!=true},"replacement result identities")
    }
    fun matches(other:FieldResultSaveIntent)=expected==other.expected&&replacements==other.replacements&&inventoryReplacement==other.inventoryReplacement&&capture(snapshot)==capture(other.snapshot)
    fun proves(results:List<FieldExportApplicationResult>):Boolean{
        val actual=results.singleOrNull()?:return false
        return actual.id.equals(expected.id,true)&&actual.store==expected.store&&actual.kind==expected.kind&&
            actual.measurementId?.equals(id,true)==true&&actual.value==expected.value&&!actual.deleted
    }
    fun hasCommittedBinding(record:FieldMeasurementRecord)=record.outcome==FieldAttemptOutcome.RESULT&&
        record.selection in listOf(FieldSelectionStatus.ACCEPTED,FieldSelectionStatus.SUPERSEDED)&&record.resultStore==expected.store&&
        record.resultRecordId?.equals(expected.id,true)==true&&record.kind==expected.kind&&record.liveValue==expected.value&&capture(record)==capture(snapshot)
    fun proof(tree:com.hcjeong.forestix.data.cruise.Tree?):List<FieldExportApplicationResult>{
        tree?:return emptyList()
        val value=if(expected.kind==FieldStudyKind.DBH)tree.dbhCm.toDouble()else tree.heightM?.toDouble()?:return emptyList()
        return listOf(FieldExportApplicationResult(tree.id.toString(),"cruise",expected.kind,value,
            if(expected.kind==FieldStudyKind.DBH)tree.dbhMeasurementId else tree.heightMeasurementId,tree.deletedAt!=null))
    }
    fun encode():String=buildJsonObject{
        put("schema",schema);put("id",id);put("snapshot",FieldStudyJson.measurementJson(snapshot))
        put("expected",expected.json());put("completed",completed)
        if(replacements.isNotEmpty())put("replacements",JsonArray(replacements.map{it.json()}))
        inventoryReplacement?.let{put("inventory_replacement",it.json())}
    }.toString()
    companion object{
        fun proofCandidates(expected:FieldExportApplicationResult,native:List<FieldExportApplicationResult>,archived:List<FieldExportApplicationResult>):List<FieldExportApplicationResult>{
            if(usesNativeProof(expected,native))return native
            val proofs=archived.filter{it.store==expected.store&&it.kind==expected.kind&&it.id.equals(expected.id,true)&&it.measurementId?.equals(expected.measurementId,true)==true}.distinct()
            return proofs.ifEmpty{native}
        }
        fun usesNativeProof(expected:FieldExportApplicationResult,native:List<FieldExportApplicationResult>)=
            native.size>1||native.firstOrNull()?.deleted==true||native.firstOrNull()?.measurementId?.equals(expected.measurementId,true)==true
        fun resultFromJson(e:JsonObject):FieldExportApplicationResult{
            for(key in listOf("id","store","kind"))fieldRequire(e.getValue(key).jsonPrimitive.isString,"Result save expected text type")
            for(key in listOf("value","deleted"))fieldRequire(!e.getValue(key).jsonPrimitive.isString,"Result save expected scalar type")
            e["measurement_id"]?.takeUnless{it==JsonNull}?.let{fieldRequire(it.jsonPrimitive.isString,"Result save measurement UUID type")}
            return FieldExportApplicationResult(e.getValue("id").jsonPrimitive.content,e.getValue("store").jsonPrimitive.content,
                FieldStudyKind.entries.single{it.raw==e.getValue("kind").jsonPrimitive.content},e.getValue("value").jsonPrimitive.double,
                e["measurement_id"]?.takeUnless{it==JsonNull}?.jsonPrimitive?.content,e.getValue("deleted").jsonPrimitive.boolean)
        }
        fun quickProof(id:String,rawKind:String,value:Double,measurementId:String?):FieldExportApplicationResult?{
            val kind=FieldStudyKind.entries.firstOrNull{it.raw==rawKind}?:return null
            return FieldExportApplicationResult(id,"quick_history",kind,value,measurementId)
        }
        fun capture(r:FieldMeasurementRecord)=r.copy(revision=0,events=emptyList(),timing=null,uiFlowTiming=null,selection=FieldSelectionStatus.PENDING,resultRecordId=null,resultStore=null,
            rawStatus=FieldRawStatus.NOT_REQUESTED,rawBundleId=null,rawFailureReason=null,replayValue=null)
        fun decode(text:String):FieldResultSaveIntent{
            val o=Json.parseToJsonElement(text).jsonObject;val e=o.getValue("expected").jsonObject
            for(key in listOf("id"))fieldRequire(o.getValue(key).jsonPrimitive.isString,"Result save text type")
            for(key in listOf("schema","completed"))fieldRequire(!o.getValue(key).jsonPrimitive.isString,"Result save scalar type")
            for(key in listOf("id","store","kind"))fieldRequire(e.getValue(key).jsonPrimitive.isString,"Result save expected text type")
            for(key in listOf("value","deleted"))fieldRequire(!e.getValue(key).jsonPrimitive.isString,"Result save expected scalar type")
            e["measurement_id"]?.takeUnless{it==JsonNull}?.let{fieldRequire(it.jsonPrimitive.isString,"Result save measurement UUID type")}
            return FieldResultSaveIntent(o.getValue("id").jsonPrimitive.content,FieldStudyJson.measurement(o.getValue("snapshot").toString()),
                resultFromJson(e),o.getValue("completed").jsonPrimitive.boolean,o.getValue("schema").jsonPrimitive.int,
                o["replacements"]?.takeUnless{it==JsonNull}?.jsonArray?.map{FieldQuickReplacementSnapshot.decode(it.jsonObject)}?:emptyList(),
                o["inventory_replacement"]?.takeUnless{it==JsonNull}?.let{FieldInventoryReplacementSnapshot.decode(it.jsonObject)}).also{it.validate()}
        }
    }
}
