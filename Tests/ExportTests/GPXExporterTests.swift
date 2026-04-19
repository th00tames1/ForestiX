// GPX 1.1 schema smoke-test: required root attrs, waypoint names,
// track point time ordering, ISO-8601 timestamps.

import XCTest
@testable import Export

final class GPXExporterTests: XCTestCase {

    func testEmptyGPXHasRootElement() {
        let gpx = GPXExporter.gpx()
        XCTAssertTrue(gpx.contains("<?xml version=\"1.0\""))
        XCTAssertTrue(gpx.contains("<gpx version=\"1.1\""))
        XCTAssertTrue(gpx.contains("creator=\"Forestix\""))
        XCTAssertTrue(gpx.contains("</gpx>"))
    }

    func testWaypointWithTimestampAndDesc() {
        let ts = Date(timeIntervalSince1970: 1_700_000_000)
        let gpx = GPXExporter.gpx(waypoints: [
            .init(lat: 45.12345,
                  lon: -122.6789,
                  name: "Plot A",
                  description: "tier=A src=gpsAveraged",
                  timestamp: ts)
        ])
        XCTAssertTrue(gpx.contains("<wpt lat=\"45.1234500\" lon=\"-122.6789000\">"))
        XCTAssertTrue(gpx.contains("<name>Plot A</name>"))
        XCTAssertTrue(gpx.contains("<desc>tier=A src=gpsAveraged</desc>"))
        XCTAssertTrue(gpx.contains("<time>2023-11-14T"))
    }

    func testTrackSerialisesPointsInOrder() {
        let t0 = Date(timeIntervalSince1970: 1_700_000_000)
        let gpx = GPXExporter.gpx(
            trackName: "Cruise 2026-04-19",
            trackPoints: [
                .init(lat: 45.0, lon: -122.0, timestamp: t0, horizontalAccuracyM: 4),
                .init(lat: 45.001, lon: -122.001, timestamp: t0.addingTimeInterval(60))
            ])
        XCTAssertTrue(gpx.contains("<trk>"))
        XCTAssertTrue(gpx.contains("<name>Cruise 2026-04-19</name>"))
        XCTAssertTrue(gpx.contains("<trkseg>"))
        XCTAssertTrue(gpx.contains("<hdop>4.0000000</hdop>"))
        // First point before second
        let first = gpx.range(of: "45.0000000")!
        let second = gpx.range(of: "45.0010000")!
        XCTAssertLessThan(first.lowerBound, second.lowerBound)
    }

    func testXMLAttrEscape() {
        let gpx = GPXExporter.gpx(waypoints: [
            .init(lat: 0, lon: 0, name: "A & <B>")
        ])
        XCTAssertTrue(gpx.contains("<name>A &amp; &lt;B&gt;</name>"))
    }
}
