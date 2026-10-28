import Foundation

public enum FieldInventoryUndoPhase: String, Codable, Sendable {
    case prepared, applicationApplied, complete, refused
}

/// Persisted BEFORE the inventory CAS. Native tree encoding is versioned;
/// it is not a cross-platform inventory import format.
public struct FieldInventoryUndoIntent: Codable, Equatable, Sendable {
    private enum CodingKeys: String,CodingKey {
        case schema,id,saved,original,phase
        case treeSnapshotFormat = "tree_snapshot_format",createdAt = "created_at",measurementIds = "measurement_ids",failureReason = "failure_reason"
    }
    public let schema: Int
    public let treeSnapshotFormat: String
    public let id: String
    public let createdAt: String
    public let saved: Tree
    public let original: Tree?
    public let measurementIds: [String]
    public var phase: FieldInventoryUndoPhase
    public var failureReason: String?

    public init(id: String,saved: Tree,original: Tree?,measurementIds: [String]) throws {
        schema = 1; treeSnapshotFormat = "swift-codable-tree-v1"
        self.id = id; createdAt = FieldStudyJSON.timestamp(); self.saved = saved; self.original = original
        self.measurementIds = measurementIds; phase = .prepared; failureReason = nil
        try validate()
    }
    public var unresolvedMeasurementIds: Set<String> {
        phase == .complete ? [] : Set(measurementIds.map { $0.lowercased() })
    }
    public func validate() throws {
        guard schema == 1,treeSnapshotFormat == "swift-codable-tree-v1",UUID(uuidString: id) != nil,!createdAt.isEmpty,
              saved.deletedAt == nil,(1...2_147_483_647).contains(saved.treeNumber),
              measurementIds.allSatisfy({ UUID(uuidString: $0) != nil }),
              Set(measurementIds.map { $0.lowercased() }).count == measurementIds.count,
              original.map({ $0.id == saved.id && $0.plotId == saved.plotId && $0.treeNumber == saved.treeNumber && $0.deletedAt == nil }) ?? true,
              phase != .refused || failureReason?.isEmpty == false else { throw FieldStudyError.invalid("Inventory Undo intent") }
        for tree in [saved]+(original.map { [$0] } ?? []) {
            let floats: [Float?] = [tree.dbhCm,tree.dbhSigmaMm,tree.dbhRmseMm,tree.dbhCoverageDeg,tree.heightM,tree.heightSigmaM,
                tree.heightDHM,tree.heightAlphaTopDeg,tree.heightAlphaBaseDeg,tree.bearingFromCenterDeg,tree.distanceFromCenterM]
            guard floats.compactMap({ $0 }).allSatisfy(\.isFinite),
                  [tree.latitude,tree.longitude].compactMap({ $0 }).allSatisfy(\.isFinite),
                  tree.createdAt.timeIntervalSinceReferenceDate.isFinite,tree.updatedAt.timeIntervalSinceReferenceDate.isFinite else {
                throw FieldStudyError.invalid("Inventory Undo snapshot")
            }
        }
    }
    public func matches(id: String,saved: Tree,original: Tree?,measurementIds: [String]) -> Bool {
        self.id.caseInsensitiveCompare(id) == .orderedSame && self.saved == saved && self.original == original &&
            Set(self.measurementIds.map { $0.lowercased() }) == Set(measurementIds.map { $0.lowercased() })
    }
    /// No key-conversion strategy: Tree's acronym-bearing keys (heightDHM,
    /// parentTreeId, etc.) must round-trip without lossy automatic recasing.
    public func encoded() throws -> Data {
        try validate(); let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
        return try encoder.encode(self)
    }
    public static func decode(_ data: Data) throws -> Self {
        let value = try JSONDecoder().decode(Self.self,from: data); try value.validate(); return value
    }
}
