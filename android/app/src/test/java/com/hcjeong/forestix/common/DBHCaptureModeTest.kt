package com.hcjeong.forestix.common

import org.junit.Assert.assertEquals
import org.junit.Test

class DBHCaptureModeTest {
    @Test fun missingAndUnknownPreferencesDefaultToSingleFrame() {
        assertEquals(DBHCaptureMode.SINGLE, DBHCaptureMode.fromRaw(null))
        assertEquals(DBHCaptureMode.SINGLE, DBHCaptureMode.fromRaw("unknown"))
        assertEquals(DBHCaptureMode.MULTI_5, DBHCaptureMode.fromRaw("multi5"))
    }

    @Test fun savedMultiFramePreferenceCannotAffectNormalUsers() {
        assertEquals(1, DBHCaptureMode.MULTI_5.frameCount(false))
        assertEquals(1, DBHCaptureMode.SINGLE.frameCount(false))
    }

    @Test fun developerModeStillDefaultsToSingleUnlessExplicitlySelected() {
        assertEquals(1, DBHCaptureMode.SINGLE.frameCount(true))
        assertEquals(5, DBHCaptureMode.MULTI_5.frameCount(true))
    }
}
