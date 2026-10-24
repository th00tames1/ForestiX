package com.hcjeong.forestix.data.fieldstudy

import kotlin.math.abs

/** Converts arbitrary ARCore yaw to true north using time-paired sensor evidence.
 * After pairing, AR yaw supplies low-latency rotation between compass samples.
 * No extrapolation beyond the configured evidence age, no measurement consumer.
 */
class FieldOverlayHeadingAlignment{
    var worldNorthOffsetDegrees:Double?=null;private set
    private var alignedAt:Double?=null
    fun reset(){worldNorthOffsetDegrees=null;alignedAt=null}
    fun update(worldYaw:Double?,frameTime:Double?,headingTrue:Double?,headingTime:Double?,now:Double,
        maximumAge:Double,trackingNormal:Boolean):Double?{
        if(!trackingNormal||worldYaw==null||frameTime==null||headingTrue==null||headingTime==null||
            !listOf(worldYaw,frameTime,headingTrue,headingTime,now,maximumAge).all{it.isFinite()}||
            worldYaw !in 0.0..<360.0||headingTrue !in 0.0..<360.0||maximumAge<=0||
            now-frameTime !in 0.0..0.5||now-headingTime !in 0.0..maximumAge){reset();return null}
        if(abs(frameTime-headingTime)<=.075&&(alignedAt==null||headingTime>=alignedAt!!)){
            worldNorthOffsetDegrees=FieldTargetOverlayPolicy.wrappedDegrees(worldYaw-headingTrue);alignedAt=headingTime
        }
        val stamp=alignedAt;val offset=worldNorthOffsetDegrees
        if(stamp==null||offset==null||now-stamp !in 0.0..maximumAge)return null
        return (worldYaw-offset+360.0)%360.0
    }
}
