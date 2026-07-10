// Phase 3 snapshot coverage for §5.3 HeightScan. One test per §4.4
// state so overlay chrome regressions surface in review diffs. The AR
// view is the deterministic Color.black placeholder per Phase 3 Q1 —
// the real ARView layers in when the device path is wired, but the
// comparison shouldn't depend on it.

#if canImport(UIKit)
import XCTest
import SwiftUI
import SnapshotTesting
import Models
import Common
@testable import UI

@MainActor
final class HeightScanScreenSnapshotTests: XCTestCase {

    override func setUp() {
        super.setUp()
        isRecording = false
    }

    private func host(_ viewModel: HeightScanViewModel) -> UIHostingController<some View> {
        let view = NavigationStack { HeightScanScreen(viewModel: viewModel) }
        return UIHostingController(rootView: view)
    }

    // MARK: - Result fixtures

    private func greenResult() -> HeightResult {
        HeightResult(
            heightM: 28.4,
            dHm: 18.0,
            alphaTopRad: 0.9599,        // ~55°
            alphaBaseRad: -0.0873,      // ~-5°
            sigmaHm: 0.9,
            confidence: .green,
            method: .vioWalkoffTangent,
            rejectionReason: nil)
    }

    private func yellowResult() -> HeightResult {
        HeightResult(
            heightM: 32.1,
            dHm: 27.0,                  // d_h > 25 → yellow warn
            alphaTopRad: 0.7854,        // ~45°
            alphaBaseRad: -0.0175,      // ~-1°
            sigmaHm: 1.4,
            confidence: .yellow,
            method: .vioWalkoffTangent,
            rejectionReason: nil)
    }

    private func redResult(reason: String) -> HeightResult {
        HeightResult(
            heightM: 0,
            dHm: 2.0,
            alphaTopRad: 0,
            alphaBaseRad: 0,
            sigmaHm: 0,
            confidence: .red,
            method: .vioWalkoffTangent,
            rejectionReason: reason)
    }

    // MARK: - State matrix

    func testAnchorSet() {
        let vm = HeightScanViewModel.preview(state: .anchorSet)
        assertSnapshot(of: host(vm), as: .image(on: .iPhone13))
    }

    func testWalkingMoveBack() {
        // d_h = 12 m, expected 30 m → sweet spot 18-30 m → move back ≈ 6 m.
        let vm = HeightScanViewModel.preview(
            state: .walking,
            dhMeters: 12,
            walkHintMeters: 6,
            expectedHeightM: 30)
        assertSnapshot(of: host(vm), as: .image(on: .iPhone13))
    }

    func testWalkingSweetSpot() {
        let vm = HeightScanViewModel.preview(
            state: .walking,
            dhMeters: 22,
            walkHintMeters: 0,
            expectedHeightM: 30)
        assertSnapshot(of: host(vm), as: .image(on: .iPhone13))
    }

    func testAimTopArmed() {
        let vm = HeightScanViewModel.preview(state: .aimTopArmed)
        assertSnapshot(of: host(vm), as: .image(on: .iPhone13))
    }

    func testAimBaseArmed() {
        let vm = HeightScanViewModel.preview(state: .aimBaseArmed)
        assertSnapshot(of: host(vm), as: .image(on: .iPhone13))
    }

    func testComputedGreen() {
        let vm = HeightScanViewModel.preview(
            state: .computed, result: greenResult())
        assertSnapshot(of: host(vm), as: .image(on: .iPhone13))
    }

    func testComputedYellow() {
        let vm = HeightScanViewModel.preview(
            state: .computed, result: yellowResult())
        assertSnapshot(of: host(vm), as: .image(on: .iPhone13))
    }

    func testRejected() {
        let vm = HeightScanViewModel.preview(
            state: .rejected,
            result: redResult(reason: "Walked back less than 3 m"))
        assertSnapshot(of: host(vm), as: .image(on: .iPhone13))
    }

    func testManualEntry() {
        let vm = HeightScanViewModel.preview(state: .manualEntry)
        assertSnapshot(of: host(vm), as: .image(on: .iPhone13))
    }
}
#endif
