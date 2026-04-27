// Sampling statistics regression tests — pinned to known textbook
// values (Avery & Burkhart, basic descriptive stats) so the engine
// doesn't drift silently across refactors.

import XCTest
@testable import Sensors

final class SamplingStatsTests: XCTestCase {

    func testMean() {
        XCTAssertEqual(SamplingStats.mean([1, 2, 3, 4, 5]), 3.0, accuracy: 1e-9)
        XCTAssertEqual(SamplingStats.mean([]), 0.0)
    }

    func testStandardDeviation() {
        // Sample SD of 1..5 = sqrt(((1-3)^2 + ... + (5-3)^2) / 4) = sqrt(10/4)
        XCTAssertEqual(SamplingStats.standardDeviation([1, 2, 3, 4, 5]),
                       (10.0 / 4.0).squareRoot(), accuracy: 1e-9)
        XCTAssertEqual(SamplingStats.standardDeviation([7]), 0.0)
    }

    func testCV() {
        // mean 100, sd ~28.28; cv ≈ 28.28%
        let xs: [Double] = [60, 80, 100, 120, 140]
        let cv = SamplingStats.cv(xs)
        XCTAssertEqual(cv, 31.62, accuracy: 0.5)
    }

    func testRequiredSampleSize() {
        // CV 30%, target 10% SE, t=2  →  n = (2*30/10)^2 = 36
        XCTAssertEqual(SamplingStats.requiredSampleSize(targetSEPct: 10,
                                                         cv: 30, t: 2), 36)
        // 5% SE → 144
        XCTAssertEqual(SamplingStats.requiredSampleSize(targetSEPct: 5,
                                                         cv: 30, t: 2), 144)
    }

    func testNeymanSumsToTotal() {
        // Three strata with different CVs and Ns; allocation must
        // sum to the requested total exactly even after rounding.
        let alloc = SamplingStats.neymanAllocation(
            strataCV: [10, 20, 30],
            strataN:  [100, 50, 50],
            totalSampleSize: 30)
        XCTAssertEqual(alloc.reduce(0, +), 30)
        XCTAssertEqual(alloc.count, 3)
    }

    func testReinekeSDI() {
        // TPA=300, QMD=10 in → SDI = 300 × 1^1.605 = 300
        XCTAssertEqual(SamplingStats.reinekeSDI(tpa: 300, qmdInches: 10),
                       300, accuracy: 1e-9)
        // QMD=20: 300 × 2^1.605 ≈ 300 × 3.04 ≈ 912
        XCTAssertEqual(SamplingStats.reinekeSDI(tpa: 300, qmdInches: 20),
                       912.5, accuracy: 5)
    }

    func testCurtisRD() {
        // BA 200 ft²/ac, QMD 16 in → RD = 200 / 4 = 50
        XCTAssertEqual(SamplingStats.curtisRD(baPerAcreFt2: 200,
                                               qmdInches: 16),
                       50.0, accuracy: 1e-9)
    }

    func testCruiseRatingBands() {
        XCTAssertEqual(SamplingStats.rating(forSEPct: 5),  .acceptable)
        XCTAssertEqual(SamplingStats.rating(forSEPct: 10), .marginal)
        XCTAssertEqual(SamplingStats.rating(forSEPct: 25), .poor)
    }
}
