package com.hcjeong.forestix.sensors

import org.junit.Assert.*
import org.junit.Test

class DepthMotionGuidanceTest {
    private fun evidence(at: Long, depth: Float = 2f) = DepthMotionEvidence(
        frameTimestampNs = at + 1, rawTimestampNs = at + 1,
        depthM = depth, sampledPixels = 25, confidentPixels = 20,
        rawSpreadM = 0.02f, disagreementM = 0.01f)

    private fun settle(guidance: DepthMotionGuidance, start: Long = 0) {
        for (at in start..start + 600 step 200) guidance.update(evidence(at), at)
        assertFalse(guidance.needsMotion)
    }

    private fun showForDisagreement(guidance: DepthMotionGuidance, start: Long = 0) {
        for (at in start..start + 1000 step 200) {
            guidance.update(evidence(at).copy(disagreementM = 0.5f), at)
        }
        assertTrue(guidance.needsMotion)
    }

    @Test fun missingEvidenceNeverInventsAMovementInstruction() {
        val guidance = DepthMotionGuidance()
        assertFalse(guidance.update(null, 0))
        assertFalse(guidance.update(null, 899))
        assertFalse(guidance.update(null, 900))
        assertFalse(guidance.update(null, 60_000))
    }

    @Test fun sustainedFreshEvidenceClearsCue() {
        val guidance = DepthMotionGuidance()
        showForDisagreement(guidance)
        assertTrue(guidance.update(evidence(1200), 1200))
        assertTrue(guidance.update(evidence(1400), 1400))
        assertTrue(guidance.update(evidence(1600), 1600))
        assertFalse(guidance.update(evidence(1800), 1800))
    }

    @Test fun reprojectedRawDepthDoesNotMeanMovementIsNeeded() {
        val guidance = DepthMotionGuidance()
        for (at in 0L..1000L step 200) {
            guidance.update(evidence(at).copy(rawTimestampNs = 1), at)
        }
        assertFalse(guidance.needsMotion)
    }

    @Test fun repeatedCameraFrameDoesNotMeanMovementIsNeeded() {
        val guidance = DepthMotionGuidance()
        for (at in 0L..1000L step 200) {
            guidance.update(evidence(at).copy(frameTimestampNs = 1), at)
        }
        assertFalse(guidance.needsMotion)
    }

    @Test fun staleDepthDoesNotReopenCue() {
        val guidance = DepthMotionGuidance()
        settle(guidance)
        val frozen = evidence(600)
        assertFalse(guidance.update(frozen, 1000))
        assertFalse(guidance.update(frozen, 2100))
        assertFalse(guidance.update(frozen, 3000))
    }

    @Test fun oneDropoutDoesNotFlashCue() {
        val guidance = DepthMotionGuidance()
        settle(guidance)
        assertFalse(guidance.update(null, 700))
        settle(guidance, 800)
        assertFalse(guidance.update(null, 1600))
        assertFalse(guidance.update(null, 2500))
    }

    @Test fun legitimateDistanceChangesWhileMovingDoNotTriggerAdvice() {
        val guidance = DepthMotionGuidance()
        for (at in 0L..1200L step 200) {
            guidance.update(evidence(at, if (at % 400 == 0L) 2f else 2.5f), at)
        }
        assertFalse(guidance.needsMotion)
    }

    @Test fun sustainedFreshReliableDisagreementStillTriggersAdvice() {
        val guidance = DepthMotionGuidance()
        showForDisagreement(guidance)
    }

    @Test fun restartedSessionCannotReusePreviousWindow() {
        val guidance = DepthMotionGuidance()
        showForDisagreement(guidance, 10_000)
        guidance.update(evidence(0), 12_000)
        for (at in 12_200L..14_000L step 200) guidance.update(evidence(0), at)
        assertFalse(guidance.needsMotion)
    }

    @Test fun invalidAndSparseConfidenceAreNotUsable() {
        val good = evidence(0)
        assertTrue(good.hasReliableRawDepth)
        assertFalse(good.copy(confidentPixels = 2).hasReliableRawDepth)
        assertFalse(good.copy(sampledPixels = 200, confidentPixels = 3).hasReliableRawDepth)
        assertFalse(good.copy(depthM = Float.NaN).hasReliableRawDepth)
        assertFalse(good.copy(rawSpreadM = Float.POSITIVE_INFINITY).hasReliableRawDepth)
        assertFalse(good.copy(disagreementM = -1f).hasReliableRawDepth)
        assertFalse(good.copy(rawTimestampNs = 0).hasReliableRawDepth)
    }

    @Test fun probeUsesRealConfidenceNotTheEstimatorValidityMask() {
        val depths = List(25) { 2000 }
        val low = DepthMotionProbe.summarize(1, 1, depths, depths, List(25) { 2 })!!
        assertEquals(0, low.confidentPixels)
        assertFalse(low.hasReliableRawDepth)
        val high = DepthMotionProbe.summarize(1, 1, depths, depths, List(25) { 255 })!!
        assertEquals(25, high.confidentPixels)
        assertEquals(2f, high.depthM, 0f)
        assertTrue(high.agreesWithRawDepth)
    }

    @Test fun probeDetectsMixedSurfacesAndPairedDepthDisagreement() {
        val mixed = List(20) { if (it < 10) 2000 else 3000 }
        val twoSurfaces = DepthMotionProbe.summarize(1, 1, mixed, mixed, List(20) { 255 })!!
        assertFalse(twoSurfaces.hasReliableRawDepth)
        val biased = DepthMotionProbe.summarize(1, 1,
            List(20) { 2500 }, List(20) { 2000 }, List(20) { 255 })!!
        assertEquals(0.5f, biased.disagreementM, 0f)
        assertTrue(biased.hasReliableRawDepth)
        assertFalse(biased.agreesWithRawDepth)
    }

    @Test fun probeRejectsMissingAndMismatchedBuffers() {
        assertNull(DepthMotionProbe.summarize(1, 1, emptyList(), emptyList(), emptyList()))
        assertNull(DepthMotionProbe.summarize(1, 1, listOf(2000), emptyList(), listOf(255)))
        assertNull(DepthMotionProbe.summarize(1, 1, List(10) { 0 }, List(10) { 0 }, List(10) { 0 }))
    }

    @Test fun sparseRawDepthNeverKeepsAnOtherwiseCorrectDistanceWarning() {
        val guidance = DepthMotionGuidance()
        for (at in 0L..60_000L step 200) {
            assertFalse(guidance.update(evidence(at).copy(confidentPixels = 2), at))
        }
    }

    @Test fun missingEvidenceClearsOldAdviceWithoutClaimingAccuracy() {
        val guidance = DepthMotionGuidance()
        showForDisagreement(guidance)
        assertFalse(guidance.update(null, 1200))
        assertFalse(guidance.update(evidence(1400).copy(confidentPixels = 0), 1400))
    }

    @Test fun oneBadFrameRepeatedForSecondsCannotTriggerAdvice() {
        val guidance = DepthMotionGuidance()
        val bad = evidence(0).copy(disagreementM = 0.5f)
        for (at in 0L..10_000L step 200) assertFalse(guidance.update(bad, at))
    }

    @Test fun staleDisagreementCannotKeepTheHintVisible() {
        val guidance = DepthMotionGuidance()
        showForDisagreement(guidance)
        val frozen = evidence(1000).copy(disagreementM = 0.5f)
        assertTrue(guidance.update(frozen, 1200))
        assertFalse(guidance.update(frozen, 2600))
    }

    @Test fun recoveryDoesNotRequireTheUserToStopMoving() {
        val guidance = DepthMotionGuidance()
        showForDisagreement(guidance)
        for (at in 1200L..1800L step 200) {
            guidance.update(evidence(at, if (at % 400 == 0L) 2f else 2.5f), at)
        }
        assertFalse(guidance.needsMotion)
    }

    @Test fun isolatedDisagreementDoesNotFlashTheHint() {
        val guidance = DepthMotionGuidance()
        settle(guidance)
        assertFalse(guidance.update(evidence(800).copy(disagreementM = 0.5f), 800))
        settle(guidance, 1000)
    }

    @Test fun intermittentDisagreementIsNotASustainedFailure() {
        val guidance = DepthMotionGuidance()
        for (at in 0L..10_000L step 200) {
            val sample = evidence(at).copy(disagreementM = if (at % 400 == 0L) 0.5f else 0.01f)
            assertFalse(guidance.update(sample, at))
        }
    }

    @Test fun stripSamplingHandlesRotationEdgesAndDuplicatePixels() {
        val horizontal = DepthMotionProbe.pixels(10, 10, 2f, 3f, 6f, 3f)
        val vertical = DepthMotionProbe.pixels(10, 10, 3f, 2f, 3f, 6f)
        assertEquals(25, horizontal.size)
        assertEquals(horizontal.map { it.second to it.first }.toSet(), vertical.toSet())
        assertEquals(5, DepthMotionProbe.pixels(10, 10, 3f, 3f, 3f, 3f).size)
        assertEquals(15, DepthMotionProbe.pixels(10, 10, 2f, 0f, 6f, 0f).size)
        assertTrue(DepthMotionProbe.pixels(10, 10, -1f, 2f, 3f, 2f).isEmpty())
        assertTrue(DepthMotionProbe.pixels(10, 10, Float.NaN, 2f, 3f, 2f).isEmpty())
    }
}
