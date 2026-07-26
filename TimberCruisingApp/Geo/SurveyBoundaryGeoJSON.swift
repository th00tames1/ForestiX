// RFC 7946 GeoJSON → BoundaryFeature.
//
// Separate from the polygon-only `GeoJSONImporter` (which serves stratum
// import and computes acreage) because a survey boundary is allowed to be
// a LineString run, a mixed FeatureCollection or a bare geometry, and
// must keep POINTS too — a shapefile of shape type 1 lands here after
// normalisation.
//
// RFC 7946 §4 fixes the CRS at WGS84 lon/lat, so a conforming file has
// nothing to declare — but the 2008 spec's `crs` member is still written
// by ogr2ogr, ArcGIS and half the exporters in the field, and when it
// says EPSG:4737 (Korea 2000) the positions are degrees, in range, and a
// few hundred metres off the ground truth. `GeoJSONCRS` reads it —
// EVERYWHERE the 2008 spec allowed it to appear, which is on any GeoJSON
// object and not just the root — and a declaration that is not WGS84
// refuses. Only a file that declares NOTHING anywhere gets the RFC 7946
// assumption. Either way the range check downstream still runs, because
// plenty of files claim RFC 7946 while carrying projected metres.

import Foundation

public enum SurveyBoundaryGeoJSON {

    public static func parse(_ data: Data) throws -> [BoundaryFeature] {
        let any: Any
        do {
            any = try JSONSerialization.jsonObject(with: data, options: [])
        } catch {
            throw BoundaryImportError.malformed(
                "GeoJSON could not be parsed (\(error.localizedDescription))")
        }
        guard let root = any as? [String: Any] else {
            throw BoundaryImportError.malformed("GeoJSON root is not an object")
        }
        // GATE 1 for GeoJSON — the declared CRS, wherever in the
        // document it was declared.
        if let found = GeoJSONCRS.rejectionAnywhere(root) {
            throw BoundaryImportError.notWGS84(found: found)
        }
        var out: [BoundaryFeature] = []
        try collect(root, name: nil, into: &out)
        return out
    }

    private static func collect(_ node: [String: Any],
                                name: String?,
                                into out: inout [BoundaryFeature]) throws {
        let type = (node["type"] as? String) ?? ""
        switch type {
        case "FeatureCollection":
            guard let features = node["features"] as? [Any] else {
                throw BoundaryImportError.malformed("FeatureCollection has no features array")
            }
            for f in features {
                guard let dict = f as? [String: Any] else { continue }
                try collect(dict, name: nil, into: &out)
            }

        case "Feature":
            let props = (node["properties"] as? [String: Any]) ?? [:]
            let label = (props["name"] as? String)
                ?? (props["Name"] as? String)
                ?? (props["NAME"] as? String)
            guard let geometry = node["geometry"] as? [String: Any] else { return }
            try collect(geometry, name: label, into: &out)

        case "GeometryCollection":
            guard let geoms = node["geometries"] as? [Any] else {
                throw BoundaryImportError.malformed("GeometryCollection has no geometries array")
            }
            for g in geoms {
                guard let dict = g as? [String: Any] else { continue }
                try collect(dict, name: name, into: &out)
            }

        case "Polygon":
            let rings = try ringList(node["coordinates"])
            guard !rings.isEmpty else { return }
            out.append(BoundaryFeature(kind: .polygon,
                                       rings: closeRings(rings),
                                       name: name))

        case "MultiPolygon":
            guard let raw = node["coordinates"] as? [Any] else {
                throw BoundaryImportError.malformed("MultiPolygon coordinates malformed")
            }
            for polygon in raw {
                let rings = try ringList(polygon)
                guard !rings.isEmpty else { continue }
                out.append(BoundaryFeature(kind: .polygon,
                                           rings: closeRings(rings),
                                           name: name))
            }

        case "LineString":
            let path = try positionList(node["coordinates"])
            guard path.count >= 2 else { return }
            out.append(BoundaryFeature(kind: .line, rings: [path], name: name))

        case "MultiLineString":
            guard let raw = node["coordinates"] as? [Any] else {
                throw BoundaryImportError.malformed("MultiLineString coordinates malformed")
            }
            for line in raw {
                let path = try positionList(line)
                guard path.count >= 2 else { continue }
                out.append(BoundaryFeature(kind: .line, rings: [path], name: name))
            }

        case "Point":
            let p = try position(node["coordinates"])
            out.append(BoundaryFeature(kind: .point, rings: [[p]], name: name))

        case "MultiPoint":
            let pts = try positionList(node["coordinates"])
            for p in pts {
                out.append(BoundaryFeature(kind: .point, rings: [[p]], name: name))
            }

        case "":
            throw BoundaryImportError.malformed("GeoJSON object has no type")

        default:
            throw BoundaryImportError.malformed("Unsupported GeoJSON type \"\(type)\"")
        }
    }

    // MARK: - Coordinate decoding

    private static func position(_ raw: Any?) throws -> CoordinateConversions.LatLon {
        guard let pair = raw as? [Any], pair.count >= 2,
              let lon = number(pair[0]), let lat = number(pair[1]) else {
            throw BoundaryImportError.malformed("Position is not a [lon, lat] pair")
        }
        return CoordinateConversions.LatLon(latitude: lat, longitude: lon)
    }

    private static func positionList(_ raw: Any?) throws -> [CoordinateConversions.LatLon] {
        guard let list = raw as? [Any] else {
            throw BoundaryImportError.malformed("Coordinate list malformed")
        }
        return try list.map { try position($0) }
    }

    /// A Polygon's `coordinates` — an array of rings.
    private static func ringList(_ raw: Any?) throws -> [[CoordinateConversions.LatLon]] {
        guard let list = raw as? [Any] else {
            throw BoundaryImportError.malformed("Ring list malformed")
        }
        return try list.map { try positionList($0) }
    }

    private static func closeRings(_ rings: [[CoordinateConversions.LatLon]])
        -> [[CoordinateConversions.LatLon]] {
        rings.compactMap { ring in
            guard var r = Optional(ring), let first = r.first, r.count >= 3 else { return nil }
            if r.last != first { r.append(first) }
            return r
        }
    }

    private static func number(_ any: Any) -> Double? {
        if let d = any as? Double { return d }
        if let n = any as? NSNumber { return n.doubleValue }
        if let s = any as? String { return Double(s) }
        return nil
    }
}

// MARK: - The legacy `crs` member

/// The GeoJSON 2008 §3 `crs` member. RFC 7946 §4 deleted it and declared
/// every GeoJSON to be WGS84 lon/lat, but deleting a member from a spec
/// does not delete it from the files already on disk, and a Korean
/// export carrying
///
///     "crs": {"type":"name","properties":{"name":"urn:ogc:def:crs:EPSG::4737"}}
///
/// is degrees, inside |lon| ≤ 180 / |lat| ≤ 90, and a few hundred metres
/// from where the cruiser is standing. Ignoring the member is what let it
/// through; reading it is the only way the file names itself.
///
/// Absence keeps the RFC 7946 assumption — that is the overwhelmingly
/// common case and refusing it would refuse almost every valid GeoJSON.
/// Presence must be one of the WGS84 spellings or it is refused, INCLUDING
/// a `crs` object we cannot read a name out of: "present but unreadable"
/// is not a confirmation.
///
/// And presence means ANYWHERE. GeoJSON 2008 §3 permitted the member on
/// EVERY GeoJSON object, not only on the outermost one, and older QGIS
/// and ArcGIS exports put it on the Feature. Reading the root alone
/// accepted a document whose Feature declared EPSG:5186 while refusing
/// the byte-identical document with the same member moved to the root —
/// the accepted one drawn a few hundred metres off in silence.
public enum GeoJSONCRS {

    /// The WHOLE document's verdict: nil when every level of it may be
    /// treated as WGS84 lon/lat, otherwise the text to quote back at the
    /// cruiser for the first declaration that is not.
    ///
    /// Walks the root, every Feature in a FeatureCollection, every
    /// Feature's geometry, and every member of a GeometryCollection
    /// however deeply those nest. One non-WGS84 declaration anywhere
    /// refuses the file.
    ///
    /// The walk follows the GeoJSON object graph ONLY — `features`,
    /// `geometry`, `geometries`. `properties` is deliberately not
    /// followed: a stand attribute that happens to be called "crs" is
    /// the cruiser's own data, not a declaration about the coordinates.
    /// Iterative rather than recursive so a pathologically nested
    /// document costs heap instead of stack.
    public static func rejectionAnywhere(_ root: [String: Any]) -> String? {
        var stack: [[String: Any]] = [root]
        while let node = stack.popLast() {
            if let found = rejection(node) { return found }
            var children: [[String: Any]] = []
            if let features = node["features"] as? [Any] {
                children += features.compactMap { $0 as? [String: Any] }
            }
            if let geometry = node["geometry"] as? [String: Any] {
                children.append(geometry)
            }
            if let geometries = node["geometries"] as? [Any] {
                children += geometries.compactMap { $0 as? [String: Any] }
            }
            // Pushed reversed so the stack pops them in document order:
            // when more than one object declares a CRS, the refusal
            // names the first one the cruiser would find by eye.
            stack.append(contentsOf: children.reversed())
        }
        return nil
    }

    /// One object's own `crs` member — the same allow-list and the same
    /// sentence at every level, because a Feature that declares Korea
    /// 2000 is wrong for exactly the reason a root that declares it is.
    /// Private on purpose: every caller must go through
    /// `rejectionAnywhere`, so no future entry point can re-open the
    /// root-only hole.
    private static func rejection(_ node: [String: Any]) -> String? {
        // `"crs": null` is GeoJSON 2008's own way of saying "no CRS
        // stated", so it reads exactly like an absent member.
        guard let crs = node["crs"], !(crs is NSNull) else { return nil }
        guard let declared = declaredName(crs) else {
            return "an unrecognised GeoJSON crs member"
        }
        let normalised = declared
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .uppercased()
        return wgs84Spellings.contains(normalised)
            ? nil : "GeoJSON crs \"\(declared)\""
    }

    /// Every way of spelling WGS84 lon/lat that a `crs` member uses —
    /// the EPSG short form, both URN forms, and the two CRS84 spellings.
    /// Whole strings, upper-cased: `urn:ogc:def:crs:EPSG::4737` differs
    /// from the accepted URN by three characters and must not match.
    private static let wgs84Spellings: Set<String> = [
        "EPSG:4326",
        "URN:OGC:DEF:CRS:EPSG::4326",
        "URN:OGC:DEF:CRS:OGC:1.3:CRS84",
        "CRS:84",
        "CRS84"
    ]

    /// What the member declares: a named CRS's `name`, a linked CRS's
    /// `href`, or the bare string some writers emit instead of an
    /// object. Nil when the member says nothing we can read.
    private static func declaredName(_ crs: Any) -> String? {
        if let text = crs as? String {
            return text.isEmpty ? nil : text
        }
        guard let object = crs as? [String: Any] else { return nil }
        let properties = (object["properties"] as? [String: Any]) ?? [:]
        if let name = properties["name"] as? String, !name.isEmpty { return name }
        if let href = properties["href"] as? String, !href.isEmpty { return href }
        return nil
    }
}
