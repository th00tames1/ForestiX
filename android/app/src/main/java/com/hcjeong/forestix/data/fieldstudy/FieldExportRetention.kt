package com.hcjeong.forestix.data.fieldstudy

import java.io.File
import java.nio.file.Files
import java.util.UUID

/** Never traverses directories, links or any non-export file. */
object FieldExportRetention {
    fun prune(directory:File,nowMillis:Long=System.currentTimeMillis(),maximumAgeMillis:Long=3600_000L,keepNewest:Int=2){
        if(!directory.isDirectory||Files.isSymbolicLink(directory.toPath()))return
        val prefix="forestix-field-study-"
        val candidates=directory.listFiles().orEmpty().filter{file->
            val name=file.name
            file.isFile&&!Files.isSymbolicLink(file.toPath())&&name.startsWith(prefix)&&name.endsWith(".zip")&&
                runCatching{val id=name.removePrefix(prefix).removeSuffix(".zip");UUID.fromString(id).toString().equals(id,true)}.getOrDefault(false)
        }.sortedWith(compareByDescending<File>{it.lastModified()}.thenBy{it.name})
        for(file in candidates.drop(keepNewest.coerceAtLeast(0))){
            if(nowMillis-file.lastModified()>maximumAgeMillis.coerceAtLeast(0))check(file.delete()){"Export cache cleanup unavailable"}
        }
    }
}
