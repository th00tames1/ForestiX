package com.hcjeong.forestix.ui.screens

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hcjeong.forestix.LocalAppEnvironment
import com.hcjeong.forestix.ar.ArController
import com.hcjeong.forestix.ar.Vec3
import com.hcjeong.forestix.data.fieldstudy.*
import com.hcjeong.forestix.positioning.LocationService
import kotlinx.coroutines.delay
import kotlin.math.*

private data class FieldCameraTargetMarker(val id:String,val label:String,val x:Float,val y:Float,val distanceM:Double)

@Composable
fun FieldTargetSwitchPresentation(coordinator:FieldTargetSwitchCoordinator){
    val busy by coordinator.busy.collectAsStateWithLifecycle()
    val pending by coordinator.pending.collectAsStateWithLifecycle()
    val error by coordinator.error.collectAsStateWithLifecycle()
    DisposableEffect(coordinator){onDispose{coordinator.cancel()}}
    pending?.let{choice->AlertDialog(onDismissRequest={coordinator.dismissConfirmation()},
        title={Text("Change measurement target?")},
        text={Text("Measure Tree ID ${choice.label}? Current setup and unsaved inputs will be cleared.")},
        confirmButton={TextButton(onClick={coordinator.confirm()}){Text("Change target")}},
        dismissButton={TextButton(onClick={coordinator.dismissConfirmation()}){Text("Cancel")}})}
    if(busy)AlertDialog(onDismissRequest={coordinator.cancel()},title={Text("Switching measurement target…")},
        text={CircularProgressIndicator()},confirmButton={TextButton(onClick={coordinator.cancel()}){Text("Cancel")}})
    error?.let{message->AlertDialog(onDismissRequest={coordinator.dismissError()},title={Text("Measurement target unavailable")},
        text={Text(message)},confirmButton={TextButton(onClick={coordinator.dismissError()}){Text("OK")}})}
}

/** Advisory horizontal locations only. Never a depth, anchor or capture gate. */
@Composable
fun FieldStudyCameraTargets(controller:ArController,canSelect:Boolean,capture:FieldCaptureCoordinator,onSelect:((String,String)->Unit)?=null){
    val env=LocalAppEnvironment.current;val state by env.fieldStudy.state.collectAsStateWithLifecycle()
    val settings by env.settings.state.collectAsStateWithLifecycle()
    val context=LocalContext.current;val lifecycle=LocalLifecycleOwner.current.lifecycle;val density=LocalDensity.current.density
    var size by remember{mutableStateOf(IntSize.Zero)}
    var markers by remember{mutableStateOf<List<FieldCameraTargetMarker>>(emptyList())}
    var status by remember{mutableStateOf(FieldOverlayStatus.DISABLED)}
    val currentState by rememberUpdatedState(state)
    val record by capture.displayRecord.collectAsStateWithLifecycle()
    val scoped by capture.scopedTarget.collectAsStateWithLifecycle()
    val global by capture.usesGlobalTargetSelection.collectAsStateWithLifecycle()
    val selectedId=if(record!=null)record?.targetId else scoped?.id?:state.selectedTargetId.takeIf{global}
    val currentSelected by rememberUpdatedState(selectedId)
    LaunchedEffect(state.configuration.protocolFingerprint,controller,size){
        val service=LocationService.shared(context)
        var previous=emptySet<String>();var fixStamp:Long?=null;var origin:Vec3?=null
        val alignment=FieldOverlayHeadingAlignment()
        while(true){
            val current=currentState;val config=current.configuration.ar
            if(!config.enabled||!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)){
                markers=emptyList();status=FieldOverlayStatus.DISABLED;previous=emptySet();fixStamp=null;alignment.reset()
                delay(200);continue
            }
            val camera=controller.fieldTargetCameraGeometry();val fix=service.latestSnapshot.value
            val heading=service.overlayHeading.value;val now=System.currentTimeMillis();val mono=SystemClock.elapsedRealtimeNanos()
            val trueHeading=alignment.update(camera?.worldYawDegrees,camera?.let{it.timestampNanos/1e9},heading?.trueDegrees,
                heading?.let{it.elapsedRealtimeNanos/1e9},mono/1e9,config.maximumHeadingAgeSeconds,camera!=null)
            val northOffset=alignment.worldNorthOffsetDegrees
            val location=fix?.let{FieldOverlayLocation(it.latitude,it.longitude,it.horizontalAccuracyM,(now-it.timestamp)/1000.0,
                trueHeading,heading?.accuracyDegrees,heading?.let{h->(mono-h.elapsedRealtimeNanos)/1e9},camera!=null)}
            val decision=FieldTargetOverlayPolicy.evaluate(config.copy(maximumPoints=20),current.configuration.studyId,
                current.overlayTargets,location,camera?.horizontalFieldOfViewDegrees?:0.0,previous,currentSelected)
            status=decision.status
            if(decision.status!=FieldOverlayStatus.READY||camera==null||fix==null||northOffset==null){
                markers=emptyList();previous=emptySet();fixStamp=null;delay(200);continue
            }
            if(fixStamp!=fix.timestamp){fixStamp=fix.timestamp;origin=camera.position}
            val anchor=origin?:camera.position;val next=ArrayList<FieldCameraTargetMarker>()
            val sx=if(controller.viewWidthPx>0)size.width.toFloat()/controller.viewWidthPx else 0f
            val sy=if(controller.viewHeightPx>0)size.height.toFloat()/controller.viewHeightPx else 0f
            for(candidate in decision.candidates){
                val angle=Math.toRadians(candidate.bearingDegrees+northOffset!!)
                val world=Vec3(anchor.x+(sin(angle)*candidate.distanceM).toFloat(),anchor.y,
                    anchor.z-(cos(angle)*candidate.distanceM).toFloat())
                val point=controller.projectToScreen(world)?:continue;val x=point.first*sx;val y=point.second*sy
                if(!x.isFinite()||!y.isFinite()||x<44*density||x>size.width-44*density||y<280*density||y>size.height-230*density||
                    next.any{abs(it.x-x)<82*density&&abs(it.y-y)<62*density})continue
                next.add(FieldCameraTargetMarker(candidate.targetId,candidate.label,x,y,candidate.distanceM))
                if(next.size>=config.maximumPoints)break
            }
            markers=next;previous=next.map{it.id}.toSet();delay(200)
        }
    }
    Box(Modifier.fillMaxSize().onSizeChanged{size=it}){
        if(state.configuration.ar.enabled&&status!=FieldOverlayStatus.READY&&status!=FieldOverlayStatus.DISABLED){
            Text("AR targets unavailable",color=Color.White,fontSize=10.sp,textAlign=TextAlign.Center,
                modifier=Modifier.fillMaxWidth().padding(top=250.dp))
        }
        markers.forEach{marker->key(marker.id){
            Column(Modifier.offset{IntOffset((marker.x-40*density).roundToInt(),(marker.y-52*density).roundToInt())}.width(80.dp),
                horizontalAlignment=androidx.compose.ui.Alignment.CenterHorizontally){
                Column(Modifier.background(if(marker.id==selectedId)Color(0xCC168847)else Color(0x99000000),RoundedCornerShape(16.dp))
                    .clickable(enabled=canSelect&&onSelect!=null){
                        if(scoped?.id!=marker.id)onSelect?.invoke(marker.id,currentState.activeTargets.firstOrNull{it.id==marker.id}?.treeId?:marker.label)
                    }.padding(horizontal=8.dp,vertical=6.dp),
                    horizontalAlignment=androidx.compose.ui.Alignment.CenterHorizontally){
                    Text(marker.label,color=Color.White,fontSize=12.sp,fontWeight=FontWeight.SemiBold,maxLines=1)
                    if(state.configuration.ar.showDistances)Text(com.hcjeong.forestix.common.MeasurementFormatter.navDistance(marker.distanceM,settings.unitSystem),color=Color.White,fontSize=10.sp)
                }
                Box(Modifier.width(1.dp).height(14.dp).background(Color.White.copy(alpha=.7f)))
                Box(Modifier.size(4.dp).background(Color.White,CircleShape))
            }
        }}
    }
}
