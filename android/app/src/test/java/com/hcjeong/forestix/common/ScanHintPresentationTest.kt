package com.hcjeong.forestix.common

import org.junit.Assert.*
import org.junit.Test

class ScanHintPresentationTest {
    @Test fun introductionsDoNotReturnAfterModeOrLockChanges() {
        val hints = ScanHintPresentation()
        assertTrue(hints.begin("auto")); assertTrue(hints.isVisible("auto"))
        hints.dismiss("auto"); assertFalse(hints.begin("auto"))
        assertTrue(hints.begin("adjust")); assertFalse(hints.begin("auto"))
        assertNull(hints.visibleKey); assertEquals(4_000L, ScanHintPresentation.DURATION_MS)
    }
    @Test fun cancelledOldTimerCannotHideNewStage() {
        val hints = ScanHintPresentation()
        hints.begin("walk"); hints.begin("top"); hints.dismiss("walk")
        assertTrue(hints.isVisible("top")); assertFalse(hints.isVisible("walk"))
    }
    @Test fun inactiveStageDoesNotConsumeIntroduction() {
        val hints = ScanHintPresentation()
        assertFalse(hints.begin(null)); assertFalse(hints.isVisible(null))
        assertTrue(hints.begin("auto"))
    }
}
