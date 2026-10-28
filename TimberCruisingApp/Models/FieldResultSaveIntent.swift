import Foundation

/// Written before the application transaction. It preserves the complete
/// attempt even if its asynchronous lifecycle journal has not reached disk.
public struct FieldResultSaveIntent: Codable, Equatable, Sendable {
    public var schema = 1
    public let id: String
    public let snapshot: FieldMeasurementRecord
    public let expected: FieldExportApplicationResult
    public var completed = false
    public let replacements: [FieldQuickReplacementSnapshot]?
    public let inventoryReplacement: FieldInventoryReplacementSnapshot?
    public var replacementSnapshots: [FieldQuickReplacementSnapshot] { replacements ?? [] }
    public init(snapshot: FieldMeasurementRecord,expected: FieldExportApplicationResult,replacements: [FieldQuickReplacementSnapshot] = [],
                inventoryReplacement: FieldInventoryReplacementSnapshot? = nil) throws {
        id = snapshot.id; self.snapshot = snapshot; self.expected = expected; self.replacements = replacements.isEmpty ? nil : replacements
        self.inventoryReplacement = inventoryReplacement
        try validate()
    }
    public func validate() throws {
        try snapshot.validate()
        guard schema == 1,UUID(uuidString: id) != nil,id == snapshot.id,
              snapshot.outcome == .result,snapshot.selection == .pending,
              snapshot.resultRecordId == nil,snapshot.resultStore == nil,
              expected.measurementId?.caseInsensitiveCompare(id) == .orderedSame,
              UUID(uuidString: expected.id) != nil,["quick_history","cruise"].contains(expected.store),
              expected.kind == snapshot.kind,expected.value == snapshot.liveValue,!expected.deleted else {
            throw FieldStudyError.invalid("prepared application save")
        }
        try replacementSnapshots.forEach { try $0.validate() }
        if let inventoryReplacement {
            try inventoryReplacement.validate()
            guard expected.store == "cruise",inventoryReplacement.results.allSatisfy({
                $0.id.caseInsensitiveCompare(expected.id) == .orderedSame &&
                ($0.kind != expected.kind || $0.measurementId?.caseInsensitiveCompare(id) != .orderedSame)
            }) else { throw FieldStudyError.invalid("inventory replacement result scope") }
        }
        let rows = replacementSnapshots.map { $0.result.id.lowercased() }
        guard rows.count <= 10000,replacementSnapshots.reduce(0,{ $0+$1.payloadJSON.utf8.count }) <= 16*1024*1024,
              Set(rows).count == rows.count,!rows.contains(expected.id.lowercased()),
              replacementSnapshots.isEmpty || expected.store == "quick_history",
              replacementSnapshots.allSatisfy({ $0.result.kind == expected.kind && $0.result.measurementId?.caseInsensitiveCompare(id) != .orderedSame }) else {
            throw FieldStudyError.invalid("replacement result identities")
        }
    }
    public func matches(_ other: Self) -> Bool {
        expected == other.expected && replacementSnapshots == other.replacementSnapshots && inventoryReplacement == other.inventoryReplacement && Self.capture(snapshot) == Self.capture(other.snapshot)
    }
    public static func capture(_ r: FieldMeasurementRecord) -> FieldMeasurementRecord {
        var r = r; r.revision = 0; r.events = []; r.timing = nil; r.uiFlowTiming = nil
        r.selection = .pending; r.resultRecordId = nil; r.resultStore = nil
        r.rawStatus = .notRequested; r.rawBundleId = nil; r.rawFailureReason = nil; r.replayValue = nil
        return r
    }
    public func proves(_ results: [FieldExportApplicationResult]) -> Bool {
        // The reader returns every exact-row candidate. Duplicates, edited
        // values, missing identities and deleted rows are not a success.
        guard results.count == 1,let actual = results.first else { return false }
        return actual.id.caseInsensitiveCompare(expected.id) == .orderedSame && actual.store == expected.store &&
            actual.kind == expected.kind && actual.measurementId?.caseInsensitiveCompare(id) == .orderedSame &&
            actual.value == expected.value && !actual.deleted
    }
    /// A newer native row may legitimately hold a different repetition.
    /// Never hide duplicates, deletion or an edit of THIS measurement behind
    /// an archived value. Identical repeated snapshots are one proof, not rows.
    public static func proofCandidates(expected: FieldExportApplicationResult,native: [FieldExportApplicationResult],
                                       archived: [FieldExportApplicationResult]) -> [FieldExportApplicationResult] {
        if usesNativeProof(expected: expected,native: native) { return native }
        var unique: [FieldExportApplicationResult] = []
        for result in archived where result.store == expected.store && result.kind == expected.kind &&
            result.id.caseInsensitiveCompare(expected.id) == .orderedSame &&
            result.measurementId?.caseInsensitiveCompare(expected.measurementId ?? "") == .orderedSame {
            if !unique.contains(result) { unique.append(result) }
        }
        return unique.isEmpty ? native : unique
    }
    public static func usesNativeProof(expected: FieldExportApplicationResult,native: [FieldExportApplicationResult]) -> Bool {
        native.count > 1 || native.first?.deleted == true || native.first?.measurementId?.caseInsensitiveCompare(expected.measurementId ?? "") == .orderedSame
    }
    public func hasCommittedBinding(_ record: FieldMeasurementRecord) -> Bool {
        record.outcome == .result && (record.selection == .accepted || record.selection == .superseded) &&
        record.resultStore == expected.store && record.resultRecordId?.caseInsensitiveCompare(expected.id) == .orderedSame &&
        record.kind == expected.kind && record.liveValue == expected.value && Self.capture(record) == Self.capture(snapshot)
    }
    public func proof(tree: Tree?) -> [FieldExportApplicationResult] {
        guard let tree else { return [] }
        let value = expected.kind == .dbh ? Double(tree.dbhCm) : tree.heightM.map(Double.init)
        guard let value else { return [] }
        return [.init(id: tree.id.uuidString,store: "cruise",kind: expected.kind,value: value,
            measurementId: expected.kind == .dbh ? tree.dbhMeasurementId : tree.heightMeasurementId,deleted: tree.deletedAt != nil)]
    }
}
