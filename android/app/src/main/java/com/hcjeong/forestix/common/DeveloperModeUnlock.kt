package com.hcjeong.forestix.common

/** Hidden map gesture: seven consecutive taps, timed with monotonic uptime. */
class DeveloperModeUnlock {
    private var count = 0
    private var lastTapSeconds: Double? = null

    fun reset() {
        count = 0
        lastTapSeconds = null
    }

    fun tap(nowSeconds: Double): Boolean {
        if (!nowSeconds.isFinite()) { reset(); return false }
        lastTapSeconds?.let {
            if (nowSeconds < it || nowSeconds - it > MAXIMUM_GAP_SECONDS) reset()
        }
        lastTapSeconds = nowSeconds
        count++
        if (count != REQUIRED_TAPS) return false
        reset()
        return true
    }

    companion object {
        const val REQUIRED_TAPS = 7
        const val MAXIMUM_GAP_SECONDS = 1.2
        fun isEnabled(savedEnabled: Boolean, gestureUnlocked: Boolean): Boolean =
            savedEnabled && gestureUnlocked
    }
}
