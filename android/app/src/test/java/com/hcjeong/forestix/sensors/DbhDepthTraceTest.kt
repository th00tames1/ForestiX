package com.hcjeong.forestix.sensors

import org.junit.Assert.*
import org.junit.Test

class DbhDepthTraceTest {
    private fun s(t:Long)=DbhDepthTrace.Sample(t,t,t,t-13_333_443_516L,"full_depth_stale_or_invalid_clock","NONE",42)
    @Test fun boundedHistoryRetainsTheActualStaleSensorTime() {
        val trace=DbhDepthTrace(3);for(i in 1L..20L)trace.add(s(i*1_000_000_000L))
        val samples=trace.snapshot();assertEquals(3,samples.size)
        assertEquals(18_000_000_000L,samples.first().frameNanos)
        assertEquals(13_333_443_516L,samples.last().cameraNanos-samples.last().fullDepthNanos!!)
    }
    @Test fun snapshotsAreIndependentAndNewSessionClockResetsOldHistory() {
        val trace=DbhDepthTrace(3);trace.add(s(200));val old=trace.snapshot();trace.add(s(100))
        assertEquals(200L,old.single().frameNanos);assertEquals(100L,trace.snapshot().single().frameNanos)
    }
}
