import XCTest
import UIKit

/// Opt-in hardware smoke checks. These never accept a measurement, export
/// private records, or change measurement settings. Physical rotation and
/// tree-measurement accuracy still require the field checklist.
/// WARNING: XCTest orientation setters can disable system Rotation Lock.
/// FORESTIX_IPAD_SMOKE alone must never authorize that side effect.
final class iPadDeviceSmokeTests: XCTestCase {
    override func setUpWithError() throws {
        try super.setUpWithError()
        try XCTSkipUnless(ProcessInfo.processInfo.environment["FORESTIX_ALLOW_ROTATION_UNLOCK"] == "1",
                          "Rotation tests can disable system Rotation Lock; separate explicit authorization is required.")
    }

    @MainActor
    func testLandscapeBackgroundResumeAndDBHReady() throws {
        try XCTSkipUnless(ProcessInfo.processInfo.environment["FORESTIX_IPAD_SMOKE"] == "1")
        try XCTSkipUnless(UIDevice.current.userInterfaceIdiom == .pad)
        continueAfterFailure = false
        let environment = ProcessInfo.processInfo.environment
        let width = try XCTUnwrap(environment["FORESTIX_IPAD_WIDTH"].flatMap { Double($0) })
        let height = try XCTUnwrap(environment["FORESTIX_IPAD_HEIGHT"].flatMap { Double($0) })
        let expectedScreen = CGSize(width: width, height: height)
        defer { XCUIDevice.shared.orientation = .portrait }
        let app = XCUIApplication(bundleIdentifier: "asfl-Forestix")
        XCUIDevice.shared.orientation = .landscapeRight
        app.launch()
        let settings = app.buttons["mapHome.settings"]
        XCTAssertTrue(settings.waitForExistence(timeout: 30), app.debugDescription)
        assertPortraitWindow(app, expectedScreen: expectedScreen)
        for orientation in [UIDeviceOrientation.landscapeLeft, .landscapeRight] {
            XCUIDevice.shared.orientation = orientation
            XCUIDevice.shared.press(.home)
            app.activate()
            XCTAssertTrue(settings.waitForExistence(timeout: 15), app.debugDescription)
            let screen = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
            screen.name = "Physical display after landscape resume \(orientation.rawValue)"
            screen.lifetime = .keepAlways; add(screen)
            assertPortraitWindow(app, expectedScreen: expectedScreen)
            app.buttons["mapHome.measure"].tap()
            let diameter = app.buttons["mapHome.choose.dbh"]
            XCTAssertTrue(diameter.waitForExistence(timeout: 10), app.debugDescription)
            diameter.tap()
            XCTAssertTrue(app.buttons["dbhScan.breastHeightBase"].waitForExistence(timeout: 20), app.debugDescription)
            assertDBHOrientationReady(app)
            app.buttons["Back"].firstMatch.tap()
            XCTAssertTrue(settings.waitForExistence(timeout: 15), app.debugDescription)
        }
    }

    /// Exercises both real camera covers without taking or accepting a reading.
    /// Separate from rotation checks so a windowing failure cannot hide a
    /// camera-startup regression in the same run.
    @MainActor
    func testPortraitDBHAndHeightOpenCloseAndResume() throws {
        try XCTSkipUnless(ProcessInfo.processInfo.environment["FORESTIX_IPAD_SMOKE"] == "1")
        #if targetEnvironment(simulator)
        throw XCTSkip("Requires the explicitly connected physical iPad")
        #endif
        try XCTSkipUnless(UIDevice.current.userInterfaceIdiom == .pad)
        continueAfterFailure = false
        let environment = ProcessInfo.processInfo.environment
        let width = try XCTUnwrap(environment["FORESTIX_IPAD_WIDTH"].flatMap { Double($0) })
        let height = try XCTUnwrap(environment["FORESTIX_IPAD_HEIGHT"].flatMap { Double($0) })
        let expectedScreen = CGSize(width: width, height: height)
        defer { XCUIDevice.shared.orientation = .portrait }
        XCUIDevice.shared.orientation = .portrait
        let app = XCUIApplication(bundleIdentifier: "asfl-Forestix")
        app.launch()
        let settings = app.buttons["mapHome.settings"]
        XCTAssertTrue(settings.waitForExistence(timeout: 30), app.debugDescription)
        assertPortraitWindow(app, expectedScreen: expectedScreen)
        for _ in 0..<2 {
            for kind in ["dbh", "height"] {
                let measure = app.buttons["mapHome.measure"]
                XCTAssertTrue(measure.waitForExistence(timeout: 10)); measure.tap()
                let choice = app.buttons["mapHome.choose.\(kind)"]
                XCTAssertTrue(choice.waitForExistence(timeout: 10)); choice.tap()
                if kind == "dbh" {
                    let ground = app.buttons["dbhScan.breastHeightBase"]
                    XCTAssertTrue(ground.waitForExistence(timeout: 25), app.debugDescription)
                    XCTAssertEqual(ground.label, "Set ground")
                    assertDBHOrientationReady(app)
                } else {
                    XCTAssertTrue(app.staticTexts["heightScan.crosshair"].waitForExistence(timeout: 25),
                                  app.debugDescription)
                }
                print("Physical iPad \(kind) camera hierarchy:\n" + app.debugDescription)
                for orientation in [UIDeviceOrientation.landscapeLeft, .landscapeRight] {
                    XCUIDevice.shared.orientation = orientation
                    assertPortraitWindow(app, expectedScreen: expectedScreen)
                }
                XCUIDevice.shared.orientation = .portrait
                if kind == "dbh" { assertDBHOrientationReady(app) }
                let attachment = XCTAttachment(screenshot: app.screenshot())
                attachment.name = "Physical iPad \(kind) camera without capture"
                attachment.lifetime = .keepAlways; add(attachment)
                let back = app.buttons["Back"].firstMatch
                XCTAssertTrue(back.exists); back.tap()
                XCTAssertTrue(settings.waitForExistence(timeout: 15), app.debugDescription)
                XCUIDevice.shared.orientation = .landscapeRight
                assertPortraitWindow(app, expectedScreen: expectedScreen)
                XCUIDevice.shared.orientation = .portrait
            }
            XCUIDevice.shared.press(.home)
            app.activate()
            XCTAssertTrue(settings.waitForExistence(timeout: 15), app.debugDescription)
            assertPortraitWindow(app, expectedScreen: expectedScreen)
        }
        // A fresh process must also recover the map without accepting a record.
        app.terminate(); app.launch()
        XCTAssertTrue(settings.waitForExistence(timeout: 30), app.debugDescription)
        assertPortraitWindow(app, expectedScreen: expectedScreen)
    }

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
        let mapOpened = settings.waitForExistence(timeout: 20)
        if !mapOpened { print("Physical iPad startup failure hierarchy:\n"+app.debugDescription) }
        XCTAssertTrue(mapOpened)
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
        assertDBHOrientationReady(app)
        assertPortraitWindow(app, expectedScreen: expectedScreen)
        XCUIDevice.shared.orientation = .landscapeLeft
        assertPortraitWindow(app, expectedScreen: expectedScreen)
        XCTAssertTrue(ground.exists)
        assertDBHOrientationReady(app)

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
    private func assertDBHOrientationReady(_ app: XCUIApplication,
                                           file: StaticString = #filePath, line: UInt = #line) {
        let refusals: Set<String> = ["Return to the app to measure.",
            "Waiting for screen alignment.", "Waiting for portrait orientation.",
            "Open the app full screen to measure.", "Waiting for screen rotation lock.",
            "Waiting for fresh camera alignment."]
        let ready = NSPredicate { _, _ in
            if app.staticTexts["dbhScan.livePreview"].exists { return true }
            let status = app.staticTexts["dbhScan.previewStatus"]
            return status.exists && !refusals.contains(status.label)
        }
        let fresh = expectation(for: ready, evaluatedWith: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [fresh], timeout: 20), .completed,
                       "Portrait/full-screen lock and fresh depth mapping must allow the real DBH preview.\n" + app.debugDescription,
                       file: file, line: line)
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
        // Also inspect the physical display, not only the app's coordinate
        // space: a fixed portrait compatibility canvas can rotate/letterbox
        // inside a landscape display while retaining portrait app bounds.
        let displayImage = XCUIScreen.main.screenshot().image
        let encoded = try? XCTUnwrap(displayImage.cgImage)
        let width = CGFloat(encoded?.width ?? 0)
        let height = CGFloat(encoded?.height ?? 0)
        let swapsAxes: Bool
        switch displayImage.imageOrientation {
        case .left, .leftMirrored, .right, .rightMirrored: swapsAxes = true
        default: swapsAxes = false
        }
        // XCUIScreenshot may encode a portrait raster with a 90-degree image
        // orientation. UIImage.size alone can therefore give a false pass.
        let display = swapsAxes ? CGSize(width: height, height: width)
                               : CGSize(width: width, height: height)
        XCTAssertGreaterThan(display.height, display.width,
                             "The physical display must not rotate a portrait-shaped app canvas.",
                             file: file, line: line)
        print("ForestiX device smoke: app window \(frame.width)x\(frame.height), orientation request \(XCUIDevice.shared.orientation.rawValue)")
    }
}
