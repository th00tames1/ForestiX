// Spec §8 (Persistence/Repositories). Internal helpers used by every
// repository to keep the implementations short and uniform.

import Foundation
import CoreData

/// Fetch a single entity by a key attribute equality. Returns nil if not
/// found; throws if Core Data errors.
func fetchOne<T: NSManagedObject>(
    _ type: T.Type,
    entityName: String,
    keyPath: String,
    value: CVarArg,
    in ctx: NSManagedObjectContext
) throws -> T? {
    let req = NSFetchRequest<T>(entityName: entityName)
    req.predicate = NSPredicate(format: "%K == %@", keyPath, value)
    req.fetchLimit = 1
    return try ctx.fetch(req).first
}

func fetchMany<T: NSManagedObject>(
    _ type: T.Type,
    entityName: String,
    predicate: NSPredicate? = nil,
    sort: [NSSortDescriptor] = [],
    in ctx: NSManagedObjectContext
) throws -> [T] {
    let req = NSFetchRequest<T>(entityName: entityName)
    req.predicate = predicate
    req.sortDescriptors = sort
    return try ctx.fetch(req)
}

/// Insert a new managed object of the given entity into the context.
func insert<T: NSManagedObject>(
    _ type: T.Type,
    entityName: String,
    in ctx: NSManagedObjectContext
) throws -> T {
    guard let desc = NSEntityDescription.entity(forEntityName: entityName, in: ctx) else {
        throw CoreDataError.entityNotFound(entityName)
    }
    return T(entity: desc, insertInto: ctx)
}

/// Run a write block on a fresh background context and save.
func performWrite<T>(
    stack: CoreDataStack,
    _ block: @escaping (NSManagedObjectContext) throws -> T
) throws -> T {
    let ctx = stack.newBackgroundContext()
    var result: Result<T, Error>!
    ctx.performAndWait {
        do {
            let v = try block(ctx)
            if ctx.hasChanges { try ctx.save() }
            result = .success(v)
        } catch {
            result = .failure(error)
        }
    }
    return try result.get()
}

/// Run a read block on the view context.
func performRead<T>(
    stack: CoreDataStack,
    _ block: @escaping (NSManagedObjectContext) throws -> T
) throws -> T {
    let ctx = stack.viewContext
    var result: Result<T, Error>!
    ctx.performAndWait {
        do {
            result = .success(try block(ctx))
        } catch {
            result = .failure(error)
        }
    }
    return try result.get()
}
