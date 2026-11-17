import XCTest
@testable import Common

final class MarkerVisibilityTests: XCTestCase {
    func testOpaqueSpheresHaveFillButRemainLit() {
        let fill = MarkerVisibility.minimumEmission(isSphere: true, alpha: 1)
        XCTAssertGreaterThan(fill, 0)
        XCTAssertLessThan(fill, 1)
    }
    func testOtherGeometryAndTranslucentMarkersRemainUnchanged() {
        XCTAssertEqual(MarkerVisibility.minimumEmission(isSphere: false, alpha: 1), 0)
        XCTAssertEqual(MarkerVisibility.minimumEmission(isSphere: true, alpha: 0.45), 0)
    }
}
