// Spec §8 + REQ-PRJ-001 (CRUD persists across app restarts).

import Foundation
import CoreData
import Models

public protocol ProjectRepository {
    func create(_ project: Project) throws -> Project
    func read(id: UUID) throws -> Project?
    func update(_ project: Project) throws -> Project
    func delete(id: UUID) throws
    func list() throws -> [Project]
}

public final class CoreDataProjectRepository: ProjectRepository {
    private let stack: CoreDataStack
    public init(stack: CoreDataStack) { self.stack = stack }

    public func create(_ project: Project) throws -> Project {
        try performWrite(stack: stack) { ctx in
            let e = try insert(ProjectEntity.self, entityName: "ProjectEntity", in: ctx)
            ProjectMapper.apply(project, to: e)
            return project
        }
    }

    public func read(id: UUID) throws -> Project? {
        try performRead(stack: stack) { ctx in
            guard let e = try fetchOne(ProjectEntity.self, entityName: "ProjectEntity",
                                       keyPath: "id", value: id as CVarArg, in: ctx)
            else { return nil }
            return try ProjectMapper.toStruct(e)
        }
    }

    public func update(_ project: Project) throws -> Project {
        try performWrite(stack: stack) { ctx in
            guard let e = try fetchOne(ProjectEntity.self, entityName: "ProjectEntity",
                                       keyPath: "id", value: project.id as CVarArg, in: ctx)
            else { throw CoreDataError.notFound(id: project.id.uuidString) }
            ProjectMapper.apply(project, to: e)
            return project
        }
    }

    public func delete(id: UUID) throws {
        try performWrite(stack: stack) { ctx in
            guard let e = try fetchOne(ProjectEntity.self, entityName: "ProjectEntity",
                                       keyPath: "id", value: id as CVarArg, in: ctx)
            else { throw CoreDataError.notFound(id: id.uuidString) }
            ctx.delete(e)
        }
    }

    public func list() throws -> [Project] {
        try performRead(stack: stack) { ctx in
            let sort = [NSSortDescriptor(key: "createdAt", ascending: false)]
            let entities = try fetchMany(ProjectEntity.self, entityName: "ProjectEntity",
                                         sort: sort, in: ctx)
            return try entities.map(ProjectMapper.toStruct)
        }
    }
}
