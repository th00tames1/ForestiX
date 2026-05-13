// Phase 6 — plot-level and stand-summary CSV round-trip tests.
//
// Uses the shared ExportBundleBuilder / StubDataSource so PlotStats and
// StandStat come out of the real inventory-engine code paths rather than
// from synthetic initialisers (whose memberwise inits are internal).

import XCTest
import Models
import InventoryEngine
@testable import Export

final class PlotAndStandCSVTests: XCTestCase {

    func testPlotsCsvHeaderIncludesPositionAndStatsColumns() {
        let plots = ExportFixtures.plots()
        let csv = CSVExporter.plotsCSV(plots: plots, statsByPlot: [:])
        let lines = csv.components(separatedBy: "\r\n").filter { !$0.isEmpty }
        XCTAssertTrue(lines[0].contains("plot_number"))
        XCTAssertTrue(lines[0].contains("position_tier"))
        XCTAssertTrue(lines[0].contains("ba_per_acre_m2"))
        XCTAssertTrue(lines[0].contains("gross_v_per_acre_m3"))
        XCTAssertEqual(lines.count - 1, plots.count)
    }

    func testPlotsCsvFillsStatsComputedFromBundle() throws {
        let bundle = try ExportBundleBuilder.build(
            using: ExportFixtures.StubDataSource(),
            at: ExportFixtures.fixedDate)
        XCTAssertFalse(bundle.plotStatsByPlot.isEmpty,
                       "fixture plots should have computed stats")
        let csv = CSVExporter.plotsCSV(
            plots: bundle.plots, statsByPlot: bundle.plotStatsByPlot)
        let lines = csv.components(separatedBy: "\r\n").filter { !$0.isEmpty }

        // Plot #2 in the fixture has 5 undeleted trees at 0.1 ac → TPA=50.
        // (Plot #1 has one soft-deleted tree, so it's 4 live / TPA=40.)
        let plot2Row = lines.first {
            $0.hasPrefix("\(bundle.plots[1].id.uuidString),2,")
        }!
        XCTAssertTrue(plot2Row.contains(",5,"), "plot 2 live trees = 5")
        XCTAssertTrue(plot2Row.contains(",50.00,"), "plot 2 TPA = 50")

        let plot1Row = lines.first {
            $0.hasPrefix("\(bundle.plots[0].id.uuidString),1,")
        }!
        XCTAssertTrue(plot1Row.contains(",4,"), "plot 1 live = 4 (one soft-deleted)")
    }

    func testStandSummaryCsvEmitsRowsForEachStratumAndTotal() throws {
        let bundle = try ExportBundleBuilder.build(
            using: ExportFixtures.StubDataSource(),
            at: ExportFixtures.fixedDate)
        let csv = CSVExporter.standSummaryCSV(
            tpa: bundle.tpaStand,
            ba: bundle.baStand,
            volume: bundle.volStand,
            stratumNamesByKey: bundle.stratumNamesByKey)
        let rows = csv.components(separatedBy: "\r\n").filter { !$0.isEmpty }
        XCTAssertTrue(rows[0].hasPrefix("metric,stratum_key,stratum_name,"))
        XCTAssertTrue(rows.contains { $0.contains("tpa") && $0.contains("TOTAL") })
        XCTAssertTrue(rows.contains { $0.contains("ba_per_acre_m2") })
        XCTAssertTrue(rows.contains { $0.contains("gross_v_per_acre_m3") })
    }
}
