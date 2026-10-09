package com.hcjeong.forestix.sensors

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class LiveStemTrackingTest {
    private fun stem(l: Double = 0.3, r: Double = 0.7) = StemExtent(l, r, 0.9f, 100)
    @Test fun acquisitionNeedsTwoConsistentFreshFrames() {
        val tracker = LiveStemTracker()
        assertNull(tracker.update(stem(), 100.0))
        assertEquals(stem(), tracker.update(stem(), 100.1))
        assertTrue(tracker.isFresh(100.2))
    }
    @Test fun smallMotionIsSmoothed() {
        val tracker = LiveStemTracker(); tracker.update(stem(), 100.0); tracker.update(stem(), 100.1)
        val next = tracker.update(stem(0.31, 0.71), 100.2)!!
        assertEquals(0.3045, next.leftFraction, 1e-12); assertEquals(0.7045, next.rightFraction, 1e-12)
    }
    @Test fun singleOutlierDoesNotMoveButRepeatedNewPositionDoes() {
        val tracker = LiveStemTracker(); tracker.update(stem(), 100.0); tracker.update(stem(), 100.1)
        assertEquals(stem(), tracker.update(stem(0.1, 0.6), 100.2))
        assertEquals(100.1, tracker.lastGood, 0.0)
        assertEquals(stem(0.1, 0.6), tracker.update(stem(0.1, 0.6), 100.3))
    }
    @Test fun briefDropoutDoesNotRefreshLock() {
        val tracker = LiveStemTracker(); tracker.update(stem(), 100.0); tracker.update(stem(), 100.1)
        assertEquals(stem(), tracker.update(null, 100.5))
        assertNull(tracker.update(null, 100.71)); assertFalse(tracker.isFresh(100.71))
    }
    @Test fun pendingFramesCannotConfirmAcrossPause() {
        val tracker = LiveStemTracker(); tracker.update(stem(), 100.0)
        assertNull(tracker.update(stem(), 102.0)); assertEquals(stem(), tracker.update(stem(), 102.1))
    }
    @Test fun resetAndInvalidClockDiscardLocks() {
        val tracker = LiveStemTracker(); tracker.update(stem(), 100.0); tracker.update(stem(), 100.1)
        tracker.reset(); assertNull(tracker.current)
        tracker.update(stem(), 100.2); tracker.update(stem(), 100.3)
        assertNull(tracker.update(stem(), Double.NaN)); assertFalse(tracker.isFresh(100.4))
    }
    @Test fun invalidBoundariesOrConfidenceCannotAcquire() {
        val tracker = LiveStemTracker(); val invalid = stem(Double.NaN, 0.7)
        assertNull(tracker.update(invalid, 100.0)); assertNull(tracker.update(invalid, 100.1))
        assertNull(LiveStemMask.centreExtent(Float.NaN) { _, _ -> true })
    }
    @Test fun visibleMaskPreservesShape() {
        val mask = LiveStemMask.sample(192.0, 256.0) { x, y -> x >= 0.2 + 0.15 * y && x < 0.6 + 0.15 * y }!!
        assertEquals(mask.height, mask.runs.size); assertEquals(96, mask.width)
        assertTrue(mask.runs.last().start > mask.runs.first().start + 5)
    }
    @Test fun centreLineUsesHorizontalSpan() {
        val extent = LiveStemMask.centreExtent(0.9f) { x, _ -> x >= 0.3 && x < 0.7 }!!
        assertEquals(0.3, extent.leftFraction, 1.0 / 512); assertEquals(0.7, extent.rightFraction, 1.0 / 512)
    }
    @Test fun holesClippedMasksAndOtherObjectsDoNotBecomeWidth() {
        assertNull(LiveStemMask.centreExtent(0.9f) { _, _ -> true })
        assertNull(LiveStemMask.centreExtent(0.9f) { x, _ -> x > 0.6 && x < 0.8 })
        assertNull(LiveStemMask.centreExtent(0.9f) { x, y ->
            x >= 0.3 && x < 0.7 && !(abs(y - 0.5) < 0.001 && x > 0.49 && x < 0.51)
        })
        assertNull(LiveStemMask.sample(192.0, 256.0) { _, _ -> false })
    }
    @Test fun projectionAllFourRotationsAndInvalidMapping() {
        val expected = listOf(101 to 81, 110 to 101, 154 to 110, 81 to 154)
        for (turn in 0..3) assertEquals(expected[turn], LiveStemMask.cameraPoint(100.0, 80.0, 256, 192, 256, 192, turn))
        assertNull(LiveStemMask.cameraPoint(Double.NaN, 0.0, 256, 192, 256, 192, 1))
    }
    @Test fun portraitColumnMappingStillGivesHorizontalOverlay() {
        val contains: (Double, Double) -> Boolean = { x, y ->
            val rgb = LiveStemMask.cameraPoint(y * 256 - 1, 192 - x * 192 - 1, 256, 192, 256, 192, 1)
            rgb != null && rgb.first >= 57 && rgb.first < 134
        }
        val extent = LiveStemMask.centreExtent(0.9f, contains)!!
        assertEquals(0.3, extent.leftFraction, 0.02); assertEquals(0.7, extent.rightFraction, 0.02)
        val mask = LiveStemMask.sample(192.0, 256.0, contains)!!
        assertTrue(mask.runs.all { it.start > 20 && it.end < 75 })
    }
    @Test fun cpuImageUsesItsOwnAffineRatherThanDepthAspectRatio() {
        val cpuFromView = floatArrayOf(0f, 1.6f, 0f, -1.125f, 0f, 90f)
        assertEquals(44 to 80, LiveStemMask.imagePoint(40.0, 50.0, cpuFromView, 160, 90, 1))
        assertEquals(11 to 80, LiveStemMask.imagePoint(10.0, 50.0, cpuFromView, 160, 90, 1))
        assertNull(LiveStemMask.imagePoint(40.0, 50.0, FloatArray(6), 160, 90, 1))
    }
}
