// Robust moving average for AR-estimated distances (Hampel-style) —
// 1:1 port of iOS Sensors/DistanceSmoother.swift; keep the maths identical.
//
// ARCore's plane hit-test distance flickers frame-to-frame — single-frame
// spikes plus low-frequency wander — where the depth-API path is stable.
// This smoother keeps a short sliding window of recent samples and
// publishes the mean of the samples that agree with the window median
// (outliers beyond max(floor, k·MAD) are excluded from the average).
// Spikes DO stay in the buffer: if a "spike" persists (the cruiser
// actually walked closer), the median follows it within a few frames, so
// genuine movement tracks while flicker never shows.
//
// Used by the AR (non-depth) distance paths only: the caliper distance
// and the live distance readout when the Depth API is unavailable.

package com.hcjeong.forestix.sensors

import android.os.SystemClock
import kotlin.math.abs

class DistanceSmoother(
    private val capacity: Int = 12,
    private val maxAgeMs: Long = 1200,
    private val madMultiplier: Double = 3.0,
    /// MAD of a genuinely stable signal can collapse to ~0, which would
    /// reject everything — this floor keeps a few cm of slack.
    private val toleranceFloorM: Double = 0.03,
) {

    private data class Sample(val value: Double, val timeMs: Long)

    private val buffer = ArrayDeque<Sample>()

    fun add(value: Double, timeMs: Long = SystemClock.elapsedRealtime()) {
        if (!value.isFinite() || value <= 0) return
        buffer.addLast(Sample(value, timeMs))
        prune(timeMs)
    }

    fun reset() = buffer.clear()

    /// Robust mean of the recent window; null when no recent samples.
    fun value(nowMs: Long = SystemClock.elapsedRealtime()): Double? {
        prune(nowMs)
        val last = buffer.lastOrNull() ?: return null
        val values = buffer.map { it.value }
        if (values.size < 3) return last.value

        val sorted = values.sorted()
        val median = sorted[sorted.size / 2]
        val deviations = values.map { abs(it - median) }.sorted()
        val mad = deviations[deviations.size / 2]
        val tolerance = maxOf(toleranceFloorM, madMultiplier * mad)
        val kept = values.filter { abs(it - median) <= tolerance }
        if (kept.isEmpty()) return median
        return kept.average()
    }

    private fun prune(nowMs: Long) {
        while (buffer.isNotEmpty() && nowMs - buffer.first().timeMs > maxAgeMs) {
            buffer.removeFirst()
        }
        while (buffer.size > capacity) buffer.removeFirst()
    }
}
