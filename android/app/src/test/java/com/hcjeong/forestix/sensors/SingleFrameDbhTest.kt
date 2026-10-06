package com.hcjeong.forestix.sensors

import org.junit.Assert.*
import org.junit.Test

class SingleFrameDbhTest {
    private fun frame(depthValue: Float = 1f) = ArDepthFrame(
        width=256, height=192, depth=FloatArray(256*192) { depthValue },
        confidence=ByteArray(256*192) { 2 },fx=210.0,fy=210.0,cx=128.0,cy=96.0,
        pose=FloatArray(16) { if (it%5==0) 1f else 0f },
        depthFromViewAffine=floatArrayOf(0f,.5f,0f,-.5f,0f,192f))
    @Test fun oneFrameBracketMatchesSpatialFitWithoutTemporalConfidence() {
        val f=frame();val axis=GuideAxis.Col(128);val cal=ProjectCalibration()
        val expected=DBHEstimator.bracketChordFit(f,axis,.35,.65)!!
        val actual=DBHEstimator.bracketChordEstimate(listOf(f),axis,.35,.65,cal)!!
        assertEquals(cal.appliedToRawCm(expected.diameterCm),actual.diameterCm.toDouble(),1e-4)
        assertEquals(expected.spanPx,actual.nInliers)
        assertEquals(ConfidenceTier.YELLOW,actual.confidence)
        assertEquals(0f,actual.sigmaRmm)
    }
    @Test fun invalidSingleFrameIsNotReplacedWithAReading() {
        assertNull(DBHEstimator.bracketChordEstimate(listOf(frame(0f)),GuideAxis.Col(128),.35,.65,ProjectCalibration()))
        assertNull(DBHEstimator.bracketChordEstimate(emptyList(),GuideAxis.Col(128),.35,.65,ProjectCalibration()))
    }
    @Test fun singleFrameAutoRecoversAnalyticCylinder() {
        val f=frame()
        for (x in 0 until f.width) {
            val k=(x-128)/210.0
            val disc=1.2*1.2-(1+k*k)*(1.2*1.2-.2*.2)
            val z=if (disc>=0) ((1.2-kotlin.math.sqrt(disc))/(1+k*k)).toFloat() else 3f
            for (y in 0 until f.height) f.depth[y*f.width+x]=z
        }
        val cal=ProjectCalibration()
        val p=DBHEstimator.livePreview(f,128.0,96.0,GuideAxis.Row(96),cal)!!
        val r=DBHEstimator.estimateChord(listOf(f),128.0,96.0,GuideAxis.Row(96),cal)!!
        assertNotEquals(ConfidenceTier.RED,r.confidence)
        assertEquals(p.diameterCm,r.diameterCm,1e-4f)
        assertEquals(40.0,r.diameterCm.toDouble(),3.0)
    }
    @Test fun viewMappedSingleFrameMatchesPersistedDepthGeometry() {
        val f=frame()
        val geometry=DBHEstimator.bracketDepthGeometry(f,100f,180f,256f)!!
        val preview=DBHEstimator.constrainedEstimate(f,100f,180f,256f,ProjectCalibration())!!
        val replay=DBHEstimator.bracketChordEstimate(listOf(f),geometry.axis,
            geometry.leftFraction,geometry.rightFraction,ProjectCalibration())!!
        assertEquals(preview.diameterCm,replay.diameterCm,1e-4f)
        assertEquals(ConfidenceTier.YELLOW,replay.confidence)
    }

}
