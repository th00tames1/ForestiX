package com.hcjeong.forestix.sensors

import java.util.UUID
import kotlinx.serialization.json.*

/** Manifest parsing is not a prerequisite for recovering original bytes. */
object RawCaptureExportAvailability {
    fun canExportRaw(inventory:RawCaptureStore.Inventory,isExporting:Boolean)=!isExporting&&inventory.directories>0
}

/** Display only: actual result, canonical fit and missing measurement stay
 * distinct. A malformed identified record never falls back to legacy zero. */
data class RawCapturePresentation(val displayedValue:Double?,val canonicalValue:Double?,val usesExactEvidence:Boolean,
    val allowsTruth:Boolean,val sigma:Double?,val tier:String?){
    companion object {
        fun from(text:String):RawCapturePresentation {
            val m=Json.parseToJsonElement(text).jsonObject
            fun obj(o:JsonObject,k:String)=o[k] as? JsonObject
            fun string(o:JsonObject?,k:String)=(o?.get(k) as? JsonPrimitive)?.contentOrNull
            fun number(o:JsonObject?,k:String)=(o?.get(k) as? JsonPrimitive)?.doubleOrNull?.takeIf{it.isFinite()}
            fun positive(v:Double?)=v?.takeIf{it>0}
            val result=obj(m,"result_live");val evidence=obj(m,"capture_evidence")
            val identified=m["measurement_id"]?.let{it!=JsonNull}==true||m["capture_evidence"]?.let{it!=JsonNull}==true
            val canonical=positive(number(result,"value"));val actual=evidence?.let{obj(it,"displayed_result")}
            val owner=string(evidence,"measurement_id")
            val bound=owner!=null&&runCatching{UUID.fromString(owner).toString().equals(owner,true)}.getOrDefault(false)&&owner.equals(string(m,"measurement_id"),true)
            val value=if(evidence!=null){if(bound&&(actual?.get("valid") as? JsonPrimitive)?.booleanOrNull==true)positive(number(actual,"value"))else null}
                else if(identified)null else canonical
            val source=if(identified)actual else result
            return RawCapturePresentation(value,canonical,identified,value!=null&&string(m,"kind") in listOf("dbh","height"),
                if(value!=null)number(source,"sigma")?.takeIf{it>=0}else null,if(value!=null)string(source,"tier")else null)
        }
    }
}
