// Spec §8 + REQ-PRJ-002 (strata per project).

import Foundation
import CoreData
import Models

public protocol StratumRepository {
    func create(_ s: Stratum) throws -> Stratum
    func read(id: UUID) throws -> Stratum?
    func update(_ s: Stratum) throws -> Stratum
    func delete(id: UUID) throws
    func list() throws -> [Stratum]
    func listByProject(_ projectId: UUID) throws -> [Stratum]
}

public final class CoreDataStratumRepository: StratumRepository {
    private let stack: CoreDataStack
    public init(stack: CoreDataStack) { self.stack = stack }

    public func create(_ s: Stratum) throws -> Stratum {
        try performWrite(stack: stack) { ctx in
            let e = try insert(StratumEntity.self, entityName: "StratumEntity", in: ctx)
            StratumMapper.apply(s, to: e)
            return s
        }
    }

    public func read(id: UUID) throws -> Stratum? {
        try performRead(stack: stack) { ctx in
            guard let e = try fetchOne(StratumEntity.self, entityName: "StratumEntity",
                                       keyPath: "id", value: id as CVarArg, in: ctx)
            else { return nil }
            return StratumMapper.toStruct(e)
        }
    }

    public func update(_ s: Stratum) throws -> Stratum {
        try performWrite(stack: stack) { ctx in
            guard let e = try fetchOne(StratumEntity.self, entityName: "StratumEntity",
                                       keyPath: "id", value: s.id as CVarArg, in: ctx)
            else { throw CoreDataError.notFound(id: s.id.uuidString) }
            StratumMapper.apply(s, to: e)
            return s
        }
    }

    public func delete(id: UUID) throws {
        try performWrite(stack: stack) { ctx in
            guard let e = try fetchOne(StratumEntity.self, entityName: "StratumEntity",
                                       keyPath: "id", value: id as CVarArg, in: ctx)
            else { throw CoreDataError.notFound(id: id.uuidString) }
            ctx.delete(e)
        }
    }

    public func list() throws -> [Stratum] {
        try performRead(stack: stack) { ctx in
            try fetchMany(StratumEntity.self, entityName: "StratumEntity", in: ctx)
                .map(StratumMapper.toStruct)
        }
    }

    public func listByProject(_ projectId: UUID) throws -> [Stratum] {
        try performRead(stack: stack) { ctx in
            let predicate = NSPredicate(format: "projectId == %@", projectId as CVarArg)
            return try fetchMany(StratumEntity.self, entityName: "StratumEntity",
                                 predicate: predicate, in: ctx)
                .map(StratumMapper.toStruct)
        }
    }
}
