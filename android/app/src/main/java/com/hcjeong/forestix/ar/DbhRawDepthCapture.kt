package com.hcjeong.forestix.ar

import com.google.ar.core.Frame
import com.hcjeong.forestix.sensors.DbhRawDepthEvidence
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Copy real Raw Depth/confidence for DBH input qualification. The original
 * dense map stays separate, and every native Image is closed on all exits. */
internal object DbhRawDepthCapture {
    data class Result(val evidence: DbhRawDepthEvidence?, val status: String)

    fun acquire(frame: Frame, cameraTimestampNanos: Long): Result = try {
        frame.acquireRawDepthImage16Bits().use { depth ->
            frame.acquireRawDepthConfidenceImage().use { confidence ->
                require(depth.width == confidence.width && depth.height == confidence.height)
                val dp = depth.planes[0]; val cp = confidence.planes[0]
                Result(DbhRawDepthEvidence(depth.width, depth.height,
                    copyMillimeters(dp.buffer, depth.width, depth.height, dp.rowStride, dp.pixelStride),
                    copyConfidence(cp.buffer, confidence.width, confidence.height, cp.rowStride, cp.pixelStride),
                    depth.timestamp, confidence.timestamp, cameraTimestampNanos), "available")
            }
        }
    } catch (e: Exception) { Result(null, e.javaClass.simpleName) }

    private fun checked(buffer: ByteBuffer, w: Int, h: Int, row: Int, pixel: Int, bytes: Int): ByteBuffer {
        require(w in 1..4096 && h in 1..4096 && w.toLong()*h <= 4*1024*1024 && pixel >= bytes)
        require(row.toLong() >= (w-1L)*pixel + bytes)
        require((h-1L)*row + (w-1L)*pixel + bytes <= buffer.remaining().toLong())
        return buffer.slice().order(ByteOrder.LITTLE_ENDIAN)
    }
    internal fun copyMillimeters(buffer: ByteBuffer, w: Int, h: Int, row: Int, pixel: Int): ShortArray {
        val b = checked(buffer,w,h,row,pixel,2)
        return ShortArray(w*h) { i -> b.getShort((i/w)*row + (i%w)*pixel) }
    }
    internal fun copyConfidence(buffer: ByteBuffer, w: Int, h: Int, row: Int, pixel: Int): ByteArray {
        val b = checked(buffer,w,h,row,pixel,1)
        return ByteArray(w*h) { i -> b.get((i/w)*row + (i%w)*pixel) }
    }
}
