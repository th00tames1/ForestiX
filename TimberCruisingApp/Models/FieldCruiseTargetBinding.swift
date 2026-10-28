import Foundation

public struct FieldInventoryRow: Sendable {
    public let id: String
    public let plotId: String
    public let treeNumber: Int
    public let deleted: Bool
    public init(id: String,plotId: String,treeNumber: Int,deleted: Bool = false) {
        self.id = id; self.plotId = plotId; self.treeNumber = treeNumber; self.deleted = deleted
    }
}
public struct FieldCruiseTargetScope: Equatable, Sendable {
    public let treeNumber: Int
    public let existingRowId: String?
    public let sourceMeasurementIds: [String]
}

/// Inventory rows are not Quick-history readings: DBH and height share one
/// application UUID but have distinct measurement UUIDs. Never join by a label.
public enum FieldCruiseTargetBinding {
    public static func resolve(target: FieldStudyTarget,plotId: String,rows: [FieldInventoryRow],records: [FieldMeasurementRecord]) throws -> FieldCruiseTargetScope {
        try target.validate()
        func uuid(_ s: String) throws -> String {
            guard let id = UUID(uuidString: s) else { throw FieldStudyError.invalid("inventory UUID") }
            return id.uuidString.lowercased()
        }
        let plot = try uuid(plotId)
        var byId: [String:FieldInventoryRow] = [:],plots: [String:String] = [:]
        for r in rows {
            let id = try uuid(r.id)
            guard byId[id] == nil else { throw FieldStudyError.conflict("Duplicate inventory UUID") }
            byId[id] = r; plots[id] = try uuid(r.plotId)
        }
        var matches = Set<String>(),sources = Set<String>(),owners: [String:Set<String>] = [:]
        for record in records where record.resultStore == "cruise" && record.outcome == .result &&
            (record.selection == .accepted || record.selection == .superseded) {
            guard let rowId = record.resultRecordId else { continue }
            let id = try uuid(rowId)
            guard let row = byId[id],plots[id] == plot,!row.deleted,record.studyId == target.studyId else { continue }
            if let owner = record.targetId { owners[id,default: []].insert(owner) }
            if record.targetId == target.id { matches.insert(id); sources.insert(try uuid(record.id)) }
        }
        guard matches.count <= 1 else { throw FieldStudyError.conflict("Target is linked to multiple inventory rows") }
        if let id = matches.first,let row = byId[id] {
            let duplicate = byId.contains { key,other in key != id && plots[key] == plot && !other.deleted && other.treeNumber == row.treeNumber }
            guard owners[id] == Set([target.id]),(1...2_147_483_647).contains(row.treeNumber),!duplicate else {
                throw FieldStudyError.conflict("Inventory row owner or local number is ambiguous")
            }
            return .init(treeNumber: row.treeNumber,existingRowId: id,sourceMeasurementIds: sources.sorted())
        }
        let maximum = byId.filter { plots[$0.key] == plot }.values.map(\.treeNumber).max() ?? 0
        guard maximum < 2_147_483_647 else { throw FieldStudyError.invalid("inventory number capacity") }
        return .init(treeNumber: max(1,maximum+1),existingRowId: nil,sourceMeasurementIds: [])
    }
}
