import XCTest
@testable import Common

final class DeveloperModeUnlockTests: XCTestCase {
    func testOldPublicToggleCannotBypassNewUnlock() {
        XCTAssertFalse(DeveloperModeUnlock.isEnabled(savedEnabled: true, gestureUnlocked: false))
        XCTAssertFalse(DeveloperModeUnlock.isEnabled(savedEnabled: false, gestureUnlocked: true))
        XCTAssertTrue(DeveloperModeUnlock.isEnabled(savedEnabled: true, gestureUnlocked: true))
    }
    func testUnlocksOnlyOnSeventhConsecutiveTap() {
        var gesture = DeveloperModeUnlock()
        for i in 0..<6 { XCTAssertFalse(gesture.tap(at: Double(i) * 0.2)) }
        XCTAssertTrue(gesture.tap(at: 1.2))
        XCTAssertFalse(gesture.tap(at: 1.4))
    }

    func testSlowTapsDoNotAccumulate() {
        var gesture = DeveloperModeUnlock()
        for i in 0..<20 { XCTAssertFalse(gesture.tap(at: Double(i) * 1.3)) }
    }

    func testPauseAndExplicitResetRestartSequence() {
        var gesture = DeveloperModeUnlock()
        for i in 0..<6 { XCTAssertFalse(gesture.tap(at: Double(i) * 0.1)) }
        XCTAssertFalse(gesture.tap(at: 3))
        gesture.reset()
        for i in 0..<6 { XCTAssertFalse(gesture.tap(at: 4 + Double(i) * 0.1)) }
        XCTAssertTrue(gesture.tap(at: 4.6))
    }

    func testInvalidOrBackwardsTimeCannotCompleteOldSequence() {
        var gesture = DeveloperModeUnlock()
        for i in 0..<6 { XCTAssertFalse(gesture.tap(at: 10 + Double(i) * 0.1)) }
        XCTAssertFalse(gesture.tap(at: .nan))
        XCTAssertFalse(gesture.tap(at: 11))
        XCTAssertFalse(gesture.tap(at: 5))
        for i in 1..<6 { XCTAssertFalse(gesture.tap(at: 5 + Double(i) * 0.1)) }
        XCTAssertTrue(gesture.tap(at: 5.6))
    }
}
