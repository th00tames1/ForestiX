import XCTest
@testable import Common

final class DBHCaptureModeTests: XCTestCase {
    func testMissingAndUnknownPreferencesDefaultToSingleFrame() {
        XCTAssertEqual(DBHCaptureMode.fromRaw(nil), .single)
        XCTAssertEqual(DBHCaptureMode.fromRaw("unknown"), .single)
        XCTAssertEqual(DBHCaptureMode.fromRaw("multi5"), .multi5)
    }

    func testSavedMultiFramePreferenceCannotAffectNormalUsers() {
        XCTAssertEqual(DBHCaptureMode.multi5.frameCount(developerMode: false), 1)
        XCTAssertEqual(DBHCaptureMode.single.frameCount(developerMode: false), 1)
    }

    func testDeveloperModeStillDefaultsToSingleUnlessExplicitlySelected() {
        XCTAssertEqual(DBHCaptureMode.single.frameCount(developerMode: true), 1)
        XCTAssertEqual(DBHCaptureMode.multi5.frameCount(developerMode: true), 5)
    }
}
