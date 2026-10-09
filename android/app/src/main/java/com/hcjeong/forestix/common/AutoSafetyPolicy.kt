package com.hcjeong.forestix.common

object AutoSafetyPolicy {
    const val MAXIMUM_SECONDS = 30.0
    const val TIMEOUT_MESSAGE = "Auto stopped after 30 seconds to reduce heat. Use Adjust."
    const val HEAT_MESSAGE = "Device is hot. Use Adjust and let it cool down."
    fun shouldReturnToAdjust(startedAt: Double, now: Double, isAiming: Boolean): Boolean =
        isAiming && startedAt.isFinite() && now.isFinite() && now - startedAt >= MAXIMUM_SECONDS
}
