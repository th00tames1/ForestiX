// Spec §7.5 Stand Statistics Done criteria:
//
//  Input: single stratum, area A (acres), plot values y = [100,110,95,105,100].
//  Expected (FPC omitted, i.e. N_h = nil):
//    ȳ   = 102
//    s²  = 32.5
//    var_ȳ = s²/n = 6.5
//    Ŷ   = A · 102
//    SE  = A · sqrt(6.5) ≈ A · 2.5495
//    df  = n − 1 = 4  (single stratum, Satterthwaite degenerates)
//    CI95 at df=4, t=2.776:  Ŷ ± 2.776 · SE

import XCTest
@testable import InventoryEngine

final class StandStatisticsTests: XCTestCase {

    func testSingleStratumKnownValues() {
        let A: Float = 10
        let plotValues: [Float] = [100, 110, 95, 105, 100]
        let result = StandStatistics.compute(strata: [
            .init(areaAcres: A, plotValues: plotValues, populationSize: nil)
        ])

        let expectedMean: Float = 102
        let expectedVarMean: Float = 6.5      // s²/n
        let expectedTotal   = A * expectedMean
        let expectedSE      = A * sqrt(expectedVarMean)

        XCTAssertEqual(result.total, expectedTotal, accuracy: 1e-3)
        XCTAssertEqual(result.se,    expectedSE,    accuracy: 1e-3)
        XCTAssertEqual(result.perStratumMean[0], expectedMean, accuracy: 1e-5)
        XCTAssertEqual(result.perStratumVarOfMean[0], expectedVarMean, accuracy: 1e-5)

        // df → 4 (Satterthwaite of a single stratum degenerates to n_h − 1).
        XCTAssertEqual(result.df, 4, accuracy: 1e-3)

        // CI95 bounds from t_{4,0.975} = 2.776.
        let t: Float = 2.776
        XCTAssertEqual(result.ci95Lower, expectedTotal - t * expectedSE, accuracy: 1e-2)
        XCTAssertEqual(result.ci95Upper, expectedTotal + t * expectedSE, accuracy: 1e-2)
    }

    func testFPCShrinksVariance() {
        let plotValues: [Float] = [100, 110, 95, 105, 100]
        let noFPC = StandStatistics.compute(strata: [
            .init(areaAcres: 10, plotValues: plotValues, populationSize: nil)
        ])
        let withFPC = StandStatistics.compute(strata: [
            .init(areaAcres: 10, plotValues: plotValues, populationSize: 10)
        ])
        XCTAssertLessThan(withFPC.se, noFPC.se,
                          "finite-population correction should shrink SE")
    }

    func testMultiStratumAggregates() {
        // Two strata; totals are area-weighted sums of stratum means.
        let s1 = StandStatistics.StratumSample(areaAcres: 20, plotValues: [10, 12, 14], populationSize: nil)
        let s2 = StandStatistics.StratumSample(areaAcres: 30, plotValues: [20, 22, 18], populationSize: nil)
        let r = StandStatistics.compute(strata: [s1, s2])
        // ȳ1 = 12, ȳ2 = 20. Ŷ = 20·12 + 30·20 = 240 + 600 = 840.
        XCTAssertEqual(r.total, 840, accuracy: 1e-3)
        XCTAssertEqual(r.perStratumMean[0], 12, accuracy: 1e-5)
        XCTAssertEqual(r.perStratumMean[1], 20, accuracy: 1e-5)
    }

    func testTStudent975TableEnds() {
        XCTAssertEqual(StandStatistics.tStudent975(df: 1), 12.706, accuracy: 1e-3)
        XCTAssertEqual(StandStatistics.tStudent975(df: 10), 2.228, accuracy: 1e-3)
        XCTAssertEqual(StandStatistics.tStudent975(df: 120), 1.960, accuracy: 1e-3)
        XCTAssertEqual(StandStatistics.tStudent975(df: 500), 1.960, accuracy: 1e-3)
    }
}
