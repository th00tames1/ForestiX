// Phase 4 snapshot coverage for §5.1 OffsetFlowScreen: one image per
// step A→E plus failed panel.

#if canImport(UIKit)
import XCTest
import SwiftUI
import SnapshotTesting
@testable import UI
import Models
import Positioning

@MainActor
final class OffsetFlowScreenSnapshotTests: XCTestCase {

    override func setUp() {
        super.setUp()
        isRecording = false
    }

    private func host(_ vm: OffsetFlowViewModel) -> UIHostingController<some View> {
        let view = NavigationStack { OffsetFlowScreen(viewModel: vm) }
        return UIHostingController(rootView: view)
    }

    private func result() -> PlotCenterResult {
        PlotCenterResult(
            lat: 45.123456, lon: -122.678901,
            source: .vioOffset, tier: .B,
            nSamples: 30, medianHAccuracyM: 4.5,
            sampleStdXyM: 2.8, offsetWalkM: 42.7)
    }

    func testStepAAnchorPlot() {
        let vm = OffsetFlowViewModel.preview(step: .anchorPlot)
        assertSnapshot(of: host(vm), as: .image(on: .iPhone13))
    }

    func testStepBWalkToOpening() {
        let vm = OffsetFlowViewModel.preview(step: .walkToOpening)
        assertSnapshot(of: host(vm), as: .image(on: .iPhone13))
    }

    func testStepCAveraging() {
        let vm = OffsetFlowViewModel.preview(
            step: .averagingAtOpening(secondsElapsed: 12, sampleCount: 13))
        assertSnapshot(of: host(vm), as: .image(on: .iPhone13))
    }

    func testStepDWalkBack() {
        let vm = OffsetFlowViewModel.preview(
            step: .walkBack(distanceFromPlotM: 14.3))
        assertSnapshot(of: host(vm), as: .image(on: .iPhone13))
    }

    func testStepEComputed() {
        let vm = OffsetFlowViewModel.preview(step: .computed(result()))
        assertSnapshot(of: host(vm), as: .image(on: .iPhone13))
    }

    func testFailed() {
        let vm = OffsetFlowViewModel.preview(
            step: .failed(reason:
                "ARKit tracking was interrupted — offset invalid."))
        assertSnapshot(of: host(vm), as: .image(on: .iPhone13))
    }
}
#endif
