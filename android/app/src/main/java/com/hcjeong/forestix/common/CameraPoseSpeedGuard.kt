package com.hcjeong.forestix.common

import kotlin.math.sqrt

/** Frame clock, not UI poll clock. The existing 4 m/s limit stays unchanged. */
class CameraPoseSpeedGuard {
    companion object { const val MAXIMUM_SPEED_MPS = 4f }
    private var timestamp: Double? = null
    private var px = 0f; private var py = 0f; private var pz = 0f
    fun reset() { timestamp = null }
    fun observe(x: Float, y: Float, z: Float, frameSeconds: Double): Boolean {
        if (!frameSeconds.isFinite() || !x.isFinite() || !y.isFinite() || !z.isFinite()) {
            reset(); return false
        }
        val old = timestamp
        if (old == null) { timestamp = frameSeconds; px = x; py = y; pz = z; return false }
        val dt = frameSeconds - old
        if (dt < 0.01) return false
        val dx = x - px; val dy = y - py; val dz = z - pz
        timestamp = frameSeconds; px = x; py = y; pz = z
        if (dt > 0.5) return false
        return sqrt(dx * dx + dy * dy + dz * dz) / dt.toFloat() > MAXIMUM_SPEED_MPS
    }
}
