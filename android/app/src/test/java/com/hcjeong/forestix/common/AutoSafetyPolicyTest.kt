package com.hcjeong.forestix.common

import org.junit.Assert.*
import org.junit.Test

class AutoSafetyPolicyTest {
    @Test fun deadlineIsSixtySecondsAndDefersDuringCapture() {
        assertEquals(60.0, AutoSafetyPolicy.MAXIMUM_SECONDS, 0.0)
        assertFalse(AutoSafetyPolicy.shouldReturnToAdjust(100.0, 130.0, true))
        assertFalse(AutoSafetyPolicy.shouldReturnToAdjust(100.0, 159.99, true))
        assertTrue(AutoSafetyPolicy.shouldReturnToAdjust(100.0, 160.0, true))
        assertFalse(AutoSafetyPolicy.shouldReturnToAdjust(100.0, 161.0, false))
        assertTrue(AutoSafetyPolicy.shouldReturnToAdjust(100.0, 162.0, true))
    }
    @Test fun nextTreeOrFreshEntryGetsItsOwnSixtySeconds() {
        assertFalse(AutoSafetyPolicy.shouldReturnToAdjust(140.0, 141.0, true))
        assertFalse(AutoSafetyPolicy.shouldReturnToAdjust(140.0, 170.0, true))
        assertTrue(AutoSafetyPolicy.shouldReturnToAdjust(140.0, 200.0, true))
        assertFalse(AutoSafetyPolicy.shouldReturnToAdjust(Double.NaN, 200.0, true))
    }
}
