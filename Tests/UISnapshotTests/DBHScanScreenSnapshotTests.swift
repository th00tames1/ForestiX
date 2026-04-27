// Phase 2 snapshot coverage for the DBH scan surface. One test per §4.3
// state so regressions in the overlay chrome (status banner / result
// panel / action row) show up in review diffs. AR view is the deterministic
// Color.black placeholder per Phase 2 Decision #5 so the image comparison
// stays stable across hosts.

#if canImport(UIKit)
import XCTest
import SwiftUI
import SnapshotTesting
import Models
import Common
@testable import UI
@testable import Sensors

@MainActor
final class DBHScanScreenSnapshotTests: XCTestCase {

    override func setUp() {
        super.setUp()
        isRecording = false
    }

    // MARK: - Helpers

    private func host(_ viewModel: DBHScanViewModel) -> UIHostingController<some View> {
        let view = NavigationStack { DBHScanScreen(viewModel: viewModel) }
        return UIHostingController(rootView: view)
    }

    private func greenResult() -> DBHResult {
        DBHResult(
            diameterCm: 31.4,
            centerXZ: SIMD2<Float>(0, 2),
            arcCoverageDeg: 140,
            rmseMm: 2.3,
            sigmaRmm: 1.1,
            nInliers: 180,
            confidence: .green,
            method: .lidarPartialArcSingleView,
            rawPointsPath: nil,
            rejectionReason: nil)
    }

    private func yellowResult() -> DBHResult {
        DBHResult(
            diameterCm: 22.7,
            centerXZ: SIMD2<Float>(0, 2),
            arcCoverageDeg: 52,
            rmseMm: 4.1,
            sigmaRmm: 3.2,
            nInliers: 90,
            confidence: .yellow,
            method: .lidarPartialArcSingleView,
            rawPointsPath: nil,
            rejectionReason: nil)
    }

    private func redResult(reason: String) -> DBHResult {
        DBHResult(
            diameterCm: 0,
            centerXZ: SIMD2<Float>(0, 2),
            arcCoverageDeg: 30,
            rmseMm: 12,
            sigmaRmm: 9,
            nInliers: 20,
            confidence: .red,
            method: .lidarPartialArcSingleView,
            rawPointsPath: nil,
            rejectionReason: reason)
    }

    // MARK: - State matrix

    func testAligning() {
        let vm = DBHScanViewModel.preview(state: .aligning)
        assertSnapshot(of: host(vm), as: .image(on: .iPhone13))
    }

    func testArmed() {
        let vm = DBHScanViewModel.preview(state: .armed)
        assertSnapshot(of: host(vm), as: .image(on: .iPhone13))
    }

    func testCapturing() {
        let vm = DBHScanViewModel.preview(state: .capturing)
        assertSnapshot(of: host(vm), as: .image(on: .iPhone13))
    }

    func testFittedGreen() {
        let vm = DBHScanViewModel.preview(state: .fitted, result: greenResult())
        assertSnapshot(of: host(vm), as: .image(on: .iPhone13))
    }

    func testFittedYellow() {
        let vm = DBHScanViewModel.preview(state: .fitted, result: yellowResult())
        assertSnapshot(of: host(vm), as: .image(on: .iPhone13))
    }

    func testRejected() {
        let vm = DBHScanViewModel.preview(
            state: .rejected,
            result: redResult(reason: "Trunk arc coverage below 45°"))
        assertSnapshot(of: host(vm), as: .image(on: .iPhone13))
    }

    func testManualEntry() {
        let vm = DBHScanViewModel.preview(state: .manualEntry)
        assertSnapshot(of: host(vm), as: .image(on: .iPhone13))
    }

    func testUnsupportedDevice() {
        let vm = DBHScanViewModel.preview(state: .manualEntry, unsupported: true)
        assertSnapshot(of: host(vm), as: .image(on: .iPhone13))
    }
}
#endif
