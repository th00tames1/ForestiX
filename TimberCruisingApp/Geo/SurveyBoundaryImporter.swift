// Survey-boundary import — SHP (zipped or with a sibling .prj), KML,
// KMZ and GeoJSON → normalised WGS84 `BoundaryFeature`s.
//
// ## The one rule
// WGS84 ONLY, AND NEVER SILENTLY WRONG. A boundary that names a CRS the
// app cannot confirm is never handed to the map, because a stand
// boundary drawn 500 km off (or 5 m off) is worse than no boundary at
// all: the cruiser trusts it.
//
// ## What "confirmed" actually means, per format
// Be precise about this, because the three formats are not equal:
//   * SHP — CONFIRMED. The `.prj` states the CRS and is read, in WKT1
//     or WKT2: datum, angular unit and prime meridian all have to be
//     WGS84 / degrees / Greenwich. No `.prj`, no import; and a `.prj`
//     the parser cannot read is refused rather than guessed at.
//   * GeoJSON — CONFIRMED when the legacy `crs` member is present ON ANY
//     OBJECT — root, Feature or geometry, all three of which GeoJSON
//     2008 §3 allowed and real exports use — in which case it must spell
//     WGS84. ASSUMED only when the whole document declares nothing,
//     which is what RFC 7946 §4 says the silence means.
//   * KML/KMZ — ASSUMED, always. KML §16.2 fixes the CRS at WGS84 and
//     the format has no mechanism to say otherwise, so there is nothing
//     to read and nothing to confirm.
// Everything assumed is then RANGE-CHECKED, which is a much weaker
// guarantee than confirmation: it catches projected metres, not a
// neighbouring datum. A KML in Korea 2000 degrees still gets through, and
// that is a limit of the format, not an oversight here.
//
// Two independent gates:
//   1. DECLARED CRS — shapefiles must ship a `.prj` that resolves to a
//      geographic WGS84 CRS in degrees from Greenwich. A projected
//      `.prj` (UTM, Korea 2000 / EPSG:5186, Lambert, State Plane…), a
//      geographic one on another datum, one in gradians, one on the
//      Ferro meridian, or a missing `.prj` refuses.
//      It must be the shapefile's OWN `.prj` — same directory, same
//      stem — never merely "some .prj in the archive", which would
//      classify one file's geometry with another file's CRS. A `.zip`
//      holding several `.shp`s imports ALL of them, each gated by its
//      own sidecar, so nothing is dropped in silence.
//      GeoJSON has the same gate applied to its `crs` member wherever
//      in the document it carries one; KML/KMZ have no declaration to
//      read at all, so there is nothing there to confirm.
//   2. ACTUAL COORDINATES — every parsed position must satisfy
//      |lon| ≤ 180 and |lat| ≤ 90. Projected easting/northing metres
//      blow straight past that, so this catches a file whose `.prj`
//      lies, a "GeoJSON" exported in metres, and a KML mangled by a
//      conversion tool. Run on EVERY format, including the ones the
//      standard says are already WGS84.

import Foundation
import Common

public enum BoundaryImportError: Error, CustomStringConvertible {
    /// The CRS gate. `found` is quoted back verbatim to the cruiser.
    case notWGS84(found: String)
    case unsupportedFormat(String)
    case noFeatures
    case malformed(String)
    case unreadable(String)

    public var description: String {
        switch self {
        case .notWGS84(let found):
            return "This file is not in WGS84 (found: \(found)). "
                + "Convert it to WGS84 / EPSG:4326 (for example in QGIS) and import again."
        case .unsupportedFormat(let ext):
            return "Unsupported file type \"\(ext)\". Import SHP (.shp + .prj, or .zip), KML, KMZ or GeoJSON."
        case .noFeatures:
            return "No polygons or lines were found in this file."
        case .malformed(let reason):
            return reason
        case .unreadable(let reason):
            return "The file could not be read (\(reason))."
        }
    }
}

public enum SurveyBoundaryImporter {

    /// The formats line the sheet shows under the import button. Lives
    /// next to the parsers so the promise and the code cannot drift.
    public static let formatHint = "SHP (.shp + .prj, or .zip) · KML/KMZ · GeoJSON"

    // MARK: - Entry points

    /// Import from a file on disk. A bare `.shp` looks for its sibling
    /// `.prj` next to it; a `.zip` finds both inside the archive.
    public static func load(fileURL: URL) throws -> SurveyBoundary {
        let data: Data
        do {
            data = try Data(contentsOf: fileURL)
        } catch {
            throw BoundaryImportError.unreadable(error.localizedDescription)
        }
        var prj: Data?
        if resolvedFormat(fileName: fileURL.lastPathComponent, data: data) == .shp {
            prj = siblingPRJ(for: fileURL)
        }
        return try load(data: data,
                        fileName: fileURL.lastPathComponent,
                        siblingPRJ: prj)
    }

    /// Import from bytes already in memory. `siblingPRJ` only matters for
    /// a bare `.shp` payload.
    public static func load(data: Data,
                            fileName: String,
                            siblingPRJ: Data? = nil) throws -> SurveyBoundary {
        let format = resolvedFormat(fileName: fileName, data: data)
        let features: [BoundaryFeature]
        let label: String
        switch format {
        case .geoJSON:
            features = try SurveyBoundaryGeoJSON.parse(data)
            label = "GeoJSON"
        case .kml:
            features = try SurveyBoundaryKML.parse(data)
            label = "KML"
        case .kmz:
            features = try parseKMZ(data)
            label = "KMZ"
        case .zip:
            features = try parseZippedShapefile(data)
            label = "SHP"
        case .shp:
            features = try parseShapefile(shp: data, prj: siblingPRJ)
            label = "SHP"
        case .unknown(let ext):
            throw BoundaryImportError.unsupportedFormat(ext)
        }

        // GATE 2 — coordinates, every format, no exceptions.
        try verifyCoordinateRange(features)

        let usable = features.filter { !$0.rings.isEmpty }
        guard !usable.isEmpty else { throw BoundaryImportError.noFeatures }

        return SurveyBoundary(displayName: baseName(fileName),
                              importedAt: Date(),
                              sourceFormat: label,
                              features: usable)
    }

    // MARK: - Gate 2: coordinate range

    /// |lon| ≤ 180 and |lat| ≤ 90, or the data is projected regardless of
    /// what any header claims.
    public static func verifyCoordinateRange(_ features: [BoundaryFeature]) throws {
        for feature in features {
            for p in feature.allPoints {
                guard p.longitude.isFinite, p.latitude.isFinite else {
                    throw BoundaryImportError.notWGS84(
                        found: "a coordinate that is not a number")
                }
                if abs(p.longitude) > 180 || abs(p.latitude) > 90 {
                    throw BoundaryImportError.notWGS84(
                        found: "coordinates outside the WGS84 range — "
                            + "lon \(trimmed(p.longitude)), lat \(trimmed(p.latitude))")
                }
            }
        }
    }

    /// Whole numbers print as integers, everything else to at most six
    /// decimals with trailing zeros dropped — the same rendering the
    /// Android sibling uses so the refusal reads identically.
    private static func trimmed(_ v: Double) -> String {
        if v == v.rounded(), abs(v) < 1e15 { return String(Int64(v)) }
        var s = String(format: "%.6f", v)
        while s.hasSuffix("0") { s.removeLast() }
        if s.hasSuffix(".") { s.removeLast() }
        return s
    }

    // MARK: - Shapefile

    private static func parseShapefile(shp: Data, prj: Data?) throws -> [BoundaryFeature] {
        // GATE 1 — the declared CRS.
        guard let prj, let wkt = String(data: prj, encoding: .utf8)
                ?? String(data: prj, encoding: .isoLatin1) else {
            throw BoundaryImportError.notWGS84(found: "no .prj file")
        }
        switch ShapefileReader.classifyPRJ(wkt) {
        case .wgs84:
            break
        case .other(let name):
            throw BoundaryImportError.notWGS84(found: name)
        }
        do {
            return try ShapefileReader.readShapes(shp)
        } catch let e as ShapefileReadError {
            throw BoundaryImportError.malformed(e.description)
        }
    }

    private static func parseZippedShapefile(_ archive: Data) throws -> [BoundaryFeature] {
        let entries: [ZipEntry]
        do { entries = try ZipReader.entries(in: archive) }
        catch { throw BoundaryImportError.unreadable(String(describing: error)) }

        let files = entries.filter { !$0.isDirectory && !$0.name.hasPrefix("__MACOSX/") }
        let shpEntries = files.filter { $0.pathExtension == "shp" }
        // A .zip may also be a zipped KML/KMZ payload — accept that too
        // rather than telling the cruiser their file is broken.
        if shpEntries.isEmpty {
            if let kml = files.first(where: { $0.pathExtension == "kml" }) {
                return try SurveyBoundaryKML.parse(try extract(kml, from: archive))
            }
            throw BoundaryImportError.malformed(
                "The .zip contains no .shp file.")
        }

        // EVERY shapefile in the archive is imported, each gated by its
        // OWN sidecar. Taking the first and dropping the rest lost a
        // stand with nothing said about it; and a second .shp in a
        // different CRS must never ride in on the first one's .prj.
        var out: [BoundaryFeature] = []
        for shpEntry in shpEntries {
            let shp = try extract(shpEntry, from: archive)
            let prj = try matchingPRJ(for: shpEntry, in: files, archive: archive)
            out += try parseShapefile(shp: shp, prj: prj)
        }
        return out
    }

    /// The `.prj` belonging to THIS `.shp` — same directory, same stem,
    /// case-folded. Never any other `.prj` in the archive: a sidecar
    /// that does not belong to the file describes a different CRS, and
    /// the entire point of the gate is that the CRS it reports is the
    /// one the geometry is actually in. No match means no declaration,
    /// which `parseShapefile` refuses by name.
    private static func matchingPRJ(for shp: ZipEntry,
                                    in files: [ZipEntry],
                                    archive: Data) throws -> Data? {
        let stem = stemPath(shp.name)
        guard let entry = files.first(where: {
            $0.pathExtension == "prj" && stemPath($0.name) == stem
        }) else { return nil }
        return try extract(entry, from: archive)
    }

    /// Directory + stem, case-folded — the identity a shapefile shares
    /// with its sidecars.
    private static func stemPath(_ name: String) -> String {
        (name as NSString).deletingPathExtension.lowercased()
    }

    /// Sibling `.prj` next to a bare `.shp` (case-insensitive extension).
    private static func siblingPRJ(for shpURL: URL) -> Data? {
        let stem = shpURL.deletingPathExtension()
        for ext in ["prj", "PRJ", "Prj"] {
            let candidate = stem.appendingPathExtension(ext)
            if let data = try? Data(contentsOf: candidate) { return data }
        }
        return nil
    }

    // MARK: - KMZ

    private static func parseKMZ(_ archive: Data) throws -> [BoundaryFeature] {
        let entries: [ZipEntry]
        do { entries = try ZipReader.entries(in: archive) }
        catch { throw BoundaryImportError.unreadable(String(describing: error)) }

        let candidates = entries.filter {
            !$0.isDirectory && $0.pathExtension == "kml"
                && !$0.name.hasPrefix("__MACOSX/")
        }
        // KMZ §  the root document is doc.kml by convention; otherwise
        // take the first .kml in the archive.
        let chosen = candidates.first { baseName($0.name).lowercased() == "doc" }
            ?? candidates.first
        guard let chosen else {
            throw BoundaryImportError.malformed("The .kmz contains no .kml document.")
        }
        return try SurveyBoundaryKML.parse(try extract(chosen, from: archive))
    }

    private static func extract(_ entry: ZipEntry, from archive: Data) throws -> Data {
        do { return try ZipReader.extract(entry, from: archive) }
        catch { throw BoundaryImportError.unreadable(String(describing: error)) }
    }

    // MARK: - Format resolution

    enum Format: Equatable {
        case shp, zip, kml, kmz, geoJSON
        case unknown(String)
    }

    /// Extension first; when it says nothing useful (iOS hands over
    /// extension-less copies often enough), sniff the magic bytes.
    static func resolvedFormat(fileName: String, data: Data) -> Format {
        switch (fileName as NSString).pathExtension.lowercased() {
        case "shp": return .shp
        case "zip": return .zip
        case "kml": return .kml
        case "kmz": return .kmz
        case "geojson", "json": return .geoJSON
        default: break
        }
        return sniff(data, fallbackExtension: (fileName as NSString).pathExtension)
    }

    private static func sniff(_ data: Data, fallbackExtension: String) -> Format {
        let head = [UInt8](data.prefix(8))
        if head.count >= 4, head[0] == 0x50, head[1] == 0x4B,
           (head[2] == 0x03 || head[2] == 0x05 || head[2] == 0x07) {
            return .zip                        // PK\x03\x04 — could hold either
        }
        if head.count >= 4 {
            let code = (UInt32(head[0]) << 24) | (UInt32(head[1]) << 16)
                | (UInt32(head[2]) << 8) | UInt32(head[3])
            if code == 9994 { return .shp }    // shapefile big-endian file code
        }
        for byte in head {
            if byte == UInt8(ascii: "{") || byte == UInt8(ascii: "[") { return .geoJSON }
            if byte == UInt8(ascii: "<") { return .kml }
            if byte == 0x20 || byte == 0x09 || byte == 0x0A || byte == 0x0D { continue }
            break
        }
        return .unknown(fallbackExtension.isEmpty ? "(no extension)" : fallbackExtension)
    }

    static func baseName(_ path: String) -> String {
        let last = (path as NSString).lastPathComponent
        let stem = (last as NSString).deletingPathExtension
        return stem.isEmpty ? last : stem
    }
}
