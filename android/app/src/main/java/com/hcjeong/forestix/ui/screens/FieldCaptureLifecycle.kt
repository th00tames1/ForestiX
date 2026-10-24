package com.hcjeong.forestix.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hcjeong.forestix.LocalAppEnvironment
import com.hcjeong.forestix.data.fieldstudy.*
import com.hcjeong.forestix.sensors.DBHEstimator
import com.hcjeong.forestix.sensors.ProjectCalibration
import com.hcjeong.forestix.sensors.BoundaryAlignment
import com.hcjeong.forestix.AppEnvironment
import com.hcjeong.forestix.data.MeasureKind
import com.hcjeong.forestix.ui.PendingTreeNumber
import androidx.navigation.NavController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.UUID
import com.hcjeong.forestix.ui.screens.cruise.CruiseCapture

/** Read-only preparation; callers apply the complete handoff after an origin check. */
suspend fun prepareFieldQuickTarget(env:AppEnvironment,targetId:String,plotID:UUID?):PendingTreeNumber.Lock {
    env.fieldStudy.awaitPendingWrites();currentCoroutineContext().ensureActive()
    val study=env.fieldStudy.state.value
    val target=study.activeTargets.firstOrNull{it.id==targetId}
    val plot=plotID?:env.history.activePlotID.value?:env.history.defaultPlotID()
    if(!study.loaded||study.error!=null||!study.configuration.enabled||target==null||plot==null||env.history.plots.value.none{it.id==plot})
        throw FieldStudyException("missing","Target, study recording or Measure log plot unavailable")
    val fallback=env.history.defaultPlotID()
    val rows=env.history.entries.value.map{e->FieldApplicationReading(e.id.toString(),
        when(e.kind){MeasureKind.DBH->FieldStudyKind.DBH;MeasureKind.HEIGHT->FieldStudyKind.HEIGHT;else->null},
        (e.plotID?:fallback)?.toString(),e.treeNumber)}
    val binding=FieldTargetApplicationBinding.resolve(target,plot.toString(),rows,study.measurements)
    return PendingTreeNumber.Lock(number=binding.treeNumber,
        name=if(binding.existing)env.history.treeName(binding.treeNumber,plot)?:target.treeId else target.treeId,
        speciesCode=if(binding.existing)env.history.speciesCode(binding.treeNumber,plot)else null,plotID=plot,fieldTarget=target)
}

suspend fun switchFieldQuickTarget(env:AppEnvironment,nav:NavController,targetId:String,plotID:UUID?,route:String,uiFlow:FieldUIFlowClock?=null){
    val origin=nav.currentBackStackEntry?:throw CancellationException("Measurement route closed")
    val payload=prepareFieldQuickTarget(env,targetId,plotID)
    currentCoroutineContext().ensureActive()
    if(nav.currentBackStackEntry!=origin)throw CancellationException("Measurement route changed")
    val staged=PendingTreeNumber.stage(payload.copy(uiFlow=uiFlow));val previous=env.fieldStudy.state.value.selectedTargetId
    try{
        env.fieldStudy.selectTarget(targetId)
        nav.navigate(route){popUpTo(origin.destination.id){inclusive=true}}
    }catch(e:Exception){
        PendingTreeNumber.clearStaged(staged)
        if(env.fieldStudy.state.value.selectedTargetId==targetId)runCatching{env.fieldStudy.selectTarget(previous)}
        throw e
    }
}

suspend fun switchFieldCruiseTarget(env:AppEnvironment,nav:NavController,targetId:String,route:String,uiFlow:FieldUIFlowClock?){
    val entry=nav.currentBackStackEntry?:throw CancellationException("Measurement route closed")
    val origin=CruiseCapture.target?:throw CancellationException("Cruise session closed")
    val next=CruiseCapture.prepareFieldTarget(env,targetId,origin)
    currentCoroutineContext().ensureActive()
    if(nav.currentBackStackEntry!=entry)throw CancellationException("Measurement route changed")
    CruiseCapture.applyFieldTarget(origin,next)
    try{restartCruiseCapture(nav,route,uiFlow)}catch(e:Exception){
        if(CruiseCapture.target===next)CruiseCapture.applyFieldTarget(next,origin)
        throw e
    }
}
fun restartCruiseCapture(nav:NavController,route:String,uiFlow:FieldUIFlowClock?){
    val entry=nav.currentBackStackEntry?:throw CancellationException("Measurement route closed")
    val t=CruiseCapture.target?:throw CancellationException("Cruise session closed")
    val staged=PendingTreeNumber.stage(PendingTreeNumber.Lock(number=t.treeNumber,name=t.treeName,
        speciesCode=t.expectedTree?.speciesCode,plotID=t.plotId,fieldTarget=t.fieldTarget,uiFlow=uiFlow))
    try{nav.navigate(route){popUpTo(entry.destination.id){inclusive=true}}}
    catch(e:Exception){PendingTreeNumber.clearStaged(staged);throw e}
}

@Composable
fun rememberFieldCaptureCoordinator(kind:FieldStudyKind,calibration:ProjectCalibration,context:Map<String,String>,targetOverride:FieldStudyTarget?=null,useGlobalTarget:Boolean=true,uiFlow:FieldUIFlowClock?=null,scopeOverride:FieldMeasurementScope?=null):FieldCaptureCoordinator{
    val env=LocalAppEnvironment.current;val settings by env.settings.state.collectAsStateWithLifecycle()
    val study by env.fieldStudy.state.collectAsStateWithLifecycle();val owner=LocalLifecycleOwner.current
    val app=LocalContext.current.applicationContext
    val version=remember(app){runCatching{
        val p=app.packageManager.getPackageInfo(app.packageName,0)
        @Suppress("DEPRECATION") val code=if(android.os.Build.VERSION.SDK_INT>=28)p.longVersionCode else p.versionCode.toLong()
        (p.versionName?:"unknown") to code.toString()
    }.getOrDefault("unknown" to "unknown")}
    val capture=remember(env.fieldStudy,kind){FieldCaptureCoordinator(env.fieldStudy,kind)}
    val runtime=remember(settings,calibration,context,study.modelFingerprint){
        context+mapOf("field_session_version" to "1","platform" to "android","app_version" to version.first,
            "app_build" to version.second,"dbh_estimator_epoch" to DBHEstimator.ESTIMATOR_EPOCH.toString(),
            "dbh_method" to settings.dbhMeasurementMethod.raw,"dbh_chord_algorithm" to settings.dbhChordAlgorithm,
            "capture_frame_count" to settings.dbhCaptureMode.frameCount(settings.developerMode).toString(),
            "depth_sampling_fraction" to FieldFingerprint.number(BoundaryAlignment.SAMPLING_FRACTION),
            "depth_sampling_statistic" to "median","depth_surface_correction" to "cylinder_median_v1",
            "breast_height_guide_height_m" to FieldFingerprint.number(settings.breastHeightGuideHeight.meters),
            "height_method" to "vio_walkoff_tangent","height_procedure_version" to "1",
            "measurement_source" to if(kind==FieldStudyKind.DBH)"arcore_depth"else"arcore_pose",
            "model_sha256" to (study.modelFingerprint?:"unavailable"),
            "calibration.depth_noise_mm" to FieldFingerprint.number(calibration.depthNoiseMm.toDouble()),
            "calibration.alpha" to FieldFingerprint.number(calibration.dbhCorrectionAlpha.toDouble()),
            "calibration.beta" to FieldFingerprint.number(calibration.dbhCorrectionBeta.toDouble()),
            "calibration.epoch" to calibration.dbhCalibrationEpoch.toString(),
            "calibration.vio_drift_fraction" to FieldFingerprint.number(calibration.vioDriftFraction.toDouble()),
            "calibration.depth_discontinuity_m" to FieldFingerprint.number(calibration.depthDiscontinuityM.toDouble()))
    }
    SideEffect{capture.configure(runtime,"${context["application.plot_id"]?:""}:${context["application.tree_number"]?:""}",targetOverride,useGlobalTarget,uiFlow,scopeOverride)}
    LaunchedEffect(study.selectedTargetId){capture.selectedTargetChanged()}
    DisposableEffect(capture,owner){
        capture.appeared()
        val observer=LifecycleEventObserver{_,event->when(event){
            Lifecycle.Event.ON_RESUME->capture.foreground(true)
            Lifecycle.Event.ON_PAUSE,Lifecycle.Event.ON_STOP->capture.foreground(false)
            else->Unit}}
        owner.lifecycle.addObserver(observer)
        onDispose{owner.lifecycle.removeObserver(observer);capture.foreground(false);capture.close()}
    }
    return capture
}

@Composable
fun FieldCaptureWriteWarning(capture:FieldCaptureCoordinator,modifier:Modifier=Modifier){
    val error by capture.error.collectAsStateWithLifecycle()
    error?.let{Text(it,color=Color.White,fontSize=12.sp,modifier=modifier.fillMaxWidth().padding(horizontal=20.dp)
        .background(Color.Red.copy(alpha=.85f),RoundedCornerShape(8.dp)).padding(8.dp))}
}

@Composable
fun FieldReferencePeek(capture:FieldCaptureCoordinator,kind:FieldStudyKind){
    val env=LocalAppEnvironment.current;val settings by env.settings.state.collectAsStateWithLifecycle()
    val study by env.fieldStudy.state.collectAsStateWithLifecycle();val record by capture.displayRecord.collectAsStateWithLifecycle()
    val scoped by capture.scopedTarget.collectAsStateWithLifecycle()
    val global by capture.usesGlobalTargetSelection.collectAsStateWithLifecycle()
    val refs=remember(capture,kind,study,record,scoped,global,settings.developerMode){capture.previewReferences(kind,settings.developerMode)}
    var showing by remember{mutableStateOf(false)}
    LaunchedEffect(refs){if(refs.isEmpty())showing=false}
    if(refs.isNotEmpty()){
        Text("Reference preview · ${refs.size}",color=Color.White,fontSize=12.sp,
            modifier=Modifier.background(Color.Black.copy(alpha=.65f),RoundedCornerShape(16.dp))
                .clickable{showing=true}.padding(horizontal=10.dp,vertical=7.dp))
        if(showing&&settings.developerMode)AlertDialog(onDismissRequest={showing=false},title={Text("Reference preview")},
            text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
                refs.forEach{r->Column{
                    Text("${r.method.name} · ${String.format(java.util.Locale.US,"%.3f",r.inputValue)} ${r.method.unit.raw}")
                    Text((if(r.method.role==FieldReferenceRole.GROUND_TRUTH)"GT"else"Comparison")+" · Reference repeat ${r.referenceRepeat}",fontSize=11.sp)
                }}
            }},confirmButton={TextButton(onClick={showing=false}){Text("Done")}})
    }
}

@Composable
fun FieldScanIdentityBadge(capture:FieldCaptureCoordinator,kind:FieldStudyKind,modifier:Modifier=Modifier){
    val env=LocalAppEnvironment.current;val study by env.fieldStudy.state.collectAsStateWithLifecycle()
    val record by capture.displayRecord.collectAsStateWithLifecycle()
    val scoped by capture.scopedTarget.collectAsStateWithLifecycle()
    val global by capture.usesGlobalTargetSelection.collectAsStateWithLifecycle()
    val identity=FieldScanIdentity.snapshot(study.configuration,scoped?:study.selectedTarget.takeIf{global},record,kind)?:return
    Column(modifier.background(Color.Black.copy(alpha=.65f),RoundedCornerShape(8.dp)).padding(horizontal=10.dp,vertical=6.dp),
        horizontalAlignment=androidx.compose.ui.Alignment.CenterHorizontally){
        Text("Tree ID · ${identity.treeId?:"Unassigned"}",color=Color.White,fontSize=12.sp,maxLines=1,
            fontWeight=androidx.compose.ui.text.font.FontWeight.SemiBold,fontFamily=androidx.compose.ui.text.font.FontFamily.Monospace)
        Text("${identity.studyId} · Round ${identity.plannedRepeat}"+if(identity.recordingEnabled)""else" · Recording off",color=Color.White,fontSize=10.sp,maxLines=1)
    }
}
