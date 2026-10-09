package com.hcjeong.forestix.sensors

import kotlin.math.*

/** Fixed one-class YOLO26n export, with RGB-resolution logit interpolation. */
object YoloAlignmentMask {
    class Mask(val score:Float,private val logits:FloatArray,private val box:DoubleArray,
               private val width:Int,private val height:Int,private val cropX:Int,private val cropY:Int,
               private val cropWidth:Int,private val cropHeight:Int) {
        fun contains(x:Int,y:Int):Boolean {
            if(x !in 0 until width || y !in 0 until height || x<box[0] || x>=box[2] || y<box[1] || y>=box[3])return false
            val px=(cropX+(x+0.5)*cropWidth/width-0.5).coerceIn(0.0,159.0)
            val py=(cropY+(y+0.5)*cropHeight/height-0.5).coerceIn(0.0,159.0)
            val ix=px.toInt();val iy=py.toInt();val dx=px-ix;val dy=py-iy
            fun value(xi:Int,yi:Int)=logits[yi*160+xi].toDouble()
            return (1-dy)*((1-dx)*value(ix,iy)+dx*value(min(159,ix+1),iy))+
                dy*((1-dx)*value(ix,min(159,iy+1))+dx*value(min(159,ix+1),min(159,iy+1)))>0
        }
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
        var best:Mask?=null;var bestCentre=false;var bestCoverage=-1.0
        for(d in kept) {
            val box=doubleArrayOf(((d.box[0]-px)/resize).coerceIn(0.0,width.toDouble()),((d.box[1]-py)/resize).coerceIn(0.0,height.toDouble()),
                ((d.box[2]-px)/resize).coerceIn(0.0,width.toDouble()),((d.box[3]-py)/resize).coerceIn(0.0,height.toDouble()))
            // Exact prefilter: the chosen mask must cover a tested central pixel.
            val minX=min(width/2,(width*0.45).toInt());val maxX=max(width/2,(width*0.55).toInt()-1)
            val minY=min(height/2,(height*0.45).toInt());val maxY=max(height/2,(height*0.55).toInt()-1)
            if(box[2]<=minX || box[0]>maxX || box[3]<=minY || box[1]>maxY)continue
            val logits=FloatArray(25600)
            for(c in 0 until 32){val coefficient=head[(5+c)*anchors+d.anchor];for(p in logits.indices)logits[p]+=coefficient*prototypes[c*25600+p]}
            val mask=Mask(d.score,logits,box,width,height,cropX,cropY,cropWidth,cropHeight)
            val centre=mask.contains(width/2,height/2);var count=0;var total=0
            for(y in (height*0.45).toInt() until (height*0.55).toInt())for(x in (width*0.45).toInt() until (width*0.55).toInt()) {
                if(mask.contains(x,y))count++;total++
            }
            val coverage=if(total>0)count.toDouble()/total else 0.0
            if(!centre && coverage<0.1)continue
            if(best==null || centre&&!bestCentre || centre==bestCentre&&(coverage>bestCoverage || coverage==bestCoverage&&d.score>best.score)) {
                best=mask;bestCentre=centre;bestCoverage=coverage
            }
        }
        return best
    }
}
