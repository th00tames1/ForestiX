// Spec §8 Geo/KMLImporter. REQ-PRJ-002.

import XCTest
@testable import Geo

final class KMLImporterTests: XCTestCase {

    func testImportSinglePlacemarkPolygon() throws {
        let kml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <kml xmlns="http://www.opengis.net/kml/2.2">
          <Document>
            <Placemark>
              <name>East Block</name>
              <Polygon>
                <outerBoundaryIs>
                  <LinearRing>
                    <coordinates>
                      -122.30,47.60,0
                      -122.29,47.60,0
                      -122.29,47.61,0
                      -122.30,47.61,0
                      -122.30,47.60,0
                    </coordinates>
                  </LinearRing>
                </outerBoundaryIs>
              </Polygon>
            </Placemark>
          </Document>
        </kml>
        """
        let imported = try KMLImporter.importStrata(from: Data(kml.utf8))
        XCTAssertEqual(imported.count, 1)
        XCTAssertEqual(imported[0].name, "East Block")
        XCTAssertEqual(imported[0].rings[0].count, 5)
        XCTAssertGreaterThan(imported[0].areaAcres, 0)
    }

    func testUnclosedRingIsAutoClosed() throws {
        let kml = """
        <kml><Placemark><name>X</name>
          <Polygon><outerBoundaryIs><LinearRing><coordinates>
            0,0 0.01,0 0.01,0.01 0,0.01
          </coordinates></LinearRing></outerBoundaryIs></Polygon>
        </Placemark></kml>
        """
        let imported = try KMLImporter.importStrata(from: Data(kml.utf8))
        XCTAssertEqual(imported[0].rings[0].first, imported[0].rings[0].last)
    }

    func testDocumentWithoutPolygonsThrows() {
        let kml = """
        <kml><Placemark><name>P</name>
          <Point><coordinates>0,0,0</coordinates></Point>
        </Placemark></kml>
        """
        XCTAssertThrowsError(try KMLImporter.importStrata(from: Data(kml.utf8))) { err in
            guard case KMLImportError.noPolygons = err else {
                return XCTFail("Expected noPolygons, got \(err)")
            }
        }
    }

    func testMalformedXMLThrows() {
        XCTAssertThrowsError(try KMLImporter.importStrata(from: Data("<kml".utf8)))
    }
}
