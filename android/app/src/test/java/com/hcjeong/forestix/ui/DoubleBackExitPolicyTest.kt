package com.hcjeong.forestix.ui

import org.junit.Assert.*
import org.junit.Test

class DoubleBackExitPolicyTest {
    @Test fun firstTapNeverExitsEvenAtZero() {
        assertFalse(DoubleBackExitPolicy().shouldExit(0))
    }
    @Test fun secondTapWithinTwoSecondsExitsAndResets() {
        val policy=DoubleBackExitPolicy()
        assertFalse(policy.shouldExit(100));assertTrue(policy.shouldExit(2100))
        assertFalse(policy.shouldExit(2200))
    }
    @Test fun lateSecondTapStartsNewWindow() {
        val policy=DoubleBackExitPolicy()
        assertFalse(policy.shouldExit(100));assertFalse(policy.shouldExit(2101))
        assertTrue(policy.shouldExit(2200))
    }
    @Test fun pauseOrLeavingMapCancelsPendingExit() {
        val policy=DoubleBackExitPolicy()
        assertFalse(policy.shouldExit(100));policy.reset()
        assertFalse(policy.shouldExit(200))
    }
    @Test fun clockRollbackNeverExits() {
        val policy=DoubleBackExitPolicy()
        assertFalse(policy.shouldExit(100));assertFalse(policy.shouldExit(99))
    }
}
