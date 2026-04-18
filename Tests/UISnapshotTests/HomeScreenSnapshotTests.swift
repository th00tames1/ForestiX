// Phase 1 snapshot coverage for the home surface. Guarded with
// `#if canImport(UIKit)` so `swift test` on macOS hosts compiles into a
// no-op while Xcode iOS Simulator runs the actual image comparison.

#if canImport(UIKit)
import XCTest
import SwiftUI
import SnapshotTesting
@testable import UI

@MainActor
final class HomeScreenSnapshotTests: XCTestCase {

    override func setUp() {
        super.setUp()
        isRecording = false
    }

    func testHomeScreenEmptyState() {
        let environment = AppEnvironment.preview()
        let view = NavigationStack { HomeScreen() }
            .environmentObject(environment)
            .environmentObject(environment.settings)
        let host = UIHostingController(rootView: view)
        assertSnapshot(of: host, as: .image(on: .iPhone13))
    }
}
#endif
