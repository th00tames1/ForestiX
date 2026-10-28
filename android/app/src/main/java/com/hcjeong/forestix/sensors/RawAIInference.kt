package com.hcjeong.forestix.sensors

import com.hcjeong.forestix.data.fieldstudy.FieldAIProvenance
import kotlinx.serialization.json.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

interface RawAIRecordSource { val inferenceId:String;fun write(directory:File):JsonObject }
data class RawAIWriteResult(val inference:JsonObject?,val status:String,val reason:String?)
class RawAIWriteRequest private constructor(val status:String,val reason:String?,source:RawAIRecordSource?,permit:Boolean){
    private val source=AtomicReference(source);private val permit=AtomicBoolean(permit);private val consumed=AtomicBoolean(false)
    companion object {
        private val pending=AtomicInteger(0)
        fun make(requested:Boolean,ai:FieldAIProvenance,source:RawAIRecordSource?):RawAIWriteRequest {
            if(!requested)return RawAIWriteRequest("not_requested",null,null,false)
            if(!ai.inferenceRan)return RawAIWriteRequest("no_inference",null,null,false)
            if(source==null)return RawAIWriteRequest("missing","input_not_retained_at_inference",null,false)
            if(source.inferenceId!=ai.inferenceId)return RawAIWriteRequest("missing","inference_identity_mismatch",null,false)
            while(true){val n=pending.get();if(n>=2)return RawAIWriteRequest("busy","two_pending_AI_recordings",null,false)
                if(pending.compareAndSet(n,n+1))return RawAIWriteRequest("pending",null,source,true)}
        }
    }
    private fun release(){if(permit.compareAndSet(true,false))pending.decrementAndGet()}
    fun cancel(){if(source.getAndSet(null)!=null)release()}
    fun write(directory:File):RawAIWriteResult {
        if(!consumed.compareAndSet(false,true))return RawAIWriteResult(null,"failed","AI_request_already_consumed")
        val current=source.getAndSet(null)?:return RawAIWriteResult(null,if(status=="pending")"cancelled"else status,reason)
        try{
            val json=current.write(directory)
            require(json.getValue("inference_id").jsonPrimitive.content==current.inferenceId){"Written AI identity"}
            RawAIInferenceIO.verifiedTensor(json,directory);RawAIInferenceIO.restoredMask(json,directory)
            RawCaptureEvidenceIO.readFrame(RawCaptureEvidence.frame(json.getValue("source_depth").jsonObject),directory)
            return RawAIWriteResult(json,"saved",if(json["model_sha256"]==null)"model_hash_unavailable"else null)
        }catch(e:Exception){return RawAIWriteResult(null,"failed",e.message?:e.javaClass.simpleName)}finally{release()}
    }
}
object RawAIInferenceIO {
    fun verifiedTensor(json:JsonObject,directory:File):ByteArray {
        require(json.getValue("version").jsonPrimitive.int==1&&json.getValue("tensor_shape").jsonArray.map{it.jsonPrimitive.int}==listOf(1,3,640,640)&&
            json.getValue("tensor_format").jsonPrimitive.content=="float32_le_nchw_rgb_0_1"){"AI tensor shape/format"}
        val file=RawCaptureEvidence.proof(json.getValue("tensor").jsonObject);require(file.bytes==3*640*640*4){"AI tensor length"}
        return file.read(directory)
    }
    fun restoredMask(json:JsonObject,directory:File):YoloAlignmentMask.Mask? {
        val file=json["mask_logits"]?.takeUnless{it==JsonNull};val geometry=json["mask_geometry"]?.takeUnless{it==JsonNull}
        if(file==null||geometry==null){require(file==null&&geometry==null){"AI mask pairing"};return null}
        val bytes=RawCaptureEvidence.proof(file.jsonObject).read(directory);require(bytes.size==25600*4){"AI logit shape"}
        val data=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return YoloAlignmentMask.restore(geometry.jsonObject,FloatArray(25600){Float.fromBits(data.int)})
    }
    internal fun floatBytes(values:FloatArray):ByteArray {
        val data=ByteBuffer.allocate(values.size*4).order(ByteOrder.LITTLE_ENDIAN)
        for(value in values)data.putInt(value.toRawBits())
        return data.array()
    }
}

/** Owns the fresh camera.convert() CHW array. It is never mutated or exposed
 * after inference; no ARCore Image or inference workspace is retained. */
internal class YoloOwnedInference(override val inferenceId:String,private val chw:FloatArray,private val box:Letterbox,
    private val turns:Int,private val expectedInputHash:String,private val frame:ArDepthFrame,private val sourceImage:RawCapturedImage?,
    private val mask:YoloAlignmentMask.Mask?,private val observation:LiveStemObservation?,private val vw:Float,private val vh:Float,
    private val started:Double,private val completed:Double,private val modelSHA:String?,private val runtimeVersion:String?,private val cpuThreads:Int):RawAIRecordSource {
    override fun write(directory:File):JsonObject {
        val bytes=YoloTensorRotation.bytes(chw,turns)
        require(RawCaptureEvidenceIO.hash(bytes)==expectedInputHash){"AI input regeneration hash mismatch"}
        val tensor=RawCaptureEvidenceIO.write(bytes,"ai_tensor.f32",directory)
        val logits=mask?.let{RawCaptureEvidenceIO.write(it.captureLogitsBytes(),"ai_mask_logits.f32",directory)}
        val depth=RawCaptureEvidenceIO.writeFrame(frame,"ai_source",directory)
        fun numbers(values:List<Double>)=JsonArray(values.map{JsonPrimitive(it)})
        return buildJsonObject{
            put("version",1);put("inference_id",inferenceId);put("clock","elapsed_realtime_seconds")
            put("started_seconds",started);put("completed_seconds",completed);put("model_file","yolo26_alignment.onnx")
            modelSHA?.let{put("model_sha256",it)};put("runtime","onnxruntime_cpu");runtimeVersion?.let{put("runtime_version",it)};put("cpu_threads",cpuThreads)
            put("preprocessing",buildJsonObject{
                put("version","android_yuv420_bt601_nearest_chw_rotate_v1");put("raw_width",box.sourceWidth.toString());put("raw_height",box.sourceHeight.toString())
                put("quarter_turns",turns.toString());put("letterbox_scale",box.scale.toString());put("pad_x",box.padX.toString());put("pad_y",box.padY.toString())
                put("mask_projection","view_to_image_pixels_floor_rotate_v1");put("authoritative_input","prepared_rgb_tensor")
            })
            put("tensor",RawCaptureEvidence.proofJson(tensor));put("tensor_shape",JsonArray(listOf(1,3,640,640).map{JsonPrimitive(it)}))
            put("tensor_format","float32_le_nchw_rgb_0_1");sourceImage?.let{put("source_image",RawCaptureEvidence.imageJson(it))}
            put("source_depth",RawCaptureEvidence.frameJson(depth));logits?.let{put("mask_logits",RawCaptureEvidence.proofJson(it))}
            mask?.let{put("mask_geometry",it.captureGeometry())}
            observation?.mask?.let{m->put("view_mask_width",m.width);put("view_mask_height",m.height)
                put("view_mask_runs",JsonArray(m.runs.map{r->JsonArray(listOf(r.row,r.start,r.end).map{JsonPrimitive(it)})}))}
            put("viewport_size",numbers(listOf(vw.toDouble(),vh.toDouble())))
            observation?.guideExtent?.let{put("guide_fractions",numbers(listOf(it.leftFraction,it.rightFraction)))}
            put("decoder","yolo26_oneclass_center_v1");put("confidence_threshold",.25);put("nms_iou_threshold",.45);put("mask_logit_threshold",0.0)
        }
    }
}
data class YoloTrackedCapture(val observation:LiveStemObservation?,val source:RawAIRecordSource?=null)
