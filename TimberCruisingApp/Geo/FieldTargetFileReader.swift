import Foundation
import CoreFoundation
import Common
import Models

/// Point catalog only. DBF rows are joined by physical SHP record index,
/// before null/deleted rows are filtered (ESRI Shapefile Technical Description).
public enum FieldTargetFileReader {
    public static let maximumBytes = 16 * 1024 * 1024
    public static func read(data: Data, filename: String, encodingOverride: String? = nil) throws -> FieldTargetSource {
        guard data.count <= maximumBytes else { throw FieldStudyError.invalid("target file exceeds 16 MiB") }
        let ext = (filename as NSString).pathExtension.lowercased()
        let layerId = FieldFingerprint.digest(["target-source-v1", data.base64EncodedString()])
        if ext == "geojson" || ext == "json" { return try geoJSON(data, filename: filename, layerId: layerId) }
        guard ext == "zip" else { throw FieldStudyError.invalid("use a SHP ZIP (.shp/.dbf/.prj) or GeoJSON") }
        let all = try ZipReader.entries(in: data)
        guard all.count <= 1000 else { throw FieldStudyError.invalid("ZIP entry count") }
        let files = all.filter { !$0.isDirectory && !$0.name.hasPrefix("__MACOSX/") }
        guard Set(files.map { $0.name.lowercased() }).count == files.count else { throw FieldStudyError.invalid("duplicate ZIP filenames") }
        let shapes = files.filter { $0.pathExtension == "shp" }
        guard shapes.count == 1, let shp = shapes.first else { throw FieldStudyError.invalid("ZIP must contain exactly one point layer") }
        let stem = (shp.name as NSString).deletingPathExtension.lowercased()
        func sidecar(_ ext: String, required: Bool = true) throws -> Data? {
            guard let entry = files.first(where: { $0.name.lowercased() == stem+"."+ext }) else {
                if required { throw FieldStudyError.missing("SHP sidecar .\(ext)") }; return nil
            }
            return try extract(entry)
        }
        func extract(_ entry: ZipEntry) throws -> Data {
            guard entry.uncompressedSize >= 0, entry.uncompressedSize <= maximumBytes,
                  entry.compressedSize >= 0, entry.compressedSize <= maximumBytes
            else { throw FieldStudyError.invalid("ZIP component size") }
            return try ZipReader.extract(entry, from: data)
        }
        let prj = try sidecar("prj")!
        guard let wkt = String(data: prj, encoding: .utf8) ?? String(data: prj, encoding: .isoLatin1),
              case .wgs84 = ShapefileReader.classifyPRJ(wkt)
        else { throw FieldStudyError.invalid("target layer requires geographic WGS 84 (EPSG:4326)") }
        let cpg = try sidecar("cpg", required: false).flatMap { String(data: $0, encoding: .ascii) }
        let rows = try dbf(try sidecar("dbf")!, encoding: encodingOverride ?? cpg)
        let points = try shape(try extract(shp))
        guard points.count == rows.count else { throw FieldStudyError.invalid("SHP/DBF record count mismatch") }
        var features: [FieldTargetSourceFeature] = []; var skipped = 0
        for index in points.indices {
            guard let point = points[index], var attributes = rows[index] else { skipped += 1; continue }
            if let z = point.z { attributes["$geometry_z"] = String(z) }
            features.append(.init(sourceFeatureId: String(index+1), latitude: point.y, longitude: point.x, attributes: attributes))
        }
        guard !features.isEmpty else { throw FieldStudyError.invalid("no active point records") }
        return .init(layerId: layerId, name: filename, features: features, skippedRecords: skipped)
    }
    private static func geoJSON(_ data: Data, filename: String, layerId: String) throws -> FieldTargetSource {
        guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { throw FieldStudyError.invalid("GeoJSON object") }
        if let reason = GeoJSONCRS.rejectionAnywhere(root) { throw FieldStudyError.invalid(reason) }
        let objects: [[String: Any]]
        if root["type"] as? String == "FeatureCollection", let list = root["features"] as? [[String: Any]] { objects = list }
        else if root["type"] as? String == "Feature" { objects = [root] }
        else { throw FieldStudyError.invalid("GeoJSON FeatureCollection or Feature required") }
        guard objects.count <= 10000 else { throw FieldStudyError.invalid("target count") }
        var features: [FieldTargetSourceFeature] = []; var skipped = 0
        for (index, object) in objects.enumerated() {
            guard object["type"] as? String == "Feature" else { throw FieldStudyError.invalid("GeoJSON feature type") }
            if object["geometry"] is NSNull { skipped += 1; continue }
            guard let geometry = object["geometry"] as? [String: Any], geometry["type"] as? String == "Point",
                  let coordinates = geometry["coordinates"] as? [NSNumber], (2...3).contains(coordinates.count),
                  coordinates.allSatisfy({ CFGetTypeID($0) != CFBooleanGetTypeID() && $0.doubleValue.isFinite })
            else { throw FieldStudyError.invalid("point geometry required at feature \(index+1)") }
            var attributes: [String: String] = [:]
            if let props = object["properties"] as? [String: Any] {
                guard props.count <= 100 else { throw FieldStudyError.invalid("attribute count") }
                for (key,value) in props {
                    guard !key.hasPrefix("$"), key.utf8.count <= 200 else { throw FieldStudyError.invalid("reserved or long attribute name") }
                    if value is NSNull { continue }
                    if let s = scalar(value) { guard s.utf8.count <= 4096 else { throw FieldStudyError.invalid("attribute length") }; attributes[key] = s }
                    else { throw FieldStudyError.invalid("target properties must be scalar: \(key)") }
                }
            } else if !(object["properties"] is NSNull) && object["properties"] != nil { throw FieldStudyError.invalid("GeoJSON properties") }
            if coordinates.count == 3 { attributes["$geometry_z"] = coordinates[2].stringValue }
            let featureId = object["id"].flatMap(scalar) ?? String(index+1)
            let point = FieldTargetSourceFeature(sourceFeatureId: featureId, latitude: coordinates[1].doubleValue,
                longitude: coordinates[0].doubleValue, attributes: attributes)
            try validatePoint(point.longitude,point.latitude); features.append(point)
        }
        guard !features.isEmpty else { throw FieldStudyError.invalid("no point records") }
        return .init(layerId: layerId, name: filename, features: features, skippedRecords: skipped)
    }
    private static func scalar(_ value: Any) -> String? {
        if let n = value as? NSNumber { return CFGetTypeID(n) == CFBooleanGetTypeID() ? (n.boolValue ? "true" : "false") : n.stringValue }
        return value as? String
    }
    private struct Point { var x: Double; var y: Double; var z: Double? }
    private static func shape(_ data: Data) throws -> [Point?] {
        let b = [UInt8](data)
        func u32(_ o: Int, big: Bool = false) -> UInt32 {
            (0..<4).reduce(0) { $0 | UInt32(b[o+$1]) << (8*(big ? 3-$1 : $1)) }
        }
        func double(_ o: Int) -> Double { Double(bitPattern: (0..<8).reduce(UInt64(0)) { $0 | UInt64(b[o+$1]) << (8*$1) }) }
        guard b.count >= 100, u32(0,big: true) == 9994, u32(28) == 1000,
              Int(u32(24,big: true))*2 == b.count, [1,11,21].contains(u32(32))
        else { throw FieldStudyError.invalid("point SHP header") }
        let fileType = u32(32); var cursor = 100; var result: [Point?] = []
        while cursor < b.count {
            guard result.count < 10000, cursor+8 <= b.count,
                  u32(cursor,big: true) == UInt32(result.count+1) else { throw FieldStudyError.invalid("SHP record sequence") }
            let length = Int(u32(cursor+4,big: true))*2; cursor += 8
            guard length >= 4, length <= b.count-cursor else { throw FieldStudyError.invalid("SHP record length") }
            let type = u32(cursor)
            if type == 0 {
                guard length == 4 else { throw FieldStudyError.invalid("null SHP record length") }; result.append(nil)
            } else {
                guard type == fileType, length == (type == 1 ? 20 : type == 21 ? 28 : 36) || type == 11 && length == 28
                else { throw FieldStudyError.invalid("mixed or nonpoint SHP records") }
                let x = double(cursor+4), y = double(cursor+12); try validatePoint(x,y)
                let z = type == 11 ? double(cursor+20) : nil
                guard z.map(\.isFinite) ?? true else { throw FieldStudyError.invalid("SHP Z coordinate") }
                result.append(Point(x: x,y: y,z: z))
            }; cursor += length
        }; return result
    }
    private static func validatePoint(_ x: Double, _ y: Double) throws {
        guard x.isFinite, y.isFinite, (-180...180).contains(x), (-90...90).contains(y)
        else { throw FieldStudyError.invalid("WGS 84 point coordinates") }
    }
    private static func dbf(_ data: Data, encoding: String?) throws -> [[String: String]?] {
        let b = [UInt8](data)
        func u16(_ o: Int) -> Int { Int(b[o]) | Int(b[o+1]) << 8 }
        guard b.count >= 33, [0x03,0x83].contains(b[0]) else { throw FieldStudyError.invalid("dBASE III table required") }
        let count = (0..<4).reduce(0) { $0 | Int(b[4+$1]) << (8*$1) }, header = u16(8), record = u16(10)
        guard count <= 10000, header >= 33, header <= b.count, (header-33)%32 == 0,
              record > 0, count <= (b.count-header)/record, b[header-1] == 0x0d
        else { throw FieldStudyError.invalid("DBF header or record count") }
        let codec: String.Encoding
        switch encoding?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
        case nil, "", "auto":
            codec = .ascii
        case "utf-8", "utf8", "65001": codec = .utf8
        case "1252", "windows-1252", "cp1252": codec = .windowsCP1252
        case "949", "cp949", "ms949": codec = String.Encoding(rawValue: CFStringConvertEncodingToNSStringEncoding(CFStringEncoding(CFStringEncodings.dosKorean.rawValue)))
        default: throw FieldStudyError.invalid("unsupported DBF encoding")
        }
        func decode(_ bytes: ArraySlice<UInt8>) throws -> String {
            guard let text = String(data: Data(bytes), encoding: codec) else { throw FieldStudyError.invalid("DBF text requires a matching .cpg or explicit UTF-8/Windows-1252/CP949 encoding") }
            return text.trimmingCharacters(in: .whitespacesAndNewlines.union(.controlCharacters))
        }
        var fields: [(String,Int)] = []; var size = 1
        for o in stride(from: 32,to: header-1,by: 32) {
            let nameBytes = b[o..<(o+11)].prefix { $0 != 0 }; let name = try decode(nameBytes)
            let width = Int(b[o+16])
            guard !name.isEmpty, !name.hasPrefix("$"), !fields.contains(where: { $0.0 == name }),
                  [67,78,70,76,68].contains(b[o+11]), width > 0, fields.count < 100
            else { throw FieldStudyError.invalid("unsupported or duplicate DBF field") }
            fields.append((name,width)); size += width
        }
        guard size == record else { throw FieldStudyError.invalid("DBF field widths") }
        return try (0..<count).map { index in
            let start = header+index*record
            guard b[start] == 0x20 || b[start] == 0x2a else { throw FieldStudyError.invalid("DBF deletion flag") }
            if b[start] == 0x2a { return nil }
            var values: [String: String] = [:]; var p = start+1
            for (name,width) in fields { values[name] = try decode(b[p..<(p+width)]); p += width }; return values
        }
    }
}
