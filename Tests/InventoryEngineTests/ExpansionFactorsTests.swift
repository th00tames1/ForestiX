// Spec §7.6 Expansion Factors — fixed-area and BAF forms.

import XCTest
import Models
@testable import InventoryEngine

final class ExpansionFactorsTests: XCTestCase {

    func testFixedAreaEF() {
        XCTAssertEqual(ExpansionFactors.fixedArea(plotAreaAcres: 0.1), 10, accuracy: 1e-6)
        XCTAssertEqual(ExpansionFactors.fixedArea(plotAreaAcres: 0.2), 5, accuracy: 1e-6)
    }

    func testVariableRadiusEF() {
        // BAF is ft²/ac, so the tree's basal area enters in ft² too:
        // DBH 40 cm = 1.35263 ft². EF = 20/1.35263 ≈ 14.79 trees/acre.
        XCTAssertEqual(
            ExpansionFactors.variableRadius(baf: 20, dbhCm: 40),
            14.786,
            accuracy: 0.01
        )
    }

    func testPerAcreFixedAreaSumsAttribute() {
        let trees = [makeTree(dbhCm: 20), makeTree(dbhCm: 30), makeTree(dbhCm: 40)]
        // count → 3 · 10 = 30 TPA
        let tpa = ExpansionFactors.perAcreFixedArea(trees: trees, plotAreaAcres: 0.1) { _ in Float(1) }
        XCTAssertEqual(tpa, 30, accuracy: 1e-5)
    }

    func testPerAcreFixedAreaExcludesSoftDeleted() {
        let trees = [
            makeTree(dbhCm: 20),
            makeTree(dbhCm: 30, deletedAt: Date())
        ]
        let tpa = ExpansionFactors.perAcreFixedArea(trees: trees, plotAreaAcres: 0.1) { _ in Float(1) }
        XCTAssertEqual(tpa, 10, accuracy: 1e-5)
    }

    func testPerAcreBAFSumsWeightedByEF() {
        // 3 identical trees DBH 30 = 0.76086 ft², BAF 20 ⇒ EF = 26.286 each.
        // Σ attr·EF where attr = 1 → 3 · 26.286 ≈ 78.86 trees/acre.
        let trees = (0..<3).map { _ in makeTree(dbhCm: 30) }
        let tpa = ExpansionFactors.perAcreBAF(trees: trees, baf: 20) { _ in Float(1) }
        XCTAssertEqual(tpa, 78.86, accuracy: 0.05)
    }
}
