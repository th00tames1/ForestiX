// Spec §8 Export — Shapefile export (Phase 6).
//
// ## Why pure Swift?
// ShapefileKit and similar SPM libraries aren't carrying Apple-platform
// support consistently, and the ESRI Shapefile + dBase III + "stored" ZIP
// binary formats are straightforward enough to write against the public
// specs without an external dependency. Doing this in-house keeps the
// Export target dependency-free and keeps BSD/MIT license conflicts out
// of the repo.
//
// ## References
//   * ESRI Shapefile Technical Description (ESRI White Paper, 1998).
//   * dBase III PLUS File Structure (xBase docs).
//   * PKWARE APPNOTE.TXT v6.3.10 (ZIP, stored = method 0).
//
// ## Supported geometry
//   * Shape type 1 — Point (plot centres, planned plots).
//   * Shape type 5 — Polygon (stratum boundaries).
//
// Higher shape types (PolyLine, multi-patch, Z/M variants) are not needed
// for Forestix's export model.
//
// ## Byte order
// Shapefile mixes big-endian (record headers, file-code, file-length) with
// little-endian (shape type, coordinates, dBase). The writer keeps those
// straight via explicit helpers.
//
// ## Character encoding
// The .dbf string fields are UTF-8; a sibling `.cpg` file declares this
// explicitly so GIS readers (QGIS, ArcGIS, OGR) pick it up.

import Foundation
import Models

public enum ShapefileExporterError: Error, CustomStringConvertible {
    case invalidGeometry(String)
    case serializationFailed(String)
    case emptyLayer

    public var description: String {
        switch self {
        case .invalidGeometry(let g): return "Invalid geometry: \(g)"
        case .serializationFailed(let s): return "Shapefile serialization failed: \(s)"
        case .emptyLayer: return "Cannot write shapefile with zero features"
        }
    }
}

// MARK: - Public API

public enum ShapefileExporter {

    /// Build a zipped shapefile bundle for the measured plot centres of a
    /// cruise. Returns the raw bytes of a `.zip` suitable for writing
    /// directly to disk — four constituent files (`<name>.shp`, `.shx`,
    /// `.dbf`, `.prj`) plus a `.cpg` encoding sidecar.
    public static func plotCentersZip(
        plots: [Plot],
        layerName: String = "plots"
    ) throws -> Data {
        let rows: [DBFRow] = plots.map { p in
            [
                ("plot_num", .int(p.plotNumber)),
                ("tier",     .string(String(describing: p.positionTier), width: 1)),
                ("pos_src",  .string(String(describing: p.positionSource), width: 16)),
                ("area_ac",  .double(Double(p.plotAreaAcres), width: 12, decimals: 4)),
                ("closed",   .string(p.closedAt == nil ? "no" : "yes", width: 3)),
                ("plot_id",  .string(p.id.uuidString, width: 36))
            ]
        }
        let shapes: [ShapeGeometry] = plots.map { .point(x: $0.centerLon,
                                                        y: $0.centerLat) }
        guard !shapes.isEmpty else { throw ShapefileExporterError.emptyLayer }
        return try Self.zipped(layerName: layerName,
                               shapeType: .point,
                               geometries: shapes,
                               attributes: rows)
    }

    /// Build a zipped shapefile bundle for planned plot points, carrying
    /// the `visited` flag so the shapefile can be styled planned-vs-visited
    /// in a GIS.
    public static func plannedPlotsZip(
        plannedPlots: [PlannedPlot],
        layerName: String = "planned_plots"
    ) throws -> Data {
        let sorted = plannedPlots.sorted { $0.plotNumber < $1.plotNumber }
        let rows: [DBFRow] = sorted.map { p in
            [
                ("plot_num", .int(p.plotNumber)),
                ("stratum",  .string(p.stratumId?.uuidString ?? "", width: 36)),
                ("visited",  .string(p.visited ? "yes" : "no", width: 3)),
                ("planned_id", .string(p.id.uuidString, width: 36))
            ]
        }
        let shapes: [ShapeGeometry] = sorted.map {
            .point(x: $0.plannedLon, y: $0.plannedLat)
        }
        guard !shapes.isEmpty else { throw ShapefileExporterError.emptyLayer }
        return try Self.zipped(layerName: layerName,
                               shapeType: .point,
                               geometries: shapes,
                               attributes: rows)
    }

    /// Build a zipped shapefile bundle for stratum polygons. Each
    /// `Stratum.polygonGeoJSON` is parsed; polygons/multi-polygons are
    /// flattened into a single polygon feature with one outer ring plus
    /// any inner holes, matching ESRI's Polygon shape type.
    public static func strataZip(
        strata: [Stratum],
        layerName: String = "strata"
    ) throws -> Data {
        var geoms: [ShapeGeometry] = []
        var rows: [DBFRow] = []
        for s in strata {
            guard let poly = try parseStratumPolygon(s.polygonGeoJSON) else {
                continue
            }
            geoms.append(poly)
            rows.append([
                ("id",       .string(s.id.uuidString, width: 36)),
                ("name",     .string(s.name, width: 64)),
                ("area_ac",  .double(Double(s.areaAcres), width: 12, decimals: 4))
            ])
        }
        guard !geoms.isEmpty else { throw ShapefileExporterError.emptyLayer }
        return try Self.zipped(layerName: layerName,
                               shapeType: .polygon,
                               geometries: geoms,
                               attributes: rows)
    }

    // MARK: - Orchestration

    private static func zipped(
        layerName: String,
        shapeType: ShapeType,
        geometries: [ShapeGeometry],
        attributes: [DBFRow]
    ) throws -> Data {
        precondition(geometries.count == attributes.count,
                     "geometries and attributes must line up")

        let (shp, shx) = try writeShpShx(shapeType: shapeType, geometries: geometries)
        let dbf = try writeDBF(rows: attributes)
        let prj = Data(wgs84PRJ.utf8)
        let cpg = Data("UTF-8\n".utf8)

        var files: [(String, Data)] = []
        files.append(("\(layerName).shp", shp))
        files.append(("\(layerName).shx", shx))
        files.append(("\(layerName).dbf", dbf))
        files.append(("\(layerName).prj", prj))
        files.append(("\(layerName).cpg", cpg))
        return ZipWriter.storedArchive(files: files)
    }
}

// MARK: - Geometry types

enum ShapeType: Int32 {
    case null = 0
    case point = 1
    case polygon = 5
}

/// Polygon rings are (closed) WGS84 lat/lon points in (x = lon, y = lat)
/// order — matching GeoJSON convention. All rings for one feature live
/// in `parts` / `points`; parts holds the index into `points` where each
/// ring starts.
enum ShapeGeometry {
    case point(x: Double, y: Double)
    case polygon(parts: [Int32], points: [(x: Double, y: Double)])
}

// MARK: - .shp / .shx writers

private func writeShpShx(
    shapeType: ShapeType,
    geometries: [ShapeGeometry]
) throws -> (shp: Data, shx: Data) {
    var shpRecords = Data()
    var shxRecords = Data()

    var bbox = MutableBBox()
    var fileOffsetWords: Int32 = 50  // header is 100 bytes = 50 × 16-bit words

    for (i, geom) in geometries.enumerated() {
        let recordContent = encodeShape(geom, updating: &bbox)
        // Record header: 4B BE record number, 4B BE content length (in
        // 16-bit words, excluding the 8-byte header itself).
        let recordNumber = Int32(i + 1)
        let contentLengthWords = Int32(recordContent.count / 2)

        var header = Data()
        header.appendBE(recordNumber)
        header.appendBE(contentLengthWords)
        shpRecords.append(header)
        shpRecords.append(recordContent)

        // Index: 4B BE offset (words), 4B BE content length (words).
        var indexEntry = Data()
        indexEntry.appendBE(fileOffsetWords)
        indexEntry.appendBE(contentLengthWords)
        shxRecords.append(indexEntry)

        fileOffsetWords += Int32(4 + recordContent.count / 2)
    }

    let shpLengthWords = Int32(50 + shpRecords.count / 2)
    let shxLengthWords = Int32(50 + shxRecords.count / 2)

    let shpHeader = shapefileHeader(
        totalLengthWords: shpLengthWords,
        shapeType: shapeType,
        bbox: bbox)
    let shxHeader = shapefileHeader(
        totalLengthWords: shxLengthWords,
        shapeType: shapeType,
        bbox: bbox)
    return (shpHeader + shpRecords, shxHeader + shxRecords)
}

private func encodeShape(_ geom: ShapeGeometry,
                        updating bbox: inout MutableBBox) -> Data {
    var out = Data()
    switch geom {
    case .point(let x, let y):
        bbox.insert(x: x, y: y)
        out.appendLE(ShapeType.point.rawValue)
        out.appendLE(x)
        out.appendLE(y)
    case .polygon(let parts, let points):
        var localBBox = MutableBBox()
        for p in points { localBBox.insert(x: p.x, y: p.y) }
        bbox.insert(bbox: localBBox)
        out.appendLE(ShapeType.polygon.rawValue)
        out.appendLE(localBBox.xmin)
        out.appendLE(localBBox.ymin)
        out.appendLE(localBBox.xmax)
        out.appendLE(localBBox.ymax)
        out.appendLE(Int32(parts.count))
        out.appendLE(Int32(points.count))
        for p in parts { out.appendLE(p) }
        for p in points { out.appendLE(p.x); out.appendLE(p.y) }
    }
    return out
}

private func shapefileHeader(
    totalLengthWords: Int32,
    shapeType: ShapeType,
    bbox: MutableBBox
) -> Data {
    var h = Data()
    h.appendBE(Int32(9994))              // File code
    h.append(Data(count: 20))            // 5 × int32 zeros
    h.appendBE(totalLengthWords)         // File length (16-bit words)
    h.appendLE(Int32(1000))              // Version
    h.appendLE(shapeType.rawValue)
    // BBox: Xmin, Ymin, Xmax, Ymax, Zmin, Zmax, Mmin, Mmax
    h.appendLE(bbox.xmin)
    h.appendLE(bbox.ymin)
    h.appendLE(bbox.xmax)
    h.appendLE(bbox.ymax)
    h.appendLE(0.0); h.appendLE(0.0); h.appendLE(0.0); h.appendLE(0.0)
    return h
}

// MARK: - BBox accumulator

struct MutableBBox {
    var xmin: Double = 0, ymin: Double = 0, xmax: Double = 0, ymax: Double = 0
    private var hasAny = false

    mutating func insert(x: Double, y: Double) {
        if !hasAny {
            xmin = x; xmax = x; ymin = y; ymax = y; hasAny = true
        } else {
            xmin = min(xmin, x); xmax = max(xmax, x)
            ymin = min(ymin, y); ymax = max(ymax, y)
        }
    }

    mutating func insert(bbox other: MutableBBox) {
        if other.hasAny {
            if !hasAny {
                xmin = other.xmin; xmax = other.xmax
                ymin = other.ymin; ymax = other.ymax
                hasAny = true
            } else {
                xmin = min(xmin, other.xmin); xmax = max(xmax, other.xmax)
                ymin = min(ymin, other.ymin); ymax = max(ymax, other.ymax)
            }
        }
    }
}

// MARK: - dBase III writer

typealias DBFRow = [(String, DBFValue)]

enum DBFValue {
    case int(Int)
    case double(Double, width: Int, decimals: Int)
    case string(String, width: Int)
}

struct DBFField {
    let name: String        // up to 10 ASCII chars, uppercased internally
    let type: UInt8         // 'C', 'N'
    let length: UInt8
    let decimals: UInt8
}

func writeDBF(rows: [DBFRow]) throws -> Data {
    // Infer one field schema from the first row.
    guard let first = rows.first else { throw ShapefileExporterError.emptyLayer }
    let fields: [DBFField] = first.map { (name, value) in
        let fname = String(name.prefix(10))
        switch value {
        case .int:
            return DBFField(name: fname, type: 0x4E /* N */, length: 11, decimals: 0)
        case .double(_, let w, let d):
            return DBFField(name: fname, type: 0x4E, length: UInt8(w), decimals: UInt8(d))
        case .string(_, let w):
            return DBFField(name: fname, type: 0x43 /* C */, length: UInt8(max(1, w)), decimals: 0)
        }
    }

    let headerLength = 32 + 32 * fields.count + 1
    let recordLength = 1 + fields.reduce(0) { $0 + Int($1.length) }
    var out = Data()

    // Header (32 bytes)
    out.append(0x03)                                // version: dBase III no memo
    let now = Calendar(identifier: .gregorian)
        .dateComponents(in: TimeZone(identifier: "UTC")!, from: Date())
    out.append(UInt8((now.year ?? 2000) - 1900))
    out.append(UInt8(now.month ?? 1))
    out.append(UInt8(now.day ?? 1))
    out.appendLE(Int32(rows.count))
    out.appendLE(Int16(headerLength))
    out.appendLE(Int16(recordLength))
    out.append(Data(count: 20))                     // reserved/flags

    // Field descriptors
    for f in fields {
        var nameBytes = Data(f.name.uppercased().utf8)
        if nameBytes.count > 10 {
            nameBytes = nameBytes.prefix(10)
        }
        out.append(nameBytes)
        out.append(Data(count: 11 - nameBytes.count)) // pad to 11
        out.append(f.type)
        out.append(Data(count: 4))                    // field data address
        out.append(f.length)
        out.append(f.decimals)
        out.append(Data(count: 14))                   // reserved + flags
    }
    out.append(0x0D)                                   // header terminator

    // Records
    for row in rows {
        out.append(0x20)                               // not-deleted flag
        precondition(row.count == fields.count,
                     "dBase row / field count mismatch")
        for (idx, cell) in row.enumerated() {
            let f = fields[idx]
            let encoded = encodeDBFCell(cell.1, width: Int(f.length), type: f.type)
            out.append(encoded)
        }
    }
    out.append(0x1A)                                   // EOF marker
    return out
}

private func encodeDBFCell(_ value: DBFValue, width: Int, type: UInt8) -> Data {
    switch value {
    case .int(let n):
        return rightAlignedAscii(String(n), width: width)
    case .double(let x, _, let decimals):
        let formatted = String(format: "%.\(decimals)f", x)
        return rightAlignedAscii(formatted, width: width)
    case .string(let s, _):
        // dBase III character fields are left-aligned, space-padded,
        // fixed-width. Truncate long strings so we never exceed width.
        let utf8 = Array(s.utf8)
        var out = Data()
        if utf8.count >= width {
            out.append(Data(utf8.prefix(width)))
        } else {
            out.append(Data(utf8))
            out.append(Data(repeating: 0x20, count: width - utf8.count))
        }
        return out
    }
}

private func rightAlignedAscii(_ s: String, width: Int) -> Data {
    let utf8 = Array(s.utf8)
    if utf8.count >= width {
        return Data(utf8.prefix(width))
    }
    var out = Data(repeating: 0x20, count: width - utf8.count)
    out.append(Data(utf8))
    return out
}

// MARK: - PRJ content

/// Esri-style WKT for WGS 84, compatible with QGIS / ArcGIS / OGR readers.
let wgs84PRJ: String =
#"GEOGCS["GCS_WGS_1984",DATUM["D_WGS_1984",SPHEROID["WGS_1984",6378137.0,298.257223563]],PRIMEM["Greenwich",0.0],UNIT["Degree",0.0174532925199433]]"#

// MARK: - GeoJSON polygon → ShapeGeometry.polygon

private func parseStratumPolygon(_ json: String) throws -> ShapeGeometry? {
    guard let data = json.data(using: .utf8),
          let any = try? JSONSerialization.jsonObject(with: data),
          let dict = any as? [String: Any],
          let type = dict["type"] as? String,
          let coordsAny = dict["coordinates"]
    else { return nil }

    // Collect rings across Polygon and MultiPolygon; ESRI Polygons hold
    // all rings in a single shape (outer ring clockwise, holes CCW).
    var parts: [Int32] = []
    var points: [(x: Double, y: Double)] = []

    func appendRing(_ coords: [[Double]]) {
        guard !coords.isEmpty else { return }
        parts.append(Int32(points.count))
        for pt in coords where pt.count >= 2 {
            points.append((x: pt[0], y: pt[1]))
        }
    }

    switch type {
    case "Polygon":
        guard let rings = coordsAny as? [[[Double]]] else { return nil }
        for r in rings { appendRing(r) }
    case "MultiPolygon":
        guard let polys = coordsAny as? [[[[Double]]]] else { return nil }
        for poly in polys { for r in poly { appendRing(r) } }
    default:
        return nil
    }
    guard !points.isEmpty else { return nil }
    return .polygon(parts: parts, points: points)
}

// MARK: - Binary helpers

extension Data {
    mutating func appendBE(_ v: Int32) {
        var bigEndian = v.bigEndian
        Swift.withUnsafeBytes(of: &bigEndian) { self.append(contentsOf: $0) }
    }

    mutating func appendLE(_ v: Int16) {
        var littleEndian = v.littleEndian
        Swift.withUnsafeBytes(of: &littleEndian) { self.append(contentsOf: $0) }
    }

    mutating func appendLE(_ v: Int32) {
        var littleEndian = v.littleEndian
        Swift.withUnsafeBytes(of: &littleEndian) { self.append(contentsOf: $0) }
    }

    mutating func appendLE(_ v: UInt32) {
        var littleEndian = v.littleEndian
        Swift.withUnsafeBytes(of: &littleEndian) { self.append(contentsOf: $0) }
    }

    mutating func appendLE(_ v: UInt16) {
        var littleEndian = v.littleEndian
        Swift.withUnsafeBytes(of: &littleEndian) { self.append(contentsOf: $0) }
    }

    mutating func appendLE(_ v: Double) {
        // IEEE 754 double-precision, little-endian (shapefile convention).
        var bits = v.bitPattern.littleEndian
        Swift.withUnsafeBytes(of: &bits) { self.append(contentsOf: $0) }
    }
}
