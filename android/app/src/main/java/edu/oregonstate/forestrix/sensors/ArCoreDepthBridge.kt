package edu.oregonstate.forestrix.sensors

import android.media.Image
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.exceptions.NotYetAvailableException
import java.nio.ByteOrder

object ArCoreDepthBridge {
    internal data class ScaledIntrinsics(
        val fx: Float,
        val fy: Float,
        val cx: Float,
        val cy: Float
    )

    fun configureDepthIfAvailable(session: Session): Boolean {
        if (!session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) return false
        val config = session.config
        config.depthMode = Config.DepthMode.AUTOMATIC
        config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
        session.configure(config)
        return true
    }

    fun acquireRawDepthFrame(frame: Frame): DepthFrame? {
        return try {
            frame.acquireRawDepthImage16Bits().use { rawDepth ->
                frame.acquireRawDepthConfidenceImage().use { rawConfidence ->
                    val intrinsics = frame.camera.imageIntrinsics
                    val scaledIntrinsics = scaleIntrinsicsToDepthMap(
                        focal = intrinsics.focalLength,
                        principal = intrinsics.principalPoint,
                        imageDimensions = intrinsics.imageDimensions,
                        depthWidth = rawDepth.width,
                        depthHeight = rawDepth.height
                    )
                    DepthFrame(
                        width = rawDepth.width,
                        height = rawDepth.height,
                        depthMm = readUint16(rawDepth),
                        confidence = readUint8(rawConfidence, rawDepth.width, rawDepth.height),
                        fx = scaledIntrinsics.fx,
                        fy = scaledIntrinsics.fy,
                        cx = scaledIntrinsics.cx,
                        cy = scaledIntrinsics.cy,
                        timestampNanos = rawDepth.timestamp
                    )
                }
            }
        } catch (_: NotYetAvailableException) {
            null
        }
    }

    internal fun scaleIntrinsicsToDepthMap(
        focal: FloatArray,
        principal: FloatArray,
        imageDimensions: IntArray,
        depthWidth: Int,
        depthHeight: Int
    ): ScaledIntrinsics {
        val imageWidth = imageDimensions.getOrNull(0)?.takeIf { it > 0 } ?: depthWidth
        val imageHeight = imageDimensions.getOrNull(1)?.takeIf { it > 0 } ?: depthHeight
        val sx = depthWidth.toFloat() / imageWidth.toFloat()
        val sy = depthHeight.toFloat() / imageHeight.toFloat()
        return ScaledIntrinsics(
            fx = focal.getOrElse(0) { 0f } * sx,
            fy = focal.getOrElse(1) { focal.getOrElse(0) { 0f } } * sy,
            cx = principal.getOrElse(0) { depthWidth / 2f } * sx,
            cy = principal.getOrElse(1) { depthHeight / 2f } * sy
        )
    }

    private fun readUint16(image: Image): IntArray {
        val plane = image.planes[0]
        val buffer = plane.buffer.order(ByteOrder.LITTLE_ENDIAN)
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val out = IntArray(image.width * image.height)
        for (y in 0 until image.height) {
            val rowOffset = y * rowStride
            for (x in 0 until image.width) {
                val offset = rowOffset + x * pixelStride
                out[y * image.width + x] = buffer.getShort(offset).toInt() and 0xffff
            }
        }
        return out
    }

    private fun readUint8(image: Image, targetWidth: Int, targetHeight: Int): ByteArray {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val width = minOf(image.width, targetWidth)
        val height = minOf(image.height, targetHeight)
        val out = ByteArray(targetWidth * targetHeight)
        for (y in 0 until height) {
            val rowOffset = y * rowStride
            for (x in 0 until width) {
                val offset = rowOffset + x * pixelStride
                out[y * targetWidth + x] = buffer.get(offset)
            }
        }
        return out
    }
}
