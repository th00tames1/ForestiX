import XCTest
@testable import Common

final class AutoSafetyPolicyTests: XCTestCase {
    func testDeadlineIsThirtySecondsAndDefersDuringCapture() {
        XCTAssertFalse(AutoSafetyPolicy.shouldReturnToAdjust(startedAt: 100, now: 129.99, isAiming: true))
        XCTAssertTrue(AutoSafetyPolicy.shouldReturnToAdjust(startedAt: 100, now: 130, isAiming: true))
        XCTAssertFalse(AutoSafetyPolicy.shouldReturnToAdjust(startedAt: 100, now: 131, isAiming: false))
        XCTAssertTrue(AutoSafetyPolicy.shouldReturnToAdjust(startedAt: 100, now: 132, isAiming: true))
    }
    func testFreshEntryGetsItsOwnThirtySeconds() {
        XCTAssertFalse(AutoSafetyPolicy.shouldReturnToAdjust(startedAt: 140, now: 141, isAiming: true))
        XCTAssertTrue(AutoSafetyPolicy.shouldReturnToAdjust(startedAt: 140, now: 170, isAiming: true))
        XCTAssertFalse(AutoSafetyPolicy.shouldReturnToAdjust(startedAt: .nan, now: 170, isAiming: true))
    }
}
