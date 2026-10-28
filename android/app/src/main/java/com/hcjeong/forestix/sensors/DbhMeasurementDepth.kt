package com.hcjeong.forestix.sensors

import kotlin.math.*

/** Measurement input policy, not a calibration. The dense map can locate
 * edges, but new Android DBH measurements use real confidence-qualified Raw
 * Depth. No reference diameter, minimum-depth shortcut or temporal averaging. */
object DbhMeasurementDepth {
    const val LEGACY_FULL = "legacy_full_depth_v1"
    const val QUALIFIED_RAW = "arcore_qualified_raw_v1"
    const val RAW_RAY = "arcore_raw_ray_v2"
    const val MIN_CONFIDENCE = 128
    // Majority/coverage requirements are application safety heuristics, not
    // manufacturer's error guarantees. Confidence cannot certify true range.
    const val MIN_COVERAGE = .60
    const val MIN_AXIS_SAMPLES = 3
    const val RETRY_MESSAGE = "Depth on the trunk is uncertain. Move sideways while keeping it in view, then retry."
    fun usesRaw(policy:String)=policy==QUALIFIED_RAW||policy==RAW_RAY
    fun recognized(policy:String)=policy==LEGACY_FULL||usesRaw(policy)
    // Geometry epoch and input-selection generation are both recorded. A
    // coefficient fitted to dense/v1 samples cannot calibrate the new rays.
    fun calibrationEpoch(policy:String)=if(policy==RAW_RAY)10 else 9

    /** Raw information may be old but reprojected to the CURRENT view.
     * Its corresponding confidence image must be recent. Information time
     * must be real and cannot follow the projection by an arbitrary amount. */
    fun currentProjection(raw:DbhRawDepthEvidence):Boolean =
        raw.timestampNanos>0L &&
        DbhDepthInputPolicy.acceptsSensorTimes(raw.cameraTimestampNanos,raw.confidenceTimestampNanos) &&
        raw.timestampNanos-raw.confidenceTimestampNanos <= DbhDepthInputPolicy.MAX_FUTURE_SKEW_NANOS

    data class Profile(val status:String,val values:DoubleArray=doubleArrayOf(),val first:Int=0,
        val supportedPixels:Int=0,val candidatePixels:Int=0,val supportedAxisSamples:Int=0,
        val candidateAxisSamples:Int=0,val rawInformationAgeNanos:Long?=null,
        val diameterCm:Double?=null) {
        val qualified:Boolean get()=status=="qualified_raw"
        val medianM:Double? get()=values.filter{it.isFinite()}.takeIf{it.isNotEmpty()}?.let{median(it)}
        fun at(index:Int):Double=values.getOrNull(index-first)?:Double.NaN
    }
    private fun median(values:List<Double>):Double {
        val a=values.sorted();return (a[(a.size-1)/2]+a[a.size/2])/2
    }
    fun measurementProfile(frame:ArDepthFrame,axis:GuideAxis,left:Double,right:Double):Profile =
        if(frame.depthInputPolicy==RAW_RAY)rayProfile(frame,axis,left,right)else profile(frame,axis,left,right)

    /** Sparse, position-aware selection. Six real pixels, three separated
     * along-axis locations on both sides, and radius agreement are
     * safety heuristics; they do not independently certify absolute range. */
    private fun rayProfile(frame:ArDepthFrame,axis:GuideAxis,left:Double,right:Double):Profile {
        val raw=frame.rawDepthEvidence?:return Profile("raw_unavailable")
        if(raw.width!=frame.width||raw.height!=frame.height||!currentProjection(raw))return Profile("raw_projection_unavailable")
        val col=axis is GuideAxis.Col;val extent=if(col)frame.height else frame.width
        val orthogonal=if(col)frame.width else frame.height
        val fixed=when(axis){is GuideAxis.Col->axis.x;is GuideAxis.Row->axis.y}
        val focal=if(col)frame.fy else frame.fx;val principal=if(col)frame.cy else frame.cx
        if(!left.isFinite()||!right.isFinite()||left<0||right>=extent||right<=left||fixed !in 0 until orthogonal||
            !focal.isFinite()||focal<=1||!principal.isFinite())return Profile("invalid_bracket")
        val margin=(right-left)*(1-BoundaryAlignment.SAMPLING_FRACTION)/2
        val epsilon=8*Math.ulp(1.0)*max(1.0,max(abs(left),abs(right)))
        val a=max(0,ceil(left+margin-epsilon).toInt());val b=min(extent-1,floor(right-margin+epsilon).toInt())
        if(b-a+1<3)return Profile("too_few_depth_pixels")
        data class AxisPoint(val index:Int,val cm:Double,val z:Double,val pixels:Int)
        val points=ArrayList<AxisPoint>();var candidates=0
        for(i in a..b){
            val zs=ArrayList<Double>(5);val cms=ArrayList<Double>(5)
            for(offset in -2..2){
                val q=fixed+offset;if(q !in 0 until orthogonal)continue
                candidates++
                val p=if(col)i*frame.width+q else q*frame.width+i
                val mm=raw.millimeters[p].toInt()and 65535
                if((raw.confidence[p].toInt()and 255)<MIN_CONFIDENCE||mm !in 1..8000)continue
                val z=mm/1000.0
                val cm=DbhRayGeometry.diameterCm(left,right,i.toDouble(),z,focal,principal)?:continue
                zs.add(z);cms.add(cm)
            }
            if(zs.isNotEmpty())points.add(AxisPoint(i,median(cms),median(zs),zs.size))
        }
        val age=(raw.confidenceTimestampNanos-raw.timestampNanos).coerceAtLeast(0)
        if(points.size<3)return Profile("raw_ray_support_insufficient",rawInformationAgeNanos=age)
        val center=median(points.map{it.cm})
        val tolerance=max(.1,center*.05)
        val inliers=points.filter{abs(it.cm-center)<=tolerance}
        val coverage=if(inliers.size<2)0.0 else (inliers.maxOf{it.index}-inliers.minOf{it.index}).toDouble()/max(1,b-a)
        val supported=inliers.sumOf{it.pixels}
        // A centre hole is allowed: every value is inverted at its actual
        // ray. Require support on both sides instead of inventing a uniform
        // central median or demanding Raw fill the entire width.
        val midpoint=(a+b)/2.0
        val enough=inliers.size>=3&&supported>=6&&coverage>=.5&&
            inliers.any{it.index<midpoint}&&inliers.any{it.index>midpoint}
        val consistent=inliers.size.toDouble()/points.size>=.70
        val values=DoubleArray(b-a+1){Double.NaN}
        inliers.forEach{values[it.index-a]=it.z}
        return Profile(if(!enough)"raw_ray_support_insufficient"else if(!consistent)"raw_ray_inconsistent"else"qualified_raw",
            values,a,supported,candidates,inliers.size,b-a+1,age,
            if(enough&&consistent)median(inliers.map{it.cm})else null)
    }
    fun profile(frame:ArDepthFrame,axis:GuideAxis,left:Double,right:Double):Profile {
        val raw=frame.rawDepthEvidence?:return Profile("raw_unavailable")
        if(raw.width!=frame.width||raw.height!=frame.height||!currentProjection(raw))return Profile("raw_projection_unavailable")
        val age=(raw.confidenceTimestampNanos-raw.timestampNanos).coerceAtLeast(0)
        val col=axis is GuideAxis.Col;val extent=if(col)frame.height else frame.width
        val fixed=when(axis){is GuideAxis.Col->axis.x;is GuideAxis.Row->axis.y}
        val orthogonal=if(col)frame.width else frame.height
        if(!left.isFinite()||!right.isFinite()||left<0||right>=extent||right<=left||fixed !in 0 until orthogonal)
            return Profile("invalid_bracket")
        val margin=(right-left)*(1-BoundaryAlignment.SAMPLING_FRACTION)/2
        val epsilon=8*Math.ulp(1.0)*max(1.0,max(abs(left),abs(right)))
        val a=max(0,ceil(left+margin-epsilon).toInt());val b=min(extent-1,floor(right-margin+epsilon).toInt())
        if(b-a+1<MIN_AXIS_SAMPLES)return Profile("too_few_depth_pixels")
        val values=DoubleArray(b-a+1){Double.NaN};val bins=IntArray(3);val binTotals=IntArray(3)
        var supported=0;var candidates=0;var axisSamples=0
        for(i in a..b){
            val local=ArrayList<Double>(5);var localCandidates=0
            for(offset in -2..2){
                val q=fixed+offset;if(q !in 0 until orthogonal)continue
                localCandidates++;candidates++
                val p=if(col)i*frame.width+q else q*frame.width+i
                val mm=raw.millimeters[p].toInt()and 65535
                if((raw.confidence[p].toInt()and 255)>=MIN_CONFIDENCE&&mm in 1..8000){local.add(mm/1000.0);supported++}
            }
            val bin=min(2,(i-a)*3/values.size);binTotals[bin]++
            // A majority across the narrow vertical neighbourhood, not one
            // lucky depth point; each along-axis position gets equal weight.
            if(local.size>=min(3,localCandidates)){
                val z=median(local)
                val deviation=median(local.map{abs(it-z)})
                if(deviation<=max(.01,z*.02)){
                    values[i-a]=z;axisSamples++;bins[bin]++
                }
            }
        }
        val enough=axisSamples>=MIN_AXIS_SAMPLES && candidates>0 &&
            supported.toDouble()/candidates>=MIN_COVERAGE && axisSamples.toDouble()/values.size>=MIN_COVERAGE &&
            bins.indices.all{bins[it]>=max(1,ceil(binTotals[it]*.5).toInt())}
        return Profile(if(enough)"qualified_raw"else"raw_support_insufficient",values,a,
            supported,candidates,axisSamples,values.size,age)
    }

    fun sample(frame:ArDepthFrame,axis:GuideAxis,left:Double,right:Double,focal:Double):Pair<Double,Double>? {
        val col=axis is GuideAxis.Col;val extent=if(col)frame.height else frame.width
        val fixed=when(axis){is GuideAxis.Col->axis.x;is GuideAxis.Row->axis.y}
        if(frame.depthInputPolicy==LEGACY_FULL)return BoundaryAlignment.sampledDepth(extent,left,right,focal){i->
            frame.depthAt(if(col)fixed else i,if(col)i else fixed).toDouble()}
        if(!usesRaw(frame.depthInputPolicy))return null
        val profile=measurementProfile(frame,axis,left,right)
        if(!profile.qualified)return null
        if(frame.depthInputPolicy==RAW_RAY)return profile.diameterCm?.let{cm->profile.medianM?.let{cm to it}}
        return BoundaryAlignment.sampledDepth(extent,left,right,focal,profile::at)
    }
}
