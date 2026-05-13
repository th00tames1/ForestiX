// Phase 4 snapshot coverage for §5.1 PlotCenterScreen: averaging
// progress, tier A accept, tier C fallback banner, failure panel.

#if canImport(UIKit)
import XCTest
import SwiftUI
import SnapshotTesting
@testable import UI
import Models
import Positioning

@MainActor
final class PlotCenterScreenSnapshotTests: XCTestCase {

    override func setUp() {
        super.setUp()
        isRecording = false
    }

    private func host(_ vm: PlotCenterViewModel) -> UIHostingController<some View> {
        let view = NavigationStack { PlotCenterScreen(viewModel: vm) }
        return UIHostingController(rootView: view)
    }

    private func result(tier: PositionTier,
                        mAcc: Float = 4,
                        std: Float = 2) -> PlotCenterResult {
        PlotCenterResult(
            lat: 45.123456, lon: -122.678901,
            source: .gpsAveraged, tier: tier,
            nSamples: 60, medianHAccuracyM: mAcc,
            sampleStdXyM: std, offsetWalkM: nil)
    }

    func testAveragingMid() {
        let vm = PlotCenterViewModel.preview(
            phase: .averaging(secondsElapsed: 30, sampleCount: 29))
        assertSnapshot(of: host(vm), as: .image(on: .iPhone13))
    }

    func testGoodTierA() {
        let vm = PlotCenterViewModel.preview(
            phase: .good(result(tier: .A, mAcc: 3.8, std: 1.9)))
        assertSnapshot(of: host(vm), as: .image(on: .iPhone13))
    }

    func testPoorTierCWithOffsetBanner() {
        let vm = PlotCenterViewModel.preview(
            phase: .poor(result(tier: .C, mAcc: 18, std: 9)))
        assertSnapshot(of: host(vm), as: .image(on: .iPhone13))
    }

    func testFailedNotEnoughSamples() {
        let vm = PlotCenterViewModel.preview(
            phase: .failed(reason:
                "Not enough samples (need 30 with accuracy ≤ 20 m)"))
        assertSnapshot(of: host(vm), as: .image(on: .iPhone13))
    }
}
#endif
