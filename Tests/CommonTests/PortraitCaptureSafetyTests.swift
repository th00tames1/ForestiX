import XCTest
@testable import Common

final class PortraitCaptureSafetyTests: XCTestCase {
    private func status(pad: Bool = true, modern: Bool = true, active: Bool = true,
                        portrait: Bool = true, full: Bool = true, locked: Bool = true,
                        transitioning: Bool = false) -> PortraitCaptureSafety.Status {
        PortraitCaptureSafety.status(isPad: pad, requiresSceneLock: modern,
            isActive: active, isPortrait: portrait, isFullScreen: full,
            isLocked: locked, isTransitioning: transitioning)
    }
    func testModernIPadRequiresActualLockNotJustPortraitAspect() {
        XCTAssertEqual(status(locked: false), .needsLock)
        XCTAssertEqual(status(), .ready)
    }
    func testWindowedInactiveAndRotatingScenesFailClosed() {
        XCTAssertEqual(status(full: false), .needsFullScreen)
        XCTAssertEqual(status(active: false), .inactive)
        XCTAssertEqual(status(transitioning: true), .transitioning)
        XCTAssertEqual(status(portrait: false), .needsPortrait)
    }
    func testEarlierIPadUsesPortraitAndFullScreenWithoutNewAPI() {
        XCTAssertEqual(status(modern: false, locked: false), .ready)
        XCTAssertEqual(status(modern: false, full: false, locked: false), .needsFullScreen)
        XCTAssertEqual(status(modern: false, portrait: false, locked: false), .needsPortrait)
    }
    func testPhoneDoesNotRequireIPadLockAPI() {
        XCTAssertEqual(status(pad: false, full: false, locked: false), .ready)
        XCTAssertEqual(status(pad: false, portrait: false), .needsPortrait)
    }
    func testOldOrUnstampedFramesCannotSurviveGeometryChanges() {
        XCTAssertFalse(PortraitCaptureSafety.acceptsFrame(currentRevision: 2, frameRevision: 1))
        XCTAssertFalse(PortraitCaptureSafety.acceptsFrame(currentRevision: 2, frameRevision: nil))
        XCTAssertTrue(PortraitCaptureSafety.acceptsFrame(currentRevision: 2, frameRevision: 2))
    }
}
