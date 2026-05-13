// Pure tests for HeightSubsample.shouldMeasureHeight — covers each rule case
// and soft-delete exclusion.

import XCTest
@testable import InventoryEngine
import Models
import Common

final class HeightSubsampleTests: XCTestCase {

    private func measuredDF(num: Int, species: String = "DF") -> Tree {
        Tree(
            id: UUID(), plotId: UUID(),
            treeNumber: num, speciesCode: species, status: .live,
            dbhCm: 30, dbhMethod: .manualCaliper,
            dbhSigmaMm: nil, dbhRmseMm: nil,
            dbhCoverageDeg: nil, dbhNInliers: nil,
            dbhConfidence: .green, dbhIsIrregular: false,
            heightM: 25, heightMethod: .manualEntry, heightSource: "measured",
            heightSigmaM: nil, heightDHM: nil,
            heightAlphaTopDeg: nil, heightAlphaBaseDeg: nil,
            heightConfidence: .green,
            bearingFromCenterDeg: nil, distanceFromCenterM: nil,
            boundaryCall: nil,
            crownClass: nil, damageCodes: [],
            isMultistem: false, parentTreeId: nil,
            notes: "", photoPath: nil, rawScanPath: nil,
            createdAt: Date(timeIntervalSince1970: 0),
            updatedAt: Date(timeIntervalSince1970: 0),
            deletedAt: nil)
    }

    func testAllTrees() {
        XCTAssertTrue(HeightSubsample.shouldMeasureHeight(
            rule: .allTrees, newTreeNumber: 17,
            newSpeciesCode: "DF", existingTreesOnPlot: []))
    }

    func testNone() {
        XCTAssertFalse(HeightSubsample.shouldMeasureHeight(
            rule: .none, newTreeNumber: 1,
            newSpeciesCode: "DF", existingTreesOnPlot: []))
    }

    func testEveryKthFirstTreeMeasured() {
        // k=5: treeNumbers 1, 6, 11, ... measured
        XCTAssertTrue(HeightSubsample.shouldMeasureHeight(
            rule: .everyKth(k: 5), newTreeNumber: 1,
            newSpeciesCode: "DF", existingTreesOnPlot: []))
        XCTAssertTrue(HeightSubsample.shouldMeasureHeight(
            rule: .everyKth(k: 5), newTreeNumber: 6,
            newSpeciesCode: "DF", existingTreesOnPlot: []))
        XCTAssertFalse(HeightSubsample.shouldMeasureHeight(
            rule: .everyKth(k: 5), newTreeNumber: 2,
            newSpeciesCode: "DF", existingTreesOnPlot: []))
        XCTAssertFalse(HeightSubsample.shouldMeasureHeight(
            rule: .everyKth(k: 5), newTreeNumber: 5,
            newSpeciesCode: "DF", existingTreesOnPlot: []))
    }

    func testEveryKthK1AlwaysMeasure() {
        for n in 1...10 {
            XCTAssertTrue(HeightSubsample.shouldMeasureHeight(
                rule: .everyKth(k: 1), newTreeNumber: n,
                newSpeciesCode: "DF", existingTreesOnPlot: []))
        }
    }

    func testPerSpeciesBelowThreshold() {
        let trees = [measuredDF(num: 1)]  // 1 measured DF
        XCTAssertTrue(HeightSubsample.shouldMeasureHeight(
            rule: .perSpeciesCount(minPerSpeciesOnPlot: 3),
            newTreeNumber: 2,
            newSpeciesCode: "DF",
            existingTreesOnPlot: trees))
    }

    func testPerSpeciesAtThreshold() {
        let trees = [measuredDF(num: 1), measuredDF(num: 2), measuredDF(num: 3)]
        XCTAssertFalse(HeightSubsample.shouldMeasureHeight(
            rule: .perSpeciesCount(minPerSpeciesOnPlot: 3),
            newTreeNumber: 4,
            newSpeciesCode: "DF",
            existingTreesOnPlot: trees))
    }

    func testPerSpeciesIsSpeciesSpecific() {
        // 3 DFs measured, but new tree is WH → still needs measurement.
        let trees = [measuredDF(num: 1), measuredDF(num: 2), measuredDF(num: 3)]
        XCTAssertTrue(HeightSubsample.shouldMeasureHeight(
            rule: .perSpeciesCount(minPerSpeciesOnPlot: 3),
            newTreeNumber: 4,
            newSpeciesCode: "WH",
            existingTreesOnPlot: trees))
    }

    func testPerSpeciesIgnoresSoftDeleted() {
        var t = measuredDF(num: 1)
        t.deletedAt = Date()
        XCTAssertTrue(HeightSubsample.shouldMeasureHeight(
            rule: .perSpeciesCount(minPerSpeciesOnPlot: 1),
            newTreeNumber: 2,
            newSpeciesCode: "DF",
            existingTreesOnPlot: [t]))
    }

    func testPerSpeciesIgnoresImputedHeights() {
        var t = measuredDF(num: 1)
        t.heightSource = "imputed"
        XCTAssertTrue(HeightSubsample.shouldMeasureHeight(
            rule: .perSpeciesCount(minPerSpeciesOnPlot: 1),
            newTreeNumber: 2,
            newSpeciesCode: "DF",
            existingTreesOnPlot: [t]))
    }
}
