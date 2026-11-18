package com.hcjeong.forestix.common

import org.junit.Assert.*
import org.junit.Test

class DBHRangeGuidanceTest {
    @Test fun tooCloseReadingDoesNotNeedAMaskOrFit() {
        assertEquals("Move back — keep at least 0.5 m from the target.",DBHRangeGuidance.message(.3f,0.0,false))
        assertNotNull(DBHRangeGuidance.message(Math.nextDown(.5f),.5,false))
    }
    @Test fun usableRangeImmediatelyClearsWarning() {
        for(d in listOf(.5f,1f,5f)) assertNull(DBHRangeGuidance.message(d,0.0,false))
    }
    @Test fun farReadingExplainsWhyToMoveCloser() {
        assertEquals("Move closer — keep within 5 m of the target.",DBHRangeGuidance.message(Math.nextUp(5f),0.0,false))
    }
    @Test fun noDepthDoesNotInventDistance() {
        for(d in listOf(null,0f,-1f,Float.NaN,Float.POSITIVE_INFINITY)) assertNull(DBHRangeGuidance.message(d,0.0,false))
    }
    @Test fun staleOrInvalidClockCannotLeaveWarningOnScreen() {
        for(age in listOf(.501,-.01,Double.NaN,Double.POSITIVE_INFINITY)) assertNull(DBHRangeGuidance.message(.3f,age,false))
    }
    @Test fun imperialUsesSameMetricGate() {
        assertEquals("Move back — keep at least 2 ft from the target.",DBHRangeGuidance.message(.3f,0.0,true))
        assertEquals("Move closer — keep within 16 ft of the target.",DBHRangeGuidance.message(6f,0.0,true))
        assertNull(DBHRangeGuidance.message(.5f,0.0,true))
    }
    @Test fun medianIgnoresHolesWithoutClippingDistance() {
        assertEquals(.3f,DBHRangeGuidance.median(2.0,2.0,5,5) { x,y -> if (x == 2 && y == 2) 0f else .3f })
        assertNull(DBHRangeGuidance.median(2.0,2.0,5,5) { _,_ -> Float.NaN })
    }
    @Test fun invalidMappingCannotReadPixels() {
        for(x in listOf(-1.0,5.0,Double.NaN,Double.POSITIVE_INFINITY))
            assertNull(DBHRangeGuidance.median(x,2.0,5,5) { _,_ -> fail("Invalid mapping read pixels");.3f })
    }
    @Test fun imageEdgeClampsNeighbourhood() {
        var reads=0
        assertEquals(.3f,DBHRangeGuidance.median(0.0,0.0,3,3) { x,y ->
            assertTrue(x in 0..2);assertTrue(y in 0..2);reads++;.3f
        })
        assertEquals(9,reads)
    }
}
