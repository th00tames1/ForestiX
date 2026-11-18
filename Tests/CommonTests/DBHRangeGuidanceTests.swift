import XCTest
@testable import Common

final class DBHRangeGuidanceTests: XCTestCase {
    func testTooCloseReadingIsAvailableWithoutAnyMaskOrFit() {
        XCTAssertEqual(DBHRangeGuidance.message(distanceMeters: 0.3,ageSeconds: 0,imperial: false),
                       "Move back — keep at least 0.5 m from the target.")
        XCTAssertNotNil(DBHRangeGuidance.message(distanceMeters: Float(0.5).nextDown,ageSeconds: 0.5,imperial: false))
    }
    func testUsableRangeClearsTheWarningImmediately() {
        for d: Float in [0.5,1,5] {
            XCTAssertNil(DBHRangeGuidance.message(distanceMeters: d,ageSeconds: 0,imperial: false))
        }
    }
    func testFarReadingExplainsWhyMovingCloserIsNecessary() {
        XCTAssertEqual(DBHRangeGuidance.message(distanceMeters: Float(5).nextUp,ageSeconds: 0,imperial: false),
                       "Move closer — keep within 5 m of the target.")
    }
    func testMissingOrInvalidDepthDoesNotInventPhysicalDistance() {
        for d: Float? in [nil,0,-1,.nan,.infinity] {
            XCTAssertNil(DBHRangeGuidance.message(distanceMeters: d,ageSeconds: 0,imperial: false))
        }
    }
    func testOldOrInvalidClockCannotLeaveARangeWarningOnScreen() {
        for age: Double in [0.501,-0.01,.nan,.infinity] {
            XCTAssertNil(DBHRangeGuidance.message(distanceMeters: 0.3,ageSeconds: age,imperial: false))
        }
    }
    func testImperialMessagesKeepTheSamePhysicalWindow() {
        XCTAssertEqual(DBHRangeGuidance.message(distanceMeters: 0.3,ageSeconds: 0,imperial: true),
                       "Move back — keep at least 2 ft from the target.")
        XCTAssertEqual(DBHRangeGuidance.message(distanceMeters: 6,ageSeconds: 0,imperial: true),
                       "Move closer — keep within 16 ft of the target.")
        XCTAssertNil(DBHRangeGuidance.message(distanceMeters: 0.5,ageSeconds: 0,imperial: true))
    }
    func testMedianIgnoresHolesAndIsNotClippedToMeasurementRange() {
        XCTAssertEqual(DBHRangeGuidance.median(centerX: 2,centerY: 2,width: 5,height: 5) { x,y in
            if x == 2 && y == 2 { return 0 }
            return 0.3
        },0.3)
        XCTAssertNil(DBHRangeGuidance.median(centerX: 2,centerY: 2,width: 5,height: 5) { _,_ in .nan })
    }
    func testMappingOutsideTheImageFailsClosed() {
        for x in [-1.0,5,.nan,.infinity] {
            XCTAssertNil(DBHRangeGuidance.median(centerX: x,centerY: 2,width: 5,height: 5) { _,_ in
                XCTFail("Invalid mapping must not read pixels");return 0.3
            })
        }
    }
    func testMedianClampsNeighbourhoodAtTheImageEdge() {
        var reads = 0
        XCTAssertEqual(DBHRangeGuidance.median(centerX: 0,centerY: 0,width: 3,height: 3) { x,y in
            XCTAssertTrue((0..<3).contains(x));XCTAssertTrue((0..<3).contains(y));reads += 1;return 0.3
        },0.3)
        XCTAssertEqual(reads,9)
    }
}
