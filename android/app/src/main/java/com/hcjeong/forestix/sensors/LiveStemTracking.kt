package com.hcjeong.forestix.sensors

import kotlin.math.*

/** Screen-space silhouette runs, sampled AFTER the camera/depth display transform. */
data class LiveStemMask(val width: Int, val height: Int, val runs: List<Run>) {
    data class Run(val row: Int, val start: Int, val end: Int)
    companion object {
        fun imagePoint(viewX: Double, viewY: Double, imageFromView: FloatArray,
                       cameraWidth: Int, cameraHeight: Int, quarterTurns: Int): Pair<Int, Int>? {
            if (!viewX.isFinite() || !viewY.isFinite() || imageFromView.size != 6 ||
                !imageFromView.all { it.isFinite() } || cameraWidth <= 0 || cameraHeight <= 0 || quarterTurns !in 0..3) return null
            val determinant = imageFromView[0] * imageFromView[4] - imageFromView[1] * imageFromView[3]
            if (abs(determinant) < 1e-9f) return null
            val rx = imageFromView[0] * viewX + imageFromView[1] * viewY + imageFromView[2]
            val ry = imageFromView[3] * viewX + imageFromView[4] * viewY + imageFromView[5]
            if (rx < 0 || rx >= cameraWidth || ry < 0 || ry >= cameraHeight) return null
            var x = floor(rx).toInt(); var y = floor(ry).toInt(); var w = cameraWidth; var h = cameraHeight
            repeat(quarterTurns) { val old = x; x = h - 1 - y; y = old; val temp = w; w = h; h = temp }
            return x to y
        }
        fun cameraPoint(depthX: Double, depthY: Double, depthWidth: Int, depthHeight: Int,
                        cameraWidth: Int, cameraHeight: Int, quarterTurns: Int): Pair<Int, Int>? {
            if (!depthX.isFinite() || !depthY.isFinite() || depthWidth <= 0 || depthHeight <= 0 ||
                cameraWidth <= 0 || cameraHeight <= 0 || quarterTurns !in 0..3) return null
            val scale = min(cameraWidth.toDouble() / depthWidth, cameraHeight.toDouble() / depthHeight)
            val rx = floor((depthX + 0.5) * scale + (cameraWidth - depthWidth * scale) / 2 + 0.5)
            val ry = floor((depthY + 0.5) * scale + (cameraHeight - depthHeight * scale) / 2 + 0.5)
            if (rx < 0 || rx >= cameraWidth || ry < 0 || ry >= cameraHeight) return null
            var x = rx.toInt(); var y = ry.toInt(); var w = cameraWidth; var h = cameraHeight
            repeat(quarterTurns) { val old = x; x = h - 1 - y; y = old; val temp = w; w = h; h = temp }
            return x to y
        }
        fun sample(viewWidth: Double, viewHeight: Double, contains: (Double, Double) -> Boolean): LiveStemMask? {
            if (!viewWidth.isFinite() || !viewHeight.isFinite() || viewWidth <= 1 || viewHeight <= 1) return null
            val width = 96; val height = (96 * viewHeight / viewWidth).toInt().coerceIn(48, 192)
            val runs = mutableListOf<Run>()
            for (y in 0 until height) {
                var start: Int? = null
                for (x in 0..width) {
                    val inside = x < width && contains((x + 0.5) / width, (y + 0.5) / height)
                    if (inside) { if (start == null) start = x }
                    else { start?.let { runs.add(Run(y, it, x)) }; start = null }
                }
            }
            return if (runs.isEmpty()) null else LiveStemMask(width, height, runs)
        }
        fun centreExtent(score: Float, contains: (Double, Double) -> Boolean): StemExtent? {
            if (!score.isFinite() || score < 0.25f) return null
            val samples = 512; val bounds = mutableListOf<Pair<Double, Double>>()
            for (y in listOf(0.48, 0.49, 0.5, 0.51, 0.52)) {
                val centre = samples / 2
                if (!contains((centre + 0.5) / samples, y)) return null
                var left = centre; var right = centre
                while (left > 0 && contains((left - 1 + 0.5) / samples, y)) left--
                while (right + 1 < samples && contains((right + 1 + 0.5) / samples, y)) right++
                if (left == 0 || right == samples - 1) return null
                bounds.add(left.toDouble() / samples to (right + 1).toDouble() / samples)
            }
            val lefts = bounds.map { it.first }.sorted(); val rights = bounds.map { it.second }.sorted()
            val left = lefts[2]; val right = rights[2]; val span = right - left
            val tolerance = max(0.015, span * 0.08)
            if (span < 0.02 || span > 0.95 || lefts.last() - lefts.first() > tolerance || rights.last() - rights.first() > tolerance) return null
            return StemExtent(left, right, score, 1)
        }
    }
}

data class LiveStemObservation(val extent: StemExtent?, val mask: LiveStemMask?)

/** Must stay byte-for-byte equivalent in behaviour to the Swift temporal policy. */
class LiveStemTracker {
    companion object { const val HOLD_SECONDS = 0.6 }
    var current: StemExtent? = null; private set
    private var pending: StemExtent? = null
    var lastGood = Double.NEGATIVE_INFINITY; private set
    private var pendingTime = Double.NEGATIVE_INFINITY
    fun reset() { current = null; pending = null; lastGood = Double.NEGATIVE_INFINITY; pendingTime = Double.NEGATIVE_INFINITY }
    fun isFresh(time: Double) = current != null && time.isFinite() && time >= lastGood && time - lastGood <= HOLD_SECONDS
    fun update(candidate: StemExtent?, time: Double): StemExtent? {
        if (!time.isFinite()) { reset(); return null }
        if (!isFresh(time)) current = null
        if (time < pendingTime || time - pendingTime > HOLD_SECONDS) pending = null
        if (candidate == null || !candidate.leftFraction.isFinite() || !candidate.rightFraction.isFinite() ||
            candidate.leftFraction < 0 || candidate.rightFraction > 1 || candidate.widthFraction !in 0.02..0.95 ||
            !candidate.score.isFinite() || candidate.score < 0.25f) {
            pending = null; return current
        }
        fun close(a: StemExtent, b: StemExtent): Boolean {
            val limit = max(0.025, a.widthFraction * 0.15)
            return abs(a.leftFraction - b.leftFraction) <= limit && abs(a.rightFraction - b.rightFraction) <= limit
        }
        val old = current
        if (old != null && close(old, candidate)) {
            current = StemExtent(old.leftFraction + 0.45 * (candidate.leftFraction - old.leftFraction),
                old.rightFraction + 0.45 * (candidate.rightFraction - old.rightFraction), candidate.score, candidate.maskPixels)
        } else {
            val prior = pending
            if (prior == null || !close(prior, candidate)) { pending = candidate; pendingTime = time; return current }
            current = candidate
        }
        pending = null; lastGood = time
        return current
    }
}
