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
        // BAF 20, DBH 40 cm: BA = π·0.4²/4 = 0.12566 m². EF = 20/0.12566 ≈ 159.15.
        XCTAssertEqual(
            ExpansionFactors.variableRadius(baf: 20, dbhCm: 40),
            159.155,
            accuracy: 0.05
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
        // 3 identical trees DBH 30, BAF 20. BA = 0.07068; EF = 20/0.07068 ≈ 282.94.
        // Σ attr·EF where attr = 1 → 3 · 282.94 ≈ 848.83.
        let trees = (0..<3).map { _ in makeTree(dbhCm: 30) }
        let tpa = ExpansionFactors.perAcreBAF(trees: trees, baf: 20) { _ in Float(1) }
        XCTAssertEqual(tpa, 848.83, accuracy: 0.5)
    }
}
