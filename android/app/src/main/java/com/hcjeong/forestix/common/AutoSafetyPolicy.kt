package com.hcjeong.forestix.common

object AutoSafetyPolicy {
    const val MAXIMUM_SECONDS = 60.0
    const val CAPTURE_PROCESSING_MILLIS = 5_000L
    const val LIVE_ERROR_RETRY_MILLIS = 750L
    const val PAIRED_INPUT_WAIT_MILLIS = 250L
    const val PAIRED_INPUT_POLL_MILLIS = 16L
    const val LIVE_RETRY_MESSAGE = "Auto temporarily unavailable. Retrying…"
    const val CAPTURE_INPUT_MESSAGE = "Waiting for a fresh camera and depth frame."
    const val CAPTURE_CHANGED_MESSAGE = "Camera alignment or survey settings changed. Retry capture."
    const val CAPTURE_NO_STEM_MESSAGE = "No stem at the crosshair. Aim at the trunk or use Adjust."
    const val CAPTURE_INVALID_DEPTH_MESSAGE = "Couldn't read depth in the captured trunk. Retry capture."
    const val CAPTURE_INFERENCE_MESSAGE = "Auto capture failed. Retry or use Adjust."
    const val CAPTURE_TIMEOUT_MESSAGE = "AI processing timed out. Use Adjust or retry Auto."
    const val CAPTURE_BUSY_MESSAGE = "AI is still finishing. Use Adjust and retry Auto shortly."
    const val TIMEOUT_MESSAGE = "Auto stopped after 1 minute to reduce heat. Use Adjust."
    const val HEAT_MESSAGE = "Device is hot. Use Adjust and let it cool down."
    /** Missing stems are successful inferences. Only actual errors back off.
     * Keep the caller's device/thermal-specific healthy cadence unchanged. */
    fun liveInferenceDelayMillis(cadenceDelayMillis: Long, failed: Boolean): Long {
        if (cadenceDelayMillis < 0) return LIVE_ERROR_RETRY_MILLIS
        return if (failed) maxOf(cadenceDelayMillis, LIVE_ERROR_RETRY_MILLIS) else cadenceDelayMillis
    }
    fun shouldReturnToAdjust(startedAt: Double, now: Double, isAiming: Boolean): Boolean =
        isAiming && startedAt.isFinite() && now.isFinite() && now - startedAt >= MAXIMUM_SECONDS
}
