import XCTest
@testable import Common

final class AutoSafetyPolicyTests: XCTestCase {
    func testDeadlineIsSixtySecondsAndDefersDuringCapture() {
        XCTAssertEqual(AutoSafetyPolicy.maximumSeconds, 60)
        XCTAssertFalse(AutoSafetyPolicy.shouldReturnToAdjust(startedAt: 100, now: 130, isAiming: true))
        XCTAssertFalse(AutoSafetyPolicy.shouldReturnToAdjust(startedAt: 100, now: 159.99, isAiming: true))
        XCTAssertTrue(AutoSafetyPolicy.shouldReturnToAdjust(startedAt: 100, now: 160, isAiming: true))
        XCTAssertFalse(AutoSafetyPolicy.shouldReturnToAdjust(startedAt: 100, now: 161, isAiming: false))
        XCTAssertTrue(AutoSafetyPolicy.shouldReturnToAdjust(startedAt: 100, now: 162, isAiming: true))
    }
    func testNextTreeOrFreshEntryGetsItsOwnSixtySeconds() {
        XCTAssertFalse(AutoSafetyPolicy.shouldReturnToAdjust(startedAt: 140, now: 141, isAiming: true))
        XCTAssertFalse(AutoSafetyPolicy.shouldReturnToAdjust(startedAt: 140, now: 170, isAiming: true))
        XCTAssertTrue(AutoSafetyPolicy.shouldReturnToAdjust(startedAt: 140, now: 200, isAiming: true))
        XCTAssertFalse(AutoSafetyPolicy.shouldReturnToAdjust(startedAt: .nan, now: 200, isAiming: true))
    }
}
