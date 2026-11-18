import XCTest
@testable import Common

final class MeasurementTargetRangeTests: XCTestCase {
    func testMinimumIsInclusiveButEverySmallerPositiveRangeIsRejected() {
        XCTAssertEqual(MeasurementTargetRange.rejection(distanceMeters: Float(0.5).nextDown),.tooClose)
        XCTAssertEqual(MeasurementTargetRange.rejection(distanceMeters: 0.499),.tooClose)
        XCTAssertNil(MeasurementTargetRange.rejection(distanceMeters: 0.5))
        XCTAssertNil(MeasurementTargetRange.rejection(distanceMeters: Float(0.5).nextUp))
    }
    func testNoPoseAndNonFiniteRangesCannotPlaceTargets() {
        for distance: Float? in [nil, .nan, .infinity, -.infinity, -1] {
            XCTAssertEqual(MeasurementTargetRange.rejection(distanceMeters: distance),.unavailable)
        }
        XCTAssertEqual(MeasurementTargetRange.rejection(distanceMeters: 0),.tooClose)
    }
    func testExistingMaximumIsStillInclusive() {
        XCTAssertNil(MeasurementTargetRange.rejection(distanceMeters: 4,maximumMeters: 4))
        XCTAssertEqual(MeasurementTargetRange.rejection(distanceMeters: Float(4).nextUp,maximumMeters: 4),.tooFar)
        XCTAssertNil(MeasurementTargetRange.rejection(distanceMeters: 6,maximumMeters: 6))
        XCTAssertEqual(MeasurementTargetRange.rejection(distanceMeters: 6.01,maximumMeters: 6),.tooFar)
    }
    func testGroundDistanceIsThreeDimensionalNotHorizontalOnly() {
        let d = MeasurementTargetRange.distance(camera: .zero,target: .init(0.3,0.4,0))
        XCTAssertEqual(d,0.5,accuracy: 0.000001)
        XCTAssertNil(MeasurementTargetRange.rejection(distanceMeters: d))
        let near = MeasurementTargetRange.distance(camera: .zero,target: .init(0.299,0.4,0))
        XCTAssertEqual(MeasurementTargetRange.rejection(distanceMeters: near),.tooClose)
    }
    func testFarHeightSightingsDoNotInheritTheShortAnchorMaximum() {
        XCTAssertNil(MeasurementTargetRange.rejection(distanceMeters: 30))
        XCTAssertNil(MeasurementTargetRange.rejection(distanceMeters: 60))
        XCTAssertEqual(MeasurementTargetRange.rejection(distanceMeters: 30,maximumMeters: 4),.tooFar)
    }
    func testDisplayUnitsCannotChangeTheGate() {
        XCTAssertEqual(MeasurementTargetRange.tooCloseMessage(imperial: false),"Move back — keep at least 0.5 m from the target.")
        XCTAssertEqual(MeasurementTargetRange.tooCloseMessage(imperial: true),"Move back — keep at least 2 ft from the target.")
        XCTAssertEqual(MeasurementTargetRange.minimumMeters,0.5)
    }
    func testInvalidMaximumCannotDisableMinimumAdmission() {
        for maximum: Float in [0.4, .nan, .infinity, -1] {
            XCTAssertEqual(MeasurementTargetRange.rejection(distanceMeters: 1,maximumMeters: maximum),.unavailable)
        }
    }
}
