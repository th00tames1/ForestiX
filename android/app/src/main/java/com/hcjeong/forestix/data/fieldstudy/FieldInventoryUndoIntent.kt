package com.hcjeong.forestix.data.fieldstudy

import com.hcjeong.forestix.data.cruise.Tree
import java.time.Instant
import kotlinx.serialization.json.*

enum class FieldInventoryUndoPhase(val raw:String){PREPARED("prepared"),APPLICATION_APPLIED("applicationApplied"),COMPLETE("complete"),REFUSED("refused")}
data class FieldInventoryUndoIntent(val id:String,val saved:Tree,val original:Tree?,val measurementIds:List<String>,
    val phase:FieldInventoryUndoPhase=FieldInventoryUndoPhase.PREPARED,val failureReason:String?=null,
    val createdAt:String=Instant.now().toString(),val schema:Int=1,val treeSnapshotFormat:String="kotlin-tree-v1"){
    val unresolvedMeasurementIds:Set<String> get()=if(phase==FieldInventoryUndoPhase.COMPLETE)emptySet()else measurementIds.map{it.lowercase()}.toSet()
    fun validate(){
        fieldRequire(schema==1&&treeSnapshotFormat=="kotlin-tree-v1"&&validFieldUuid(id)&&createdAt.isNotEmpty()&&saved.deletedAt==null&&saved.treeNumber>0&&
            measurementIds.all(::validFieldUuid)&&measurementIds.map{it.lowercase()}.toSet().size==measurementIds.size&&
            (original==null||original.id==saved.id&&original.plotId==saved.plotId&&original.treeNumber==saved.treeNumber&&original.deletedAt==null)&&
            (phase!=FieldInventoryUndoPhase.REFUSED||!failureReason.isNullOrEmpty()),"Inventory Undo intent")
        listOfNotNull(saved,original).forEach{t->fieldRequire(listOfNotNull(t.dbhCm,t.dbhSigmaMm,t.dbhRmseMm,t.dbhCoverageDeg,t.heightM,t.heightSigmaM,
            t.heightDHM,t.heightAlphaTopDeg,t.heightAlphaBaseDeg,t.bearingFromCenterDeg,t.distanceFromCenterM).all{it.isFinite()}&&
            listOfNotNull(t.latitude,t.longitude).all{it.isFinite()},"Inventory Undo snapshot")}
    }
    fun matches(id:String,saved:Tree,original:Tree?,measurementIds:List<String>)=this.id.equals(id,true)&&this.saved==saved&&this.original==original&&
        this.measurementIds.map{it.lowercase()}.toSet()==measurementIds.map{it.lowercase()}.toSet()
    fun encode():String{validate();return buildJsonObject{
        put("schema",schema);put("tree_snapshot_format",treeSnapshotFormat);put("id",id);put("created_at",createdAt);put("phase",phase.raw)
        put("saved",FieldInventoryTreeJSON.encode(saved));put("original",original?.let(FieldInventoryTreeJSON::encode)?:JsonNull)
        put("measurement_ids",JsonArray(measurementIds.map{JsonPrimitive(it)}));put("failure_reason",failureReason?.let{JsonPrimitive(it)}?:JsonNull)
    }.toString()}
    companion object{
        fun decode(text:String):FieldInventoryUndoIntent{val o=Json.parseToJsonElement(text).jsonObject
            return FieldInventoryUndoIntent(id=o.getValue("id").jsonPrimitive.content,saved=FieldInventoryTreeJSON.decode(o.getValue("saved").jsonObject),
                original=o["original"]?.takeUnless{it==JsonNull}?.let{FieldInventoryTreeJSON.decode(it.jsonObject)},
                measurementIds=o.getValue("measurement_ids").jsonArray.map{it.jsonPrimitive.content},
                phase=FieldInventoryUndoPhase.entries.firstOrNull{it.raw==o.getValue("phase").jsonPrimitive.content}?:throw FieldStudyException("invalid","Undo phase"),
                failureReason=o["failure_reason"]?.takeUnless{it==JsonNull}?.jsonPrimitive?.content,createdAt=o.getValue("created_at").jsonPrimitive.content,
                schema=o.getValue("schema").jsonPrimitive.int,treeSnapshotFormat=o.getValue("tree_snapshot_format").jsonPrimitive.content).also{it.validate()}}
    }
}
