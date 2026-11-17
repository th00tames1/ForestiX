import XCTest
@testable import Common

final class CameraPoseSpeedGuardTests: XCTestCase {
    func testQueuedWalkingFramesUseTheirOwnClock() {
        var guardrail = CameraPoseSpeedGuard()
        // Delivered back-to-back; native timestamps are still 100 ms apart.
        for i in 0...30 {
            XCTAssertFalse(guardrail.observe(position: .init(Float(i) / 10, 0, 0), timestamp: 10 + Double(i) / 10))
        }
    }
    func testRealJumpAndExistingFourMeterPerSecondLimit() {
        var guardrail = CameraPoseSpeedGuard()
        XCTAssertFalse(guardrail.observe(position: .zero, timestamp: 1))
        XCTAssertFalse(guardrail.observe(position: .init(0.4, 0, 0), timestamp: 1.1))
        XCTAssertTrue(guardrail.observe(position: .init(1.4, 0, 0), timestamp: 1.2))
        XCTAssertEqual(CameraPoseSpeedGuard.maximumSpeedMetersPerSecond, 4)
    }
    func testDuplicateAndOutOfOrderFramesDoNotRebasePosition() {
        var guardrail = CameraPoseSpeedGuard()
        XCTAssertFalse(guardrail.observe(position: .zero, timestamp: 1))
        XCTAssertFalse(guardrail.observe(position: .init(100, 0, 0), timestamp: 1))
        XCTAssertFalse(guardrail.observe(position: .init(100, 0, 0), timestamp: 0.9))
        XCTAssertFalse(guardrail.observe(position: .init(0.1, 0, 0), timestamp: 1.1))
    }
    func testOutagesInvalidFramesAndRetakesResetSamplePair() {
        var guardrail = CameraPoseSpeedGuard()
        XCTAssertFalse(guardrail.observe(position: .zero, timestamp: 1))
        XCTAssertFalse(guardrail.observe(position: .init(100, 0, 0), timestamp: 2))
        XCTAssertFalse(guardrail.observe(position: nil, timestamp: nil))
        XCTAssertFalse(guardrail.observe(position: .zero, timestamp: 2.1))
        guardrail.reset()
        XCTAssertFalse(guardrail.observe(position: .init(100, 0, 0), timestamp: 2.2))
        XCTAssertFalse(guardrail.observe(position: .zero, timestamp: .nan))
    }
}
