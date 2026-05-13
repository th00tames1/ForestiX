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
        // BAF 20 (ft²/ac), DBH 40 cm ⇒ BA = 0.12566 m² ⇒ TF = 20 / 0.12566 = 159.15
        let tree = makeTree(dbhCm: 40)
        XCTAssertEqual(treeFactorBAF(tree: tree, baf: 20), 159.15, accuracy: 0.05)
    }

    func testBaPerAcreBAF() {
        // 7 "in" trees, BAF = 20 ⇒ BA/ac = 140.
        let trees = (0..<7).map { _ in makeTree(dbhCm: 30) }
        XCTAssertEqual(baPerAcreBAF(trees: trees, baf: 20), 140)
    }
}
