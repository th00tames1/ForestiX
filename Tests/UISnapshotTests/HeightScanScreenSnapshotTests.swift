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

    /// A red fit that is STILL A MEASUREMENT — the shape the `.rejected`
    /// stage takes in the field now. d_h = 1 m with an 88° top aim trips
    /// the steep-angle guard, yet the geometry is sound: H = 29.5 m with a
    /// real, large σ_H (±4.34 m, the sec⁴ term dominating). The old
    /// fixture used H = 0 with σ = 0 and both aims at 0°, which is an
    /// inverted non-measurement — it rendered the row with Accept inert
    /// and so could never show the Accept-on-red affordance at all.
    private func redResult(reason: String) -> HeightResult {
        HeightResult(
            heightM: 29.47,
            dHm: 1.0,
            alphaTopRad: 1.5359,        // 88°
            alphaBaseRad: -0.6981,      // -40°
            sigmaHm: 4.34,
            confidence: .red,
            method: .vioWalkoffTangent,
            rejectionReason: reason)
    }

    /// A red result that is NOT a measurement: the aims are inverted, so
    /// σ_H is underivable and stays unset. `HeightEstimator.canAccept`
    /// refuses it, which is what makes Accept inert in this state.
    private func unacceptableResult() -> HeightResult {
        HeightResult(
            heightM: 0,
            dHm: 2.0,
            alphaTopRad: 0,
            alphaBaseRad: 0,
            sigmaHm: nil,
            confidence: .red,
            method: .vioWalkoffTangent,
            rejectionReason: "Top aim was at or below the base — re-capture the top higher")
    }

    // MARK: - State matrix

    func testAnchorSet() {
        let vm = HeightScanViewModel.preview(state: .anchorSet)
        assertSnapshot(of: host(vm), as: .image(on: .iPhone13))
    }

    func testWalkingMoveBack() {
        // d_h = 12 m, expected 30 m → sweet spot 18-30 m → move back ≈ 6 m.
        // Anchored from 2 m out and walked 10 m straight back → total 12 m.
        let vm = HeightScanViewModel.preview(
            state: .walking,
            dhMeters: 12,
            walkHintMeters: 6,
            expectedHeightM: 30,
            initialDistanceM: 2,
            walkedBackMeters: 10)
        assertSnapshot(of: host(vm), as: .image(on: .iPhone13))
    }

    func testWalkingSweetSpot() {
        let vm = HeightScanViewModel.preview(
            state: .walking,
            dhMeters: 22,
            walkHintMeters: 0,
            expectedHeightM: 30,
            initialDistanceM: 2,
            walkedBackMeters: 20)
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

    /// RED-TIER RESULT STAGE. The row is now three buttons — Retake,
    /// Manual, Accept — with the crown control above them, and the
    /// rejection reason inline under the value rather than only in the
    /// top banner. Accept is LIVE here: a red fit is still a fit, and the
    /// cruiser decides with the reason in front of them.
    ///
    /// The old reason string ("Walked back less than 3 m") named a floor
    /// that no longer exists; the steep-aim reason is one the estimator
    /// actually produces.
    func testRejected() {
        let vm = HeightScanViewModel.preview(
            state: .rejected,
            result: redResult(reason: "Top angle too steep; step back"))
        assertSnapshot(of: host(vm), as: .image(on: .iPhone13))
    }

    /// Same stage, but the result is not a measurement (inverted aims,
    /// σ unset) — the row is identical except that Accept is inert.
    func testRejectedNotAMeasurement() {
        let vm = HeightScanViewModel.preview(
            state: .rejected,
            result: unacceptableResult())
        assertSnapshot(of: host(vm), as: .image(on: .iPhone13))
    }

    func testManualEntry() {
        let vm = HeightScanViewModel.preview(state: .manualEntry)
        assertSnapshot(of: host(vm), as: .image(on: .iPhone13))
    }
}
#endif
