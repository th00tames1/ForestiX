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
}
