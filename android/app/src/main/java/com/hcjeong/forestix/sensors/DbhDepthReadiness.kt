package com.hcjeong.forestix.sensors

import kotlin.math.*

/** Metadata-only admission of a SINGLE DBH frame. Never averages depths or
 * retains images. ARCore information timestamps advance only for new Raw
 * information; current reprojections are not independent stability votes.
 * Pose compensation prevents ordinary hand motion from becoming a veto.
 * These are application heuristics, not a certificate of absolute range. */
class DbhDepthReadiness {
    companion object {
        const val POLICY = "raw_update_pose_stability_v1"
        const val WAIT_MESSAGE = "Depth is updating. Keep the trunk in view, then retry."
        const val MIN_STABLE_SPAN_NANOS = 100_000_000L
        const val MAX_OBSERVATION_GAP_NANOS = 2_000_000_000L
    }
    data class Assessment(val status:String, val ready:Boolean, val newInformation:Boolean=false,
        val independentSamples:Int=0, val residualM:Double?=null,
        val informationNanos:Long?=null, val cameraNanos:Long?=null,
        val referenceInformationNanos:Long?=null,val referenceCameraNanos:Long?=null,
        val expectedDepthM:Double?=null) {
        fun diagnostics():Map<String,String> = buildMap {
            put("readiness_policy",POLICY);put("readiness_status",status)
            put("readiness_depth_basis","silhouette_center_camera_z")
            put("readiness_ready",ready.toString());put("readiness_new_information",newInformation.toString())
            put("readiness_independent_samples",independentSamples.toString())
            residualM?.let{put("readiness_pose_residual_m",it.toString())}
            informationNanos?.let{put("readiness_information_ns",it.toString())}
            cameraNanos?.let{put("readiness_camera_ns",it.toString())}
            referenceInformationNanos?.let{put("readiness_reference_information_ns",it.toString())}
            referenceCameraNanos?.let{put("readiness_reference_camera_ns",it.toString())}
            expectedDepthM?.let{put("readiness_expected_depth_m",it.toString())}
        }
    }
    private data class Observation(val camera:Long,val information:Long,val z:Double,
        val world:DoubleArray,val pose:FloatArray,val col:Boolean,val width:Int,val height:Int,
        val focal:Double,val principal:Double,val orthFocal:Double,val orthPrincipal:Double,
        val left:Double,val right:Double,val fixed:Double,val mapping:List<Float>?)
    private var anchor:Observation?=null
    private var lastCamera=0L
    private var lastInformation=0L
    private var lastIndependentCamera=0L
    private var samples=0
    private var ready=false

    @Synchronized fun reset(){anchor=null;lastCamera=0;lastInformation=0;lastIndependentCamera=0;samples=0;ready=false}

    /** Fractions are in the frame's native GUIDE axis, never screen width. */
    @Synchronized fun observe(frame:ArDepthFrame,axis:GuideAxis,leftFraction:Double,rightFraction:Double):Assessment {
        // Historical replay and developer legacy inputs retain their original
        // estimator semantics. The controller supplies RAW_RAY for live DBH.
        if(!DbhMeasurementDepth.recognized(frame.depthInputPolicy))return Assessment("unsupported_input_policy",false)
        if(frame.depthInputPolicy!=DbhMeasurementDepth.RAW_RAY)
            return Assessment("not_applicable",true)
        val raw=frame.rawDepthEvidence?:return Assessment("raw_unavailable",false)
        fun verdict(status:String,ok:Boolean=false,new:Boolean=false,residual:Double?=null,expected:Double?=null)=
            Assessment(status,ok,new,samples,residual,raw.timestampNanos,raw.cameraTimestampNanos,
                anchor?.information,anchor?.camera,expected)
        if(!DbhDepthInputPolicy.acceptsFrame(frame)||!DbhMeasurementDepth.currentProjection(raw))
            return verdict("projection_unavailable")
        if(raw.cameraTimestampNanos<lastCamera)return verdict("out_of_order")
        val col=axis is GuideAxis.Col;val extent=if(col)frame.height else frame.width
        val left=leftFraction*extent;val right=rightFraction*extent
        val profile=DbhMeasurementDepth.measurementProfile(frame,axis,left,right)
        val surfaceZ=profile.medianM
        if(!profile.qualified||surfaceZ==null||!DbhDepthInputPolicy.acceptsDistance(surfaceZ))return verdict("spatial_support_unavailable")
        val focal=if(col)frame.fy else frame.fx;val principal=if(col)frame.cy else frame.cx
        val of=if(col)frame.fx else frame.fy;val op=if(col)frame.cx else frame.cy
        val fixed=when(axis){is GuideAxis.Row->axis.y.toDouble();is GuideAxis.Col->axis.x.toDouble()}
        val pose=frame.pose
        if(pose.size!=16||pose.any{!it.isFinite()}||!validRotation(pose)||of<=1||!of.isFinite())return verdict("pose_unavailable")
        // Compare the same silhouette-centre range, not medians drawn from
        // different parts of a curved surface. Sparse edge-heavy Raw support
        // naturally lies deeper than centre-heavy support even at one range.
        // This uses the existing per-ray geometry, never a temporal radius fit
        // or a change to the measured DBH. It inherits its circular assumption.
        val radius=profile.diameterCm?.div(200)?:return verdict("spatial_support_unavailable")
        val al=atan((left-principal)/focal);val ar=atan((right-principal)/focal)
        val middle=(al+ar)/2;val centerRange=radius/sin((ar-al)/2)
        val z=centerRange*cos(middle);val alongCenter=centerRange*sin(middle)
        val orthCenter=(fixed-op)/of*z
        val camera=if(col)doubleArrayOf(orthCenter,alongCenter,z)else doubleArrayOf(alongCenter,orthCenter,z)
        if(!camera.all{it.isFinite()}||z<=0)return verdict("spatial_support_unavailable")
        val world=DoubleArray(3){r->pose[r]*camera[0]+pose[4+r]*camera[1]+pose[8+r]*camera[2]+pose[12+r]}
        val current=Observation(raw.cameraTimestampNanos,raw.timestampNanos,z,world,pose.copyOf(),col,frame.width,frame.height,
            focal,principal,of,op,left,right,fixed,frame.depthFromViewAffine?.toList())
        val previous=anchor
        fun seed(status:String,new:Boolean=false,residual:Double?=null,expected:Double?=null):Assessment {
            // Preserve the reference that explained a rejection BEFORE the
            // new candidate becomes the next window's anchor.
            val event=verdict(status,new=new,residual=residual,expected=expected)
            anchor=current;lastCamera=current.camera;lastInformation=current.information
            lastIndependentCamera=current.camera;samples=1;ready=false
            return event.copy(independentSamples=1)
        }
        if(previous==null)return seed("waiting_new_information")
        if(current.camera-lastCamera>MAX_OBSERVATION_GAP_NANOS||current.information<lastInformation)
            return seed("waiting_new_information")
        if(current.col!=previous.col||current.width!=previous.width||current.height!=previous.height||
            current.mapping!=previous.mapping||abs(current.focal/previous.focal-1)>.01)
            return seed("target_changed")
        val delta=DoubleArray(3){previous.world[it]-pose[12+it]}
        val predicted=DoubleArray(3){c->(0..2).sumOf{r->pose[c*4+r]*delta[r]}}
        if(predicted[2]<=0||!predicted.all{it.isFinite()})return seed("target_changed")
        val along=current.focal*predicted[if(col)1 else 0]/predicted[2]+current.principal
        val orth=current.orthFocal*predicted[if(col)0 else 1]/predicted[2]+current.orthPrincipal
        val span=right-left
        // Associate a local stem neighbourhood, not an exact image pixel.
        // A few cm of up/down/sideways motion is normal while collecting parallax.
        if(along<left-span*.5||along>right+span*.5||abs(orth-fixed)>max(8.0,span*of/focal))
            return seed("target_changed")
        val residual=abs(z-predicted[2])
        val tolerance=max(.025,.04*z)
        val newInformation=current.information>lastInformation
        if(residual>tolerance)return seed("depth_changed",newInformation,residual,predicted[2])
        if(newInformation&&current.camera<=lastCamera)return verdict("duplicate_frame")
        if(newInformation){samples=(samples+1).coerceAtMost(1_000_000);lastInformation=current.information;lastIndependentCamera=current.camera}
        lastCamera=current.camera
        if(samples>=2&&lastIndependentCamera-previous.camera>=MIN_STABLE_SPAN_NANOS)ready=true
        return verdict(if(ready)if(newInformation)"ready_new_information"else"ready_reprojected"
            else"waiting_new_information",ready,newInformation,residual,predicted[2])
    }
    private fun validRotation(p:FloatArray):Boolean = (0..2).all{c->
        abs((0..2).sumOf{r->p[c*4+r].toDouble().pow(2)}-1)<.02 &&
            (0 until c).all{d->abs((0..2).sumOf{r->p[c*4+r].toDouble()*p[d*4+r]})<.02}}
}
