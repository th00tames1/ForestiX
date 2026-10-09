package com.hcjeong.forestix.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** Application regressions using an artificial constant-depth grid only. */
class BoundaryAlignmentSyntheticTests {
    private fun grid() = BoundaryAlignment.Grid(
        width = 100,
        height = 100,
        row = 50,
        depth = DoubleArray(10_000) { 1.0 },
        left = 30.0,
        right = 70.0,
        focal = 100.0,
    )

    @Test
    fun unsupportedMaskPreservesManualPlacement() {
        val result = BoundaryAlignment.correct(
            grid(), 4.0, { _, _ -> false }, { listOf(28.0 to 72.0) },
        )
        assertNotNull(result)
        result!!
        assertEquals(30.0, result.left, 0.0)
        assertEquals(70.0, result.right, 0.0)
        assertEquals("mask_core_gap", result.leftReason)
    }

    @Test
    fun partialAdjustmentIsAppliedOnceFromOriginalGuides() {
        val input = grid()
        val result = BoundaryAlignment.correct(
            input, 4.0, { _, _ -> true }, { listOf(28.0 to 72.0) },
        )
        assertNotNull(result)
        result!!
        assertEquals(29.0, result.left, 1e-10)
        assertEquals(71.0, result.right, 1e-10)
        assertEquals(
            BoundaryAlignment.diameter(input, 29.0, 71.0)!!.first,
            result.diameterCm,
            1e-10,
        )
    }

    @Test
    fun nearForegroundRetainsBothSides() {
        val input = grid()
        for (y in 48..52) {
            for (x in 40..60) input.depth[y * input.width + x] = 0.5
        }
        val result = BoundaryAlignment.correct(
            input, 4.0, { _, _ -> true }, { listOf(28.0 to 72.0) },
        )
        assertNotNull(result)
        result!!
        assertEquals("depth_occlusion", result.leftReason)
        assertEquals(30.0, result.left, 0.0)
        assertEquals(70.0, result.right, 0.0)
    }
}
