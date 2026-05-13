// Phase 2 snapshot coverage for the calibration surface (§7.10 +
// REQ-CAL-003/004). One test per WallState × CylinderState permutation of
// interest so regressions in the procedure UI show up in review diffs.

#if canImport(UIKit)
import XCTest
import SwiftUI
import SnapshotTesting
import Models
@testable import UI
@testable import Sensors

@MainActor
final class CalibrationScreenSnapshotTests: XCTestCase {

    override func setUp() {
        super.setUp()
        isRecording = false
    }

    // MARK: - Helpers

    private func host(
        _ viewModel: CalibrationViewModel
    ) -> UIHostingController<some View> {
        let view = NavigationStack { CalibrationScreen(viewModel: viewModel) }
        return UIHostingController(rootView: view)
    }

    private func wallFixture() -> WallCalibrationResult {
        WallCalibrationResult(
            depthNoiseMm: 4.23,
            depthBiasMm: -1.07,
            planeNormal: SIMD3<Double>(0, 0, 1),
            planeCentroid: SIMD3<Double>(0, 0, 2),
            pointCount: 540)
    }

    private func cylinderSamples() -> [CylinderCalibration.Sample] {
        [
            .init(dbhMeasuredCm: 10.2, dbhTrueCm: 10.0),
            .init(dbhMeasuredCm: 20.1, dbhTrueCm: 20.0),
            .init(dbhMeasuredCm: 30.4, dbhTrueCm: 30.0)
        ]
    }

    private func cylinderFixture() -> CylinderCalibrationResult {
        CylinderCalibrationResult(
            alpha: -0.18,
            beta: 0.994,
            rSquared: 0.9997,
            sampleCount: 3)
    }

    // MARK: - Wall procedure

    func testWallIdle() {
        let vm = CalibrationViewModel.preview(wall: .idle, cylinder: .idle)
        assertSnapshot(of: host(vm), as: .image(on: .iPhone13))
    }

    func testWallScanning() {
        let vm = CalibrationViewModel.preview(
            wall: .scanning(progress: 0.4), cylinder: .idle)
        assertSnapshot(of: host(vm), as: .image(on: .iPhone13))
    }

    func testWallComputed() {
        let vm = CalibrationViewModel.preview(
            wall: .computed(wallFixture()), cylinder: .idle)
        assertSnapshot(of: host(vm), as: .image(on: .iPhone13))
    }

    func testWallFailed() {
        let vm = CalibrationViewModel.preview(
            wall: .failed("Need at least 30 points (captured 12)."),
            cylinder: .idle)
        assertSnapshot(of: host(vm), as: .image(on: .iPhone13))
    }

    // MARK: - Cylinder procedure

    private func cylinderHost(
        _ viewModel: CalibrationViewModel
    ) -> UIHostingController<some View> {
        let view = NavigationStack {
            CalibrationScreen(viewModel: viewModel, initialProcedure: .cylinder)
        }
        return UIHostingController(rootView: view)
    }

    func testCylinderCollecting() {
        let vm = CalibrationViewModel.preview(
            wall: .idle,
            cylinder: .collecting(samples: cylinderSamples()))
        assertSnapshot(of: cylinderHost(vm), as: .image(on: .iPhone13))
    }

    func testCylinderComputed() {
        let vm = CalibrationViewModel.preview(
            wall: .idle,
            cylinder: .computed(cylinderFixture(), samples: cylinderSamples()))
        assertSnapshot(of: cylinderHost(vm), as: .image(on: .iPhone13))
    }

    func testCylinderFailed() {
        let vm = CalibrationViewModel.preview(
            wall: .idle,
            cylinder: .failed("All diameters were identical — vary the target sizes."))
        assertSnapshot(of: cylinderHost(vm), as: .image(on: .iPhone13))
    }
}
#endif
