package com.hcjeong.forestix.data.fieldstudy

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.*

data class FieldExportApplicationResult(val id:String,val store:String,val kind:FieldStudyKind,val value:Double,
    val measurementId:String?=null,val deleted:Boolean=false){
    fun json()=buildJsonObject{put("id",id);put("store",store);put("kind",kind.raw);put("value",value)
        put("measurement_id",measurementId?.let(::JsonPrimitive)?:JsonNull);put("deleted",deleted)}
}
data class FieldExportIssue(val code:String,val path:String,val measurementId:String?=null){
    fun json()=buildJsonObject{put("code",code);put("path",path);measurementId?.let{put("measurement_id",it)}}
}
data class FieldExportFileProof(val path:String,val bytes:Long,val sha256:String){
    fun json()=buildJsonObject{put("path",path);put("bytes",bytes);put("sha256",sha256)}
}
data class FieldStudyExportReport(val exportId:String,val createdAt:String,val measurementCount:Int,val referenceCount:Int,
    val rawBundleCount:Int,val applicationResultCount:Int,val issues:List<FieldExportIssue>){
    val integrityPassed get()=issues.isEmpty()
    fun json()=buildJsonObject{put("schema",1);put("export_id",exportId);put("created_at",createdAt)
        put("measurement_count",measurementCount);put("reference_count",referenceCount);put("raw_bundle_count",rawBundleCount)
        put("application_result_count",applicationResultCount);put("integrity_passed",integrityPassed)
        put("issues",JsonArray(issues.map{it.json()}))}
}
data class FieldStudyExportArtifact(val file:File,val report:FieldStudyExportReport)

internal object FieldExportFileTree {
    data class Entry(val path:String,val file:File,val bytes:Long,val key:String?,val modified:java.nio.file.attribute.FileTime)
    fun file(file:File,path:String):Entry{
        val a=Files.readAttributes(file.toPath(),BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS)
        fieldRequire(a.isRegularFile&&!a.isSymbolicLink,"export non-regular file: $path")
        return Entry(path,file,a.size(),a.fileKey()?.toString(),a.lastModifiedTime())
    }
    fun files(root:File):List<Entry>{
        if(!Files.exists(root.toPath(),LinkOption.NOFOLLOW_LINKS))return emptyList()
        val result=ArrayList<Entry>()
        fun walk(dir:File,prefix:String){
            val attr=Files.readAttributes(dir.toPath(),BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS)
            fieldRequire(attr.isDirectory&&!attr.isSymbolicLink,"export source directory")
            val children=dir.listFiles()?:throw java.io.IOException("Export directory unreadable: $prefix")
            for(file in children.sortedBy{it.name}){
                fieldRequire(file.name.isNotEmpty()&&!file.name.contains('\\')&&file.name!="."&&file.name!="..","export file name")
                val a=Files.readAttributes(file.toPath(),BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS);val path=prefix+file.name
                if(a.isDirectory)walk(file,"$path/")else{
                    fieldRequire(a.isRegularFile&&!a.isSymbolicLink,"export non-regular file: $path")
                    result+=Entry(path,file,a.size(),a.fileKey()?.toString(),a.lastModifiedTime())
                }
            }
        }
        walk(root,"");return result.sortedBy{it.path}
    }
}

/** All attempts, exact UUID joins, immutable metadata snapshot, no repair.
 * SHA-256 is computed from the same bounded chunks actually written to ZIP. */
object FieldStudyExport {
    fun build(archive:FieldStudyArchive,rawRoot:File,results:List<FieldExportApplicationResult>,
        applicationPayloads:List<Pair<String,ByteArray>> = emptyList(),outputDirectory:File,
        applicationFiles:List<Pair<String,File>> = emptyList(),
        applicationDirectories:List<Pair<String,File>> = emptyList(),
        progress:((String)->Unit)?=null):FieldStudyExportArtifact{
        val dest=outputDirectory.canonicalFile
        for(root in (listOf(archive.root,rawRoot)+applicationDirectories.map{it.second}).map{it.canonicalFile})fieldRequire(dest!=root&&!dest.path.startsWith(root.path+File.separator),"export destination overlaps source")
        check(dest.isDirectory||dest.mkdirs()){"Export output directory unavailable"}
        runCatching{FieldExportRetention.prune(dest)}
        val id=newFieldId();val snapshot=File(dest,"snapshot-$id");val output=File(dest,"forestix-field-study-$id.zip")
        try{
            archive.copyReadOnlySnapshot(snapshot)
            val metadata=FieldExportFileTree.files(snapshot);val raw=FieldExportFileTree.files(rawRoot)
            val directories=applicationDirectories.map{(name,root)->fieldRequire(name.isNotEmpty()&&!name.contains('/')&&!name.contains('\\')&&name!="."&&name!="..","application directory name");Triple(name,root,FieldExportFileTree.files(root))}
            val appFiles=applicationFiles.map{(name,file)->fieldRequire(name.isNotEmpty()&&!name.contains('/')&&!name.contains('\\')&&name!="."&&name!="..","application export name");FieldExportFileTree.file(file,name)}+
                directories.flatMap{(name,_,entries)->entries.map{it.copy(path="$name/${it.path}")}}
            val proofs=ArrayList<FieldExportFileProof>();val names=HashSet<String>()
            val report:FieldStudyExportReport
            ZipOutputStream(output.outputStream().buffered()).use{zip->
                fun add(name:String,data:ByteArray){
                    check(names.add(name)){"Duplicate export path"};zip.putNextEntry(ZipEntry(name));zip.write(data);zip.closeEntry()
                    proofs+=FieldExportFileProof(name,data.size.toLong(),hash(data))
                }
                for((prefix,entries) in listOf("field-study/" to metadata,"raw-captures/" to raw,"application/" to appFiles))for(f in entries){
                    if(Thread.currentThread().isInterrupted)throw InterruptedException("Export cancelled")
                    val name=prefix+f.path;check(names.add(name)){"Duplicate export path"};zip.putNextEntry(ZipEntry(name))
                    val sha=MessageDigest.getInstance("SHA-256");var bytes=0L
                    f.file.inputStream().use{input->val chunk=ByteArray(1024*1024)
                        while(true){val n=input.read(chunk);if(n<0)break;zip.write(chunk,0,n);sha.update(chunk,0,n);bytes+=n}}
                    zip.closeEntry();check(bytes==f.bytes){"Source changed during export"}
                    proofs+=FieldExportFileProof(name,bytes,hex(sha.digest()));progress?.invoke(name)
                }
                check(raw==FieldExportFileTree.files(rawRoot)){"Raw files changed during export; retry after recording finishes"}
                for((name,data) in applicationPayloads){fieldRequire(name.isNotEmpty()&&!name.contains('/')&&!name.contains('\\')&&name!="."&&name!="..","application export name");add("application/$name",data)}
                add("application/result_index.json",JsonArray(results.map{it.json()}).toString().toByteArray(Charsets.UTF_8))
                val audit=audit(metadata,raw,proofs.associateBy{it.path},results)
                val proofByPath=proofs.associateBy{it.path}
                for((_,data) in applicationPayloads)runCatching{Json.parseToJsonElement(data.toString(Charsets.UTF_8))}.getOrNull()?.let{checkPhotos(it,proofByPath,audit.issues)}
                for(entry in audit.replacements)checkPhotos(Json.parseToJsonElement(entry.snapshot.payloadJSON),proofByPath,audit.issues)
                for(entry in audit.inventoryReplacements)checkPhotos(Json.parseToJsonElement(entry.snapshot.payloadJSON),proofByPath,audit.issues)
                if(audit.replacements.isNotEmpty())add("application/replaced_result_index.json",JsonArray(audit.replacements.map{it.json()}).toString().toByteArray(Charsets.UTF_8))
                if(audit.inventoryReplacements.isNotEmpty())add("application/replaced_inventory_index.json",JsonArray(audit.inventoryReplacements.map{it.json()}).toString().toByteArray(Charsets.UTF_8))
                report=FieldStudyExportReport(id,Instant.now().toString(),audit.records.size,audit.references.size,
                    raw.filter{it.path.contains('/')}.map{it.path.substringBefore('/')}.toSet().size,results.size,audit.issues.distinct().sortedWith(compareBy<FieldExportIssue>{it.code}.thenBy{it.path}.thenBy{it.measurementId.orEmpty()}))
                add("measurement_index.csv",measurementCsv(audit.records,report.issues).toByteArray(Charsets.UTF_8))
                add("reference_index.csv",referenceCsv(audit.references).toByteArray(Charsets.UTF_8))
                add("audit.json",report.json().toString().toByteArray(Charsets.UTF_8));add("README.txt",readme.toByteArray(Charsets.UTF_8))
                zip.putNextEntry(ZipEntry("file_inventory.json"));zip.write(JsonArray(proofs.map{it.json()}).toString().toByteArray(Charsets.UTF_8));zip.closeEntry()
                check(raw==FieldExportFileTree.files(rawRoot)){"Raw files changed during validation; retry"}
                check(appFiles==appFiles.map{FieldExportFileTree.file(it.file,it.path)}){"Application files changed during export; retry"}
                for((_,root,initial) in directories)check(FieldExportFileTree.files(root)==initial){"Application directory changed during export; retry"}
            }
            return FieldStudyExportArtifact(output,report)
        }catch(t:Throwable){output.delete();throw t}finally{snapshot.deleteRecursively()}
    }
    private fun hex(bytes:ByteArray)=bytes.joinToString(""){"%02x".format(it.toInt()and 255)}
    private fun hash(bytes:ByteArray)=hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private data class ReplacedResult(val sourceMeasurementId:String,val snapshot:FieldQuickReplacementSnapshot){
        fun json()=buildJsonObject{put("source_measurement_id",sourceMeasurementId);put("storage_state","replaced_snapshot");put("snapshot",snapshot.json())}}
    private data class ReplacedInventory(val sourceMeasurementId:String,val snapshot:FieldInventoryReplacementSnapshot){
        fun json()=buildJsonObject{put("source_measurement_id",sourceMeasurementId);put("storage_state","replaced_inventory_snapshot");put("snapshot",snapshot.json())}}
    private data class Audit(val records:ArrayList<FieldMeasurementRecord> = arrayListOf(),val references:ArrayList<FieldReferenceObservation> = arrayListOf(),val targets:ArrayList<FieldStudyTarget> = arrayListOf(),val issues:ArrayList<FieldExportIssue> = arrayListOf(),val replacements:ArrayList<ReplacedResult> = arrayListOf(),val inventoryReplacements:ArrayList<ReplacedInventory> = arrayListOf())
    private fun JsonObject.s(key:String)=(this[key] as? JsonPrimitive)?.takeUnless{it==JsonNull}?.contentOrNull
    private fun audit(metadata:List<FieldExportFileTree.Entry>,raw:List<FieldExportFileTree.Entry>,proofs:Map<String,FieldExportFileProof>,results:List<FieldExportApplicationResult>):Audit{
        val out=Audit()
        val completedSaves=ArrayList<FieldResultSaveIntent>()
        val bindings=HashMap<String,FieldProtocolBinding>()
        for(file in metadata.filter{it.path.endsWith(".json")})try{
            if(file.bytes>64*1024*1024){out.issues+=FieldExportIssue("metadata_too_large_to_parse","field-study/${file.path}");continue}
            val text=file.file.readText()
            when{
                file.path.startsWith("measurements/")->FieldStudyJson.measurement(text).also{r->
                    fieldRequire(file.file.name.equals("${r.id}.json",true),"measurement file identity");out.records+=r}
                file.path.startsWith("protocol_bindings/")->FieldStudyJson.protocolBinding(text).also{b->
                    fieldRequire(file.path=="protocol_bindings/${b.id}.json","protocol binding file identity");bindings[b.id]=b}
                file.path.startsWith("references/")->FieldStudyJson.reference(text).also{r->r.validate(true)
                    fieldRequire(file.file.name.equals("${r.id}.json",true),"reference file identity");out.references+=r}
                file.path.startsWith("imports/")->FieldStudyJson.importBatch(text).also{it.validate(true);fieldRequire(file.file.name.equals("${it.id}.json",true),"import file identity");out.references+=it.references;out.targets+=it.targets}
                file.path=="targets.json"->out.targets.addAll(FieldStudyJson.targets(text))
                file.path=="configuration.json"->FieldStudyJson.configuration(text).validate()
                file.path.startsWith("result-saves/")->FieldResultSaveIntent.decode(text).also{
                    fieldRequire(file.file.name.equals("${it.id}.json",true),"Result save file UUID")
                    if(!it.completed)out.issues+=FieldExportIssue("unresolved_application_save","field-study/${file.path}",it.id)else completedSaves+=it}
                file.path.startsWith("inventory-undos/")->FieldInventoryUndoIntent.decode(text).also{if(it.phase!=FieldInventoryUndoPhase.COMPLETE)out.issues+=FieldExportIssue("unresolved_inventory_undo","field-study/${file.path}")}
                file.path.startsWith("application-undos/")->FieldApplicationUndoBatch.decode(text).also{it.validate();if(!it.completed)out.issues+=FieldExportIssue("unresolved_application_undo","field-study/${file.path}")}
            }
        }catch(_:Exception){out.issues+=FieldExportIssue("corrupt_metadata","field-study/${file.path}")}
        for(intent in completedSaves.filter{it.replacements.isNotEmpty()||it.inventoryReplacement!=null}){
            val record=out.records.filter{it.id.equals(intent.id,true)};val r=record.singleOrNull()
            if(r!=null&&r.outcome==FieldAttemptOutcome.RESULT&&(r.selection in listOf(FieldSelectionStatus.ACCEPTED,FieldSelectionStatus.SUPERSEDED)||r.wasApplicationUndone)&&
                r.resultStore==intent.expected.store&&r.resultRecordId.equals(intent.expected.id,true)&&r.kind==intent.expected.kind&&r.liveValue==intent.expected.value)
                {if(r.wasApplicationUndone)continue
                    out.replacements+=intent.replacements.map{ReplacedResult(intent.id,it)}
                    intent.inventoryReplacement?.let{out.inventoryReplacements+=ReplacedInventory(intent.id,it)}}
            else out.issues+=FieldExportIssue("replacement_receipt_binding_mismatch","field-study/result-saves/${intent.id}.json",intent.id)
        }
        out.records.groupBy{it.id.lowercase()}.values.filter{it.size>1}.forEach{out.issues+=FieldExportIssue("duplicate_measurement_id","field-study/measurements",it[0].id)}
        out.references.groupBy{it.id.lowercase()}.values.filter{it.size>1}.forEach{out.issues+=FieldExportIssue("duplicate_reference_id","field-study/references")}
        if(out.records.isNotEmpty()&&metadata.none{it.path=="configuration.json"})out.issues+=FieldExportIssue("configuration_missing","field-study/configuration.json")
        if(out.targets.map{it.id}.toSet().size!=out.targets.size)out.issues+=FieldExportIssue("duplicate_target_id","field-study/targets.json")
        out.references.filter{it.supersedesId!=null&&out.references.none{parent->parent.id==it.supersedesId}}.forEach{out.issues+=FieldExportIssue("reference_parent_missing","field-study/references/${it.id}.json")}
        val bundles=raw.filter{it.path.contains('/')}.groupBy{it.path.substringBefore('/').lowercase()};val manifests=HashMap<String,JsonObject>()
        raw.filter{!it.path.contains('/')}.forEach{out.issues+=FieldExportIssue("unscoped_raw_file","raw-captures/${it.path}")}
        for((id,entries) in bundles){
            if(!validFieldUuid(id))out.issues+=FieldExportIssue("raw_bundle_id_invalid","raw-captures/$id")
            if(entries.map{it.path.substringBefore('/')}.toSet().size>1){out.issues+=FieldExportIssue("duplicate_raw_bundle_id","raw-captures/$id",id);continue}
            val file=entries.firstOrNull{it.path.substringAfter('/')=="manifest.json"}
            if(file==null){out.issues+=FieldExportIssue("raw_manifest_missing","raw-captures/$id",id);continue}
            if(file.bytes>64*1024*1024){out.issues+=FieldExportIssue("raw_manifest_too_large_to_parse","raw-captures/${file.path}",id);continue}
            val bytes=file.file.readBytes();check(hash(bytes)==proofs["raw-captures/${file.path}"]?.sha256){"Raw manifest changed during export"}
            val manifest=runCatching{Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject}.getOrNull()
            if(manifest==null){out.issues+=FieldExportIssue("raw_manifest_corrupt","raw-captures/${file.path}",id);continue}
            manifests[id]=manifest;val folder=file.path.substringBefore('/');checkFiles(manifest,folder,proofs,id,out.issues)
            val kind=manifest.s("kind");val schema=(manifest["schema"] as? JsonPrimitive)?.intOrNull
            if(schema !in listOf(1,2)||kind !in listOf("dbh","height")||manifest["context"] !is JsonObject||manifest["settings"] !is JsonObject||manifest["result_live"] !is JsonObject||manifest[kind.orEmpty()] !is JsonObject)
                out.issues+=FieldExportIssue("raw_manifest_incomplete_or_unsupported","raw-captures/${file.path}",id)
            if(manifest.s("measurement_id")==null)out.issues+=FieldExportIssue("legacy_raw_identity_unrecorded","raw-captures/${file.path}",id)
            else if(manifest.s("measurement_id")?.lowercase()!=id)out.issues+=FieldExportIssue("raw_identity_mismatch","raw-captures/${file.path}",id)
            (manifest["capture_evidence"] as? JsonObject)?.let{if(it.s("measurement_id")?.lowercase()!=id)out.issues+=FieldExportIssue("raw_evidence_identity_mismatch","raw-captures/${file.path}",id)}
            if(out.records.none{it.id.equals(id,true)})out.issues+=FieldExportIssue("raw_without_measurement","raw-captures/$folder",id)
        }
        val refs=out.references.groupBy{it.id.lowercase()}
        for(r in out.records){
            val id=r.id;val path="field-study/measurements/$id.json"
            if(r.outcome==FieldAttemptOutcome.STARTED)out.issues+=FieldExportIssue("unfinished_attempt",path,id)
            if(!r.protocolConforms)out.issues+=FieldExportIssue("protocol_runtime_mismatch",path,id)
            r.protocolBinding?.let{if(bindings[it.id]!=it)out.issues+=FieldExportIssue("protocol_baseline_missing_or_mismatch",path,id)}
            if(r.targetId!=null&&out.targets.none{it.id==r.targetId})out.issues+=FieldExportIssue("target_missing",path,id)
            for(ref in r.referenceIds){
                val matches=refs[ref.lowercase()]
                if(matches?.size!=1){out.issues+=FieldExportIssue("reference_missing_or_duplicate",path,id);continue}
                if(matches[0].targetId!=r.targetId||matches[0].method.kind!=r.kind)out.issues+=FieldExportIssue("reference_binding_mismatch",path,id)
                r.measurementScope?.references?.firstOrNull{it.id==ref}?.let{if(it!=matches[0])out.issues+=FieldExportIssue("reference_revision_mismatch",path,id)}
            }
            if(r.rawStatus!=FieldRawStatus.NOT_REQUESTED){
                if(r.rawStatus!=FieldRawStatus.SAVED)out.issues+=FieldExportIssue("raw_status_${r.rawStatus.raw}",path,id)
                val manifest=manifests[id.lowercase()]
                if(manifest==null)out.issues+=FieldExportIssue("measurement_raw_missing_or_corrupt",path,id)
                else {
                    if(!manifest.s("measurement_id").equals(id,true)||manifest.s("kind")!=r.kind.raw)out.issues+=FieldExportIssue("measurement_raw_binding_mismatch",path,id)
                    val evidence=manifest["capture_evidence"] as? JsonObject
                    if(evidence==null)out.issues+=FieldExportIssue("raw_evidence_unrecorded",path,id)
                    else{
                        if(r.outcome==FieldAttemptOutcome.RESULT){val captured=((evidence["displayed_result"] as? JsonObject)?.get("value") as? JsonPrimitive)?.doubleOrNull
                            if(captured==null||r.liveValue?.let{kotlin.math.abs(it-captured)<=maxOf(.000001,kotlin.math.abs(it)*.000001)}!=true)out.issues+=FieldExportIssue("raw_displayed_result_mismatch",path,id)}
                        if(r.configuration.preserveAiInputs&&r.ai.inferenceRan&&evidence["ai_inference"] !is JsonObject)out.issues+=FieldExportIssue("ai_input_unavailable",path,id)
                    }
                }
            }
            if(r.selection==FieldSelectionStatus.ACCEPTED){
                val current=results.filter{it.store==r.resultStore&&it.id.equals(r.resultRecordId,true)&&it.kind==r.kind}
                val expected=FieldExportApplicationResult(r.resultRecordId.orEmpty(),r.resultStore.orEmpty(),r.kind,r.liveValue?:Double.NaN,id)
                val matching=FieldResultSaveIntent.proofCandidates(expected,current,out.replacements.map{it.snapshot.result}+out.inventoryReplacements.flatMap{it.snapshot.results})
                if(matching.size!=1)out.issues+=FieldExportIssue("application_result_missing_or_duplicate",path,id)
                else if(matching[0].deleted||!matching[0].value.isFinite()||r.liveValue?.let{kotlin.math.abs(it-matching[0].value)>maxOf(.000001,kotlin.math.abs(it)*.000001)}!=false)out.issues+=FieldExportIssue("application_result_mismatch",path,id)
                else if(matching[0].measurementId==null)out.issues+=FieldExportIssue("application_measurement_identity_unrecorded",path,id)
                else if(!matching[0].measurementId.equals(id,true))out.issues+=FieldExportIssue("application_measurement_identity_mismatch",path,id)
            }
        }
        for(result in results){
            if(result.measurementId!=null){if(out.records.count{it.id.equals(result.measurementId,true)&&it.kind==result.kind&&it.resultStore==result.store&&it.resultRecordId.equals(result.id,true)}!=1)out.issues+=FieldExportIssue("application_without_measurement","application/result_index.json",result.measurementId)}
            else if(result.store=="quick_history")out.issues+=FieldExportIssue("application_identity_unrecorded","application/result_index.json")
            else if(!result.deleted&&out.records.none{it.selection==FieldSelectionStatus.ACCEPTED&&it.resultStore==result.store&&it.resultRecordId.equals(result.id,true)&&it.kind==result.kind})out.issues+=FieldExportIssue("application_without_measurement","application/${result.store}/${result.id}")
        }
        out.records.filter{it.selection==FieldSelectionStatus.ACCEPTED}.groupBy{"${it.resultStore}:${it.resultRecordId?.lowercase()}:${it.kind.raw}"}.values.filter{it.size>1}.forEach{group->
            group.filter{r->group.any{it.id!=r.id&&FieldMeasurementReplacementScope.matches(it,r)}}.forEach{
                out.issues+=FieldExportIssue("multiple_accepted_results","field-study/measurements/${it.id}.json",it.id)}}
        out.records.sortBy{it.id};out.references.sortBy{it.id};return out
    }
    private fun checkPhotos(value:JsonElement,proofs:Map<String,FieldExportFileProof>,issues:MutableList<FieldExportIssue>){
        if(value is JsonArray){value.forEach{checkPhotos(it,proofs,issues)};return};if(value !is JsonObject)return
        value.s("photoPath")?.takeIf{it.isNotEmpty()}?.let{name->val path="application/measure-photos/$name"
            if(name.contains('/')||name.contains('\\')||name=="."||name=="..")issues+=FieldExportIssue("unsafe_application_photo_reference",path)
            else if(path !in proofs)issues+=FieldExportIssue("application_photo_missing",path)}
        value.values.filter{it is JsonArray||it is JsonObject}.forEach{checkPhotos(it,proofs,issues)}
    }
    private fun checkFiles(value:JsonElement,folder:String,proofs:Map<String,FieldExportFileProof>,id:String,issues:MutableList<FieldExportIssue>){
        if(value is JsonArray){value.forEach{checkFiles(it,folder,proofs,id,issues)};return};if(value !is JsonObject)return
        for(key in listOf("file","depth_file","rgb_file","confidence_file")){
            val name=value.s(key)?:continue;val path="raw-captures/$folder/$name"
            if(name.isEmpty()||name.contains('/')||name.contains('\\')||name=="."||name==".."){issues+=FieldExportIssue("unsafe_raw_file_reference",path,id);continue}
            val proof=proofs[path];if(proof==null){issues+=FieldExportIssue("raw_file_missing",path,id);continue}
            if(key=="file"){
                (value["bytes"] as? JsonPrimitive)?.longOrNull?.let{if(it!=proof.bytes)issues+=FieldExportIssue("raw_file_size_mismatch",path,id)}
                value.s("sha256")?.let{if(it.lowercase()!=proof.sha256)issues+=FieldExportIssue("raw_file_checksum_mismatch",path,id)}
            }
            if(key=="depth_file"){
                val w=(value["width"] as? JsonPrimitive)?.intOrNull;val h=(value["height"] as? JsonPrimitive)?.intOrNull;val format=value.s("format")
                if(w!=null&&h!=null&&w in 1..4096&&h in 1..4096&&format!=null){val bytes=when(format){"f32m"->4;"u16mm"->2;else->0}
                    if(bytes==0||proof.bytes!=w.toLong()*h*bytes)issues+=FieldExportIssue("raw_depth_size_or_format_mismatch",path,id)}
                else issues+=FieldExportIssue("raw_depth_size_or_format_mismatch",path,id)
            }
            if(key=="confidence_file"){
                val w=(value["width"] as? JsonPrimitive)?.intOrNull;val h=(value["height"] as? JsonPrimitive)?.intOrNull
                if(w==null||h==null||w !in 1..4096||h !in 1..4096||proof.bytes!=w.toLong()*h)
                    issues+=FieldExportIssue("raw_confidence_size_mismatch",path,id)
            }
        }
        value.values.filter{it is JsonObject||it is JsonArray}.forEach{checkFiles(it,folder,proofs,id,issues)}
    }
    private fun csv(rows:List<List<String>>)=rows.joinToString("\r\n",postfix="\r\n"){row->row.joinToString(","){"\"${it.replace("\"","\"\"")}\""}}
    private fun measurementCsv(records:List<FieldMeasurementRecord>,issues:List<FieldExportIssue>):String{
        val header=listOf("measurement_id","session_id","study_id","tree_id","target_id","operator_id","kind","unit","planned_repeat","attempt_number","outcome","selection","displayed_value","displayed_sigma","replay_value","result_record_id","result_store","raw_status","raw_bundle_id","ai_enabled","ai_inference_ran","ai_mask_detected","ai_boundary_applied","user_adjusted_boundary","protocol_version","protocol_fingerprint","runtime_fingerprint","measurement_scope_id","reference_ids","ui_flow_id","screen_to_result_seconds","screen_to_accepted_seconds","attempt_elapsed_seconds","failure_reason","integrity_issues")
        return csv(listOf(header+listOf("displayed_sigma_unit","protocol_conforms","procedure_fingerprint","protocol_binding_id"))+records.map{r->listOf(r.id,r.sessionId,r.studyId,r.treeId,r.targetId.orEmpty(),r.operatorId,r.kind.raw,if(r.kind==FieldStudyKind.DBH)"cm"else"m",r.plannedRepeat.toString(),r.attemptNumber.toString(),r.outcome.raw,r.selection.raw,
            r.liveValue?.toString().orEmpty(),r.liveSigma?.toString().orEmpty(),r.replayValue?.toString().orEmpty(),r.resultRecordId.orEmpty(),r.resultStore.orEmpty(),r.rawStatus.raw,r.rawBundleId.orEmpty(),r.ai.enabled.toString(),r.ai.inferenceRan.toString(),r.ai.maskDetected.toString(),r.ai.boundaryApplied.toString(),r.ai.userAdjustedBoundary.toString(),
            r.configuration.protocolVersion,r.configuration.protocolFingerprint,FieldFingerprint.settings(r.runtimeSettings),r.measurementScope?.id.orEmpty(),r.referenceIds.joinToString("|"),r.uiFlowTiming?.id.orEmpty(),r.uiFlowTiming?.atResult?.elapsedSeconds?.toString().orEmpty(),r.uiFlowTiming?.atAccepted?.elapsedSeconds?.toString().orEmpty(),r.timing?.attemptElapsedSeconds?.toString().orEmpty(),r.failureReason.orEmpty(),issues.filter{it.measurementId.equals(r.id,true)}.joinToString("|"){it.code})+listOf(if(r.kind==FieldStudyKind.DBH)"mm"else"m",r.protocolConforms.toString(),r.protocolBinding?.procedureFingerprint.orEmpty(),r.protocolBinding?.id.orEmpty())})
    }
    private fun referenceCsv(refs:List<FieldReferenceObservation>)=csv(listOf(listOf("reference_id","target_id","method_id","method_name","kind","role","input_value","input_unit","base_value","base_unit","operator_id","planned_repeat","reference_repeat","supersedes_id","recorded_at"))+
        refs.map{r->listOf(r.id,r.targetId,r.method.id,r.method.name,r.method.kind.raw,r.method.role.raw,r.inputValue.toString(),r.method.unit.raw,r.baseValue.toString(),if(r.method.kind==FieldStudyKind.DBH)"cm"else"m",r.operatorId,r.plannedRepeat?.toString().orEmpty(),r.referenceRepeat.toString(),r.supersedesId.orEmpty(),r.recordedAt)})
    private val readme="""ForestiX field-study export, schema 1
All attempts and original metadata/raw bytes are retained, including failed,
cancelled, superseded, Undo, corrupt and partial records. No repair is run.
audit.json reports integrity issues; a ZIP is NOT proof of analysis readiness.
measurement_index.csv: displayed/captured result, NOT a replacement replay.
reference_index.csv includes every original GT/comparison revision and unit.
field-study/ includes full protocol, scope, UI-flow and Undo snapshots.
application/ contains application-store snapshots; formats are named by platform.
Journal metadata is an atomic read-only snapshot. Application-store snapshots
are collected separately, not a single cross-database transaction. The audit
reports exact identity/value mismatches. Integrity passed is not a replay,
accuracy, export re-import or field-readiness certification.
Exact UUID foreign keys only. Legacy records are never joined by value/time/label.
file_inventory.json gives SHA-256 and byte count for the exact bytes in every
ZIP payload, except file_inventory.json itself. ZIP CRC also covers each entry.
Raw changes during export cause failure; retry after recording has finished.
DBH base unit cm; height base unit m; elapsed durations are seconds.
"""
}
