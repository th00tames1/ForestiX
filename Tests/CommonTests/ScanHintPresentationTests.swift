import XCTest
@testable import Common

final class ScanHintPresentationTests: XCTestCase {
    func testIntroductionsDoNotReturnAfterLockOrModeChanges() {
        var hints = ScanHintPresentation()
        XCTAssertTrue(hints.begin("auto"))
        XCTAssertTrue(hints.isVisible("auto"))
        hints.dismiss("auto")
        XCTAssertFalse(hints.begin("auto"))
        XCTAssertTrue(hints.begin("adjust"))
        XCTAssertFalse(hints.begin("auto"))
        XCTAssertNil(hints.visibleKey)
        XCTAssertEqual(ScanHintPresentation.durationSeconds, 4)
    }
    func testCancelledOldTimerCannotHideNewStage() {
        var hints = ScanHintPresentation()
        hints.begin("walk"); hints.begin("top")
        hints.dismiss("walk")
        XCTAssertTrue(hints.isVisible("top"))
        XCTAssertFalse(hints.isVisible("walk"))
    }
    func testInactiveStageDoesNotConsumeIntroduction() {
        var hints = ScanHintPresentation()
        XCTAssertFalse(hints.begin(nil))
        XCTAssertFalse(hints.isVisible(nil))
        XCTAssertTrue(hints.begin("auto"))
    }
}
