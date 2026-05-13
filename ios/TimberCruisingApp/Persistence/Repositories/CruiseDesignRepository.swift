// Spec §8 + REQ-PRJ-003.

import Foundation
import CoreData
import Models

public protocol CruiseDesignRepository {
    func create(_ d: CruiseDesign) throws -> CruiseDesign
    func read(id: UUID) throws -> CruiseDesign?
    func update(_ d: CruiseDesign) throws -> CruiseDesign
    func delete(id: UUID) throws
    func forProject(_ projectId: UUID) throws -> [CruiseDesign]
}

public final class CoreDataCruiseDesignRepository: CruiseDesignRepository {
    private let stack: CoreDataStack
    public init(stack: CoreDataStack) { self.stack = stack }

    public func create(_ d: CruiseDesign) throws -> CruiseDesign {
        try performWrite(stack: stack) { ctx in
            let e = try insert(CruiseDesignEntity.self, entityName: "CruiseDesignEntity", in: ctx)
            CruiseDesignMapper.apply(d, to: e)
            return d
        }
    }

    public func read(id: UUID) throws -> CruiseDesign? {
        try performRead(stack: stack) { ctx in
            guard let e = try fetchOne(CruiseDesignEntity.self, entityName: "CruiseDesignEntity",
                                       keyPath: "id", value: id as CVarArg, in: ctx)
            else { return nil }
            return try CruiseDesignMapper.toStruct(e)
        }
    }

    public func update(_ d: CruiseDesign) throws -> CruiseDesign {
        try performWrite(stack: stack) { ctx in
            guard let e = try fetchOne(CruiseDesignEntity.self, entityName: "CruiseDesignEntity",
                                       keyPath: "id", value: d.id as CVarArg, in: ctx)
            else { throw CoreDataError.notFound(id: d.id.uuidString) }
            CruiseDesignMapper.apply(d, to: e)
            return d
        }
    }

    public func delete(id: UUID) throws {
        try performWrite(stack: stack) { ctx in
            guard let e = try fetchOne(CruiseDesignEntity.self, entityName: "CruiseDesignEntity",
                                       keyPath: "id", value: id as CVarArg, in: ctx)
            else { throw CoreDataError.notFound(id: id.uuidString) }
            ctx.delete(e)
        }
    }

    public func forProject(_ projectId: UUID) throws -> [CruiseDesign] {
        try performRead(stack: stack) { ctx in
            let pred = NSPredicate(format: "projectId == %@", projectId as CVarArg)
            return try fetchMany(CruiseDesignEntity.self, entityName: "CruiseDesignEntity",
                                 predicate: pred, in: ctx)
                .map { try CruiseDesignMapper.toStruct($0) }
        }
    }
}
