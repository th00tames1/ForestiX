package com.hcjeong.forestix.data.fieldstudy

import android.content.Context
import androidx.core.content.FileProvider
import com.hcjeong.forestix.AppEnvironment
import com.hcjeong.forestix.data.EntryRow
import com.hcjeong.forestix.data.PlotRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File

object FieldStudyExportApplication {
    suspend fun build(context:Context,env:AppEnvironment):FieldStudyExportArtifact{
        env.fieldStudy.awaitCaptureEvidence();env.fieldStudy.awaitPendingWrites()
        return withContext(Dispatchers.IO){
            val quick=env.history.fieldStudySnapshot()
            val inventory=env.projectRepository.list().flatMap{project->env.plotRepository.listByProject(project.id).flatMap{plot->env.treeRepository.listByPlot(plot.id,includeDeleted=true)}}
            val results=quick.entries.mapNotNull{e->FieldStudyKind.entries.firstOrNull{it.raw==e.kind}?.let{kind->
                FieldExportApplicationResult(e.id,"quick_history",kind,e.value,e.measurementId)}}.toMutableList()
            for(t in inventory){
                results+=FieldExportApplicationResult(t.id.toString(),"cruise",FieldStudyKind.DBH,t.dbhCm.toDouble(),t.dbhMeasurementId,deleted=t.deletedAt!=null)
                t.heightM?.let{results+=FieldExportApplicationResult(t.id.toString(),"cruise",FieldStudyKind.HEIGHT,it.toDouble(),t.heightMeasurementId,deleted=t.deletedAt!=null)}
            }
            val payloads=mutableListOf("quick-history-android.json" to JsonArray(quick.entries.map(::entryJson)).toString().toByteArray(Charsets.UTF_8),
                "quick-plots-android.json" to JsonArray(quick.plots.map(::plotJson)).toString().toByteArray(Charsets.UTF_8),
                "inventory-trees-android.json" to JsonArray(inventory.map(FieldInventoryTreeJSON::encode)).toString().toByteArray(Charsets.UTF_8),
                "species-catalog.json" to JsonArray(env.speciesCatalog.state.value.records.map { it.json() }).toString().toByteArray(Charsets.UTF_8))
            val log=File(context.filesDir,"forestix_research_log.csv");val files=if(log.exists())listOf("research-log-original.csv" to log)else emptyList()
            FieldStudyExport.build(env.fieldStudy.archive,File(context.filesDir,"raw-captures"),results,payloads,File(context.cacheDir,"Exports/FieldStudy"),applicationFiles=files,
                applicationDirectories=listOf("measure-photos" to File(context.filesDir,"measure-photos")))
        }
    }
    fun uri(context:Context,artifact:FieldStudyExportArtifact)=FileProvider.getUriForFile(context,"${context.packageName}.fileprovider",artifact.file)
    private fun json(fields:Map<String,Any?>)=JsonObject(fields.mapValues{(_,v)->when(v){null->JsonNull;is Boolean->JsonPrimitive(v);is Number->JsonPrimitive(v);else->JsonPrimitive(v.toString())}})
    internal fun entryJson(e:EntryRow)=json(linkedMapOf(
        "id" to e.id,"kind" to e.kind,"value" to e.value,"secondaryValue" to e.secondaryValue,"sigma" to e.sigma,"confidenceRaw" to e.confidenceRaw,"method" to e.method,"createdAt" to e.createdAt,
        "treeNumber" to e.treeNumber,"treeName" to e.treeName,"plotID" to e.plotID,"speciesCode" to e.speciesCode,"position" to e.position,"damageCodes" to e.damageCodes,"note" to e.note,
        "latitude" to e.latitude,"longitude" to e.longitude,"photoPath" to e.photoPath,"captureMode" to e.captureMode,"truth" to e.truth,"truthSource" to e.truthSource,"truthUnit" to e.truthUnit,
        "positionSource" to e.positionSource,"timeSource" to e.timeSource,"measurementId" to e.measurementId))
    private fun plotJson(p:PlotRow)=json(linkedMapOf("id" to p.id,"name" to p.name,"unitName" to p.unitName,"acres" to p.acres,"typeRaw" to p.typeRaw,"baf" to p.baf,"radiusFt" to p.radiusFt,
        "parentPlotID" to p.parentPlotID,"nestedKind" to p.nestedKind,"createdAt" to p.createdAt,"isDefault" to p.isDefault))
}
