package com.hcjeong.forestix.sensors

import com.hcjeong.forestix.data.fieldstudy.*
import kotlinx.serialization.json.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

data class RawCapturedResult(val value:Double?,val sigma:Double?,val tier:String?,val method:String?,val valid:Boolean)
data class RawCapturedFile(val file:String,val bytes:Int,val sha256:String){
    fun checkedFile(directory:File):File {
        require(file.isNotEmpty()&&file==File(file).name&&!file.contains("..")&&!file.contains('\\')&&
            bytes in 0..32*1024*1024&&sha256.matches(Regex("[0-9a-f]{64}"))){"Invalid evidence file"}
        val f=File(directory,file)
        require(f.canonicalFile.parentFile==directory.canonicalFile&&!java.nio.file.Files.isSymbolicLink(f.toPath())){"Evidence file escaped bundle"}
        return f
    }
    fun read(directory:File):ByteArray {
        val f=checkedFile(directory);require(f.isFile&&f.length()==bytes.toLong()){"Evidence file length"}
        return f.readBytes().also{require(RawCaptureEvidenceIO.hash(it)==sha256){"Evidence checksum"}}
    }
}
data class RawCapturedAuxDepth(val width:Int,val height:Int,val depth:RawCapturedFile,val confidence:RawCapturedFile,
    val depthSensorNanos:String,val confidenceSensorNanos:String,val cameraSensorNanos:String)
data class RawCapturedFrame(val role:String,val width:Int,val height:Int,val depth:RawCapturedFile,val confidence:RawCapturedFile,
    val cameraFrameSeconds:Double?,val depthSensorSeconds:Double?,val depthTimeSource:String,val cameraPose:List<Double>,
    val intrinsics:List<Double>,val viewToDepth:List<Double>?,val viewportRevision:String?=null,
    val sourceFrameId:String?=null,val depthSensorId:String?=null,val depthAgeNanoseconds:String?=null,val cameraSensorId:String?=null,
    val auxiliaryRawDepth:RawCapturedAuxDepth?=null,val depthDiagnostics:Map<String,String> = emptyMap(),
    val depthInputPolicy:String=DbhMeasurementDepth.LEGACY_FULL)
data class RawCapturedImage(val role:String,val file:RawCapturedFile?,val width:Int,val height:Int,
    val cameraFrameSeconds:Double?,val imageSensorSeconds:Double?,val timeSource:String,val intrinsics:List<Double>,
    val cameraPose:List<Double>,val viewToImage:List<Double>?,val coordinateSpace:String="camera_pixels_native",
    val sourceFrameId:String?=null,val imageSensorId:String?=null)
data class RawCameraJPEG(val data:ByteArray,val metadata:RawCapturedImage)

/** Separate actual reading from legacy canonical replay. Nil is unrecorded, never zero. */
data class RawCaptureEvidence(val measurementId:String,val clock:String="elapsed_realtime_seconds",val resultSeconds:Double,
    val displayedResult:RawCapturedResult,val ai:FieldAIProvenance=FieldAIProvenance(),val frames:List<RawCapturedFrame> = emptyList(),
    val scalars:Map<String,Double> = emptyMap(),val replayValue:Double?=null,val replayDelta:Double?=null,
    val replayStatus:String="not_run",val version:Int=1,val images:List<RawCapturedImage>?=null,
    val aiInference:JsonObject?=null,val aiEvidenceStatus:String?=null,val aiEvidenceReason:String?=null,val attemptFailureReason:String?=null,
    val depthDiagnostics:JsonObject?=null){
    fun noteReplay(value:Double?):RawCaptureEvidence {
        val replay=value?.takeIf{it.isFinite()&&it>0};val delta=displayedResult.value?.let{v->replay?.minus(v)}
        return copy(replayValue=replay,replayDelta=delta,replayStatus=when{delta==null->"no_result";delta==0.0->"pass";else->"mismatch"})
    }
    fun json():JsonObject = buildJsonObject {
        put("version",version);put("measurement_id",measurementId);put("clock",clock);put("result_seconds",resultSeconds)
        put("displayed_result",buildJsonObject{number("value",displayedResult.value);number("sigma",displayedResult.sigma)
            text("tier",displayedResult.tier);text("method",displayedResult.method);put("valid",displayedResult.valid)})
        put("ai",FieldStudyJson.aiJson(ai));put("scalars",buildJsonObject{scalars.forEach{(k,v)->put(k,v)}})
        number("replay_value",replayValue);number("replay_delta",replayDelta);put("replay_status",replayStatus)
        aiInference?.let{put("ai_inference",it)};text("ai_evidence_status",aiEvidenceStatus);text("ai_evidence_reason",aiEvidenceReason)
        text("attempt_failure_reason",attemptFailureReason)
        depthDiagnostics?.let{put("depth_diagnostics",it)}
        images?.let{values->put("images",JsonArray(values.map{imageJson(it)}))}
        put("frames",JsonArray(frames.map{frameJson(it)}))
    }
    companion object {
        internal fun imageJson(m:RawCapturedImage)=buildJsonObject{
            put("role",m.role);m.file?.let{put("file",proofJson(it))};put("width",m.width);put("height",m.height)
            number("camera_frame_seconds",m.cameraFrameSeconds);number("image_sensor_seconds",m.imageSensorSeconds)
            put("time_source",m.timeSource);put("intrinsics",numbers(m.intrinsics));put("camera_pose",numbers(m.cameraPose))
            m.viewToImage?.let{put("view_to_image",numbers(it))};put("coordinate_space",m.coordinateSpace)
            text("source_frame_id",m.sourceFrameId);text("image_sensor_id",m.imageSensorId)
        }
        internal fun frameJson(f:RawCapturedFrame)=buildJsonObject{
            put("role",f.role);put("width",f.width);put("height",f.height);put("depth",proofJson(f.depth));put("confidence",proofJson(f.confidence))
            number("camera_frame_seconds",f.cameraFrameSeconds);number("depth_sensor_seconds",f.depthSensorSeconds)
            put("depth_time_source",f.depthTimeSource);put("camera_pose",numbers(f.cameraPose));put("intrinsics",numbers(f.intrinsics))
            f.viewToDepth?.let{put("view_to_depth",numbers(it))};text("viewport_revision",f.viewportRevision)
            text("source_frame_id",f.sourceFrameId);text("depth_sensor_id",f.depthSensorId)
            text("depth_age_nanoseconds",f.depthAgeNanoseconds);text("camera_sensor_id",f.cameraSensorId)
            f.auxiliaryRawDepth?.let{r->put("auxiliary_raw_depth",buildJsonObject{
                put("format","u16mm");put("confidence_format","u8_score_0_255");put("coordinate_space","depth_pixels_native")
                put("width",r.width);put("height",r.height);put("depth",proofJson(r.depth));put("confidence",proofJson(r.confidence))
                put("depth_sensor_ns",r.depthSensorNanos);put("confidence_sensor_ns",r.confidenceSensorNanos);put("camera_sensor_ns",r.cameraSensorNanos)
            })}
            if(f.depthDiagnostics.isNotEmpty())put("depth_diagnostics",buildJsonObject{f.depthDiagnostics.forEach{(k,v)->put(k,v)}})
            put("depth_input_policy",f.depthInputPolicy)
        }
        internal fun proofJson(f:RawCapturedFile)=buildJsonObject{put("file",f.file);put("bytes",f.bytes);put("sha256",f.sha256)}
        internal fun proof(o:JsonObject)=RawCapturedFile(o.getValue("file").jsonPrimitive.content,o.getValue("bytes").jsonPrimitive.int,o.getValue("sha256").jsonPrimitive.content)
        private fun numbers(v:List<Double>)=JsonArray(v.map{JsonPrimitive(it)})
        private fun JsonObjectBuilder.text(k:String,v:String?){if(v!=null)put(k,v)}
        private fun JsonObjectBuilder.number(k:String,v:Double?){if(v!=null&&v.isFinite())put(k,v)}
        private fun JsonObject.s(k:String)=getValue(k).jsonPrimitive.content
        private fun JsonObject.d(k:String)=get(k)?.takeUnless{it==JsonNull}?.jsonPrimitive?.double?.also{require(it.isFinite())}
        private fun JsonObject.os(k:String)=get(k)?.takeUnless{it==JsonNull}?.jsonPrimitive?.content
        private fun JsonObject.array(k:String)=getValue(k).jsonArray.map{it.jsonPrimitive.double.also{n->require(n.isFinite())}}
        internal fun frame(o:JsonObject)=RawCapturedFrame(o.s("role"),o.getValue("width").jsonPrimitive.int,o.getValue("height").jsonPrimitive.int,
            proof(o.getValue("depth").jsonObject),proof(o.getValue("confidence").jsonObject),o.d("camera_frame_seconds"),o.d("depth_sensor_seconds"),
            o.s("depth_time_source"),o.array("camera_pose"),o.array("intrinsics"),o["view_to_depth"]?.takeUnless{it==JsonNull}?.jsonArray?.map{it.jsonPrimitive.double},
            o.os("viewport_revision"),o.os("source_frame_id"),o.os("depth_sensor_id"),o.os("depth_age_nanoseconds"),o.os("camera_sensor_id"),
            o["auxiliary_raw_depth"]?.takeUnless{it==JsonNull}?.jsonObject?.let{r->
                require(r.s("format")=="u16mm"&&r.s("confidence_format")=="u8_score_0_255"&&r.s("coordinate_space")=="depth_pixels_native")
                RawCapturedAuxDepth(r.getValue("width").jsonPrimitive.int,r.getValue("height").jsonPrimitive.int,
                    proof(r.getValue("depth").jsonObject),proof(r.getValue("confidence").jsonObject),r.s("depth_sensor_ns"),r.s("confidence_sensor_ns"),r.s("camera_sensor_ns"))
            },o["depth_diagnostics"]?.takeUnless{it==JsonNull}?.jsonObject?.mapValues{it.value.jsonPrimitive.content}?:emptyMap(),
            o.os("depth_input_policy")?:DbhMeasurementDepth.LEGACY_FULL)
        internal fun image(o:JsonObject)=RawCapturedImage(o.s("role"),o["file"]?.takeUnless{it==JsonNull}?.let{proof(it.jsonObject)},
            o.getValue("width").jsonPrimitive.int,o.getValue("height").jsonPrimitive.int,o.d("camera_frame_seconds"),o.d("image_sensor_seconds"),
            o.s("time_source"),o.array("intrinsics"),o.array("camera_pose"),o["view_to_image"]?.takeUnless{it==JsonNull}?.jsonArray?.map{it.jsonPrimitive.double},
            o.s("coordinate_space"),o.os("source_frame_id"),o.os("image_sensor_id"))
        fun parse(text:String):RawCaptureEvidence {
            val o=Json.parseToJsonElement(text).jsonObject
            val r=o.getValue("displayed_result").jsonObject
            return RawCaptureEvidence(o.s("measurement_id"),o.s("clock"),o.d("result_seconds")!!,
                RawCapturedResult(r.d("value"),r.d("sigma"),r.os("tier"),r.os("method"),r.getValue("valid").jsonPrimitive.boolean),
                FieldStudyJson.ai(o.getValue("ai").jsonObject),o.getValue("frames").jsonArray.map{frame(it.jsonObject)},
                o.getValue("scalars").jsonObject.mapValues{it.value.jsonPrimitive.double.also{n->require(n.isFinite())}},
                o.d("replay_value"),o.d("replay_delta"),o.s("replay_status"),o.getValue("version").jsonPrimitive.int,
                images=o["images"]?.takeUnless{it==JsonNull}?.jsonArray?.map{image(it.jsonObject)},
                aiInference=o["ai_inference"]?.takeUnless{it==JsonNull}?.jsonObject,aiEvidenceStatus=o.os("ai_evidence_status"),
                aiEvidenceReason=o.os("ai_evidence_reason"),attemptFailureReason=o.os("attempt_failure_reason"),
                depthDiagnostics=o["depth_diagnostics"]?.takeUnless{it==JsonNull}?.jsonObject)
                .also{require(it.version in 1..2&&it.frames.size<=32&&it.frames.map{f->f.role}.toSet().size==it.frames.size)
                    require(it.frames.all{f->DbhMeasurementDepth.recognized(f.depthInputPolicy)})
                    require(it.version>=2||it.frames.all{f->f.depthInputPolicy==DbhMeasurementDepth.LEGACY_FULL})
                    it.ai.validate()}
        }
    }
}

object RawCaptureEvidenceIO {
    fun attachImage(metadata:RawCapturedImage?,bytes:ByteArray?,file:String?,role:String,directory:File):RawCapturedImage? {
        metadata?:return null
        val proof=bytes?.let{
            if(file==null)write(it,"image_$role.jpg",directory)
            else RawCapturedFile(file,it.size,hash(it)).also{p->p.read(directory)}
        }
        return metadata.copy(role=role,file=proof)
    }
    internal fun hash(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt()and 255)}
    internal fun write(bytes:ByteArray,name:String,directory:File):RawCapturedFile {
        val p=RawCapturedFile(name,bytes.size,hash(bytes));val f=p.checkedFile(directory)
        java.io.FileOutputStream(f).use{it.write(bytes);it.fd.sync()};return p
    }
    fun writeFrame(f:ArDepthFrame,role:String,directory:File):RawCapturedFrame {
        require(f.width in 1..4096&&f.height in 1..4096&&f.width.toLong()*f.height<=4*1024*1024&&
            f.depth.size==f.width*f.height&&f.confidence.size==f.depth.size&&f.confidence.all{it.toInt() in 0..2}){"Original frame dimensions"}
        val bytes=ByteBuffer.allocate(f.depth.size*4).order(ByteOrder.LITTLE_ENDIAN)
        f.depth.forEach{bytes.putInt(it.toRawBits())}
        val clock=RawFrameClockRegistry.stamp(f)
        return RawCapturedFrame(role,f.width,f.height,write(bytes.array(),"input_$role.f32",directory),
            write(f.confidence,"input_$role.conf",directory),f.frameTimestampNanos.takeIf{it>0}?.div(1e9),
            clock?.depthSensorNanos?.takeIf{it>0}?.div(1e9),
            if(clock==null)"camera_relative_depth_age_only"else"arcore_depth_image_timestamp",f.pose.map{it.toDouble()},
            listOf(f.fx,0.0,0.0,0.0,f.fy,0.0,f.cx,f.cy,1.0),f.depthFromViewAffine?.map{it.toDouble()},
            sourceFrameId=f.frameTimestampNanos.takeIf{it>0}?.let{"arcore_frame_ns:$it"},
            depthSensorId=clock?.depthSensorNanos?.let{"depth_image_ns:$it"},depthAgeNanoseconds=f.depthAgeNanos.toString(),
            cameraSensorId=clock?.androidCameraNanos?.let{"android_camera_ns:$it"},
            auxiliaryRawDepth=f.rawDepthEvidence?.let{r->
                val raw=ByteBuffer.allocate(r.millimeters.size*2).order(ByteOrder.LITTLE_ENDIAN)
                r.millimeters.forEach{raw.putShort(it)}
                RawCapturedAuxDepth(r.width,r.height,write(raw.array(),"aux_raw_$role.u16",directory),
                    write(r.confidence,"aux_raw_$role.conf.u8",directory),r.timestampNanos.toString(),
                    r.confidenceTimestampNanos.toString(),r.cameraTimestampNanos.toString())
            },depthDiagnostics=f.depthDiagnostics,depthInputPolicy=f.depthInputPolicy)
    }
    fun readFrame(m:RawCapturedFrame,directory:File):ArDepthFrame {
        require(DbhMeasurementDepth.recognized(m.depthInputPolicy)){"Unsupported DBH depth input policy"}
        require(m.width in 1..4096&&m.height in 1..4096&&m.width.toLong()*m.height<=4*1024*1024&&
            m.cameraPose.size==16&&m.intrinsics.size==9&&(m.cameraPose+m.intrinsics).all{it.isFinite()}&&
            (m.viewToDepth?.let{it.size==6&&it.all{n->n.isFinite()}}?:true)){"Original frame geometry"}
        val count=m.width*m.height;val bytes=m.depth.read(directory);val confidence=m.confidence.read(directory)
        require(bytes.size==count*4&&confidence.size==count&&confidence.all{it.toInt() in 0..2}){"Original frame bytes"}
        val data=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);val depth=FloatArray(count){Float.fromBits(data.int)}
        val timestamp=m.sourceFrameId?.removePrefix("arcore_frame_ns:")?.toLongOrNull()?:m.cameraFrameSeconds?.times(1e9)?.toLong()?:0L
        val depthTime=m.depthSensorId?.removePrefix("depth_image_ns:")?.toLongOrNull()?:m.depthSensorSeconds?.times(1e9)?.toLong()
        val cameraTime=m.cameraSensorId?.removePrefix("android_camera_ns:")?.toLongOrNull()
        val recordedAge=m.depthAgeNanoseconds?.toLong()?:0L
        // A known sensor-clock violation must not turn into age=0 during
        // replay; missing historical clocks remain explicitly unknown.
        val age=if(cameraTime!=null&&depthTime!=null&&!DbhDepthInputPolicy.acceptsSensorTimes(cameraTime,depthTime)&&
            recordedAge in 0..DbhDepthInputPolicy.MAX_DEPTH_AGE_NANOS)
            Long.MAX_VALUE else recordedAge
        val auxiliary=m.auxiliaryRawDepth?.let{r->
            require(r.width in 1..4096&&r.height in 1..4096&&r.width.toLong()*r.height<=4*1024*1024){"Auxiliary raw dimensions"}
            val raw=r.depth.read(directory);val scores=r.confidence.read(directory);val n=r.width*r.height
            require(raw.size==n*2&&scores.size==n){"Auxiliary raw bytes"}
            val b=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            val cameraNanos=r.cameraSensorNanos.toLong()
            require(cameraTime==null||cameraTime==cameraNanos){"Raw/current frame camera identity mismatch"}
            DbhRawDepthEvidence(r.width,r.height,ShortArray(n){b.short},scores,r.depthSensorNanos.toLong(),
                r.confidenceSensorNanos.toLong(),cameraNanos)
        }
        val output=ArDepthFrame(m.width,m.height,depth,confidence,m.intrinsics[0],m.intrinsics[4],m.intrinsics[6],m.intrinsics[7],
            m.cameraPose.map{it.toFloat()}.toFloatArray(),depthFromViewAffine=m.viewToDepth?.map{it.toFloat()}?.toFloatArray(),
            frameTimestampNanos=timestamp,depthAgeNanos=age,rawDepthEvidence=auxiliary,depthDiagnostics=m.depthDiagnostics,
            depthInputPolicy=m.depthInputPolicy)
        if(cameraTime!=null&&depthTime!=null)RawFrameClockRegistry.register(output,cameraTime,depthTime)
        return output
    }
}
