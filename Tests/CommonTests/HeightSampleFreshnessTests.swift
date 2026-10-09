import XCTest
@testable import Common

final class HeightSampleFreshnessTests: XCTestCase {
    func testRecentTrackedGeometryAge() {
        for age in [0.0, 0.25, 0.5] { XCTAssertTrue(HeightSampleFreshness.isRecent(ageSeconds: age)) }
        for age in [-0.01, 0.501, 2, .infinity, .nan] {
            XCTAssertFalse(HeightSampleFreshness.isRecent(ageSeconds: age))
        }
    }
}
