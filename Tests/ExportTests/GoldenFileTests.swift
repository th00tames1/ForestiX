// Phase 6 — byte-level golden file test.
//
// We can't pin a SHA across runs for the PDF (CoreText font subsetting
// introduces slight variance) or the shapefile ZIP (DOS timestamp in
// each local file header reflects the wall clock). But the three
// string-based formats (tree CSV, plot CSV, stand-summary CSV) and the
// cruise GeoJSON serialise deterministically on identical inputs, so
// we SHA-256 them and assert against a constant.
//
// When a legitimate format change lands, run the one-liner at the bottom
// of this file to regenerate the hashes. Any change without updating
// this golden hash is rejected by CI.

import XCTest
import CryptoKit
import Models
import InventoryEngine
@testable import Export

final class GoldenFileTests: XCTestCase {

    private func sha(_ data: Data) -> String {
        SHA256.hash(data: data)
            .map { String(format: "%02x", $0) }
            .joined()
    }

    func testTreesCsvGoldenHash() {
        let csv = CSVExporter.treesCSV(trees: ExportFixtures.trees())
        XCTAssertFalse(csv.isEmpty)
        // Recompute if export semantics change.
        // print("trees.csv:", sha(Data(csv.utf8)))
        XCTAssertEqual(
            sha(Data(csv.utf8)),
            Golden.treesCSV,
            "tree-level CSV bytes changed — if intentional, update Golden.treesCSV")
    }

    func testPlotsCsvGoldenHash() throws {
        let bundle = try ExportBundleBuilder.build(
            using: ExportFixtures.StubDataSource(),
            at: ExportFixtures.fixedDate)
        let csv = CSVExporter.plotsCSV(
            plots: bundle.plots, statsByPlot: bundle.plotStatsByPlot)
        // print("plots.csv:", sha(Data(csv.utf8)))
        XCTAssertEqual(
            sha(Data(csv.utf8)),
            Golden.plotsCSV,
            "plot-level CSV bytes changed — if intentional, update Golden.plotsCSV")
    }

    func testStandSummaryCsvGoldenHash() throws {
        let bundle = try ExportBundleBuilder.build(
            using: ExportFixtures.StubDataSource(),
            at: ExportFixtures.fixedDate)
        let csv = CSVExporter.standSummaryCSV(
            tpa: bundle.tpaStand,
            ba: bundle.baStand,
            volume: bundle.volStand,
            stratumNamesByKey: bundle.stratumNamesByKey)
        // print("stand.csv:", sha(Data(csv.utf8)))
        XCTAssertEqual(
            sha(Data(csv.utf8)),
            Golden.standSummaryCSV,
            "stand summary CSV bytes changed — if intentional, update Golden.standSummaryCSV")
    }

    func testCruiseGeoJsonGoldenHash() throws {
        let text = try GeoJSONExporter.cruise(
            strata: ExportFixtures.strata(),
            plannedPlots: ExportFixtures.plannedPlots(),
            plots: ExportFixtures.plots())
        // print("cruise.geojson:", sha(Data(text.utf8)))
        XCTAssertEqual(
            sha(Data(text.utf8)),
            Golden.cruiseGeoJSON,
            "cruise GeoJSON bytes changed — if intentional, update Golden.cruiseGeoJSON")
    }
}

/// Canonical SHA-256 of each exported artefact for the Phase 6 fixture.
/// Populated by `xcrun swift test --filter GoldenFileTests` once on a
/// clean branch; treat later changes as a deliberate review event.
enum Golden {
    static let treesCSV: String = "a03f1117f96a31a60c767938f06cb56151169d2e95d9e56bf19b71020db8db3f"
    static let plotsCSV: String = "c2dd5e1ff6330ace9fb4b9ffe7ac605b1f51ce76368b4966ff06775bd712340c"
    static let standSummaryCSV: String = "5fbb5dbbeb1c36a7df3b12ec89c3c9741396d9b8514fc7b22b42f91774b2ff12"
    static let cruiseGeoJSON: String = "f5c46d7a18f34b65a36f050c6093c1b9adf29c066c0b9edbd3d95d29e4112c56"
}
