package com.hcjeong.forestix.ar

import com.google.ar.core.Coordinates2d
import com.google.ar.core.TrackingState
import com.hcjeong.forestix.sensors.DepthMotionEvidence
import com.hcjeong.forestix.sensors.DepthMotionProbe
import java.nio.ByteOrder

/** Samples only a small stem ROI. Images never escape this call, including
 * when an acquisition fails. The existing estimator/depth ingest is untouched.
 * https://developers.google.com/ar/develop/java/depth/raw-depth */
internal fun ArController.acquireDepthMotionEvidence(
    leftFraction: Float, rightFraction: Float,
): DepthMotionEvidence? {
    val current = frame ?: return null
    if (current.camera.trackingState != TrackingState.TRACKING || !supportsDepth ||
        viewWidthPx <= 1 || viewHeightPx <= 1 || !leftFraction.isFinite() ||
        !rightFraction.isFinite() || leftFraction >= rightFraction) return null
    return try {
        current.acquireRawDepthImage16Bits().use { raw ->
            current.acquireRawDepthConfidenceImage().use { confidence ->
                current.acquireDepthImage16Bits().use { full ->
                    if (raw.width != confidence.width || raw.height != confidence.height ||
                        raw.width != full.width || raw.height != full.height) return null
                    val center = (leftFraction + rightFraction) / 2
                    val halfCore = (rightFraction - leftFraction) / 4
                    val viewPoints = floatArrayOf(
                        (center - halfCore) * viewWidthPx, viewHeightPx / 2f,
                        (center + halfCore) * viewWidthPx, viewHeightPx / 2f)
                    val texture = FloatArray(4)
                    current.transformCoordinates2d(Coordinates2d.VIEW, viewPoints,
                        Coordinates2d.TEXTURE_NORMALIZED, texture)
                    val pixels = DepthMotionProbe.pixels(raw.width, raw.height,
                        texture[0] * raw.width, texture[1] * raw.height,
                        texture[2] * raw.width, texture[3] * raw.height)
                    if (pixels.isEmpty()) return null
                    val rp = raw.planes[0]
                    val cp = confidence.planes[0]
                    val fp = full.planes[0]
                    val rb = rp.buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                    val cb = cp.buffer.duplicate()
                    val fb = fp.buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                    val rawMm = ArrayList<Int>(pixels.size)
                    val fullMm = ArrayList<Int>(pixels.size)
                    val scores = ArrayList<Int>(pixels.size)
                    for ((x, y) in pixels) {
                        rawMm.add(rb.getShort(y * rp.rowStride + x * rp.pixelStride).toInt() and 0xffff)
                        fullMm.add(fb.getShort(y * fp.rowStride + x * fp.pixelStride).toInt() and 0xffff)
                        scores.add(cb.get(y * cp.rowStride + x * cp.pixelStride).toInt() and 0xff)
                    }
                    DepthMotionProbe.summarize(current.timestamp, raw.timestamp, fullMm, rawMm, scores)
                }
            }
        }
    } catch (_: Exception) {
        // Startup, tracking loss, or a frame advancing before acquisition.
        // Missing evidence must not be treated as a confident measurement.
        null
    }
}
