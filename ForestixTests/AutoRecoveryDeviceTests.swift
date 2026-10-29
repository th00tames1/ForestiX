import XCTest
import Common

/// Pure synthetic policy checks on ARM. No camera, sensor, settings or user data.
final class AutoRecoveryDeviceTests: XCTestCase {
    func testLiveRecoveryPreservesHealthyCadenceAndBacksOffOnlyActualErrors() {
        for cadence in [0.002,0.020,0.0625,0.125] {
            XCTAssertEqual(AutoSafetyPolicy.liveInferenceDelaySeconds(cadenceDelay:cadence,failed:false),cadence)
            XCTAssertEqual(AutoSafetyPolicy.liveInferenceDelaySeconds(cadenceDelay:cadence,failed:true),0.75)
        }
        XCTAssertEqual(AutoSafetyPolicy.liveInferenceDelaySeconds(cadenceDelay:1,failed:true),1)
        XCTAssertEqual(AutoSafetyPolicy.pairedInputWaitSeconds,0.25)
        XCTAssertEqual(AutoSafetyPolicy.pairedInputPollSeconds,0.016)
        XCTAssertEqual(AutoSafetyPolicy.captureProcessingSeconds,5)
        XCTAssertEqual(AutoSafetyPolicy.maximumSeconds,60)
    }
}
