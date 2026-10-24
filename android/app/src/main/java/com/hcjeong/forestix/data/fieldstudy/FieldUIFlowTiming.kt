package com.hcjeong.forestix.data.fieldstudy

import java.lang.ref.WeakReference
import java.time.Instant
import kotlin.math.abs

data class FieldUIFlowDuration(val elapsedSeconds:Double,val foregroundSeconds:Double,val inactiveSeconds:Double,val clockValid:Boolean){
    fun validate(){fieldRequire(listOf(elapsedSeconds,foregroundSeconds,inactiveSeconds).all{it.isFinite()&&it>=0}&&
        abs(elapsedSeconds-foregroundSeconds-inactiveSeconds)<.000001,"UI flow duration")}
}
data class FieldUIFlowTiming(val id:String,val kind:FieldStudyKind,val openedAt:String,val current:FieldUIFlowDuration,
    val atResult:FieldUIFlowDuration?=null,val atAccepted:FieldUIFlowDuration?=null,val schema:Int=1){
    fun validate(){
        fieldRequire(schema==1&&validFieldUuid(id)&&openedAt.isNotEmpty(),"UI flow identity")
        current.validate();atResult?.validate();atAccepted?.validate()
        fieldRequire((atResult?.let{it.elapsedSeconds<=current.elapsedSeconds}?:true)&&
            (atAccepted?.let{it.elapsedSeconds<=current.elapsedSeconds&&it.elapsedSeconds>=(atResult?.elapsedSeconds?:0.0)}?:true),"UI flow event order")
    }
    fun continues(previous:FieldUIFlowTiming?):Boolean=previous==null||(id==previous.id&&kind==previous.kind&&openedAt==previous.openedAt&&
        current.elapsedSeconds>=previous.current.elapsedSeconds&&current.foregroundSeconds+.000001>=previous.current.foregroundSeconds&&
        current.inactiveSeconds+.000001>=previous.current.inactiveSeconds&&(previous.current.clockValid||!current.clockValid)&&
        (previous.atResult==null||previous.atResult==atResult)&&
        (previous.atAccepted==null||previous.atAccepted==atAccepted))
}

/** Lazy first appearance, shared only across the same-kind UI target rebind. */
class FieldUIFlowClock(val kind:FieldStudyKind,val id:String=newFieldId()){
    private var clock:FieldMeasurementClock?=null;private var openedAt:String?=null
    @Synchronized fun open(now:Double,foreground:Boolean,openedAt:String=Instant.now().toString()){
        if(clock==null){clock=FieldMeasurementClock(now);this.openedAt=openedAt};clock?.setForeground(foreground,now)
    }
    @Synchronized fun foreground(active:Boolean,now:Double){clock?.setForeground(active,now)}
    @Synchronized fun evidence(now:Double,previous:FieldUIFlowTiming?,result:Boolean,accepted:Boolean):FieldUIFlowTiming?{
        val t=clock?.snapshot(now)?:return null
        val duration=FieldUIFlowDuration(t.screenElapsedSeconds,t.activeElapsedSeconds,t.backgroundSeconds,t.clockValid)
        return FieldUIFlowTiming(id,kind,requireNotNull(openedAt),duration,
            previous?.atResult?:duration.takeIf{result},previous?.atAccepted?:duration.takeIf{accepted}).also{
            it.validate();if(!it.continues(previous))throw FieldStudyException("conflict","UI flow identity or frozen event")}
    }
}

/** Activity lifecycle ONLY; navigation disposal is not app backgrounding. */
class FieldUIFlowRegistry {
    companion object{val shared=FieldUIFlowRegistry()}
    private val owners=HashSet<String>();private val flows=HashMap<String,WeakReference<FieldUIFlowClock>>()
    @Synchronized fun owner(id:String,active:Boolean,now:Double){
        val before=owners.isNotEmpty();if(active)owners.add(id)else owners.remove(id)
        flows.entries.removeAll{it.value.get()==null};val after=owners.isNotEmpty()
        if(before!=after)flows.values.forEach{it.get()?.foreground(after,now)}
    }
    @Synchronized fun track(flow:FieldUIFlowClock,now:Double){
        flows.entries.removeAll{it.value.get()==null};flows[flow.id]=WeakReference(flow);flow.open(now,owners.isNotEmpty())
    }
}
