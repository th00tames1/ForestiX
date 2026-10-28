package com.hcjeong.forestix.data.fieldstudy

import com.hcjeong.forestix.data.cruise.Tree
import kotlinx.serialization.json.*

/** Full original native inventory row, not a reconstructed result or a repair. */
data class FieldInventoryReplacementSnapshot(val results:List<FieldExportApplicationResult>,val payloadFormat:String,val payloadJSON:String){
    fun validate(){
        fieldRequire(results.size in 1..2&&results.map{it.kind}.distinct().size==results.size&&results.any{it.kind==FieldStudyKind.DBH}&&
            payloadFormat in listOf("ios.tree_json.v1","android.tree_json.v1")&&payloadJSON.toByteArray(Charsets.UTF_8).size<=4*1024*1024,"inventory replacement snapshot")
        val row=Json.parseToJsonElement(payloadJSON).jsonObject
        fun s(key:String):String?=row[key]?.takeUnless{it==JsonNull}?.jsonPrimitive?.let{fieldRequire(it.isString,"Inventory text type");it.content}
        val id=s("id");val plot=s("plotId");val number=row.getValue("treeNumber").jsonPrimitive
        fieldRequire(validFieldUuid(id.orEmpty())&&validFieldUuid(plot.orEmpty())&&!number.isString&&number.int in 1..Int.MAX_VALUE&&
            (row["deletedAt"]==null||row["deletedAt"]==JsonNull),"inventory replacement native row")
        fieldRequire(listOf("speciesCode","status","dbhMethod","dbhConfidence","dbhIsIrregular","damageCodes","isMultistem","notes","createdAt","updatedAt").all{it in row},"incomplete native inventory snapshot")
        if(payloadFormat=="android.tree_json.v1")FieldInventoryTreeJSON.decode(row)
        fieldRequire((row["heightM"]?.let{it!=JsonNull}==true)==results.any{it.kind==FieldStudyKind.HEIGHT},"inventory replacement height")
        for(result in results){
            val value=row.getValue(if(result.kind==FieldStudyKind.DBH)"dbhCm"else"heightM").jsonPrimitive
            val owner=s(if(result.kind==FieldStudyKind.DBH)"dbhMeasurementId"else"heightMeasurementId")
            fieldRequire(result.store=="cruise"&&!result.deleted&&result.id.equals(id,true)&&result.value.isFinite()&&
                !value.isString&&value.float.isFinite()&&value.float.toDouble()==result.value&&owner==result.measurementId,"inventory replacement identity/value")
        }
    }
    fun json():JsonObject=buildJsonObject{put("results",JsonArray(results.map{it.json()}));put("payload_format",payloadFormat);put("payload_json",payloadJSON)}
    companion object{
        fun from(tree:Tree)=FieldInventoryReplacementSnapshot(listOfNotNull(
            FieldExportApplicationResult(tree.id.toString(),"cruise",FieldStudyKind.DBH,tree.dbhCm.toDouble(),tree.dbhMeasurementId,tree.deletedAt!=null),
            tree.heightM?.let{FieldExportApplicationResult(tree.id.toString(),"cruise",FieldStudyKind.HEIGHT,it.toDouble(),tree.heightMeasurementId,tree.deletedAt!=null)}),
            "android.tree_json.v1",FieldInventoryTreeJSON.encode(tree).toString()).also{it.validate()}
        fun decode(o:JsonObject)=FieldInventoryReplacementSnapshot(o.getValue("results").jsonArray.map{FieldResultSaveIntent.resultFromJson(it.jsonObject)},
            o.getValue("payload_format").jsonPrimitive.content,o.getValue("payload_json").jsonPrimitive.content).also{it.validate()}
    }
}
