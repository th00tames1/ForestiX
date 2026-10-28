package com.hcjeong.forestix.sensors
import org.junit.Assert.assertEquals
import org.junit.Test
class LiveAutoCadenceTest {
    @Test fun nominalTargetIsSixteenHzWithYieldForSlowWork(){
        assertEquals(62L,LiveAutoCadence.delayMillis(0.0))
        assertEquals(32L,LiveAutoCadence.delayMillis(.03))
        assertEquals(2L,LiveAutoCadence.delayMillis(.3))
    }
    @Test fun thermalBackoffRetainsOriginalPeriodAndRest(){
        assertEquals(95L,LiveAutoCadence.delayMillis(.03,true))
        assertEquals(20L,LiveAutoCadence.delayMillis(.3,true))
    }
    @Test fun invalidClocksCannotCreateBusyLoop(){
        for(bad in listOf(Double.NaN,Double.POSITIVE_INFINITY,-1.0))assertEquals(62L,LiveAutoCadence.delayMillis(bad))
    }
}
