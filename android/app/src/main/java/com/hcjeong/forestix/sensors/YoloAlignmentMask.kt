package com.hcjeong.forestix.sensors

import kotlin.math.*
import kotlinx.serialization.json.*

/** Fixed one-class YOLO26n export, with RGB-resolution logit interpolation. */
object YoloAlignmentMask {
    /** Same interpolation arithmetic, computed once per source image geometry. */
    internal class Sampling(width:Int,height:Int,cropX:Int,cropY:Int,cropWidth:Int,cropHeight:Int) {
        val xs=IntArray(width);val ys=IntArray(height)
        val dx=DoubleArray(width);val dy=DoubleArray(height)
        init {
            for(x in 0 until width){val p=(cropX+(x+0.5)*cropWidth/width-0.5).coerceIn(0.0,159.0);xs[x]=p.toInt();dx[x]=p-xs[x]}
            for(y in 0 until height){val p=(cropY+(y+0.5)*cropHeight/height-0.5).coerceIn(0.0,159.0);ys[y]=p.toInt();dy[y]=p-ys[y]}
        }
    }
    class Mask internal constructor(val score:Float,private val logits:FloatArray,private val box:DoubleArray,
               private val width:Int,private val height:Int,private val cropX:Int,private val cropY:Int,
               private val cropWidth:Int,private val cropHeight:Int,private val sampling:Sampling) {
        internal fun captureGeometry()=buildJsonObject{
            put("score",score.toDouble());put("box",JsonArray(box.map{JsonPrimitive(it)}));put("width",width);put("height",height)
            put("crop_x",cropX);put("crop_y",cropY);put("crop_width",cropWidth);put("crop_height",cropHeight)
        }
        internal fun captureLogitsBytes()=RawAIInferenceIO.floatBytes(logits)
        fun contains(x:Int,y:Int):Boolean {
            if(x !in 0 until width || y !in 0 until height || x<box[0] || x>=box[2] || y<box[1] || y>=box[3])return false
            val ix=sampling.xs[x];val iy=sampling.ys[y];val dx=sampling.dx[x];val dy=sampling.dy[y]
            fun value(xi:Int,yi:Int)=logits[yi*160+xi].toDouble()
            return (1-dy)*((1-dx)*value(ix,iy)+dx*value(min(159,ix+1),iy))+
                dy*((1-dx)*value(ix,min(159,iy+1))+dx*value(min(159,ix+1),min(159,iy+1)))>0
        }
    }
    fun restore(g:JsonObject,logits:FloatArray):Mask {
        fun n(k:String)=g.getValue(k).jsonPrimitive.int
        val width=n("width");val height=n("height");val cx=n("crop_x");val cy=n("crop_y");val cw=n("crop_width");val ch=n("crop_height")
        val score=g.getValue("score").jsonPrimitive.double;val box=g.getValue("box").jsonArray.map{it.jsonPrimitive.double}.toDoubleArray()
        require(logits.size==25600&&width in 1..8192&&height in 1..8192&&box.size==4&&box.all{it.isFinite()}&&score.isFinite()&&
            cx>=0&&cy>=0&&cw>0&&ch>0&&cx.toLong()+cw<=160&&cy.toLong()+ch<=160){"Recorded mask geometry"}
        return Mask(score.toFloat(),logits,box,width,height,cx,cy,cw,ch,Sampling(width,height,cx,cy,cw,ch))
    }
    fun select(head:FloatArray,prototypes:FloatArray,width:Int,height:Int):Mask? {
        val anchors=8400
        if(head.size!=37*anchors || prototypes.size!=32*25600 || width<=0 || height<=0)return null
        data class Detection(val anchor:Int,val score:Float,val box:DoubleArray)
        val detections=mutableListOf<Detection>()
        for(i in 0 until anchors) {
            val score=head[4*anchors+i];if(!score.isFinite() || score<0.25f)continue
            val cx=head[i].toDouble();val cy=head[anchors+i].toDouble()
            val w=head[2*anchors+i].toDouble();val h=head[3*anchors+i].toDouble()
            if(!cx.isFinite() || !cy.isFinite() || !w.isFinite() || !h.isFinite() || w<=0 || h<=0)continue
            detections.add(Detection(i,score,doubleArrayOf(cx-w/2,cy-h/2,cx+w/2,cy+h/2)))
        }
        val kept=mutableListOf<Detection>()
        for(d in detections.sortedWith(compareByDescending<Detection>{it.score}.thenBy{it.anchor})) {
            val overlaps=kept.any { k ->
                val ix=max(0.0,min(d.box[2],k.box[2])-max(d.box[0],k.box[0]))
                val iy=max(0.0,min(d.box[3],k.box[3])-max(d.box[1],k.box[1]))
                val a=(d.box[2]-d.box[0])*(d.box[3]-d.box[1]);val b=(k.box[2]-k.box[0])*(k.box[3]-k.box[1])
                ix*iy/(a+b-ix*iy)>0.45
            }
            if(!overlaps)kept.add(d)
            if(kept.size==300)break
        }
        val resize=640.0/max(width,height);val nw=(width*resize+0.5).toInt();val nh=(height*resize+0.5).toInt()
        val px=floor((640-nw)/2.0-0.1+0.5);val py=floor((640-nh)/2.0-0.1+0.5)
        val gain=min(160.0/width,160.0/height);val padx=(160-width*gain)/2;val pady=(160-height*gain)/2
        val cropX=floor(padx-0.1+0.5).toInt();val cropY=floor(pady-0.1+0.5).toInt()
        val cropWidth=160-cropX-floor(padx+0.1+0.5).toInt();val cropHeight=160-cropY-floor(pady+0.1+0.5).toInt()
        val sampling=Sampling(width,height,cropX,cropY,cropWidth,cropHeight)
        val minX=min(width/2,(width*0.45).toInt());val maxX=max(width/2,(width*0.55).toInt()-1)
        val minY=min(height/2,(height*0.45).toInt());val maxY=max(height/2,(height*0.55).toInt()-1)
        val x0=sampling.xs[minX];val x1=min(159,sampling.xs[maxX]+1)
        val y0=sampling.ys[minY];val y1=min(159,sampling.ys[maxY]+1)
        val scratch=FloatArray(25600)
        var best:Detection?=null;var bestBox:DoubleArray?=null;var bestCentre=false;var bestCoverage=-1.0
        for(d in kept) {
            val box=doubleArrayOf(((d.box[0]-px)/resize).coerceIn(0.0,width.toDouble()),((d.box[1]-py)/resize).coerceIn(0.0,height.toDouble()),
                ((d.box[2]-px)/resize).coerceIn(0.0,width.toDouble()),((d.box[3]-py)/resize).coerceIn(0.0,height.toDouble()))
            // Exact prefilter: the chosen mask must cover a tested central pixel.
            if(box[2]<=minX || box[0]>maxX || box[3]<=minY || box[1]>maxY)continue
            // Only central cells (and all bilinear neighbours) affect selection.
            // This partial mask cannot escape; materialize the chosen full mask below.
            for(y in y0..y1)java.util.Arrays.fill(scratch,y*160+x0,y*160+x1+1,0f)
            for(c in 0 until 32){
                val coefficient=head[(5+c)*anchors+d.anchor]
                for(y in y0..y1)for(x in x0..x1){val p=y*160+x;scratch[p]+=coefficient*prototypes[c*25600+p]}
            }
            val mask=Mask(d.score,scratch,box,width,height,cropX,cropY,cropWidth,cropHeight,sampling)
            val centre=mask.contains(width/2,height/2);var count=0;var total=0
            for(y in (height*0.45).toInt() until (height*0.55).toInt())for(x in (width*0.45).toInt() until (width*0.55).toInt()) {
                if(mask.contains(x,y))count++;total++
            }
            val coverage=if(total>0)count.toDouble()/total else 0.0
            if(!centre && coverage<0.1)continue
            if(best==null || centre&&!bestCentre || centre==bestCentre&&(coverage>bestCoverage || coverage==bestCoverage&&d.score>best.score)) {
                best=d;bestBox=box;bestCentre=centre;bestCoverage=coverage
            }
        }
        val chosen=best ?: return null
        val logits=FloatArray(25600)
        // Preserve the exact original float accumulation order and output ownership.
        for(c in 0 until 32){val coefficient=head[(5+c)*anchors+chosen.anchor];for(p in logits.indices)logits[p]+=coefficient*prototypes[c*25600+p]}
        return Mask(chosen.score,logits,bestBox!!,width,height,cropX,cropY,cropWidth,cropHeight,sampling)
    }
}
