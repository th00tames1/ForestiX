import XCTest
@testable import Common

final class CameraPoseSpeedGuardTests: XCTestCase {
    func testRadialShiftCanBeImplausibleBelowSpeedCeiling() {
        var g = CameraPoseSpeedGuard()
        for t in [0.0, 0.1] {
            XCTAssertEqual(g.observe(position: .init(15,0,0), timestamp:t, trackedAnchor:.zero), .usable)
        }
        // 0.75 m / 0.2 s is below 4 m/s, but inconsistent with the stationary prehistory.
        XCTAssertEqual(g.observe(position:.init(15.75,0,0), timestamp:0.3, trackedAnchor:.zero), .checking)
        for i in 4...23 {
            XCTAssertEqual(g.observe(position:.init(15.75,0,0), timestamp:Double(i)/10, trackedAnchor:.zero), .checking)
        }
        XCTAssertEqual(g.observe(position:.init(15.75,0,0), timestamp:2.5, trackedAnchor:.zero), .retake)
    }

    func testStoppingDoesNotInvalidateAnUnchangedRange() {
        var g = CameraPoseSpeedGuard()
        for (t,x) in [(0.0,15 as Float),(0.2,15.6),(0.4,16.2),(0.6,16.2)] {
            XCTAssertEqual(g.observe(position:.init(x,0,0), timestamp:t, trackedAnchor:.zero), .usable)
        }
    }

    func testWalkingFramesUseSensorTime() {
        var g = CameraPoseSpeedGuard()
        XCTAssertEqual(g.observe(position: .init(15, 0, 0), timestamp: 0.000, trackedAnchor: .init(0, 0, 0)), .usable)
        XCTAssertEqual(g.observe(position: .init(15.1, 0, 0), timestamp: 0.100, trackedAnchor: .init(0, 0, 0)), .usable)
        XCTAssertEqual(g.observe(position: .init(15.2, 0, 0), timestamp: 0.200, trackedAnchor: .init(0, 0, 0)), .usable)
        XCTAssertEqual(g.observe(position: .init(15.3, 0, 0), timestamp: 0.300, trackedAnchor: .init(0, 0, 0)), .usable)
    }

    func testWalkingExactlyAtLimit() {
        var g = CameraPoseSpeedGuard()
        XCTAssertEqual(g.observe(position: .init(15, 0, 0), timestamp: 0.000, trackedAnchor: .init(0, 0, 0)), .usable)
        XCTAssertEqual(g.observe(position: .init(15.5, 0, 0), timestamp: 0.125, trackedAnchor: .init(0, 0, 0)), .usable)
        XCTAssertEqual(g.observe(position: .init(16, 0, 0), timestamp: 0.250, trackedAnchor: .init(0, 0, 0)), .usable)
    }

    func testCommonOriginTranslation() {
        var g = CameraPoseSpeedGuard()
        XCTAssertEqual(g.observe(position: .init(15, 0, 0), timestamp: 0.000, trackedAnchor: .init(0, 0, 0)), .usable)
        XCTAssertEqual(g.observe(position: .init(15, 0, 0), timestamp: 0.100, trackedAnchor: .init(0, 0, 0)), .usable)
        XCTAssertEqual(g.observe(position: .init(16, 0, 0), timestamp: 0.133, trackedAnchor: .init(1, 0, 0)), .usable)
        XCTAssertEqual(g.observe(position: .init(16, 0, 0), timestamp: 0.166, trackedAnchor: .init(1, 0, 0)), .usable)
    }

    func testVerticalOnlyStep() {
        var g = CameraPoseSpeedGuard()
        XCTAssertEqual(g.observe(position: .init(15, 0, 0), timestamp: 0.000, trackedAnchor: .init(0, 0, 0)), .usable)
        XCTAssertEqual(g.observe(position: .init(15, 0, 0), timestamp: 0.100, trackedAnchor: .init(0, 0, 0)), .usable)
        XCTAssertEqual(g.observe(position: .init(15, 0.25, 0), timestamp: 0.133, trackedAnchor: .init(0, 0, 0)), .usable)
        XCTAssertEqual(g.observe(position: .init(15, 0.25, 0), timestamp: 0.167, trackedAnchor: .init(0, 0, 0)), .usable)
    }

    func testCameraAndAnchorOffsetDoesNotHideChangedRange() {
        var g = CameraPoseSpeedGuard()
        XCTAssertEqual(g.observe(position: .init(15, 0, 0), timestamp: 0.000, trackedAnchor: .init(0, 0, 0)), .usable)
        XCTAssertEqual(g.observe(position: .init(15, 0, 0), timestamp: 0.100, trackedAnchor: .init(0, 0, 0)), .usable)
        for ms in stride(from: 133, through: 2133, by: 100) {
            XCTAssertEqual(g.observe(position: .init(17, 0, 0), timestamp: Double(ms) / 1000, trackedAnchor: .init(1, 0, 0)), .checking)
        }
        XCTAssertEqual(g.observe(position: .init(17, 0, 0), timestamp: 2.233, trackedAnchor: .init(1, 0, 0)), .retake)
        XCTAssertEqual(g.observe(position: .init(17, 0, 0), timestamp: 2.333, trackedAnchor: .init(1, 0, 0)), .retake)
    }

    func testAnchorOnlyRangeJump() {
        var g = CameraPoseSpeedGuard()
        XCTAssertEqual(g.observe(position: .init(15, 0, 0), timestamp: 0.000, trackedAnchor: .init(0, 0, 0)), .usable)
        XCTAssertEqual(g.observe(position: .init(15, 0, 0), timestamp: 0.100, trackedAnchor: .init(0, 0, 0)), .usable)
        XCTAssertEqual(g.observe(position: .init(15, 0, 0), timestamp: 0.140, trackedAnchor: .init(1, 0, 0)), .checking)
        for ms in stride(from: 250, through: 2150, by: 100) {
            XCTAssertEqual(g.observe(position: .init(15, 0, 0), timestamp: Double(ms) / 1000, trackedAnchor: .init(1, 0, 0)), .checking)
        }
        XCTAssertEqual(g.observe(position: .init(15, 0, 0), timestamp: 2.250, trackedAnchor: .init(1, 0, 0)), .retake)
        XCTAssertEqual(g.observe(position: .init(15, 0, 0), timestamp: 2.350, trackedAnchor: .init(1, 0, 0)), .retake)
    }

    func testSingleSpikeNeedsTwoMeasuredReturns() {
        var g = CameraPoseSpeedGuard()
        XCTAssertEqual(g.observe(position: .init(15, 0, 0), timestamp: 0.000, trackedAnchor: .init(0, 0, 0)), .usable)
        XCTAssertEqual(g.observe(position: .init(15.1, 0, 0), timestamp: 0.100, trackedAnchor: .init(0, 0, 0)), .usable)
        XCTAssertEqual(g.observe(position: .init(16.7, 0, 0), timestamp: 0.200, trackedAnchor: .init(0, 0, 0)), .checking)
        XCTAssertEqual(g.observe(position: .init(15.3, 0, 0), timestamp: 0.300, trackedAnchor: .init(0, 0, 0)), .checking)
        XCTAssertEqual(g.observe(position: .init(15.3, 0, 0), timestamp: 0.300, trackedAnchor: .init(0, 0, 0)), .checking)
        XCTAssertEqual(g.observe(position: .init(15.4, 0, 0), timestamp: 0.400, trackedAnchor: .init(0, 0, 0)), .usable)
    }

    func testStaleAndDuplicateFramesCannotConfirm() {
        var g = CameraPoseSpeedGuard()
        XCTAssertEqual(g.observe(position: .init(15, 0, 0), timestamp: 0.000, trackedAnchor: .init(0, 0, 0)), .usable)
        XCTAssertEqual(g.observe(position: .init(15, 0, 0), timestamp: 0.100, trackedAnchor: .init(0, 0, 0)), .usable)
        XCTAssertEqual(g.observe(position: .init(16, 0, 0), timestamp: 0.133, trackedAnchor: .init(0, 0, 0)), .checking)
        XCTAssertEqual(g.observe(position: .init(15, 0, 0), timestamp: 0.166, trackedAnchor: .init(0, 0, 0)), .checking)
        XCTAssertEqual(g.observe(position: .init(15, 0, 0), timestamp: 0.120, trackedAnchor: .init(0, 0, 0)), .checking)
        XCTAssertEqual(g.observe(position: .init(15, 0, 0), timestamp: 0.166, trackedAnchor: .init(0, 0, 0)), .checking)
        XCTAssertEqual(g.observe(position: .init(15, 0, 0), timestamp: 0.199, trackedAnchor: .init(0, 0, 0)), .usable)
        XCTAssertEqual(g.observe(position: .init(15, 0, 0), timestamp: 0.199, trackedAnchor: .init(0, 0, 0)), .usable)
        XCTAssertEqual(g.observe(position: .init(15, 0, 0), timestamp: 0.166, trackedAnchor: .init(0, 0, 0)), .ignored)
    }

    func testInPlaceRecheckAfterMultipleBadFrames() {
        var g = CameraPoseSpeedGuard()
        XCTAssertEqual(g.observe(position: .init(15, 0, 0), timestamp: 0.000, trackedAnchor: .init(0, 0, 0)), .usable)
        XCTAssertEqual(g.observe(position: .init(15, 0, 0), timestamp: 0.100, trackedAnchor: .init(0, 0, 0)), .usable)
        XCTAssertEqual(g.observe(position: .init(16, 0, 0), timestamp: 0.133, trackedAnchor: .init(0, 0, 0)), .checking)
        XCTAssertEqual(g.observe(position: .init(16, 0, 0), timestamp: 0.166, trackedAnchor: .init(0, 0, 0)), .checking)
        XCTAssertEqual(g.observe(position: .init(16, 0, 0), timestamp: 0.200, trackedAnchor: .init(0, 0, 0)), .checking)
        for ms in stride(from: 250, through: 550, by: 100) {
            XCTAssertEqual(g.observe(position: .init(15, 0, 0), timestamp: Double(ms) / 1000, trackedAnchor: .init(0, 0, 0)), .checking)
        }
        XCTAssertEqual(g.observe(position: .init(15, 0, 0), timestamp: 0.650, trackedAnchor: .init(0, 0, 0)), .usable)
        XCTAssertEqual(g.observe(position: .init(15, 0, 0), timestamp: 0.750, trackedAnchor: .init(0, 0, 0)), .usable)
    }

    func testSettledButOffsetMustRetake() {
        var g = CameraPoseSpeedGuard()
        XCTAssertEqual(g.observe(position: .init(15, 0, 0), timestamp: 0.000, trackedAnchor: .init(0, 0, 0)), .usable)
        XCTAssertEqual(g.observe(position: .init(15, 0, 0), timestamp: 0.100, trackedAnchor: .init(0, 0, 0)), .usable)
        XCTAssertEqual(g.observe(position: .init(16, 0, 0), timestamp: 0.133, trackedAnchor: .init(0, 0, 0)), .checking)
        for ms in stride(from: 250, through: 2150, by: 100) {
            XCTAssertEqual(g.observe(position: .init(16, 0, 0), timestamp: Double(ms) / 1000, trackedAnchor: .init(0, 0, 0)), .checking)
        }
        XCTAssertEqual(g.observe(position: .init(16, 0, 0), timestamp: 2.250, trackedAnchor: .init(0, 0, 0)), .retake)
        XCTAssertEqual(g.observe(position: .init(16, 0, 0), timestamp: 2.350, trackedAnchor: .init(0, 0, 0)), .retake)
        XCTAssertEqual(g.observe(position: .init(15, 0, 0), timestamp: 2.500, trackedAnchor: .init(0, 0, 0)), .retake)
    }

    func testSidewaysRotationKeepsHorizontalRange() {
        var g = CameraPoseSpeedGuard()
        XCTAssertEqual(g.observe(position: .init(15, 0, 0), timestamp: 0.000, trackedAnchor: .init(0, 0, 0)), .usable)
        XCTAssertEqual(g.observe(position: .init(15, 0, 0), timestamp: 0.100, trackedAnchor: .init(0, 0, 0)), .usable)
        XCTAssertEqual(g.observe(position: .init(0, 0, 15), timestamp: 0.133, trackedAnchor: .init(0, 0, 0)), .checking)
        XCTAssertEqual(g.observe(position: .init(0, 0, 15), timestamp: 0.166, trackedAnchor: .init(0, 0, 0)), .checking)
        XCTAssertEqual(g.observe(position: .init(0, 0, 15), timestamp: 0.200, trackedAnchor: .init(0, 0, 0)), .usable)
    }

    func testCommonOriginChangeWhileWalking() {
        var g = CameraPoseSpeedGuard()
        XCTAssertEqual(g.observe(position: .init(15, 0, 0), timestamp: 0.000, trackedAnchor: .init(0, 0, 0)), .usable)
        XCTAssertEqual(g.observe(position: .init(15.1, 0, 0), timestamp: 0.100, trackedAnchor: .init(0, 0, 0)), .usable)
        XCTAssertEqual(g.observe(position: .init(16.2, 0, 0), timestamp: 0.200, trackedAnchor: .init(1, 0, 0)), .usable)
        XCTAssertEqual(g.observe(position: .init(16.3, 0, 0), timestamp: 0.300, trackedAnchor: .init(1, 0, 0)), .usable)
        XCTAssertEqual(g.observe(position: .init(16.4, 0, 0), timestamp: 0.400, trackedAnchor: .init(1, 0, 0)), .usable)
    }

    func testSecondSpikeAfterApparentReturn() {
        var g = CameraPoseSpeedGuard()
        XCTAssertEqual(g.observe(position: .init(15, 0, 0), timestamp: 0.000, trackedAnchor: .init(0, 0, 0)), .usable)
        XCTAssertEqual(g.observe(position: .init(15, 0, 0), timestamp: 0.100, trackedAnchor: .init(0, 0, 0)), .usable)
        XCTAssertEqual(g.observe(position: .init(17, 0, 0), timestamp: 0.133, trackedAnchor: .init(0, 0, 0)), .checking)
        XCTAssertEqual(g.observe(position: .init(15, 0, 0), timestamp: 0.166, trackedAnchor: .init(0, 0, 0)), .checking)
        XCTAssertEqual(g.observe(position: .init(17, 0, 0), timestamp: 0.199, trackedAnchor: .init(0, 0, 0)), .checking)
        for ms in stride(from: 300, through: 2100, by: 100) {
            XCTAssertEqual(g.observe(position: .init(17, 0, 0), timestamp: Double(ms) / 1000, trackedAnchor: .init(0, 0, 0)), .checking)
        }
        XCTAssertEqual(g.observe(position: .init(17, 0, 0), timestamp: 2.300, trackedAnchor: .init(0, 0, 0)), .retake)
        XCTAssertEqual(g.observe(position: .init(17, 0, 0), timestamp: 2.400, trackedAnchor: .init(0, 0, 0)), .retake)
    }

    func testShortLossKeepsProgress() {
        var g = CameraPoseSpeedGuard()
        XCTAssertEqual(g.observe(position: .init(15, 0, 0), timestamp: 0.000, trackedAnchor: .init(0, 0, 0)), .usable)
        XCTAssertEqual(g.observe(position: .init(15, 0, 0), timestamp: 0.100, trackedAnchor: .init(0, 0, 0)), .usable)
        XCTAssertEqual(g.interrupt(), .checking)
        XCTAssertEqual(g.observe(position: .init(15, 0, 0), timestamp: 0.166, trackedAnchor: .init(0, 0, 0)), .checking)
        XCTAssertEqual(g.observe(position: .init(15, 0, 0), timestamp: 0.199, trackedAnchor: .init(0, 0, 0)), .usable)
    }

    func testGapCannotConfirmByItself() {
        var g = CameraPoseSpeedGuard()
        XCTAssertEqual(g.observe(position: .init(15, 0, 0), timestamp: 0.000, trackedAnchor: .init(0, 0, 0)), .usable)
        XCTAssertEqual(g.observe(position: .init(15, 0, 0), timestamp: 0.100, trackedAnchor: .init(0, 0, 0)), .usable)
        for ms in stride(from: 900, through: 1300, by: 100) {
            XCTAssertEqual(g.observe(position: .init(15, 0, 0), timestamp: Double(ms) / 1000, trackedAnchor: .init(0, 0, 0)), .checking)
        }
        XCTAssertEqual(g.observe(position: .init(15, 0, 0), timestamp: 1.400, trackedAnchor: .init(0, 0, 0)), .usable)
        XCTAssertEqual(g.observe(position: .init(15, 0, 0), timestamp: 1.500, trackedAnchor: .init(0, 0, 0)), .usable)
    }

    func testMissingAnchorNeverConfirms() {
        var g = CameraPoseSpeedGuard()
        XCTAssertEqual(g.observe(position: .init(15, 0, 0), timestamp: 0.000, trackedAnchor: .init(0, 0, 0)), .usable)
        XCTAssertEqual(g.observe(position: .init(15, 0, 0), timestamp: 0.100, trackedAnchor: .init(0, 0, 0)), .usable)
        XCTAssertEqual(g.observe(position: .init(16, 0, 0), timestamp: 0.133, trackedAnchor: .init(0, 0, 0)), .checking)
        XCTAssertEqual(g.observe(position: .init(15, 0, 0), timestamp: 0.166, trackedAnchor: nil), .checking)
        XCTAssertEqual(g.observe(position: .init(15, 0, 0), timestamp: 0.566, trackedAnchor: nil), .checking)
        XCTAssertEqual(g.observe(position: .init(15, 0, 0), timestamp: 2.200, trackedAnchor: nil), .checking)
        for ms in stride(from: 2300, through: 2600, by: 100) {
            XCTAssertEqual(g.observe(position: .init(15, 0, 0), timestamp: Double(ms) / 1000, trackedAnchor: .init(0, 0, 0)), .checking)
        }
        XCTAssertEqual(g.observe(position: .init(15, 0, 0), timestamp: 2.700, trackedAnchor: .init(0, 0, 0)), .usable)
        XCTAssertEqual(g.observe(position: .init(15, 0, 0), timestamp: 2.800, trackedAnchor: .init(0, 0, 0)), .usable)
    }

    func testNoPreSpikeDirection() {
        var g = CameraPoseSpeedGuard()
        XCTAssertEqual(g.observe(position: .init(15, 0, 0), timestamp: 0.000, trackedAnchor: .init(0, 0, 0)), .usable)
        XCTAssertEqual(g.observe(position: .init(18, 0, 0), timestamp: 0.100, trackedAnchor: .init(0, 0, 0)), .checking)
        for ms in stride(from: 200, through: 500, by: 100) {
            XCTAssertEqual(g.observe(position: .init(15, 0, 0), timestamp: Double(ms) / 1000, trackedAnchor: .init(0, 0, 0)), .checking)
        }
        XCTAssertEqual(g.observe(position: .init(15, 0, 0), timestamp: 0.600, trackedAnchor: .init(0, 0, 0)), .usable)
        XCTAssertEqual(g.observe(position: .init(15, 0, 0), timestamp: 0.700, trackedAnchor: .init(0, 0, 0)), .usable)
    }

    func testInvalidFrameDuringCheck() {
        var g = CameraPoseSpeedGuard()
        XCTAssertEqual(g.observe(position: .init(15, 0, 0), timestamp: 0.000, trackedAnchor: .init(0, 0, 0)), .usable)
        XCTAssertEqual(g.observe(position: .init(15, 0, 0), timestamp: 0.100, trackedAnchor: .init(0, 0, 0)), .usable)
        XCTAssertEqual(g.observe(position: .init(16, 0, 0), timestamp: 0.133, trackedAnchor: .init(0, 0, 0)), .checking)
        XCTAssertEqual(g.observe(position: .init(Float.nan, 0, 0), timestamp: 0.166, trackedAnchor: .init(0, 0, 0)), .checking)
        for ms in stride(from: 1000, through: 1300, by: 100) {
            XCTAssertEqual(g.observe(position: .init(15, 0, 0), timestamp: Double(ms) / 1000, trackedAnchor: .init(0, 0, 0)), .checking)
        }
        XCTAssertEqual(g.observe(position: .init(15, 0, 0), timestamp: 1.400, trackedAnchor: .init(0, 0, 0)), .usable)
        XCTAssertEqual(g.observe(position: .init(15, 0, 0), timestamp: 1.500, trackedAnchor: .init(0, 0, 0)), .usable)
    }

    func testDriftDuringMissingObservationsDoesNotRelock() {
        var g = CameraPoseSpeedGuard()
        XCTAssertEqual(g.observe(position: .init(15, 0, 0), timestamp: 0.000, trackedAnchor: .init(0, 0, 0)), .usable)
        XCTAssertEqual(g.observe(position: .init(15, 0, 0), timestamp: 0.100, trackedAnchor: .init(0, 0, 0)), .usable)
        XCTAssertEqual(g.interrupt(), .checking)
        for ms in stride(from: 5000, through: 6900, by: 100) {
            XCTAssertEqual(g.observe(position: .init(16, 0, 0), timestamp: Double(ms) / 1000, trackedAnchor: .init(0, 0, 0)), .checking)
        }
        XCTAssertEqual(g.observe(position: .init(16, 0, 0), timestamp: 7.000, trackedAnchor: .init(0, 0, 0)), .retake)
        XCTAssertEqual(g.observe(position: .init(16, 0, 0), timestamp: 7.100, trackedAnchor: .init(0, 0, 0)), .retake)
        XCTAssertEqual(g.interrupt(), .retake)
        g.reset()
        XCTAssertEqual(g.observe(position: .init(55, 0, 0), timestamp: 8.000, trackedAnchor: .init(0, 0, 0)), .usable)
    }

    func testStableWrongRangeCannotWaitForPredictionToCatchUp() {
        var g = CameraPoseSpeedGuard()
        XCTAssertEqual(g.observe(position: .init(14.8, 0, 0), timestamp: 0.000, trackedAnchor: .init(0, 0, 0)), .usable)
        XCTAssertEqual(g.observe(position: .init(15, 0, 0), timestamp: 0.100, trackedAnchor: .init(0, 0, 0)), .usable)
        XCTAssertEqual(g.observe(position: .init(15.21, 0, 0), timestamp: 0.133, trackedAnchor: .init(0, 0, 0)), .checking)
        XCTAssertEqual(g.observe(position: .init(15.21, 0, 0), timestamp: 0.166, trackedAnchor: .init(0, 0, 0)), .checking)
        XCTAssertEqual(g.observe(position: .init(15.21, 0, 0), timestamp: 0.200, trackedAnchor: .init(0, 0, 0)), .checking)
    }

    func testMissingFirstAnchorCannotSeedHeight() {
        var g = CameraPoseSpeedGuard()
        XCTAssertEqual(g.observe(position: .init(15, 0, 0), timestamp: 0.000, trackedAnchor: nil), .ignored)
        XCTAssertEqual(g.observe(position: .init(15, 0, 0), timestamp: 0.100, trackedAnchor: .init(0, 0, 0)), .usable)
    }

    func testAbsoluteCoordinatesCannotMixFrames() {
        var g = CameraPoseSpeedGuard()
        XCTAssertEqual(g.observe(position: .init(15, 0, 0), timestamp: 0.000, trackedAnchor: .init(0, 0, 0)), .usable)
        XCTAssertEqual(g.observe(position: .init(15, 0, 0), timestamp: 0.100, trackedAnchor: .init(0, 0, 0)), .usable)
        XCTAssertEqual(g.observe(position: .init(15, 0, 0), timestamp: 0.100, trackedAnchor: .init(0.2, 0, 0)), .ignored)
        XCTAssertEqual(g.observe(position: .init(15, 0, 0), timestamp: 0.100, trackedAnchor: .init(0, 0, 0)), .usable)
    }
}
