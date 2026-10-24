package com.hcjeong.forestix.sensors

import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RawOperatorEditTest {
    private fun runOwned(block:(File,String,File)->Unit){val root=Files.createTempDirectory("forestix-operator-").toFile();val id=UUID.randomUUID().toString()
        try{block(root,id,File(root,id))}finally{root.deleteRecursively()}}
    private fun base(id:String)=buildJsonObject{put("schema",2);put("kind","dbh");put("measurement_id",id)
        put("result_live",buildJsonObject{put("value",25.0);put("accepted",false);put("operator_accepted",false)})
        put("truth",buildJsonObject{put("value",JsonNull)})}
    private fun write(dir:File,m:JsonObject){dir.mkdirs();RawOperatorEditJournal.atomic(m.toString(),File(dir,"manifest.json"))}
    private fun read(dir:File)=Json.parseToJsonElement(File(dir,"manifest.json").readText()).jsonObject
    private fun truth(value:Double?)=buildJsonObject{put("value",value?.let{JsonPrimitive(it)}?:JsonNull)
        put("entered_at",value?.let{JsonPrimitive("FIXED_TIME")}?:JsonNull);put("truth_unit",value?.let{JsonPrimitive("cm")}?:JsonNull)}
    @Test fun pendingTruthAndAcceptanceRecoverFromIntermediateManifest()=runOwned{root,id,dir->
        assertFalse(RawOperatorEditJournal.update(dir,id,truth(37.125)));assertFalse(RawOperatorEditJournal.update(dir,id,accepted=true))
        assertTrue(File(dir,"pending_patch.json").isFile);RawCaptureWriteGuard.claim(dir,id,"dbh");write(dir,base(id))
        assertTrue(RawOperatorEditJournal.recover(root).isEmpty());val m=read(dir);val t=m.getValue("truth").jsonObject
        assertEquals(37.125,t.getValue("value").jsonPrimitive.double,0.0);assertEquals("FIXED_TIME",t.getValue("entered_at").jsonPrimitive.content)
        assertEquals("cm",t.getValue("truth_unit").jsonPrimitive.content);assertTrue(m.getValue("result_live").jsonObject.getValue("operator_accepted").jsonPrimitive.boolean)
        assertNotNull(m["operator_edit_id"]);assertFalse(File(dir,"pending_patch.json").exists())
    }
    @Test fun consumedSidecarAndLateRecorderCannotResurrectAnOldTruthOrExplicitClear()=runOwned{root,id,dir->
        assertFalse(RawOperatorEditJournal.update(dir,id,truth(30.0)));val sidecar=File(dir,"pending_patch.json");val consumed=sidecar.readBytes()
        RawOperatorEditJournal.commitRecorder(dir,id,base(id).toString());sidecar.writeBytes(consumed)
        assertTrue(RawOperatorEditJournal.recover(root).isEmpty());assertTrue(RawOperatorEditJournal.update(dir,id,truth(42.0)))
        assertTrue(RawOperatorEditJournal.update(dir,id,truth(null)));RawOperatorEditJournal.commitRecorder(dir,id,base(id).toString())
        assertEquals(JsonNull,read(dir).getValue("truth").jsonObject["value"]);assertTrue(RawOperatorEditJournal.recover(root).isEmpty())
    }
    @Test fun legacySidecarGetsReceiptWithoutChangingTypedTimeOrUnits()=runOwned{root,id,dir->
        write(dir,base(id));File(dir,"pending_patch.json").writeText("""{"truth":{"value":37.125,"entered_at":"OLD_TIME","truth_unit":"in"},"operator_accepted":true}""")
        assertTrue(RawOperatorEditJournal.recover(root).isEmpty());val m=read(dir);val t=m.getValue("truth").jsonObject
        assertEquals("OLD_TIME",t.getValue("entered_at").jsonPrimitive.content);assertEquals("in",t.getValue("truth_unit").jsonPrimitive.content)
        assertEquals(37.125,t.getValue("value").jsonPrimitive.double,0.0);assertNotNull(m["operator_edit_id"])
    }
    @Test fun corruptOrMisboundInputIsPreservedNotOverwritten(){for(mode in 0..2)runOwned{root,id,dir->
        write(dir,base(id));val manifest=File(dir,"manifest.json");val pending=File(dir,"pending_patch.json")
        when(mode){0->pending.writeText("{broken");1->{manifest.writeText("{broken");pending.writeText("""{"operator_accepted":true}""")}
            2->pending.writeText("""{"measurement_id":"${UUID.randomUUID()}","operator_accepted":true}""")}
        val before=manifest.readBytes();val queued=pending.readBytes();assertFalse(RawOperatorEditJournal.recover(root).isEmpty())
        assertTrue(runCatching{RawOperatorEditJournal.update(dir,id,truth(42.0))}.isFailure)
        assertArrayEquals(before,manifest.readBytes());assertArrayEquals(queued,pending.readBytes())
    }}
    @Test fun missingManifestKeepsIntentAndInputOnlyCaptureCannotBeAccepted()=runOwned{root,id,dir->
        assertFalse(RawOperatorEditJournal.update(dir,id,accepted=true));assertFalse(RawOperatorEditJournal.recover(root).isEmpty())
        val pending=File(dir,"pending_patch.json");val bytes=pending.readBytes();val fields=base(id).toMutableMap()
        fields["capture_evidence"]=buildJsonObject{put("displayed_result",buildJsonObject{put("valid",false);put("value",JsonNull)})}
        write(dir,JsonObject(fields));assertTrue(runCatching{RawOperatorEditJournal.update(dir,id,accepted=true)}.isFailure)
        assertFalse(RawOperatorEditJournal.recover(root).isEmpty());assertArrayEquals(bytes,pending.readBytes())
        assertFalse(read(dir).getValue("result_live").jsonObject.getValue("operator_accepted").jsonPrimitive.boolean)
    }
    @Test fun exclusiveClaimsRefuseCompletePartialUnknownOrConcurrentWriters(){
        for(name in listOf("manifest.json","input_dbh_0.f32","unexpected.txt","writer_claim.json"))runOwned{_,id,dir->
            dir.mkdir();val file=File(dir,name);val bytes=byteArrayOf(7,8,9);file.writeBytes(bytes)
            assertTrue(runCatching{RawCaptureWriteGuard.claim(dir,id,"dbh")}.isFailure);assertArrayEquals(bytes,file.readBytes())
            assertEquals(listOf(name),dir.list()!!.toList())}
        runOwned{root,id,dir->val executor=Executors.newFixedThreadPool(4)
            try{val successes=executor.invokeAll((1..16).map{Callable{runCatching{RawCaptureWriteGuard.claim(dir,id,"dbh")}.isSuccess}}).count{it.get()}
                assertEquals(1,successes)}finally{executor.shutdown()}
            assertTrue(runCatching{RawCaptureWriteGuard.claim(File(root,"escaped"),"../escaped","dbh")}.isFailure)}
    }
}
