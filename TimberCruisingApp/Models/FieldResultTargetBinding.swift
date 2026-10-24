import Foundation

/// Reverse lookup from actual application UUIDs, never from a local tree label.
public enum FieldResultTargetBinding {
    public static func resolve(resultIds: [String],resultStore: String,studyId: String,
                               records: [FieldMeasurementRecord],targets: [FieldStudyTarget]) throws -> FieldStudyTarget? {
        func uuid(_ s: String) throws -> String {
            guard let id = UUID(uuidString: s) else { throw FieldStudyError.invalid("result UUID") }
            return id.uuidString.lowercased()
        }
        let ids = try Set(resultIds.map(uuid))
        guard !ids.isEmpty else { return nil }
        var owners = Set<String>(),foreignStudy = false
        for r in records where r.resultStore == resultStore && r.outcome == .result &&
            (r.selection == .accepted || r.selection == .superseded) {
            guard let rowId = r.resultRecordId,ids.contains(try uuid(rowId)),let owner = r.targetId else { continue }
            if resultStore == "quick_history",try uuid(r.id) != uuid(rowId) {
                throw FieldStudyError.conflict("Quick result UUID does not match its measurement")
            }
            if r.studyId == studyId { owners.insert(owner) } else { foreignStudy = true }
        }
        guard owners.count <= 1 else { throw FieldStudyError.conflict("Application row is linked to different targets") }
        if foreignStudy,!owners.isEmpty { throw FieldStudyError.conflict("Application row spans different studies") }
        guard let owner = owners.first else {
            if foreignStudy { throw FieldStudyError.conflict("Result belongs to another study; select that study before remeasurement") }
            return nil
        }
        guard let target = targets.first(where: { $0.id == owner && $0.studyId == studyId }) else {
            throw FieldStudyError.missing("Original measurement target is not in the active catalog")
        }
        try target.validate(); return target
    }
}

public enum FieldMeasurementReplacementScope {
    public static func matches(_ a: FieldMeasurementRecord,_ b: FieldMeasurementRecord) -> Bool {
        guard a.studyId == b.studyId,a.operatorId == b.operatorId,a.kind == b.kind,a.plannedRepeat == b.plannedRepeat,
              a.configuration.protocolVersion == b.configuration.protocolVersion,a.resultStore == b.resultStore else { return false }
        if a.targetId != nil || b.targetId != nil { return a.targetId == b.targetId }
        // Unassigned targets can only be retired with matching explicit app scope.
        let keys = ["application.mode","application.project_id","application.plot_id","application.tree_number"]
        guard a.runtimeSettings["application.plot_id"].map({ !$0.isEmpty }) == true,
              a.runtimeSettings["application.tree_number"].flatMap(Int.init).map({ $0 > 0 }) == true else { return false }
        return keys.allSatisfy { a.runtimeSettings[$0] == b.runtimeSettings[$0] }
    }
}
