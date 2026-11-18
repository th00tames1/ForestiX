import XCTest
import Models
import Sensors
#if canImport(UI)
@testable import UI
#else
@testable import CaptureRuntime
#endif

final class HeightTargetRangeTests: XCTestCase {
    @MainActor func testTooCloseRaycastCannotCreateAnAnchorOrKeepAttemptOpen() {
        let vm = HeightScanViewModel(calibration: .identity)
        vm.anchorHereNow(screenCenterHit: nil,rangeRejection: .tooClose)
        XCTAssertEqual(vm.state,.idle)
        XCTAssertNil(vm.anchorPointWorld)
        XCTAssertNil(vm.measurementId)
        XCTAssertEqual(vm.anchorFailureReason,"Move back — keep at least 0.5 m from the target.")
    }
    @MainActor func testImperialAdviceUsesFeetWithoutChangingMinimum() {
        let vm = HeightScanViewModel(calibration: .identity)
        vm.unitSystem = .imperial
        vm.anchorHereNow(screenCenterHit: nil,rangeRejection: .tooClose)
        XCTAssertNil(vm.anchorPointWorld)
        XCTAssertEqual(vm.anchorFailureReason,"Move back — keep at least 2 ft from the target.")
    }
}
