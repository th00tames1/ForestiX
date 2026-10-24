package com.hcjeong.forestix.ui.screens.dbh

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DepthMotionHintPolicyTest {
    @Test fun stalledAcquisitionNeedsNoRawConfidenceEvidence() {
        assertTrue(shouldShowDepthMotionHint(true, true, false, false, false, false))
    }
    @Test fun initialAcquisitionDoesNotFlashAdvice() {
        assertFalse(shouldShowDepthMotionHint(true, false, false, false, false, false))
    }
    @Test fun recoveredLockHidesAdviceEvenBeforeStallFlagUpdates() {
        assertFalse(shouldShowDepthMotionHint(true, true, false, true, false, false))
    }
    @Test fun stoppedStreamOverridesHeldLockAndStaleEdgeError() {
        assertTrue(shouldShowDepthMotionHint(true, true, true, true, true, false))
    }
    @Test fun inactiveModesNeverShowAdvice() {
        assertFalse(shouldShowDepthMotionHint(false, true, true, false, false, false))
    }
    @Test fun liveSpecificErrorTakesPrecedence() {
        assertFalse(shouldShowDepthMotionHint(true, true, false, false, true, false))
    }
    @Test fun captureFailureTakesPrecedence() {
        assertFalse(shouldShowDepthMotionHint(true, true, true, false, false, true))
    }
    @Test fun firstGoodDepthImmediatelyHidesAndLatchesHint() {
        var acquired = false
        assertTrue(shouldShowDepthMotionHint(true, true, false, false, false, false, acquired))
        acquired = hasAcquiredInitialDepth(acquired, true, true, false)
        assertTrue(acquired)
        assertFalse(shouldShowDepthMotionHint(true, true, false, true, false, false, acquired))
        // Losing a tree/lock, a silent stream, retake or a mode switch cannot reopen it.
        for (silent in listOf(false, true)) {
            acquired = hasAcquiredInitialDepth(acquired, false, false, silent)
            acquired = hasAcquiredInitialDepth(acquired, true, false, silent)
            assertFalse(shouldShowDepthMotionHint(true, true, silent, false, false, false, acquired))
        }
    }
    @Test fun silentHeldLockAndInactiveScreenCannotFinishInitialAcquisition() {
        assertFalse(hasAcquiredInitialDepth(false, true, true, true))
        assertFalse(hasAcquiredInitialDepth(false, false, true, false))
        assertFalse(hasAcquiredInitialDepth(false, true, false, false))
    }
    @Test fun aNewScreenStartsWithFreshInitialAcquisition() {
        val previousScreen = hasAcquiredInitialDepth(false, true, true, false)
        assertFalse(shouldShowDepthMotionHint(true, true, false, false, false, false, previousScreen))
        val newScreen = hasAcquiredInitialDepth(false, true, false, false)
        assertTrue(shouldShowDepthMotionHint(true, true, false, false, false, false, newScreen))
    }
}
