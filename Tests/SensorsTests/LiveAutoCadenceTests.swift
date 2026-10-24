import XCTest
@testable import Sensors
final class LiveAutoCadenceTests: XCTestCase {
    func testNominalTargetIsSixteenHzWithoutOverlappingWorkers() {
        XCTAssertEqual(LiveAutoCadence.delaySeconds(elapsed: 0),0.0625)
        XCTAssertEqual(LiveAutoCadence.delaySeconds(elapsed: 0.03),0.0325)
        XCTAssertEqual(LiveAutoCadence.delaySeconds(elapsed: 0.3),0.002)
    }
    func testThermalBackoffRetainsOriginalPeriodAndRest() {
        XCTAssertEqual(LiveAutoCadence.delaySeconds(elapsed: 0.03,thermallyLimited: true),0.095)
        XCTAssertEqual(LiveAutoCadence.delaySeconds(elapsed: 0.3,thermallyLimited: true),0.02)
    }
    func testInvalidClocksCannotCreateBusyLoop() {
        for bad in [Double.nan, .infinity, -1] {
            XCTAssertEqual(LiveAutoCadence.delaySeconds(elapsed: bad),0.0625)
        }
    }
}
