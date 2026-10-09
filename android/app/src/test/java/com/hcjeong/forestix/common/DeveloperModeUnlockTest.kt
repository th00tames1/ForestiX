package com.hcjeong.forestix.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeveloperModeUnlockTest {
    @Test fun oldPublicToggleCannotBypassNewUnlock() {
        assertFalse(DeveloperModeUnlock.isEnabled(true, false))
        assertFalse(DeveloperModeUnlock.isEnabled(false, true))
        assertTrue(DeveloperModeUnlock.isEnabled(true, true))
    }
    @Test fun unlocksOnlyOnSeventhConsecutiveTap() {
        val gesture = DeveloperModeUnlock()
        for (i in 0..5) assertFalse(gesture.tap(i * 0.2))
        assertTrue(gesture.tap(1.2))
        assertFalse(gesture.tap(1.4))
    }

    @Test fun slowTapsDoNotAccumulate() {
        val gesture = DeveloperModeUnlock()
        for (i in 0..20) assertFalse(gesture.tap(i * 1.3))
    }

    @Test fun pauseAndExplicitResetRestartSequence() {
        val gesture = DeveloperModeUnlock()
        for (i in 0..5) assertFalse(gesture.tap(i * 0.1))
        assertFalse(gesture.tap(3.0))
        gesture.reset()
        for (i in 0..5) assertFalse(gesture.tap(4 + i * 0.1))
        assertTrue(gesture.tap(4.6))
    }

    @Test fun invalidOrBackwardsTimeCannotCompleteOldSequence() {
        val gesture = DeveloperModeUnlock()
        for (i in 0..5) assertFalse(gesture.tap(10 + i * 0.1))
        assertFalse(gesture.tap(Double.NaN))
        assertFalse(gesture.tap(11.0))
        assertFalse(gesture.tap(5.0))
        for (i in 1..5) assertFalse(gesture.tap(5 + i * 0.1))
        assertTrue(gesture.tap(5.6))
    }
}
