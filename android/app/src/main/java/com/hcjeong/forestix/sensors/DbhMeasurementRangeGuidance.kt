package com.hcjeong.forestix.sensors

import com.hcjeong.forestix.common.DBHRangeGuidance

/** Presentation only. Reuses the estimator's selected distance; never samples
 * the dense map, estimates a diameter, or changes measurement admission. */
class DbhMeasurementRangeGuidance {
    private var frameTimestamp: Long? = null
    private var sampledAt = Double.NEGATIVE_INFINITY
    private var distanceM: Float? = null

    fun reset() {
        frameTimestamp = null; sampledAt = Double.NEGATIVE_INFINITY; distanceM = null
    }

    fun observe(frame: ArDepthFrame, estimate: DBHEstimator.DbhPreview?, nowSeconds: Double,
                bracketed: Boolean) {
        if (!nowSeconds.isFinite() || !DbhDepthInputPolicy.acceptsFrame(frame)) { reset(); return }
        if (frameTimestamp != frame.frameTimestampNanos) {
            frameTimestamp = frame.frameTimestampNanos
            // Raw information can be old but reprojected to the current view.
            // Match the measurement's projection/confidence clock, not the
            // original Raw information time. Repeated frames never renew TTL.
            val raw = frame.rawDepthEvidence
            val projectionAge = if (DbhMeasurementDepth.usesRaw(frame.depthInputPolicy) && raw != null)
                (raw.cameraTimestampNanos - raw.confidenceTimestampNanos).coerceAtLeast(0L) else 0L
            sampledAt = nowSeconds - maxOf(frame.depthAgeNanos,projectionAge)/1_000_000_000.0
        }
        // constrainedEstimate reports the selected depth even outside the
        // distance window. Legacy edge-walk misses instead return a dense tap
        // seed: only an actual locked fit proves its distance was measured.
        distanceM = estimate?.takeIf { (bracketed || it.locked) && it.depthRejection == null }
            ?.distanceM?.takeIf { it.isFinite() && it > 0f }
    }

    fun message(nowSeconds: Double, imperial: Boolean): String? =
        DBHRangeGuidance.message(distanceM,nowSeconds-sampledAt,imperial)
}
