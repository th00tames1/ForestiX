package com.hcjeong.forestix.common

import org.junit.Assert.*
import org.junit.Test

class MeasurementTargetRangeTest {
    @Test fun minimumIsInclusiveAndSmallerValuesAreRejected() {
        assertEquals(MeasurementTargetRange.Rejection.TOO_CLOSE,MeasurementTargetRange.rejection(Math.nextDown(.5f)))
        assertEquals(MeasurementTargetRange.Rejection.TOO_CLOSE,MeasurementTargetRange.rejection(.499f))
        assertNull(MeasurementTargetRange.rejection(.5f));assertNull(MeasurementTargetRange.rejection(Math.nextUp(.5f)))
    }
    @Test fun missingAndNonFiniteRangesCannotPlacePoints() {
        for(d in listOf(null,Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY,-1f))
            assertEquals(MeasurementTargetRange.Rejection.UNAVAILABLE,MeasurementTargetRange.rejection(d))
        assertEquals(MeasurementTargetRange.Rejection.TOO_CLOSE,MeasurementTargetRange.rejection(0f))
    }
    @Test fun maximumIsInclusiveAndStillEnforced() {
        assertNull(MeasurementTargetRange.rejection(4f,4f))
        assertEquals(MeasurementTargetRange.Rejection.TOO_FAR,MeasurementTargetRange.rejection(Math.nextUp(4f),4f))
        assertNull(MeasurementTargetRange.rejection(6f,6f))
        assertEquals(MeasurementTargetRange.Rejection.TOO_FAR,MeasurementTargetRange.rejection(6.01f,6f))
    }
    @Test fun slantRangeNotHorizontalRangeDeterminesGroundAdmission() {
        val d=MeasurementTargetRange.distance(0f,0f,0f,.3f,.4f,0f)
        assertEquals(.5f,d,.000001f);assertNull(MeasurementTargetRange.rejection(d))
        assertEquals(MeasurementTargetRange.Rejection.TOO_CLOSE,
            MeasurementTargetRange.rejection(MeasurementTargetRange.distance(0f,0f,0f,.299f,.4f,0f)))
    }
    @Test fun farHeightSightingsDoNotInheritAnchorMaximum() {
        assertNull(MeasurementTargetRange.rejection(30f));assertNull(MeasurementTargetRange.rejection(60f))
        assertEquals(MeasurementTargetRange.Rejection.TOO_FAR,MeasurementTargetRange.rejection(30f,4f))
    }
    @Test fun displayUnitsDoNotChangeMetricGate() {
        assertEquals("Move back — keep at least 0.5 m from the target.",MeasurementTargetRange.tooCloseMessage(false))
        assertEquals("Move back — keep at least 2 ft from the target.",MeasurementTargetRange.tooCloseMessage(true))
        assertEquals(.5f,MeasurementTargetRange.MINIMUM_METERS,0f)
    }
    @Test fun invalidMaximumDoesNotBypassGate() {
        for(max in listOf(.4f,Float.NaN,Float.POSITIVE_INFINITY,-1f))
            assertEquals(MeasurementTargetRange.Rejection.UNAVAILABLE,MeasurementTargetRange.rejection(1f,max))
    }
}
