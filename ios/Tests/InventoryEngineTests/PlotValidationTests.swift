// Pure unit tests for PlotValidation.validatePlotForClose. Exercises each
// issue code path + soft-delete exclusion.

import XCTest
@testable import InventoryEngine
import Models
import Common

final class PlotValidationTests: XCTestCase {

    private func species(
        code: String = "DF",
        dbhMin: Float = 10,
        dbhMax: Float = 150
    ) -> SpeciesConfig {
        SpeciesConfig(
            code: code,
            commonName: code == "DF" ? "Douglas-fir" : code,
            scientificName: "Pseudotsuga menziesii",
            volumeEquationId: "bruce_df",
            merchTopDibCm: 12,
            stumpHeightCm: 30,
            expectedDbhMinCm: dbhMin,
            expectedDbhMaxCm: dbhMax,
            expectedHeightMinM: 5,
            expectedHeightMaxM: 80)
    }

    private func tree(
        number: Int = 1,
        speciesCode: String = "DF",
        status: TreeStatus = .live,
        dbhCm: Float = 30,
        dbhConfidence: ConfidenceTier = .green,
        heightM: Float? = 25,
        heightSource: String? = "measured",
        heightConfidence: ConfidenceTier? = .green,
        deletedAt: Date? = nil
    ) -> Tree {
        Tree(
            id: UUID(), plotId: UUID(),
            treeNumber: number,
            speciesCode: speciesCode,
            status: status,
            dbhCm: dbhCm,
            dbhMethod: .manualCaliper,
            dbhSigmaMm: nil, dbhRmseMm: nil,
            dbhCoverageDeg: nil, dbhNInliers: nil,
            dbhConfidence: dbhConfidence,
            dbhIsIrregular: false,
            heightM: heightM,
            heightMethod: heightM == nil ? nil : .manualEntry,
            heightSource: heightSource,
            heightSigmaM: nil, heightDHM: nil,
            heightAlphaTopDeg: nil, heightAlphaBaseDeg: nil,
            heightConfidence: heightConfidence,
            bearingFromCenterDeg: nil,
            distanceFromCenterM: nil,
            boundaryCall: nil,
            crownClass: nil, damageCodes: [],
            isMultistem: false, parentTreeId: nil,
            notes: "", photoPath: nil, rawScanPath: nil,
            createdAt: Date(timeIntervalSince1970: 0),
            updatedAt: Date(timeIntervalSince1970: 0),
            deletedAt: deletedAt)
    }

    // MARK: - Happy path

    func testAllGreenClean() {
        let plot = makePlot()
        let trees = [tree(number: 1, dbhCm: 30), tree(number: 2, dbhCm: 45)]
        let r = PlotValidation.validatePlotForClose(
            plot: plot, trees: trees, speciesByCode: ["DF": species()])
        XCTAssertTrue(r.canClose)
        XCTAssertFalse(r.hasErrors)
        XCTAssertFalse(r.hasWarnings)
    }

    // MARK: - Errors

    func testUnknownSpeciesIsError() {
        let plot = makePlot()
        let trees = [tree(number: 1, speciesCode: "ZZ", dbhCm: 25)]
        let r = PlotValidation.validatePlotForClose(
            plot: plot, trees: trees, speciesByCode: ["DF": species()])
        XCTAssertFalse(r.canClose)
        XCTAssertEqual(r.errors.count, 1)
        XCTAssertEqual(r.errors[0].code, PlotValidation.Code.unknownSpecies)
    }

    // MARK: - Warnings

    func testDbhBelowMinWarns() {
        let plot = makePlot()
        let trees = [tree(number: 1, dbhCm: 5)]  // min = 10
        let r = PlotValidation.validatePlotForClose(
            plot: plot, trees: trees, speciesByCode: ["DF": species()])
        XCTAssertTrue(r.canClose)
        XCTAssertEqual(r.warnings.map(\.code), [PlotValidation.Code.dbhBelowMin])
    }

    func testDbhAboveMaxWarns() {
        let plot = makePlot()
        let trees = [tree(number: 1, dbhCm: 200)]  // max = 150
        let r = PlotValidation.validatePlotForClose(
            plot: plot, trees: trees, speciesByCode: ["DF": species()])
        XCTAssertEqual(r.warnings.map(\.code), [PlotValidation.Code.dbhAboveMax])
    }

    func testRedTierDbhWarns() {
        let plot = makePlot()
        let trees = [tree(number: 1, dbhCm: 30, dbhConfidence: .red)]
        let r = PlotValidation.validatePlotForClose(
            plot: plot, trees: trees, speciesByCode: ["DF": species()])
        XCTAssertEqual(r.warnings.map(\.code), [PlotValidation.Code.redTierDbh])
    }

    func testRedTierHeightWarnsForLive() {
        let plot = makePlot()
        let trees = [tree(number: 1, heightConfidence: .red)]
        let r = PlotValidation.validatePlotForClose(
            plot: plot, trees: trees, speciesByCode: ["DF": species()])
        XCTAssertEqual(r.warnings.map(\.code), [PlotValidation.Code.redTierHeight])
    }

    func testMissingHeightOnLiveWarns() {
        let plot = makePlot()
        let trees = [tree(
            number: 1,
            heightM: nil, heightSource: nil, heightConfidence: nil)]
        let r = PlotValidation.validatePlotForClose(
            plot: plot, trees: trees, speciesByCode: ["DF": species()])
        XCTAssertEqual(r.warnings.map(\.code), [PlotValidation.Code.missingHeightOnLive])
    }

    func testImputedHeightDoesNotWarn() {
        let plot = makePlot()
        let trees = [tree(
            number: 1,
            heightM: nil, heightSource: "imputed", heightConfidence: .yellow)]
        let r = PlotValidation.validatePlotForClose(
            plot: plot, trees: trees, speciesByCode: ["DF": species()])
        XCTAssertFalse(r.warnings.contains { $0.code == PlotValidation.Code.missingHeightOnLive })
    }

    func testEmptyPlotWarnsButAllowsClose() {
        let plot = makePlot()
        let r = PlotValidation.validatePlotForClose(
            plot: plot, trees: [], speciesByCode: ["DF": species()])
        XCTAssertTrue(r.canClose)
        XCTAssertEqual(r.warnings.map(\.code), [PlotValidation.Code.noTrees])
    }

    // MARK: - Soft-delete exclusion

    func testSoftDeletedTreesAreIgnored() {
        let plot = makePlot()
        let trees = [
            tree(number: 1, speciesCode: "ZZ", dbhCm: 5, deletedAt: Date()),
            tree(number: 2, dbhCm: 30)
        ]
        let r = PlotValidation.validatePlotForClose(
            plot: plot, trees: trees, speciesByCode: ["DF": species()])
        XCTAssertTrue(r.canClose)
        XCTAssertFalse(r.hasErrors)
        XCTAssertFalse(r.hasWarnings)
    }

    // MARK: - Multi-issue aggregation

    func testMultipleIssuesAggregate() {
        let plot = makePlot()
        let trees = [
            tree(number: 1, speciesCode: "ZZ", dbhCm: 30),     // unknown → error
            tree(number: 2, dbhCm: 5),                         // below min
            tree(number: 3, dbhCm: 30, dbhConfidence: .red)    // red tier
        ]
        let r = PlotValidation.validatePlotForClose(
            plot: plot, trees: trees, speciesByCode: ["DF": species()])
        XCTAssertFalse(r.canClose)
        XCTAssertEqual(r.errors.count, 1)
        XCTAssertEqual(r.warnings.count, 2)
    }
}
