// Spec §7.6 Plot & Tree Computations — pure-function unit tests.

import XCTest
import Models
import Common
@testable import InventoryEngine

final class BasalAreaMathTests: XCTestCase {

    func testBasalAreaKnownValue() {
        // 40 cm DBH ⇒ BA = π·0.40²/4 = 0.12566 m²
        XCTAssertEqual(basalAreaM2(dbhCm: 40), 0.12566, accuracy: 1e-4)
    }

    func testBasalAreaZero() {
        XCTAssertEqual(basalAreaM2(dbhCm: 0), 0)
    }

    func testTPAIsExpansion() {
        let plot = makePlot(plotAreaAcres: 0.1)
        let trees = [makeTree(dbhCm: 25), makeTree(dbhCm: 30), makeTree(dbhCm: 35)]
        // 3 trees in a 1/10-acre plot ⇒ TPA = 30.
        XCTAssertEqual(tpa(plot: plot, trees: trees), 30, accuracy: 1e-5)
    }

    func testTPAExcludesSoftDeleted() {
        let plot = makePlot(plotAreaAcres: 0.1)
        let trees = [
            makeTree(dbhCm: 25),
            makeTree(dbhCm: 30, deletedAt: Date())
        ]
        XCTAssertEqual(tpa(plot: plot, trees: trees), 10, accuracy: 1e-5)
    }

    func testBaPerAcreScalesByEF() {
        let plot = makePlot(plotAreaAcres: 0.1)   // EF = 10
        let trees = [makeTree(dbhCm: 40)]         // BA = 0.12566
        // 0.12566 · 10 = 1.2566 m²/ac
        XCTAssertEqual(baPerAcre(plot: plot, trees: trees), 1.2566, accuracy: 1e-3)
    }

    func testQMDFormula() {
        // DBHs [20, 30, 40] ⇒ QMD = sqrt((400+900+1600)/3) = sqrt(966.67) ≈ 31.09
        let trees = [makeTree(dbhCm: 20), makeTree(dbhCm: 30), makeTree(dbhCm: 40)]
        XCTAssertEqual(qmd(trees: trees), 31.0912, accuracy: 1e-3)
    }

    func testQMDEmpty() {
        XCTAssertEqual(qmd(trees: []), 0)
    }

    func testTreeFactorBAF() {
        // THE DIVIDE IS IN SQUARE FEET, because that is what a BAF counts.
        // BAF 20 ft²/ac, DBH 40 cm ⇒ BA = 1.35263 ft² ⇒ TF = 20 / 1.35263 = 14.79
        // trees/acre. Dividing by the metric area instead gave 159.15 — 10.76×
        // too many stems, and it reached the tally, the CSV and the client PDF.
        let tree = makeTree(dbhCm: 40)
        XCTAssertEqual(treeFactorBAF(tree: tree, baf: 20), 14.786, accuracy: 0.01)
    }

    func testBaPerAcreBAF() {
        // 7 "in" trees, BAF = 20 ⇒ 140 ft²/ac. RETURNED IN m²/acre, so that
        // this and `baPerAcre` mean the same thing whichever plot type filled
        // them: 140 × 0.09290304 = 13.006.
        let trees = (0..<7).map { _ in makeTree(dbhCm: 30) }
        XCTAssertEqual(baPerAcreBAF(trees: trees, baf: 20), 13.006, accuracy: 0.01)
    }
}
