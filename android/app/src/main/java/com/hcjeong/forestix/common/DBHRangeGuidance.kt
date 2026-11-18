package com.hcjeong.forestix.common

/** Presentation only; never supplies a depth fallback or changes a capture gate. */
object DBHRangeGuidance {
    const val MAXIMUM_AGE_SECONDS = 0.5

    fun message(distanceMeters: Float?, ageSeconds: Double, imperial: Boolean): String? {
        val d = distanceMeters
        if (!ageSeconds.isFinite() || ageSeconds < 0 || ageSeconds > MAXIMUM_AGE_SECONDS ||
            d == null || !d.isFinite() || d <= 0f) return null
        if (d < .5f) return MeasurementTargetRange.tooCloseMessage(imperial)
        if (d > 5f) return "Move closer — keep within ${if (imperial) "16 ft" else "5 m"} of the target."
        return null
    }

    /** Unfiltered 5x5 centre depth; holes are unknown, not a near-target verdict. */
    fun median(centerX: Double, centerY: Double, width: Int, height: Int,
               depthAt: (Int, Int) -> Float): Float? {
        if (!centerX.isFinite() || !centerY.isFinite() || centerX < 0 || centerY < 0 ||
            centerX >= width || centerY >= height) return null
        val cx = Math.round(centerX).toInt(); val cy = Math.round(centerY).toInt()
        if (cx >= width || cy >= height) return null
        val values = ArrayList<Float>(25)
        for (y in maxOf(0, cy-2)..minOf(height-1, cy+2)) {
            for (x in maxOf(0, cx-2)..minOf(width-1, cx+2)) {
                val d = depthAt(x,y)
                if (d.isFinite() && d > 0f) values.add(d)
            }
        }
        if (values.isEmpty()) return null
        values.sort()
        return values[values.size/2]
    }
}
