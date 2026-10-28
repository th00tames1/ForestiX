// Spec §8 + REQ-TAL-001..006. Soft-delete (§6.2 Tree.deletedAt) supported:
//   - `delete(id:)` sets `deletedAt = now` without removing the row.
//   - `hardDelete(id:)` removes the row permanently (admin-only).
//   - list/query methods take `includeDeleted` (default false).

import Foundation
import CoreData
import Models

public protocol TreeRepository {
    func create(_ t: Tree) throws -> Tree
    func createIfNumberFree(_ t: Tree) throws -> Tree
    func read(id: UUID, includeDeleted: Bool) throws -> Tree?
    func readCurrent(id: UUID) throws -> Tree?
    func readCurrent(id: UUID,includeDeleted: Bool) throws -> Tree?
    func update(_ t: Tree) throws -> Tree
    func replaceIfUnchanged(_ next: Tree,expected: Tree) throws -> Tree
    func deleteIfUnchanged(_ expected: Tree) throws
    /// Soft delete: sets `deletedAt` to the given date (default now).
    func delete(id: UUID, at date: Date) throws
    func hardDelete(id: UUID) throws
    func listByPlot(_ plotId: UUID, includeDeleted: Bool) throws -> [Tree]
    func listCurrentByPlot(_ plotId: UUID,includeDeleted: Bool) throws -> [Tree]
    func bySpeciesInProject(_ projectId: UUID, speciesCode: String, includeDeleted: Bool) throws -> [Tree]

    /// Top-N species codes by live-tree count within a project, ordered by
    /// frequency descending. Used by the AddTreeFlow species quick-tap grid.
    /// Soft-deleted trees are excluded.
    func recentSpeciesCodes(projectId: UUID, limit: Int) throws -> [String]
}

public extension TreeRepository {
    func createIfNumberFree(_ t: Tree) throws -> Tree {
        throw FieldStudyError.conflict("Conditional inventory creation is unavailable")
    }
    func readCurrent(id: UUID) throws -> Tree? { try read(id: id,includeDeleted: false) }
    func readCurrent(id: UUID,includeDeleted: Bool) throws -> Tree? { try read(id: id,includeDeleted: includeDeleted) }
    func listCurrentByPlot(_ plotId: UUID,includeDeleted: Bool) throws -> [Tree] {
        try listByPlot(plotId,includeDeleted: includeDeleted)
    }
    func replaceIfUnchanged(_ next: Tree,expected: Tree) throws -> Tree {
        throw FieldStudyError.conflict("Conditional inventory writes are unavailable")
    }
    func deleteIfUnchanged(_ expected: Tree) throws {
        throw FieldStudyError.conflict("Conditional inventory undo is unavailable")
    }
    func read(id: UUID) throws -> Tree? { try read(id: id, includeDeleted: false) }
    func delete(id: UUID) throws { try delete(id: id, at: Date()) }
    func listByPlot(_ plotId: UUID) throws -> [Tree] {
        try listByPlot(plotId, includeDeleted: false)
    }
}

public final class CoreDataTreeRepository: TreeRepository {
    private static let inventoryWriteLock = NSRecursiveLock()
    private let stack: CoreDataStack
    public init(stack: CoreDataStack) { self.stack = stack }
    private func writeTree<T>(_ body: @escaping (NSManagedObjectContext) throws -> T) throws -> T {
        Self.inventoryWriteLock.lock(); defer { Self.inventoryWriteLock.unlock() }
        return try performWrite(stack: stack,body)
    }

    public func createIfNumberFree(_ t: Tree) throws -> Tree {
        let t = try FieldTreeMeasurementIdentity.prepare(t)
        guard (1...2_147_483_647).contains(t.treeNumber),t.deletedAt == nil else {
            throw FieldStudyError.conflict("Invalid new inventory row")
        }
        return try writeTree { ctx in
            ctx.mergePolicy = NSErrorMergePolicy
            if let current = try fetchOne(TreeEntity.self,entityName: "TreeEntity",keyPath: "id",value: t.id as CVarArg,in: ctx) {
                guard try TreeMapper.toStruct(current) == t else { throw FieldStudyError.conflict("Inventory UUID already exists") }
                return t
            }
            let req = NSFetchRequest<TreeEntity>(entityName: "TreeEntity")
            req.predicate = NSPredicate(format: "plotId == %@ AND treeNumber == %d",t.plotId as CVarArg,t.treeNumber)
            req.fetchLimit = 1
            guard try ctx.fetch(req).isEmpty else { throw FieldStudyError.conflict("Inventory number is no longer free") }
            let e = try insert(TreeEntity.self,entityName: "TreeEntity",in: ctx);TreeMapper.apply(t,to: e)
            return try TreeMapper.toStruct(e)
        }
    }

    public func readCurrent(id: UUID) throws -> Tree? {
        try readCurrent(id: id,includeDeleted: false)
    }
    public func readCurrent(id: UUID,includeDeleted: Bool) throws -> Tree? {
        try performWrite(stack: stack) { ctx in
            guard let e = try fetchOne(TreeEntity.self,entityName: "TreeEntity",keyPath: "id",value: id as CVarArg,in: ctx),includeDeleted || e.deletedAt == nil else { return nil }
            return try TreeMapper.toStruct(e)
        }
    }

    public func create(_ t: Tree) throws -> Tree {
        let t = try FieldTreeMeasurementIdentity.prepare(t)
        return try writeTree { ctx in
            let e = try insert(TreeEntity.self, entityName: "TreeEntity", in: ctx)
            TreeMapper.apply(t, to: e)
            return t
        }
    }

    public func read(id: UUID, includeDeleted: Bool) throws -> Tree? {
        try performRead(stack: stack) { ctx in
            guard let e = try fetchOne(TreeEntity.self, entityName: "TreeEntity",
                                       keyPath: "id", value: id as CVarArg, in: ctx)
            else { return nil }
            if !includeDeleted && e.deletedAt != nil { return nil }
            return try TreeMapper.toStruct(e)
        }
    }

    public func update(_ t: Tree) throws -> Tree {
        try writeTree { ctx in
            guard let e = try fetchOne(TreeEntity.self, entityName: "TreeEntity",
                                       keyPath: "id", value: t.id as CVarArg, in: ctx)
            else { throw CoreDataError.notFound(id: t.id.uuidString) }
            let next = try FieldTreeMeasurementIdentity.prepare(t,previous: TreeMapper.toStruct(e))
            TreeMapper.apply(next, to: e)
            return next
        }
    }

    public func replaceIfUnchanged(_ next: Tree,expected: Tree) throws -> Tree {
        guard next.id == expected.id,next.plotId == expected.plotId,next.treeNumber == expected.treeNumber,
              expected.deletedAt == nil,next.deletedAt == nil else { throw FieldStudyError.conflict("Inventory scope changed") }
        return try writeTree { ctx in
            ctx.mergePolicy = NSErrorMergePolicy
            guard let e = try fetchOne(TreeEntity.self,entityName: "TreeEntity",keyPath: "id",value: expected.id as CVarArg,in: ctx),
                  try TreeMapper.toStruct(e) == expected else { throw FieldStudyError.conflict("Inventory row changed; reopen the target") }
            let value = try FieldTreeMeasurementIdentity.prepare(next,previous: expected)
            TreeMapper.apply(value,to: e);return try TreeMapper.toStruct(e)
        }
    }

    public func deleteIfUnchanged(_ expected: Tree) throws {
        guard expected.deletedAt == nil else { throw FieldStudyError.conflict("Deleted inventory row; undo refused") }
        try writeTree { ctx in
            ctx.mergePolicy = NSErrorMergePolicy
            guard let e = try fetchOne(TreeEntity.self,entityName: "TreeEntity",keyPath: "id",value: expected.id as CVarArg,in: ctx),
                  try TreeMapper.toStruct(e) == expected else { throw FieldStudyError.conflict("Inventory row changed; undo refused") }
            ctx.delete(e)
        }
    }

    public func delete(id: UUID, at date: Date) throws {
        try writeTree { ctx in
            guard let e = try fetchOne(TreeEntity.self, entityName: "TreeEntity",
                                       keyPath: "id", value: id as CVarArg, in: ctx)
            else { throw CoreDataError.notFound(id: id.uuidString) }
            e.deletedAt = date
            e.updatedAt = date
        }
    }

    public func hardDelete(id: UUID) throws {
        try writeTree { ctx in
            guard let e = try fetchOne(TreeEntity.self, entityName: "TreeEntity",
                                       keyPath: "id", value: id as CVarArg, in: ctx)
            else { throw CoreDataError.notFound(id: id.uuidString) }
            ctx.delete(e)
        }
    }

    public func listByPlot(_ plotId: UUID, includeDeleted: Bool) throws -> [Tree] {
        try performRead(stack: stack) { ctx in
            let pred: NSPredicate
            if includeDeleted {
                pred = NSPredicate(format: "plotId == %@", plotId as CVarArg)
            } else {
                pred = NSPredicate(format: "plotId == %@ AND deletedAt == nil", plotId as CVarArg)
            }
            let sort = [NSSortDescriptor(key: "treeNumber", ascending: true)]
            return try fetchMany(TreeEntity.self, entityName: "TreeEntity",
                                 predicate: pred, sort: sort, in: ctx)
                .map { try TreeMapper.toStruct($0) }
        }
    }
    /// An authoritative value snapshot after conditional writes. The view
    /// context may still be waiting to merge background-context notifications.
    public func listCurrentByPlot(_ plotId: UUID,includeDeleted: Bool) throws -> [Tree] {
        try performWrite(stack: stack) { ctx in
            let predicate = includeDeleted ? NSPredicate(format: "plotId == %@",plotId as CVarArg) :
                NSPredicate(format: "plotId == %@ AND deletedAt == nil",plotId as CVarArg)
            return try fetchMany(TreeEntity.self,entityName: "TreeEntity",predicate: predicate,
                sort: [NSSortDescriptor(key: "treeNumber",ascending: true)],in: ctx).map { try TreeMapper.toStruct($0) }
        }
    }

    public func recentSpeciesCodes(projectId: UUID, limit: Int) throws -> [String] {
        guard limit > 0 else { return [] }
        return try performRead(stack: stack) { ctx in
            // First find plot ids for this project, then count trees per
            // species in a single Core Data fetch.
            let plotReq = NSFetchRequest<PlotEntity>(entityName: "PlotEntity")
            plotReq.predicate = NSPredicate(format: "projectId == %@", projectId as CVarArg)
            let plotIds = try ctx.fetch(plotReq).map(\.id)
            guard !plotIds.isEmpty else { return [] }

            let treeReq = NSFetchRequest<TreeEntity>(entityName: "TreeEntity")
            treeReq.predicate = NSPredicate(
                format: "plotId IN %@ AND deletedAt == nil", plotIds as NSArray)
            treeReq.propertiesToFetch = ["speciesCode"]
            let trees = try ctx.fetch(treeReq)

            var counts: [String: Int] = [:]
            for t in trees { counts[t.speciesCode, default: 0] += 1 }
            return counts
                .sorted { lhs, rhs in
                    lhs.value != rhs.value
                        ? lhs.value > rhs.value
                        : lhs.key < rhs.key
                }
                .prefix(limit)
                .map(\.key)
        }
    }

    public func bySpeciesInProject(_ projectId: UUID, speciesCode: String,
                                   includeDeleted: Bool) throws -> [Tree] {
        try performRead(stack: stack) { ctx in
            // Tree.plotId → Plot.projectId. Sub-query via plot IDs.
            let plotReq = NSFetchRequest<PlotEntity>(entityName: "PlotEntity")
            plotReq.predicate = NSPredicate(format: "projectId == %@", projectId as CVarArg)
            let plotIds = try ctx.fetch(plotReq).map(\.id)
            guard !plotIds.isEmpty else { return [] }

            let deletedClause = includeDeleted ? "" : " AND deletedAt == nil"
            let predFmt = "plotId IN %@ AND speciesCode == %@" + deletedClause
            let pred = NSPredicate(format: predFmt, plotIds as NSArray, speciesCode)

            let sort = [NSSortDescriptor(key: "createdAt", ascending: true)]
            return try fetchMany(TreeEntity.self, entityName: "TreeEntity",
                                 predicate: pred, sort: sort, in: ctx)
                .map { try TreeMapper.toStruct($0) }
        }
    }
}
