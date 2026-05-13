// Pure tests for PlotStatsCalculator — exercises fixed-area + BAF paths,
// species breakdown, soft-delete exclusion, imputed heights.

import XCTest
@testable import InventoryEngine
import Models
import Common

final class PlotStatsTests: XCTestCase {

    private func species(_ code: String = "DF") -> SpeciesConfig {
        SpeciesConfig(
            code: code, commonName: code, scientificName: code,
            volumeEquationId: "sh_\(code)",
            merchTopDibCm: 12, stumpHeightCm: 30,
            expectedDbhMinCm: 10, expectedDbhMaxCm: 200,
            expectedHeightMinM: 5, expectedHeightMaxM: 80)
    }

    /// SchumacherHall with V = 1e-4 · D² · H — values tiny but predictable.
    private func sh() -> any InventoryEngine.VolumeEquation {
        SchumacherHall(coefficients: [
            "a": 1.0e-4, "b": 2.0, "c": 1.0, "merchFraction": 0.8
        ])
    }

    private func tree(
        num: Int = 1,
        species: String = "DF",
        status: TreeStatus = .live,
        dbhCm: Float = 30,
        heightM: Float? = 25,
        deletedAt: Date? = nil
    ) -> Tree {
        Tree(
            id: UUID(), plotId: UUID(),
            treeNumber: num, speciesCode: species, status: status,
            dbhCm: dbhCm, dbhMethod: .manualCaliper,
            dbhSigmaMm: nil, dbhRmseMm: nil,
            dbhCoverageDeg: nil, dbhNInliers: nil,
            dbhConfidence: .green, dbhIsIrregular: false,
            heightM: heightM,
            heightMethod: heightM == nil ? nil : .manualEntry,
            heightSource: heightM == nil ? nil : "measured",
            heightSigmaM: nil, heightDHM: nil,
            heightAlphaTopDeg: nil, heightAlphaBaseDeg: nil,
            heightConfidence: heightM == nil ? nil : .green,
            bearingFromCenterDeg: nil, distanceFromCenterM: nil,
            boundaryCall: nil, crownClass: nil, damageCodes: [],
            isMultistem: false, parentTreeId: nil,
            notes: "", photoPath: nil, rawScanPath: nil,
            createdAt: Date(timeIntervalSince1970: 0),
            updatedAt: Date(timeIntervalSince1970: 0),
            deletedAt: deletedAt)
    }

    private func fixedDesign(plotAreaAcres: Float = 0.1) -> CruiseDesign {
        CruiseDesign(
            id: UUID(), projectId: UUID(),
            plotType: .fixedArea, plotAreaAcres: plotAreaAcres,
            baf: nil, samplingScheme: .systematicGrid,
            gridSpacingMeters: 50)
    }

    private func bafDesign(baf: Float = 1.0) -> CruiseDesign {
        CruiseDesign(
            id: UUID(), projectId: UUID(),
            plotType: .variableRadius, plotAreaAcres: nil,
            baf: baf, samplingScheme: .systematicGrid,
            gridSpacingMeters: 50)
    }

    // MARK: - Empty

    func testEmptyPlotReturnsEmpty() {
        let r = PlotStatsCalculator.compute(
            plot: makePlot(plotAreaAcres: 0.1), cruiseDesign: fixedDesign(),
            trees: [], species: ["DF": species()],
            volumeEquations: ["DF": sh()])
        XCTAssertEqual(r, .empty)
    }

    // MARK: - Fixed-area basic math

    func testFixedAreaTpaAndBaAndQmd() {
        // 2 trees on 0.1 acre → EF = 10 → TPA = 20.
        let trees = [tree(num: 1, dbhCm: 30), tree(num: 2, dbhCm: 40)]
        let r = PlotStatsCalculator.compute(
            plot: makePlot(plotAreaAcres: 0.1),
            cruiseDesign: fixedDesign(),
            trees: trees, species: ["DF": species()],
            volumeEquations: ["DF": sh()])
        XCTAssertEqual(r.liveTreeCount, 2)
        XCTAssertEqual(r.tpa, 20, accuracy: 1e-3)

        // BA: π·(0.3)²/4 + π·(0.4)²/4 = 0.070686 + 0.125664 = 0.19635 m²/plot
        // × EF=10 ⇒ 1.9635 m²/acre
        XCTAssertEqual(r.baPerAcreM2, 1.9635, accuracy: 1e-3)

        // QMD = sqrt((30²+40²)/2) = sqrt(1250) = 35.355
        XCTAssertEqual(r.qmdCm, 35.3553, accuracy: 1e-3)
    }

    func testFixedAreaVolumeScalesByEF() {
        // Single tree, V = 1e-4 · 30² · 25 = 2.25 m³/tree.
        // EF = 1/0.1 = 10 ⇒ 22.5 m³/acre. Merch = 0.8 × gross = 18.0.
        let trees = [tree(dbhCm: 30, heightM: 25)]
        let r = PlotStatsCalculator.compute(
            plot: makePlot(plotAreaAcres: 0.1),
            cruiseDesign: fixedDesign(),
            trees: trees, species: ["DF": species()],
            volumeEquations: ["DF": sh()])
        XCTAssertEqual(r.grossVolumePerAcreM3, 22.5, accuracy: 1e-3)
        XCTAssertEqual(r.merchVolumePerAcreM3, 18.0, accuracy: 1e-3)
    }

    // MARK: - Status / soft-delete filtering

    func testDeadAndSoftDeletedExcluded() {
        let trees = [
            tree(num: 1, dbhCm: 30),
            tree(num: 2, status: .deadStanding, dbhCm: 50),
            tree(num: 3, dbhCm: 40, deletedAt: Date())
        ]
        let r = PlotStatsCalculator.compute(
            plot: makePlot(plotAreaAcres: 0.1),
            cruiseDesign: fixedDesign(),
            trees: trees, species: ["DF": species()],
            volumeEquations: ["DF": sh()])
        XCTAssertEqual(r.liveTreeCount, 1)
        XCTAssertEqual(r.tpa, 10, accuracy: 1e-3)
    }

    // MARK: - BAF path

    func testBAFTpaBA() {
        // BAF = 1, two trees DBH 30 and 40.
        // tree1 BA = 0.070686 m² ⇒ EF = 1/0.070686 ≈ 14.147
        // tree2 BA = 0.125664 m² ⇒ EF ≈ 7.958
        // TPA ≈ 22.105. BA/ac = n·BAF = 2·1 = 2.
        let trees = [tree(num: 1, dbhCm: 30), tree(num: 2, dbhCm: 40)]
        let r = PlotStatsCalculator.compute(
            plot: makePlot(plotAreaAcres: 0.1),  // unused for BAF
            cruiseDesign: bafDesign(baf: 1.0),
            trees: trees, species: ["DF": species()],
            volumeEquations: ["DF": sh()])
        XCTAssertEqual(r.tpa, 22.105, accuracy: 0.01)
        XCTAssertEqual(r.baPerAcreM2, 2.0, accuracy: 1e-4)
    }

    // MARK: - Species breakdown

    func testSpeciesBreakdown() {
        let trees = [
            tree(num: 1, species: "DF", dbhCm: 30),
            tree(num: 2, species: "DF", dbhCm: 40),
            tree(num: 3, species: "WH", dbhCm: 25)
        ]
        let r = PlotStatsCalculator.compute(
            plot: makePlot(plotAreaAcres: 0.1),
            cruiseDesign: fixedDesign(),
            trees: trees, species: ["DF": species("DF"), "WH": species("WH")],
            volumeEquations: ["DF": sh(), "WH": sh()])
        XCTAssertEqual(r.bySpecies["DF"]?.count, 2)
        XCTAssertEqual(r.bySpecies["WH"]?.count, 1)
        let dfTpa = r.bySpecies["DF"]?.tpa ?? 0
        let whTpa = r.bySpecies["WH"]?.tpa ?? 0
        XCTAssertEqual(dfTpa + whTpa, r.tpa, accuracy: 1e-3)
    }

    // MARK: - Imputation

    func testImputedHeightUsedForVolume() {
        // Tree missing heightM but has fit → height = 1.3 + 30²/(1+0.05·30)² = 1.3 + 900/6.25 = 145.3
        // Volume = 1e-4 · 900 · 145.3 = 13.077. EF=10 ⇒ 130.77.
        let fit = HDModel.Fit(a: 1.0, b: 0.05, nObs: 10, rmse: 1.0)
        let trees = [tree(dbhCm: 30, heightM: nil)]
        let r = PlotStatsCalculator.compute(
            plot: makePlot(plotAreaAcres: 0.1),
            cruiseDesign: fixedDesign(),
            trees: trees, species: ["DF": species()],
            volumeEquations: ["DF": sh()],
            hdFits: ["DF": fit])
        XCTAssertGreaterThan(r.grossVolumePerAcreM3, 0)
        XCTAssertEqual(r.grossVolumePerAcreM3, 130.77, accuracy: 0.5)
    }

    func testMissingHeightAndNoFitContributesZeroVolume() {
        let trees = [tree(dbhCm: 30, heightM: nil)]
        let r = PlotStatsCalculator.compute(
            plot: makePlot(plotAreaAcres: 0.1),
            cruiseDesign: fixedDesign(),
            trees: trees, species: ["DF": species()],
            volumeEquations: ["DF": sh()],
            hdFits: [:])
        XCTAssertEqual(r.grossVolumePerAcreM3, 0, accuracy: 1e-6)
        // But TPA/BA should still count.
        XCTAssertEqual(r.tpa, 10, accuracy: 1e-3)
    }

    // MARK: - Performance (REQ-TAL-005)

    func testPerformanceBound() {
        let trees = (0..<200).map { tree(num: $0 + 1, dbhCm: Float(10 + $0 % 40)) }
        let plot = makePlot(plotAreaAcres: 0.1)
        let design = fixedDesign()
        let speciesMap = ["DF": species()]
        let volMap: [String: any InventoryEngine.VolumeEquation] = ["DF": sh()]
        measure {
            for _ in 0..<50 {
                _ = PlotStatsCalculator.compute(
                    plot: plot, cruiseDesign: design,
                    trees: trees, species: speciesMap,
                    volumeEquations: volMap)
            }
        }
    }
}
