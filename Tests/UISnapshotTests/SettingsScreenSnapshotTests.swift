// Phase 1 snapshot coverage for the settings surface.

#if canImport(UIKit)
import XCTest
import SwiftUI
import SnapshotTesting
@testable import UI

@MainActor
final class SettingsScreenSnapshotTests: XCTestCase {

    override func setUp() {
        super.setUp()
        isRecording = false
    }

    func testSettingsScreenDefault() {
        let environment = AppEnvironment.preview()
        let view = NavigationStack { SettingsScreen() }
            .environmentObject(environment)
            .environmentObject(environment.settings)
        let host = UIHostingController(rootView: view)
        assertSnapshot(of: host, as: .image(on: .iPhone13))
    }
}
#endif
