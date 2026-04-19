// Phase 5 §5.3 TreeDetailScreen view model. REQ-TAL-006.
//
// Lets the user inspect, edit, soft-delete, or undelete a single tree. Raw
// metadata (DBH sigma, coverage, inliers, height alphas, etc.) is exposed
// read-only so auditors can confirm how the measurement was captured.

import Foundation
import Combine
import Models
import Common
import Persistence

@MainActor
public final class TreeDetailViewModel: ObservableObject {

    // Repo
    private let treeRepo: any TreeRepository

    // Editable state mirrors a subset of Tree fields.
    @Published public private(set) var tree: Tree
    @Published public var speciesCode: String
    @Published public var status: TreeStatus
    @Published public var dbhCm: Float
    @Published public var dbhIsIrregular: Bool
    @Published public var heightM: Float?
    @Published public var crownClass: String?
    @Published public var damageCodes: [String]
    @Published public var notes: String
    @Published public var bearingFromCenterDeg: Float?
    @Published public var distanceFromCenterM: Float?

    @Published public private(set) var isSaving: Bool = false
    @Published public private(set) var errorMessage: String?
    @Published public private(set) var dirty: Bool = false

    public init(tree: Tree, treeRepo: any TreeRepository) {
        self.tree = tree
        self.treeRepo = treeRepo
        self.speciesCode = tree.speciesCode
        self.status = tree.status
        self.dbhCm = tree.dbhCm
        self.dbhIsIrregular = tree.dbhIsIrregular
        self.heightM = tree.heightM
        self.crownClass = tree.crownClass
        self.damageCodes = tree.damageCodes
        self.notes = tree.notes
        self.bearingFromCenterDeg = tree.bearingFromCenterDeg
        self.distanceFromCenterM = tree.distanceFromCenterM
    }

    public var isDeleted: Bool { tree.deletedAt != nil }

    public func markDirty() { dirty = true }

    public func save() {
        guard !isSaving else { return }
        isSaving = true
        defer { isSaving = false }

        var t = tree
        t.speciesCode = speciesCode
        t.status = status
        t.dbhCm = dbhCm
        t.dbhIsIrregular = dbhIsIrregular
        if heightM != t.heightM {
            t.heightM = heightM
            // If a height was entered, mark as measured; otherwise cleared.
            t.heightSource = heightM != nil ? "measured" : nil
        }
        t.crownClass = crownClass
        t.damageCodes = damageCodes
        t.notes = notes
        t.bearingFromCenterDeg = bearingFromCenterDeg
        t.distanceFromCenterM = distanceFromCenterM
        t.updatedAt = Date()

        do {
            tree = try treeRepo.update(t)
            dirty = false
            errorMessage = nil
        } catch {
            errorMessage = "Save failed: \(error.localizedDescription)"
        }
    }

    public func softDelete() {
        do {
            try treeRepo.delete(id: tree.id)
            if let fresh = try treeRepo.read(id: tree.id, includeDeleted: true) {
                tree = fresh
            }
            errorMessage = nil
        } catch {
            errorMessage = "Delete failed: \(error.localizedDescription)"
        }
    }

    public func undelete() {
        do {
            var t = tree
            t.deletedAt = nil
            t.updatedAt = Date()
            tree = try treeRepo.update(t)
            errorMessage = nil
        } catch {
            errorMessage = "Undelete failed: \(error.localizedDescription)"
        }
    }

    // MARK: - Preview

    public static func preview(tree: Tree? = nil) -> TreeDetailViewModel {
        let t = tree ?? Self.sampleTree()
        return TreeDetailViewModel(tree: t, treeRepo: StubDetailRepo())
    }

    private static func sampleTree() -> Tree {
        Tree(
            id: UUID(),
            plotId: UUID(),
            treeNumber: 7,
            speciesCode: "DF",
            status: .live,
            dbhCm: 42.3,
            dbhMethod: .manualCaliper,
            dbhSigmaMm: 3.2,
            dbhRmseMm: 4.1,
            dbhCoverageDeg: 300,
            dbhNInliers: 420,
            dbhConfidence: .green,
            dbhIsIrregular: false,
            heightM: 28.1,
            heightMethod: .vioWalkoffTangent,
            heightSource: "measured",
            heightSigmaM: 0.4,
            heightDHM: 5.2,
            heightAlphaTopDeg: 42.5,
            heightAlphaBaseDeg: -8.3,
            heightConfidence: .green,
            bearingFromCenterDeg: 112,
            distanceFromCenterM: 4.3,
            boundaryCall: nil,
            crownClass: "dominant",
            damageCodes: [],
            isMultistem: false,
            parentTreeId: nil,
            notes: "",
            photoPath: nil,
            rawScanPath: nil,
            createdAt: Date(),
            updatedAt: Date(),
            deletedAt: nil)
    }
}

private final class StubDetailRepo: TreeRepository {
    func create(_ t: Tree) throws -> Tree { t }
    func read(id: UUID, includeDeleted: Bool) throws -> Tree? { nil }
    func update(_ t: Tree) throws -> Tree { t }
    func delete(id: UUID, at date: Date) throws {}
    func hardDelete(id: UUID) throws {}
    func listByPlot(_ plotId: UUID, includeDeleted: Bool) throws -> [Tree] { [] }
    func bySpeciesInProject(_ projectId: UUID, speciesCode: String, includeDeleted: Bool) throws -> [Tree] { [] }
    func recentSpeciesCodes(projectId: UUID, limit: Int) throws -> [String] { [] }
}
