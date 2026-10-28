package com.hcjeong.forestix.sensors

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveAutoInferencePolicyTest {
    @Test fun measuredExynos2400UsesFiveThreads() {
        assertEquals(5,LiveAutoInferencePolicy.cpuThreads("s5e9945",10))
        assertEquals(5,LiveAutoInferencePolicy.cpuThreads("S5E9945",10))
    }
    @Test fun unmeasuredDevicesRetainOriginalPool() {
        for(soc in listOf("","unknown","SM8650","s5e9935"))
            assertEquals(2,LiveAutoInferencePolicy.cpuThreads(soc,10))
    }
    @Test fun restrictedCpuAvailabilityRetainsOriginalPool() {
        for(count in listOf(0,1,2,4,6,7))
            assertEquals(2,LiveAutoInferencePolicy.cpuThreads("s5e9945",count))
    }
}
