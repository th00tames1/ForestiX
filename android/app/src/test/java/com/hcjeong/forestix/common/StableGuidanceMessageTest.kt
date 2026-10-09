package com.hcjeong.forestix.common

import org.junit.Assert.*
import org.junit.Test

class StableGuidanceMessageTest {
    @Test fun noStemAlternatingMessagesDoNotFlicker() {
        val filter = StableGuidanceMessage("Finding stem…", 0.0)
        for (tick in 1..40) {
            val message = if (tick % 2 == 0) "Finding stem…" else "No stem found"
            assertEquals("Finding stem…", filter.update(message, tick / 8.0))
        }
    }
    @Test fun persistentFailureAndRecoveryAreEventuallyVisible() {
        val filter = StableGuidanceMessage("Finding stem…", 0.0)
        assertEquals("Finding stem…", filter.update("AI unavailable", 0.25))
        assertEquals("Finding stem…", filter.update("AI unavailable", 1.49))
        assertEquals("AI unavailable", filter.update("AI unavailable", 1.5))
        assertEquals("AI unavailable", filter.update(null, 1.6))
        assertEquals("AI unavailable", filter.update(null, 2.7))
        assertNull(filter.update(null, 3.0))
    }
    @Test fun clockResetDoesNotLeaveGuidanceStuck() {
        val filter = StableGuidanceMessage("Finding", 10.0)
        assertEquals("Error", filter.update("Error", 0.0))
    }
}
