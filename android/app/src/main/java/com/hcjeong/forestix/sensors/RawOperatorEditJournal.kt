package com.hcjeong.forestix.sensors

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.*

data class RawOperatorRecoveryIssue(val file:String,val reason:String)

/** Durable operator intent + manifest receipt, with no camera/estimator/DB
 * dependency. Schema also reads the earlier iOS pending_patch shape. */
internal object RawOperatorEditJournal {
    private data class Edit(val id:String?,val editId:String?,val truth:JsonObject?,val accepted:Boolean){
        fun json()=buildJsonObject{put("schema",1);id?.let{put("measurement_id",it)};editId?.let{put("edit_id",it)}
            truth?.let{put("truth",it)};put("operator_accepted",accepted)}
    }
    private fun uuid(s:String)=runCatching{UUID.fromString(s).toString().equals(s,true)}.getOrDefault(false)
    fun validate(directory:File,id:String){
        require(uuid(id)&&directory.name.equals(id,true)&&!Files.isSymbolicLink(directory.toPath())){"Raw capture identity/path mismatch"}
    }
    fun atomic(text:String,file:File){
        val temp=File.createTempFile(".operator-",".tmp",file.parentFile)
        try{FileOutputStream(temp).use{it.write(text.toByteArray(Charsets.UTF_8));it.fd.sync()}
            Files.move(temp.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        }finally{if(temp.exists())temp.delete()}
    }
    private fun readManifest(directory:File,id:String):JsonObject?{
        val f=File(directory,"manifest.json");if(!f.exists())return null
        val m=Json.parseToJsonElement(f.readText()).jsonObject
        val recorded=m["measurement_id"]?.takeUnless{it==JsonNull}?.jsonPrimitive?.content
        require(recorded==null||recorded.equals(id,true)){"Manifest measurement ID mismatch"}
        return m
    }
    private fun readEdit(directory:File,id:String):Edit?{
        val f=File(directory,"pending_patch.json");if(!f.exists())return null
        val o=Json.parseToJsonElement(f.readText()).jsonObject
        val schema=o["schema"]?.takeUnless{it==JsonNull}?.jsonPrimitive?.int
        require(schema==null||schema==1){"Pending operator schema"}
        val owner=o["measurement_id"]?.takeUnless{it==JsonNull}?.jsonPrimitive?.content
        val edit=o["edit_id"]?.takeUnless{it==JsonNull}?.jsonPrimitive?.content
        require((owner==null||owner.equals(id,true))&&(edit==null||uuid(edit))){"Pending operator edit is misbound"}
        val truth=o["truth"]?.takeUnless{it==JsonNull}?.jsonObject
        val v=truth?.get("value")?.takeUnless{it==JsonNull}?.jsonPrimitive?.double
        val unit=truth?.get("truth_unit")?.takeUnless{it==JsonNull}?.jsonPrimitive?.content
        require((v==null||v.isFinite()&&v>0)&&(unit==null||unit in listOf("cm","in","m","ft"))){"Pending operator truth"}
        return Edit(owner,edit,truth,o.getValue("operator_accepted").jsonPrimitive.boolean)
    }
    private fun save(edit:Edit,directory:File)=atomic(edit.json().toString(),File(directory,"pending_patch.json"))
    private fun measurable(m:JsonObject):Boolean{
        val evidence=m["capture_evidence"]?.takeUnless{it==JsonNull}?.jsonObject
        if(evidence!=null){val actual=evidence.getValue("displayed_result").jsonObject
            val v=actual["value"]?.takeUnless{it==JsonNull}?.jsonPrimitive?.double
            return actual.getValue("valid").jsonPrimitive.boolean&&v!=null&&v.isFinite()&&v>0}
        val v=m["result_live"]?.jsonObject?.get("value")?.takeUnless{it==JsonNull}?.jsonPrimitive?.double
        return v!=null&&v.isFinite()&&v>0
    }
    private fun receipt(m:JsonObject)=m["operator_edit_id"]?.takeUnless{it==JsonNull}?.jsonPrimitive?.content
    private fun commitPending(base:JsonObject,directory:File,id:String):JsonObject?{
        var edit=readEdit(directory,id);var m=base
        if(edit!=null){
            if(edit.editId==null){edit=edit.copy(id=id,editId=UUID.randomUUID().toString());save(edit,directory)}
            if(edit.editId!=receipt(m)){
                require(measurable(m)){"Operator edit requires an actual measurement"}
                val fields=m.toMutableMap();edit.truth?.let{fields["truth"]=it}
                if(edit.accepted){val result=m.getValue("result_live").jsonObject.toMutableMap()
                    result["operator_accepted"]=JsonPrimitive(true);result["accepted"]=JsonPrimitive(true);fields["result_live"]=JsonObject(result)}
                fields["operator_edit_id"]=JsonPrimitive(edit.editId);m=JsonObject(fields)
            }
        }
        atomic(m.toString(),File(directory,"manifest.json"))
        File(directory,"pending_patch.json").delete() // Receipt makes a failed unlink safe.
        return edit?.truth
    }
    fun update(directory:File,id:String,truth:JsonObject?=null,accepted:Boolean=false):Boolean=synchronized(RawCaptureStore.pendingLock){
        validate(directory,id);val manifest=readManifest(directory,id)
        require(manifest==null||measurable(manifest)){"Operator edit requires an actual measurement"}
        var old=readEdit(directory,id)
        if(manifest!=null&&old?.editId!=null&&old.editId==receipt(manifest))old=null
        val edit=Edit(id,UUID.randomUUID().toString(),truth?:old?.truth,accepted||(old?.accepted?:false))
        val value=edit.truth?.get("value")?.takeUnless{it==JsonNull}?.jsonPrimitive?.double
        val unit=edit.truth?.get("truth_unit")?.takeUnless{it==JsonNull}?.jsonPrimitive?.content
        require((value==null||value.isFinite()&&value>0)&&(unit==null||unit in listOf("cm","in","m","ft"))){"Pending operator truth"}
        require(directory.isDirectory||directory.mkdirs()){"Pending operator directory unavailable"}
        save(edit,directory) // Confirm the sidecar BEFORE touching a manifest.
        if(manifest==null)false else{commitPending(manifest,directory,id);true}
    }
    fun commitRecorder(directory:File,id:String,text:String):JsonObject?=synchronized(RawCaptureStore.pendingLock){
        validate(directory,id);val fields=Json.parseToJsonElement(text).jsonObject.toMutableMap()
        readManifest(directory,id)?.let{disk->
            disk["truth"]?.let{fields["truth"]=it};disk["operator_edit_id"]?.let{fields["operator_edit_id"]=it}
            if(disk["result_live"]?.jsonObject?.get("operator_accepted")?.jsonPrimitive?.booleanOrNull==true){
                val result=fields.getValue("result_live").jsonObject.toMutableMap();result["accepted"]=JsonPrimitive(true)
                result["operator_accepted"]=JsonPrimitive(true);fields["result_live"]=JsonObject(result)}
        }
        commitPending(JsonObject(fields),directory,id)
    }
    fun pendingTruth(directory:File,id:String):JsonObject?=synchronized(RawCaptureStore.pendingLock){validate(directory,id);readEdit(directory,id)?.truth}
    fun recover(root:File):List<RawOperatorRecoveryIssue> = synchronized(RawCaptureStore.pendingLock){
        if(!root.exists())return@synchronized emptyList()
        val dirs=root.listFiles()?:return@synchronized listOf(RawOperatorRecoveryIssue("raw-captures","pending_operator_inventory_unreadable"))
        val issues=ArrayList<RawOperatorRecoveryIssue>()
        for(dir in dirs)if(File(dir,"pending_patch.json").exists())try{
            validate(dir,dir.name);val m=readManifest(dir,dir.name)?:error("Pending operator input retained without manifest")
            commitPending(m,dir,dir.name)
        }catch(e:Exception){issues.add(RawOperatorRecoveryIssue("raw-captures/${dir.name}/pending_patch.json",e.toString()))}
        issues
    }
    fun truth(value:Double?,unit:String?)=buildJsonObject{put("value",value?.let{JsonPrimitive(it)}?:JsonNull)
        put("entered_at",value?.let{JsonPrimitive(Instant.now().toString())}?:JsonNull);put("truth_unit",unit?.let{JsonPrimitive(it)}?:JsonNull)}
}
