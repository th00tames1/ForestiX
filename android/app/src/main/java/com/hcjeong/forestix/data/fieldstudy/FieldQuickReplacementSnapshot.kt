package com.hcjeong.forestix.data.fieldstudy

import kotlinx.serialization.json.*

/** Original native row values, not reconstructed or relabelled sensor inputs. */
data class FieldQuickReplacementSnapshot(val result:FieldExportApplicationResult,val payloadFormat:String,val payloadJSON:String){
    fun validate(){
        fieldRequire(result.store=="quick_history"&&validFieldUuid(result.id)&&result.value.isFinite()&&!result.deleted&&
            payloadFormat in listOf("ios.quick_entry_json.v1","android.entry_row_json.v1")&&payloadJSON.toByteArray(Charsets.UTF_8).size<=4*1024*1024,"replacement native snapshot")
        val row=Json.parseToJsonElement(payloadJSON).jsonObject
        fieldRequire(row.getValue("id").jsonPrimitive.isString&&row.getValue("id").jsonPrimitive.content.equals(result.id,true)&&
            row.getValue("kind").jsonPrimitive.isString&&row.getValue("kind").jsonPrimitive.content==result.kind.raw&&
            !row.getValue("value").jsonPrimitive.isString&&row.getValue("value").jsonPrimitive.double==result.value&&
            row["measurementId"]?.takeUnless{it==JsonNull}?.jsonPrimitive?.content==result.measurementId,"replacement payload identity")
    }
    fun json()=buildJsonObject{put("result",result.json());put("payload_format",payloadFormat);put("payload_json",payloadJSON)}
    companion object{
        fun decode(o:JsonObject)=FieldQuickReplacementSnapshot(FieldResultSaveIntent.resultFromJson(o.getValue("result").jsonObject),
            o.getValue("payload_format").jsonPrimitive.content,o.getValue("payload_json").jsonPrimitive.content).also{it.validate()}
    }
}
