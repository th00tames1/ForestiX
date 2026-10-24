import Foundation
import CoreFoundation

/// The complete original native row, not a reconstructed sensor measurement.
/// Capture replacement uses it as both a compare-and-swap precondition and
/// durable provenance. Legacy nil/malformed measurement IDs stay unchanged.
public struct FieldQuickReplacementSnapshot: Codable, Equatable, Sendable {
    private enum CodingKeys: String,CodingKey { case result,payloadFormat; case payloadJSON = "payloadJson" }
    public let result: FieldExportApplicationResult
    public let payloadFormat: String
    public let payloadJSON: String
    public init(result: FieldExportApplicationResult,payloadFormat: String,payloadJSON: String) throws {
        self.result = result; self.payloadFormat = payloadFormat; self.payloadJSON = payloadJSON; try validate()
    }
    public func validate() throws {
        guard result.store == "quick_history",UUID(uuidString: result.id) != nil,result.value.isFinite,!result.deleted,
              ["ios.quick_entry_json.v1","android.entry_row_json.v1"].contains(payloadFormat),
              let data = payloadJSON.data(using: .utf8),data.count <= 4*1024*1024,
              let row = try JSONSerialization.jsonObject(with: data) as? [String:Any],
              let id = row["id"] as? String,id.caseInsensitiveCompare(result.id) == .orderedSame,
              row["kind"] as? String == result.kind.rawValue,
              let value = row["value"] as? NSNumber,CFGetTypeID(value) != CFBooleanGetTypeID(),value.doubleValue == result.value,
              row["measurementId"] == nil || row["measurementId"] is NSNull || row["measurementId"] is String,
              (row["measurementId"] as? String) == result.measurementId else {
            throw FieldStudyError.invalid("replacement native snapshot")
        }
    }
}
