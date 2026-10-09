package com.hcjeong.forestix.sensors

import org.junit.Assert.*
import org.junit.Test

class DepthGuideAxisTest {
    private fun frame(mapping: FloatArray?, depth: Float = 1f) = ArDepthFrame(
        width = 256, height = 192,
        depth = FloatArray(256 * 192) { depth },
        confidence = ByteArray(256 * 192) { 2 },
        fx = 100.0, fy = 100.0, cx = 128.0, cy = 96.0,
        pose = FloatArray(16) { if (it % 5 == 0) 1f else 0f },
        depthFromViewAffine = mapping,
    )
    private fun axis(mapping: FloatArray?, depth: Float = 1f) =
        DBHEstimator.screenHorizontalGuideAxis(frame(mapping, depth), 128.0, 96.0)

    @Test fun portraitUsesNativeColumnRegardlessOfDepth() {
        val mapping = floatArrayOf(0f, 0.5f, 0f, -0.5f, 0f, 192f)
        for (depth in listOf(0f, 1f, 7f)) assertEquals(GuideAxis.Col(128), axis(mapping, depth))
    }
    @Test fun landscapeUsesNativeRow() {
        assertEquals(GuideAxis.Row(96), axis(floatArrayOf(0.5f, 0f, 0f, 0f, 0.5f, 0f)))
    }
    @Test fun oppositePortraitAndCropPreserveHorizontalDirection() {
        assertEquals(GuideAxis.Col(128), axis(floatArrayOf(0f, -0.2f, 240f, 0.3f, 0f, 15f)))
    }
    @Test fun invalidMappingsFailClosed() {
        for (mapping in listOf(null, floatArrayOf(1f), FloatArray(6),
            floatArrayOf(1f, -1f, 0f, 1f, 1f, 0f),
            floatArrayOf(Float.NaN, 0f, 0f, 0f, 1f, 0f))) {
            assertNull(axis(mapping))
        }
    }
    @Test fun rotationChangesAxisAndUnmappedFramesCannotMatchCapture() {
        val portrait = axis(floatArrayOf(0f, 0.5f, 0f, -0.5f, 0f, 192f))
        val landscape = axis(floatArrayOf(0.5f, 0f, 0f, 0f, 0.5f, 0f))
        assertNotEquals(portrait, landscape)
        assertNotEquals(portrait, axis(null))
    }
}
