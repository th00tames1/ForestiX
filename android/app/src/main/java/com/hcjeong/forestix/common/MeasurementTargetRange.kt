package com.hcjeong.forestix.common

import kotlin.math.sqrt

/** Live AR placement admission only. No estimator or historical replay changes. */
object MeasurementTargetRange {
    const val MINIMUM_METERS = 0.5f
    enum class Rejection { UNAVAILABLE, TOO_CLOSE, TOO_FAR }
    fun rejection(distanceMeters: Float?, maximumMeters: Float? = null): Rejection? {
        val d = distanceMeters ?: return Rejection.UNAVAILABLE
        if (!d.isFinite() || d < 0f) return Rejection.UNAVAILABLE
        if (maximumMeters != null && (!maximumMeters.isFinite() || maximumMeters < MINIMUM_METERS)) return Rejection.UNAVAILABLE
        if (d < MINIMUM_METERS) return Rejection.TOO_CLOSE
        if (maximumMeters != null && d > maximumMeters) return Rejection.TOO_FAR
        return null
    }
    fun distance(cx: Float, cy: Float, cz: Float, x: Float, y: Float, z: Float): Float {
        val dx=x-cx; val dy=y-cy; val dz=z-cz
        return sqrt(dx*dx+dy*dy+dz*dz)
    }
    fun tooCloseMessage(imperial: Boolean): String =
        "Move back — keep at least ${if (imperial) "2 ft" else "0.5 m"} from the target."
}
