import XCTest

/// Explicit opt-in. Use ONLY a newly created disposable iPad simulator, never
/// the user's installed app. No measured sensor value or surveyed target.
final class RemoteFieldPreflightTests: XCTestCase {
    /// Only the newly created disposable simulator may receive these
    /// explicitly synthetic study/target/reference records.
    @MainActor func testSyntheticFieldConfigurationAndMultipleReferencesExport() throws {
        let app = try application()
        app.launchArguments += ["-tc.developerMode","YES","-tc.developerModeUnlocked","YES"]
        app.launch()
        let settings = app.buttons["mapHome.settings"]
        XCTAssertTrue(settings.waitForExistence(timeout: 45)); settings.tap()
        let field = app.buttons["Field study"]
        reveal(field, in: app); field.tap()
        XCTAssertTrue(app.navigationBars["Field study"].waitForExistence(timeout: 15))
        let recording = app.switches["Record field-study measurements"]
        XCTAssertTrue(recording.exists)
        if recording.value as? String == "0" { recording.tap() }
        enter("SYNTHETIC-UI-20261023", into: app.textFields["Study ID"])
        enter("SYNTHETIC-OPERATOR", into: app.textFields["Operator ID"])
        for (kind,name) in [("dbh","Synthetic DBH comparison"),("height","Synthetic height comparison")] {
            let add = app.buttons["fieldStudy.addReferenceMethod."+kind]
            reveal(add, in: app); add.tap()
            let newName = app.textFields.matching(NSPredicate(format: "value == %@", "Comparison 2")).firstMatch
            reveal(newName, in: app)
            // A value-based query stops matching as soon as enter() clears
            // the old text. Rebind to the stable method ID before editing.
            let identifier = newName.identifier
            XCTAssertTrue(identifier.hasPrefix("fieldStudy.referenceMethodName."))
            enter(name, into: app.textFields[identifier])
        }
        let save = app.buttons["fieldStudy.saveConfiguration"]
        reveal(save, in: app); save.tap()
        let message = app.staticTexts["fieldStudySettingsMessage"]
        XCTAssertTrue(message.waitForExistence(timeout: 20))
        XCTAssertEqual(message.label,"Configuration saved.")
        let catalog = app.buttons["Target catalog / Reference observations"]
        reveal(catalog, in: app, upward: false); catalog.tap()
        XCTAssertTrue(app.navigationBars["Target catalog"].waitForExistence(timeout: 15))
        app.buttons["Add target"].tap()
        XCTAssertTrue(app.textFields["Tree ID"].waitForExistence(timeout: 10))
        enter("0003", into: app.textFields["Tree ID"])
        enter("Synthetic 003", into: app.textFields["Label"])
        enter("45", into: app.textFields["Latitude (optional)"])
        enter("-123", into: app.textFields["Longitude (optional)"])
        let addTarget = app.buttons["fieldStudy.addTarget"]
        reveal(addTarget, in: app); addTarget.tap()
        let target = app.buttons["fieldStudy.target.0003"]
        XCTAssertTrue(target.waitForExistence(timeout: 15)); target.tap()
        let observations = [("DBH reference (cm)","25"),("Synthetic DBH comparison (cm)","26"),
                            ("Height reference (m)","20"),("Synthetic height comparison (m)","21")]
        for (method,value) in observations {
            let add = app.buttons["Add reference observation"]
            reveal(add, in: app); add.tap()
            let picker = app.buttons["fieldStudy.referenceMethod"]
            XCTAssertTrue(picker.waitForExistence(timeout: 10)); picker.tap()
            let option = app.buttons[method]
            XCTAssertTrue(option.waitForExistence(timeout: 10),app.debugDescription); option.tap()
            enter(value, into: app.textFields["Reference value"])
            let saveObservation = app.buttons["Save observation"]
            reveal(saveObservation, in: app); saveObservation.tap()
            XCTAssertTrue(app.buttons["Add reference observation"].waitForExistence(timeout: 15))
        }
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = "Synthetic separate DBH and height reference methods"
        screenshot.lifetime = .keepAlways; add(screenshot)
        // Return through the real navigation stack to the exporter.
        app.navigationBars.buttons.element(boundBy: 0).tap()
        XCTAssertTrue(app.navigationBars["Target catalog"].waitForExistence(timeout: 10))
        app.navigationBars.buttons.element(boundBy: 0).tap()
        XCTAssertTrue(app.navigationBars["Field study"].waitForExistence(timeout: 10))
        let export = app.buttons["fieldStudy.exportAll"]
        reveal(export, in: app); export.tap()
        let exported = app.staticTexts["fieldStudySettingsMessage"]
        let ready = NSPredicate(format: "exists == true AND label CONTAINS %@", "4 reference observations")
        XCTAssertEqual(XCTWaiter.wait(for: [expectation(for: ready, evaluatedWith: exported)],timeout: 30),.completed,
                       exported.exists ? exported.label : app.debugDescription)
        print("Synthetic native GT export: "+exported.label)
    }

    @MainActor private func reveal(_ element: XCUIElement, in app: XCUIApplication, upward: Bool = true) {
        for _ in 0..<24 {
            if element.exists && element.isHittable { return }
            if upward { app.swipeUp() } else { app.swipeDown() }
        }
        XCTAssertTrue(element.exists && element.isHittable,app.debugDescription)
    }

    @MainActor private func enter(_ text: String, into field: XCUIElement) {
        XCTAssertTrue(field.waitForExistence(timeout: 10)); field.tap()
        if let current = field.value as? String, current != field.placeholderValue, !current.isEmpty {
            field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue,count: current.count))
        }
        field.typeText(text)
    }

    @MainActor private func application(_ fault: String? = nil) throws -> XCUIApplication {
        try XCTSkipUnless(ProcessInfo.processInfo.environment["FORESTIX_REMOTE_PREFLIGHT"] == "1")
        #if !targetEnvironment(simulator)
        throw XCTSkip("Synthetic preflight is restricted to a disposable simulator")
        #endif
        continueAfterFailure = false
        let app = XCUIApplication(bundleIdentifier: "asfl-Forestix")
        app.launchArguments = ["-tc.regionPickerSeen","YES","-tc.unitSystem","metric"]
        if let fault { app.launchEnvironment["FORESTIX_UI_TEST_STARTUP_FAILURE"] = fault }
        return app
    }
    @MainActor func testPersistentStartupFailureNeverExposesMeasurementOrPreview() throws {
        let app = try application("always"); app.launch()
        XCTAssertTrue(app.otherElements["startup.storageFailure"].waitForExistence(timeout: 30))
        XCTAssertTrue(app.staticTexts["Unable to open saved data"].exists)
        XCTAssertFalse(app.buttons["mapHome.measure"].exists)
        let failureScreen = XCTAttachment(screenshot: app.screenshot())
        failureScreen.name = "Storage failure refuses temporary measurement state"
        failureScreen.lifetime = .keepAlways; add(failureScreen)
        let retry = app.buttons["startup.retry"]; XCTAssertTrue(retry.exists); retry.tap()
        XCTAssertTrue(app.otherElements["startup.storageFailure"].waitForExistence(timeout: 15))
        XCTAssertFalse(app.buttons["mapHome.measure"].exists)
    }
    @MainActor func testRetryOpensRealStorageAndFieldSettingsAreUsable() throws {
        let app = try application("once"); app.launch()
        XCTAssertTrue(app.otherElements["startup.storageFailure"].waitForExistence(timeout: 30))
        app.buttons["startup.retry"].tap()
        let settings = app.buttons["mapHome.settings"]
        XCTAssertTrue(settings.waitForExistence(timeout: 45))
        XCTAssertFalse(app.otherElements["startup.storageFailure"].exists)
        settings.tap()
        let link = app.buttons["Field study"]
        for _ in 0..<10 { if link.isHittable { break }; app.swipeUp() }
        XCTAssertTrue(link.isHittable); link.tap()
        XCTAssertTrue(app.navigationBars["Field study"].waitForExistence(timeout: 15))
        XCTAssertTrue(app.textFields["Study ID"].exists)
        XCTAssertTrue(app.textFields["Operator ID"].exists)
        // Normal-mode GT definitions must not be exposed anywhere in the form.
        for _ in 0..<10 {
            XCTAssertFalse(app.staticTexts["DBH reference methods"].exists)
            XCTAssertFalse(app.staticTexts["Height reference methods"].exists)
            app.swipeUp()
        }
        let export = app.buttons["fieldStudy.exportAll"]
        for _ in 0..<6 { if export.isHittable { break }; app.swipeDown() }
        XCTAssertTrue(export.isHittable); export.tap()
        XCTAssertTrue(app.staticTexts["fieldStudySettingsMessage"].waitForExistence(timeout: 30))
        XCTAssertTrue(app.staticTexts["fieldStudySettingsMessage"].label.hasPrefix("Exported "))
        // Form rows are lazy. The success message inserts a row above the
        // export, so the new ShareLink can initially be below the viewport.
        let share = app.buttons["fieldStudy.shareExport"]
        for _ in 0..<6 { if share.exists && share.isHittable { break }; app.swipeUp() }
        XCTAssertTrue(share.exists && share.isHittable); share.tap()
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = "Disposable iPad complete export share presentation"
        attachment.lifetime = .keepAlways; add(attachment)
        XCTAssertTrue(app.wait(for: .runningForeground,timeout: 5))
    }
}
