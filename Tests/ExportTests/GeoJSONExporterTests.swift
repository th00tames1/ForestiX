// Spec §8 Export/GeoJSONExporter. Plan-only exports for Phase 1.

import XCTest
import Models
@testable import Export

final class GeoJSONExporterTests: XCTestCase {

    private func parseCollection(_ s: String) throws -> [String: Any] {
        try XCTUnwrap(try JSONSerialization.jsonObject(
            with: Data(s.utf8)) as? [String: Any])
    }

    func testEmptyInputStillProducesFeatureCollection() throws {
        let text = try GeoJSONExporter.plan(strata: [], plannedPlots: [])
        let obj = try parseCollection(text)
        XCTAssertEqual(obj["type"] as? String, "FeatureCollection")
        XCTAssertEqual((obj["features"] as? [Any])?.count, 0)
    }

    func testStratumIsEmittedAsPolygonFeature() throws {
        let polygonJSON = """
        {"type":"Polygon","coordinates":[[[-122.3,47.6],[-122.29,47.6],[-122.29,47.61],[-122.3,47.61],[-122.3,47.6]]]}
        """
        let s = Stratum(id: UUID(), projectId: UUID(), name: "East",
                        areaAcres: 10, polygonGeoJSON: polygonJSON)
        let text = try GeoJSONExporter.plan(strata: [s], plannedPlots: [])
        let obj = try parseCollection(text)
        let features = try XCTUnwrap(obj["features"] as? [[String: Any]])
        XCTAssertEqual(features.count, 1)
        XCTAssertEqual((features[0]["geometry"] as? [String: Any])?["type"] as? String, "Polygon")
        XCTAssertEqual((features[0]["properties"] as? [String: Any])?["kind"] as? String, "stratum")
        XCTAssertEqual((features[0]["properties"] as? [String: Any])?["name"] as? String, "East")
    }

    func testPlannedPlotIsEmittedAsPointFeature() throws {
        let plot = PlannedPlot(id: UUID(), projectId: UUID(), stratumId: nil,
                               plotNumber: 7,
                               plannedLat: 47.6, plannedLon: -122.3, visited: false)
        let text = try GeoJSONExporter.plan(strata: [], plannedPlots: [plot])
        let obj = try parseCollection(text)
        let features = try XCTUnwrap(obj["features"] as? [[String: Any]])
        XCTAssertEqual((features[0]["geometry"] as? [String: Any])?["type"] as? String, "Point")
        let coords = try XCTUnwrap((features[0]["geometry"] as? [String: Any])?["coordinates"] as? [Double])
        XCTAssertEqual(coords, [-122.3, 47.6])
        XCTAssertEqual((features[0]["properties"] as? [String: Any])?["plotNumber"] as? Int, 7)
    }

    func testMalformedStratumPolygonIsSkipped() throws {
        let bad = Stratum(id: UUID(), projectId: UUID(), name: "bad",
                          areaAcres: 1, polygonGeoJSON: "not json")
        let text = try GeoJSONExporter.plan(strata: [bad], plannedPlots: [])
        let obj = try parseCollection(text)
        XCTAssertEqual((obj["features"] as? [Any])?.count, 0)
    }

    func testOutputIsDeterministic() throws {
        let s = Stratum(
            id: UUID(uuidString: "00000000-0000-0000-0000-000000000001")!,
            projectId: UUID(), name: "A", areaAcres: 1,
            polygonGeoJSON: "{\"type\":\"Polygon\",\"coordinates\":[[[0,0],[1,0],[1,1],[0,1],[0,0]]]}"
        )
        let first = try GeoJSONExporter.plan(strata: [s], plannedPlots: [])
        let second = try GeoJSONExporter.plan(strata: [s], plannedPlots: [])
        XCTAssertEqual(first, second)
    }
}
