import XCTest
import Models
import Sensors
#if canImport(UI)
@testable import UI
#else
@testable import CaptureRuntime
#endif

final class HeightDirectBaseCaptureTests: XCTestCase {
    @MainActor func testFirstTapAfterWalkCapturesBaseAndKeepsOriginalHeightMath() throws {
        let pitch = IMUPitchBuffer()
        let vm = HeightScanViewModel(calibration: .identity, pitchBuffer: pitch)
        vm.anchorHere(anchorPointWorld: .zero, standingPointWorld: .init(1, 0, 0))
        let id = vm.measurementId
        XCTAssertEqual(vm.state, .walking)
        pitch.append(timestamp: 10, pitchRad: -0.1)
        vm.captureBase(at: 10, standingPointWorld: .init(10, 0, 0))
        XCTAssertEqual(vm.state, .aimTopArmed)
        XCTAssertEqual(vm.measurementId, id)
        XCTAssertEqual(vm.dhMeters, 10, accuracy: 0.001)
        vm.captureTopDirect(alphaTopRad: 0.6)
        let height = try XCTUnwrap(vm.result).heightM
        XCTAssertEqual(height, 10 * (tan(Float(0.6)) - tan(Float(-0.1))), accuracy: 0.001)
    }
    @MainActor func testMissingAngleDoesNotConsumeWalkingTap() {
        let vm = HeightScanViewModel(calibration: .identity, pitchBuffer: IMUPitchBuffer())
        vm.anchorHere(anchorPointWorld: .zero, standingPointWorld: .init(1, 0, 0))
        vm.captureBase(at: 10, standingPointWorld: .init(10, 0, 0))
        XCTAssertEqual(vm.state, .walking)
        XCTAssertNotNil(vm.anchorFailureReason)
        XCTAssertNil(vm.result)
    }
    @MainActor func testProtocolRefusalStillPreventsBaseCapture() {
        let pitch = IMUPitchBuffer()
        let vm = HeightScanViewModel(calibration: .identity, pitchBuffer: pitch)
        vm.anchorHere(anchorPointWorld: .zero, standingPointWorld: .init(1, 0, 0))
        vm.fieldCapturePermission = { _, _ in "protocol mismatch" }
        pitch.append(timestamp: 10, pitchRad: -0.1)
        vm.captureBase(at: 10, standingPointWorld: .init(10, 0, 0))
        XCTAssertEqual(vm.state, .walking)
        XCTAssertNil(vm.result)
    }
}
