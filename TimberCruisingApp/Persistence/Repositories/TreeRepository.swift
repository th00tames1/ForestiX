// Spec §8 + REQ-TAL-001..006. Soft-delete (§6.2 Tree.deletedAt) supported:
//   - `delete(id:)` sets `deletedAt = now` without removing the row.
//   - `hardDelete(id:)` removes the row permanently (admin-only).
//   - list/query methods take `includeDeleted` (default false).

import Foundation
import CoreData
import Models

public protocol TreeRepository {
    func create(_ t: Tree) throws -> Tree
    func read(id: UUID, includeDeleted: Bool) throws -> Tree?
    func update(_ t: Tree) throws -> Tree
    /// Soft delete: sets `deletedAt` to the given date (default now).
    func delete(id: UUID, at date: Date) throws
    func hardDelete(id: UUID) throws
    func listByPlot(_ plotId: UUID, includeDeleted: Bool) throws -> [Tree]
    func bySpeciesInProject(_ projectId: UUID, speciesCode: String, includeDeleted: Bool) throws -> [Tree]
}

public extension TreeRepository {
    func read(id: UUID) throws -> Tree? { try read(id: id, includeDeleted: false) }
    func delete(id: UUID) throws { try delete(id: id, at: Date()) }
    func listByPlot(_ plotId: UUID) throws -> [Tree] {
        try listByPlot(plotId, includeDeleted: false)
    }
}

public final class CoreDataTreeRepository: TreeRepository {
    private let stack: CoreDataStack
    public init(stack: CoreDataStack) { self.stack = stack }

    public func create(_ t: Tree) throws -> Tree {
        try performWrite(stack: stack) { ctx in
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
        try performWrite(stack: stack) { ctx in
            guard let e = try fetchOne(TreeEntity.self, entityName: "TreeEntity",
                                       keyPath: "id", value: t.id as CVarArg, in: ctx)
            else { throw CoreDataError.notFound(id: t.id.uuidString) }
            TreeMapper.apply(t, to: e)
            return t
        }
    }

    public func delete(id: UUID, at date: Date) throws {
        try performWrite(stack: stack) { ctx in
            guard let e = try fetchOne(TreeEntity.self, entityName: "TreeEntity",
                                       keyPath: "id", value: id as CVarArg, in: ctx)
            else { throw CoreDataError.notFound(id: id.uuidString) }
            e.deletedAt = date
            e.updatedAt = date
        }
    }

    public func hardDelete(id: UUID) throws {
        try performWrite(stack: stack) { ctx in
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
