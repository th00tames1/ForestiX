// Spec §8 + REQ-CTR-005, REQ-AGG-001..003.

import Foundation
import CoreData
import Models

public protocol PlotRepository {
    func create(_ p: Plot) throws -> Plot
    func read(id: UUID) throws -> Plot?
    func update(_ p: Plot) throws -> Plot
    func delete(id: UUID) throws
    func listByProject(_ projectId: UUID) throws -> [Plot]
    func closed(projectId: UUID) throws -> [Plot]
    func byPlotNumber(projectId: UUID, plotNumber: Int) throws -> Plot?
}

public final class CoreDataPlotRepository: PlotRepository {
    private let stack: CoreDataStack
    public init(stack: CoreDataStack) { self.stack = stack }

    /// Refuse a site description the model says cannot exist, BEFORE it
    /// reaches the store. The sheet that types these numbers checks the same
    /// rule and keeps Save off, but the sheet is only today's caller — a
    /// restore, an importer or a future screen writing an aspect of 400° must
    /// hit the same wall, because nothing downstream re-reads the range and a
    /// stored 400° is wrong in every export from then on.
    private func validate(_ p: Plot) throws {
        if let reason = p.siteDescriptionRejection {
            throw CoreDataError.invalidValue(reason)
        }
    }

    public func create(_ p: Plot) throws -> Plot {
        try validate(p)
        return try performWrite(stack: stack) { ctx in
            let e = try insert(PlotEntity.self, entityName: "PlotEntity", in: ctx)
            PlotMapper.apply(p, to: e)
            return p
        }
    }

    public func read(id: UUID) throws -> Plot? {
        try performRead(stack: stack) { ctx in
            guard let e = try fetchOne(PlotEntity.self, entityName: "PlotEntity",
                                       keyPath: "id", value: id as CVarArg, in: ctx)
            else { return nil }
            return try PlotMapper.toStruct(e)
        }
    }

    public func update(_ p: Plot) throws -> Plot {
        try validate(p)
        return try performWrite(stack: stack) { ctx in
            guard let e = try fetchOne(PlotEntity.self, entityName: "PlotEntity",
                                       keyPath: "id", value: p.id as CVarArg, in: ctx)
            else { throw CoreDataError.notFound(id: p.id.uuidString) }
            PlotMapper.apply(p, to: e)
            return p
        }
    }

    public func delete(id: UUID) throws {
        try performWrite(stack: stack) { ctx in
            guard let e = try fetchOne(PlotEntity.self, entityName: "PlotEntity",
                                       keyPath: "id", value: id as CVarArg, in: ctx)
            else { throw CoreDataError.notFound(id: id.uuidString) }
            ctx.delete(e)
        }
    }

    public func listByProject(_ projectId: UUID) throws -> [Plot] {
        try performRead(stack: stack) { ctx in
            let pred = NSPredicate(format: "projectId == %@", projectId as CVarArg)
            let sort = [NSSortDescriptor(key: "plotNumber", ascending: true)]
            return try fetchMany(PlotEntity.self, entityName: "PlotEntity",
                                 predicate: pred, sort: sort, in: ctx)
                .map { try PlotMapper.toStruct($0) }
        }
    }

    public func closed(projectId: UUID) throws -> [Plot] {
        try performRead(stack: stack) { ctx in
            let pred = NSPredicate(format: "projectId == %@ AND closedAt != nil", projectId as CVarArg)
            let sort = [NSSortDescriptor(key: "plotNumber", ascending: true)]
            return try fetchMany(PlotEntity.self, entityName: "PlotEntity",
                                 predicate: pred, sort: sort, in: ctx)
                .map { try PlotMapper.toStruct($0) }
        }
    }

    public func byPlotNumber(projectId: UUID, plotNumber: Int) throws -> Plot? {
        try performRead(stack: stack) { ctx in
            let pred = NSPredicate(format: "projectId == %@ AND plotNumber == %d",
                                   projectId as CVarArg, plotNumber)
            let req = NSFetchRequest<PlotEntity>(entityName: "PlotEntity")
            req.predicate = pred
            req.fetchLimit = 1
            guard let e = try ctx.fetch(req).first else { return nil }
            return try PlotMapper.toStruct(e)
        }
    }
}
