package com.hcjeong.forestix.fieldstudy

import android.content.Context
import android.content.ContextWrapper
import com.hcjeong.forestix.ar.Vec3
import com.hcjeong.forestix.common.TruthInput
import com.hcjeong.forestix.sensors.*
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Actual Android recorder/replay with synthetic-only grids in a redirected
 * cache UUID. Does not open the user's results, preferences, AR or location. */
internal object RawDepthFallbackChecks {
    private val pose=FloatArray(16){if(it%5==0)1f else 0f}
    private val ctx=RawCaptureStore.CaptureContext("quick",null,null,3,null)
    private val rgb=byteArrayOf(-1,-40,-1,-39) // Synthetic JPEG marker bytes.
    private fun frame(native:Boolean,index:Int):ArDepthFrame {
        val metres=if(native)(1250+index*10)/1000f else 1.2345678f+index*.01f
        val depth=FloatArray(128*96){metres};val conf=ByteArray(depth.size){2}
        if(!native){depth[0]=-0.0f;depth[1]=Float.fromBits(0x7fc01234);conf[0]=0;conf[1]=0;conf[2]=1}
        return ArDepthFrame(128,96,depth,conf,100.0,100.0,64.0,48.0,pose,
            depthFromViewAffine=floatArrayOf(1f,0f,0f,0f,1f,0f),
            rawDepthMm=if(native)ShortArray(depth.size){(1250+index*10).toShort()}else null,
            frameTimestampNanos=1_000_000_000L+index*100_000_000L)
    }
    private fun sameInputs(expected:List<ArDepthFrame>,actual:List<ArDepthFrame>){
        check(expected.size==actual.size){"Raw fallback dropped/reordered frames"}
        expected.zip(actual).forEach{(a,b)->
            check(a.depth.map{it.toRawBits()}==b.depth.map{it.toRawBits()}&&a.confidence.contentEquals(b.confidence))
            check(a.pose.contentEquals(b.pose)&&a.depthFromViewAffine!!.contentEquals(b.depthFromViewAffine!!))
        }
    }
    suspend fun run(base:Context,parent:File){
        val root=File(parent,"raw-depth-fallback");check(root.mkdir())
        val context=object:ContextWrapper(base){override fun getFilesDir()=root}
        for(nativePattern in listOf(listOf(false),listOf(true,false,true,false,true))){
            val frames=nativePattern.mapIndexed{i,native->frame(native,i)}
            val id=UUID.randomUUID().toString();val original=frames.map{it.depth.map{v->v.toRawBits()}}
            val expected=DBHEstimator.bracketChordEstimate(frames,GuideAxis.Row(48),.25,.75,ProjectCalibration())
            check(expected!=null){"Synthetic estimator fixture did not yield a result"}
            val evidence=RawCaptureEvidence(id,resultSeconds=2.0,displayedResult=RawCapturedResult(
                expected.diameterCm.toDouble(),expected.sigmaRmm.toDouble(),expected.confidence.raw,expected.method.raw,true))
            val out=RawCaptureStore.recordDbh(context,id,frames,64.0,48.0,true,RawCaptureStore.BracketSpec(32f,96f,48f),
                ProjectCalibration(),"silhouette","metric",ctx,rgb,captureEvidence=evidence)
            check(out.saved&&out.frameCount==frames.size){"Depth fallback recording: ${out.error}"}
            val dir=RawCaptureStore.dirOf(context,id);val m=requireNotNull(RawCaptureStore.manifestOf(context,id))
            check(m.getJSONObject("result_live").getInt("frame_count")==frames.size)
            val descriptors=m.getJSONObject("dbh").getJSONArray("frames")
            check(descriptors.length()==frames.size)
            frames.forEachIndexed{i,f->
                val meta=descriptors.getJSONObject(i)
                check(meta.getString("format")==if(f.rawDepthMm==null)"f32m"else"u16mm")
                check(File(dir,meta.getString("depth_file")).length()==f.depth.size.toLong()*if(f.rawDepthMm==null)4 else 2)
                check(File(dir,meta.getString("confidence_file")).readBytes().contentEquals(f.confidence))
            }
            sameInputs(frames,RawCaptureReplay.reconstructDbhFrames(dir,m))
            val exact=RawCaptureEvidence.parse(m.getJSONObject("capture_evidence").toString())
            sameInputs(frames,exact.frames.map{RawCaptureEvidenceIO.readFrame(it,dir)})
            check(exact.frames.map{it.role}==frames.indices.map{"dbh_$it"}&&exact.replayStatus=="pass")
            check(RawCaptureReplay.rerunDbh(dir,m)?.value==expected.diameterCm.toDouble())
            check(RawCaptureReplay.rerunCapturedDbh(dir,m)==expected.diameterCm.toDouble())
            check(File(dir,"rgb_0.jpg").readBytes().contentEquals(rgb))
            check(original==frames.map{it.depth.map{v->v.toRawBits()}}){"Recording mutated live input"}
            val conf=File(dir,descriptors.getJSONObject(0).getString("confidence_file"));val before=conf.readBytes()
            conf.writeBytes(ByteArray(before.size){3})
            check(RawCaptureReplay.reconstructDbhFrames(dir,m).isEmpty()){ "Corrupt confidence became high confidence" }
            conf.writeBytes(before)
            // A missing first legacy file must not turn a burst into a subset.
            check(File(dir,descriptors.getJSONObject(0).getString("depth_file")).delete())
            check(RawCaptureReplay.reconstructDbhFrames(dir,m).isEmpty())
            check(RawCaptureReplay.rerunCapturedDbh(dir,m)==expected.diameterCm.toDouble())
        }
        // Unsupported counts are explicit refusal, preserving queued GT bytes.
        val refusedId=UUID.randomUUID().toString()
        check(RawCaptureStore.setTruth(context,refusedId,17.0,TruthInput.Unit.CM)==RawCaptureStore.TruthWrite.QUEUED)
        val pending=File(RawCaptureStore.dirOf(context,refusedId),"pending_patch.json");val before=pending.readBytes()
        val refused=RawCaptureStore.recordDbh(context,refusedId,List(6){frame(false,it)},64.0,48.0,true,null,
            ProjectCalibration(),"silhouette","metric",ctx,null)
        check(!refused.saved&&refused.error!!.contains("frame count")&&refused.queuedTruthLost?.value==17.0)
        check(before.contentEquals(pending.readBytes())&&!File(pending.parentFile,"manifest.json").exists())

        // Height depth and RGB availability are independent.
        val id=UUID.randomUUID().toString();val f=frame(false,0);val live=HeightEstimator.estimate(0f,0f,10f,0f,.6f,-.1f)
        fun image(role:String)=RawCapturedImage(role,null,128,96,1.0,null,"synthetic",
            listOf(100.0,0.0,0.0,0.0,100.0,0.0,64.0,48.0,1.0),pose.map{it.toDouble()},null)
        val evidence=RawCaptureEvidence(id,resultSeconds=2.0,displayedResult=RawCapturedResult(live.heightM.toDouble(),null,"green","synthetic",true),
            scalars=mapOf("anchor_x" to 0.0,"anchor_z" to 0.0,"standing_x" to 10.0,"standing_z" to 0.0,
                "alpha_top_rad" to .6f.toDouble(),"alpha_base_rad" to (-.1f).toDouble()))
        val out=RawCaptureStore.recordHeight(context,id,Vec3(0f,0f,0f),"synthetic",1f,pose,-5f,pose,30f,pose,10f,live,
            ProjectCalibration(),emptyList(),false,"metric",ctx,RawCaptureStore.HeightAim(f,rgb,image("base")),
            RawCaptureStore.HeightAim(null,rgb,image("top")),evidence)
        check(out.saved&&out.frameCount==1){"Height fallback failed: ${out.error}"}
        val dir=RawCaptureStore.dirOf(context,id);val m=requireNotNull(RawCaptureStore.manifestOf(context,id))
        val h=m.getJSONObject("height");check(h.getJSONObject("base").getString("format")=="f32m")
        check(!h.getJSONObject("top").has("depth_file")&&h.getJSONObject("top").getString("rgb_file")=="rgb_top.jpg")
        val saved=RawCaptureEvidence.parse(m.getJSONObject("capture_evidence").toString())
        sameInputs(listOf(f),saved.frames.map{RawCaptureEvidenceIO.readFrame(it,dir)})
        check(saved.images!!.map{it.role}==listOf("base","top"))
        saved.images.forEach{check(it.file!!.read(dir).contentEquals(rgb))}
    }
}
