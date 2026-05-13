// Spec §7.4 H–D Model Done criteria:
//  * Given synthetic Näslund data with known (a, b), the fit recovers them
//    within 5% when n ≥ 8.
//  * n < 8 raises `notEnoughObservations`.

import XCTest
@testable import InventoryEngine

final class HDModelTests: XCTestCase {

    func testPredictFormMatchesNaslund() {
        // H = 1.3 + D² / (a + b·D)².  Verify at a couple of DBHs.
        let a: Float = 2.0
        let b: Float = 0.05
        let d: Float = 30
        let expected = 1.3 + (d * d) / ((a + b * d) * (a + b * d))
        XCTAssertEqual(HDModel.predict(dbhCm: d, a: a, b: b), expected, accuracy: 1e-4)
    }

    func testFitRecoversParametersWithin5Percent() throws {
        // Ground truth (a, b) — realistic-looking values.
        let aTrue: Float = 2.0
        let bTrue: Float = 0.05
        let dbhs: [Float] = [10, 15, 20, 25, 30, 35, 40, 45, 50, 55]
        let obs: [(dbhCm: Float, heightM: Float)] = dbhs.map { d in
            let h = 1.3 + (d * d) / ((aTrue + bTrue * d) * (aTrue + bTrue * d))
            return (d, h)
        }
        let fit = try HDModel.fit(observations: obs)
        XCTAssertEqual(fit.a, aTrue, accuracy: aTrue * 0.05,
                       "a recovered within 5%")
        XCTAssertEqual(fit.b, bTrue, accuracy: bTrue * 0.05,
                       "b recovered within 5%")
        XCTAssertEqual(fit.nObs, dbhs.count)
        XCTAssertLessThan(fit.rmse, 0.01, "RMSE tiny for noise-free data")
    }

    func testFitThrowsForInsufficientObservations() {
        // §7.4: min 8 at species level. 5 observations → must throw.
        let obs: [(dbhCm: Float, heightM: Float)] = [
            (10, 10), (20, 15), (30, 20), (40, 25), (50, 30)
        ]
        XCTAssertThrowsError(try HDModel.fit(observations: obs)) { error in
            guard case HDModel.FitError.notEnoughObservations(let count, let required) = error else {
                XCTFail("wrong error: \(error)")
                return
            }
            XCTAssertEqual(count, 5)
            XCTAssertEqual(required, 8)
        }
    }

    func testImputeMatchesPredict() {
        let fit = HDModel.Fit(a: 2.0, b: 0.05, nObs: 10, rmse: 0.01)
        let d: Float = 30
        XCTAssertEqual(
            HDModel.impute(dbhCm: d, fit: fit),
            HDModel.predict(dbhCm: d, a: fit.a, b: fit.b)
        )
    }

    // MARK: - §7.4 rolling update

    func testRollingUpdateMatchesColdFit() throws {
        let aTrue: Float = 2.0
        let bTrue: Float = 0.05
        let dbhs: [Float] = [10, 15, 20, 25, 30, 35, 40, 45, 50, 55]
        let obs: [(dbhCm: Float, heightM: Float)] = dbhs.map { d in
            let h = 1.3 + (d * d) / ((aTrue + bTrue * d) * (aTrue + bTrue * d))
            return (d, h)
        }
        let cold = try HDModel.fit(observations: obs)
        let warm = try HDModel.update(previous: cold, observations: obs)
        // Warm-starting from a converged fit should land on the same fit.
        XCTAssertEqual(warm.a, cold.a, accuracy: 1e-4)
        XCTAssertEqual(warm.b, cold.b, accuracy: 1e-4)
        XCTAssertEqual(warm.nObs, cold.nObs)
    }

    func testRollingUpdateConvergesFromPerturbedPrior() throws {
        // Warm-start from a prior that is 50% off in both directions
        // but still in a reasonable neighbourhood. Must converge to
        // the true parameters.
        let aTrue: Float = 2.0
        let bTrue: Float = 0.05
        let dbhs: [Float] = [10, 15, 20, 25, 30, 35, 40, 45, 50, 55]
        let obs: [(dbhCm: Float, heightM: Float)] = dbhs.map { d in
            let h = 1.3 + (d * d) / ((aTrue + bTrue * d) * (aTrue + bTrue * d))
            return (d, h)
        }
        let perturbed = HDModel.Fit(a: aTrue * 1.5, b: bTrue * 0.5,
                                    nObs: 0, rmse: 99)
        let fit = try HDModel.update(previous: perturbed, observations: obs)
        XCTAssertEqual(fit.a, aTrue, accuracy: aTrue * 0.05)
        XCTAssertEqual(fit.b, bTrue, accuracy: bTrue * 0.05)
    }

    func testRollingUpdateRespectsMinN() {
        let obs: [(dbhCm: Float, heightM: Float)] = [
            (10, 5), (15, 8), (20, 12), (25, 15), (30, 18)
        ]
        XCTAssertThrowsError(
            try HDModel.update(previous: nil, observations: obs)
        ) { err in
            guard case HDModel.FitError.notEnoughObservations = err else {
                XCTFail("Wrong error: \(err)")
                return
            }
        }
    }

    func testRollingUpdateIncorporatesNewObservations() throws {
        // Fit1: 8 points from (aTrue, bTrue).
        // Then add 4 fresh observations consistent with the same curve;
        // the new fit should still recover (aTrue, bTrue) within 5%.
        let aTrue: Float = 2.0
        let bTrue: Float = 0.05
        func h(_ d: Float) -> Float {
            1.3 + (d * d) / ((aTrue + bTrue * d) * (aTrue + bTrue * d))
        }
        let initial: [(dbhCm: Float, heightM: Float)] =
            [10, 15, 20, 25, 30, 35, 40, 45].map { ($0, h($0)) }
        let fit1 = try HDModel.fit(observations: initial)

        let expanded = initial + [50, 55, 60, 65].map { ($0, h($0)) }
        let fit2 = try HDModel.update(previous: fit1, observations: expanded)
        XCTAssertEqual(fit2.a, aTrue, accuracy: aTrue * 0.05)
        XCTAssertEqual(fit2.b, bTrue, accuracy: bTrue * 0.05)
        XCTAssertEqual(fit2.nObs, 12)
    }

    func testFitCoefficientsRoundTrip() throws {
        let fit = HDModel.Fit(a: 1.234, b: 0.0567, nObs: 10, rmse: 0.1)
        let round = HDModel.Fit.fromCoefficients(
            fit.coefficients, nObs: fit.nObs, rmse: fit.rmse)
        XCTAssertNotNil(round)
        XCTAssertEqual(round!.a, fit.a, accuracy: 1e-6)
        XCTAssertEqual(round!.b, fit.b, accuracy: 1e-6)
    }

    func testFitCoefficientsReturnsNilOnMissingKeys() {
        XCTAssertNil(HDModel.Fit.fromCoefficients(["only": 1.0], nObs: 0, rmse: 0))
    }

    func testFitDropsShortHeights() throws {
        // Observations with height ≤ 1.3 m must be dropped before counting.
        let aTrue: Float = 2.0
        let bTrue: Float = 0.05
        let dbhs: [Float] = [10, 15, 20, 25, 30, 35, 40, 45]
        var obs: [(dbhCm: Float, heightM: Float)] = dbhs.map { d in
            (d, 1.3 + (d * d) / ((aTrue + bTrue * d) * (aTrue + bTrue * d)))
        }
        // Add 3 bogus observations with H ≤ 1.3 — should be filtered out.
        let bogus: [(dbhCm: Float, heightM: Float)] = [
            (5, 1.0), (6, 1.3), (7, 0.5)
        ]
        obs.append(contentsOf: bogus)
        let fit = try HDModel.fit(observations: obs)
        XCTAssertEqual(fit.nObs, dbhs.count, "short-height observations dropped")
    }
}
