// Spec §7.9 Confidence framework: reject / warn → tier combination.
//  * any failed reject check ⇒ red
//  * 2+ failed warn checks   ⇒ red
//  * 1  failed warn check    ⇒ yellow
//  * all pass                ⇒ green

import XCTest
import Common

final class ConfidenceTierTests: XCTestCase {

    func testAllPassIsGreen() {
        let checks = [
            Check(passed: true, severity: .reject, reason: "a"),
            Check(passed: true, severity: .warn,   reason: "b")
        ]
        XCTAssertEqual(combineChecks(checks), .green)
    }

    func testOneWarnFailureIsYellow() {
        let checks = [
            Check(passed: true,  severity: .reject, reason: "a"),
            Check(passed: false, severity: .warn,   reason: "b")
        ]
        XCTAssertEqual(combineChecks(checks), .yellow)
    }

    func testTwoWarnFailuresIsRed() {
        let checks = [
            Check(passed: false, severity: .warn,   reason: "a"),
            Check(passed: false, severity: .warn,   reason: "b"),
            Check(passed: true,  severity: .reject, reason: "c")
        ]
        XCTAssertEqual(combineChecks(checks), .red)
    }

    func testAnyRejectFailureIsRed() {
        let checks = [
            Check(passed: false, severity: .reject, reason: "a"),
            Check(passed: true,  severity: .warn,   reason: "b")
        ]
        XCTAssertEqual(combineChecks(checks), .red)
    }
}
