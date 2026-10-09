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
        if dbhCm != t.dbhCm {
            t.dbhCm = dbhCm
            // A diameter typed over on this screen was not produced by any
            // estimator, so the row must stop claiming one produced it — nil
            // is the epoch field's word for "unknown".
            //
            // AND IT MUST SAY IT WAS TYPED, or clearing the epoch does the
            // opposite of protecting the number. `DBHEpochRecompute` skips a
            // row that is already at the current epoch and skips a typed one;
            // clearing only the first re-arms the row, and a hand correction
            // is normally a small nudge — well inside the 2 % window the
            // bundle match uses — so the row matches its own old capture and
            // the replay writes over the tape reading the cruiser came here
            // to enter. A cruiser who overrides the app has said the last
            // word on that stem.
            //
            // Left alone when the value is unchanged, which is every save
            // that edits something else.
            t.dbhEstimatorEpoch = nil
            t.dbhCaptureMode = "typed"
        }
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

    /// Puts the tree back in the tally, and changes NOTHING else.
    ///
    /// The record is re-read first. `tree` is the copy this screen loaded when
    /// it opened, and two rows on the form write straight to the store without
    /// going through here — the plot (a move re-files the tree and may renumber
    /// it) and the measured time (`createdAt` is not on this view model at
    /// all). Writing the held copy back would put a moved tree in its old plot
    /// under its old number and a re-timed one at its old instant, silently:
    /// nothing is marked dirty by an undelete, so Save is not even offered as a
    /// way to correct it. Clearing the flag on the record as it stands is the
    /// only version of this that cannot lose someone's work.
    public func undelete() {
        do {
            guard var fresh = try treeRepo.read(id: tree.id, includeDeleted: true) else {
                // The row is gone from under the screen. Say so rather than
                // re-creating it from the copy in memory, which would resurrect
                // a tree the store no longer has. Worded as the move flow words
                // the same fact (`TreeMoveWords.treeGone`), so a cruiser meets
                // one sentence for one state.
                errorMessage = "Undelete failed: no longer in this device's database"
                return
            }
            fresh.deletedAt = nil
            fresh.updatedAt = Date()
            tree = try treeRepo.update(fresh)
            errorMessage = nil
        } catch {
            errorMessage = "Undelete failed: \(error.localizedDescription)"
        }
    }

    /// Re-read the stored row after a write this screen did not make itself —
    /// the Plot row re-parents the tree through `TreeMover`, which reads and
    /// writes the row in the store, and the time row writes `createdAt`, which
    /// is not on this view model at all. A snapshot taken before either still
    /// names the plot the tree has left (and, when the destination was already
    /// using its number, the number it no longer wears), and `save()` builds
    /// from that snapshot — so without this, saving an unrelated field puts
    /// the tree back where it was.
    ///
    /// THE EDIT MIRRORS ARE LEFT AS THE CRUISER TYPED THEM: a half-finished
    /// species or note is theirs, and their Save still lands — on the row where
    /// it now lives. The exception is the placement pair, which has had no
    /// editor since F8: those mirrors can only hold what the record held when
    /// the screen opened, and `TreeMover` clears them at a move precisely
    /// because they are measured from the centre the tree just left. Re-seeding
    /// them is the difference between saving a cleared placement and
    /// resurrecting a bearing to the wrong plot centre.
    ///
    /// Mirrors Android `TreeDetailViewModel.reload`.
    public func reload() {
        guard let fresh = try? treeRepo.read(id: tree.id, includeDeleted: true) else { return }
        tree = fresh
        bearingFromCenterDeg = fresh.bearingFromCenterDeg
        distanceFromCenterM = fresh.distanceFromCenterM
    }

    /// External reset for the error alert.
    public func clearError() { errorMessage = nil }

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
