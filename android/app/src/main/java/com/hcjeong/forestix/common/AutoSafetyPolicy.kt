package com.hcjeong.forestix.common

object AutoSafetyPolicy {
    const val MAXIMUM_SECONDS = 60.0
    const val CAPTURE_PROCESSING_MILLIS = 5_000L
    const val TIMEOUT_MESSAGE = "Auto stopped after 1 minute to reduce heat. Use Adjust."
    const val HEAT_MESSAGE = "Device is hot. Use Adjust and let it cool down."
    fun shouldReturnToAdjust(startedAt: Double, now: Double, isAiming: Boolean): Boolean =
        isAiming && startedAt.isFinite() && now.isFinite() && now - startedAt >= MAXIMUM_SECONDS
}
