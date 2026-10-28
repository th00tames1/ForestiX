package com.hcjeong.forestix.data.fieldstudy

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import java.util.UUID

/** Application metadata only: no estimator, camera, depth-cache or positioning dependency. */
enum class FieldStudyKind(val raw:String) { DBH("dbh"), HEIGHT("height") }
enum class FieldReferenceRole(val raw:String) { GROUND_TRUTH("ground_truth"), COMPARISON("comparison") }
enum class FieldReferenceUnit(val raw:String) {
    CM("cm"), INCH("in"), M("m"), FT("ft");
    fun supports(kind:FieldStudyKind)=if(kind==FieldStudyKind.DBH)this==CM||this==INCH else this==M||this==FT
    fun baseValue(value:Double)=when(this){CM,M->value;INCH->value*2.54;FT->value*0.3048}
}

class FieldStudyException(val code:String,message:String):IllegalArgumentException(message)
internal fun fieldRequire(condition:Boolean,detail:String) {
    if(!condition)throw FieldStudyException("invalid","Invalid field-study data: $detail")
}
fun newFieldId():String=UUID.randomUUID().toString().uppercase(Locale.US)
internal fun validFieldUuid(value:String):Boolean=try {
    UUID.fromString(value).toString().equals(value,ignoreCase=true)
}catch(_:IllegalArgumentException){false}

object FieldFingerprint {
    fun digest(parts:List<String>):String {
        val hash=MessageDigest.getInstance("SHA-256")
        for(part in parts){val bytes=part.toByteArray(Charsets.UTF_8)
            hash.update(ByteBuffer.allocate(4).putInt(bytes.size).array());hash.update(bytes)}
        return hash.digest().joinToString(""){"%02x".format(it.toInt() and 255)}
    }
    fun number(value:Double):String=String.format(Locale.US,"%016x",value.toRawBits())
    fun settings(values:Map<String,String>):String=digest(values.keys.sorted().flatMap{listOf(it,values.getValue(it))})
}

data class FieldReferenceMethod(
    val id:String=newFieldId(),val name:String,val kind:FieldStudyKind,val unit:FieldReferenceUnit,
    val role:FieldReferenceRole=FieldReferenceRole.COMPARISON,val enabled:Boolean=true,
){
    fun validate(){fieldRequire(id.isNotBlank()&&name.isNotBlank()&&id.toByteArray().size<=200&&
        name.toByteArray().size<=200&&unit.supports(kind),"reference method")}
}

data class FieldARConfiguration(
    val enabled:Boolean=false,val maximumDistanceM:Double=20.0,val maximumPoints:Int=5,
    val maximumHorizontalAccuracyM:Double=3.0,val maximumFixAgeSeconds:Double=2.0,
    val maximumHeadingAgeSeconds:Double=2.0,val maximumHeadingErrorDegrees:Double=10.0,
    val distanceHysteresisM:Double=2.0,val showDistances:Boolean=false,
){
    fun validate(){
        fieldRequire(listOf(maximumDistanceM,maximumHorizontalAccuracyM,maximumFixAgeSeconds,
            maximumHeadingAgeSeconds,maximumHeadingErrorDegrees,distanceHysteresisM).all{it.isFinite()}&&
            maximumDistanceM in 1.0..100.0&&maximumPoints in 1..20&&maximumHorizontalAccuracyM in 0.1..100.0&&
            maximumFixAgeSeconds in 0.1..10.0&&maximumHeadingAgeSeconds in 0.1..10.0&&
            maximumHeadingErrorDegrees in 0.1..45.0&&distanceHysteresisM in 0.0..10.0,"AR overlay configuration")
    }
}

data class FieldStudyConfiguration(
    val schema:Int=1,val enabled:Boolean=false,val studyId:String="",val operatorId:String="",
    val protocolVersion:String="1",val protocolLocked:Boolean=false,val plannedRepetitions:Int=3,
    val plannedRepeat:Int=1,val recordTiming:Boolean=true,val recordFailedAttempts:Boolean=true,
    val preserveAiInputs:Boolean=true,val mapOverlayEnabled:Boolean=true,val hideCompletedTargets:Boolean=false,
    val showReferencesDuringMeasurement:Boolean=false,val ar:FieldARConfiguration=FieldARConfiguration(),
    val referenceMethods:List<FieldReferenceMethod> = listOf(
        FieldReferenceMethod("dbh-gt","DBH reference",FieldStudyKind.DBH,FieldReferenceUnit.CM,FieldReferenceRole.GROUND_TRUTH),
        FieldReferenceMethod("height-gt","Height reference",FieldStudyKind.HEIGHT,FieldReferenceUnit.M,FieldReferenceRole.GROUND_TRUTH)),
    val pinnedRuntimeSettings:Map<String,String> = emptyMap(),
){
    fun validate(){
        if(schema!=1)throw FieldStudyException("schema","Unsupported field-study schema $schema.")
        fieldRequire((!enabled||(studyId.isNotBlank()&&operatorId.isNotBlank()))&&protocolVersion.isNotBlank()&&
            listOf(studyId,operatorId,protocolVersion).all{it.toByteArray().size<=200}&&
            plannedRepetitions in 1..999&&plannedRepeat in 1..plannedRepetitions&&referenceMethods.size<=50&&
            referenceMethods.map{it.id}.toSet().size==referenceMethods.size,"study identity, repetitions or duplicate reference methods")
        ar.validate();referenceMethods.forEach{it.validate()}
    }
    val protocolFingerprint:String get(){
        val p=mutableListOf("field-study-protocol-v1",studyId,protocolVersion,plannedRepetitions.toString(),
            recordTiming.toString(),recordFailedAttempts.toString(),preserveAiInputs.toString(),mapOverlayEnabled.toString(),
            hideCompletedTargets.toString(),showReferencesDuringMeasurement.toString(),ar.enabled.toString(),
            FieldFingerprint.number(ar.maximumDistanceM),ar.maximumPoints.toString(),
            FieldFingerprint.number(ar.maximumHorizontalAccuracyM),FieldFingerprint.number(ar.maximumFixAgeSeconds),
            FieldFingerprint.number(ar.maximumHeadingAgeSeconds),FieldFingerprint.number(ar.maximumHeadingErrorDegrees),
            FieldFingerprint.number(ar.distanceHysteresisM),ar.showDistances.toString())
        for(m in referenceMethods.sortedBy{it.id})p.addAll(listOf(m.id,m.name,m.kind.raw,m.unit.raw,m.role.raw,m.enabled.toString()))
        p.add(FieldFingerprint.settings(pinnedRuntimeSettings));return FieldFingerprint.digest(p)
    }
    fun validateTransition(previous:FieldStudyConfiguration,developerMode:Boolean){
        validate()
        if(!developerMode&&(referenceMethods!=previous.referenceMethods||
                    showReferencesDuringMeasurement!=previous.showReferencesDuringMeasurement))
            throw FieldStudyException("developer_required","Ground-truth editing requires developer mode.")
        if(previous.protocolLocked&&studyId==previous.studyId&&protocolVersion==previous.protocolVersion&&
            (protocolFingerprint!=previous.protocolFingerprint||!protocolLocked))
            throw FieldStudyException("protocol_locked","The protocol is locked. Create a new protocol version before changing its configuration.")
    }
}

data class FieldStudyTarget(
    val studyId:String,val treeId:String,val label:String,val latitude:Double?=null,val longitude:Double?=null,
    val altitudeM:Double?=null,val horizontalAccuracyM:Double?=null,val sourceLayerId:String="manual",
    val sourceFeatureId:String="",val sourceCrs:String="EPSG:4326",val attributes:Map<String,String> = emptyMap(),
    val id:String=FieldFingerprint.digest(listOf(studyId,treeId)),
){
    fun validate(){fieldRequire(studyId.isNotEmpty()&&treeId.isNotEmpty()&&label.isNotEmpty()&&
        listOf(studyId,treeId,label,sourceLayerId,sourceFeatureId).all{it.toByteArray().size<=500}&&
        id==FieldFingerprint.digest(listOf(studyId,treeId))&&
        ((latitude==null&&longitude==null)||(latitude?.let{it.isFinite()&&it in -90.0..90.0}==true&&
            longitude?.let{it.isFinite()&&it in -180.0..180.0}==true))&&(altitudeM?.isFinite()?:true)&&
        (horizontalAccuracyM?.let{it.isFinite()&&it>=0}?:true),"target identity or coordinates")}
}

data class FieldReferenceObservation(
    val id:String=newFieldId(),val targetId:String,val method:FieldReferenceMethod,val inputValue:Double,
    val plannedRepeat:Int?=null,val referenceRepeat:Int=1,val operatorId:String,
    val recordedAt:String=Instant.now().toString(),val supersedesId:String?=null,val source:String="typed",
    val baseValue:Double=method.unit.baseValue(inputValue),
){
    fun validate(developerMode:Boolean){
        if(!developerMode)throw FieldStudyException("developer_required","Ground-truth editing requires developer mode.")
        method.validate()
        fieldRequire(validFieldUuid(id)&&targetId.isNotEmpty()&&operatorId.isNotEmpty()&&inputValue.isFinite()&&inputValue>0&&
            baseValue.isFinite()&&baseValue>0&&baseValue==method.unit.baseValue(inputValue)&&(plannedRepeat?.let{it>0}?:true)&&
            referenceRepeat>0&&recordedAt.isNotEmpty()&&(supersedesId?.let{validFieldUuid(it)&&it!=id}?:true),"reference observation")
    }
}

enum class FieldAttemptOutcome(val raw:String){ STARTED("started"),RESULT("result"),FAILED("failed"),CANCELLED("cancelled"),INTERRUPTED("interrupted") }
enum class FieldSelectionStatus(val raw:String){ PENDING("pending"),ACCEPTED("accepted"),DISCARDED("discarded"),SUPERSEDED("superseded") }
enum class FieldRawStatus(val raw:String){ NOT_REQUESTED("not_requested"),PENDING("pending"),SAVED("saved"),FAILED("failed"),MISSING("missing") }

data class FieldAIProvenance(
    val enabled:Boolean=false,val inferenceRan:Boolean=false,val maskDetected:Boolean=false,
    val boundaryApplied:Boolean=false,val userAdjustedBoundary:Boolean=false,val inferenceId:String?=null,val reason:String?=null,
){
    fun validate(){fieldRequire((!maskDetected||inferenceRan)&&(!boundaryApplied||(maskDetected&&inferenceRan))&&
        (!boundaryApplied||inferenceId!=null),"AI provenance")}
}

data class FieldTimingSnapshot(
    val screenElapsedSeconds:Double,val activeElapsedSeconds:Double,val backgroundSeconds:Double,
    val attemptElapsedSeconds:Double?,val attemptActiveSeconds:Double?,val firstValidResultElapsedSeconds:Double?,
    val attemptFirstResultSeconds:Double?,val acceptedElapsedSeconds:Double?,val clockValid:Boolean,
)

data class FieldMeasurementEvent(val action:String,val detail:String?=null,val timestamp:String=Instant.now().toString())
data class FieldMeasurementRecord(
    val id:String=newFieldId(),val sessionId:String,val treeId:String,val targetId:String?=null,
    val targetSnapshot:FieldStudyTarget?=null,val kind:FieldStudyKind,
    val attemptNumber:Int,val configuration:FieldStudyConfiguration,val runtimeSettings:Map<String,String>,
    val startedAt:String=Instant.now().toString(),val schema:Int=1,val studyId:String=configuration.studyId,
    val operatorId:String=configuration.operatorId,val plannedRepeat:Int=configuration.plannedRepeat,
    val outcome:FieldAttemptOutcome=FieldAttemptOutcome.STARTED,val selection:FieldSelectionStatus=FieldSelectionStatus.PENDING,
    val failureReason:String?=null,val liveValue:Double?=null,val liveSigma:Double?=null,val replayValue:Double?=null,
    val rawStatus:FieldRawStatus=FieldRawStatus.NOT_REQUESTED,val rawBundleId:String?=null,val rawFailureReason:String?=null,
    val ai:FieldAIProvenance=FieldAIProvenance(),val timing:FieldTimingSnapshot?=null,
    val referenceIds:List<String> = emptyList(),val revision:Int=0,val events:List<FieldMeasurementEvent> = emptyList(),
    val resultRecordId:String?=null,val resultStore:String?=null,
    val uiFlowTiming:FieldUIFlowTiming?=null,
    val measurementScope:FieldMeasurementScope?=null,
    val protocolBinding:FieldProtocolBinding?=null,
){
    val protocolConforms:Boolean get()=if(configuration.protocolLocked&&configuration.pinnedRuntimeSettings==FieldProtocolBinding.policy)
        protocolBinding?.matches(configuration,kind,runtimeSettings)==true else !configuration.protocolLocked||
            (configuration.pinnedRuntimeSettings.isNotEmpty()&&configuration.pinnedRuntimeSettings==runtimeSettings)
    val wasApplicationUndone:Boolean get()=selection==FieldSelectionStatus.DISCARDED&&outcome==FieldAttemptOutcome.RESULT&&
        resultStore=="cruise"&&resultRecordId!=null&&events.any{it.action=="application_undo"&&it.detail?.let(::validFieldUuid)==true}
    fun validate(){
        if(schema!=1)throw FieldStudyException("schema","Unsupported field-study schema $schema.")
        configuration.validate();ai.validate();protocolBinding?.validate()
        fieldRequire(validFieldUuid(id)&&validFieldUuid(sessionId)&&studyId.isNotEmpty()&&treeId.isNotEmpty()&&
            operatorId.isNotEmpty()&&studyId==configuration.studyId&&operatorId==configuration.operatorId&&
            plannedRepeat in 1..configuration.plannedRepetitions&&attemptNumber>0&&revision>=0&&
            (liveValue?.let{it.isFinite()&&it>0}?:true)&&(liveSigma?.let{it.isFinite()&&it>=0}?:true)&&
            (replayValue?.let{it.isFinite()&&it>0}?:true)&&(rawBundleId==null||rawBundleId==id)&&
            (rawStatus!=FieldRawStatus.SAVED||rawBundleId==id)&&
            ((resultRecordId==null&&resultStore==null)||(resultRecordId!=null&&validFieldUuid(resultRecordId)&&resultStore in listOf("quick_history","cruise")))&&
            (outcome!=FieldAttemptOutcome.RESULT||liveValue!=null)&&
            (targetId==null||targetSnapshot?.let{it.id==targetId&&it.studyId==studyId&&it.treeId==treeId}==true)&&
            (selection!=FieldSelectionStatus.ACCEPTED||(outcome==FieldAttemptOutcome.RESULT&&liveValue!=null))&&
            (outcome!=FieldAttemptOutcome.FAILED||!failureReason.isNullOrEmpty()),"measurement record")
        targetSnapshot?.validate()
        measurementScope?.let{s->s.validate();fieldRequire(configuration==s.configuration&&targetSnapshot==s.target&&
            referenceIds==s.references(kind).map{it.id},"measurement scope binding")}
        uiFlowTiming?.let{ui->ui.validate();fieldRequire(ui.kind==kind&&configuration.recordTiming&&
            (ui.atResult==null||outcome==FieldAttemptOutcome.RESULT)&&
            (ui.atAccepted==null||selection in listOf(FieldSelectionStatus.ACCEPTED,FieldSelectionStatus.SUPERSEDED)||wasApplicationUndone),"UI flow measurement binding")}
        timing?.let{t->val durations=listOf(t.screenElapsedSeconds,t.activeElapsedSeconds,t.backgroundSeconds)+
            listOfNotNull(t.attemptElapsedSeconds,t.attemptActiveSeconds,t.firstValidResultElapsedSeconds,t.attemptFirstResultSeconds,t.acceptedElapsedSeconds)
            fieldRequire(durations.all{it.isFinite()&&it>=0}&&
                kotlin.math.abs(t.screenElapsedSeconds-t.activeElapsedSeconds-t.backgroundSeconds)<0.000001&&
                (t.attemptElapsedSeconds?.let{it<=t.screenElapsedSeconds}?:true)&&
                (t.firstValidResultElapsedSeconds?.let{it<=t.screenElapsedSeconds}?:true)&&
                (t.acceptedElapsedSeconds?.let{it<=t.screenElapsedSeconds}?:true)&&
                (t.attemptActiveSeconds?.let{it<=(t.attemptElapsedSeconds?:-1.0)}?:true)&&
                (t.attemptFirstResultSeconds?.let{it<=(t.attemptElapsedSeconds?:-1.0)}?:true),"measurement timing")}
    }
}
