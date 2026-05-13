// Phase 3 snapshot coverage for §5.1 ARBoundary. Two baseline states
// (center not set, center set) plus the 15 m drift warn banner.

#if canImport(UIKit)
import XCTest
import SwiftUI
import SnapshotTesting
import simd
@testable import UI

@MainActor
final class ARBoundaryScreenSnapshotTests: XCTestCase {

    override func setUp() {
        super.setUp()
        isRecording = false
    }

    private func host(_ viewModel: ARBoundaryViewModel) -> UIHostingController<some View> {
        let view = NavigationStack { ARBoundaryScreen(viewModel: viewModel) }
        return UIHostingController(rootView: view)
    }

    func testCenterNotSet() {
        let vm = ARBoundaryViewModel.preview()
        assertSnapshot(of: host(vm), as: .image(on: .iPhone13))
    }

    func testCenterSet() {
        let vm = ARBoundaryViewModel.preview(
            centerWorld: SIMD3<Float>(0, 0, 0),
            userDistanceM: 4.2)
        assertSnapshot(of: host(vm), as: .image(on: .iPhone13))
    }

    func testDriftWarn() {
        let vm = ARBoundaryViewModel.preview(
            centerWorld: SIMD3<Float>(0, 0, 0),
            userDistanceM: 17.3,
            isDrifted: true)
        assertSnapshot(of: host(vm), as: .image(on: .iPhone13))
    }
}
#endif
