package com.hcjeong.forestix.sensors

import android.content.Context
import ai.onnxruntime.*
import java.nio.FloatBuffer
import kotlin.math.*

/** Cached YOLO26n graph; the placement is reviewed before capture. */
class YoloBoundaryAligner private constructor(context:Context) {
    companion object {
        @Volatile private var cached:YoloBoundaryAligner?=null
        fun shared(context:Context):YoloBoundaryAligner = cached ?: synchronized(this) {
            cached ?: YoloBoundaryAligner(context.applicationContext).also { cached=it }
        }
    }
    private val env=OrtEnvironment.getEnvironment()
    private val session=OrtSession.SessionOptions().use { opts -> opts.setIntraOpNumThreads(2);env.createSession(context.assets.open("models/yolo26_alignment.onnx").use { it.readBytes() },opts) }
    fun align(chw:FloatArray,box:Letterbox,frame:ArDepthFrame,vw:Float,vh:Float,left:Double,right:Double):StemExtent? {
        val geometry=DBHEstimator.bracketDepthGeometry(frame,(left*vw).toFloat(),(right*vw).toFloat(),vh/2) ?: return null
        val a=frame.viewToDepth((left*vw).toFloat(),vh/2) ?: return null;val b=frame.viewToDepth((right*vw).toFloat(),vh/2) ?: return null
        val col=geometry.axis is GuideAxis.Col;val w=if(col)frame.height else frame.width;val h=if(col)frame.width else frame.height
        val aa=if(col)a.second else a.first;val bb=if(col)b.second else b.first
        val y=when(val axis=geometry.axis){is GuideAxis.Row->axis.y;is GuideAxis.Col->axis.x}
        val g=BoundaryAlignment.Grid(w,h,y,DoubleArray(w*h){i->frame.depthAt(if(col)i/w else i%w,if(col)i%w else i/w).toDouble()},geometry.leftFraction*w,geometry.rightFraction*w,if(col)frame.fy else frame.fx)
        if(abs(bb-aa)<0.000001 || box.size!=640 || chw.size!=3*640*640)return null
        val turns=listOf(-frame.pose[5],frame.pose[1],frame.pose[5],-frame.pose[1]).withIndex().minByOrNull { it.value }!!.index
        val rw=box.sourceWidth;val rh=box.sourceHeight;val uw=if(turns%2==0)rw else rh;val uh=if(turns%2==0)rh else rw
        val sc=min(rw.toDouble()/frame.width,rh.toDouble()/frame.height);val ox=(rw-frame.width*sc)/2;val oy=(rh-frame.height*sc)/2
        fun upright(x0:Double,y0:Double):Pair<Double,Double>{var x=x0;var y=y0;var iw=rw.toDouble();var ih=rh.toDouble();repeat(turns){val t=x;x=ih-1-y;y=t;val z=iw;iw=ih;ih=z};return x to y}
        // Rotate the camera letterbox as a square; its symmetric padding is preserved.
        val image=FloatArray(3*640*640)
        for(yy in 0 until 640)for(xx in 0 until 640) {
            var x=xx;var y0=yy;repeat(turns){val t=x;x=639-y0;y0=t}
            for(c in 0..2)image[c*409600+y0*640+x]=chw[c*409600+yy*640+xx]
        }
        val mask=OnnxTensor.createTensor(env,FloatBuffer.wrap(image),longArrayOf(1,3,640,640)).use { input ->
            session.run(mapOf("images" to input)).use { out ->
                val head=out.get("output0").get() as OnnxTensor
                val proto=out.get("output1").get() as OnnxTensor
                if(head.info.shape.contentEquals(longArrayOf(1,37,8400)) && proto.info.shape.contentEquals(longArrayOf(1,32,160,160))) {
                    val heads=FloatArray(37*8400);head.floatBuffer.get(heads)
                    val prototypes=FloatArray(32*25600);proto.floatBuffer.get(prototypes)
                    YoloAlignmentMask.select(heads,prototypes,uw,uh)
                } else null
            }
        } ?: return null
        fun raw(x:Int,y:Int):Boolean{val p=upright(x.toDouble(),y.toDouble());return mask.contains(p.first.toInt(),p.second.toInt())}
        val mw=if(col)rh else rw;val mh=if(col)rw else rh;val offX=if(col)oy else ox;val offY=if(col)ox else oy
        val lines=mutableMapOf<Int,BooleanArray>()
        fun line(row:Int)=lines.getOrPut(row){val ry=floor((row+0.5)*sc+offY+0.5).toInt();if(ry !in 0 until mh)BooleanArray(0) else BooleanArray(mw){raw(if(col)ry else it,if(col)it else ry)}}
        val result=BoundaryAlignment.correct(g,sc,{x,yy->val l=line(yy);val rx=floor((x+0.5)*sc+offX+0.5).toInt();rx in l.indices&&l[rx]},{row->
            val l=line(row);val out=mutableListOf<Pair<Double,Double>>();var start:Int?=null
            for(x in 0..l.size){if(x<l.size&&l[x]){if(start==null)start=x}else{val s=start;if(s!=null){out.add((if(s>0)(s-0.5-offX)/sc-0.5 else -0.5) to (if(x<l.size)(x-0.5-offX)/sc-0.5 else w-0.5));start=null}}};out
        }) ?: return null
        val p0=left+(result.left-aa)/(bb-aa)*(right-left);val p1=left+(result.right-aa)/(bb-aa)*(right-left)
        return StemExtent(min(p0,p1),max(p0,p1),mask.score,1)
    }
}
