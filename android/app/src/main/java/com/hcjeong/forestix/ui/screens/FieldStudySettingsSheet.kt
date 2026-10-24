package com.hcjeong.forestix.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import com.hcjeong.forestix.ui.shareFile
import com.hcjeong.forestix.LocalAppEnvironment
import com.hcjeong.forestix.data.fieldstudy.*
import com.hcjeong.forestix.ui.screens.project.FormSection
import com.hcjeong.forestix.ui.screens.project.MenuPickerRow
import kotlinx.coroutines.launch
import java.util.Locale
import com.hcjeong.forestix.common.UnitSystem
import com.hcjeong.forestix.common.Units
import com.hcjeong.forestix.ui.theme.Forestix

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FieldStudySettingsSheet(onDismiss:()->Unit) {
    val env=LocalAppEnvironment.current
    val state by env.fieldStudy.state.collectAsStateWithLifecycle()
    val settings by env.settings.state.collectAsStateWithLifecycle()
    var draft by remember{mutableStateOf(state.configuration)}
    var saving by remember{mutableStateOf(false)}
    var message by remember{mutableStateOf<String?>(null)}
    var targetsOpen by remember{mutableStateOf(false)}
    var exporting by remember{mutableStateOf(false)}
    var exportArtifact by remember{mutableStateOf<FieldStudyExportArtifact?>(null)}
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    LaunchedEffect(state.loaded,state.configuration){if(state.loaded)draft=state.configuration}
    val enabled=state.loaded&&!saving&&!exporting&&state.error==null
    val colors=Forestix.colors
    val type=Forestix.type
    fun distanceLabel(meters:Double)=if(settings.unitSystem==UnitSystem.IMPERIAL)
        String.format(Locale.US,"%.1f ft",Units.metersToFeet(meters)) else String.format(Locale.US,"%.1f m",meters)
    fun updateMethod(method:FieldReferenceMethod){if(enabled)draft=draft.copy(referenceMethods=draft.referenceMethods.map{if(it.id==method.id)method else it})}
    ModalBottomSheet(onDismissRequest={if(!saving&&!exporting)onDismiss()},containerColor=colors.canvas,modifier=Modifier.fillMaxHeight(.96f)){
        CompositionLocalProvider(LocalTextStyle provides type.body,LocalContentColor provides colors.textPrimary){
        Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically){
            Text("Field study",style=type.bodyBold,modifier=Modifier.weight(1f))
            TextButton(onClick=onDismiss,enabled=!saving&&!exporting){Text("Close")}
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            item{FormSection(header="Study protocol"){
                FieldStudyToggle("Record field-study measurements",draft.enabled,enabled){draft=draft.copy(enabled=it)}
                OutlinedTextField(draft.studyId,{draft=draft.copy(studyId=it)},label={Text("Study ID")},singleLine=true,enabled=enabled,modifier=Modifier.fillMaxWidth())
                OutlinedTextField(draft.operatorId,{draft=draft.copy(operatorId=it)},label={Text("Operator ID")},singleLine=true,enabled=enabled,modifier=Modifier.fillMaxWidth())
                OutlinedTextField(draft.protocolVersion,{draft=draft.copy(protocolVersion=it)},label={Text("Protocol version")},singleLine=true,enabled=enabled,modifier=Modifier.fillMaxWidth())
                FieldStudyToggle("Lock protocol",draft.protocolLocked,enabled){draft=draft.copy(protocolLocked=it)}
                FieldStudyNumber("Planned repetitions",draft.plannedRepetitions.toDouble(),1.0..999.0,1.0,enabled){
                    draft=draft.copy(plannedRepetitions=it.toInt(),plannedRepeat=minOf(draft.plannedRepeat,it.toInt()))}
                FieldStudyNumber("Current repetition",draft.plannedRepeat.toDouble(),1.0..draft.plannedRepetitions.toDouble(),1.0,enabled){draft=draft.copy(plannedRepeat=it.toInt())}
                Text("After recording starts, change the protocol version before changing measurement settings.",style=type.caption,color=colors.textSecondary)
            }}
            item{FormSection(header="Recording"){
                FieldStudyToggle("Record elapsed time",draft.recordTiming,enabled){draft=draft.copy(recordTiming=it)}
                FieldStudyToggle("Record failed attempts",draft.recordFailedAttempts,enabled){draft=draft.copy(recordFailedAttempts=it)}
                FieldStudyToggle("Preserve AI input and mask",draft.preserveAiInputs,enabled){draft=draft.copy(preserveAiInputs=it)}
            }}
            item{FormSection(header="Target map layer"){
                OutlinedButton(onClick={targetsOpen=true},enabled=enabled&&state.configuration.studyId.isNotBlank()){
                    Text("Target catalog / Reference observations")}
                FieldStudyToggle("Display target layer",draft.mapOverlayEnabled,enabled){draft=draft.copy(mapOverlayEnabled=it)}
                FieldStudyToggle("Hide completed targets",draft.hideCompletedTargets,enabled){draft=draft.copy(hideCompletedTargets=it)}
            }}
            item{FormSection(header="AR target overlay"){
                FieldStudyToggle("Display nearby targets in AR",draft.ar.enabled,enabled){draft=draft.copy(ar=draft.ar.copy(enabled=it))}
                FieldStudyNumber("Maximum distance",draft.ar.maximumDistanceM,1.0..100.0,1.0,enabled,displayValue=distanceLabel(draft.ar.maximumDistanceM)){draft=draft.copy(ar=draft.ar.copy(maximumDistanceM=it))}
                FieldStudyNumber("Maximum targets",draft.ar.maximumPoints.toDouble(),1.0..20.0,1.0,enabled){draft=draft.copy(ar=draft.ar.copy(maximumPoints=it.toInt()))}
                FieldStudyNumber("Maximum horizontal uncertainty",draft.ar.maximumHorizontalAccuracyM,0.1..100.0,0.5,enabled,displayValue=distanceLabel(draft.ar.maximumHorizontalAccuracyM)){draft=draft.copy(ar=draft.ar.copy(maximumHorizontalAccuracyM=it))}
                FieldStudyNumber("Maximum position age (s)",draft.ar.maximumFixAgeSeconds,0.1..10.0,0.5,enabled){draft=draft.copy(ar=draft.ar.copy(maximumFixAgeSeconds=it))}
                FieldStudyNumber("Maximum heading age (s)",draft.ar.maximumHeadingAgeSeconds,0.1..10.0,0.5,enabled){draft=draft.copy(ar=draft.ar.copy(maximumHeadingAgeSeconds=it))}
                FieldStudyNumber("Maximum heading uncertainty (°)",draft.ar.maximumHeadingErrorDegrees,0.1..45.0,1.0,enabled){draft=draft.copy(ar=draft.ar.copy(maximumHeadingErrorDegrees=it))}
                FieldStudyNumber("Range buffer",draft.ar.distanceHysteresisM,0.0..10.0,0.5,enabled,displayValue=distanceLabel(draft.ar.distanceHysteresisM)){draft=draft.copy(ar=draft.ar.copy(distanceHysteresisM=it))}
                FieldStudyToggle("Display target distances",draft.ar.showDistances,enabled){draft=draft.copy(ar=draft.ar.copy(showDistances=it))}
                Text("Approximate locations. Poor GPS hides markers, not measurement.",style=type.caption,color=colors.textSecondary)
            }}
            if(settings.developerMode){
                for(kind in FieldStudyKind.entries){
                    item{Text(if(kind==FieldStudyKind.DBH)"DBH reference methods" else "Height reference methods",style=type.sectionHead,color=colors.textTertiary)}
                    items(draft.referenceMethods.filter{it.kind==kind},key={it.id}){method->
                        FormSection(header=method.name){
                            OutlinedTextField(method.name,{updateMethod(method.copy(name=it))},label={Text("Method name")},singleLine=true,enabled=enabled,modifier=Modifier.fillMaxWidth())
                            FieldStudyToggle("Enabled",method.enabled,enabled){updateMethod(method.copy(enabled=it))}
                            val units=FieldReferenceUnit.entries.filter{it.supports(kind)}
                            MenuPickerRow("Unit",method.unit.raw,units.map{it.raw}){if(enabled)updateMethod(method.copy(unit=units[it]))}
                            MenuPickerRow("Role",if(method.role==FieldReferenceRole.GROUND_TRUTH)"Ground truth" else "Comparison",listOf("Ground truth","Comparison")){
                                if(enabled)updateMethod(method.copy(role=if(it==0)FieldReferenceRole.GROUND_TRUTH else FieldReferenceRole.COMPARISON))}
                        }
                    }
                    item{OutlinedButton(onClick={draft=draft.copy(referenceMethods=draft.referenceMethods+FieldReferenceMethod(
                        name="Comparison ${draft.referenceMethods.count{it.kind==kind}+1}",kind=kind,
                        unit=if(kind==FieldStudyKind.DBH)FieldReferenceUnit.CM else FieldReferenceUnit.M))},enabled=enabled&&draft.referenceMethods.size<50,
                        modifier=Modifier.semantics{contentDescription="Add ${if(kind==FieldStudyKind.DBH)"DBH"else"height"} reference method"}){Text("Add reference method")}}
                }
                item{FormSection(header="Reference visibility"){
                    FieldStudyToggle("Allow reference preview during measurement",draft.showReferencesDuringMeasurement,enabled){draft=draft.copy(showReferencesDuringMeasurement=it)}
                }}
            }
            state.error?.let{error->item{Text(error,color=MaterialTheme.colorScheme.error)}}
            if(state.issues.isNotEmpty())item{Text("${state.issues.size} archive integrity issues. Original files have been retained.",color=MaterialTheme.colorScheme.error)}
            message?.let{text->item{Text(text,modifier=Modifier.semantics{contentDescription="Field study settings: $text"})}}
            item{FormSection(header="Complete field-study export"){
                OutlinedButton(onClick={exporting=true;exportArtifact=null;message=null
                    scope.launch{try{
                        val artifact=FieldStudyExportApplication.build(context,env);exportArtifact=artifact
                        message="Exported ${artifact.report.measurementCount} attempts, ${artifact.report.referenceCount} reference observations and ${artifact.report.rawBundleCount} raw bundles. Integrity issues: ${artifact.report.issues.size}. See audit.json."
                    }catch(e:Exception){message="Export failed: ${e.message?:"Archive unavailable"}"}finally{exporting=false}}},
                    enabled=state.loaded&&!saving&&!exporting){Text(if(exporting)"Exporting…"else"Export records, raw and integrity audit")}
                Text("Includes records and raw files. Missing files are listed in audit.json.",style=type.caption,color=colors.textSecondary)
                exportArtifact?.let{artifact->TextButton(onClick={runCatching{shareFile(context,FieldStudyExportApplication.uri(context,artifact),"application/zip")}.onFailure{message="Share failed: ${it.message}"}}){Text("Share field-study ZIP")}}
            }}
            item{Button(onClick={saving=true;message=null;val next=FieldProtocolBinding.preparing(draft,state.configuration);val developer=settings.developerMode
                scope.launch{try{env.fieldStudy.saveConfiguration(next,developer);message="Configuration saved."}
                    catch(e:Exception){message=e.message?:"Configuration could not be saved."}finally{saving=false}}},
                enabled=enabled,modifier=Modifier.fillMaxWidth()){Text(if(saving)"Saving…" else "Save configuration")}}
            item{Spacer(Modifier.height(24.dp))}
        }
        }
    }
    if(targetsOpen)FieldStudyTargetsSheet{targetsOpen=false}
}

@Composable private fun FieldStudyToggle(label:String,value:Boolean,enabled:Boolean,onChange:(Boolean)->Unit){
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
        Text(label,modifier=Modifier.weight(1f));Switch(checked=value,onCheckedChange=onChange,enabled=enabled)}
}
@Composable private fun FieldStudyNumber(label:String,value:Double,range:ClosedFloatingPointRange<Double>,step:Double,enabled:Boolean,displayValue:String?=null,onChange:(Double)->Unit){
    Column{
        Text("$label: ${displayValue?:String.format(Locale.US,if(value%1.0==0.0)"%.0f" else "%.1f",value)}")
        Row{
            OutlinedButton(onClick={onChange((value-step).coerceIn(range))},enabled=enabled&&value>range.start,
                modifier=Modifier.semantics{contentDescription="Decrease $label"}){Text("−")}
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick={onChange((value+step).coerceIn(range))},enabled=enabled&&value<range.endInclusive,
                modifier=Modifier.semantics{contentDescription="Increase $label"}){Text("+")}
        }
    }
}
