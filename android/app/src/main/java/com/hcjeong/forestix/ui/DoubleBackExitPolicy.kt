package com.hcjeong.forestix.ui

/** Monotonic time only. A pause or leaving map cancels a pending exit. */
internal class DoubleBackExitPolicy(private val windowMs: Long = 2_000L) {
    private var lastPress: Long? = null
    fun shouldExit(nowMs: Long): Boolean {
        val previous = lastPress
        if (previous != null && nowMs >= previous && nowMs - previous <= windowMs) {
            reset()
            return true
        }
        lastPress = nowMs
        return false
    }
    fun reset() { lastPress = null }
}
