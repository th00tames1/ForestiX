import XCTest
import UIKit

/// Explicit opt-in on the user's connected iPad. Reads the actual settings
/// and generates a local export, but never accepts a measurement, edits GT,
/// changes settings, deletes data or sends files to an external recipient.
/// Never inject device orientation: even a portrait setter can disable the
/// user's system Rotation Lock. The operator establishes portrait beforehand.
final class iPadFieldStudySmokeTests: XCTestCase {
    /// Regression comparison with Android's Plot double-Back failure. No
    /// rotation commands, anchor placement, capture, Save or Reset actions.
    @MainActor func testSamplingPlotBackAndResumeWithoutOrientationCommands() throws {
        try XCTSkipUnless(ProcessInfo.processInfo.environment["FORESTIX_IPAD_SMOKE"] == "1")
        #if targetEnvironment(simulator)
        throw XCTSkip("This check requires the explicitly connected physical iPad")
        #endif
        try XCTSkipUnless(UIDevice.current.userInterfaceIdiom == .pad)
        continueAfterFailure = false
        let app = XCUIApplication(bundleIdentifier: "asfl-Forestix")
        app.launch()
        let map = app.buttons["mapHome.measure"]
        XCTAssertTrue(map.waitForExistence(timeout: 30),app.debugDescription)
        for repeatedBack in [false,true,false,true] {
            map.tap()
            let plot = app.buttons["mapHome.choose.sampling"]
            if !plot.isHittable { app.swipeUp() }
            XCTAssertTrue(plot.waitForExistence(timeout: 10)); plot.tap()
            XCTAssertTrue(app.sliders["samplingPlot.radius"].waitForExistence(timeout: 25),app.debugDescription)
            assertNativePortraitWindow(app)
            XCUIDevice.shared.press(.home); app.activate()
            let back = app.buttons["measure.back"]
            XCTAssertTrue(back.waitForExistence(timeout: 15))
            if repeatedBack { back.doubleTap() } else { back.tap() }
            XCTAssertTrue(map.waitForExistence(timeout: 15),app.debugDescription)
            assertNativePortraitWindow(app)
        }
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = "iPad Plot single and double Back returned to map"
        shot.lifetime = .keepAlways; add(shot)
    }
    /// Camera navigation and application resume only. No orientation setters,
    /// measurement/anchor taps, preference edits, or accepted results.
    @MainActor func testCameraEntryExitAndResumeWithoutOrientationCommands() throws {
        try XCTSkipUnless(ProcessInfo.processInfo.environment["FORESTIX_IPAD_SMOKE"] == "1")
        #if targetEnvironment(simulator)
        throw XCTSkip("This check requires the explicitly connected physical iPad")
        #endif
        try XCTSkipUnless(UIDevice.current.userInterfaceIdiom == .pad)
        continueAfterFailure = false
        let app = XCUIApplication(bundleIdentifier: "asfl-Forestix")
        app.launch()
        let settings = app.buttons["mapHome.settings"]
        XCTAssertTrue(settings.waitForExistence(timeout: 30), app.debugDescription)
        assertNativePortraitWindow(app)
        for cycle in 1...2 {
            for kind in ["dbh", "height"] {
                let measure = app.buttons["mapHome.measure"]
                XCTAssertTrue(measure.waitForExistence(timeout: 10)); measure.tap()
                let choice = app.buttons["mapHome.choose.\(kind)"]
                XCTAssertTrue(choice.waitForExistence(timeout: 10)); choice.tap()
                assertCameraPresentation(app, kind: kind)
                assertNativePortraitWindow(app)
                XCUIDevice.shared.press(.home)
                app.activate()
                assertCameraPresentation(app, kind: kind)
                assertNativePortraitWindow(app)
                let shot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
                shot.name = "Orientation-safe iPad \(kind) resume cycle \(cycle)"
                shot.lifetime = .keepAlways; add(shot)
                print("Orientation-safe iPad \(kind) cycle \(cycle) hierarchy:\n"+app.debugDescription)
                let back = app.buttons["Back"].firstMatch
                XCTAssertTrue(back.exists && back.isHittable); back.tap()
                XCTAssertTrue(settings.waitForExistence(timeout: 15), app.debugDescription)
                assertNativePortraitWindow(app)
            }
        }
    }

    @MainActor private func assertCameraPresentation(_ app: XCUIApplication, kind: String) {
        if kind == "height" {
            XCTAssertTrue(app.staticTexts["heightScan.crosshair"].waitForExistence(timeout: 25), app.debugDescription)
            return
        }
        XCTAssertTrue(app.buttons["dbhScan.breastHeightBase"].waitForExistence(timeout: 25), app.debugDescription)
        // Auto hides Adjust's numeric/status strip until it finds a stem.
        // Check the actual capture scene/viewport gate, not that optional UI.
        let alignment = app.otherElements["dbhScan.crosshair"]
        let ready = NSPredicate { _, _ in
            alignment.exists && alignment.value as? String == "Camera view aligned"
        }
        XCTAssertEqual(XCTWaiter.wait(for: [expectation(for: ready, evaluatedWith: nil)], timeout: 25),
                       .completed, "Fresh portrait camera mapping must be available in both Auto and Adjust.\n"+app.debugDescription)
    }

    @MainActor private func assertNativePortraitWindow(_ app: XCUIApplication) {
        let environment = ProcessInfo.processInfo.environment
        guard let width = environment["FORESTIX_IPAD_WIDTH"].flatMap(Double.init),
              let height = environment["FORESTIX_IPAD_HEIGHT"].flatMap(Double.init) else {
            return XCTFail("Supply the connected iPad's native portrait dimensions in points")
        }
        let window = app.windows.firstMatch
        let ready = NSPredicate { _, _ in window.exists && window.frame.height > window.frame.width }
        XCTAssertEqual(XCTWaiter.wait(for: [expectation(for: ready, evaluatedWith: nil)], timeout: 10), .completed)
        XCTAssertEqual(window.frame.width, width, accuracy: 1)
        XCTAssertEqual(window.frame.height, height, accuracy: 1)
        // These dimensions do not prove the Control Center setting, physical
        // rotation behavior, camera registration, or a sensor measurement.
    }

    @MainActor func testFieldSettingsAndLocalExportSharePresentation() throws {
        try XCTSkipUnless(ProcessInfo.processInfo.environment["FORESTIX_IPAD_SMOKE"] == "1")
        #if targetEnvironment(simulator)
        throw XCTSkip("This check requires the explicitly connected physical iPad")
        #endif
        try XCTSkipUnless(UIDevice.current.userInterfaceIdiom == .pad)
        continueAfterFailure = false
        let app = XCUIApplication(bundleIdentifier: "asfl-Forestix")
        app.launch()
        let settings = app.buttons["mapHome.settings"]
        let mapOpened = settings.waitForExistence(timeout: 30)
        if !mapOpened { print("Physical iPad startup failure hierarchy:\n"+app.debugDescription) }
        XCTAssertTrue(mapOpened); settings.tap()
        let link = app.buttons["Field study"]
        for _ in 0..<10 { if link.exists && link.isHittable { break }; app.swipeUp() }
        XCTAssertTrue(link.exists && link.isHittable); link.tap()
        XCTAssertTrue(app.navigationBars["Field study"].waitForExistence(timeout: 15))
        XCTAssertTrue(app.textFields["Study ID"].exists)
        XCTAssertTrue(app.textFields["Operator ID"].exists)
        let export = app.buttons["fieldStudy.exportAll"]
        for _ in 0..<20 { if export.exists && export.isHittable { break }; app.swipeUp() }
        XCTAssertTrue(export.exists && export.isHittable); export.tap()
        let message = app.staticTexts["fieldStudySettingsMessage"]
        XCTAssertTrue(message.waitForExistence(timeout: 90))
        XCTAssertTrue(message.label.hasPrefix("Exported "),message.label)
        print("Physical iPad export report: \(message.label)")
        let share = app.buttons["fieldStudy.shareExport"]
        for _ in 0..<8 { if share.exists && share.isHittable { break }; app.swipeUp() }
        XCTAssertTrue(share.exists && share.isHittable); share.tap()
        // Screenshot review confirms the native activity presentation, not
        // successful receipt by an external app. Never tap a destination.
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = "Physical iPad field-study native share presentation"
        shot.lifetime = .keepAlways; add(shot)
        XCTAssertTrue(app.wait(for: .runningForeground,timeout: 5))
    }
}
