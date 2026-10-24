package com.hcjeong.forestix.sensors

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class LiveStemTrackingTest {
    private fun stem(l: Double = 0.3, r: Double = 0.7) = StemExtent(l, r, 0.9f, 100)
    private fun mask() = LiveStemMask.sample(192.0, 256.0) { x, _ -> x >= 0.3 && x < 0.7 }!!
    private fun depthResult(z: Double = 1.0, foreground: Boolean = false, maskSupported: Boolean = true): BoundaryAlignment.Result? {
        val depth = DoubleArray(10_000) { z }
        if (foreground) for (y in 48..52) for (x in 40..60) depth[y * 100 + x] = 0.5
        val grid = BoundaryAlignment.Grid(100, 100, 50, depth, 30.0, 70.0, 100.0)
        return BoundaryAlignment.correct(grid, 4.0, { _, _ -> maskSupported }, { listOf(28.0 to 72.0) })
    }
    @Test fun missingDepthKeepsGuideButCannotProduceMeasurement() {
        val result = depthResult(z = 0.0); assertNull(result)
        val observation = LiveStemObservation.validatingGuide(stem(), mask(), result)
        assertEquals(stem(), observation.guideExtent); assertNotNull(observation.mask); assertNull(observation.extent)
    }
    @Test fun occlusionAndMaskSupportFailuresKeepGuidesButRejectMeasurement() {
        for (result in listOf(depthResult(foreground = true), depthResult(maskSupported = false))) {
            assertNotNull(result)
            val observation = LiveStemObservation.validatingGuide(stem(), mask(), result)
            assertEquals(stem(), observation.guideExtent); assertNull(observation.extent)
        }
    }
    @Test fun approvedDepthUsesExactVisibleGuideNotHalfCorrectedSpan() {
        val result = depthResult()!!; assertEquals(29.0, result.left, 1e-10)
        val observation = LiveStemObservation.validatingGuide(stem(), mask(), result)
        assertEquals(stem(), observation.extent); assertEquals(observation.extent, observation.guideExtent)
        assertNull(LiveStemObservation.validatingGuide(null, mask(), result).extent)
        assertNull(LiveStemObservation.validatingGuide(stem(), null, result).extent)
    }
    @Test fun guideAppearsOnFirstMaskWithoutAnyDepthLock() {
        val preview = LiveStemMaskPreview(); val tracker = LiveStemTracker()
        val observation = LiveStemObservation(null, mask(), stem())
        preview.update(observation, 100.0)
        assertEquals(stem(), preview.guideExtent)
        assertNull(tracker.update(observation.extent, 100.0))
        assertFalse(preview.matchesMeasurement(null, 100.0))
        assertEquals("Stem found — checking depth…", preview.guidance(false))
    }
    @Test fun guideUsesActualCentreIntersectionWhenNearbyRowsRejectMeasurement() {
        val shape: (Double, Double) -> Boolean = { x, y ->
            x >= 0.45 + (y - 0.5) * 0.8 && x < 0.55 + (y - 0.5) * 0.2
        }
        val guide = LiveStemMask.guideExtent(0.9f, shape)!!
        assertEquals(0.45, guide.leftFraction, 1.0 / 512)
        assertEquals(0.55, guide.rightFraction, 1.0 / 512)
        assertNull(LiveStemMask.centreExtent(0.9f, shape))
    }
    @Test fun guideNeverBridgesCentreHoleClippedOrOffCentreMasks() {
        assertNull(LiveStemMask.guideExtent(0.9f) { _, _ -> true })
        assertNull(LiveStemMask.guideExtent(0.9f) { x, _ -> x > 0.6 && x < 0.8 })
        assertNull(LiveStemMask.guideExtent(0.9f) { x, _ -> (x > 0.2 && x < 0.45) || (x > 0.55 && x < 0.8) })
        assertNull(LiveStemMask.guideExtent(0.24f) { x, _ -> x > 0.3 && x < 0.7 })
    }
    @Test fun guideMovesImmediatelyWithoutGrantingMeasurementOnNewMask() {
        val preview = LiveStemMaskPreview()
        preview.update(LiveStemObservation(stem(), mask(), stem()), 100.0)
        assertTrue(preview.matchesMeasurement(stem(), 100.0))
        val moved = stem(0.35, 0.75)
        preview.update(LiveStemObservation(null, mask(), moved), 100.1)
        assertEquals(moved, preview.guideExtent)
        assertFalse(preview.matchesMeasurement(stem(), 100.0))
        assertFalse(preview.matchesMeasurement(stem(), 100.1))
        assertFalse(preview.matchesMeasurement(null, 100.1))
    }
    @Test fun sameGeometryFromOlderMaskCannotAuthorizeNewMask() {
        val preview = LiveStemMaskPreview(); val observation = LiveStemObservation(stem(), mask(), stem())
        preview.update(observation, 100.0); preview.update(observation, 100.1)
        assertFalse(preview.matchesMeasurement(stem(), 100.0))
        assertTrue(preview.matchesMeasurement(stem(), 100.1))
        assertFalse(preview.matchesMeasurement(stem(0.3001, 0.7), 100.1))
    }
    @Test fun maskAndGuideExpireAndResetTogetherAndMissDoesNotRefresh() {
        val preview = LiveStemMaskPreview()
        preview.update(LiveStemObservation(null, mask(), stem()), 100.0)
        preview.update(null, 100.3); assertEquals(stem(), preview.guideExtent)
        preview.expire(100.61); assertNull(preview.current); assertNull(preview.guideExtent)
        assertFalse(preview.matchesMeasurement(stem(), 100.0))
        preview.update(LiveStemObservation(null, mask(), stem()), 101.0)
        preview.reset(); assertNull(preview.guideExtent)
        assertEquals("Finding stem…", preview.guidance(false))
    }
    @Test fun maskWithoutCentreIntersectionClearsOldGuide() {
        val preview = LiveStemMaskPreview()
        preview.update(LiveStemObservation(null, mask(), stem()), 100.0)
        preview.update(LiveStemObservation(null, mask()), 100.1)
        assertNull(preview.guideExtent)
        assertEquals("Aim at the trunk centre.", preview.guidance(false))
    }
    @Test fun slowInferenceCanDisplayButCannotMeasure() {
        val decision = LiveStemResultPolicy.evaluate(0.8, 0.01, 0.9998)
        assertTrue(decision.displayMask); assertFalse(decision.allowMeasurement)
        val preview = LiveStemMaskPreview()
        assertNotNull(preview.update(LiveStemObservation(stem(), mask()), 100.0, 100.8))
        assertNotNull(preview.expire(101.3)); assertNull(preview.expire(101.41))
    }
    @Test fun receiptHoldStillHonoursMaximumSourceAge() {
        val preview = LiveStemMaskPreview(); val observation = LiveStemObservation(null, mask())
        assertNotNull(preview.update(observation, 100.0, 101.3))
        assertNotNull(preview.expire(101.45)); assertNull(preview.expire(101.51))
        assertNull(preview.update(observation, 102.0, 103.51))
        assertNull(preview.update(observation, 105.0, 104.0))
    }
    @Test fun previewMotionToleranceNeverWeakensMeasurementPolicy() {
        val motion = LiveStemResultPolicy.evaluate(0.2, 0.03, 0.998)
        assertTrue(motion.displayMask); assertFalse(motion.allowMeasurement)
        assertFalse(LiveStemResultPolicy.evaluate(0.2, 0.05, 1.0).displayMask)
        assertFalse(LiveStemResultPolicy.evaluate(0.2, 0.0, 0.995).displayMask)
    }
    @Test fun measurementAgeAndPoseLimitsRemainUnchanged() {
        val fast = LiveStemResultPolicy.evaluate(0.45, 0.019, 0.9995)
        assertTrue(fast.displayMask); assertTrue(fast.allowMeasurement)
        assertFalse(LiveStemResultPolicy.evaluate(0.4501, 0.0, 1.0).allowMeasurement)
        assertFalse(LiveStemResultPolicy.evaluate(0.1, 0.02, 1.0).allowMeasurement)
        assertFalse(LiveStemResultPolicy.evaluate(0.1, 0.0, 0.9994).allowMeasurement)
    }
    @Test fun invalidTimingOrChangedViewportRejectBothUses() {
        val invalid = listOf(
            LiveStemResultPolicy.evaluate(-0.1, 0.0, 1.0),
            LiveStemResultPolicy.evaluate(Double.NaN, 0.0, 1.0),
            LiveStemResultPolicy.evaluate(1.51, 0.0, 1.0),
            LiveStemResultPolicy.evaluate(0.1, Double.POSITIVE_INFINITY, 1.0),
            LiveStemResultPolicy.evaluate(0.1, 0.0, Double.NaN),
            LiveStemResultPolicy.evaluate(0.1, 0.0, 1.0, false))
        assertTrue(invalid.all { !it.displayMask && !it.allowMeasurement })
    }
    @Test fun previewRejectsDeviceRollEvenWhenCameraFacesForward() {
        assertFalse(LiveStemResultPolicy.evaluate(0.1, 0.0, 1.0, upDot = 0.98).displayMask)
        assertFalse(LiveStemResultPolicy.evaluate(0.1, 0.0, 1.0, upDot = Double.NaN).displayMask)
    }
    @Test fun missAndOutOfOrderResultCannotExtendSlowMaskHold() {
        val preview = LiveStemMaskPreview(); val observation = LiveStemObservation(null, mask())
        preview.update(observation, 100.0, 100.8)
        assertNotNull(preview.update(null, 101.2)); assertNull(preview.expire(101.41))
        preview.update(observation, 102.0, 102.5)
        assertNull(preview.update(observation, 101.9, 102.6))
    }
    @Test fun detectedMaskIsVisibleWhenDepthOrGuideExtentIsRejected() {
        val preview = LiveStemMaskPreview(); val tracker = LiveStemTracker()
        val observation = LiveStemObservation(null, mask())
        assertNotNull(preview.update(observation, 100.0))
        assertNull(tracker.update(observation.extent, 100.0))
        assertFalse(tracker.isFresh(100.0))
    }
    @Test fun firstMaskDoesNotWaitForTwoFrameMeasurementLock() {
        val preview = LiveStemMaskPreview(); val tracker = LiveStemTracker()
        val observation = LiveStemObservation(stem(), mask())
        assertNotNull(preview.update(observation, 100.0))
        assertNull(tracker.update(observation.extent, 100.0))
        assertEquals(stem(), tracker.update(observation.extent, 100.1))
    }
    @Test fun repeatedMaskWithoutExtentNeverRefreshesCaptureLock() {
        val preview = LiveStemMaskPreview(); val tracker = LiveStemTracker()
        tracker.update(stem(), 100.0); tracker.update(stem(), 100.1)
        val rejected = LiveStemObservation(null, mask())
        preview.update(rejected, 100.2); tracker.update(rejected.extent, 100.2)
        assertNotNull(preview.update(rejected, 100.8))
        assertNull(tracker.update(rejected.extent, 100.8))
        assertFalse(tracker.isFresh(100.8))
    }
    @Test fun briefMaskMissIsHeldButMissesDoNotExtendExpiry() {
        val preview = LiveStemMaskPreview()
        preview.update(LiveStemObservation(null, mask()), 100.0)
        assertNotNull(preview.update(null, 100.3))
        assertNotNull(preview.update(LiveStemObservation(stem(), null), 100.5))
        assertNull(preview.expire(100.61)); assertNull(preview.current)
    }
    @Test fun maskResetAndInvalidOrBackwardsClockDiscardPreview() {
        val preview = LiveStemMaskPreview(); val observation = LiveStemObservation(null, mask())
        preview.update(observation, 100.0); preview.reset(); assertNull(preview.current)
        preview.update(observation, 101.0); assertNull(preview.expire(100.0))
        preview.update(observation, 102.0); assertNull(preview.update(observation, Double.NaN))
        preview.update(observation, 103.0); assertNull(preview.expire(Double.POSITIVE_INFINITY))
    }
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
        val lowConfidence = StemExtent(0.3, 0.7, 0.24f, 100)
        assertNull(tracker.update(lowConfidence, 100.2)); assertNull(tracker.update(lowConfidence, 100.3))
        assertNull(LiveStemMask.centreExtent(0.24f) { x, _ -> x >= 0.3 && x < 0.7 })
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
