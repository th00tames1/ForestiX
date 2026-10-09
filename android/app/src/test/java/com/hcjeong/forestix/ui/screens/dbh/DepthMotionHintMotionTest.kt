package com.hcjeong.forestix.ui.screens.dbh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class DepthMotionHintMotionTest {
    @Test fun horizontalCycleMovesBothWaysWithoutVerticalDrift() {
        assertOffset(0.25f, 6f, 0f)
        assertOffset(0.75f, -6f, 0f)
    }

    @Test fun verticalCycleMovesBothWaysWithoutHorizontalDrift() {
        assertOffset(1.25f, 0f, 4f)
        assertOffset(1.75f, 0f, -4f)
    }

    @Test fun axisSwitchAndLoopRestartAreCentred() {
        for (phase in listOf(0f, 0.5f, 1f, 1.5f, 2f)) {
            assertOffset(phase, 0f, 0f)
        }
        for (phase in listOf(0.999f, 1.001f, 1.999f)) {
            val offset = depthMotionHintOffset(phase)
            assertTrue(abs(offset.xDp) < 0.001f && abs(offset.yDp) < 0.001f)
        }
    }

    @Test fun entireLoopStaysInsideTheIconFootprintAndUsesOneAxisAtATime() {
        for (step in 0..2000) {
            val offset = depthMotionHintOffset(step / 1000f)
            assertTrue(abs(offset.xDp) <= 6f)
            assertTrue(abs(offset.yDp) <= 4f)
            assertTrue(offset.xDp == 0f || offset.yDp == 0f)
        }
    }

    @Test fun outOfRangeProgressIsClampedToTheCentredEndpoints() {
        assertOffset(-1f, 0f, 0f)
        assertOffset(3f, 0f, 0f)
    }

    private fun assertOffset(phase: Float, xDp: Float, yDp: Float) {
        val offset = depthMotionHintOffset(phase)
        assertEquals(xDp, offset.xDp, 0.0001f)
        assertEquals(yDp, offset.yDp, 0.0001f)
    }
}
