package com.hcjeong.forestix.sensors

import org.junit.Assert.*
import org.junit.Test

/** Synthetic app fixtures only: Auto supplies bounds, Adjust measures current depth. */
class AutoMaskBracketTest {
    private val guide = StemExtent(1.0 / 3, 2.0 / 3, 0.8f, 1)
    private val mask = LiveStemMask(1, 1, listOf(LiveStemMask.Run(0, 0, 1)))
    private fun frame(depth: Float) = ArDepthFrame(120, 90, FloatArray(10800) { depth },
        ByteArray(10800) { 2 }, 100.0, 150.0, 60.0, 45.0, FloatArray(16) { if (it % 5 == 0) 1f else 0f })

    @Test fun firstMaskPlacesExactBracketDespiteLegacyMotionAndLatencyRefusal() {
        val decision = LiveStemResultPolicy.evaluate(0.8, 0.03, 0.998)
        assertTrue(decision.displayMask); assertFalse(decision.allowMeasurement)
        val preview = LiveStemMaskPreview()
        preview.update(LiveStemObservation(null, mask, guide), 10.0, 10.8)
        assertEquals(guide, preview.bracketForDepth(10.8))
        assertEquals(guide, preview.bracketForDepth(11.2))
        assertNull(preview.bracketForDepth(11.41))
    }
    @Test fun autoBoundsUseSameCurrentDepthAndAxisMathAsAdjust() {
        val preview = LiveStemMaskPreview()
        preview.update(LiveStemObservation(null, mask, guide), 11.0)
        val bounds = preview.bracketForDepth(11.0)!!
        for (column in listOf(false, true)) {
            val axis = if (column) GuideAxis.Col(60) else GuideAxis.Row(45)
            for (depth in listOf(1f, 2f)) {
                val f = frame(depth)
                val auto = DBHEstimator.bracketChordFit(f, axis, bounds.leftFraction, bounds.rightFraction)!!
                val adjust = DBHEstimator.bracketChordFit(f, axis, guide.leftFraction, guide.rightFraction)!!
                assertEquals(adjust.diameterCm, auto.diameterCm, 0.0)
                val expected = BoundaryAlignment.sampledDiameterCm(if (column) 30.0 else 40.0,
                    depth.toDouble(), if (column) 150.0 else 100.0)!!
                assertEquals(expected, auto.diameterCm, 1e-8)
            }
            assertNull(DBHEstimator.bracketChordFit(frame(0f), axis, bounds.leftFraction, bounds.rightFraction))
        }
    }
    @Test fun noMaskNoGuideAndInvalidBoundsNeverProvideAutoBracket() {
        val preview = LiveStemMaskPreview()
        for (observation in listOf(LiveStemObservation(guide, null, guide), LiveStemObservation(guide, mask),
            LiveStemObservation(guide, mask, StemExtent(Double.NaN, 0.7, 0.8f, 1)))) {
            preview.reset(); preview.update(observation, 10.0)
            assertNull(preview.bracketForDepth(10.0))
        }
        preview.reset(); preview.update(LiveStemObservation(null, mask, guide), 10.0, 11.4)
        assertNull(preview.bracketForDepth(11.51))
        assertNull(preview.bracketForDepth(11.3))
    }
    @Test fun centreGuideDoesNotRequireFiveRowAgreement() {
        val contains: (Double, Double) -> Boolean = { x, y -> x > (if (y == 0.5) 0.35 else 0.42) && x < 0.65 }
        assertNull(LiveStemMask.centreExtent(0.8f, contains))
        val raw = LiveStemMask.guideExtent(0.8f, contains)!!
        val preview = LiveStemMaskPreview()
        preview.update(LiveStemObservation(null, mask, raw), 10.0)
        assertEquals(raw, preview.bracketForDepth(10.0))
    }
}
