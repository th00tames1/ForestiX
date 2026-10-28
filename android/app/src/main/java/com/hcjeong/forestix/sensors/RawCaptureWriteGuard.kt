package com.hcjeong.forestix.sensors

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.time.Instant
import kotlinx.serialization.json.*

internal object RawCaptureWriteGuard {
    fun claim(directory:File,id:String,kind:String)=synchronized(RawCaptureStore.pendingLock){
        RawOperatorEditJournal.validate(directory,id)
        require(directory.isDirectory||directory.mkdirs()){"Raw capture directory unavailable"}
        val files=directory.listFiles()?:error("Raw capture directory unreadable")
        require(files.all{it.name=="pending_patch.json"}){"Existing raw capture preserved; use a new measurement ID"}
        val claim=File(directory,"writer_claim.json")
        Files.createFile(claim.toPath()) // Exclusive create: a second writer cannot replace this claim.
        val text=buildJsonObject{put("schema",1);put("measurement_id",id);put("kind",kind);put("claimed_at",Instant.now().toString())}.toString()
        FileOutputStream(claim).use{it.write(text.toByteArray(Charsets.UTF_8));it.fd.sync()}
    }
}
