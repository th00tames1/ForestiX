package com.hcjeong.forestix.common

import org.junit.Assert.*
import org.junit.Test

class CameraPoseSpeedGuardTest {
    @Test fun queuedWalkingFramesUseTheirOwnClock() {
        val guardrail = CameraPoseSpeedGuard()
        for (i in 0..30) assertFalse(guardrail.observe(i / 10f, 0f, 0f, 10 + i / 10.0))
    }
    @Test fun realJumpsAndExistingSpeedLimitStillApply() {
        val guardrail = CameraPoseSpeedGuard()
        assertFalse(guardrail.observe(0f, 0f, 0f, 1.0))
        assertFalse(guardrail.observe(0.4f, 0f, 0f, 1.1))
        assertTrue(guardrail.observe(1.4f, 0f, 0f, 1.2))
        assertEquals(4f, CameraPoseSpeedGuard.MAXIMUM_SPEED_MPS, 0f)
    }
    @Test fun duplicateAndOutOfOrderFramesCannotRebasePosition() {
        val guardrail = CameraPoseSpeedGuard()
        assertFalse(guardrail.observe(0f, 0f, 0f, 1.0))
        assertFalse(guardrail.observe(100f, 0f, 0f, 1.0))
        assertFalse(guardrail.observe(100f, 0f, 0f, 0.9))
        assertFalse(guardrail.observe(0.1f, 0f, 0f, 1.1))
    }
    @Test fun outagesInvalidFramesAndRetakesResetSamplePair() {
        val guardrail = CameraPoseSpeedGuard()
        assertFalse(guardrail.observe(0f, 0f, 0f, 1.0))
        assertFalse(guardrail.observe(100f, 0f, 0f, 2.0))
        assertFalse(guardrail.observe(Float.NaN, 0f, 0f, 2.1))
        assertFalse(guardrail.observe(0f, 0f, 0f, 2.2))
        guardrail.reset()
        assertFalse(guardrail.observe(100f, 0f, 0f, 2.3))
    }
}
