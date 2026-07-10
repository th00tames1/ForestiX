// Spec §8 Geo/GeoJSONImporter. REQ-PRJ-002.

import XCTest
@testable import Geo

final class GeoJSONImporterTests: XCTestCase {

    // MARK: - Happy path

    func testImportSimplePolygonFeature() throws {
        let json = """
        {
          "type": "Feature",
          "properties": { "name": "East Block" },
          "geometry": {
            "type": "Polygon",
            "coordinates": [[
              [-122.30, 47.60],
              [-122.29, 47.60],
              [-122.29, 47.61],
              [-122.30, 47.61],
              [-122.30, 47.60]
            ]]
          }
        }
        """
        let imported = try GeoJSONImporter.importStrata(from: Data(json.utf8))
        XCTAssertEqual(imported.count, 1)
        XCTAssertEqual(imported[0].name, "East Block")
        XCTAssertEqual(imported[0].rings.count, 1)
        XCTAssertEqual(imported[0].rings[0].count, 5)
        XCTAssertGreaterThan(imported[0].areaAcres, 0)
    }

    func testImportFeatureCollectionWithTwoPolygons() throws {
        let json = """
        {
          "type": "FeatureCollection",
          "features": [
            { "type": "Feature", "properties": { "name": "A" },
              "geometry": { "type": "Polygon", "coordinates": [[
                [0,0],[0.01,0],[0.01,0.01],[0,0.01],[0,0]
              ]] } },
            { "type": "Feature", "properties": { "name": "B" },
              "geometry": { "type": "Polygon", "coordinates": [[
                [1,1],[1.01,1],[1.01,1.01],[1,1.01],[1,1]
              ]] } }
          ]
        }
        """
        let imported = try GeoJSONImporter.importStrata(from: Data(json.utf8))
        XCTAssertEqual(imported.map(\.name), ["A", "B"])
    }

    func testImportMultiPolygonFeature() throws {
        let json = """
        {
          "type": "Feature",
          "properties": { "name": "Unit" },
          "geometry": {
            "type": "MultiPolygon",
            "coordinates": [
              [[[0,0],[0.01,0],[0.01,0.01],[0,0.01],[0,0]]],
              [[[1,1],[1.01,1],[1.01,1.01],[1,1.01],[1,1]]]
            ]
          }
        }
        """
        let imported = try GeoJSONImporter.importStrata(from: Data(json.utf8))
        XCTAssertEqual(imported.count, 2)
        XCTAssertEqual(imported[0].name, "Unit")
        XCTAssertTrue(imported[1].name.hasPrefix("Unit"))
    }

    // MARK: - Area computation

    func testComputedAreaAtEquatorMatchesExpected() throws {
        // 0.01° × 0.01° square at the equator ≈ 1.1132 km per side
        // Area ≈ 1.239 km² ≈ 306.16 acres.
        let json = """
        {
          "type": "Feature",
          "properties": { "name": "Eq" },
          "geometry": {
            "type": "Polygon",
            "coordinates": [[
              [0,0],[0.01,0],[0.01,0.01],[0,0.01],[0,0]
            ]]
          }
        }
        """
        let imported = try GeoJSONImporter.importStrata(from: Data(json.utf8))
        XCTAssertEqual(imported[0].areaAcres, 306.16, accuracy: 3.0)
    }

    func testSuppliedAreaAcresIsHonoured() throws {
        let json = """
        {
          "type": "Feature",
          "properties": { "name": "Given", "areaAcres": 42.0 },
          "geometry": {
            "type": "Polygon",
            "coordinates": [[
              [0,0],[0.01,0],[0.01,0.01],[0,0.01],[0,0]
            ]]
          }
        }
        """
        let imported = try GeoJSONImporter.importStrata(from: Data(json.utf8))
        XCTAssertEqual(imported[0].areaAcres, 42.0)
    }

    // MARK: - Errors

    func testMalformedJSONThrows() {
        XCTAssertThrowsError(try GeoJSONImporter.importStrata(from: Data("{not json".utf8)))
    }

    func testUnsupportedGeometryThrows() {
        let json = """
        { "type": "Feature", "properties": {}, "geometry": { "type": "Point", "coordinates": [0,0] } }
        """
        XCTAssertThrowsError(try GeoJSONImporter.importStrata(from: Data(json.utf8))) { err in
            guard case GeoJSONImportError.unsupportedGeometry = err else {
                return XCTFail("Expected unsupportedGeometry, got \(err)")
            }
        }
    }

    func testUnclosedRingThrows() {
        let json = """
        { "type": "Feature", "properties": {}, "geometry": { "type": "Polygon",
          "coordinates": [[ [0,0], [0.01,0], [0,0] ]] } }
        """
        XCTAssertThrowsError(try GeoJSONImporter.importStrata(from: Data(json.utf8)))
    }

    func testSerialisationRoundTrip() throws {
        let json = """
        { "type": "Feature", "properties": { "name": "X" }, "geometry": { "type": "Polygon",
          "coordinates": [[ [0,0], [0.01,0], [0.01,0.01], [0,0.01], [0,0] ]] } }
        """
        let imported = try GeoJSONImporter.importStrata(from: Data(json.utf8))
        // Parse the serialised form back to ensure it is valid JSON.
        let data = Data(imported[0].geoJSONString.utf8)
        let parsed = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        XCTAssertEqual(parsed?["type"] as? String, "Polygon")
    }
}
