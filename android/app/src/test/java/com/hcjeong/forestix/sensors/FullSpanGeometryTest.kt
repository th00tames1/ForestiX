package com.hcjeong.forestix.sensors

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/** Synthetic application fixtures only; no field measurements or fitted values. */
class FullSpanGeometryTest {
    @Test fun fullSpanSurfaceCorrectionRecoversAnalyticDiameter() {
        assertEquals(40.0,BoundaryAlignment.fullSpanDiameterCm(
            28.432746132436435,1.5+(1-sqrt(0.75))*0.2,120.0)!!,1e-10)
    }
    @Test fun continuousBoundsSelectPixelCentresAndEvenMedian() {
        val selected=mutableListOf<Int>()
        val result=BoundaryAlignment.fullSpanSample(20,4.2,14.8,100.0) { selected.add(it);it/10.0 }!!
        assertEquals((5..14).toList(),selected)
        assertEquals(0.95,result.second,1e-12)
        assertEquals(BoundaryAlignment.fullSpanDiameterCm(10.6,0.95,100.0)!!,result.first,1e-12)
    }
    @Test fun invalidAndUndersampledGeometryFailsClosed() {
        for(bad in listOf(Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,0.0,-1.0)) {
            assertNull(BoundaryAlignment.fullSpanDiameterCm(bad,1.0,100.0))
            assertNull(BoundaryAlignment.fullSpanDiameterCm(20.0,bad,100.0))
            assertNull(BoundaryAlignment.fullSpanDiameterCm(20.0,1.0,bad))
        }
        assertNull(BoundaryAlignment.fullSpanSample(20,Double.NaN,10.0,100.0) { 1.0 })
        assertNull(BoundaryAlignment.fullSpanSample(20,4.2,6.8,100.0) { 1.0 })
        assertNull(BoundaryAlignment.fullSpanSample(20,4.0,14.0,100.0) { Double.NaN })
    }
    @Test fun aiAdjustsSupportedSideOnlyAndUsesSameDiameter() {
        val g=BoundaryAlignment.Grid(100,100,50,DoubleArray(10000){1.0},30.0,70.0,100.0)
        val result=BoundaryAlignment.correct(g,4.0,{_,_->true},{listOf(28.0 to 80.0)})!!
        assertEquals(29.0,result.left,0.0);assertEquals(70.0,result.right,0.0)
        assertEquals("accepted",result.leftReason);assertEquals("large_shift",result.rightReason)
        assertEquals(BoundaryAlignment.diameter(g,29.0,70.0)!!.first,result.diameterCm,1e-12)
    }
    private fun frame(column:Boolean):ArDepthFrame {
        val width=120;val height=90
        val depth=FloatArray(width*height) { i ->
            val idx=if(column)i/width else i%width
            val lo=if(column)30 else 40;val hi=if(column)60 else 80;val mid=if(column)45 else 60
            if(idx in lo..hi) { if(abs(idx-mid)<=2)1.02f else 1f } else 3f
        }
        return ArDepthFrame(width,height,depth,ByteArray(depth.size){2},100.0,150.0,60.0,45.0,
            FloatArray(16){if(it%5==0)1f else 0f})
    }
    @Test fun autoAndManualUseSameSpanDepthAndAxisFocal() {
        for(column in listOf(false,true)) {
            val f=frame(column);val axis=if(column)GuideAxis.Col(60) else GuideAxis.Row(45)
            val auto=DBHEstimator.livePreview(f,60.0,45.0,axis,ProjectCalibration())!!
            val manual=DBHEstimator.bracketChordFit(f,axis,auto.stripLeftFraction.toDouble(),auto.stripRightFraction.toDouble())!!
            assertEquals(auto.diameterCm.toDouble(),manual.diameterCm,1e-5)
            assertEquals(1f,auto.distanceM,1e-6f)
            val span=if(column)30.0 else 40.0;val focal=if(column)150.0 else 100.0
            assertEquals(BoundaryAlignment.fullSpanDiameterCm(span,1.0,focal)!!,auto.diameterCm.toDouble(),1e-5)
            val actual=DBHEstimator.estimateChord(listOf(f),60.0,45.0,axis,ProjectCalibration())!!
            assertEquals(auto.diameterCm,actual.diameterCm,1e-4f)
        }
    }
    @Test fun autoDoesNotSubstituteNeighbouringRowWhenMeasurementRowMissing() {
        val f=frame(false);for(x in 0 until f.width) f.depth[45*f.width+x]=0f
        val preview=DBHEstimator.livePreview(f,60.0,45.0,GuideAxis.Row(45),ProjectCalibration())
        assertTrue(preview==null || !preview.locked)
    }
}
