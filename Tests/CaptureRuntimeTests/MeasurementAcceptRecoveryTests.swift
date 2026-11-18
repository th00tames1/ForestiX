import XCTest
import Models
import Sensors
#if canImport(UI)
@testable import UI
#else
@testable import CaptureRuntime
#endif

/// The scan's save-failure branch must keep its result available for retry;
/// successful-save presentation changes must not weaken this contract.
final class MeasurementAcceptRecoveryTests: XCTestCase {
    @MainActor func testFailedDBHSaveKeepsTheResultAndAllowsAcceptRetry() throws {
        let result = DBHResult(diameterCm: 31.4,centerXZ: .init(0,2),arcCoverageDeg: 140,
            rmseMm: 2.3,sigmaRmm: 1.1,nInliers: 180,confidence: .green,
            method: .lidarPartialArcSingleView,rawPointsPath: nil,rejectionReason: nil)
        let vm = DBHScanViewModel.preview(state: .fitted,result: result)
        let before = try XCTUnwrap(vm.result)
        let generation = vm.resultGeneration
        vm.accept()
        XCTAssertEqual(vm.state,.accepted)
        vm.acceptFailed()
        XCTAssertEqual(vm.state,.fitted)
        XCTAssertEqual(vm.result?.diameterCm,before.diameterCm)
        XCTAssertEqual(vm.resultGeneration,generation)
        vm.accept()
        XCTAssertEqual(vm.state,.accepted)
    }
    @MainActor func testFailedHeightSaveKeepsTheResultAndAllowsAcceptRetry() throws {
        let result = HeightResult(heightM: 28.4,dHm: 18,alphaTopRad: 0.96,alphaBaseRad: -0.087,
            sigmaHm: 0.9,confidence: .green,method: .vioWalkoffTangent,rejectionReason: nil)
        let vm = HeightScanViewModel.preview(state: .computed,result: result)
        let before = try XCTUnwrap(vm.result)
        let generation = vm.resultGeneration
        vm.accept()
        XCTAssertEqual(vm.state,.accepted)
        vm.acceptFailed()
        XCTAssertEqual(vm.state,.computed)
        XCTAssertEqual(vm.result?.heightM,before.heightM)
        XCTAssertEqual(vm.resultGeneration,generation)
        vm.accept()
        XCTAssertEqual(vm.state,.accepted)
    }
    @MainActor func testBaseWarningPersistsUntilASuccessfulRetryThenClears() {
        let pitch = IMUPitchBuffer()
        let vm = HeightScanViewModel(calibration: .identity,pitchBuffer: pitch)
        vm.anchorHere(anchorPointWorld: .zero,standingPointWorld: .init(1,0,0))
        vm.captureBase(at: 10,standingPointWorld: .init(10,0,0))
        XCTAssertNotNil(vm.anchorFailureReason)
        let warning = vm.anchorFailureReason
        vm.captureBase(at: 10,standingPointWorld: .init(10,0,0))
        XCTAssertEqual(vm.anchorFailureReason,warning)
        XCTAssertEqual(vm.state,.walking)
        pitch.append(timestamp: 10,pitchRad: -0.1)
        vm.captureBase(at: 10,standingPointWorld: .init(10,0,0))
        XCTAssertEqual(vm.state,.aimTopArmed)
        XCTAssertNil(vm.anchorFailureReason)
    }
    @MainActor func testTopWarningClearsOnSuccessfulRetryWithoutChangingHeightMath() throws {
        let pitch = IMUPitchBuffer()
        let vm = HeightScanViewModel(calibration: .identity,pitchBuffer: pitch)
        vm.anchorHere(anchorPointWorld: .zero,standingPointWorld: .init(1,0,0))
        pitch.append(timestamp: 10,pitchRad: -0.1)
        vm.captureBase(at: 10,standingPointWorld: .init(10,0,0))
        vm.captureTop(at: 12)
        XCTAssertNotNil(vm.anchorFailureReason)
        XCTAssertEqual(vm.state,.aimTopArmed)
        XCTAssertNil(vm.result)
        pitch.append(timestamp: 12,pitchRad: 0.6)
        vm.captureTop(at: 12)
        XCTAssertNotNil(vm.result)
        XCTAssertNil(vm.anchorFailureReason)
        XCTAssertEqual(try XCTUnwrap(vm.result).heightM,10*(tan(Float(0.6))-tan(Float(-0.1))),accuracy: 0.001)
    }
}
