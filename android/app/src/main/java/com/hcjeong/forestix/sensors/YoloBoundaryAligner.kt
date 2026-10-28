package com.hcjeong.forestix.sensors

import android.content.Context
import ai.onnxruntime.*
import com.hcjeong.forestix.common.IdleResourceCache
import com.hcjeong.forestix.common.SingleFlightGate
import kotlin.math.*

/** Cached YOLO26n graph; the placement is reviewed before capture. */
class YoloBoundaryAligner private constructor(context:Context) {
    companion object {
        private val cache=IdleResourceCache<YoloBoundaryAligner>{runCatching{it.session.close()}}
        private val executionGate=SingleFlightGate()
        fun <R> withShared(context:Context,operation:(YoloBoundaryAligner)->R):R =
            executionGate.run { cache.withResource({YoloBoundaryAligner(context.applicationContext)},operation) }
        fun trimCached(){cache.trim()}
    }
    private val env=OrtEnvironment.getEnvironment()
    private var modelSHA:String?=null
    private val cpuThreads=LiveAutoInferencePolicy.cpuThreads(
        if(android.os.Build.VERSION.SDK_INT>=31)android.os.Build.SOC_MODEL else "",Runtime.getRuntime().availableProcessors())
    private val session=createSession(context)
    private fun createSession(context:Context):OrtSession {
        val model=context.assets.open("models/yolo26_alignment.onnx").use { it.readBytes() }
        modelSHA=RawCaptureEvidenceIO.hash(model)
        // Keep the original float32 CPU graph and OS scheduling. Only the
        // measured SoC gets a larger pool; untested devices retain two threads.
        return OrtSession.SessionOptions().use { opts ->
            opts.setIntraOpNumThreads(cpuThreads)
            env.createSession(model,opts)
        }
    }
    private val workspace=YoloInferenceWorkspace()
    fun align(chw:FloatArray,box:Letterbox,frame:ArDepthFrame,vw:Float,vh:Float,left:Double,right:Double):StemExtent? {
        return infer(chw,box,frame,vw,vh,left,right,false)?.extent
    }
    fun track(chw:FloatArray,box:Letterbox,frame:ArDepthFrame,vw:Float,vh:Float,
              imageFromView:FloatArray):LiveStemObservation? =
        infer(chw,box,frame,vw,vh,0.25,0.75,true,imageFromView)
    private class CaptureSink(val id:String,val image:RawCapturedImage?,val started:Double){var source:RawAIRecordSource?=null}
    fun trackWithCapture(chw:FloatArray,box:Letterbox,frame:ArDepthFrame,vw:Float,vh:Float,imageFromView:FloatArray,
                         id:String,sourceImage:RawCapturedImage?,started:Double):YoloTrackedCapture {
        val sink=CaptureSink(id,sourceImage,started)
        val observation=infer(chw,box,frame,vw,vh,.25,.75,true,imageFromView,sink)
        return YoloTrackedCapture(observation,sink.source)
    }

    @Synchronized
    private fun infer(chw:FloatArray,box:Letterbox,frame:ArDepthFrame,vw:Float,vh:Float,left:Double,right:Double,
                      live:Boolean,imageFromView:FloatArray?=null,capture:CaptureSink?=null):LiveStemObservation? {
        if(live && (imageFromView == null || imageFromView.size != 6 || !imageFromView.all { it.isFinite() }))return null
        if(box.size!=640 || chw.size!=3*640*640)return null
        val turns=listOf(-frame.pose[5],frame.pose[1],frame.pose[5],-frame.pose[1]).withIndex().minByOrNull { it.value }!!.index
        val rw=box.sourceWidth;val rh=box.sourceHeight;val uw=if(turns%2==0)rw else rh;val uh=if(turns%2==0)rh else rw
        val sc=min(rw.toDouble()/frame.width,rh.toDouble()/frame.height);val ox=(rw-frame.width*sc)/2;val oy=(rh-frame.height*sc)/2
        fun upright(x0:Double,y0:Double):Pair<Double,Double>{var x=x0;var y=y0;var iw=rw.toDouble();var ih=rh.toDouble();repeat(turns){val t=x;x=ih-1-y;y=t;val z=iw;iw=ih;ih=z};return x to y}
        val image=workspace.prepareInput(chw,turns)
        val inputHash=if(capture!=null)workspace.inputSha256()else null
        val selected=OnnxTensor.createTensor(env,image,longArrayOf(1,3,640,640)).use { input ->
            session.run(mapOf("images" to input)).use { out ->
                val head=out.get("output0").get() as OnnxTensor
                val proto=out.get("output1").get() as OnnxTensor
                if(head.info.shape.contentEquals(longArrayOf(1,37,8400)) && proto.info.shape.contentEquals(longArrayOf(1,32,160,160))) {
                    workspace.decodeMask(head.floatBuffer,proto.floatBuffer,uw,uh)
                } else null
            }
        }
        fun record(observation:LiveStemObservation?){
            if(capture!=null&&inputHash!=null)capture.source=YoloOwnedInference(capture.id,chw,box,turns,inputHash,frame,capture.image,
                selected,observation,vw,vh,capture.started,android.os.SystemClock.elapsedRealtimeNanos()/1e9,modelSHA,env.version,cpuThreads)
        }
        val mask=selected?:run{record(null);return null}
        fun raw(x:Int,y:Int):Boolean{if(x !in 0 until rw || y !in 0 until rh)return false;val p=upright(x.toDouble(),y.toDouble());return mask.contains(p.first.toInt(),p.second.toInt())}
        fun screenMask(vx:Double,vy:Double):Boolean {
            val m=imageFromView ?: return false
            val rgb=LiveStemMask.imagePoint(vx,vy,m,rw,rh,turns) ?: return false
            return mask.contains(rgb.first,rgb.second)
        }
        fun inView(x:Double,y0:Double):Boolean {
            if(live)return screenMask(x*vw,y0*vh)
            val p=frame.viewToDepth((x*vw).toFloat(),(y0*vh).toFloat()) ?: return false
            val rgb=LiveStemMask.cameraPoint(p.first,p.second,frame.width,frame.height,rw,rh,turns) ?: return false
            return mask.contains(rgb.first,rgb.second)
        }
        val overlay=if(live)LiveStemMask.sample(vw.toDouble(),vh.toDouble(),::inView)else null
        val guide=if(live)LiveStemMask.guideExtent(mask.score,::inView)else null
        // Auto only places bounds. The screen uses Adjust's current-depth path.
        if(live){val result=LiveStemObservation(null,overlay,guide);record(result);return result}
        val geometry=DBHEstimator.bracketDepthGeometry(frame,(left*vw).toFloat(),(right*vw).toFloat(),vh/2) ?: return null
        val a=frame.viewToDepth((left*vw).toFloat(),vh/2) ?: return null;val b=frame.viewToDepth((right*vw).toFloat(),vh/2) ?: return null
        val col=geometry.axis is GuideAxis.Col;val w=if(col)frame.height else frame.width;val h=if(col)frame.width else frame.height
        val aa=if(col)a.second else a.first;val bb=if(col)b.second else b.first
        val y=when(val axis=geometry.axis){is GuideAxis.Row->axis.y;is GuideAxis.Col->axis.x}
        val g=BoundaryAlignment.Grid(w,h,y,DoubleArray(w*h){i->frame.depthAt(if(col)i/w else i%w,if(col)i%w else i/w).toDouble()},geometry.leftFraction*w,geometry.rightFraction*w,if(col)frame.fy else frame.fx)
        if(abs(bb-aa)<0.000001)return null
        val mw=if(col)rh else rw;val mh=if(col)rw else rh;val offX=if(col)oy else ox;val offY=if(col)ox else oy
        val lines=mutableMapOf<Int,BooleanArray>()
        fun line(row:Int)=lines.getOrPut(row){val ry=floor((row+0.5)*sc+offY+0.5).toInt();if(ry !in 0 until mh)BooleanArray(0) else BooleanArray(mw){raw(if(col)ry else it,if(col)it else ry)}}
        val result=BoundaryAlignment.correct(g,sc,{x,yy->
            val l=line(yy);val rx=floor((x+0.5)*sc+offX+0.5).toInt();rx in l.indices&&l[rx]
        },{row->
            val l=line(row);val out=mutableListOf<Pair<Double,Double>>();var start:Int?=null
            for(x in 0..l.size){if(x<l.size&&l[x]){if(start==null)start=x}else{val s=start;if(s!=null){out.add((if(s>0)(s-0.5-offX)/sc-0.5 else -0.5) to (if(x<l.size)(x-0.5-offX)/sc-0.5 else w-0.5));start=null}}};out
        })
        if(result==null)return null
        val p0=left+(result.left-aa)/(bb-aa)*(right-left);val p1=left+(result.right-aa)/(bb-aa)*(right-left)
        return LiveStemObservation(StemExtent(min(p0,p1),max(p0,p1),mask.score,
            overlay?.runs?.sumOf { it.end-it.start } ?: 1),overlay)
    }
}
