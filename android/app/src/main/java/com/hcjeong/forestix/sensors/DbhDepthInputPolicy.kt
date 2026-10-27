package com.hcjeong.forestix.sensors

/** Android DBH input admission, separate from geometry and other AR tools. */
object DbhDepthInputPolicy {
    const val MIN_DISTANCE_M = 0.5
    const val MAX_DISTANCE_M = 5.0
    val distanceRangeM = MIN_DISTANCE_M.toFloat()..MAX_DISTANCE_M.toFloat()

    // Application safety limits, not manufacturer accuracy guarantees.
    const val MAX_DEPTH_AGE_NANOS = 500_000_000L
    // ARCore's Android camera clock can precede the depth-image clock slightly.
    // Preserve the sign here; clamping every future timestamp to zero masks errors.
    const val MAX_FUTURE_SKEW_NANOS = 50_000_000L

    fun acceptsDistance(depthM: Double): Boolean =
        depthM.isFinite() && depthM in MIN_DISTANCE_M..MAX_DISTANCE_M

    fun acceptsSensorTimes(cameraNanos: Long, depthNanos: Long): Boolean {
        if (cameraNanos <= 0L || depthNanos <= 0L) return false
        return cameraNanos - depthNanos in -MAX_FUTURE_SKEW_NANOS..MAX_DEPTH_AGE_NANOS
    }

    fun acceptsArrival(receivedNanos: Long, nowNanos: Long): Boolean =
        receivedNanos > 0L && nowNanos >= receivedNanos &&
            nowNanos - receivedNanos <= MAX_DEPTH_AGE_NANOS

    // Age is relative to the camera AT ACQUISITION, not the time inference
    // finishes. A deliberately frozen shutter input may take seconds to process.
    // Legacy/synthetic frames retain age=0; live acquisition requires valid
    // sensor timestamps and a recent frame arrival before constructing a frame.
    fun acceptsFrame(frame: ArDepthFrame): Boolean =
        DbhMeasurementDepth.recognized(frame.depthInputPolicy) &&
        frame.depthAgeNanos in 0L..MAX_DEPTH_AGE_NANOS

    // A developer burst cannot obtain a result by substituting legacy dense
    // frames for missing Raw evidence in the same capture window.
    fun acceptsWindow(frames:List<ArDepthFrame>):Boolean = frames.isNotEmpty() &&
        frames.map{it.depthInputPolicy}.distinct().size==1 && frames.all(::acceptsFrame)
}
