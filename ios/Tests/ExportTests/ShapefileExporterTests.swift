// Phase 6 — Shapefile export round-trip: uses a minimal in-test parser
// to verify that the .shp/.shx/.dbf/.prj/.cpg set inside the emitted zip
// unpacks to the geometry and attributes we fed in.
//
// Parser scope is intentionally narrow (Point + Polygon, stored ZIP,
// UTF-8 .dbf, dBase III) — just enough to validate that our writer is
// self-consistent. A full-featured read path lives in QGIS/OGR; any
// regression there would be caught by our golden-file hash test.

import XCTest
import Models
@testable import Export

final class ShapefileExporterTests: XCTestCase {

    // MARK: - Plot-centers round-trip

    func testPlotCentersZipContainsFiveExpectedFiles() throws {
        let plots = ExportFixtures.plots()
        let zipData = try ShapefileExporter.plotCentersZip(plots: plots)
        let entries = try ZipReader.readStoredEntries(zipData)
        let names = Set(entries.keys)
        XCTAssertEqual(names, Set([
            "plots.shp", "plots.shx", "plots.dbf",
            "plots.prj", "plots.cpg"
        ]))
        XCTAssertEqual(String(data: entries["plots.cpg"]!, encoding: .utf8),
                       "UTF-8\n")
        let prj = String(data: entries["plots.prj"]!, encoding: .utf8)!
        XCTAssertTrue(prj.contains("WGS_1984"))
    }

    func testPlotCentersShpContainsPointGeometryWithWGS84Coords() throws {
        let plots = ExportFixtures.plots()
        let zipData = try ShapefileExporter.plotCentersZip(plots: plots)
        let entries = try ZipReader.readStoredEntries(zipData)

        let header = try ShpParser.parseHeader(entries["plots.shp"]!)
        XCTAssertEqual(header.shapeType, 1)             // Point
        XCTAssertGreaterThan(header.fileLengthBytes, 100)

        let records = try ShpParser.parsePointRecords(entries["plots.shp"]!)
        XCTAssertEqual(records.count, plots.count)
        for (i, p) in plots.enumerated() {
            XCTAssertEqual(records[i].x, p.centerLon, accuracy: 1e-9)
            XCTAssertEqual(records[i].y, p.centerLat, accuracy: 1e-9)
        }
    }

    func testPlotCentersDbfCarriesPlotNumberAndTier() throws {
        let plots = ExportFixtures.plots()
        let zipData = try ShapefileExporter.plotCentersZip(plots: plots)
        let entries = try ZipReader.readStoredEntries(zipData)
        let dbf = try DBFParser.parse(entries["plots.dbf"]!)
        XCTAssertEqual(dbf.records.count, plots.count)
        XCTAssertTrue(dbf.fields.contains { $0.name == "PLOT_NUM" })
        XCTAssertTrue(dbf.fields.contains { $0.name == "TIER" })
        // First row's plot number should be 1.
        let plotNumIdx = dbf.fields.firstIndex { $0.name == "PLOT_NUM" }!
        XCTAssertEqual(
            dbf.records[0][plotNumIdx]
                .trimmingCharacters(in: .whitespaces),
            "1")
        let tierIdx = dbf.fields.firstIndex { $0.name == "TIER" }!
        XCTAssertEqual(dbf.records[0][tierIdx], "B")
    }

    // MARK: - Polygon (strata) round-trip

    func testStrataZipProducesPolygonRecords() throws {
        let strata = ExportFixtures.strata()
        let zipData = try ShapefileExporter.strataZip(strata: strata)
        let entries = try ZipReader.readStoredEntries(zipData)
        let header = try ShpParser.parseHeader(entries["strata.shp"]!)
        XCTAssertEqual(header.shapeType, 5)             // Polygon

        let records = try ShpParser.parsePolygonRecords(entries["strata.shp"]!)
        XCTAssertEqual(records.count, strata.count)
        XCTAssertGreaterThan(records[0].points.count, 4)
    }

    // MARK: - Empty layer error

    func testEmptyPlotsThrowsEmptyLayer() {
        XCTAssertThrowsError(try ShapefileExporter.plotCentersZip(plots: []))
    }

    // MARK: - ZIP is byte-stable (used for the golden-file hash elsewhere)

    func testZipDeterministic() throws {
        let plots = ExportFixtures.plots()
        let a = try ShapefileExporter.plotCentersZip(plots: plots)
        let b = try ShapefileExporter.plotCentersZip(plots: plots)
        // The DOS timestamp in the ZIP header is built from Date() so the
        // two archives differ only in the time word of the local + CD
        // headers. Everything else — names, payloads, CRCs, offsets —
        // should match.
        let aStripped = ShpDiff.stripDosTime(a)
        let bStripped = ShpDiff.stripDosTime(b)
        XCTAssertEqual(aStripped, bStripped,
                       "shapefile ZIP non-timestamp bytes must be stable")
    }
}

// MARK: - In-test parsers

enum ZipReaderError: Error { case malformed(String) }

enum ZipReader {
    /// Extracts every "stored" entry out of a ZIP by walking local file
    /// headers from the front. Sufficient for round-tripping our own
    /// single-disk, no-compression archives.
    static func readStoredEntries(_ data: Data) throws -> [String: Data] {
        var out: [String: Data] = [:]
        var i = 0
        while i + 30 <= data.count {
            let sig = data.readLE32(at: i)
            if sig == 0x04034b50 {
                let method = Int(data.readLE16(at: i + 8))
                let compSize = Int(data.readLE32(at: i + 18))
                let nameLen = Int(data.readLE16(at: i + 26))
                let extraLen = Int(data.readLE16(at: i + 28))
                let nameStart = i + 30
                let dataStart = nameStart + nameLen + extraLen
                guard method == 0 else {
                    throw ZipReaderError.malformed("only stored method supported")
                }
                let name = String(data: data[nameStart..<nameStart + nameLen],
                                  encoding: .utf8) ?? ""
                let payload = data[dataStart..<dataStart + compSize]
                out[name] = Data(payload)
                i = dataStart + compSize
                continue
            }
            // Reached central directory → stop.
            break
        }
        return out
    }
}

enum ShpParserError: Error { case malformed(String) }

enum ShpParser {
    struct Header {
        let shapeType: Int32
        let fileLengthBytes: Int
    }

    struct PointRecord { let x: Double; let y: Double }
    struct PolygonRecord {
        let parts: [Int32]
        let points: [(x: Double, y: Double)]
    }

    static func parseHeader(_ data: Data) throws -> Header {
        guard data.count >= 100 else { throw ShpParserError.malformed("short header") }
        let fileCode = data.readBE32(at: 0)
        guard fileCode == 9994 else { throw ShpParserError.malformed("file code") }
        let lengthWords = data.readBE32(at: 24)
        let shapeType = Int32(bitPattern: data.readLE32(at: 32))
        return Header(shapeType: shapeType,
                      fileLengthBytes: Int(lengthWords) * 2)
    }

    static func parsePointRecords(_ data: Data) throws -> [PointRecord] {
        var out: [PointRecord] = []
        var i = 100
        while i + 8 <= data.count {
            // record number BE, content length BE
            _ = data.readBE32(at: i)
            let contentWords = Int(data.readBE32(at: i + 4))
            let contentBytes = contentWords * 2
            let body = i + 8
            let shapeType = data.readLE32(at: body)
            if shapeType == 1 {
                let x = data.readLEDouble(at: body + 4)
                let y = data.readLEDouble(at: body + 12)
                out.append(PointRecord(x: x, y: y))
            }
            i = body + contentBytes
        }
        return out
    }

    static func parsePolygonRecords(_ data: Data) throws -> [PolygonRecord] {
        var out: [PolygonRecord] = []
        var i = 100
        while i + 8 <= data.count {
            _ = data.readBE32(at: i)
            let contentWords = Int(data.readBE32(at: i + 4))
            let contentBytes = contentWords * 2
            let body = i + 8
            let shapeType = data.readLE32(at: body)
            if shapeType == 5 {
                // bbox(32) + numParts(4) + numPoints(4) = 40
                let numParts = Int(data.readLE32(at: body + 36))
                let numPoints = Int(data.readLE32(at: body + 40))
                var parts: [Int32] = []
                for p in 0..<numParts {
                    parts.append(Int32(bitPattern: data.readLE32(at: body + 44 + p * 4)))
                }
                var points: [(x: Double, y: Double)] = []
                let pointsStart = body + 44 + numParts * 4
                for p in 0..<numPoints {
                    let x = data.readLEDouble(at: pointsStart + p * 16)
                    let y = data.readLEDouble(at: pointsStart + p * 16 + 8)
                    points.append((x, y))
                }
                out.append(PolygonRecord(parts: parts, points: points))
            }
            i = body + contentBytes
        }
        return out
    }
}

enum DBFParserError: Error { case malformed(String) }

enum DBFParser {
    struct Field { let name: String; let type: Character; let length: Int }
    struct Table {
        let fields: [Field]
        let records: [[String]]
    }

    static func parse(_ data: Data) throws -> Table {
        guard data.count > 32 else { throw DBFParserError.malformed("header") }
        let recordCount = Int(data.readLE32(at: 4))
        let headerLength = Int(data.readLE16(at: 8))
        let recordLength = Int(data.readLE16(at: 10))

        var fields: [Field] = []
        var pos = 32
        while pos + 32 <= headerLength && data[pos] != 0x0D {
            let nameBytes = data[pos..<pos + 11]
            let name = String(
                bytes: nameBytes.prefix {
                    $0 != 0 && $0 != 0x20
                },
                encoding: .utf8) ?? ""
            let type = Character(UnicodeScalar(data[pos + 11]))
            let length = Int(data[pos + 16])
            fields.append(Field(name: name, type: type, length: length))
            pos += 32
        }

        var records: [[String]] = []
        var rec = headerLength
        for _ in 0..<recordCount {
            // Skip delete-flag byte.
            var offset = rec + 1
            var row: [String] = []
            for f in fields {
                let raw = data[offset..<offset + f.length]
                let s = String(data: raw, encoding: .utf8) ?? ""
                if f.type == "N" {
                    row.append(s.trimmingCharacters(in: .whitespaces))
                } else {
                    // Character: trailing-trim only (matches left-align + space-pad).
                    row.append(String(s.reversed()
                                      .drop(while: { $0 == " " })
                                      .reversed()))
                }
                offset += f.length
            }
            records.append(row)
            rec += recordLength
        }
        return Table(fields: fields, records: records)
    }
}

// MARK: - Binary readers

extension Data {
    func readLE32(at i: Int) -> UInt32 {
        UInt32(self[i]) |
        (UInt32(self[i + 1]) << 8) |
        (UInt32(self[i + 2]) << 16) |
        (UInt32(self[i + 3]) << 24)
    }

    func readBE32(at i: Int) -> Int32 {
        Int32(bitPattern:
            (UInt32(self[i]) << 24) |
            (UInt32(self[i + 1]) << 16) |
            (UInt32(self[i + 2]) << 8) |
            UInt32(self[i + 3]))
    }

    func readLE16(at i: Int) -> Int16 {
        Int16(bitPattern:
            UInt16(self[i]) | (UInt16(self[i + 1]) << 8))
    }

    func readLEDouble(at i: Int) -> Double {
        var bits: UInt64 = 0
        for k in 0..<8 {
            bits |= UInt64(self[i + k]) << (k * 8)
        }
        return Double(bitPattern: bits)
    }
}

// MARK: - Helpers

enum ShpDiff {
    /// ZIP "local file header" has its mod-time at offset 10..12 and
    /// mod-date at 12..14. Central directory mirrors it at offset 12..16.
    /// Zeroing these out removes Date()-dependent jitter from byte diffs.
    static func stripDosTime(_ data: Data) -> Data {
        var out = data
        var i = 0
        while i + 30 <= out.count {
            let sig = out.readLE32(at: i)
            if sig == 0x04034b50 {
                out[i + 10] = 0; out[i + 11] = 0
                out[i + 12] = 0; out[i + 13] = 0
                let nameLen = Int(out.readLE16(at: i + 26))
                let extraLen = Int(out.readLE16(at: i + 28))
                let compSize = Int(out.readLE32(at: i + 18))
                i += 30 + nameLen + extraLen + compSize
                continue
            }
            if sig == 0x02014b50 {
                out[i + 12] = 0; out[i + 13] = 0
                out[i + 14] = 0; out[i + 15] = 0
                let nameLen = Int(out.readLE16(at: i + 28))
                let extraLen = Int(out.readLE16(at: i + 30))
                let commentLen = Int(out.readLE16(at: i + 32))
                i += 46 + nameLen + extraLen + commentLen
                continue
            }
            break
        }
        return out
    }
}
