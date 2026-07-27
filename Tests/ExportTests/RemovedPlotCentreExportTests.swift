// A plot with NO CENTRE must never export as a real point.
//
// (0, 0) is the sentinel a Plot is born with and the value the map's
// "Remove plot" writes back. It is a sentinel, not a coordinate — and
// 0°N 0°E is in the Gulf of Guinea, roughly 600 km off Ghana, so an
// exporter that passes it through ships a cruise whose bounding box
// spans the Atlantic. These tests pin the rule in every place a plot
// centre is emitted, and pin that a plot which never had a centre and a
// plot whose centre was removed are treated identically.

import XCTest
import Models
@testable import Export

final class RemovedPlotCentreExportTests: XCTestCase {

    /// The fixture plots with plot #2's centre cleared exactly the way
    /// the map's Remove does it.
    private func plotsWithOneRemoved() -> (all: [Plot], located: [Plot]) {
        var all = ExportFixtures.plots()
        all[1].centerLat = 0
        all[1].centerLon = 0
        return (all, all.filter(\.hasCentre))
    }

    // MARK: - hasCentre

    func testNeverSetAndRemovedCentresAreIndistinguishable() {
        var never = ExportFixtures.plots()[0]
        never.centerLat = 0
        never.centerLon = 0
        var removed = ExportFixtures.plots()[2]
        removed.centerLat = 0
        removed.centerLon = 0
        XCTAssertFalse(never.hasCentre)
        XCTAssertFalse(removed.hasCentre)
        XCTAssertEqual(never.hasCentre, removed.hasCentre)
    }

    func testNonFiniteCentreIsNotACentre() {
        var plot = ExportFixtures.plots()[0]
        plot.centerLat = Double.nan
        XCTAssertFalse(plot.hasCentre)
    }

    // MARK: - GeoJSON

    func testRemovedPlotExportsAsUnlocatedFeature() throws {
        let (all, _) = plotsWithOneRemoved()
        let text = try GeoJSONExporter.cruise(
            strata: [], plannedPlots: [], plots: all)
        let obj = try XCTUnwrap(try JSONSerialization.jsonObject(
            with: Data(text.utf8)) as? [String: Any])
        let features = try XCTUnwrap(obj["features"] as? [[String: Any]])

        // Every plot is still a feature — the tally is not dropped.
        XCTAssertEqual(features.count, all.count)

        let removed = try XCTUnwrap(features.first {
            ($0["properties"] as? [String: Any])?["plotNumber"] as? Int == 2
        })
        // RFC 7946 §3.2: an unlocated feature has a null geometry.
        XCTAssertTrue(removed["geometry"] is NSNull)
        // …and keeps everything that is not a position.
        let props = try XCTUnwrap(removed["properties"] as? [String: Any])
        XCTAssertEqual(props["kind"] as? String, "measuredPlot")
        XCTAssertEqual(props["positionTier"] as? String, "B")

        // The located plots are untouched.
        let located = try XCTUnwrap(features.first {
            ($0["properties"] as? [String: Any])?["plotNumber"] as? Int == 1
        })
        let geometry = try XCTUnwrap(located["geometry"] as? [String: Any])
        XCTAssertEqual(geometry["type"] as? String, "Point")
    }

    func testNoFeatureIsEmittedAtNullIsland() throws {
        let (all, _) = plotsWithOneRemoved()
        let text = try GeoJSONExporter.cruise(
            strata: [], plannedPlots: [], plots: all)
        let obj = try XCTUnwrap(try JSONSerialization.jsonObject(
            with: Data(text.utf8)) as? [String: Any])
        let features = try XCTUnwrap(obj["features"] as? [[String: Any]])
        for feature in features {
            guard let geometry = feature["geometry"] as? [String: Any],
                  let coords = geometry["coordinates"] as? [Double]
            else { continue }
            XCTAssertFalse(coords == [0, 0],
                           "a feature was emitted at 0, 0")
        }
    }

    // MARK: - Shapefile

    func testRemovedPlotHasNoShapefileRecord() throws {
        let (all, located) = plotsWithOneRemoved()
        let zipData = try ShapefileExporter.plotCentersZip(plots: all)
        let entries = try ZipReader.readStoredEntries(zipData)

        // Geometry and attributes must stay in lockstep: a shapefile
        // pairs them by index, so a filter applied to one and not the
        // other silently re-labels every record after the gap.
        let records = try ShpParser.parsePointRecords(entries["plots.shp"]!)
        let dbf = try DBFParser.parse(entries["plots.dbf"]!)
        XCTAssertEqual(records.count, located.count)
        XCTAssertEqual(dbf.records.count, located.count)

        for (i, p) in located.enumerated() {
            XCTAssertEqual(records[i].x, p.centerLon, accuracy: 1e-9)
            XCTAssertEqual(records[i].y, p.centerLat, accuracy: 1e-9)
        }
        let plotNumIdx = try XCTUnwrap(
            dbf.fields.firstIndex { $0.name == "PLOT_NUM" })
        let numbers = dbf.records.map {
            $0[plotNumIdx].trimmingCharacters(in: .whitespaces)
        }
        XCTAssertFalse(numbers.contains("2"),
                       "the removed plot must not carry a row")
    }

    func testShapefileRefusesWhenNoPlotHasACentre() {
        var all = ExportFixtures.plots()
        for i in all.indices {
            all[i].centerLat = 0
            all[i].centerLon = 0
        }
        XCTAssertThrowsError(try ShapefileExporter.plotCentersZip(plots: all))
    }

    // MARK: - CSV

    func testRemovedPlotCsvRowKeepsTheTallyAndBlanksTheCoordinates() throws {
        let (all, _) = plotsWithOneRemoved()
        let csv = CSVExporter.plotsCSV(plots: all, statsByPlot: [:])
        let lines = csv.components(separatedBy: "\r\n").filter { !$0.isEmpty }
        // Header + one row per plot: the row survives, only the position
        // goes.
        XCTAssertEqual(lines.count - 1, all.count)

        let header = lines[0].components(separatedBy: ",")
        let latIdx = try XCTUnwrap(header.firstIndex(of: "center_lat"))
        let lonIdx = try XCTUnwrap(header.firstIndex(of: "center_lon"))

        let removedRow = try XCTUnwrap(lines.first {
            $0.contains(all[1].id.uuidString)
        }).components(separatedBy: ",")
        XCTAssertEqual(removedRow[latIdx], "")
        XCTAssertEqual(removedRow[lonIdx], "")
        // Never the sentinel formatted as a coordinate.
        XCTAssertNotEqual(removedRow[latIdx], "0.0000000")

        let locatedRow = try XCTUnwrap(lines.first {
            $0.contains(all[0].id.uuidString)
        }).components(separatedBy: ",")
        XCTAssertEqual(locatedRow[latIdx],
                       String(format: "%.7f", all[0].centerLat))
    }
}
