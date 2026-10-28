package com.hcjeong.forestix.sensors

import kotlin.math.*

/** Single-input, shadow-only alternatives. No reference diameter, learned
 * bias, extra camera frames, native images, AR reset or automatic selection.
 * An experimental value is not an approved measurement. */
object DbhDepthCandidates {
    const val VERSION = 2
    const val DENSE = "dense_c80_v1"
    const val STRICT_RAW = "qualified_raw_c80_v1"
    const val RAY_RAW = "sparse_raw_ray_v1"
    const val PAIRED_OFFSET = "paired_raw_dense_offset_v1"
    const val DENSE_Q25 = "dense_ray_q25_v1"
    val RAW_IDS = listOf(STRICT_RAW, RAY_RAW, PAIRED_OFFSET)
    val IDS_V1 = listOf(DENSE)+RAW_IDS
    val IDS = IDS_V1+DENSE_Q25
    fun ids(version:Int)=when(version){1->IDS_V1;2->IDS;else->error("Unsupported depth comparison version")}

    data class Estimate(
        val id:String, val status:String,
        val rawDiameterCm:Double?=null, val diameterCm:Double?=null, val depthM:Double?=null,
        val rawPixels:Int=0, val axisSamples:Int=0, val axisCoverage:Double=0.0,
        val relativeMad:Double?=null, val offsetM:Double?=null,
    )
    data class Report(val axis:String, val fixed:Int, val left:Double?, val right:Double?,
        val focal:Double?, val principal:Double?, val estimates:List<Estimate>)
    private data class Point(val axis:Int,val z:Double,val full:Double?)
    private fun med(a:List<Double>):Double = BoundaryAlignment.median(a)
    private fun positive(v:Double?)=v?.takeIf{it.isFinite()&&it>0}
    private fun Estimate.calibrated(cal:ProjectCalibration)=copy(
        diameterCm=rawDiameterCm?.let{positive(cal.appliedToRawCm(it,expectedEpoch=9).toFloat().toDouble())})

    /** Invert a circular silhouette at the ACTUAL raw sample ray, rather
     * than assuming sparse samples occupy a uniform central-80% interval.
     * Exact for a circular cross-section and correct tangent endpoints;
     * does not correct sensor scale/bias or guarantee a circular real stem. */
    internal fun rayDiameterCm(left:Double,right:Double,pixel:Double,z:Double,focal:Double,principal:Double):Double? {
        return DbhRayGeometry.diameterCm(left,right,pixel,z,focal,principal)
    }

    fun evaluate(frame:ArDepthFrame,axis:GuideAxis,left:Double?,right:Double?,
                 cal:ProjectCalibration=ProjectCalibration.identity):Report {
        val col=axis is GuideAxis.Col;val extent=if(col)frame.height else frame.width
        val orthogonal=if(col)frame.width else frame.height
        val fixed=when(axis){is GuideAxis.Col->axis.x;is GuideAxis.Row->axis.y}
        val focal=if(col)frame.fy else frame.fx;val principal=if(col)frame.cy else frame.cx
        fun report(values:List<Estimate>)=Report(if(col)"col"else"row",fixed,
            left?.takeIf{it.isFinite()},right?.takeIf{it.isFinite()},focal.takeIf{it.isFinite()},principal.takeIf{it.isFinite()},values)
        fun unavailable(reason:String)=report(IDS.map{Estimate(it,reason)})
        if(left==null||right==null||!left.isFinite()||!right.isFinite()||left<0||right>=extent||right<=left||
            fixed !in 0 until orthogonal||!focal.isFinite()||focal<=1||!principal.isFinite()||
            frame.depth.size!=frame.width*frame.height||frame.confidence.size!=frame.depth.size)return unavailable("boundary_unavailable")
        if(!DbhDepthInputPolicy.acceptsFrame(frame))return unavailable("stale_or_unknown_input_policy")
        val margin=(right-left)*(1-BoundaryAlignment.SAMPLING_FRACTION)/2
        val eps=8*Math.ulp(1.0)*max(1.0,max(abs(left),abs(right)))
        val a=max(0,ceil(left+margin-eps).toInt());val b=min(extent-1,floor(right-margin+eps).toInt())
        if(b-a+1<3)return unavailable("too_few_depth_pixels")
        fun fullAt(i:Int):Double=frame.depthAt(if(col)fixed else i,if(col)i else fixed).toDouble()
        fun estimate(id:String,z:Double?,status:String,cm:Double?=null)=Estimate(id,status,
            rawDiameterCm=cm?:z?.let{BoundaryAlignment.sampledDiameterCm(right-left,it,focal)},depthM=z).calibrated(cal)
        val dense=BoundaryAlignment.sampledDepth(extent,left,right,focal,::fullAt)
        val baseline=estimate(DENSE,dense?.second,if(dense==null)"dense_unavailable"else if(DbhDepthInputPolicy.acceptsDistance(dense.second))"baseline_only"else"out_of_range",dense?.first)
        // Fixed, exploratory lower-quartile radius after removing circular
        // ray-position effects. Real Raw confidence is NOT invented for it.
        // It can reduce positive background/smoothing contamination, but can
        // also introduce negative bias. Never an automatic fallback/result.
        val densePoints=(a..b).mapNotNull{i->
            val value=fullAt(i)
            if(!value.isFinite()||value !in .001..8.0)null
            else rayDiameterCm(left,right,i.toDouble(),value,focal,principal)?.let{i to it}
        }
        val denseRays=densePoints.map{it.second}.sorted()
        val denseQ25=if(denseRays.size<3)Estimate(DENSE_Q25,"dense_unavailable")else{
            val index=(denseRays.size-1)*.25;val loIndex=floor(index).toInt();val fraction=index-loIndex
            val q=denseRays[loIndex]*(1-fraction)+denseRays[min(denseRays.lastIndex,loIndex+1)]*fraction
            val median=med(denseRays)
            Estimate(DENSE_Q25,if(dense?.second?.let(DbhDepthInputPolicy::acceptsDistance)==true)"experimental_dense_only"else"out_of_range",
                rawDiameterCm=q,depthM=dense?.second,axisSamples=denseRays.size,
                axisCoverage=(densePoints.maxOf{it.first}-densePoints.minOf{it.first}).toDouble()/max(1,b-a),
                relativeMad=med(denseRays.map{abs(it-median)})/median).calibrated(cal)
        }
        val raw=frame.rawDepthEvidence
        val rawStatus=when{
            raw==null->"raw_unavailable"
            raw.width!=frame.width||raw.height!=frame.height||!DbhMeasurementDepth.currentProjection(raw)->"raw_projection_unavailable"
            else->null
        }
        if(rawStatus!=null)return report(listOf(baseline)+RAW_IDS.map{Estimate(it,rawStatus)}+denseQ25)
        requireNotNull(raw)
        val profile=DbhMeasurementDepth.profile(frame,axis,left,right)
        val strictZ=profile.medianM?.takeIf{profile.qualified}
        val strictStatus=when{
            !profile.qualified->profile.status
            strictZ==null||!DbhDepthInputPolicy.acceptsDistance(strictZ)->"out_of_range"
            else->"qualified_raw"
        }
        val strict=(if(strictStatus=="qualified_raw")estimate(STRICT_RAW,strictZ,strictStatus)
            else Estimate(STRICT_RAW,strictStatus,depthM=strictZ)).copy(rawPixels=profile.supportedPixels,axisSamples=profile.supportedAxisSamples,
            axisCoverage=profile.supportedAxisSamples.toDouble()/max(1,profile.candidateAxisSamples))
        val points=ArrayList<Point>()
        for(i in a..b)for(offset in -2..2){
            val q=fixed+offset;if(q !in 0 until orthogonal)continue
            val p=if(col)i*frame.width+q else q*frame.width+i
            val mm=raw.millimeters[p].toInt()and 65535
            if((raw.confidence[p].toInt()and 255)<DbhMeasurementDepth.MIN_CONFIDENCE||mm !in 1..8000)continue
            val full=frame.depth[p].toDouble().takeIf{it.isFinite()&&it in .001..8.0&&frame.confidence[p].toInt()>0}
            points.add(Point(i,mm/1000.0,full))
        }
        val groups=points.groupBy{it.axis}
        val z=groups.values.map{med(it.map{p->p.z})}.takeIf{it.isNotEmpty()}?.let(::med)
        fun coverage(indexes:Collection<Int>)=if(indexes.size<2)0.0 else
            (indexes.max()-indexes.min()).toDouble()/max(1,b-a)
        fun status(count:Int,axisCount:Int,cover:Double,mad:Double?,inRange:Boolean):String=when{
            !inRange->"out_of_range"
            count<6||axisCount<3||cover<.5->"experimental_weak_support"
            mad==null||mad>.05->"experimental_inconsistent"
            else->"experimental_spatial_support"
        }
        val rayByAxis=groups.mapNotNull{(_,p)->p.mapNotNull{rayDiameterCm(left,right,it.axis.toDouble(),it.z,focal,principal)}
            .takeIf{it.isNotEmpty()}?.let(::med)}
        val rayCm=rayByAxis.takeIf{it.isNotEmpty()}?.let(::med)
        val rayMad=rayCm?.let{v->med(rayByAxis.map{abs(it-v)})/v}
        val cov=coverage(groups.keys)
        val ray=Estimate(RAY_RAW,if(rayCm==null)"raw_unavailable"else status(points.size,groups.size,cov,rayMad,z?.let(DbhDepthInputPolicy::acceptsDistance)==true),
            rawDiameterCm=rayCm,depthM=z,rawPixels=points.size,axisSamples=groups.size,axisCoverage=cov,relativeMad=rayMad).calibrated(cal)
        // Same-frame paired residual, not a fitted laboratory multiplier.
        // Keep dense lateral shape, offset by Raw-minus-dense at matching pixels.
        val paired=points.filter{it.full!=null}.groupBy{it.axis}
        val offsets=paired.values.map{med(it.map{p->p.z-p.full!!})}
        val delta=offsets.takeIf{it.isNotEmpty()}?.let(::med)
        val corrected=delta?.let{d->(a..b).map{fullAt(it)}.filter{it.isFinite()&&it in .001..8.0}
            .takeIf{it.size>=3}?.let{med(it)+d}?.takeIf{it.isFinite()&&it>0}}
        val deltaMad=corrected?.let{c->delta?.let{d->med(offsets.map{abs(it-d)})/c}}
        val pairedCoverage=coverage(paired.keys)
        val hybrid=estimate(PAIRED_OFFSET,corrected,if(corrected==null)"paired_input_unavailable"else
            status(paired.values.sumOf{it.size},paired.size,pairedCoverage,deltaMad,DbhDepthInputPolicy.acceptsDistance(corrected)))
            .copy(rawPixels=paired.values.sumOf{it.size},axisSamples=paired.size,axisCoverage=pairedCoverage,relativeMad=deltaMad,offsetM=delta)
        return report(listOf(baseline,strict,ray,hybrid,denseQ25))
    }
}
