package com.hcjeong.forestix.common

import com.hcjeong.forestix.ar.Vec3
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraPoseSpeedGuardTest {
    @Test fun testRadialShiftCanBeImplausibleBelowSpeedCeiling() {
        val g=CameraPoseSpeedGuard(); val a=Vec3(0f,0f,0f)
        for (t in listOf(0.0,0.1)) assertEquals(CameraPoseSpeedGuard.Verdict.USABLE,g.observe(Vec3(15f,0f,0f),t,a))
        assertEquals(CameraPoseSpeedGuard.Verdict.CHECKING,g.observe(Vec3(15.75f,0f,0f),0.3,a))
        for (i in 4..23) assertEquals(CameraPoseSpeedGuard.Verdict.CHECKING,g.observe(Vec3(15.75f,0f,0f),i/10.0,a))
        assertEquals(CameraPoseSpeedGuard.Verdict.RETAKE,g.observe(Vec3(15.75f,0f,0f),2.5,a))
    }

    @Test fun testStoppingDoesNotInvalidateAnUnchangedRange() {
        val g=CameraPoseSpeedGuard(); val a=Vec3(0f,0f,0f)
        for ((t,x) in listOf(0.0 to 15f,0.2 to 15.6f,0.4 to 16.2f,0.6 to 16.2f))
            assertEquals(CameraPoseSpeedGuard.Verdict.USABLE,g.observe(Vec3(x,0f,0f),t,a))
    }

    @Test fun testWalkingFramesUseSensorTime() {
        val g = CameraPoseSpeedGuard()
        assertEquals(CameraPoseSpeedGuard.Verdict.USABLE, g.observe(Vec3(15f, 0f, 0f), 0.000, Vec3(0f, 0f, 0f)))
        assertEquals(CameraPoseSpeedGuard.Verdict.USABLE, g.observe(Vec3(15.1f, 0f, 0f), 0.100, Vec3(0f, 0f, 0f)))
        assertEquals(CameraPoseSpeedGuard.Verdict.USABLE, g.observe(Vec3(15.2f, 0f, 0f), 0.200, Vec3(0f, 0f, 0f)))
        assertEquals(CameraPoseSpeedGuard.Verdict.USABLE, g.observe(Vec3(15.3f, 0f, 0f), 0.300, Vec3(0f, 0f, 0f)))
    }

    @Test fun testWalkingExactlyAtLimit() {
        val g = CameraPoseSpeedGuard()
        assertEquals(CameraPoseSpeedGuard.Verdict.USABLE, g.observe(Vec3(15f, 0f, 0f), 0.000, Vec3(0f, 0f, 0f)))
        assertEquals(CameraPoseSpeedGuard.Verdict.USABLE, g.observe(Vec3(15.5f, 0f, 0f), 0.125, Vec3(0f, 0f, 0f)))
        assertEquals(CameraPoseSpeedGuard.Verdict.USABLE, g.observe(Vec3(16f, 0f, 0f), 0.250, Vec3(0f, 0f, 0f)))
    }

    @Test fun testCommonOriginTranslation() {
        val g = CameraPoseSpeedGuard()
        assertEquals(CameraPoseSpeedGuard.Verdict.USABLE, g.observe(Vec3(15f, 0f, 0f), 0.000, Vec3(0f, 0f, 0f)))
        assertEquals(CameraPoseSpeedGuard.Verdict.USABLE, g.observe(Vec3(15f, 0f, 0f), 0.100, Vec3(0f, 0f, 0f)))
        assertEquals(CameraPoseSpeedGuard.Verdict.USABLE, g.observe(Vec3(16f, 0f, 0f), 0.133, Vec3(1f, 0f, 0f)))
        assertEquals(CameraPoseSpeedGuard.Verdict.USABLE, g.observe(Vec3(16f, 0f, 0f), 0.166, Vec3(1f, 0f, 0f)))
    }

    @Test fun testVerticalOnlyStep() {
        val g = CameraPoseSpeedGuard()
        assertEquals(CameraPoseSpeedGuard.Verdict.USABLE, g.observe(Vec3(15f, 0f, 0f), 0.000, Vec3(0f, 0f, 0f)))
        assertEquals(CameraPoseSpeedGuard.Verdict.USABLE, g.observe(Vec3(15f, 0f, 0f), 0.100, Vec3(0f, 0f, 0f)))
        assertEquals(CameraPoseSpeedGuard.Verdict.USABLE, g.observe(Vec3(15f, 0.25f, 0f), 0.133, Vec3(0f, 0f, 0f)))
        assertEquals(CameraPoseSpeedGuard.Verdict.USABLE, g.observe(Vec3(15f, 0.25f, 0f), 0.167, Vec3(0f, 0f, 0f)))
    }

    @Test fun testCameraAndAnchorOffsetDoesNotHideChangedRange() {
        val g = CameraPoseSpeedGuard()
        assertEquals(CameraPoseSpeedGuard.Verdict.USABLE, g.observe(Vec3(15f, 0f, 0f), 0.000, Vec3(0f, 0f, 0f)))
        assertEquals(CameraPoseSpeedGuard.Verdict.USABLE, g.observe(Vec3(15f, 0f, 0f), 0.100, Vec3(0f, 0f, 0f)))
        for (ms in 133..2133 step 100) {
            assertEquals(CameraPoseSpeedGuard.Verdict.CHECKING, g.observe(Vec3(17f, 0f, 0f), ms / 1000.0, Vec3(1f, 0f, 0f)))
        }
        assertEquals(CameraPoseSpeedGuard.Verdict.RETAKE, g.observe(Vec3(17f, 0f, 0f), 2.233, Vec3(1f, 0f, 0f)))
        assertEquals(CameraPoseSpeedGuard.Verdict.RETAKE, g.observe(Vec3(17f, 0f, 0f), 2.333, Vec3(1f, 0f, 0f)))
    }

    @Test fun testAnchorOnlyRangeJump() {
        val g = CameraPoseSpeedGuard()
        assertEquals(CameraPoseSpeedGuard.Verdict.USABLE, g.observe(Vec3(15f, 0f, 0f), 0.000, Vec3(0f, 0f, 0f)))
        assertEquals(CameraPoseSpeedGuard.Verdict.USABLE, g.observe(Vec3(15f, 0f, 0f), 0.100, Vec3(0f, 0f, 0f)))
        assertEquals(CameraPoseSpeedGuard.Verdict.CHECKING, g.observe(Vec3(15f, 0f, 0f), 0.140, Vec3(1f, 0f, 0f)))
        for (ms in 250..2150 step 100) {
            assertEquals(CameraPoseSpeedGuard.Verdict.CHECKING, g.observe(Vec3(15f, 0f, 0f), ms / 1000.0, Vec3(1f, 0f, 0f)))
        }
        assertEquals(CameraPoseSpeedGuard.Verdict.RETAKE, g.observe(Vec3(15f, 0f, 0f), 2.250, Vec3(1f, 0f, 0f)))
        assertEquals(CameraPoseSpeedGuard.Verdict.RETAKE, g.observe(Vec3(15f, 0f, 0f), 2.350, Vec3(1f, 0f, 0f)))
    }

    @Test fun testSingleSpikeNeedsTwoMeasuredReturns() {
        val g = CameraPoseSpeedGuard()
        assertEquals(CameraPoseSpeedGuard.Verdict.USABLE, g.observe(Vec3(15f, 0f, 0f), 0.000, Vec3(0f, 0f, 0f)))
        assertEquals(CameraPoseSpeedGuard.Verdict.USABLE, g.observe(Vec3(15.1f, 0f, 0f), 0.100, Vec3(0f, 0f, 0f)))
        assertEquals(CameraPoseSpeedGuard.Verdict.CHECKING, g.observe(Vec3(16.7f, 0f, 0f), 0.200, Vec3(0f, 0f, 0f)))
        assertEquals(CameraPoseSpeedGuard.Verdict.CHECKING, g.observe(Vec3(15.3f, 0f, 0f), 0.300, Vec3(0f, 0f, 0f)))
        assertEquals(CameraPoseSpeedGuard.Verdict.CHECKING, g.observe(Vec3(15.3f, 0f, 0f), 0.300, Vec3(0f, 0f, 0f)))
        assertEquals(CameraPoseSpeedGuard.Verdict.USABLE, g.observe(Vec3(15.4f, 0f, 0f), 0.400, Vec3(0f, 0f, 0f)))
    }

    @Test fun testStaleAndDuplicateFramesCannotConfirm() {
        val g = CameraPoseSpeedGuard()
        assertEquals(CameraPoseSpeedGuard.Verdict.USABLE, g.observe(Vec3(15f, 0f, 0f), 0.000, Vec3(0f, 0f, 0f)))
        assertEquals(CameraPoseSpeedGuard.Verdict.USABLE, g.observe(Vec3(15f, 0f, 0f), 0.100, Vec3(0f, 0f, 0f)))
        assertEquals(CameraPoseSpeedGuard.Verdict.CHECKING, g.observe(Vec3(16f, 0f, 0f), 0.133, Vec3(0f, 0f, 0f)))
        assertEquals(CameraPoseSpeedGuard.Verdict.CHECKING, g.observe(Vec3(15f, 0f, 0f), 0.166, Vec3(0f, 0f, 0f)))
        assertEquals(CameraPoseSpeedGuard.Verdict.CHECKING, g.observe(Vec3(15f, 0f, 0f), 0.120, Vec3(0f, 0f, 0f)))
        assertEquals(CameraPoseSpeedGuard.Verdict.CHECKING, g.observe(Vec3(15f, 0f, 0f), 0.166, Vec3(0f, 0f, 0f)))
        assertEquals(CameraPoseSpeedGuard.Verdict.USABLE, g.observe(Vec3(15f, 0f, 0f), 0.199, Vec3(0f, 0f, 0f)))
        assertEquals(CameraPoseSpeedGuard.Verdict.USABLE, g.observe(Vec3(15f, 0f, 0f), 0.199, Vec3(0f, 0f, 0f)))
        assertEquals(CameraPoseSpeedGuard.Verdict.IGNORED, g.observe(Vec3(15f, 0f, 0f), 0.166, Vec3(0f, 0f, 0f)))
    }

    @Test fun testInPlaceRecheckAfterMultipleBadFrames() {
        val g = CameraPoseSpeedGuard()
        assertEquals(CameraPoseSpeedGuard.Verdict.USABLE, g.observe(Vec3(15f, 0f, 0f), 0.000, Vec3(0f, 0f, 0f)))
        assertEquals(CameraPoseSpeedGuard.Verdict.USABLE, g.observe(Vec3(15f, 0f, 0f), 0.100, Vec3(0f, 0f, 0f)))
        assertEquals(CameraPoseSpeedGuard.Verdict.CHECKING, g.observe(Vec3(16f, 0f, 0f), 0.133, Vec3(0f, 0f, 0f)))
        assertEquals(CameraPoseSpeedGuard.Verdict.CHECKING, g.observe(Vec3(16f, 0f, 0f), 0.166, Vec3(0f, 0f, 0f)))
        assertEquals(CameraPoseSpeedGuard.Verdict.CHECKING, g.observe(Vec3(16f, 0f, 0f), 0.200, Vec3(0f, 0f, 0f)))
        for (ms in 250..550 step 100) {
            assertEquals(CameraPoseSpeedGuard.Verdict.CHECKING, g.observe(Vec3(15f, 0f, 0f), ms / 1000.0, Vec3(0f, 0f, 0f)))
        }
        assertEquals(CameraPoseSpeedGuard.Verdict.USABLE, g.observe(Vec3(15f, 0f, 0f), 0.650, Vec3(0f, 0f, 0f)))
        assertEquals(CameraPoseSpeedGuard.Verdict.USABLE, g.observe(Vec3(15f, 0f, 0f), 0.750, Vec3(0f, 0f, 0f)))
    }

    @Test fun testSettledButOffsetMustRetake() {
        val g = CameraPoseSpeedGuard()
        assertEquals(CameraPoseSpeedGuard.Verdict.USABLE, g.observe(Vec3(15f, 0f, 0f), 0.000, Vec3(0f, 0f, 0f)))
        assertEquals(CameraPoseSpeedGuard.Verdict.USABLE, g.observe(Vec3(15f, 0f, 0f), 0.100, Vec3(0f, 0f, 0f)))
        assertEquals(CameraPoseSpeedGuard.Verdict.CHECKING, g.observe(Vec3(16f, 0f, 0f), 0.133, Vec3(0f, 0f, 0f)))
        for (ms in 250..2150 step 100) {
            assertEquals(CameraPoseSpeedGuard.Verdict.CHECKING, g.observe(Vec3(16f, 0f, 0f), ms / 1000.0, Vec3(0f, 0f, 0f)))
        }
        assertEquals(CameraPoseSpeedGuard.Verdict.RETAKE, g.observe(Vec3(16f, 0f, 0f), 2.250, Vec3(0f, 0f, 0f)))
        assertEquals(CameraPoseSpeedGuard.Verdict.RETAKE, g.observe(Vec3(16f, 0f, 0f), 2.350, Vec3(0f, 0f, 0f)))
        assertEquals(CameraPoseSpeedGuard.Verdict.RETAKE, g.observe(Vec3(15f, 0f, 0f), 2.500, Vec3(0f, 0f, 0f)))
    }

    @Test fun testSidewaysRotationKeepsHorizontalRange() {
        val g = CameraPoseSpeedGuard()
        assertEquals(CameraPoseSpeedGuard.Verdict.USABLE, g.observe(Vec3(15f, 0f, 0f), 0.000, Vec3(0f, 0f, 0f)))
        assertEquals(CameraPoseSpeedGuard.Verdict.USABLE, g.observe(Vec3(15f, 0f, 0f), 0.100, Vec3(0f, 0f, 0f)))
        assertEquals(CameraPoseSpeedGuard.Verdict.CHECKING, g.observe(Vec3(0f, 0f, 15f), 0.133, Vec3(0f, 0f, 0f)))
        assertEquals(CameraPoseSpeedGuard.Verdict.CHECKING, g.observe(Vec3(0f, 0f, 15f), 0.166, Vec3(0f, 0f, 0f)))
        assertEquals(CameraPoseSpeedGuard.Verdict.USABLE, g.observe(Vec3(0f, 0f, 15f), 0.200, Vec3(0f, 0f, 0f)))
    }

    @Test fun testCommonOriginChangeWhileWalking() {
        val g = CameraPoseSpeedGuard()
        assertEquals(CameraPoseSpeedGuard.Verdict.USABLE, g.observe(Vec3(15f, 0f, 0f), 0.000, Vec3(0f, 0f, 0f)))
        assertEquals(CameraPoseSpeedGuard.Verdict.USABLE, g.observe(Vec3(15.1f, 0f, 0f), 0.100, Vec3(0f, 0f, 0f)))
        assertEquals(CameraPoseSpeedGuard.Verdict.USABLE, g.observe(Vec3(16.2f, 0f, 0f), 0.200, Vec3(1f, 0f, 0f)))
        assertEquals(CameraPoseSpeedGuard.Verdict.USABLE, g.observe(Vec3(16.3f, 0f, 0f), 0.300, Vec3(1f, 0f, 0f)))
        assertEquals(CameraPoseSpeedGuard.Verdict.USABLE, g.observe(Vec3(16.4f, 0f, 0f), 0.400, Vec3(1f, 0f, 0f)))
    }

    @Test fun testSecondSpikeAfterApparentReturn() {
        val g = CameraPoseSpeedGuard()
        assertEquals(CameraPoseSpeedGuard.Verdict.USABLE, g.observe(Vec3(15f, 0f, 0f), 0.000, Vec3(0f, 0f, 0f)))
        assertEquals(CameraPoseSpeedGuard.Verdict.USABLE, g.observe(Vec3(15f, 0f, 0f), 0.100, Vec3(0f, 0f, 0f)))
        assertEquals(CameraPoseSpeedGuard.Verdict.CHECKING, g.observe(Vec3(17f, 0f, 0f), 0.133, Vec3(0f, 0f, 0f)))
        assertEquals(CameraPoseSpeedGuard.Verdict.CHECKING, g.observe(Vec3(15f, 0f, 0f), 0.166, Vec3(0f, 0f, 0f)))
        assertEquals(CameraPoseSpeedGuard.Verdict.CHECKING, g.observe(Vec3(17f, 0f, 0f), 0.199, Vec3(0f, 0f, 0f)))
        for (ms in 300..2100 step 100) {
            assertEquals(CameraPoseSpeedGuard.Verdict.CHECKING, g.observe(Vec3(17f, 0f, 0f), ms / 1000.0, Vec3(0f, 0f, 0f)))
        }
        assertEquals(CameraPoseSpeedGuard.Verdict.RETAKE, g.observe(Vec3(17f, 0f, 0f), 2.300, Vec3(0f, 0f, 0f)))
        assertEquals(CameraPoseSpeedGuard.Verdict.RETAKE, g.observe(Vec3(17f, 0f, 0f), 2.400, Vec3(0f, 0f, 0f)))
    }

    @Test fun testShortLossKeepsProgress() {
        val g = CameraPoseSpeedGuard()
        assertEquals(CameraPoseSpeedGuard.Verdict.USABLE, g.observe(Vec3(15f, 0f, 0f), 0.000, Vec3(0f, 0f, 0f)))
        assertEquals(CameraPoseSpeedGuard.Verdict.USABLE, g.observe(Vec3(15f, 0f, 0f), 0.100, Vec3(0f, 0f, 0f)))
        assertEquals(CameraPoseSpeedGuard.Verdict.CHECKING, g.interrupt())
        assertEquals(CameraPoseSpeedGuard.Verdict.CHECKING, g.observe(Vec3(15f, 0f, 0f), 0.166, Vec3(0f, 0f, 0f)))
        assertEquals(CameraPoseSpeedGuard.Verdict.USABLE, g.observe(Vec3(15f, 0f, 0f), 0.199, Vec3(0f, 0f, 0f)))
    }

    @Test fun testGapCannotConfirmByItself() {
        val g = CameraPoseSpeedGuard()
        assertEquals(CameraPoseSpeedGuard.Verdict.USABLE, g.observe(Vec3(15f, 0f, 0f), 0.000, Vec3(0f, 0f, 0f)))
        assertEquals(CameraPoseSpeedGuard.Verdict.USABLE, g.observe(Vec3(15f, 0f, 0f), 0.100, Vec3(0f, 0f, 0f)))
        for (ms in 900..1300 step 100) {
            assertEquals(CameraPoseSpeedGuard.Verdict.CHECKING, g.observe(Vec3(15f, 0f, 0f), ms / 1000.0, Vec3(0f, 0f, 0f)))
        }
        assertEquals(CameraPoseSpeedGuard.Verdict.USABLE, g.observe(Vec3(15f, 0f, 0f), 1.400, Vec3(0f, 0f, 0f)))
        assertEquals(CameraPoseSpeedGuard.Verdict.USABLE, g.observe(Vec3(15f, 0f, 0f), 1.500, Vec3(0f, 0f, 0f)))
    }

    @Test fun testMissingAnchorNeverConfirms() {
        val g = CameraPoseSpeedGuard()
        assertEquals(CameraPoseSpeedGuard.Verdict.USABLE, g.observe(Vec3(15f, 0f, 0f), 0.000, Vec3(0f, 0f, 0f)))
        assertEquals(CameraPoseSpeedGuard.Verdict.USABLE, g.observe(Vec3(15f, 0f, 0f), 0.100, Vec3(0f, 0f, 0f)))
        assertEquals(CameraPoseSpeedGuard.Verdict.CHECKING, g.observe(Vec3(16f, 0f, 0f), 0.133, Vec3(0f, 0f, 0f)))
        assertEquals(CameraPoseSpeedGuard.Verdict.CHECKING, g.observe(Vec3(15f, 0f, 0f), 0.166, null))
        assertEquals(CameraPoseSpeedGuard.Verdict.CHECKING, g.observe(Vec3(15f, 0f, 0f), 0.566, null))
        assertEquals(CameraPoseSpeedGuard.Verdict.CHECKING, g.observe(Vec3(15f, 0f, 0f), 2.200, null))
        for (ms in 2300..2600 step 100) {
            assertEquals(CameraPoseSpeedGuard.Verdict.CHECKING, g.observe(Vec3(15f, 0f, 0f), ms / 1000.0, Vec3(0f, 0f, 0f)))
        }
        assertEquals(CameraPoseSpeedGuard.Verdict.USABLE, g.observe(Vec3(15f, 0f, 0f), 2.700, Vec3(0f, 0f, 0f)))
        assertEquals(CameraPoseSpeedGuard.Verdict.USABLE, g.observe(Vec3(15f, 0f, 0f), 2.800, Vec3(0f, 0f, 0f)))
    }

    @Test fun testNoPreSpikeDirection() {
        val g = CameraPoseSpeedGuard()
        assertEquals(CameraPoseSpeedGuard.Verdict.USABLE, g.observe(Vec3(15f, 0f, 0f), 0.000, Vec3(0f, 0f, 0f)))
        assertEquals(CameraPoseSpeedGuard.Verdict.CHECKING, g.observe(Vec3(18f, 0f, 0f), 0.100, Vec3(0f, 0f, 0f)))
        for (ms in 200..500 step 100) {
            assertEquals(CameraPoseSpeedGuard.Verdict.CHECKING, g.observe(Vec3(15f, 0f, 0f), ms / 1000.0, Vec3(0f, 0f, 0f)))
        }
        assertEquals(CameraPoseSpeedGuard.Verdict.USABLE, g.observe(Vec3(15f, 0f, 0f), 0.600, Vec3(0f, 0f, 0f)))
        assertEquals(CameraPoseSpeedGuard.Verdict.USABLE, g.observe(Vec3(15f, 0f, 0f), 0.700, Vec3(0f, 0f, 0f)))
    }

    @Test fun testInvalidFrameDuringCheck() {
        val g = CameraPoseSpeedGuard()
        assertEquals(CameraPoseSpeedGuard.Verdict.USABLE, g.observe(Vec3(15f, 0f, 0f), 0.000, Vec3(0f, 0f, 0f)))
        assertEquals(CameraPoseSpeedGuard.Verdict.USABLE, g.observe(Vec3(15f, 0f, 0f), 0.100, Vec3(0f, 0f, 0f)))
        assertEquals(CameraPoseSpeedGuard.Verdict.CHECKING, g.observe(Vec3(16f, 0f, 0f), 0.133, Vec3(0f, 0f, 0f)))
        assertEquals(CameraPoseSpeedGuard.Verdict.CHECKING, g.observe(Vec3(Float.NaN, 0f, 0f), 0.166, Vec3(0f, 0f, 0f)))
        for (ms in 1000..1300 step 100) {
            assertEquals(CameraPoseSpeedGuard.Verdict.CHECKING, g.observe(Vec3(15f, 0f, 0f), ms / 1000.0, Vec3(0f, 0f, 0f)))
        }
        assertEquals(CameraPoseSpeedGuard.Verdict.USABLE, g.observe(Vec3(15f, 0f, 0f), 1.400, Vec3(0f, 0f, 0f)))
        assertEquals(CameraPoseSpeedGuard.Verdict.USABLE, g.observe(Vec3(15f, 0f, 0f), 1.500, Vec3(0f, 0f, 0f)))
    }

    @Test fun testDriftDuringMissingObservationsDoesNotRelock() {
        val g = CameraPoseSpeedGuard()
        assertEquals(CameraPoseSpeedGuard.Verdict.USABLE, g.observe(Vec3(15f, 0f, 0f), 0.000, Vec3(0f, 0f, 0f)))
        assertEquals(CameraPoseSpeedGuard.Verdict.USABLE, g.observe(Vec3(15f, 0f, 0f), 0.100, Vec3(0f, 0f, 0f)))
        assertEquals(CameraPoseSpeedGuard.Verdict.CHECKING, g.interrupt())
        for (ms in 5000..6900 step 100) {
            assertEquals(CameraPoseSpeedGuard.Verdict.CHECKING, g.observe(Vec3(16f, 0f, 0f), ms / 1000.0, Vec3(0f, 0f, 0f)))
        }
        assertEquals(CameraPoseSpeedGuard.Verdict.RETAKE, g.observe(Vec3(16f, 0f, 0f), 7.000, Vec3(0f, 0f, 0f)))
        assertEquals(CameraPoseSpeedGuard.Verdict.RETAKE, g.observe(Vec3(16f, 0f, 0f), 7.100, Vec3(0f, 0f, 0f)))
        assertEquals(CameraPoseSpeedGuard.Verdict.RETAKE, g.interrupt())
        g.reset()
        assertEquals(CameraPoseSpeedGuard.Verdict.USABLE, g.observe(Vec3(55f, 0f, 0f), 8.000, Vec3(0f, 0f, 0f)))
    }

    @Test fun testStableWrongRangeCannotWaitForPredictionToCatchUp() {
        val g = CameraPoseSpeedGuard()
        assertEquals(CameraPoseSpeedGuard.Verdict.USABLE, g.observe(Vec3(14.8f, 0f, 0f), 0.000, Vec3(0f, 0f, 0f)))
        assertEquals(CameraPoseSpeedGuard.Verdict.USABLE, g.observe(Vec3(15f, 0f, 0f), 0.100, Vec3(0f, 0f, 0f)))
        assertEquals(CameraPoseSpeedGuard.Verdict.CHECKING, g.observe(Vec3(15.21f, 0f, 0f), 0.133, Vec3(0f, 0f, 0f)))
        assertEquals(CameraPoseSpeedGuard.Verdict.CHECKING, g.observe(Vec3(15.21f, 0f, 0f), 0.166, Vec3(0f, 0f, 0f)))
        assertEquals(CameraPoseSpeedGuard.Verdict.CHECKING, g.observe(Vec3(15.21f, 0f, 0f), 0.200, Vec3(0f, 0f, 0f)))
    }

    @Test fun testMissingFirstAnchorCannotSeedHeight() {
        val g = CameraPoseSpeedGuard()
        assertEquals(CameraPoseSpeedGuard.Verdict.IGNORED, g.observe(Vec3(15f, 0f, 0f), 0.000, null))
        assertEquals(CameraPoseSpeedGuard.Verdict.USABLE, g.observe(Vec3(15f, 0f, 0f), 0.100, Vec3(0f, 0f, 0f)))
    }

    @Test fun testAbsoluteCoordinatesCannotMixFrames() {
        val g = CameraPoseSpeedGuard()
        assertEquals(CameraPoseSpeedGuard.Verdict.USABLE, g.observe(Vec3(15f, 0f, 0f), 0.000, Vec3(0f, 0f, 0f)))
        assertEquals(CameraPoseSpeedGuard.Verdict.USABLE, g.observe(Vec3(15f, 0f, 0f), 0.100, Vec3(0f, 0f, 0f)))
        assertEquals(CameraPoseSpeedGuard.Verdict.IGNORED, g.observe(Vec3(15f, 0f, 0f), 0.100, Vec3(0.2f, 0f, 0f)))
        assertEquals(CameraPoseSpeedGuard.Verdict.USABLE, g.observe(Vec3(15f, 0f, 0f), 0.100, Vec3(0f, 0f, 0f)))
    }
}
