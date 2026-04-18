// Spec §8 Export/CSVExporter. Plan-only exports for Phase 1.

import XCTest
import Models
@testable import Export

final class CSVExporterTests: XCTestCase {

    func testStratumListHeader() {
        let csv = CSVExporter.stratumListCSV(strata: [])
        XCTAssertEqual(csv, "stratum_id,name,area_acres\r\n")
    }

    func testStratumListBody() {
        let id = UUID()
        let projectId = UUID()
        let s = Stratum(
            id: id,
            projectId: projectId,
            name: "East Block",
            areaAcres: 42.5,
            polygonGeoJSON: "{}"
        )
        let csv = CSVExporter.stratumListCSV(strata: [s])
        let lines = csv.split(separator: "\r\n", omittingEmptySubsequences: true)
        XCTAssertEqual(lines.count, 2)
        XCTAssertTrue(String(lines[1]).hasSuffix(",East Block,42.5000"))
        XCTAssertTrue(String(lines[1]).hasPrefix(id.uuidString))
    }

    func testCommasInNameAreQuoted() {
        let s = Stratum(id: UUID(), projectId: UUID(),
                        name: "North, West", areaAcres: 1,
                        polygonGeoJSON: "{}")
        let csv = CSVExporter.stratumListCSV(strata: [s])
        XCTAssertTrue(csv.contains(",\"North, West\","))
    }

    func testPlannedPlotsAreSortedByNumber() {
        let projectId = UUID()
        let stratumId = UUID()
        let plots = [
            PlannedPlot(id: UUID(), projectId: projectId, stratumId: stratumId,
                        plotNumber: 3, plannedLat: 47.6, plannedLon: -122.3, visited: false),
            PlannedPlot(id: UUID(), projectId: projectId, stratumId: stratumId,
                        plotNumber: 1, plannedLat: 47.6, plannedLon: -122.3, visited: true),
            PlannedPlot(id: UUID(), projectId: projectId, stratumId: stratumId,
                        plotNumber: 2, plannedLat: 47.6, plannedLon: -122.3, visited: false)
        ]
        let strata = [Stratum(id: stratumId, projectId: projectId,
                              name: "E", areaAcres: 1, polygonGeoJSON: "{}")]
        let csv = CSVExporter.plannedPlotsCSV(plannedPlots: plots, strata: strata)
        let lines = csv.components(separatedBy: "\r\n").filter { !$0.isEmpty }
        XCTAssertEqual(lines[0],
                       "plot_number,stratum_id,stratum_name,planned_lat,planned_lon,visited")
        XCTAssertTrue(lines[1].hasPrefix("1,"))
        XCTAssertTrue(lines[2].hasPrefix("2,"))
        XCTAssertTrue(lines[3].hasPrefix("3,"))
        XCTAssertTrue(lines[1].contains("true"))
        XCTAssertTrue(lines[2].contains("false"))
    }

    func testPlannedPlotsOmitStratumNameWhenUnknown() {
        let plots = [
            PlannedPlot(id: UUID(), projectId: UUID(), stratumId: nil,
                        plotNumber: 1, plannedLat: 47.6, plannedLon: -122.3, visited: false)
        ]
        let csv = CSVExporter.plannedPlotsCSV(plannedPlots: plots, strata: [])
        XCTAssertTrue(csv.contains("1,,,47.6000000,"))
    }

    func testLatLonFormatting() {
        let plots = [
            PlannedPlot(id: UUID(), projectId: UUID(), stratumId: nil,
                        plotNumber: 1,
                        plannedLat: 47.6062345678, plannedLon: -122.3321098765,
                        visited: false)
        ]
        let csv = CSVExporter.plannedPlotsCSV(plannedPlots: plots, strata: [])
        // 7 decimal places.
        XCTAssertTrue(csv.contains("47.6062346"))
        XCTAssertTrue(csv.contains("-122.3321099"))
    }
}
