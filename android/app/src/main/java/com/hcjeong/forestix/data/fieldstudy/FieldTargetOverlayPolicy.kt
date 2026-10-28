package com.hcjeong.forestix.data.fieldstudy

import kotlin.math.*

object FieldTargetProgress {
    fun completed(studyId:String,plannedRepeat:Int,records:List<FieldMeasurementRecord>):Set<String> =
        records.filter{it.studyId==studyId&&it.plannedRepeat==plannedRepeat&&it.selection==FieldSelectionStatus.ACCEPTED&&
            it.outcome==FieldAttemptOutcome.RESULT&&it.liveValue?.let{v->v.isFinite()&&v>0}==true&&it.resultRecordId!=null&&it.targetId!=null}
            .groupBy{it.targetId!!}.filterValues{rows->rows.map{it.kind}.toSet().containsAll(FieldStudyKind.entries)}.keys
    fun visible(c:FieldStudyConfiguration,targets:List<FieldStudyTarget>,records:List<FieldMeasurementRecord>):List<FieldStudyTarget>{
        val done=if(c.hideCompletedTargets)completed(c.studyId,c.plannedRepeat,records)else emptySet()
        return targets.filter{it.studyId==c.studyId&&it.id !in done}
    }
}
data class FieldScanIdentity(val studyId:String,val treeId:String?,val plannedRepeat:Int,val kind:FieldStudyKind,
    val measurementId:String?,val recordingEnabled:Boolean){
    companion object{
        fun snapshot(c:FieldStudyConfiguration,selected:FieldStudyTarget?,record:FieldMeasurementRecord?,kind:FieldStudyKind):FieldScanIdentity?{
            if(record!=null&&record.selection !in listOf(FieldSelectionStatus.DISCARDED,FieldSelectionStatus.SUPERSEDED))
                return FieldScanIdentity(record.studyId,if(record.targetId==null)null else record.treeId,record.plannedRepeat,record.kind,record.id,true)
            if(!c.enabled&&selected==null)return null
            return FieldScanIdentity(c.studyId,selected?.takeIf{it.studyId==c.studyId}?.treeId,c.plannedRepeat,kind,null,c.enabled)
        }
    }
}

data class FieldOverlayLocation(val latitude:Double,val longitude:Double,val horizontalAccuracyM:Double,
    val fixAgeSeconds:Double,val headingTrueDegrees:Double?,val headingAccuracyDegrees:Double?,
    val headingAgeSeconds:Double?,val arTrackingNormal:Boolean)
enum class FieldOverlayStatus(val raw:String){
    READY("ready"),DISABLED("disabled"),POSITION_UNAVAILABLE("position_unavailable"),POSITION_STALE("position_stale"),
    POSITION_UNCERTAIN("position_uncertain"),HEADING_UNAVAILABLE("heading_unavailable"),HEADING_STALE("heading_stale"),
    HEADING_UNCERTAIN("heading_uncertain"),TRACKING_UNAVAILABLE("tracking_unavailable"),VIEWPORT_UNAVAILABLE("viewport_unavailable")
}
data class FieldOverlayCandidate(val targetId:String,val label:String,val distanceM:Double,val bearingDegrees:Double,
    val relativeBearingDegrees:Double,val uncertaintyDegrees:Double,val targetAccuracyKnown:Boolean)
data class FieldOverlayDecision(val status:FieldOverlayStatus,val candidates:List<FieldOverlayCandidate>)

/** Advisory target selection only: there is intentionally no measurement/capture gate here. */
object FieldTargetOverlayPolicy {
    fun quality(c:FieldARConfiguration,f:FieldOverlayLocation?):FieldOverlayStatus {
        if(!c.enabled)return FieldOverlayStatus.DISABLED
        if(runCatching{c.validate()}.isFailure||f==null||!f.latitude.isFinite()||!f.longitude.isFinite()||
            f.latitude !in -90.0..90.0||f.longitude !in -180.0..180.0)return FieldOverlayStatus.POSITION_UNAVAILABLE
        if(!f.fixAgeSeconds.isFinite()||f.fixAgeSeconds<0||f.fixAgeSeconds>c.maximumFixAgeSeconds)return FieldOverlayStatus.POSITION_STALE
        if(!f.horizontalAccuracyM.isFinite()||f.horizontalAccuracyM<0||f.horizontalAccuracyM>c.maximumHorizontalAccuracyM)return FieldOverlayStatus.POSITION_UNCERTAIN
        if(f.headingTrueDegrees==null||!f.headingTrueDegrees.isFinite()||f.headingTrueDegrees<0||f.headingTrueDegrees>=360)return FieldOverlayStatus.HEADING_UNAVAILABLE
        if(f.headingAgeSeconds==null||!f.headingAgeSeconds.isFinite()||f.headingAgeSeconds<0||f.headingAgeSeconds>c.maximumHeadingAgeSeconds)return FieldOverlayStatus.HEADING_STALE
        if(f.headingAccuracyDegrees==null||!f.headingAccuracyDegrees.isFinite()||f.headingAccuracyDegrees<0||f.headingAccuracyDegrees>c.maximumHeadingErrorDegrees)return FieldOverlayStatus.HEADING_UNCERTAIN
        if(!f.arTrackingNormal)return FieldOverlayStatus.TRACKING_UNAVAILABLE
        return FieldOverlayStatus.READY
    }
    fun evaluate(configuration:FieldARConfiguration,studyId:String,targets:List<FieldStudyTarget>,location:FieldOverlayLocation?,
        horizontalFieldOfViewDegrees:Double,previouslyVisible:Set<String> = emptySet(),selectedTargetId:String?=null):FieldOverlayDecision {
        val status=quality(configuration,location)
        if(status!=FieldOverlayStatus.READY||location==null)return FieldOverlayDecision(status,emptyList())
        if(!horizontalFieldOfViewDegrees.isFinite()||horizontalFieldOfViewDegrees<=0||horizontalFieldOfViewDegrees>=180)
            return FieldOverlayDecision(FieldOverlayStatus.VIEWPORT_UNAVAILABLE,emptyList())
        val candidates=ArrayList<FieldOverlayCandidate>()
        for(t in targets){
            if(t.studyId!=studyId||t.latitude==null||t.longitude==null||runCatching{t.validate()}.isFailure)continue
            val (distance,bearing)=geometry(location.latitude,location.longitude,t.latitude,t.longitude)
            val limit=configuration.maximumDistanceM+if(t.id in previouslyVisible)configuration.distanceHysteresisM else 0.0
            if(distance<=0.01||distance>limit)continue
            val relative=wrappedDegrees(bearing-location.headingTrueDegrees!!)
            if(abs(relative)>horizontalFieldOfViewDegrees/2)continue
            val error=atan2(location.horizontalAccuracyM+(t.horizontalAccuracyM?:0.0),distance)*180/PI+location.headingAccuracyDegrees!!
            candidates.add(FieldOverlayCandidate(t.id,t.label,distance,bearing,relative,min(180.0,error),t.horizontalAccuracyM!=null))
        }
        val sorted=candidates.sortedWith(compareByDescending<FieldOverlayCandidate>{it.targetId==selectedTargetId}.thenBy{it.distanceM}.thenBy{it.targetId})
        return FieldOverlayDecision(FieldOverlayStatus.READY,sorted.take(configuration.maximumPoints))
    }
    fun wrappedDegrees(value:Double):Double {val v=(value+180)%360;return (if(v<0)v+360 else v)-180}
    fun geometry(latitude:Double,longitude:Double,targetLatitude:Double,targetLongitude:Double):Pair<Double,Double>{
        val p1=latitude*PI/180;val p2=targetLatitude*PI/180;val dp=p2-p1;val dl=wrappedDegrees(targetLongitude-longitude)*PI/180
        val a=sin(dp/2).pow(2)+cos(p1)*cos(p2)*sin(dl/2).pow(2)
        val distance=6371008.8*2*atan2(sqrt(a.coerceIn(0.0,1.0)),sqrt(max(0.0,1-a)))
        val bearing=atan2(sin(dl)*cos(p2),cos(p1)*sin(p2)-sin(p1)*cos(p2)*cos(dl))*180/PI
        return distance to (bearing+360)%360
    }
}
