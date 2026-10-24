import XCTest
import Geo
import Models
import Common

/// Synthetic catalog only; no field measurements or surveyed coordinates.
final class FieldTargetImportTests: XCTestCase {
    private let wgs = "GEOGCS[\"GCS_WGS_1984\",DATUM[\"D_WGS_1984\",SPHEROID[\"WGS_1984\",6378137,298.257223563]],PRIMEM[\"Greenwich\",0],UNIT[\"Degree\",0.0174532925199433]]"
    private func fixture(cpg: Bool = true, badCount: Bool = false) -> Data {
        func le(_ n: UInt32) -> [UInt8] { (0..<4).map { UInt8(truncatingIfNeeded: n >> (8*$0)) } }
        func be(_ n: UInt32) -> [UInt8] { le(n).reversed() }
        func number(_ x: Double) -> [UInt8] { (0..<8).map { UInt8(truncatingIfNeeded: x.bitPattern >> (8*$0)) } }
        var shp = [UInt8](repeating: 0,count: 100); shp.replaceSubrange(0..<4,with: be(9994))
        shp.replaceSubrange(28..<32,with: le(1000)); shp.replaceSubrange(32..<36,with: le(1))
        for i in 0..<4 {
            let body = i == 1 ? le(0) : le(1)+number(-123+Double(i)/10000)+number(45)
            shp += be(UInt32(i+1))+be(UInt32(body.count/2))+body
        }; shp.replaceSubrange(24..<28,with: be(UInt32(shp.count/2)))
        let fields: [(String,UInt8,Int)] = [("TREE_ID",67,8),("GT",78,8),("NAME",67,20)]
        let header = 33+fields.count*32, record = 37
        var dbf = [UInt8](repeating: 0,count: header); dbf[0] = 3
        dbf.replaceSubrange(4..<8,with: le(badCount ? 3 : 4)); dbf[8] = UInt8(header); dbf[10] = UInt8(record)
        for (i,field) in fields.enumerated() {
            let p = 32+i*32; dbf.replaceSubrange(p..<(p+field.0.utf8.count),with: field.0.utf8)
            dbf[p+11] = field.1; dbf[p+16] = UInt8(field.2)
        }; dbf[header-1] = 13
        for i in 0..<4 {
            dbf.append(i == 2 ? 42 : 32)
            for (text,width) in [(String(format: "%03d",i+1),8),(String(20+i),8),("합성\(i+1)",20)] {
                let bytes = Array(text.utf8); dbf += bytes + [UInt8](repeating: 32,count: width-bytes.count)
            }
        }
        var files: [(String,Data)] = [("test/trees.shp",Data(shp)),("test/trees.dbf",Data(dbf)),("test/trees.prj",Data(wgs.utf8))]
        if cpg { files.append(("test/trees.cpg",Data("UTF-8".utf8))) }
        return ZipWriter.storedArchive(files: files)
    }
    func testShapeNullAndDeletedRowsDoNotShiftAttributeJoin() throws {
        let source = try FieldTargetFileReader.read(data: fixture(),filename: "synthetic.zip")
        XCTAssertEqual(source.skippedRecords,2); XCTAssertEqual(source.features.map { $0.attributes["TREE_ID"] },["001","004"])
        XCTAssertEqual(source.features.map(\.sourceFeatureId),["1","4"])
        XCTAssertEqual(source.features.last?.attributes["NAME"],"합성4")
        XCTAssertEqual(source.features.last!.longitude,-122.9997,accuracy: 1e-9)
        var c = FieldStudyConfiguration(); c.studyId = "SYNTHETIC"; c.operatorId = "TEST"
        var mapping = FieldTargetMapping(treeIdField: "TREE_ID"); mapping.referenceFields = ["dbh-gt":"GT"]
        let batch = try FieldTargetMaterializer.make(source: source,mapping: mapping,configuration: c,developerMode: true)
        XCTAssertEqual(batch.references.map(\.inputValue),[20,23]); XCTAssertEqual(batch.targets.map(\.treeId),["001","004"])
        XCTAssertThrowsError(try FieldTargetMaterializer.make(source: source,mapping: mapping,configuration: c,developerMode: false))
    }
    func testEncodingCountCRSAndIntegrityFailExplicitly() throws {
        XCTAssertThrowsError(try FieldTargetFileReader.read(data: fixture(cpg: false),filename: "synthetic.zip"))
        XCTAssertNoThrow(try FieldTargetFileReader.read(data: fixture(cpg: false),filename: "synthetic.zip",encodingOverride: "UTF-8"))
        XCTAssertThrowsError(try FieldTargetFileReader.read(data: fixture(badCount: true),filename: "synthetic.zip"))
        var damaged = fixture(); damaged[60] ^= 1
        XCTAssertThrowsError(try FieldTargetFileReader.read(data: damaged,filename: "synthetic.zip"))
        let noCRS = ZipWriter.storedArchive(files: [("trees.shp",Data()),("trees.dbf",Data())])
        XCTAssertThrowsError(try FieldTargetFileReader.read(data: noCRS,filename: "synthetic.zip"))
    }
    func testGeoJSONMappingUnitsNullsDuplicatesAndNoPartialImport() throws {
        let json = #"{"type":"FeatureCollection","features":[{"type":"Feature","id":"f1","properties":{"id":"007","dbh":"10","alt":100},"geometry":{"type":"Point","coordinates":[-123,45]}},{"type":"Feature","properties":{},"geometry":null}]}"#
        let source = try FieldTargetFileReader.read(data: Data(json.utf8),filename: "synthetic.geojson")
        XCTAssertEqual(source.skippedRecords,1)
        var c = FieldStudyConfiguration(); c.studyId = "SYNTHETIC"; c.operatorId = "TEST"
        c.referenceMethods[0].unit = .inch
        var map = FieldTargetMapping(treeIdField: "id"); map.referenceFields = ["dbh-gt":"dbh"]; map.altitudeField = "alt"; map.altitudeUnit = .ft
        let batch = try FieldTargetMaterializer.make(source: source,mapping: map,configuration: c,developerMode: true)
        XCTAssertEqual(batch.targets[0].treeId,"007"); XCTAssertEqual(batch.targets[0].altitudeM!,30.48,accuracy: 1e-9)
        XCTAssertEqual(batch.references[0].baseValue,25.4)
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let archive = FieldStudyArchive(root: root); try archive.saveConfiguration(c,developerMode: true)
        XCTAssertThrowsError(try archive.commitImport(batch,developerMode: false)); XCTAssertEqual(try archive.loadTargets().count,0)
        try archive.commitImport(batch,developerMode: true); try archive.commitImport(batch,developerMode: true)
        XCTAssertEqual(try archive.loadTargets().count,1); XCTAssertEqual(archive.scanReferences().values.count,1)
        let duplicate = try FieldTargetMaterializer.make(source: source,mapping: map,configuration: c,developerMode: true)
        XCTAssertThrowsError(try archive.commitImport(duplicate,developerMode: true))
        XCTAssertThrowsError(try archive.saveTargets([])); XCTAssertEqual(try archive.loadTargets().count,1)
        var replacement = batch.references[0]; replacement.id = UUID().uuidString; replacement.inputValue = 11; replacement.baseValue = 27.94
        replacement.supersedesId = batch.references[0].id
        try archive.appendReference(replacement,developerMode: true); XCTAssertEqual(archive.scanReferences().values.count,2)
        var other = source; other.features += source.features
        XCTAssertThrowsError(try FieldTargetMaterializer.make(source: other,mapping: map,configuration: c,developerMode: true))
    }
    func testUnsafeGeoJSONDoesNotProduceTargets() {
        for input in [
            #"{"type":"Feature","crs":{"type":"name","properties":{"name":"EPSG:3857"}},"geometry":{"type":"Point","coordinates":[0,0]}}"#,
            #"{"type":"Feature","geometry":{"type":"Point","coordinates":[true,0]}}"#,
            #"{"type":"Feature","properties":{"id":{"nested":1}},"geometry":{"type":"Point","coordinates":[0,0]}}"#,
            #"{"type":"Feature","geometry":{"type":"MultiPoint","coordinates":[[0,0]]}}"#,
            #"{"type":"Feature","geometry":{"type":"Point","coordinates":[181,0]}}"#
        ] { XCTAssertThrowsError(try FieldTargetFileReader.read(data: Data(input.utf8),filename: "synthetic.geojson")) }
    }
}
