package com.hcjeong.forestix.data.fieldstudy

import com.hcjeong.forestix.data.cruise.*
import com.hcjeong.forestix.sensors.*
import kotlinx.serialization.json.*
import java.util.UUID

/** Full native Tree snapshot, not an estimator/research interchange format.
 * Long dates are epoch milliseconds; float values round-trip as Float. */
object FieldInventoryTreeJSON {
    private fun value(v:Any?):JsonElement=when(v){
        null->JsonNull;is String->JsonPrimitive(v);is Boolean->JsonPrimitive(v);is Number->JsonPrimitive(v)
        is UUID->JsonPrimitive(v.toString());else->throw FieldStudyException("invalid","Unsupported tree snapshot value")}
    fun encode(t:Tree):JsonObject{
        val fields=linkedMapOf<String,Any?>(
            "id" to t.id,"plotId" to t.plotId,"treeNumber" to t.treeNumber,"treeName" to t.treeName,"speciesCode" to t.speciesCode,"status" to t.status.raw,
            "dbhCm" to t.dbhCm,"dbhMethod" to t.dbhMethod.raw,"dbhSigmaMm" to t.dbhSigmaMm,"dbhRmseMm" to t.dbhRmseMm,"dbhCoverageDeg" to t.dbhCoverageDeg,
            "dbhNInliers" to t.dbhNInliers,"dbhConfidence" to t.dbhConfidence.raw,"dbhIsIrregular" to t.dbhIsIrregular,"dbhCaptureMode" to t.dbhCaptureMode,"dbhEstimatorEpoch" to t.dbhEstimatorEpoch,
            "heightM" to t.heightM,"heightMethod" to t.heightMethod?.raw,"heightSource" to t.heightSource,"heightSigmaM" to t.heightSigmaM,"heightDHM" to t.heightDHM,
            "heightAlphaTopDeg" to t.heightAlphaTopDeg,"heightAlphaBaseDeg" to t.heightAlphaBaseDeg,"heightConfidence" to t.heightConfidence?.raw,
            "bearingFromCenterDeg" to t.bearingFromCenterDeg,"distanceFromCenterM" to t.distanceFromCenterM,"boundaryCall" to t.boundaryCall,
            "crownClass" to t.crownClass,"isMultistem" to t.isMultistem,"parentTreeId" to t.parentTreeId,"notes" to t.notes,"photoPath" to t.photoPath,"rawScanPath" to t.rawScanPath,
            "latitude" to t.latitude,"longitude" to t.longitude,"createdAt" to t.createdAt,"updatedAt" to t.updatedAt,"deletedAt" to t.deletedAt)
        return buildJsonObject{fields.forEach{(k,v)->put(k,value(v))};put("damageCodes",JsonArray(t.damageCodes.map{JsonPrimitive(it)}))
            t.dbhMeasurementId?.let{put("dbhMeasurementId",it)};t.heightMeasurementId?.let{put("heightMeasurementId",it)}}
    }
    fun decode(o:JsonObject):Tree{
        fun p(k:String)=o.getValue(k).jsonPrimitive
        fun s(k:String):String{val v=p(k);fieldRequire(v.isString,"tree string $k");return v.content}
        fun ns(k:String):String?=o[k]?.takeUnless{it==JsonNull}?.let{s(k)}
        fun uuid(k:String):UUID{val v=s(k);fieldRequire(validFieldUuid(v),"tree UUID $k");return UUID.fromString(v)}
        fun f(k:String):Float{val v=p(k);fieldRequire(!v.isString&&v.float.isFinite(),"tree float $k");return v.float}
        fun nf(k:String):Float?=o[k]?.takeUnless{it==JsonNull}?.let{f(k)}
        fun i(k:String):Int{val v=p(k);fieldRequire(!v.isString,"tree integer $k");return v.int}
        fun ni(k:String):Int?=o[k]?.takeUnless{it==JsonNull}?.let{i(k)}
        fun l(k:String):Long{val v=p(k);fieldRequire(!v.isString,"tree time $k");return v.long}
        fun nd(k:String):Double?=o[k]?.takeUnless{it==JsonNull}?.jsonPrimitive?.let{fieldRequire(!it.isString&&it.double.isFinite(),"tree coordinate $k");it.double}
        fun b(k:String):Boolean{val v=p(k);fieldRequire(!v.isString,"tree boolean $k");return v.boolean}
        fun confidence(raw:String)=ConfidenceTier.entries.firstOrNull{it.raw==raw}?:throw FieldStudyException("invalid","Tree confidence")
        return Tree(id=uuid("id"),plotId=uuid("plotId"),treeNumber=i("treeNumber"),treeName=ns("treeName"),speciesCode=s("speciesCode"),
            status=TreeStatus.fromRaw(s("status"))?:throw FieldStudyException("invalid","Tree status"),dbhCm=f("dbhCm"),
            dbhMethod=DBHMethod.entries.firstOrNull{it.raw==s("dbhMethod")}?:throw FieldStudyException("invalid","Tree DBH method"),
            dbhSigmaMm=nf("dbhSigmaMm"),dbhRmseMm=nf("dbhRmseMm"),dbhCoverageDeg=nf("dbhCoverageDeg"),dbhNInliers=ni("dbhNInliers"),
            dbhConfidence=confidence(s("dbhConfidence")),dbhIsIrregular=b("dbhIsIrregular"),dbhCaptureMode=ns("dbhCaptureMode"),dbhEstimatorEpoch=ni("dbhEstimatorEpoch"),
            heightM=nf("heightM"),heightMethod=ns("heightMethod")?.let{raw->HeightMethod.entries.firstOrNull{it.raw==raw}?:throw FieldStudyException("invalid","Tree height method")},
            heightSource=ns("heightSource"),heightSigmaM=nf("heightSigmaM"),heightDHM=nf("heightDHM"),heightAlphaTopDeg=nf("heightAlphaTopDeg"),heightAlphaBaseDeg=nf("heightAlphaBaseDeg"),
            heightConfidence=ns("heightConfidence")?.let(::confidence),bearingFromCenterDeg=nf("bearingFromCenterDeg"),distanceFromCenterM=nf("distanceFromCenterM"),
            boundaryCall=ns("boundaryCall"),crownClass=ns("crownClass"),damageCodes=o.getValue("damageCodes").jsonArray.map{fieldRequire(it.jsonPrimitive.isString,"tree damage code");it.jsonPrimitive.content},
            isMultistem=b("isMultistem"),parentTreeId=ns("parentTreeId")?.let{fieldRequire(validFieldUuid(it),"parent Tree UUID");UUID.fromString(it)},
            notes=s("notes"),photoPath=ns("photoPath"),rawScanPath=ns("rawScanPath"),latitude=nd("latitude"),longitude=nd("longitude"),
            createdAt=l("createdAt"),updatedAt=l("updatedAt"),deletedAt=o["deletedAt"]?.takeUnless{it==JsonNull}?.let{l("deletedAt")},
            dbhMeasurementId=ns("dbhMeasurementId"),heightMeasurementId=ns("heightMeasurementId"))
    }
}
