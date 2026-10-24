package com.hcjeong.forestix.sensors

import java.util.WeakHashMap

/** Raw-only annotations. Weak keys do not retain depth frames or camera images.
 * ARCore frame time and Android camera sensor time must not be assumed identical. */
object RawFrameClockRegistry {
    data class Stamp(val androidCameraNanos:Long,val depthSensorNanos:Long)
    private val stamps=WeakHashMap<ArDepthFrame,Stamp>()
    @Synchronized fun register(frame:ArDepthFrame,androidCameraNanos:Long,depthSensorNanos:Long){
        stamps[frame]=Stamp(androidCameraNanos,depthSensorNanos)
    }
    @Synchronized fun stamp(frame:ArDepthFrame):Stamp?=stamps[frame]
}
