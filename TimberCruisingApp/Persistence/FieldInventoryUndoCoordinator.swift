import Foundation
import Models

/// Ordered cross-store recovery, not a pretend DB/filesystem transaction.
/// Unrelated edits are refused and reported rather than overwritten.
public final class FieldInventoryUndoCoordinator {
    private static let lock = NSRecursiveLock()
    private let repository: any TreeRepository
    private let archive: FieldStudyArchive
    public init(repository: any TreeRepository,archive: FieldStudyArchive) { self.repository = repository; self.archive = archive }
    public func execute(id: String,saved: Tree,original: Tree?,measurementIds: [String]) throws {
        Self.lock.lock(); defer { Self.lock.unlock() }
        let intent = try archive.prepareInventoryUndo(id: id,saved: saved,original: original,measurementIds: measurementIds)
        try resume(intent)
    }
    public func recover() -> [FieldArchiveIssue] {
        Self.lock.lock(); defer { Self.lock.unlock() }
        let scan = archive.scanInventoryUndoIntents(); var issues = scan.issues
        guard issues.isEmpty else { return issues }
        let pending = scan.values.filter { $0.phase == .prepared || $0.phase == .applicationApplied }
        guard Dictionary(grouping: pending,by: { $0.saved.id }).values.allSatisfy({ $0.count == 1 }) else {
            return [.init(file: "inventory-undos",reason: "duplicate_pending_inventory_undo")]
        }
        for intent in scan.values.sorted(by: { $0.createdAt == $1.createdAt ? $0.id < $1.id : $0.createdAt < $1.createdAt }) where intent.phase != .complete {
            do { try resume(intent) }
            catch { issues.append(.init(file: "inventory-undos/"+intent.id,reason: String(describing: error))) }
        }
        return issues
    }
    private func resume(_ initial: FieldInventoryUndoIntent) throws {
        var intent = initial
        if intent.phase == .complete { return }
        if intent.phase == .refused { throw FieldStudyError.conflict(intent.failureReason ?? "Inventory Undo refused") }
        if intent.phase == .prepared {
            let current = try repository.readCurrent(id: intent.saved.id,includeDeleted: true)
            if current == intent.saved {
                do {
                    if let original = intent.original { _ = try repository.replaceIfUnchanged(original,expected: intent.saved) }
                    else { try repository.deleteIfUnchanged(intent.saved) }
                } catch FieldStudyError.conflict(let reason) {
                    _ = try archive.transitionInventoryUndo(id: intent.id,to: .refused,reason: reason); throw FieldStudyError.conflict(reason)
                }
            } else if current != intent.original {
                let reason = "Inventory row changed; Undo recovery refused"
                _ = try archive.transitionInventoryUndo(id: intent.id,to: .refused,reason: reason)
                throw FieldStudyError.conflict(reason)
            }
            intent = try archive.transitionInventoryUndo(id: intent.id,to: .applicationApplied)
        }
        if !intent.measurementIds.isEmpty {
            _ = try archive.undoApplicationMeasurements(operationId: intent.id,resultRecordId: intent.saved.id.uuidString,measurementIds: intent.measurementIds)
        }
        _ = try archive.transitionInventoryUndo(id: intent.id,to: .complete)
    }
}
