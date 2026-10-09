package com.hcjeong.forestix.sensors

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/** Read-only evidence for the movement hint, NOT estimator confidence or an
 * absolute-distance calibration. Full and raw depth share ARCore's errors.
 * Thresholds below are UI heuristics that still need device/field validation. */
internal data class DepthMotionEvidence(
    val frameTimestampNs: Long,
    val rawTimestampNs: Long,
    val depthM: Float,
    val sampledPixels: Int,
    val confidentPixels: Int,
    val rawSpreadM: Float,
    val disagreementM: Float,
) {
    val hasReliableRawDepth: Boolean get() = frameTimestampNs > 0 && rawTimestampNs > 0 &&
        depthM.isFinite() && depthM > 0 && sampledPixels >= 3 &&
        confidentPixels >= 3 && confidentPixels.toFloat() / sampledPixels >= 0.2f &&
        rawSpreadM.isFinite() && rawSpreadM in 0f..max(0.08f, depthM * 0.06f) &&
        disagreementM.isFinite() && disagreementM >= 0f

    val agreesWithRawDepth: Boolean get() = hasReliableRawDepth &&
        disagreementM <= max(0.08f, depthM * 0.08f)
}

internal object DepthMotionProbe {
    // Real ARCore confidence is unsigned 0..255, unlike the estimator's
    // synthetic 0/2 valid-depth mask. Never substitute that mask here.
    const val MIN_RAW_CONFIDENCE = 160

    /** A five-pixel-wide strip along the mapped inner stem bracket. Handles
     * portrait/landscape rotation; duplicated/clipped pixels count only once. */
    fun pixels(width: Int, height: Int, x0: Float, y0: Float,
               x1: Float, y1: Float): List<Pair<Int, Int>> {
        if (width <= 0 || height <= 0 ||
            !listOf(x0, y0, x1, y1).all { it.isFinite() } ||
            x0 !in 0f..(width - 1).toFloat() || x1 !in 0f..(width - 1).toFloat() ||
            y0 !in 0f..(height - 1).toFloat() || y1 !in 0f..(height - 1).toFloat()) return emptyList()
        val dx = x1 - x0
        val dy = y1 - y0
        val horizontal = abs(dx) >= abs(dy)
        val steps = ceil(max(abs(dx), abs(dy))).toInt().coerceIn(1, 128)
        val pixels = LinkedHashSet<Pair<Int, Int>>()
        for (i in 0..steps) {
            val x = (x0 + dx * i / steps).roundToInt()
            val y = (y0 + dy * i / steps).roundToInt()
            for (offset in -2..2) {
                val px = x + if (horizontal) 0 else offset
                val py = y + if (horizontal) offset else 0
                if (px in 0 until width && py in 0 until height) pixels.add(px to py)
            }
        }
        return pixels.toList()
    }

    fun summarize(frameTimestampNs: Long, rawTimestampNs: Long,
                  fullMm: List<Int>, rawMm: List<Int>, confidence: List<Int>): DepthMotionEvidence? {
        if (fullMm.isEmpty() || fullMm.size != rawMm.size || fullMm.size != confidence.size) return null
        val full = fullMm.filter { it in 1..8000 }.sorted()
        if (full.size < 3) return null
        val raw = ArrayList<Float>()
        val differences = ArrayList<Float>()
        for (i in fullMm.indices) {
            if (rawMm[i] in 1..8000 && fullMm[i] in 1..8000 &&
                confidence[i] in MIN_RAW_CONFIDENCE..255) {
                raw.add(rawMm[i] / 1000f)
                differences.add(abs(rawMm[i] - fullMm[i]) / 1000f)
            }
        }
        raw.sort()
        differences.sort()
        return DepthMotionEvidence(frameTimestampNs, rawTimestampNs,
            full[full.size / 2] / 1000f, fullMm.size, raw.size,
            if (raw.isEmpty()) Float.POSITIVE_INFINITY else raw[raw.size * 3 / 4] - raw[raw.size / 4],
            if (differences.isEmpty()) Float.POSITIVE_INFINITY else differences[differences.size / 2])
    }
}

/** Positive-evidence advisory, NOT a readiness gate. Missing/sparse/reused
 * raw data cannot prove that movement is needed. Show only after repeated
 * fresh, reliable raw/full disagreement; agreement can recover while moving.
 * Hiding the hint makes no assertion that the absolute distance is correct. */
internal class DepthMotionGuidance {
    private var lastFrameNs = 0L
    private var lastRawNs = 0L
    private var lastFreshAtMs: Long? = null
    private var badSinceMs: Long? = null
    private var badCount = 0
    private var goodSinceMs: Long? = null
    private var goodCount = 0
    var needsMotion = false
        private set

    fun update(evidence: DepthMotionEvidence?, nowMs: Long): Boolean {
        if (evidence == null || !evidence.hasReliableRawDepth) return clearAdvice()
        if (evidence.frameTimestampNs < lastFrameNs || evidence.rawTimestampNs < lastRawNs) {
            clearAdvice()
            lastFreshAtMs = null
            lastFrameNs = 0
            lastRawNs = 0
        }
        val fresh = evidence.frameTimestampNs > lastFrameNs && evidence.rawTimestampNs > lastRawNs
        if (!fresh) {
            if (lastFreshAtMs == null || nowMs - lastFreshAtMs!! >= STALE_MS) {
                return clearAdvice()
            }
            // Reprojection is normal. It cannot add evidence for either
            // showing or clearing a hint, nor keep stale advice indefinitely.
            return needsMotion
        }
        if (lastFreshAtMs?.let { nowMs - it >= STALE_MS } == true) clearAdvice()
        lastFrameNs = evidence.frameTimestampNs
        lastRawNs = evidence.rawTimestampNs
        lastFreshAtMs = nowMs
        if (evidence.agreesWithRawDepth) {
            badSinceMs = null
            badCount = 0
            val since = goodSinceMs ?: nowMs.also { goodSinceMs = it }
            goodCount += 1
            if (goodCount >= 4 && nowMs - since >= SETTLE_MS) needsMotion = false
        } else {
            goodSinceMs = null
            goodCount = 0
            val since = badSinceMs ?: nowMs.also { badSinceMs = it }
            badCount += 1
            if (badCount >= 4 && nowMs - since >= SHOW_DELAY_MS) needsMotion = true
        }
        return needsMotion
    }

    private fun clearAdvice(): Boolean {
        badSinceMs = null
        goodSinceMs = null
        badCount = 0
        goodCount = 0
        needsMotion = false
        return false
    }

    companion object {
        const val SHOW_DELAY_MS = 900L
        const val SETTLE_MS = 600L
        const val STALE_MS = 1500L
    }
}
