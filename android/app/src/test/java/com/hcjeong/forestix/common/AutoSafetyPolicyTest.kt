package com.hcjeong.forestix.common

import org.junit.Assert.*
import org.junit.Test

class AutoSafetyPolicyTest {
    @Test fun deadlineIsThirtySecondsAndDefersDuringCapture() {
        assertFalse(AutoSafetyPolicy.shouldReturnToAdjust(100.0, 129.99, true))
        assertTrue(AutoSafetyPolicy.shouldReturnToAdjust(100.0, 130.0, true))
        assertFalse(AutoSafetyPolicy.shouldReturnToAdjust(100.0, 131.0, false))
        assertTrue(AutoSafetyPolicy.shouldReturnToAdjust(100.0, 132.0, true))
    }
    @Test fun freshEntryGetsItsOwnThirtySeconds() {
        assertFalse(AutoSafetyPolicy.shouldReturnToAdjust(140.0, 141.0, true))
        assertTrue(AutoSafetyPolicy.shouldReturnToAdjust(140.0, 170.0, true))
        assertFalse(AutoSafetyPolicy.shouldReturnToAdjust(Double.NaN, 170.0, true))
    }
}
