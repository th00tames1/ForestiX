// Spec §8 + §7.4 (per project, per species). Rolling updates on plot close.

import Foundation
import CoreData
import Models

public protocol HeightDiameterFitRepository {
    func create(_ f: HeightDiameterFit) throws -> HeightDiameterFit
    func read(id: UUID) throws -> HeightDiameterFit?
    func update(_ f: HeightDiameterFit) throws -> HeightDiameterFit
    func delete(id: UUID) throws
    func forProjectAndSpecies(projectId: UUID, speciesCode: String) throws -> HeightDiameterFit?
    func listByProject(_ projectId: UUID) throws -> [HeightDiameterFit]
}

public final class CoreDataHeightDiameterFitRepository: HeightDiameterFitRepository {
    private let stack: CoreDataStack
    public init(stack: CoreDataStack) { self.stack = stack }

    public func create(_ f: HeightDiameterFit) throws -> HeightDiameterFit {
        try performWrite(stack: stack) { ctx in
            let e = try insert(HeightDiameterFitEntity.self,
                               entityName: "HeightDiameterFitEntity", in: ctx)
            HeightDiameterFitMapper.apply(f, to: e)
            return f
        }
    }

    public func read(id: UUID) throws -> HeightDiameterFit? {
        try performRead(stack: stack) { ctx in
            guard let e = try fetchOne(HeightDiameterFitEntity.self,
                                       entityName: "HeightDiameterFitEntity",
                                       keyPath: "id", value: id as CVarArg, in: ctx)
            else { return nil }
            return HeightDiameterFitMapper.toStruct(e)
        }
    }

    public func update(_ f: HeightDiameterFit) throws -> HeightDiameterFit {
        try performWrite(stack: stack) { ctx in
            guard let e = try fetchOne(HeightDiameterFitEntity.self,
                                       entityName: "HeightDiameterFitEntity",
                                       keyPath: "id", value: f.id as CVarArg, in: ctx)
            else { throw CoreDataError.notFound(id: f.id.uuidString) }
            HeightDiameterFitMapper.apply(f, to: e)
            return f
        }
    }

    public func delete(id: UUID) throws {
        try performWrite(stack: stack) { ctx in
            guard let e = try fetchOne(HeightDiameterFitEntity.self,
                                       entityName: "HeightDiameterFitEntity",
                                       keyPath: "id", value: id as CVarArg, in: ctx)
            else { throw CoreDataError.notFound(id: id.uuidString) }
            ctx.delete(e)
        }
    }

    public func forProjectAndSpecies(projectId: UUID, speciesCode: String) throws -> HeightDiameterFit? {
        try performRead(stack: stack) { ctx in
            let pred = NSPredicate(format: "projectId == %@ AND speciesCode == %@",
                                   projectId as CVarArg, speciesCode)
            let req = NSFetchRequest<HeightDiameterFitEntity>(entityName: "HeightDiameterFitEntity")
            req.predicate = pred
            req.sortDescriptors = [NSSortDescriptor(key: "updatedAt", ascending: false)]
            req.fetchLimit = 1
            guard let e = try ctx.fetch(req).first else { return nil }
            return HeightDiameterFitMapper.toStruct(e)
        }
    }

    public func listByProject(_ projectId: UUID) throws -> [HeightDiameterFit] {
        try performRead(stack: stack) { ctx in
            let pred = NSPredicate(format: "projectId == %@", projectId as CVarArg)
            let sort = [NSSortDescriptor(key: "speciesCode", ascending: true)]
            return try fetchMany(HeightDiameterFitEntity.self,
                                 entityName: "HeightDiameterFitEntity",
                                 predicate: pred, sort: sort, in: ctx)
                .map(HeightDiameterFitMapper.toStruct)
        }
    }
}
