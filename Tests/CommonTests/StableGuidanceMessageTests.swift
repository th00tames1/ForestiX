import XCTest
@testable import Common

final class StableGuidanceMessageTests: XCTestCase {
    func testNoStemAlternatingMessagesDoNotFlicker() {
        var filter = StableGuidanceMessage(initial: "Finding stem…", now: 0)
        for tick in 1...40 {
            let message = tick.isMultiple(of: 2) ? "Finding stem…" : "No stem found"
            XCTAssertEqual(filter.update(message, now: Double(tick) / 8), "Finding stem…")
        }
    }
    func testPersistentFailureAndRecoveryAreEventuallyVisible() {
        var filter = StableGuidanceMessage(initial: "Finding stem…", now: 0)
        XCTAssertEqual(filter.update("AI unavailable", now: 0.25), "Finding stem…")
        XCTAssertEqual(filter.update("AI unavailable", now: 1.49), "Finding stem…")
        XCTAssertEqual(filter.update("AI unavailable", now: 1.5), "AI unavailable")
        XCTAssertEqual(filter.update(nil, now: 1.6), "AI unavailable")
        XCTAssertEqual(filter.update(nil, now: 2.7), "AI unavailable")
        XCTAssertNil(filter.update(nil, now: 3))
    }
    func testClockResetDoesNotLeaveGuidanceStuck() {
        var filter = StableGuidanceMessage(initial: "Finding", now: 10)
        XCTAssertEqual(filter.update("Error", now: 0), "Error")
    }
}
