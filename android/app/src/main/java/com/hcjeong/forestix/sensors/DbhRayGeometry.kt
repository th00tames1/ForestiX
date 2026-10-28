package com.hcjeong.forestix.sensors

import kotlin.math.*

/** Circular silhouette inversion at an actual pixel ray. Depth is camera-z,
 * not ray length. No sensor-bias correction or reference diameter is used. */
object DbhRayGeometry {
    fun diameterCm(left:Double,right:Double,pixel:Double,z:Double,focal:Double,principal:Double):Double? {
        if(!listOf(left,right,pixel,z,focal,principal).all{it.isFinite()}||right<=left||focal<=1||z<=0||pixel !in left..right)return null
        val al=atan((left-principal)/focal);val ar=atan((right-principal)/focal)
        val half=(ar-al)/2;val middle=(ar+al)/2;val s=sin(half)
        if(s<=0||s>=1)return null
        val t=(pixel-principal)/focal;val aa=1+t*t
        val bb=(t*sin(middle)+cos(middle))/s;val cc=1/(s*s)-1
        val disc=bb*bb-aa*cc
        val epsilon=64*Math.ulp(1.0)*max(bb*bb,aa*cc)
        if(disc < -epsilon||cc<=0)return null
        return (200*z*(bb+sqrt(max(0.0,disc)))/cc).takeIf{it.isFinite()&&it>0}
    }
}
