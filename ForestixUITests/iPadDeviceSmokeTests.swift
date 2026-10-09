import XCTest
import UIKit

/// Opt-in hardware smoke checks. These never accept a measurement, export
/// private records, or change measurement settings. Physical rotation and
/// tree-measurement accuracy still require the field checklist.
final class iPadDeviceSmokeTests: XCTestCase {
    @MainActor
    func testMapSettingsAndDBHStayPortrait() throws {
        try XCTSkipUnless(ProcessInfo.processInfo.environment["FORESTIX_IPAD_SMOKE"] == "1",
                          "Run explicitly on a connected LiDAR iPad.")
        try XCTSkipUnless(UIDevice.current.userInterfaceIdiom == .pad)
        let environment = ProcessInfo.processInfo.environment
        let width = try XCTUnwrap(environment["FORESTIX_IPAD_WIDTH"].flatMap { Double($0) },
                                  "Supply the connected device's native portrait width in points.")
        let height = try XCTUnwrap(environment["FORESTIX_IPAD_HEIGHT"].flatMap { Double($0) },
                                   "Supply the connected device's native portrait height in points.")
        let expectedScreen = CGSize(width: width, height: height)
        continueAfterFailure = false
        let app = XCUIApplication(bundleIdentifier: "asfl-Forestix")
        defer { XCUIDevice.shared.orientation = .portrait }
        XCUIDevice.shared.orientation = .landscapeRight
        app.launch()
        let settings = app.buttons["mapHome.settings"]
        XCTAssertTrue(settings.waitForExistence(timeout: 20))
        assertPortraitWindow(app, expectedScreen: expectedScreen)

        for orientation in [UIDeviceOrientation.landscapeLeft, .landscapeRight, .portraitUpsideDown] {
            XCUIDevice.shared.orientation = orientation
            assertPortraitWindow(app, expectedScreen: expectedScreen)
        }
        XCUIDevice.shared.orientation = .portrait
        settings.tap()
        XCTAssertTrue(app.navigationBars["Settings"].waitForExistence(timeout: 10))
        // Inspect the complete Settings surface, without tapping any data or
        // settings controls. The device's legacy developer flag must not
        // bypass the new explicit unlock requirement.
        for _ in 0..<8 { app.swipeUp() }
        XCTAssertFalse(app.switches["settings.developerMode"].exists)
        XCTAssertFalse(app.switches["settings.dbhMultiFrame"].exists)
        app.buttons["Close"].tap()

        let measure = app.buttons["mapHome.measure"]
        XCTAssertTrue(measure.waitForExistence(timeout: 10))
        measure.tap()
        let diameter = app.buttons["mapHome.choose.dbh"]
        XCTAssertTrue(diameter.waitForExistence(timeout: 10))
        diameter.tap()
        let ground = app.buttons["dbhScan.breastHeightBase"]
        XCTAssertTrue(ground.waitForExistence(timeout: 20))
        XCTAssertEqual(ground.label, "Set ground")
        assertPortraitWindow(app, expectedScreen: expectedScreen)
        XCUIDevice.shared.orientation = .landscapeLeft
        assertPortraitWindow(app, expectedScreen: expectedScreen)
        XCTAssertTrue(ground.exists)

        // Close the camera with no capture and no accepted record.
        let back = app.buttons["Back"].firstMatch
        XCTAssertTrue(back.exists)
        back.tap()
        XCTAssertTrue(settings.waitForExistence(timeout: 10))
        assertPortraitWindow(app, expectedScreen: expectedScreen)
        XCUIDevice.shared.press(.home)
        app.activate()
        XCTAssertTrue(settings.waitForExistence(timeout: 10))
        assertPortraitWindow(app, expectedScreen: expectedScreen)
    }

    @MainActor
    private func assertPortraitWindow(_ app: XCUIApplication, expectedScreen: CGSize,
                                      file: StaticString = #filePath, line: UInt = #line) {
        let window = app.windows.firstMatch
        let portrait = NSPredicate { _, _ in
            window.exists && window.frame.height > window.frame.width
        }
        let ready = expectation(for: portrait, evaluatedWith: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 5), .completed,
                      "The visible app window must remain portrait.", file: file, line: line)
        // UIScreen in the TEST RUNNER can be a 768x1024 compatibility surface,
        // even when the app itself occupies the real 1032x1376 display. Use
        // the connected device's display inventory, not the runner's scene.
        let frame = window.frame.size
        XCTAssertEqual(frame.width, expectedScreen.width, accuracy: 1,
                       "Do not accept a portrait-shaped compatibility window.", file: file, line: line)
        XCTAssertEqual(frame.height, expectedScreen.height, accuracy: 1,
                       "The app must occupy the full iPad screen.", file: file, line: line)
        print("ForestiX device smoke: app window \(frame.width)x\(frame.height), orientation request \(XCUIDevice.shared.orientation.rawValue)")
    }
}
