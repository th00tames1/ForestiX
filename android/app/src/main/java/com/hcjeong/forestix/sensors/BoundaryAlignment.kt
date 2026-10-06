package com.hcjeong.forestix.sensors

import kotlin.math.*

/** Shared manual, depth-walk and AI-assisted geometry, independent of reference diameters. */
object BoundaryAlignment {
    data class Grid(val width:Int,val height:Int,val row:Int,val depth:DoubleArray,val left:Double,val right:Double,val focal:Double) {
        fun at(x:Int,y:Int)=depth[y*width+x]
    }
    data class Result(val left:Double,val right:Double,val diameterCm:Double,val leftReason:String,val rightReason:String)
    fun median(a:List<Double>):Double { val s=a.filter { it.isFinite() }.sorted();return if(s.isEmpty()) Double.NaN else (s[(s.size-1)/2]+s[s.size/2])/2 }
    fun mad(a:List<Double>):Double { val m=median(a);return median(a.map { abs(it-m) }) }
    fun valid(a:List<Double>)=a.filter { it.isFinite() && it>0.001 && it<=8 }
    // Uniform-lateral circular approximation, not a fitted sensor multiplier.
    // Raw depth pixels remain unchanged. Keep identical to the Swift geometry.
    val FULL_SPAN_DEPTH_OFFSET_FACTOR = 1.0 - sqrt(0.75)
    fun fullSpanDiameterCm(spanPx:Double,medianDepthM:Double,focalPx:Double):Double? {
        if(!spanPx.isFinite() || !medianDepthM.isFinite() || !focalPx.isFinite() ||
            spanPx<=0 || medianDepthM<=0 || focalPx<=1) return null
        val k=spanPx/(2*focalPx);val q=k*(k+sqrt(1+k*k))
        val cm=200*medianDepthM*q/(1+FULL_SPAN_DEPTH_OFFSET_FACTOR*q)
        return cm.takeIf { it.isFinite() && it>0 }
    }
    /** Integer pixel centres inside the continuous guide interval, endpoints included. */
    fun fullSpanSample(width:Int,left:Double,right:Double,focal:Double,depthAt:(Int)->Double):Pair<Double,Double>? {
        if(width<=0 || !left.isFinite() || !right.isFinite() || left<0 || right>=width || right<=left) return null
        val lo=ceil(left).toInt();val hi=floor(right).toInt();if(hi<lo)return null
        val samples=valid((lo..hi).map(depthAt));if(samples.size<3)return null
        val z=median(samples);if(z !in 0.3..5.0)return null
        val cm=fullSpanDiameterCm(right-left,z,focal) ?: return null
        return cm to z
    }
    fun diameter(g:Grid,left:Double,right:Double):Pair<Double,Double>? {
        if(g.width<=0 || g.height<=0 || g.depth.size!=g.width*g.height || g.row !in 0 until g.height) return null
        return fullSpanSample(g.width,left,right,g.focal) { g.at(it,g.row) }
    }
    fun prompts(g:Grid):List<Pair<Double,Double>>? {
        val span=g.right-g.left;val core=(0 until g.width).filter { it>=g.left+0.25*span && it<=g.right-0.25*span }
        val ys=listOf(-0.15,0.0,0.15).map { (g.row+it*g.height).toInt().coerceIn(0,g.height-1) }
        val adjacent=valid(listOf(ys[0],ys[2]).flatMap { y -> (max(0,y-2) until min(g.height,y+3)).flatMap { yy -> core.map { g.at(it,yy) } } })
        val z=median(adjacent);if(!z.isFinite())return null
        val spread=max(0.05,3*1.4826*mad(adjacent));val pts=mutableListOf<Pair<Double,Double>>()
        for(y in ys) {
            val x=core.filter { val v=g.at(it,y);v.isFinite()&&v>0.001&&v<=8 }.minByOrNull { abs(g.at(it,y)-z)/spread+abs(it-(g.left+g.right)/2)/max(span,1.0) } ?: return null
            pts.add(x.toDouble() to y.toDouble())
        }
        pts.add(max(0.0,g.left-0.25*span) to max(0.0,g.row-0.25*g.height))
        pts.add(min(g.width-1.0,g.right+0.25*span) to min(g.height-1.0,g.row+0.25*g.height));return pts
    }
    fun correct(g:Grid,scale:Double,mask:(Int,Int)->Boolean,rowSegments:(Int)->List<Pair<Double,Double>>):Result? {
        val base=diameter(g,g.left,g.right)?.first ?: return null
        fun retained(why:String)=Result(g.left,g.right,base,why,why)
        val span=g.right-g.left;val core=(0 until g.width).filter { it>=g.left+0.25*span && it<=g.right-0.25*span }
        if(core.isEmpty())return retained("empty_core")
        val near=(max(0,g.row-2) until min(g.height,g.row+3)).toList()
        val ys=(max(0,g.row-(g.height*0.08).toInt()) until min(g.height,g.row+(g.height*0.08).toInt()+1)).toList()
        fun vals(rows:List<Int>)=valid(rows.flatMap { y -> core.map { g.at(it,y) } })
        val ay=listOf(-0.15,0.15).map { (g.row+it*g.height).toInt().coerceIn(0,g.height-1) }
        val adj=vals(ay.flatMap { (max(0,it-2) until min(g.height,it+3)).toList() });val center=vals(near)
        val za=median(adj);val lim=max(0.05,3*1.4826*mad(adj))
        if(center.isNotEmpty()&&za.isFinite()&&center.count { it<za-lim }.toDouble()/center.size>=0.1)return retained("depth_occlusion")
        val v=vals(ys);val zc=median(v);if(v.isEmpty()||center.isEmpty())return retained("depth_support")
        if(!near.all { y -> core.all { mask(it,y) } })return retained("mask_core_gap")
        if(v.count { abs(it/zc-1)<=0.2 }.toDouble()/v.size<0.6 || center.count { abs(it/zc-1)<=0.2 }.toDouble()/center.size<0.6)return retained("depth_support")
        val diffs=ys.flatMap { y -> core.drop(1).zip(core).map { g.at(it.first,y)-g.at(it.second,y) } }
        val noise=max(1e-6,1.4826*mad(diffs)/sqrt(2.0));val bounds=listOf(mutableListOf<Pair<Double,Double>>(),mutableListOf<Pair<Double,Double>>())
        for(y in ys) {
            val best=rowSegments(y).filter { (l,r) -> min(r,g.right-0.25*span)>max(l,g.left+0.25*span) }.maxWithOrNull(compareBy<Pair<Double,Double>> { (l,r) -> min(r,g.right-0.25*span)-max(l,g.left+0.25*span) }.thenBy { it.second-it.first })
            if(best!=null){if(best.first> -0.5)bounds[0].add(y.toDouble() to best.first);if(best.second<g.width-0.5)bounds[1].add(y.toDouble() to best.second)}
        }
        val proposed=doubleArrayOf(g.left,g.right);val reasons=arrayOf("accepted","accepted")
        for(side in 0..1) {
            val pts=bounds[side];val old=proposed[side]
            if(pts.size<max(3.0,0.6*ys.size)){reasons[side]="insufficient_rows";continue}
            val slopes=mutableListOf<Double>();for(i in pts.indices)for(j in 0 until i)slopes.add((pts[i].second-pts[j].second)/(pts[i].first-pts[j].first))
            val slope=median(slopes);val b=median(pts.map { it.second-slope*(it.first-g.row) });val resid=mad(pts.map { it.second-(slope*(it.first-g.row)+b) })
            if(resid>2)reasons[side]="nonlinear_boundary"
            else if(abs(b-old)>0.1*span)reasons[side]="large_shift"
            else if(abs(b-old)<1/scale)reasons[side]="below_rgb_resolution"
            else {
                val ix=(0..2).map { if(side==0)ceil(b).toInt()+it else floor(b).toInt()-it }.filter { it in 0 until g.width }
                val inside=valid(near.flatMap { y -> ix.map { g.at(it,y) } })
                if(inside.size<3||median(inside)>zc+base/200+3*noise)reasons[side]="incoherent_inside_depth"
                else proposed[side]=old+0.5*(b-old)
            }
        }
        val answer=diameter(g,proposed[0],proposed[1]) ?: return retained("incoherent_result")
        if(answer.second>zc+base/200+3*noise)return retained("incoherent_result")
        return Result(proposed[0],proposed[1],answer.first,reasons[0],reasons[1])
    }
}
