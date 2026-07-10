// Spec §8 + REQ-PRJ-004, REQ-NAV-001 (visited vs remaining).

import Foundation
import CoreData
import Models

public protocol PlannedPlotRepository {
    func create(_ p: PlannedPlot) throws -> PlannedPlot
    func read(id: UUID) throws -> PlannedPlot?
    func update(_ p: PlannedPlot) throws -> PlannedPlot
    func delete(id: UUID) throws
    func listByProject(_ projectId: UUID) throws -> [PlannedPlot]
    func listUnvisited(projectId: UUID) throws -> [PlannedPlot]
}

public final class CoreDataPlannedPlotRepository: PlannedPlotRepository {
    private let stack: CoreDataStack
    public init(stack: CoreDataStack) { self.stack = stack }

    public func create(_ p: PlannedPlot) throws -> PlannedPlot {
        try performWrite(stack: stack) { ctx in
            let e = try insert(PlannedPlotEntity.self, entityName: "PlannedPlotEntity", in: ctx)
            PlannedPlotMapper.apply(p, to: e)
            return p
        }
    }

    public func read(id: UUID) throws -> PlannedPlot? {
        try performRead(stack: stack) { ctx in
            guard let e = try fetchOne(PlannedPlotEntity.self, entityName: "PlannedPlotEntity",
                                       keyPath: "id", value: id as CVarArg, in: ctx)
            else { return nil }
            return PlannedPlotMapper.toStruct(e)
        }
    }

    public func update(_ p: PlannedPlot) throws -> PlannedPlot {
        try performWrite(stack: stack) { ctx in
            guard let e = try fetchOne(PlannedPlotEntity.self, entityName: "PlannedPlotEntity",
                                       keyPath: "id", value: p.id as CVarArg, in: ctx)
            else { throw CoreDataError.notFound(id: p.id.uuidString) }
            PlannedPlotMapper.apply(p, to: e)
            return p
        }
    }

    public func delete(id: UUID) throws {
        try performWrite(stack: stack) { ctx in
            guard let e = try fetchOne(PlannedPlotEntity.self, entityName: "PlannedPlotEntity",
                                       keyPath: "id", value: id as CVarArg, in: ctx)
            else { throw CoreDataError.notFound(id: id.uuidString) }
            ctx.delete(e)
        }
    }

    public func listByProject(_ projectId: UUID) throws -> [PlannedPlot] {
        try performRead(stack: stack) { ctx in
            let pred = NSPredicate(format: "projectId == %@", projectId as CVarArg)
            let sort = [NSSortDescriptor(key: "plotNumber", ascending: true)]
            return try fetchMany(PlannedPlotEntity.self, entityName: "PlannedPlotEntity",
                                 predicate: pred, sort: sort, in: ctx)
                .map(PlannedPlotMapper.toStruct)
        }
    }

    public func listUnvisited(projectId: UUID) throws -> [PlannedPlot] {
        try performRead(stack: stack) { ctx in
            let pred = NSPredicate(format: "projectId == %@ AND visited == NO", projectId as CVarArg)
            let sort = [NSSortDescriptor(key: "plotNumber", ascending: true)]
            return try fetchMany(PlannedPlotEntity.self, entityName: "PlannedPlotEntity",
                                 predicate: pred, sort: sort, in: ctx)
                .map(PlannedPlotMapper.toStruct)
        }
    }
}
