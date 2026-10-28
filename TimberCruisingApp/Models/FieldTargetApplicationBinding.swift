import Foundation

/// Minimal application-row projection. No coordinate/name/time/value matching.
public struct FieldApplicationReading: Sendable {
    public let id: String
    public let kind: FieldStudyKind?
    public let plotId: String?
    public let treeNumber: Int?
    public init(id: String,kind: FieldStudyKind?,plotId: String?,treeNumber: Int?) {
        self.id = id; self.kind = kind; self.plotId = plotId; self.treeNumber = treeNumber
    }
}
public struct FieldTargetLocalScope: Equatable, Sendable {
    public let treeNumber: Int
    public let existing: Bool
    public let sourceResultIds: [String]
}

/// External target identity and the app's sequential local number are different
/// namespaces. Only durable, exact result UUID links may reuse a local number.
public enum FieldTargetApplicationBinding {
    public static func resolve(target: FieldStudyTarget,plotId: String?,readings: [FieldApplicationReading],
                               records: [FieldMeasurementRecord]) throws -> FieldTargetLocalScope {
        try target.validate()
        func uuid(_ value: String) throws -> String {
            guard let id = UUID(uuidString: value) else { throw FieldStudyError.invalid("application UUID") }
            return id.uuidString.lowercased()
        }
        let plot = try plotId.map(uuid)
        var rows: [String:FieldApplicationReading] = [:]
        var rowPlots: [String:String] = [:]
        for row in readings {
            let id = try uuid(row.id)
            guard rows[id] == nil else { throw FieldStudyError.conflict("Duplicate application result UUID") }
            rows[id] = row; rowPlots[id] = try row.plotId.map(uuid) ?? ""
        }
        var owners: [String:String] = [:],numbers = Set<Int>(),sources = Set<String>()
        var links: [(FieldMeasurementRecord,FieldApplicationReading,String)] = []
        for r in records where r.resultStore == "quick_history" && r.outcome == .result &&
            (r.selection == .accepted || r.selection == .superseded) {
            guard let resultId = r.resultRecordId else { continue }
            let id = try uuid(resultId)
            guard let row = rows[id] else { continue } // A deleted application row is not a reusable binding.
            guard id == (try uuid(r.id)),r.kind == row.kind else {
                throw FieldStudyError.conflict("Application result UUID/kind does not match its measurement")
            }
            if let owner = r.targetId {
                if let previous = owners[id],previous != owner { throw FieldStudyError.conflict("Result UUID belongs to multiple targets") }
                owners[id] = owner
            }
            guard rowPlots[id] == (plot ?? "") else { continue }
            links.append((r,row,id))
            guard r.studyId == target.studyId,r.targetId == target.id else { continue }
            guard let number = row.treeNumber,(1...2_147_483_647).contains(number) else {
                throw FieldStudyError.conflict("Linked result has no valid local tree number")
            }
            numbers.insert(number); sources.insert(id)
        }
        guard numbers.count <= 1 else { throw FieldStudyError.conflict("Target is linked to multiple local trees in this plot") }
        if let number = numbers.first {
            guard !links.contains(where: { r,row,_ in r.studyId == target.studyId && r.targetId != nil &&
                r.targetId != target.id && row.treeNumber == number }) else {
                throw FieldStudyError.conflict("Local tree is linked to another target in this study")
            }
            return .init(treeNumber: number,existing: true,sourceResultIds: sources.sorted())
        }
        // Global application counter, including other plots and non-DBH rows.
        let maximum = readings.compactMap(\.treeNumber).max() ?? 0
        guard maximum < 2_147_483_647 else { throw FieldStudyError.invalid("local tree number capacity") }
        return .init(treeNumber: max(1,maximum+1),existing: false,sourceResultIds: [])
    }
}
