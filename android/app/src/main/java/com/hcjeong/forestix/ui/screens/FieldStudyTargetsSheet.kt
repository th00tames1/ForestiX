package com.hcjeong.forestix.ui.screens

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hcjeong.forestix.LocalAppEnvironment
import com.hcjeong.forestix.data.fieldstudy.*
import com.hcjeong.forestix.geo.FieldTargetFileReader
import com.hcjeong.forestix.ui.screens.project.MenuPickerRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
private fun FieldCatalogDialog(title:String,busy:Boolean,onDismiss:()->Unit,content:@Composable ColumnScope.()->Unit){
    Dialog(onDismissRequest={if(!busy)onDismiss()},properties=DialogProperties(usePlatformDefaultWidth=false)){
        Surface(Modifier.fillMaxSize().systemBarsPadding()){
            Column(Modifier.fillMaxSize().padding(16.dp)){
                Row(Modifier.fillMaxWidth()){
                    Text(title,style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f))
                    TextButton(onClick=onDismiss,enabled=!busy){Text("Close")}
                }
                if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp),content=content)
            }
        }
    }
}

@Composable
fun FieldStudyTargetsSheet(onDismiss:()->Unit){
    val env=LocalAppEnvironment.current;val state by env.fieldStudy.state.collectAsStateWithLifecycle()
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    var busy by remember{mutableStateOf(false)};var message by remember{mutableStateOf<String?>(null)}
    var codec by remember{mutableStateOf("auto")};var source by remember{mutableStateOf<FieldTargetSource?>(null)}
    var selected by remember{mutableStateOf<String?>(null)};var adding by remember{mutableStateOf(false)}
    var page by remember{mutableStateOf(0)}
    val sortedTargets=remember(state.targets,state.configuration.studyId){state.activeTargets.sortedBy{it.treeId}}
    val lastPage=maxOf(0,(sortedTargets.size-1)/100)
    LaunchedEffect(lastPage){page=page.coerceAtMost(lastPage)}
    val launcher=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null){
        busy=true;message=null;val encoding=codec.takeIf{it!="auto"}
        scope.launch{
            try{source=withContext(Dispatchers.IO){
                val name=context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use{c->
                    if(c.moveToFirst())c.getString(0)else null}?:uri.lastPathSegment?:"targets"
                val bytes=context.contentResolver.openInputStream(uri)?.use{input->
                    val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                    while(true){val count=input.read(buffer);if(count<0)break
                        if(output.size()+count>FieldTargetFileReader.maximumBytes)throw FieldStudyException("invalid","Target file exceeds 16 MiB")
                        output.write(buffer,0,count)
                    };output.toByteArray()}?:throw FieldStudyException("missing","Target file unavailable")
                FieldTargetFileReader.read(bytes,name,encoding)
            }}catch(e:Exception){message=e.message};busy=false
        }
    }}
    FieldCatalogDialog("Target catalog",busy,onDismiss){
        Text("Study: ${state.configuration.studyId}")
        val codecs=listOf("auto","UTF-8","Windows-1252","CP949")
        MenuPickerRow("DBF encoding",if(codec=="auto")"Automatic (.cpg)"else codec,listOf("Automatic (.cpg)")+codecs.drop(1)){if(!busy)codec=codecs[it]}
        Button(onClick={launcher.launch(arrayOf("application/zip","application/geo+json","application/json","application/octet-stream"))},
            enabled=!busy&&state.configuration.studyId.isNotBlank()){Text("Import SHP ZIP / GeoJSON")}
        OutlinedButton(onClick={adding=true},enabled=!busy&&state.configuration.studyId.isNotBlank()){Text("Add target")}
        Text("SHP ZIP requires matching .shp, .dbf and WGS 84 .prj files. Select attribute fields before importing.",style=MaterialTheme.typography.bodySmall)
        message?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        Text("Targets (${state.activeTargets.size})",style=MaterialTheme.typography.titleMedium)
        if(sortedTargets.size>100)Row{
            TextButton(onClick={page--},enabled=!busy&&page>0){Text("Previous")}
            Text("${page+1} / ${lastPage+1}")
            TextButton(onClick={page++},enabled=!busy&&page<lastPage){Text("Next")}
        }
        sortedTargets.drop(page*100).take(100).forEach{target->
            ListItem(headlineContent={Text(target.label)},supportingContent={Text(target.treeId)},
                trailingContent={if(state.selectedTargetId==target.id)Text("Selected")},modifier=Modifier.clickable(enabled=!busy){selected=target.id})
            HorizontalDivider()
        }
    }
    source?.let{parsed->FieldTargetMappingSheet(parsed,onDismiss={source=null})}
    if(adding)FieldManualTargetSheet{adding=false}
    selected?.let{id->FieldStudyTargetDetailSheet(id){selected=null}}
}

@Composable
private fun FieldImportPicker(title:String,value:String,fields:List<String>,required:Boolean=false,onValue:(String)->Unit){
    val options=listOf(if(required)"Select field"else"None")+fields
    MenuPickerRow(title,if(value.isEmpty())options[0]else value,options){onValue(if(it==0)""else fields[it-1])}
}

@Composable
private fun FieldTargetMappingSheet(source:FieldTargetSource,onDismiss:()->Unit){
    val env=LocalAppEnvironment.current;val state by env.fieldStudy.state.collectAsStateWithLifecycle()
    val settings by env.settings.state.collectAsStateWithLifecycle();val scope=rememberCoroutineScope()
    var mapping by remember{mutableStateOf(FieldTargetMapping(""))};var busy by remember{mutableStateOf(false)}
    var message by remember{mutableStateOf<String?>(null)}
    FieldCatalogDialog("Attribute mapping",busy,onDismiss){
        Text(source.name);Text("${source.features.size} targets · ${source.skippedRecords} null/deleted records excluded")
        FieldImportPicker("Tree ID",mapping.treeIdField,source.fields,true){if(!busy)mapping=mapping.copy(treeIdField=it)}
        FieldImportPicker("Label",mapping.labelField?:"",source.fields){if(!busy)mapping=mapping.copy(labelField=it.ifEmpty{null})}
        FieldImportPicker("Altitude",mapping.altitudeField?:"",source.fields){if(!busy)mapping=mapping.copy(altitudeField=it.ifEmpty{null})}
        MenuPickerRow("Altitude unit",mapping.altitudeUnit.raw,listOf("m","ft")){if(!busy)mapping=mapping.copy(altitudeUnit=if(it==0)FieldReferenceUnit.M else FieldReferenceUnit.FT)}
        FieldImportPicker("Horizontal uncertainty (m)",mapping.accuracyField?:"",source.fields){if(!busy)mapping=mapping.copy(accuracyField=it.ifEmpty{null})}
        if(settings.developerMode){
            Text("Reference attributes",style=MaterialTheme.typography.titleMedium)
            state.configuration.referenceMethods.filter{it.enabled}.forEach{method->
                FieldImportPicker("${method.name} (${method.unit.raw})",mapping.referenceFields[method.id]?:"",source.fields){field->
                    if(!busy)mapping=mapping.copy(referenceFields=if(field.isEmpty())mapping.referenceFields-method.id else mapping.referenceFields+(method.id to field))}
            }
            val rounds=listOf("All repetitions")+(1..state.configuration.plannedRepetitions).map{it.toString()}
            MenuPickerRow("Planned repetition",mapping.referencePlannedRepeat?.toString()?:rounds[0],rounds){if(!busy)mapping=mapping.copy(referencePlannedRepeat=it.takeIf{it!=0})}
        }
        source.features.firstOrNull()?.let{f->
            Text("Preview · first feature",style=MaterialTheme.typography.titleMedium)
            source.fields.forEach{field->Text("$field: ${f.value(field)?:"—"}",style=MaterialTheme.typography.bodySmall)}
        }
        message?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        Button(onClick={
            busy=true;message=null;val safeMapping=if(settings.developerMode)mapping else mapping.copy(referenceFields=emptyMap())
            scope.launch{try{
                val batch=withContext(Dispatchers.Default){FieldTargetMaterializer.make(source,safeMapping,state.configuration,settings.developerMode)}
                env.fieldStudy.commitImport(batch,env.settings.state.value.developerMode);onDismiss()
            }catch(e:Exception){message=e.message};busy=false}
        },enabled=!busy&&mapping.treeIdField.isNotEmpty()){Text("Import targets")}
    }
}

@Composable
private fun FieldManualTargetSheet(onDismiss:()->Unit){
    val env=LocalAppEnvironment.current;val state by env.fieldStudy.state.collectAsStateWithLifecycle();val scope=rememberCoroutineScope()
    var id by remember{mutableStateOf("")};var label by remember{mutableStateOf("")}
    var lat by remember{mutableStateOf("")};var lon by remember{mutableStateOf("")}
    var busy by remember{mutableStateOf(false)};var message by remember{mutableStateOf<String?>(null)}
    FieldCatalogDialog("New target",busy,onDismiss){
        OutlinedTextField(id,{id=it},label={Text("Tree ID")},enabled=!busy,singleLine=true)
        OutlinedTextField(label,{label=it},label={Text("Label")},enabled=!busy,singleLine=true)
        OutlinedTextField(lat,{lat=it},label={Text("Latitude (optional)")},enabled=!busy,singleLine=true)
        OutlinedTextField(lon,{lon=it},label={Text("Longitude (optional)")},enabled=!busy,singleLine=true)
        message?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        Button(onClick={busy=true;scope.launch{try{
            if(!(lat.isBlank()&&lon.isBlank())&&(lat.toDoubleOrNull()==null||lon.toDoubleOrNull()==null))throw FieldStudyException("invalid","Latitude/longitude pair required")
            val target=FieldStudyTarget(state.configuration.studyId,id.trim(),label.ifBlank{id.trim()},lat.toDoubleOrNull(),lon.toDoubleOrNull())
            env.fieldStudy.saveTargets(state.targets+target);onDismiss()
        }catch(e:Exception){message=e.message};busy=false}},enabled=!busy&&id.isNotBlank()){Text("Add target")}
    }
}

@Composable
fun FieldStudyTargetDetailSheet(targetId:String,onMeasure:((FieldStudyKind)->Unit)?=null,onDismiss:()->Unit){
    val env=LocalAppEnvironment.current;val state by env.fieldStudy.state.collectAsStateWithLifecycle()
    val settings by env.settings.state.collectAsStateWithLifecycle();val target=state.targets.firstOrNull{it.id==targetId}
    var editing by remember{mutableStateOf(false)};var revision by remember{mutableStateOf<FieldReferenceObservation?>(null)}
    var message by remember{mutableStateOf<String?>(null)}
    FieldCatalogDialog(target?.label?:"Target",false,onDismiss){
        target?.let{t->
            Text("Tree ID: ${t.treeId}")
            if(t.latitude!=null&&t.longitude!=null)Text("WGS 84: ${t.latitude}, ${t.longitude}")
            Button(onClick={try{env.fieldStudy.selectTarget(t.id)}catch(e:Exception){message=e.message}},enabled=t.studyId==state.configuration.studyId){
                Text(if(t.id==state.selectedTargetId)"Selected for measurement"else"Select for measurement")}
            onMeasure?.let{measure->
                Text("Measure log",style=MaterialTheme.typography.titleMedium)
                val ready=state.loaded&&state.error==null&&state.configuration.enabled&&t.studyId==state.configuration.studyId
                Button(onClick={measure(FieldStudyKind.DBH)},enabled=ready){Text("Measure DBH")}
                Button(onClick={measure(FieldStudyKind.HEIGHT)},enabled=ready){Text("Measure height")}
                Text(if(state.configuration.enabled)"Target measurements are saved in Measure log."else
                    "Enable field-study recording in Settings to link target measurements.",style=MaterialTheme.typography.bodySmall)
            }
            message?.let{Text(it,color=MaterialTheme.colorScheme.error)}
            if(settings.developerMode){
                FieldStudyKind.entries.forEach{kind->
                    Text(if(kind==FieldStudyKind.DBH)"DBH references"else"Height references",style=MaterialTheme.typography.titleMedium)
                    state.references(t.id,kind).forEach{ref->
                        TextButton(onClick={revision=ref;editing=true}){Text("${ref.method.name} · ${ref.referenceRepeat}: ${ref.inputValue} ${ref.method.unit.raw}")}
                    }
                }
                OutlinedButton(onClick={revision=null;editing=true}){Text("Add reference observation")}
            }
        }
    }
    if(editing)FieldReferenceEntrySheet(targetId,revision){editing=false}
}

@Composable
private fun FieldReferenceEntrySheet(targetId:String,revision:FieldReferenceObservation?,onDismiss:()->Unit){
    val env=LocalAppEnvironment.current;val state by env.fieldStudy.state.collectAsStateWithLifecycle()
    val settings by env.settings.state.collectAsStateWithLifecycle();val scope=rememberCoroutineScope()
    var methodId by remember{mutableStateOf(revision?.method?.id?:"")};var value by remember{mutableStateOf(revision?.inputValue?.toString()?:"")}
    var round by remember{mutableStateOf(revision?.plannedRepeat?:0)};var repeat by remember{mutableStateOf(revision?.referenceRepeat?:1)}
    var busy by remember{mutableStateOf(false)};var message by remember{mutableStateOf<String?>(null)}
    val methods=state.configuration.referenceMethods.filter{it.enabled}
    FieldCatalogDialog("Reference observation",busy,onDismiss){
        if(settings.developerMode){
            MenuPickerRow("Method",revision?.method?.name?:methods.firstOrNull{it.id==methodId}?.name?:"Select method",listOf("Select method")+methods.map{"${it.name} (${it.unit.raw})"}){
                if(!busy&&revision==null)methodId=if(it==0)""else methods[it-1].id}
            OutlinedTextField(value,{value=it},label={Text("Reference value")},enabled=!busy,singleLine=true)
            val rounds=listOf("All repetitions")+(1..state.configuration.plannedRepetitions).map{it.toString()}
            MenuPickerRow("Planned repetition",if(round==0)rounds[0]else round.toString(),rounds){if(!busy&&revision==null)round=it}
            Row{
                Text("Reference repetition: $repeat",modifier=Modifier.weight(1f))
                TextButton(onClick={repeat--},enabled=!busy&&revision==null&&repeat>1){Text("−")}
                TextButton(onClick={repeat++},enabled=!busy&&revision==null&&repeat<999){Text("+")}
            }
            Button(onClick={busy=true;scope.launch{try{
                val number=value.toDoubleOrNull()?:throw FieldStudyException("invalid","Reference value required")
                val method=revision?.method?:methods.firstOrNull{it.id==methodId}?:throw FieldStudyException("invalid","Reference method required")
                val ref=FieldReferenceObservation(targetId=targetId,method=method,inputValue=number,plannedRepeat=round.takeIf{it!=0},
                    referenceRepeat=repeat,operatorId=state.configuration.operatorId,supersedesId=revision?.id)
                env.fieldStudy.appendReference(ref,env.settings.state.value.developerMode);onDismiss()
            }catch(e:Exception){message=e.message};busy=false}},enabled=!busy&&methodId.isNotEmpty()&&value.isNotBlank()){
                Text(if(revision==null)"Save observation"else"Save revision")}
        }
        message?.let{Text(it,color=MaterialTheme.colorScheme.error)}
    }
}
