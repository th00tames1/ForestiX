package com.hcjeong.forestix.data.fieldstudy

import kotlinx.serialization.json.*

/** Durable before/after journal batch; raw/timing/capture evidence stays intact. */
data class FieldApplicationUndoBatch(val id:String,val resultRecordId:String,val measurementIds:List<String>,
    val changes:List<Change>,val completed:Boolean=false,val schema:Int=1){
    data class Change(val before:FieldMeasurementRecord,val after:FieldMeasurementRecord)
    companion object {
        fun prepare(id:String,resultRecordId:String,measurementIds:List<String>,records:List<FieldMeasurementRecord>):FieldApplicationUndoBatch{
            fieldRequire(validFieldUuid(id)&&validFieldUuid(resultRecordId)&&measurementIds.isNotEmpty()&&
                measurementIds.map{it.lowercase()}.toSet().size==measurementIds.size,"application Undo identity")
            val changes=ArrayList<Change>()
            for(measurementId in measurementIds){
                val current=records.filter{it.id.equals(measurementId,true)}.singleOrNull()
                    ?:throw FieldStudyException("conflict","Undo journal result missing or duplicated")
                if(current.selection!=FieldSelectionStatus.ACCEPTED||current.outcome!=FieldAttemptOutcome.RESULT||
                    current.resultStore!="cruise"||current.resultRecordId?.equals(resultRecordId,true)!=true)
                    throw FieldStudyException("conflict","Undo journal no longer matches inventory result")
                changes.add(Change(current,current.copy(selection=FieldSelectionStatus.DISCARDED,revision=current.revision+1,
                    events=current.events+FieldMeasurementEvent("application_undo",id))))
                for(old in records){
                    if(old.selection!=FieldSelectionStatus.SUPERSEDED||old.resultStore!="cruise"||old.resultRecordId?.equals(resultRecordId,true)!=true||
                        !FieldMeasurementReplacementScope.matches(old,current))continue
                    val last=old.events.lastOrNull{it.action in listOf("superseded","application_undo_restore")}
                    if(last?.action!="superseded"||last.detail?.equals(current.id,true)!=true)continue
                    changes.add(Change(old,old.copy(selection=FieldSelectionStatus.ACCEPTED,revision=old.revision+1,
                        events=old.events+FieldMeasurementEvent("application_undo_restore","$id:${current.id}"))))
                }
            }
            return FieldApplicationUndoBatch(id,resultRecordId,measurementIds.toList(),changes.toList()).also{it.validate()}
        }
        fun decode(text:String):FieldApplicationUndoBatch{
            val o=Json.parseToJsonElement(text).jsonObject
            return FieldApplicationUndoBatch(id=o.getValue("id").jsonPrimitive.content,resultRecordId=o.getValue("result_record_id").jsonPrimitive.content,
                measurementIds=o.getValue("measurement_ids").jsonArray.map{it.jsonPrimitive.content},
                changes=o.getValue("changes").jsonArray.map{v->v.jsonObject.let{Change(FieldStudyJson.measurement(it.getValue("before").toString()),FieldStudyJson.measurement(it.getValue("after").toString()))}},
                completed=o.getValue("completed").jsonPrimitive.boolean,schema=o.getValue("schema").jsonPrimitive.int).also{it.validate()}
        }
    }
    fun encode()=buildJsonObject{
        put("schema",schema);put("id",id);put("result_record_id",resultRecordId);put("completed",completed)
        put("measurement_ids",JsonArray(measurementIds.map{JsonPrimitive(it)}))
        put("changes",JsonArray(changes.map{c->buildJsonObject{put("before",FieldStudyJson.measurementJson(c.before));put("after",FieldStudyJson.measurementJson(c.after))}}))
    }.toString()
    fun validate(){
        fieldRequire(schema==1&&validFieldUuid(id)&&validFieldUuid(resultRecordId)&&measurementIds.isNotEmpty()&&
            measurementIds.all{validFieldUuid(it)}&&measurementIds.map{it.lowercase()}.toSet().size==measurementIds.size&&
            changes.map{it.before.id.lowercase()}.toSet().size==changes.size&&
            changes.filter{it.after.selection==FieldSelectionStatus.DISCARDED}.map{it.before.id.lowercase()}.toSet()==measurementIds.map{it.lowercase()}.toSet(),"application Undo batch")
        for(c in changes){
            val old=c.before;val next=c.after;old.validate();next.validate()
            val event=next.events.lastOrNull()?:throw FieldStudyException("invalid","Undo event missing")
            fieldRequire(old.resultStore=="cruise"&&old.resultRecordId?.equals(resultRecordId,true)==true,"application Undo result")
            val selection=if(measurementIds.any{it.equals(old.id,true)}){
                fieldRequire(old.selection==FieldSelectionStatus.ACCEPTED&&old.outcome==FieldAttemptOutcome.RESULT&&event.action=="application_undo"&&event.detail==id,"application Undo discard")
                FieldSelectionStatus.DISCARDED
            }else{
                val replacement=changes.firstOrNull{event.detail=="$id:${it.before.id}"&&it.after.selection==FieldSelectionStatus.DISCARDED}
                fieldRequire(old.selection==FieldSelectionStatus.SUPERSEDED&&event.action=="application_undo_restore"&&replacement!=null&&
                    old.events.lastOrNull{it.action in listOf("superseded","application_undo_restore")}?.detail?.equals(replacement?.before?.id,true)==true&&
                    FieldMeasurementReplacementScope.matches(old,requireNotNull(replacement).before),"application Undo restoration")
                FieldSelectionStatus.ACCEPTED
            }
            fieldRequire(next==old.copy(selection=selection,revision=old.revision+1,events=old.events+event),"Undo cannot rewrite capture evidence")
        }
    }
}
