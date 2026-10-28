package com.hcjeong.forestix.sensors

import com.hcjeong.forestix.data.cruise.DBHCalibration
import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

/** Synthetic surfaces only; mirrors the Swift sampling and geometry checks. */
class OptimalSamplingTest {
    @Test fun selectedSurfaceCorrectionRecoversAnalyticDiameter() {
        val radius=0.2; val front=1.5
        val span=2*120*tan(asin(radius/(front+radius)))
        val offset=1-sqrt(1-0.4*0.4)
        assertEquals(0.8,BoundaryAlignment.SAMPLING_FRACTION,0.0)
        assertEquals(offset,BoundaryAlignment.SAMPLED_DEPTH_OFFSET_FACTOR,1e-14)
        assertEquals(40.0,BoundaryAlignment.sampledDiameterCm(span,front+offset*radius,120.0)!!,1e-10)
    }
    @Test fun fractionalGuidesKeepFullWidthButTrimDepthPixels() {
        val read=mutableListOf<Int>()
        val result=BoundaryAlignment.sampledDepth(20,4.2,14.8,100.0) { read.add(it);it/10.0 }!!
        assertEquals((6..13).toList(),read)
        assertEquals(0.95,result.second,1e-12)
        assertEquals(BoundaryAlignment.sampledDiameterCm(10.6,0.95,100.0)!!,result.first,1e-12)
    }
    @Test fun pixelCentresExactlyOnSelectedBoundsAreRetained() {
        val read=mutableListOf<Int>()
        assertNotNull(BoundaryAlignment.sampledDepth(30,0.0,20.0,100.0) { read.add(it);1.0 })
        assertEquals((2..18).toList(),read)
    }
    @Test fun peripheralDepthsCannotChangeSelectedMedian() {
        val read=mutableListOf<Int>()
        val result=BoundaryAlignment.sampledDepth(120,10.0,110.0,100.0) { read.add(it);if(it in 20..100)1.0 else 7.0 }!!
        assertEquals((20..100).toList(),read)
        assertEquals(1.0,result.second,0.0)
    }
    @Test fun invalidOrUndersampledSelectedRegionIsRejected() {
        assertNull(BoundaryAlignment.sampledDepth(20,4.2,6.8,100.0) { 1.0 })
        assertNull(BoundaryAlignment.sampledDepth(20,4.0,14.0,100.0) { Double.NaN })
        assertNull(BoundaryAlignment.sampledDiameterCm(20.0,Double.NaN,100.0))
    }
    @Test fun previousCalibrationIsNotAppliedToChangedEstimator() {
        assertEquals(DBHCalibration.State.IGNORED_STALE,DBHCalibration.state(1f,1f,8))
        assertEquals(DBHCalibration.State.APPLIED,DBHCalibration.state(1f,1f,DBHCalibration.CURRENT_EPOCH))
    }
}
