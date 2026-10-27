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
        /** Auto bracket placement only. Adjust supplies depth validation; never bridge holes/instances. */
        fun guideExtent(score: Float, contains: (Double, Double) -> Boolean): StemExtent? {
            if (!score.isFinite() || score < 0.25f) return null
            val samples = 512; val centre = 256
            if (!contains((centre + 0.5) / samples, 0.5)) return null
            var left = centre; var right = centre
            while (left > 0 && contains((left - 1 + 0.5) / samples, 0.5)) left--
            while (right + 1 < samples && contains((right + 1 + 0.5) / samples, 0.5)) right++
            val span = (right + 1 - left).toDouble() / samples
            if (left == 0 || right == samples - 1 || span !in 0.02..0.95) return null
            return StemExtent(left.toDouble() / samples, (right + 1).toDouble() / samples, score, 1)
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

/** extent is a legacy depth-validated candidate. Live Auto uses guideExtent for bounds,
 * and the Adjust estimator (not either extent alone) supplies the depth lock. */
data class LiveStemObservation(val extent: StemExtent?, val mask: LiveStemMask?, val guideExtent: StemExtent? = null) {
    companion object {
        fun validatingGuide(guide: StemExtent?, mask: LiveStemMask?, depthResult: BoundaryAlignment.Result?): LiveStemObservation {
            val supported = listOf("accepted", "below_rgb_resolution")
            val approved = mask != null && guide != null && depthResult != null &&
                depthResult.leftReason in supported && depthResult.rightReason in supported
            return LiveStemObservation(if (approved) guide else null, mask, guide)
        }
    }
}

/** Mask registration policy. Live Auto uses displayMask, then Adjust's current-depth estimator. */
object LiveStemResultPolicy {
    const val PREVIEW_MAX_AGE_SECONDS = 1.5
    data class Decision(val displayMask: Boolean, val allowMeasurement: Boolean)
    fun evaluate(age: Double, travelMeters: Double, facingDot: Double,
                 mappingCompatible: Boolean = true, upDot: Double = 1.0): Decision {
        val valid = mappingCompatible && age.isFinite() && age >= 0 &&
            travelMeters.isFinite() && travelMeters >= 0 && facingDot.isFinite()
        return Decision(
            valid && upDot.isFinite() && age <= PREVIEW_MAX_AGE_SECONDS && travelMeters < 0.05 &&
                facingDot > 0.996194698 && upDot > 0.996194698,
            valid && age <= 0.45 && travelMeters < 0.02 && facingDot > 0.9994)
    }
}

/** Mask and Auto bracket availability, not a depth/capture lock.
 * Callers invalidate it separately when the camera mapping/pose changes. */
class LiveStemMaskPreview {
    companion object {
        const val HOLD_SECONDS = 0.5
        fun validGuide(guide:StemExtent?):StemExtent? = guide?.takeIf {
            it.leftFraction.isFinite() && it.rightFraction.isFinite() && it.leftFraction >= 0 && it.rightFraction <= 1 &&
                it.widthFraction in 0.02..0.95 && it.score.isFinite() && it.score >= 0.25f
        }
    }
    var current: LiveStemMask? = null; private set
    var guideExtent: StemExtent? = null; private set
    private var lastSeen = Double.NEGATIVE_INFINITY
    private var sourceTime = Double.NEGATIVE_INFINITY
    var guideSourceTime = Double.NEGATIVE_INFINITY; private set
    private var guideSeen = Double.NEGATIVE_INFINITY
    fun reset() {
        current = null; guideExtent = null; lastSeen = Double.NEGATIVE_INFINITY; sourceTime = Double.NEGATIVE_INFINITY
        guideSeen = Double.NEGATIVE_INFINITY; guideSourceTime = Double.NEGATIVE_INFINITY
    }
    /** Exact visible span, without temporal confirmation or source-depth approval.
     * Caller still validates mask registration and runs the Adjust depth checks. */
    fun bracketForDepth(time: Double): StemExtent? {
        val guide = guideExtent ?: return null
        if (current == null || !time.isFinite() || time < guideSeen || time - guideSeen > HOLD_SECONDS ||
            time < guideSourceTime || time - guideSourceTime > LiveStemResultPolicy.PREVIEW_MAX_AGE_SECONDS ||
            validGuide(guide) == null) return null
        return guide
    }
    fun matchesMeasurement(extent: StemExtent?, time: Double): Boolean =
        current != null && guideExtent != null && extent != null && time.isFinite() && time == sourceTime && time == guideSourceTime &&
            guideExtent!!.leftFraction == extent.leftFraction && guideExtent!!.rightFraction == extent.rightFraction
    fun guidance(measurementReady: Boolean): String? = when {
        measurementReady -> null
        current == null -> "Finding stem…"
        guideExtent == null -> "Aim at the trunk centre."
        else -> "Stem found — checking depth…"
    }
    fun update(observation: LiveStemObservation?, time: Double, receivedAt: Double = time): LiveStemMask? {
        if (!time.isFinite() || !receivedAt.isFinite() || receivedAt < time || receivedAt < lastSeen ||
            time < sourceTime || receivedAt - time > LiveStemResultPolicy.PREVIEW_MAX_AGE_SECONDS) {
            reset(); return null
        }
        // Keep the source clock as a hard limit; a miss never refreshes either clock.
        observation?.mask?.let {
            current = it; lastSeen = receivedAt; sourceTime = time
            validGuide(observation.guideExtent)?.let { guide ->
                guideExtent = guide; guideSeen = receivedAt; guideSourceTime = time
            }
        }
        return expire(receivedAt)
    }
    fun expire(time: Double): LiveStemMask? {
        if (!time.isFinite() || time < lastSeen || time - lastSeen > HOLD_SECONDS ||
            time - sourceTime > LiveStemResultPolicy.PREVIEW_MAX_AGE_SECONDS) {
            reset(); return null
        }
        if (time < guideSeen || time - guideSeen > HOLD_SECONDS ||
            time - guideSourceTime > LiveStemResultPolicy.PREVIEW_MAX_AGE_SECONDS) {
            guideExtent = null; guideSeen = Double.NEGATIVE_INFINITY; guideSourceTime = Double.NEGATIVE_INFINITY
        }
        return current
    }
}

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
