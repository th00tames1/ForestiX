package com.hcjeong.forestix.common

import org.junit.Assert.*
import org.junit.Test

class HeightSampleFreshnessTest {
    @Test fun recentTrackedGeometryAge() {
        for (age in listOf(0.0, 0.25, 0.5)) assertTrue(HeightSampleFreshness.isRecent(age))
        for (age in listOf(-0.01, 0.501, 2.0, Double.POSITIVE_INFINITY, Double.NaN))
            assertFalse(HeightSampleFreshness.isRecent(age))
    }
}
