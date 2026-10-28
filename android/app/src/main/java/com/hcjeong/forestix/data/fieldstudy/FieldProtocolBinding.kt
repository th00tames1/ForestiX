package com.hcjeong.forestix.data.fieldstudy

/** Immutable first-use baseline, not a target/GPS or estimator modification. */
data class FieldProtocolBinding(val schema:Int=1,val id:String,val protocolFingerprint:String,
    val kind:FieldStudyKind,val platform:String,val inputMode:String,val settings:Map<String,String>){
    val procedureFingerprint:String get(){
        val values=mutableMapOf("procedure_schema" to "1","kind" to kind.raw,"input_mode" to inputMode)
        val keys=if(inputMode=="typed")emptyList()else if(kind==FieldStudyKind.DBH)listOf("dbh_estimator_epoch","dbh_method","dbh_chord_algorithm",
            "capture_frame_count","depth_sampling_fraction","depth_sampling_statistic","depth_surface_correction","breast_height_guide_height_m")else listOf("height_method","height_procedure_version")
        keys.forEach{key->settings[key]?.let{values[key]=it}}
        if(values["dbh_method"]=="partialArcCircleFit")values["dbh_method"]="arc"
        return FieldFingerprint.settings(values)
    }
    fun validate(){
        fieldRequire(schema==1&&protocolFingerprint.length==64&&id==identity(protocolFingerprint,kind,platform,inputMode)&&
            settings["platform"]==platform&&settings["input_mode"]==inputMode&&project(kind,settings)==settings,"protocol binding")
    }
    fun matches(configuration:FieldStudyConfiguration,kind:FieldStudyKind,runtime:Map<String,String>):Boolean=
        runCatching{validate();configuration.pinnedRuntimeSettings==policy&&protocolFingerprint==configuration.protocolFingerprint&&
            this.kind==kind&&project(kind,runtime)==settings}.getOrDefault(false)
    companion object{
        val policy:Map<String,String> = frozenFieldMap(mapOf("protocol_lock_policy" to "first_capture_v1"))
        private fun identity(fingerprint:String,kind:FieldStudyKind,platform:String,inputMode:String)=
            FieldFingerprint.digest(listOf("field-protocol-binding-v1",fingerprint,kind.raw,platform,inputMode))
        fun create(configuration:FieldStudyConfiguration,kind:FieldStudyKind,runtime:Map<String,String>):FieldProtocolBinding{
            val platform=runtime["platform"].orEmpty();val mode=runtime["input_mode"].orEmpty()
            return FieldProtocolBinding(id=identity(configuration.protocolFingerprint,kind,platform,mode),
                protocolFingerprint=configuration.protocolFingerprint,kind=kind,platform=platform,inputMode=mode,
                settings=frozenFieldMap(project(kind,runtime))).also{it.validate()}
        }
        fun project(kind:FieldStudyKind,runtime:Map<String,String>):Map<String,String>{
            fieldRequire(runtime["platform"] in listOf("ios","android")&&runtime["input_mode"] in listOf("typed","sensor"),"protocol runtime identity")
            val keys=mutableListOf("platform","input_mode","app_version","app_build")
            if(runtime["input_mode"]=="sensor"){
                keys+=listOf("measurement_source","calibration.depth_noise_mm","calibration.vio_drift_fraction")
                keys+=if(kind==FieldStudyKind.DBH)listOf("dbh_estimator_epoch","dbh_method","dbh_chord_algorithm","capture_frame_count",
                    "depth_sampling_fraction","depth_sampling_statistic","depth_surface_correction","model_sha256",
                    "calibration.alpha","calibration.beta","calibration.epoch","calibration.depth_discontinuity_m")
                else listOf("height_method","height_procedure_version")
            }
            val values=keys.associateWith{key->runtime[key]?.takeIf{it.isNotEmpty()&&it !in listOf("unknown","unavailable")}
                ?:throw FieldStudyException("missing","Missing protocol setting: $key")}.toMutableMap()
            values["model_sha256"]?.let{fieldRequire(it.matches(Regex("[0-9a-f]{64}")),"protocol model fingerprint")}
            // Old baselines remain readable. New captures must not silently
            // reuse an old lock that omitted the physical guide height.
            if(kind==FieldStudyKind.DBH&&runtime["input_mode"]=="sensor")runtime["breast_height_guide_height_m"]?.let{height->
                val meters=height.toULongOrNull(16)?.let{Double.fromBits(it.toLong())}
                fieldRequire(height.matches(Regex("[0-9a-f]{16}"))&&meters!=null&&meters.isFinite()&&meters>0&&
                    FieldFingerprint.number(meters)==height,"protocol guide height")
                values["breast_height_guide_height_m"]=height
            }
            return values
        }
        fun preparing(next:FieldStudyConfiguration,previous:FieldStudyConfiguration)=
            if(next.protocolLocked&&(!previous.protocolLocked||next.studyId!=previous.studyId||next.protocolVersion!=previous.protocolVersion))
                next.copy(pinnedRuntimeSettings=policy)else next
    }
}
