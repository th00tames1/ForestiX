// Phase 6 — GeoJSON full-cruise export: tests that the new `cruise(…)`
// entry point emits measured-plot Point features with the expected
// properties, including `positionTier`, `closedAt`, and the distinction
// between visited and unvisited planned plots.

import XCTest
import Models
@testable import Export

final class GeoJSONCruiseTests: XCTestCase {

    private func parseCollection(_ s: String) throws -> [String: Any] {
        try XCTUnwrap(try JSONSerialization.jsonObject(
            with: Data(s.utf8)) as? [String: Any])
    }

    func testCruiseExportIncludesMeasuredPlotPoints() throws {
        let strata = ExportFixtures.strata()
        let planned = ExportFixtures.plannedPlots()
        let plots = ExportFixtures.plots()
        let text = try GeoJSONExporter.cruise(
            strata: strata, plannedPlots: planned, plots: plots)
        let obj = try parseCollection(text)
        let features = try XCTUnwrap(obj["features"] as? [[String: Any]])

        // 2 strata + 4 planned + 3 measured = 9.
        XCTAssertEqual(features.count, strata.count + planned.count + plots.count)

        // There should be exactly `plots.count` measured-plot features.
        let measured = features.compactMap {
            ($0["properties"] as? [String: Any])?["kind"] as? String == "measuredPlot"
            ? $0 : nil
        }
        XCTAssertEqual(measured.count, plots.count)

        // Measured plots carry positionTier.
        let props = try XCTUnwrap(measured[0]["properties"] as? [String: Any])
        XCTAssertEqual(props["positionTier"] as? String, "B")
        XCTAssertNotNil(props["closedAt"] as? String)
    }

    func testPlannedVsVisitedIsDistinguished() throws {
        let planned = ExportFixtures.plannedPlots()
        let text = try GeoJSONExporter.cruise(
            strata: [], plannedPlots: planned, plots: [])
        let obj = try parseCollection(text)
        let features = try XCTUnwrap(obj["features"] as? [[String: Any]])
        let plannedFeatures = features.filter {
            ($0["properties"] as? [String: Any])?["kind"] as? String == "plannedPlot"
        }
        let visitedCount = plannedFeatures.compactMap {
            ($0["properties"] as? [String: Any])?["visited"] as? Bool
        }.filter { $0 }.count
        // Fixture: 3 of 4 planned plots are visited.
        XCTAssertEqual(visitedCount, 3)
        XCTAssertEqual(plannedFeatures.count - visitedCount, 1)
    }

    func testOutputIsDeterministic() throws {
        let strata = ExportFixtures.strata()
        let planned = ExportFixtures.plannedPlots()
        let plots = ExportFixtures.plots()
        let a = try GeoJSONExporter.cruise(
            strata: strata, plannedPlots: planned, plots: plots)
        let b = try GeoJSONExporter.cruise(
            strata: strata, plannedPlots: planned, plots: plots)
        XCTAssertEqual(a, b)
    }
}
