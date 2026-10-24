import Foundation
import CoreFoundation

/// Original native inventory row before a conditional save, including the
/// other measurement kind, surveyed coordinates, photo, notes and provenance.
/// This is evidence, never an instruction to rewrite a historical Tree.
public struct FieldInventoryReplacementSnapshot: Codable, Equatable, Sendable {
    private enum CodingKeys: String,CodingKey { case results,payloadFormat; case payloadJSON = "payloadJson" }
    public let results: [FieldExportApplicationResult]
    public let payloadFormat: String
    public let payloadJSON: String
    public init(results: [FieldExportApplicationResult],payloadFormat: String,payloadJSON: String) throws {
        self.results = results; self.payloadFormat = payloadFormat; self.payloadJSON = payloadJSON; try validate()
    }
    public init(tree: Tree) throws {
        var values = [FieldExportApplicationResult(id: tree.id.uuidString,store: "cruise",kind: .dbh,
            value: Double(tree.dbhCm),measurementId: tree.dbhMeasurementId,deleted: tree.deletedAt != nil)]
        if let height = tree.heightM { values.append(.init(id: tree.id.uuidString,store: "cruise",kind: .height,
            value: Double(height),measurementId: tree.heightMeasurementId,deleted: tree.deletedAt != nil)) }
        let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
        try self.init(results: values,payloadFormat: "ios.tree_json.v1",payloadJSON: String(decoding: encoder.encode(tree),as: UTF8.self))
    }
    public func validate() throws {
        guard (1...2).contains(results.count),Set(results.map(\.kind)).count == results.count,
              results.contains(where: { $0.kind == .dbh }),
              ["ios.tree_json.v1","android.tree_json.v1"].contains(payloadFormat),
              let data = payloadJSON.data(using: .utf8),data.count <= 4*1024*1024,
              let row = try JSONSerialization.jsonObject(with: data) as? [String:Any],
              let id = row["id"] as? String,UUID(uuidString: id) != nil,
              let plot = row["plotId"] as? String,UUID(uuidString: plot) != nil,
              let number = row["treeNumber"] as? NSNumber,CFGetTypeID(number) != CFBooleanGetTypeID(),
              number.doubleValue.rounded() == number.doubleValue,(1...Double(Int32.max)).contains(number.doubleValue),
              row["deletedAt"] == nil || row["deletedAt"] is NSNull else { throw FieldStudyError.invalid("inventory replacement snapshot") }
        let required = ["speciesCode","status","dbhMethod","dbhConfidence","dbhIsIrregular","damageCodes","isMultistem","notes","createdAt","updatedAt"]
        guard required.allSatisfy({ row[$0] != nil }) else { throw FieldStudyError.invalid("incomplete native inventory snapshot") }
        if payloadFormat == "ios.tree_json.v1" { _ = try JSONDecoder().decode(Tree.self,from: data) }
        let hasHeight = row["heightM"] != nil && !(row["heightM"] is NSNull)
        guard hasHeight == results.contains(where: { $0.kind == .height }) else { throw FieldStudyError.invalid("inventory replacement height") }
        for result in results {
            let valueKey = result.kind == .dbh ? "dbhCm" : "heightM",identityKey = result.kind == .dbh ? "dbhMeasurementId" : "heightMeasurementId"
            guard result.store == "cruise",!result.deleted,result.id.caseInsensitiveCompare(id) == .orderedSame,result.value.isFinite,
                  let value = row[valueKey] as? NSNumber,CFGetTypeID(value) != CFBooleanGetTypeID(),
                  value.doubleValue.isFinite,Float(value.doubleValue).isFinite,Double(Float(value.doubleValue)) == result.value,
                  row[identityKey] == nil || row[identityKey] is NSNull || row[identityKey] is String,
                  (row[identityKey] as? String) == result.measurementId else { throw FieldStudyError.invalid("inventory replacement identity/value") }
        }
    }
}
