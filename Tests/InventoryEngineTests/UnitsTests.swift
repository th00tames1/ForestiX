// Spec §1 Glossary + §7.6 (unit conversions).

import XCTest
import Common

final class UnitsTests: XCTestCase {

    func testMetersFeetRoundTrip() {
        let m: Double = 30
        XCTAssertEqual(Units.feetToMeters(Units.metersToFeet(m)), m, accuracy: 1e-9)
    }

    func testCmInchesRoundTrip() {
        let cm: Double = 40
        XCTAssertEqual(Units.inchesToCm(Units.cmToInches(cm)), cm, accuracy: 1e-9)
    }

    func testAcreToSquareMeters() {
        // 1 acre = 4046.8564224 m² exact.
        XCTAssertEqual(Units.acresToSquareMeters(1.0), 4046.8564224, accuracy: 1e-6)
        XCTAssertEqual(Units.squareMetersToAcres(4046.8564224), 1.0, accuracy: 1e-9)
    }

    func testFloatOverloads() {
        XCTAssertEqual(Units.metersToFeet(Float(1)), Float(1 / 0.3048), accuracy: 1e-4)
        XCTAssertEqual(Units.cmToInches(Float(2.54)), 1.0, accuracy: 1e-4)
    }
}
