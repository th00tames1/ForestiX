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

    // MARK: - progress() — what the plot peek reads

    /// A tallied tree with NO measured height.
    private func unmeasured(num: Int, species: String = "DF") -> Tree {
        var t = measuredDF(num: num, species: species)
        t.heightM = nil
        t.heightSource = nil
        return t
    }

    /// An empty plot asks for nothing under every rule — the peek must not
    /// be able to render a fraction that says work is due.
    func testProgressEmptyPlotHasNoTarget() {
        for rule: HeightSubsampleRule in [.allTrees, .none, .everyKth(k: 5),
                                          .perSpeciesCount(minPerSpeciesOnPlot: 3)] {
            let p = HeightSubsample.progress(rule: rule, treesOnPlot: [])
            XCTAssertEqual(p.measured, 0)
            XCTAssertEqual(p.target ?? 0, 0)
        }
    }

    func testProgressAllTreesTargetsEveryLiveTree() {
        let trees = [measuredDF(num: 1), unmeasured(num: 2), unmeasured(num: 3)]
        let p = HeightSubsample.progress(rule: .allTrees, treesOnPlot: trees)
        XCTAssertEqual(p.measured, 1)
        XCTAssertEqual(p.target, 3)
    }

    /// `.none` asks for nothing, so it has no denominator at all — the peek
    /// must show a plain count, never "N of M".
    func testProgressNoneHasNilTarget() {
        let p = HeightSubsample.progress(
            rule: .none, treesOnPlot: [measuredDF(num: 1), unmeasured(num: 2)])
        XCTAssertEqual(p.measured, 1)
        XCTAssertNil(p.target)
    }

    /// k=5 over 11 trees selects #1, #6, #11 — the same three the flow would
    /// have asked for.
    func testProgressEveryKthCountsTheSelectedTrees() {
        let trees = (1...11).map { measuredDF(num: $0) }
        let p = HeightSubsample.progress(rule: .everyKth(k: 5), treesOnPlot: trees)
        XCTAssertEqual(p.target, 3)
        XCTAssertEqual(p.measured, 3)
    }

    /// Heights on #1–#3 satisfy ONE of the three trees k=5 picks. Counting
    /// them all would read "3 of 3" with #6 and #11 still unmeasured.
    func testProgressEveryKthIgnoresOffRuleHeights() {
        let trees = (1...11).map { n in
            n <= 3 ? measuredDF(num: n) : unmeasured(num: n)
        }
        let p = HeightSubsample.progress(rule: .everyKth(k: 5), treesOnPlot: trees)
        XCTAssertEqual(p.target, 3)
        XCTAssertEqual(p.measured, 1)
    }

    func testProgressSkipsSoftDeletedTrees() {
        var gone = measuredDF(num: 2)
        gone.deletedAt = Date()
        let p = HeightSubsample.progress(
            rule: .allTrees, treesOnPlot: [measuredDF(num: 1), gone])
        XCTAssertEqual(p.measured, 1)
        XCTAssertEqual(p.target, 1)
    }

    /// Two species, N=2. All four DF heights must NOT pay for the missing
    /// WH ones: the plot is 2 of 4, not 4 of 4.
    func testProgressPerSpeciesCapsEachSpeciesSeparately() {
        let trees = [measuredDF(num: 1), measuredDF(num: 2),
                     measuredDF(num: 3), measuredDF(num: 4),
                     unmeasured(num: 5, species: "WH"),
                     unmeasured(num: 6, species: "WH")]
        let p = HeightSubsample.progress(
            rule: .perSpeciesCount(minPerSpeciesOnPlot: 2), treesOnPlot: trees)
        XCTAssertEqual(p.measured, 2)
        XCTAssertEqual(p.target, 4)
    }

    /// The target is capped by what the plot actually carries: one lone WH
    /// cannot owe two heights.
    func testProgressPerSpeciesTargetCappedByTreesPresent() {
        let trees = [measuredDF(num: 1), measuredDF(num: 2),
                     measuredDF(num: 3, species: "WH")]
        let p = HeightSubsample.progress(
            rule: .perSpeciesCount(minPerSpeciesOnPlot: 2), treesOnPlot: trees)
        XCTAssertEqual(p.measured, 3)
        XCTAssertEqual(p.target, 3)      // 2 DF + 1 WH — satisfied
    }

    /// An imputed height fills a volume, not a subsample slot.
    func testProgressDoesNotCountImputedHeights() {
        var imputed = measuredDF(num: 2)
        imputed.heightSource = "imputed"
        let p = HeightSubsample.progress(
            rule: .allTrees, treesOnPlot: [measuredDF(num: 1), imputed])
        XCTAssertEqual(p.measured, 1)
        XCTAssertEqual(p.target, 2)
    }
}
