// ESRI Shapefile READER — the inverse of Export/ShapefileExporter.
//
// ## References
//   * ESRI Shapefile Technical Description (ESRI White Paper, 1998).
//   * OGC WKT 1 and WKT 2 (the `.prj` sidecar's two grammars — GDAL and
//     ESRI still write WKT1, PROJ 9 writes WKT2 by default).
//
// ## Byte order
// Same mixed-endian layout the exporter writes: big-endian file code,
// file length and record headers; little-endian shape type, part
// indices and every coordinate.
//
// ## Supported shape types
//   1 Point · 3 PolyLine · 5 Polygon, plus their multi-part forms.
// Z and M variants (11/13/15/18, 21/23/25/28) parse their XY ordinates
// and IGNORE the trailing Z/M arrays rather than failing — a boundary
// digitised in 3D is still a valid boundary.
//
// ## Coordinate system
// `classifyPRJ` is the gate, not a convenience: the WKT is PARSED into
// nodes and a geographic CRS has to clear THREE separate tests before a
// single vertex is drawn.
//   * DATUM — where the origin is. Present and not WGS84 ⇒ refused, no
//     matter what the CRS name or an AUTHORITY claims. Read under all
//     of its spellings: WKT1 `DATUM`, WKT2 `GEODETICDATUM`/`TRF`, and
//     the `ENSEMBLE` PROJ 9 writes for WGS 84.
//   * UNIT  — what the numbers mean. Anything but degrees ⇒ refused.
//     Read under WKT1 `UNIT` and WKT2 `ANGLEUNIT`, and WKT2 hides the
//     latter inside `CS[…]` / once per `AXIS[…]`, so those are walked
//     too — a gradian WKT2 sidecar whose only unit sits on its axes is
//     the exact shape that used to walk through this gate untouched.
//   * PRIMEM — where zero longitude is. Anything but Greenwich ⇒
//     refused. WKT1 `PRIMEM` and WKT2 `PRIMEMERIDIAN` alike.
// WHICH FAMILY the file is even in is settled before those three run.
// WKT1 said it in the keyword — `GEOGCS` against `GEOCCS` — but WKT2
// gives a geocentric CRS the same `GEODCRS` keyword as a geographic one
// and separates them by the coordinate system, so the `CS[…]` type is
// read: `CS[Cartesian,3]` (EPSG:4978, earth-centred X/Y/Z metres) is
// refused as geocentric rather than asked for an angular unit it does
// not carry and then judged to be in degrees.
// Everything refused is named back at the cruiser, so a Korea 2000 /
// Tokyo / gradian / Ferro file is quoted instead of being drawn a few
// hundred metres (or 1,500 km) off. Substring matching is what this file
// must never do — `TOWGS84[…]` contains the letters "WGS84" and appears
// in most WKT for CRSs that are NOT WGS84.
//
// Both grammars are UNDERSTOOD rather than merely tolerated: a genuine
// WGS 84 `.prj` written as WKT2 `GEOGCRS` by PROJ 9 is accepted, because
// refusing a correct file with "this file is not in WGS84 (found:
// WGS 84)" teaches the cruiser to distrust the gate. What is NOT
// understood fails CLOSED — a root keyword with no bracket after it is
// not WKT at all, and one whose closing bracket never arrived is a
// TRUNCATED file rather than a complete declaration. Both are refused as
// an unrecognised coordinate system however WGS84-ish the first quoted
// string in the file happens to look.

import Foundation

public enum ShapefileReadError: Error, CustomStringConvertible {
    case notAShapefile(String)
    case truncated(String)
    case unsupportedShapeType(Int32)

    public var description: String {
        switch self {
        case .notAShapefile(let r): return "Not a shapefile: \(r)"
        case .truncated(let r): return "Shapefile truncated: \(r)"
        case .unsupportedShapeType(let t):
            return "Unsupported shapefile shape type \(t) (supported: 1 Point, 3 PolyLine, 5 Polygon)"
        }
    }
}

public enum ShapefileReader {

    // MARK: - .prj → coordinate-system verdict

    public enum CRSVerdict: Equatable, Sendable {
        /// A geographic WGS84 CRS — the only thing the importer draws.
        case wgs84(name: String)
        /// Anything else, carrying the name to quote back at the cruiser.
        case other(name: String)
    }

    /// Classify a `.prj` WKT string. WKT1 and WKT2 alike — GDAL and ESRI
    /// still write `GEOGCS`, PROJ 9 writes `GEOGCRS`, and both describe
    /// the same file.
    ///
    /// STRUCTURAL, never substring. The WKT is parsed into nodes and the
    /// verdict rests on the root keyword, the datum name, the CRS
    /// name, the CRS's OWN `AUTHORITY`/`ID`, its angular unit and its
    /// prime meridian — names compared WHOLE against the recognised WGS84
    /// spellings, numbers compared against the values degrees and
    /// Greenwich actually have.
    ///
    /// The DATUM is decisive whenever it is present, and it is checked
    /// BEFORE the name and the authority rather than alongside them.
    /// `GEOGCS["WGS 84", DATUM["Tokyo", SPHEROID["Bessel 1841",…], …]]`
    /// is a real and common export: a Korean file relabelled by a tool
    /// that copied the CRS name and left the datum alone. Accepting it on
    /// the strength of the name — or of an `AUTHORITY["EPSG","4326"]`
    /// pasted in beside it — draws the stand a few hundred metres off, in
    /// range, with nothing said. Only a WKT carrying no datum at all
    /// falls back to the CRS name and the authority code.
    ///
    /// Why that matters. A datum's `TOWGS84[…]` block holds the seven
    /// parameters that convert that datum TO WGS84, and GDAL/OGC WKT1
    /// emits it for most CRSs that are NOT WGS84. Any test that merely
    /// LOOKS for "WGS84" in the text therefore accepts Korea 2000,
    /// Tokyo, Bessel and friends — all of which are in degrees and in
    /// range, so the coordinate gate cannot catch them either — and a
    /// Korean stand boundary is drawn a few hundred metres off with no
    /// warning at all. `TOWGS84` names the TARGET of a transformation,
    /// not the CRS the file is in, so it is parsed as an ordinary child
    /// node and never consulted.
    ///
    /// The SPHEROID is deliberately not consulted either: KGD2002 and
    /// the ITRF realisations are routinely written with
    /// `SPHEROID["WGS 84",…]` while sitting on their own datum. An
    /// ellipsoid is not a datum.
    public static func classifyPRJ(_ wkt: String) -> CRSVerdict {
        let strippable = CharacterSet.whitespacesAndNewlines
            .union(CharacterSet(charactersIn: "\u{FEFF}"))
        let trimmed = wkt.trimmingCharacters(in: strippable)
        guard !trimmed.isEmpty else { return .other(name: "empty .prj file") }
        // FAIL CLOSED. `WKTNode.parse` returns nil for anything that is
        // not one COMPLETE `KEYWORD[…]` — a root keyword with no bracket
        // after it, and equally a root whose closing bracket never
        // arrived, which is the shape a sidecar truncated by a failed
        // write takes. There is a quoted name in such a file and it is
        // often a WGS84 spelling, but a name is not a declaration: the
        // file states nothing this gate can read, so it is refused.
        guard let root = WKTNode.parse(trimmed) else {
            return .other(name: "an unrecognised coordinate system")
        }

        switch root.keyword {
        // Projected — eastings and northings in metres/feet, whatever
        // datum they sit on.
        case "PROJCS", "PROJCRS":
            return .other(name: named("projected CRS", root.name)
                ?? "an unnamed projected CRS")
        // Geocentric — earth-centred X/Y/Z metres, not lon/lat.
        case "GEOCCS", "GEOCENTRICCRS":
            return .other(name: named("geocentric CRS", root.name)
                ?? "an unnamed geocentric CRS")
        // A site-local grid with an arbitrary origin — unplaceable.
        case "LOCAL_CS", "ENGCRS", "ENGINEERINGCRS":
            return .other(name: named("local/engineering CRS", root.name)
                ?? "an unnamed local/engineering CRS")
        // Geodetic — WKT1 `GEOGCS` and the WKT2 spellings. The only
        // family that can be accepted, and the only one whose ROOT
        // KEYWORD does not settle what the file holds: WKT2 spells a
        // GEOCENTRIC CRS `GEODCRS` too and tells the two apart by the
        // coordinate system instead. `CS[Cartesian,3]` is earth-centred
        // X/Y/Z metres — EPSG:4978 — not lon/lat, and it declares no
        // `ANGLEUNIT` at all, so the unit gate reads it as WKT1's
        // default degrees and waves it through. Only the coordinate
        // range downstream stopped it, one gate late and naming the
        // symptom ("coordinates outside the WGS84 range") rather than
        // the cause. So the CS is read here, and a geocentric one is
        // refused by the same wording `GEOCCS` gets.
        case "GEOGCS", "GEOGCRS", "GEOGRAPHICCRS", "GEODCRS", "GEODETICCRS":
            if isGeocentric(root) {
                return .other(name: named("geocentric CRS", root.name)
                    ?? "an unnamed geocentric CRS")
            }
            return geographicVerdict(root)
        // BOUNDCRS, COMPD_CS, VERTCRS, a fragment — refused rather than
        // guessed at, and refused by KIND rather than by the name it
        // happens to carry: "found: WGS 84" on a file the app declined
        // to read is a self-contradiction, not an explanation.
        default:
            return .other(name: "an unrecognised coordinate system")
        }
    }

    /// The verdict for a geographic CRS — WKT1 or WKT2, same three tests.
    private static func geographicVerdict(_ root: WKTNode) -> CRSVerdict {
        let geogName = root.name ?? "an unnamed geographic CRS"

        // 1. DATUM — present and decisive, or absent and delegated.
        if let datum = root.child(datumKeywords) {
            let datumName = datum.name ?? ""
            guard isWGS84Spelling(datumName) else {
                return .other(name: datumName.isEmpty
                    ? "an unnamed datum"
                    : "datum \"\(datumName)\"")
            }
        } else if !isWGS84Spelling(geogName),
                  !root.declaresAuthority("EPSG", code: "4326") {
            return .other(name: named("geographic CRS", root.name)
                ?? "an unnamed geographic CRS")
        }

        // 2/3. The datum fixes WHERE the origin is; the angular unit and
        // the prime meridian fix what the stored numbers MEAN. A CRS
        // in gradians is 400 units to the turn instead of 360 and a
        // Ferro-based one is shifted 17.67° — both stay inside |lon| ≤ 180
        // and |lat| ≤ 90, so the coordinate gate downstream cannot see
        // them, and both land a Korean stand well over 1,000 km away.
        if let found = angularUnitRejection(root) { return .other(name: found) }
        if let found = primeMeridianRejection(root) { return .other(name: found) }

        return .wgs84(name: geogName)
    }

    /// `kind "name"` for the refusal, or nil when the node is unnamed and
    /// the caller has to say so in words instead.
    private static func named(_ kind: String, _ name: String?) -> String? {
        guard let name, !name.isEmpty else { return nil }
        return "\(kind) \"\(name)\""
    }

    /// True when a WKT2 geodetic CRS describes GEOCENTRIC coordinates —
    /// earth-centred X/Y/Z metres — rather than the lon/lat this app
    /// draws.
    ///
    /// WKT1 wrote the two families under two keywords, `GEOGCS` and
    /// `GEOCCS`, so the root keyword was the whole answer. WKT2 writes
    /// BOTH as `GEODCRS`/`GEODETICCRS` and settles it in the coordinate
    /// system instead: `CS[Cartesian,3]` is geocentric (EPSG:4978),
    /// `CS[ellipsoidal,2]` and `CS[ellipsoidal,3]` are geographic
    /// (EPSG:4326, EPSG:4979). The CS type is a BARE unquoted word,
    /// which is the reason `WKTNode` keeps those beside the numbers;
    /// a writer that quotes it anyway is read the same way.
    ///
    /// Only the WKT2 spellings are asked. `GEOGCS`/`GEOGCRS` are
    /// geographic by definition, and WKT1's geocentric `GEOCCS` is
    /// already refused by keyword one branch above.
    private static func isGeocentric(_ root: WKTNode) -> Bool {
        guard root.keyword == "GEODCRS" || root.keyword == "GEODETICCRS",
              let cs = root.child("CS"),
              let type = cs.words.first ?? cs.quoted.first else { return false }
        return normalisedName(type) == "CARTESIAN"
    }

    // MARK: - Angular unit and prime meridian

    /// One degree in radians — the factor a WGS84 `.prj` carries on its
    /// angular `UNIT`. Gradians (0.015707…), radians (1.0) and
    /// arc-seconds (4.848e-6) all sit orders of magnitude outside the
    /// window below, while the 15- and 17-digit spellings GDAL and ESRI
    /// write ("0.0174532925199433", "0.017453292519943295") sit well
    /// inside it.
    private static let radiansPerDegree = 0.017453292519943295

    /// Tight enough to separate degrees from every other angular unit in
    /// use, loose enough to absorb the digits a writer chose to keep.
    private static let angleTolerance = 1e-9

    /// The CRS's own angular unit, or nil when it clears the gate. No
    /// unit at all keeps WKT1's default — degrees — which is what the
    /// rest of the app already assumes.
    ///
    /// The declared FACTOR is believed over the declared name whenever
    /// there is one: ESRI writes `UNIT["Decimal_Degree",0.01745…]`, whose
    /// name is on no short list but whose factor is exactly right, and a
    /// unit labelled "degree" while carrying the gradian factor is the
    /// same contradiction the datum rule refuses. Only a unit with no
    /// factor at all is judged by its name.
    ///
    /// EVERY unit `angularUnits` finds has to clear the gate, not just
    /// the first: a WKT2 CRS declares one per axis, and a document that
    /// says degrees on one axis and gradians on the other is not a
    /// document to accept on the strength of whichever came first.
    private static func angularUnitRejection(_ crs: WKTNode) -> String? {
        for unit in angularUnits(crs) {
            let name = unit.name ?? ""
            if let factor = unit.numbers.first {
                if abs(factor - radiansPerDegree) <= angleTolerance { continue }
                return "angular unit \(quoting(name, or: factor))"
            }
            if !degreeUnitNames.contains(normalisedName(name)) {
                return "angular unit \(quoting(name, or: nil))"
            }
        }
        return nil
    }

    /// Every node that can declare the CRS's OWN angular unit.
    ///
    /// WKT1 puts a single `UNIT` directly under the `GEOGCS` and that is
    /// the whole story. WKT2 writes `ANGLEUNIT` instead and puts it
    /// where the coordinate system is described: directly under the CRS,
    /// under its `CS[…]`, or — the spelling PROJ actually emits — once
    /// per `AXIS[…]`, with those axes sitting either beside the `CS[…]`
    /// or nested inside it. Reading only the direct child waved a
    /// gradian WKT2 sidecar straight through this gate, so the `CS`/`AXIS`
    /// subtree is walked as well.
    ///
    /// A `PRIMEM`'s own `ANGLEUNIT` is deliberately NOT collected: it
    /// gives the unit of the meridian OFFSET, not of the stored
    /// coordinates, and it is judged with the prime meridian instead.
    /// Nothing else is descended into either, so an `ELLIPSOID`'s
    /// `LENGTHUNIT` or a datum's internals can never answer for the CRS.
    private static func angularUnits(_ node: WKTNode) -> [WKTNode] {
        var out: [WKTNode] = []
        for child in node.children {
            if angularUnitKeywords.contains(child.keyword) {
                out.append(child)
            } else if child.keyword == "CS" || child.keyword == "AXIS" {
                out += angularUnits(child)
            }
        }
        return out
    }

    /// The CRS's own prime meridian, or nil when it clears the gate. No
    /// `PRIMEM` keeps WKT1's default, Greenwich.
    ///
    /// Same rule as the unit: a declared offset outranks a declared
    /// name, so `PRIMEM["Greenwich",-17.666…]` is refused rather than
    /// believed.
    private static func primeMeridianRejection(_ crs: WKTNode) -> String? {
        guard let primem = crs.child(primeMeridianKeywords) else { return nil }
        let name = primem.name ?? ""
        if let offset = primem.numbers.first {
            if abs(offset) <= angleTolerance { return nil }
            return "prime meridian \(quoting(name, or: offset))"
        }
        return normalisedName(name) == "GREENWICH"
            ? nil : "prime meridian \(quoting(name, or: nil))"
    }

    /// WKT1 `DATUM`, the WKT2 spellings `GEODETICDATUM`/`TRF`, and the
    /// WKT2:2019 `ENSEMBLE` PROJ 9 writes for WGS 84 in place of a
    /// datum — `ENSEMBLE["World Geodetic System 1984 ensemble", …]`.
    /// Missing that last one is what refused a genuine WKT2 WGS 84 file.
    private static let datumKeywords = ["GEODETICDATUM", "DATUM", "TRF", "ENSEMBLE"]

    /// WKT1 `UNIT` and the WKT2 `ANGLEUNIT`.
    private static let angularUnitKeywords: Set<String> = ["UNIT", "ANGLEUNIT"]

    /// WKT1 `PRIMEM` and the WKT2 `PRIMEMERIDIAN`.
    private static let primeMeridianKeywords = ["PRIMEM", "PRIMEMERIDIAN"]

    /// Only reached when a `UNIT`/`PRIMEM` declares no factor at all,
    /// which WKT1's grammar does not actually permit — kept so a
    /// malformed sidecar is refused by name instead of waved through.
    private static let degreeUnitNames: Set<String> = ["DEGREE", "DEGREES"]

    /// Case, spaces and hyphens folded the same way `isWGS84Spelling`
    /// folds them, so "Degree", "degree" and "DEGREE" are one name.
    private static func normalisedName(_ raw: String) -> String {
        raw.uppercased()
            .replacingOccurrences(of: " ", with: "_")
            .replacingOccurrences(of: "-", with: "_")
            .trimmingCharacters(in: CharacterSet(charactersIn: "_"))
    }

    /// What the refusal quotes back: the node's name when it has one,
    /// otherwise the number it carried. Always quoted, so every message
    /// in this family reads `… (found: angular unit "grad")`.
    private static func quoting(_ name: String, or value: Double?) -> String {
        if !name.isEmpty { return "\"\(name)\"" }
        guard let value else { return "\"\"" }
        if value == value.rounded(), abs(value) < 1e15 { return "\"\(Int64(value))\"" }
        var s = String(format: "%.6f", value)
        while s.hasSuffix("0") { s.removeLast() }
        if s.hasSuffix(".") { s.removeLast() }
        return "\"\(s)\""
    }

    /// The spellings that mean WGS84 — GEOGCS names on the left of the
    /// list, DATUM names on the right, since either node may carry it.
    /// Whole strings: a name is on this list or it is not WGS84.
    ///
    /// The "ensemble" spellings matter now that the DATUM decides on its
    /// own: recent PROJ writes `DATUM["World Geodetic System 1984
    /// ensemble",…]`, and dropping it would refuse a genuine WGS84 file
    /// that older code accepted on its GEOGCS name.
    private static let wgs84Spellings: Set<String> = [
        "WGS_84", "WGS84", "WGS_1984",
        "GCS_WGS_1984", "GCS_WGS_84",
        "D_WGS_1984", "D_WGS_84",
        "WORLD_GEODETIC_SYSTEM_1984",
        "D_WORLD_GEODETIC_SYSTEM_1984",
        "WORLD_GEODETIC_SYSTEM_1984_ENSEMBLE",
        "D_WORLD_GEODETIC_SYSTEM_1984_ENSEMBLE"
    ]

    /// Whole-name test. Case, spaces, hyphens and underscores are one
    /// and the same, so "WGS 84", "WGS_84" and "wgs84" agree. A
    /// realisation suffix — "WGS 84 (G1762)" — names an epoch of the
    /// SAME datum, centimetres apart, so it is dropped before the
    /// comparison; what is left must still match a listed spelling
    /// exactly, so "Korea 2000" and "Tokyo" cannot slip through.
    static func isWGS84Spelling(_ raw: String) -> Bool {
        let head = raw.split(separator: "(", maxSplits: 1,
                             omittingEmptySubsequences: false).first.map(String.init) ?? raw
        let normalised = head.uppercased()
            .replacingOccurrences(of: " ", with: "_")
            .replacingOccurrences(of: "-", with: "_")
            .trimmingCharacters(in: CharacterSet(charactersIn: "_"))
        return wgs84Spellings.contains(normalised)
    }

    // MARK: - .shp → features

    /// Parse the geometry records of a `.shp` payload. Coordinates come
    /// back exactly as stored (x, y) → (longitude, latitude); the caller
    /// is responsible for the CRS gate and the lon/lat range check.
    public static func readShapes(_ shp: Data) throws -> [BoundaryFeature] {
        let b = [UInt8](shp)
        guard b.count >= 100 else {
            throw ShapefileReadError.truncated("header shorter than 100 bytes")
        }
        guard i32BE(b, 0) == 9994 else {
            throw ShapefileReadError.notAShapefile("file code is not 9994")
        }
        // File length is in 16-bit words and includes the 100-byte header.
        let declaredBytes = Int(i32BE(b, 24)) * 2
        let end = declaredBytes > 100 && declaredBytes <= b.count ? declaredBytes : b.count

        var out: [BoundaryFeature] = []
        var cursor = 100
        while cursor + 8 <= end {
            let contentWords = Int(i32BE(b, cursor + 4))
            let contentStart = cursor + 8
            let contentEnd = contentStart + contentWords * 2
            guard contentWords > 0, contentEnd <= end else { break }
            if let feature = try parseRecord(b, from: contentStart, to: contentEnd) {
                out.append(feature)
            }
            cursor = contentEnd
        }
        return out
    }

    private static func parseRecord(_ b: [UInt8], from start: Int,
                                    to end: Int) throws -> BoundaryFeature? {
        guard start + 4 <= end else { return nil }
        let type = i32LE(b, start)
        switch type {
        case 0:                       // Null shape — legal, carries nothing.
            return nil
        case 1, 11, 21:               // Point / PointZ / PointM
            guard start + 4 + 16 <= end else {
                throw ShapefileReadError.truncated("point record")
            }
            let x = f64LE(b, start + 4)
            let y = f64LE(b, start + 12)
            return BoundaryFeature(kind: .point,
                                   rings: [[CoordinateConversions.LatLon(latitude: y,
                                                                         longitude: x)]])
        case 8, 18, 28:               // MultiPoint / Z / M
            // Box(32) numPoints(4) points(16·n) — rendered as loose points.
            var p = start + 4 + 32
            guard p + 4 <= end else {
                throw ShapefileReadError.truncated("multipoint header")
            }
            let n = Int(i32LE(b, p)); p += 4
            guard n >= 0, p + n * 16 <= end else {
                throw ShapefileReadError.truncated("multipoint coordinates")
            }
            var pts: [CoordinateConversions.LatLon] = []
            pts.reserveCapacity(n)
            for k in 0..<n {
                pts.append(CoordinateConversions.LatLon(latitude: f64LE(b, p + k * 16 + 8),
                                                        longitude: f64LE(b, p + k * 16)))
            }
            guard let first = pts.first else { return nil }
            // One feature per record keeps the count honest; extra
            // positions ride along as a degenerate multi-position point.
            return BoundaryFeature(kind: .point, rings: [[first]])
        case 3, 13, 23,               // PolyLine / Z / M
             5, 15, 25:               // Polygon  / Z / M
            let isPolygon = (type == 5 || type == 15 || type == 25)
            var p = start + 4 + 32     // skip the record bounding box
            guard p + 8 <= end else {
                throw ShapefileReadError.truncated("polyline/polygon header")
            }
            let numParts = Int(i32LE(b, p)); p += 4
            let numPoints = Int(i32LE(b, p)); p += 4
            guard numParts > 0, numPoints > 0,
                  p + numParts * 4 + numPoints * 16 <= end else {
                throw ShapefileReadError.truncated("polyline/polygon body")
            }
            var partStarts: [Int] = []
            partStarts.reserveCapacity(numParts)
            for k in 0..<numParts { partStarts.append(Int(i32LE(b, p + k * 4))) }
            p += numParts * 4

            var rings: [[CoordinateConversions.LatLon]] = []
            for (idx, first) in partStarts.enumerated() {
                let last = (idx + 1 < partStarts.count) ? partStarts[idx + 1] : numPoints
                guard first >= 0, last <= numPoints, first < last else { continue }
                var ring: [CoordinateConversions.LatLon] = []
                ring.reserveCapacity(last - first)
                for k in first..<last {
                    let off = p + k * 16
                    ring.append(CoordinateConversions.LatLon(latitude: f64LE(b, off + 8),
                                                             longitude: f64LE(b, off)))
                }
                if isPolygon, let a = ring.first, let z = ring.last, a != z {
                    ring.append(a)     // shapefile rings SHOULD close; make sure
                }
                if ring.count >= 2 { rings.append(ring) }
            }
            guard !rings.isEmpty else { return nil }
            // Z/M ordinates trail the XY block — deliberately not read.
            return BoundaryFeature(kind: isPolygon ? .polygon : .line, rings: rings)
        default:
            throw ShapefileReadError.unsupportedShapeType(type)
        }
    }

    // MARK: - Scalar reads

    private static func i32BE(_ b: [UInt8], _ i: Int) -> Int32 {
        guard i >= 0, i + 4 <= b.count else { return 0 }
        let v = (UInt32(b[i]) << 24) | (UInt32(b[i + 1]) << 16)
            | (UInt32(b[i + 2]) << 8) | UInt32(b[i + 3])
        return Int32(bitPattern: v)
    }

    private static func i32LE(_ b: [UInt8], _ i: Int) -> Int32 {
        guard i >= 0, i + 4 <= b.count else { return 0 }
        let v = UInt32(b[i]) | (UInt32(b[i + 1]) << 8)
            | (UInt32(b[i + 2]) << 16) | (UInt32(b[i + 3]) << 24)
        return Int32(bitPattern: v)
    }

    private static func f64LE(_ b: [UInt8], _ i: Int) -> Double {
        guard i >= 0, i + 8 <= b.count else { return 0 }
        var bits: UInt64 = 0
        for k in (0..<8).reversed() { bits = (bits << 8) | UInt64(b[i + k]) }
        return Double(bitPattern: bits)
    }
}

// MARK: - Minimal WKT node parser

/// One node of an OGC WKT string, either grammar —
/// `KEYWORD["quoted", 123, CHILD[…]]`.
///
/// Deliberately tiny: the reader needs node NAMES and node IDENTITY, not
/// a CRS model. Parsing rather than substring-matching is the entire
/// point (see `ShapefileReader.classifyPRJ`) — once the text is a tree,
/// a `TOWGS84[…]` transformation block is just a child node nobody asks
/// about, and a `SPHEROID`'s or `DATUM`'s own `AUTHORITY` can never be
/// mistaken for the CRS's identity.
struct WKTNode {

    /// Upper-cased keyword: "GEOGCS", "DATUM", "AUTHORITY"…
    let keyword: String
    /// Quoted arguments in order, quotes removed.
    let quoted: [String]
    /// Bare numeric arguments in order, this node's own only. A
    /// `UNIT`/`ANGLEUNIT`'s conversion factor, a `PRIMEM`'s longitude
    /// offset and a WKT2 `ID`'s numeric code live here, and each decides
    /// whether the file is really in degrees from Greenwich — so unlike
    /// every other bare token they are kept.
    let numbers: [Double]
    /// Bare NON-numeric arguments in order — the unquoted words WKT2
    /// writes for a coordinate system's type (`CS[Cartesian,3]`) and for
    /// an axis direction (`AXIS["(X)",geocentricX]`). The first of them
    /// on a `CS[…]` is what separates a geocentric `GEODCRS` from a
    /// geographic one, and nothing else in the file says which it is.
    let words: [String]
    let children: [WKTNode]

    /// A WKT node's name is its first quoted argument.
    var name: String? {
        guard let first = quoted.first?.trimmingCharacters(in: .whitespaces),
              !first.isEmpty else { return nil }
        return first
    }

    func child(_ keyword: String) -> WKTNode? {
        children.first { $0.keyword == keyword }
    }

    /// The first child matching any of `keywords`, in the order given —
    /// one node under all the spellings the two WKT grammars give it.
    func child(_ keywords: [String]) -> WKTNode? {
        for keyword in keywords {
            if let match = children.first(where: { $0.keyword == keyword }) {
                return match
            }
        }
        return nil
    }

    /// WKT1 `AUTHORITY["EPSG","4326"]` or WKT2 `ID["EPSG",4326]` as a
    /// DIRECT child of this node — the node's own identity, not one
    /// borrowed from a nested spheroid, primem or unit. WKT2 writes the
    /// code as a bare number rather than a quoted string, so both are
    /// accepted.
    func declaresAuthority(_ authority: String, code: String) -> Bool {
        children.contains { node in
            guard node.keyword == "AUTHORITY" || node.keyword == "ID",
                  let declared = node.quoted.first,
                  declared.caseInsensitiveCompare(authority) == .orderedSame
            else { return false }
            if node.quoted.count >= 2,
               node.quoted[1].trimmingCharacters(in: .whitespaces) == code {
                return true
            }
            guard let number = node.numbers.first, number == number.rounded(),
                  abs(number) < 1e15 else { return false }
            return String(Int64(number)) == code
        }
    }

    /// Parse the outermost node; nil when the text is not a COMPLETE
    /// bracketed WKT node.
    ///
    /// Complete means the closing bracket was actually reached. A node
    /// whose arguments simply ran out at end-of-input is a TRUNCATED
    /// node, and reading one as if it were finished is how a `.prj` cut
    /// short by a failed write — `GEOGCS["WGS 84"`, on a file whose real
    /// body went on `DATUM["Tokyo",…]` — used to be accepted: the datum,
    /// unit and meridian gates all ran against an empty child list and
    /// found nothing to object to, and the stand was drawn a few hundred
    /// metres off in silence. A name is not a declaration, so an
    /// unterminated node states nothing this gate can read and is
    /// refused as an unrecognised coordinate system.
    ///
    /// Only the root is asked. A child that ran out of input can only do
    /// so at end-of-input, which leaves every ancestor unterminated too.
    static func parse(_ wkt: String) -> WKTNode? {
        let chars = Array(wkt)
        var i = 0
        var closed = false
        guard let node = parseNode(chars, &i, closed: &closed), closed else {
            return nil
        }
        return node
    }

    /// `closed` comes back true only when this node's own closing
    /// bracket was consumed.
    private static func parseNode(_ c: [Character], _ i: inout Int,
                                  closed: inout Bool) -> WKTNode? {
        closed = false
        skipSpace(c, &i)
        let keyStart = i
        while i < c.count, c[i].isLetter || c[i].isNumber || c[i] == "_" { i += 1 }
        let keyword = String(c[keyStart..<i]).uppercased()
        guard !keyword.isEmpty else { return nil }
        skipSpace(c, &i)
        // WKT permits either bracket flavour.
        guard i < c.count, c[i] == "[" || c[i] == "(" else { return nil }
        let closing: Character = c[i] == "[" ? "]" : ")"
        i += 1

        var quoted: [String] = []
        var numbers: [Double] = []
        var words: [String] = []
        var children: [WKTNode] = []
        while i < c.count {
            skipSpace(c, &i)
            guard i < c.count else { break }
            if c[i] == closing { i += 1; closed = true; break }
            if c[i] == "," { i += 1; continue }
            if c[i] == "\"" {
                i += 1
                var s = ""
                while i < c.count {
                    if c[i] == "\"" {
                        // A literal quote inside a name is doubled.
                        if i + 1 < c.count, c[i + 1] == "\"" {
                            s.append("\""); i += 2; continue
                        }
                        i += 1
                        break
                    }
                    s.append(c[i])
                    i += 1
                }
                quoted.append(s)
                continue
            }
            if c[i].isLetter || c[i] == "_" {
                let save = i
                // A child that never met its own closing bracket is
                // kept all the same: it can only have run out at
                // end-of-input, so this node cannot close either and
                // the root is refused whole.
                var childClosed = false
                if let child = parseNode(c, &i, closed: &childClosed) {
                    children.append(child)
                    continue
                }
                i = save
            }
            // A bare token — a number, or a bare word. Numbers are KEPT:
            // `UNIT["grad",0.015708…]` and `PRIMEM["Ferro",-17.666…]`
            // hide the whole verdict in one, and reading only the name
            // lets both through. The bare WORDS are kept beside them:
            // WKT2 writes a coordinate system's type as one
            // (`CS[Cartesian,3]`), and that word is the only thing in
            // the file that says whether a `GEODCRS` holds lon/lat or
            // earth-centred metres.
            let tokenStart = i
            while i < c.count, c[i] != ",", c[i] != closing, c[i] != "\"" { i += 1 }
            let token = String(c[tokenStart..<i]).trimmingCharacters(in: .whitespaces)
            if let value = Double(token), value.isFinite {
                numbers.append(value)
            } else if !token.isEmpty {
                words.append(token)
            }
        }
        return WKTNode(keyword: keyword, quoted: quoted, numbers: numbers,
                       words: words, children: children)
    }

    private static func skipSpace(_ c: [Character], _ i: inout Int) {
        while i < c.count, c[i].isWhitespace { i += 1 }
    }
}
