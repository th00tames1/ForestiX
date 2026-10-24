package com.hcjeong.forestix.data.fieldstudy

import kotlinx.serialization.json.*

/** Explicit codec: identical snake_case schema without an additional compiler/runtime dependency. */
object FieldStudyJson {
    private fun root(text:String)=try{Json.parseToJsonElement(text).jsonObject}
        catch(e:Exception){throw FieldStudyException("corrupt","Unreadable field-study JSON: ${e.message}")}
    private fun JsonObject.required(k:String)=this[k]?:throw FieldStudyException("corrupt","Missing field-study key $k")
    private fun JsonObject.s(k:String):String=required(k).jsonPrimitive.let{
        fieldRequire(it.isString,"string $k");it.content}
    private fun JsonObject.os(k:String):String?=if(this[k]==null||this[k]==JsonNull)null else s(k)
    private fun JsonObject.b(k:String):Boolean=required(k).jsonPrimitive.let{
        fieldRequire(!it.isString,"boolean $k");it.boolean}
    private fun JsonObject.i(k:String):Int=required(k).jsonPrimitive.let{
        fieldRequire(!it.isString,"integer $k");it.int}
    private fun JsonObject.oi(k:String):Int?=if(this[k]==null||this[k]==JsonNull)null else i(k)
    private fun JsonObject.d(k:String):Double=required(k).jsonPrimitive.let{
        fieldRequire(!it.isString,"number $k");it.double.also{v->fieldRequire(v.isFinite(),"finite number $k")}}
    private fun JsonObject.od(k:String):Double?=if(this[k]==null||this[k]==JsonNull)null else d(k)
    private fun JsonObject.o(k:String)=required(k).jsonObject
    private fun JsonObject.a(k:String)=required(k).jsonArray
    private fun JsonObject.sm(k:String)=o(k).mapValues{(_,v)->v.jsonPrimitive.let{
        fieldRequire(it.isString,"string map $k");it.content}}
    private fun JsonObjectBuilder.nullable(k:String,v:JsonElement?){put(k,v?:JsonNull)}
    private fun JsonObjectBuilder.s(k:String,v:String?){nullable(k,v?.let{JsonPrimitive(it)})}
    private fun JsonObjectBuilder.d(k:String,v:Double?){nullable(k,v?.let{JsonPrimitive(it)})}
    private fun JsonObjectBuilder.i(k:String,v:Int?){nullable(k,v?.let{JsonPrimitive(it)})}
    private fun strings(map:Map<String,String>)=buildJsonObject{map.toSortedMap().forEach{(k,v)->put(k,v)}}
    private fun <T> enum(raw:String,values:Array<T>,key:(T)->String):T=values.firstOrNull{key(it)==raw}
        ?:throw FieldStudyException("corrupt","Unknown field-study value $raw")

    fun methodJson(m:FieldReferenceMethod)=buildJsonObject{
        put("id",m.id);put("name",m.name);put("kind",m.kind.raw);put("unit",m.unit.raw);put("role",m.role.raw);put("enabled",m.enabled)}
    private fun method(o:JsonObject)=FieldReferenceMethod(o.s("id"),o.s("name"),
        enum(o.s("kind"),FieldStudyKind.entries.toTypedArray()){it.raw},
        enum(o.s("unit"),FieldReferenceUnit.entries.toTypedArray()){it.raw},
        enum(o.s("role"),FieldReferenceRole.entries.toTypedArray()){it.raw},o.b("enabled"))
    private fun arJson(c:FieldARConfiguration)=buildJsonObject{
        put("enabled",c.enabled);put("maximum_distance_m",c.maximumDistanceM);put("maximum_points",c.maximumPoints)
        put("maximum_horizontal_accuracy_m",c.maximumHorizontalAccuracyM);put("maximum_fix_age_seconds",c.maximumFixAgeSeconds)
        put("maximum_heading_age_seconds",c.maximumHeadingAgeSeconds);put("maximum_heading_error_degrees",c.maximumHeadingErrorDegrees)
        put("distance_hysteresis_m",c.distanceHysteresisM);put("show_distances",c.showDistances)}
    private fun ar(o:JsonObject)=FieldARConfiguration(o.b("enabled"),o.d("maximum_distance_m"),o.i("maximum_points"),
        o.d("maximum_horizontal_accuracy_m"),o.d("maximum_fix_age_seconds"),o.d("maximum_heading_age_seconds"),
        o.d("maximum_heading_error_degrees"),o.d("distance_hysteresis_m"),o.b("show_distances"))
    fun configurationJson(c:FieldStudyConfiguration)=buildJsonObject{
        put("schema",c.schema);put("enabled",c.enabled);put("study_id",c.studyId);put("operator_id",c.operatorId)
        put("protocol_version",c.protocolVersion);put("protocol_locked",c.protocolLocked)
        put("planned_repetitions",c.plannedRepetitions);put("planned_repeat",c.plannedRepeat)
        put("record_timing",c.recordTiming);put("record_failed_attempts",c.recordFailedAttempts)
        put("preserve_ai_inputs",c.preserveAiInputs);put("map_overlay_enabled",c.mapOverlayEnabled)
        put("hide_completed_targets",c.hideCompletedTargets);put("show_references_during_measurement",c.showReferencesDuringMeasurement)
        put("ar",arJson(c.ar));put("reference_methods",JsonArray(c.referenceMethods.map{methodJson(it)}))
        put("pinned_runtime_settings",strings(c.pinnedRuntimeSettings))}
    fun configuration(text:String)=configuration(root(text)).also{it.validate()}
    private fun configuration(o:JsonObject)=FieldStudyConfiguration(
        schema=o.i("schema"),enabled=o.b("enabled"),studyId=o.s("study_id"),operatorId=o.s("operator_id"),
        protocolVersion=o.s("protocol_version"),protocolLocked=o.b("protocol_locked"),
        plannedRepetitions=o.i("planned_repetitions"),plannedRepeat=o.i("planned_repeat"),recordTiming=o.b("record_timing"),
        recordFailedAttempts=o.b("record_failed_attempts"),preserveAiInputs=o.b("preserve_ai_inputs"),
        mapOverlayEnabled=o.b("map_overlay_enabled"),hideCompletedTargets=o.b("hide_completed_targets"),
        showReferencesDuringMeasurement=o.b("show_references_during_measurement"),ar=ar(o.o("ar")),
        referenceMethods=o.a("reference_methods").map{method(it.jsonObject)},pinnedRuntimeSettings=o.sm("pinned_runtime_settings"))

    fun targetJson(t:FieldStudyTarget)=buildJsonObject{
        put("id",t.id);put("study_id",t.studyId);put("tree_id",t.treeId);put("label",t.label)
        d("latitude",t.latitude);d("longitude",t.longitude);d("altitude_m",t.altitudeM);d("horizontal_accuracy_m",t.horizontalAccuracyM)
        put("source_layer_id",t.sourceLayerId);put("source_feature_id",t.sourceFeatureId);put("source_crs",t.sourceCrs)
        put("attributes",strings(t.attributes))}
    private fun target(o:JsonObject)=FieldStudyTarget(o.s("study_id"),o.s("tree_id"),o.s("label"),o.od("latitude"),o.od("longitude"),
        o.od("altitude_m"),o.od("horizontal_accuracy_m"),o.s("source_layer_id"),o.s("source_feature_id"),o.s("source_crs"),o.sm("attributes"),o.s("id"))
    fun targetsJson(targets:List<FieldStudyTarget>)=buildJsonObject{put("schema",1);put("targets",JsonArray(targets.map{targetJson(it)}))}
    fun targets(text:String):List<FieldStudyTarget>{val o=root(text);val schema=o.i("schema")
        if(schema!=1)throw FieldStudyException("schema","Unsupported field-study schema $schema.")
        return o.a("targets").map{target(it.jsonObject).also{t->t.validate()}}}

    fun referenceJson(r:FieldReferenceObservation)=buildJsonObject{
        put("id",r.id);put("target_id",r.targetId);put("method",methodJson(r.method));put("input_value",r.inputValue)
        put("base_value",r.baseValue);i("planned_repeat",r.plannedRepeat);put("reference_repeat",r.referenceRepeat)
        put("operator_id",r.operatorId);put("recorded_at",r.recordedAt);s("supersedes_id",r.supersedesId);put("source",r.source)}
    fun reference(text:String):FieldReferenceObservation{val o=root(text)
        return FieldReferenceObservation(id=o.s("id"),targetId=o.s("target_id"),method=method(o.o("method")),inputValue=o.d("input_value"),
            plannedRepeat=o.oi("planned_repeat"),referenceRepeat=o.i("reference_repeat"),operatorId=o.s("operator_id"),
            recordedAt=o.s("recorded_at"),supersedesId=o.os("supersedes_id"),source=o.s("source"),baseValue=o.d("base_value"))
            .also{it.validate(true)}}

    fun importJson(b:FieldTargetImportBatch)=buildJsonObject{
        put("schema",b.schema);put("id",b.id);put("imported_at",b.importedAt);put("source_name",b.sourceName)
        put("source_layer_id",b.sourceLayerId);put("study_id",b.studyId);put("protocol_fingerprint",b.protocolFingerprint)
        put("mapping",buildJsonObject{val m=b.mapping;put("tree_id_field",m.treeIdField);s("label_field",m.labelField)
            s("altitude_field",m.altitudeField);put("altitude_unit",m.altitudeUnit.raw);s("accuracy_field",m.accuracyField)
            put("reference_fields",strings(m.referenceFields));i("reference_planned_repeat",m.referencePlannedRepeat)})
        put("targets",JsonArray(b.targets.map{targetJson(it)}));put("references",JsonArray(b.references.map{referenceJson(it)}))
        put("skipped_records",b.skippedRecords)}
    fun importBatch(text:String):FieldTargetImportBatch{val o=root(text);val m=o.o("mapping")
        return FieldTargetImportBatch(sourceName=o.s("source_name"),sourceLayerId=o.s("source_layer_id"),studyId=o.s("study_id"),
            protocolFingerprint=o.s("protocol_fingerprint"),mapping=FieldTargetMapping(m.s("tree_id_field"),m.os("label_field"),
                m.os("altitude_field"),enum(m.s("altitude_unit"),FieldReferenceUnit.entries.toTypedArray()){it.raw},
                m.os("accuracy_field"),m.sm("reference_fields"),m.oi("reference_planned_repeat")),
            targets=o.a("targets").map{target(it.jsonObject)},references=o.a("references").map{reference(it.toString())},
            skippedRecords=o.i("skipped_records"),schema=o.i("schema"),id=o.s("id"),importedAt=o.s("imported_at")).also{it.validate(true)}}

    internal fun aiJson(a:FieldAIProvenance)=buildJsonObject{
        put("enabled",a.enabled);put("inference_ran",a.inferenceRan);put("mask_detected",a.maskDetected)
        put("boundary_applied",a.boundaryApplied);put("user_adjusted_boundary",a.userAdjustedBoundary)
        s("inference_id",a.inferenceId);s("reason",a.reason)}
    internal fun ai(o:JsonObject)=FieldAIProvenance(o.b("enabled"),o.b("inference_ran"),o.b("mask_detected"),
        o.b("boundary_applied"),o.b("user_adjusted_boundary"),o.os("inference_id"),o.os("reason"))
    private fun timingJson(t:FieldTimingSnapshot)=buildJsonObject{
        put("screen_elapsed_seconds",t.screenElapsedSeconds);put("active_elapsed_seconds",t.activeElapsedSeconds)
        put("background_seconds",t.backgroundSeconds);d("attempt_elapsed_seconds",t.attemptElapsedSeconds)
        d("attempt_active_seconds",t.attemptActiveSeconds);d("first_valid_result_elapsed_seconds",t.firstValidResultElapsedSeconds)
        d("attempt_first_result_seconds",t.attemptFirstResultSeconds);d("accepted_elapsed_seconds",t.acceptedElapsedSeconds);put("clock_valid",t.clockValid)}
    private fun timing(o:JsonObject)=FieldTimingSnapshot(o.d("screen_elapsed_seconds"),o.d("active_elapsed_seconds"),o.d("background_seconds"),
        o.od("attempt_elapsed_seconds"),o.od("attempt_active_seconds"),o.od("first_valid_result_elapsed_seconds"),
        o.od("attempt_first_result_seconds"),o.od("accepted_elapsed_seconds"),o.b("clock_valid"))
    private fun flowDurationJson(t:FieldUIFlowDuration)=buildJsonObject{
        put("elapsed_seconds",t.elapsedSeconds);put("foreground_seconds",t.foregroundSeconds);put("inactive_seconds",t.inactiveSeconds);put("clock_valid",t.clockValid)}
    private fun flowDuration(o:JsonObject)=FieldUIFlowDuration(o.d("elapsed_seconds"),o.d("foreground_seconds"),o.d("inactive_seconds"),o.b("clock_valid"))
    fun uiFlowTimingJson(t:FieldUIFlowTiming)=buildJsonObject{
        put("schema",t.schema);put("id",t.id);put("kind",t.kind.raw);put("opened_at",t.openedAt);put("current",flowDurationJson(t.current))
        t.atResult?.let{put("at_result",flowDurationJson(it))};t.atAccepted?.let{put("at_accepted",flowDurationJson(it))}}
    fun uiFlowTiming(text:String):FieldUIFlowTiming{val o=root(text)
        return FieldUIFlowTiming(o.s("id"),enum(o.s("kind"),FieldStudyKind.entries.toTypedArray()){it.raw},o.s("opened_at"),flowDuration(o.o("current")),
            o["at_result"]?.takeUnless{it==JsonNull}?.let{flowDuration(it.jsonObject)},
            o["at_accepted"]?.takeUnless{it==JsonNull}?.let{flowDuration(it.jsonObject)},o.i("schema")).also{it.validate()}}
    fun scopeJson(s:FieldMeasurementScope)=buildJsonObject{
        put("id",s.id);put("configuration",configurationJson(s.configuration));nullable("target",s.target?.let{targetJson(it)})
        put("references",JsonArray(s.references.map{referenceJson(it)}));put("created_at",s.createdAt)}
    fun scope(text:String):FieldMeasurementScope {val o=root(text)
        return FieldMeasurementScope(o.s("id"),configuration(o.o("configuration")),
            o["target"]?.takeUnless{it==JsonNull}?.let{target(it.jsonObject)},o.a("references").map{reference(it.toString())},o.s("created_at"))}
    fun protocolBindingJson(b:FieldProtocolBinding)=buildJsonObject{
        put("schema",b.schema);put("id",b.id);put("protocol_fingerprint",b.protocolFingerprint);put("kind",b.kind.raw)
        put("platform",b.platform);put("input_mode",b.inputMode);put("settings",strings(b.settings))}
    fun protocolBinding(text:String):FieldProtocolBinding{val o=root(text)
        return FieldProtocolBinding(o.i("schema"),o.s("id"),o.s("protocol_fingerprint"),
            enum(o.s("kind"),FieldStudyKind.entries.toTypedArray()){it.raw},o.s("platform"),o.s("input_mode"),frozenFieldMap(o.sm("settings"))).also{it.validate()}}
    fun measurementJson(r:FieldMeasurementRecord)=buildJsonObject{
        put("schema",r.schema);put("id",r.id);put("session_id",r.sessionId);put("study_id",r.studyId);put("tree_id",r.treeId)
        s("target_id",r.targetId);nullable("target_snapshot",r.targetSnapshot?.let{targetJson(it)})
        put("operator_id",r.operatorId);put("kind",r.kind.raw);put("planned_repeat",r.plannedRepeat);put("attempt_number",r.attemptNumber)
        put("started_at",r.startedAt);put("outcome",r.outcome.raw);put("selection",r.selection.raw);s("failure_reason",r.failureReason)
        d("live_value",r.liveValue);d("live_sigma",r.liveSigma);d("replay_value",r.replayValue);put("raw_status",r.rawStatus.raw)
        s("result_record_id",r.resultRecordId);s("result_store",r.resultStore)
        s("raw_bundle_id",r.rawBundleId);s("raw_failure_reason",r.rawFailureReason);put("ai",aiJson(r.ai));nullable("timing",r.timing?.let{timingJson(it)})
        r.uiFlowTiming?.let{put("ui_flow_timing",uiFlowTimingJson(it))}
        put("configuration",configurationJson(r.configuration));put("runtime_settings",strings(r.runtimeSettings))
        r.protocolBinding?.let{put("protocol_binding",protocolBindingJson(it))}
        put("reference_ids",JsonArray(r.referenceIds.map{JsonPrimitive(it)}));put("revision",r.revision)
        nullable("measurement_scope",r.measurementScope?.let{scopeJson(it)})
        put("events",JsonArray(r.events.map{e->buildJsonObject{put("timestamp",e.timestamp);put("action",e.action);s("detail",e.detail)}}))}
    fun measurement(text:String):FieldMeasurementRecord{val o=root(text)
        return FieldMeasurementRecord(id=o.s("id"),sessionId=o.s("session_id"),studyId=o.s("study_id"),treeId=o.s("tree_id"),
            targetId=o.os("target_id"),targetSnapshot=o["target_snapshot"]?.takeUnless{it==JsonNull}?.let{target(it.jsonObject)},
            operatorId=o.s("operator_id"),kind=enum(o.s("kind"),FieldStudyKind.entries.toTypedArray()){it.raw},
            plannedRepeat=o.i("planned_repeat"),attemptNumber=o.i("attempt_number"),startedAt=o.s("started_at"),schema=o.i("schema"),
            outcome=enum(o.s("outcome"),FieldAttemptOutcome.entries.toTypedArray()){it.raw},
            selection=enum(o.s("selection"),FieldSelectionStatus.entries.toTypedArray()){it.raw},failureReason=o.os("failure_reason"),
            liveValue=o.od("live_value"),liveSigma=o.od("live_sigma"),replayValue=o.od("replay_value"),
            resultRecordId=o.os("result_record_id"),resultStore=o.os("result_store"),
            rawStatus=enum(o.s("raw_status"),FieldRawStatus.entries.toTypedArray()){it.raw},rawBundleId=o.os("raw_bundle_id"),
            rawFailureReason=o.os("raw_failure_reason"),ai=ai(o.o("ai")),
            timing=o["timing"]?.takeUnless{it==JsonNull}?.let{timing(it.jsonObject)},
            uiFlowTiming=o["ui_flow_timing"]?.takeUnless{it==JsonNull}?.let{uiFlowTiming(it.toString())},configuration=configuration(o.o("configuration")),
            runtimeSettings=o.sm("runtime_settings"),referenceIds=o.a("reference_ids").map{it.jsonPrimitive.content},revision=o.i("revision"),
            measurementScope=o["measurement_scope"]?.takeUnless{it==JsonNull}?.let{scope(it.toString())},
            protocolBinding=o["protocol_binding"]?.takeUnless{it==JsonNull}?.let{protocolBinding(it.toString())},
            events=o.a("events").map{e->e.jsonObject.let{FieldMeasurementEvent(it.s("action"),it.os("detail"),it.s("timestamp"))}})
            .also{it.validate()}}
}
