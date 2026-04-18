// Spec §8 + REQ-PRJ-005.

import Foundation
import CoreData
import Models

public protocol SpeciesConfigRepository {
    func create(_ s: SpeciesConfig) throws -> SpeciesConfig
    func read(code: String) throws -> SpeciesConfig?
    func update(_ s: SpeciesConfig) throws -> SpeciesConfig
    func delete(code: String) throws
    func list() throws -> [SpeciesConfig]
}

public final class CoreDataSpeciesConfigRepository: SpeciesConfigRepository {
    private let stack: CoreDataStack
    public init(stack: CoreDataStack) { self.stack = stack }

    public func create(_ s: SpeciesConfig) throws -> SpeciesConfig {
        try performWrite(stack: stack) { ctx in
            let e = try insert(SpeciesConfigEntity.self, entityName: "SpeciesConfigEntity", in: ctx)
            SpeciesConfigMapper.apply(s, to: e)
            return s
        }
    }

    public func read(code: String) throws -> SpeciesConfig? {
        try performRead(stack: stack) { ctx in
            guard let e = try fetchOne(SpeciesConfigEntity.self, entityName: "SpeciesConfigEntity",
                                       keyPath: "code", value: code as CVarArg, in: ctx)
            else { return nil }
            return SpeciesConfigMapper.toStruct(e)
        }
    }

    public func update(_ s: SpeciesConfig) throws -> SpeciesConfig {
        try performWrite(stack: stack) { ctx in
            guard let e = try fetchOne(SpeciesConfigEntity.self, entityName: "SpeciesConfigEntity",
                                       keyPath: "code", value: s.code as CVarArg, in: ctx)
            else { throw CoreDataError.notFound(id: s.code) }
            SpeciesConfigMapper.apply(s, to: e)
            return s
        }
    }

    public func delete(code: String) throws {
        try performWrite(stack: stack) { ctx in
            guard let e = try fetchOne(SpeciesConfigEntity.self, entityName: "SpeciesConfigEntity",
                                       keyPath: "code", value: code as CVarArg, in: ctx)
            else { throw CoreDataError.notFound(id: code) }
            ctx.delete(e)
        }
    }

    public func list() throws -> [SpeciesConfig] {
        try performRead(stack: stack) { ctx in
            let sort = [NSSortDescriptor(key: "code", ascending: true)]
            return try fetchMany(SpeciesConfigEntity.self, entityName: "SpeciesConfigEntity",
                                 sort: sort, in: ctx)
                .map(SpeciesConfigMapper.toStruct)
        }
    }
}
