// Spec §8 (persisted VolumeEquation record) + §7.7.

import Foundation
import CoreData
import Models

public protocol VolumeEquationRepository {
    func create(_ v: VolumeEquation) throws -> VolumeEquation
    func read(id: String) throws -> VolumeEquation?
    func update(_ v: VolumeEquation) throws -> VolumeEquation
    func delete(id: String) throws
    func list() throws -> [VolumeEquation]
}

public final class CoreDataVolumeEquationRepository: VolumeEquationRepository {
    private let stack: CoreDataStack
    public init(stack: CoreDataStack) { self.stack = stack }

    public func create(_ v: VolumeEquation) throws -> VolumeEquation {
        try performWrite(stack: stack) { ctx in
            let e = try insert(VolumeEquationEntity.self, entityName: "VolumeEquationEntity", in: ctx)
            VolumeEquationMapper.apply(v, to: e)
            return v
        }
    }

    public func read(id: String) throws -> VolumeEquation? {
        try performRead(stack: stack) { ctx in
            guard let e = try fetchOne(VolumeEquationEntity.self, entityName: "VolumeEquationEntity",
                                       keyPath: "id", value: id as CVarArg, in: ctx)
            else { return nil }
            return VolumeEquationMapper.toStruct(e)
        }
    }

    public func update(_ v: VolumeEquation) throws -> VolumeEquation {
        try performWrite(stack: stack) { ctx in
            guard let e = try fetchOne(VolumeEquationEntity.self, entityName: "VolumeEquationEntity",
                                       keyPath: "id", value: v.id as CVarArg, in: ctx)
            else { throw CoreDataError.notFound(id: v.id) }
            VolumeEquationMapper.apply(v, to: e)
            return v
        }
    }

    public func delete(id: String) throws {
        try performWrite(stack: stack) { ctx in
            guard let e = try fetchOne(VolumeEquationEntity.self, entityName: "VolumeEquationEntity",
                                       keyPath: "id", value: id as CVarArg, in: ctx)
            else { throw CoreDataError.notFound(id: id) }
            ctx.delete(e)
        }
    }

    public func list() throws -> [VolumeEquation] {
        try performRead(stack: stack) { ctx in
            let sort = [NSSortDescriptor(key: "id", ascending: true)]
            return try fetchMany(VolumeEquationEntity.self, entityName: "VolumeEquationEntity",
                                 sort: sort, in: ctx)
                .map(VolumeEquationMapper.toStruct)
        }
    }
}
