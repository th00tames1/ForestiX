// Unit tests for §7.5 stratified-sampling statistics.

import XCTest
@testable import InventoryEngine

final class StandStatsTests: XCTestCase {

    func testUnstratifiedMatchesSampleMean() {
        let values: [(String, Double)] = [
            ("s", 10), ("s", 20), ("s", 30), ("s", 40)]
        let stat = StandStatsCalculator.compute(
            plotValues: values, stratumAreasAcres: [:])
        XCTAssertEqual(stat.mean, 25, accuracy: 1e-9)
        XCTAssertEqual(stat.nPlots, 4)
        // Sample variance = 166.667, se = sqrt(166.667/4)=6.455
        XCTAssertEqual(stat.seMean, 6.455, accuracy: 0.005)
        XCTAssertGreaterThan(stat.ci95HalfWidth, 0)
    }

    func testTwoStrataWeightedMean() {
        // Stratum A: plots [10, 14], 60 acres (weight 0.6).
        // Stratum B: plots [30, 34], 40 acres (weight 0.4).
        let values: [(String, Double)] = [
            ("A", 10), ("A", 14), ("B", 30), ("B", 34)]
        let areas: [String: Double] = ["A": 60, "B": 40]
        let stat = StandStatsCalculator.compute(
            plotValues: values, stratumAreasAcres: areas)
        // ȳ_A=12, ȳ_B=32, Ȳ = 0.6·12 + 0.4·32 = 20
        XCTAssertEqual(stat.mean, 20, accuracy: 1e-9)
        XCTAssertEqual(stat.nPlots, 4)
        XCTAssertEqual(stat.byStratum.count, 2)
        XCTAssertEqual(stat.byStratum["A"]?.mean ?? 0, 12, accuracy: 1e-9)
        XCTAssertEqual(stat.byStratum["B"]?.mean ?? 0, 32, accuracy: 1e-9)
    }

    func testSinglePlotZeroVariance() {
        let values: [(String, Double)] = [("s", 42)]
        let stat = StandStatsCalculator.compute(
            plotValues: values, stratumAreasAcres: [:])
        XCTAssertEqual(stat.mean, 42, accuracy: 1e-9)
        XCTAssertEqual(stat.seMean, 0, accuracy: 1e-9)
    }

    func testEmptyInput() {
        let stat = StandStatsCalculator.compute(
            plotValues: [], stratumAreasAcres: [:])
        XCTAssertEqual(stat.nPlots, 0)
        XCTAssertEqual(stat.mean, 0)
    }

    func testTCriticalInterpolation() {
        // df=10 ⇒ 2.228, df=15 ⇒ 2.131. At df=12.5, expect ~2.180.
        let t = StandStatsCalculator.tCritical95(df: 12.5)
        XCTAssertEqual(t, 2.180, accuracy: 0.01)
    }
}
