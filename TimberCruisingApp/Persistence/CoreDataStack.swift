// Spec §8 (Persistence/CoreDataStack.swift) + §11 NFR "WAL on Core Data; save
// after every Tree save".
//
// Sets up the persistent container with WAL journal mode, a background
// context for writes, and a viewContext for reads. Loads the compiled
// `TimberCruising.momd` bundled by SPM via `.process()`.

import Foundation
import CoreData

public final class CoreDataStack {

    public enum Configuration {
        /// Persistent SQLite store at the given URL.
        case sqlite(url: URL)
        /// In-memory store (for tests).
        case inMemory
    }

    public let container: NSPersistentContainer

    /// Main-queue context for reads. Writes should go through
    /// `performBackgroundTask` or `newBackgroundContext`.
    public var viewContext: NSManagedObjectContext { container.viewContext }

    public convenience init() throws {
        try self.init(configuration: .sqlite(url: CoreDataStack.defaultStoreURL()))
    }

    public init(configuration: Configuration,
                model: NSManagedObjectModel? = nil) throws {
        let resolvedModel: NSManagedObjectModel
        if let injected = model {
            resolvedModel = injected
        } else if let loaded = Self.loadModel() {
            resolvedModel = loaded
        } else {
            throw CoreDataError.modelNotFound
        }
        let container = NSPersistentContainer(name: "TimberCruising",
                                              managedObjectModel: resolvedModel)

        let description = NSPersistentStoreDescription()
        switch configuration {
        case .sqlite(let url):
            description.type = NSSQLiteStoreType
            description.url = url
            // WAL is the default for SQLite stores on iOS/macOS since iOS 10,
            // but we set it explicitly for clarity per §11 NFR.
            description.setOption(["journal_mode": "WAL"] as NSDictionary,
                                  forKey: NSSQLitePragmasOption)
            description.shouldAddStoreAsynchronously = false
            description.shouldMigrateStoreAutomatically = true
            description.shouldInferMappingModelAutomatically = true
        case .inMemory:
            description.type = NSInMemoryStoreType
            description.url = URL(fileURLWithPath: "/dev/null")
            description.shouldAddStoreAsynchronously = false
        }
        container.persistentStoreDescriptions = [description]

        var loadError: Error?
        container.loadPersistentStores { _, err in
            if let err = err { loadError = err }
        }
        if let err = loadError { throw err }

        container.viewContext.automaticallyMergesChangesFromParent = true
        container.viewContext.mergePolicy = NSMergeByPropertyObjectTrumpMergePolicy
        self.container = container
    }

    /// Creates a new background context for writes. Each context has its own
    /// queue; callers must use `perform` / `performAndWait`.
    public func newBackgroundContext() -> NSManagedObjectContext {
        let ctx = container.newBackgroundContext()
        ctx.mergePolicy = NSMergeByPropertyObjectTrumpMergePolicy
        return ctx
    }

    // MARK: - Model loading

    private static func loadModel() -> NSManagedObjectModel? {
        // SPM .process() produces a compiled `.momd` in Bundle.module.
        if let url = Bundle.module.url(forResource: "TimberCruising", withExtension: "momd"),
           let model = NSManagedObjectModel(contentsOf: url) {
            return model
        }
        // Fallback: a single `.mom` file (rare with SPM).
        if let url = Bundle.module.url(forResource: "TimberCruising", withExtension: "mom"),
           let model = NSManagedObjectModel(contentsOf: url) {
            return model
        }
        return nil
    }

    // MARK: - Default store URL

    public static func defaultStoreURL() -> URL {
        let fm = FileManager.default
        let base = (try? fm.url(for: .applicationSupportDirectory,
                                in: .userDomainMask,
                                appropriateFor: nil,
                                create: true))
            ?? fm.temporaryDirectory
        let dir = base.appendingPathComponent("TimberCruising", isDirectory: true)
        try? fm.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("TimberCruising.sqlite")
    }
}

public enum CoreDataError: Error, CustomStringConvertible {
    case modelNotFound
    case entityNotFound(String)
    case mappingFailed(String)
    case notFound(id: String)

    public var description: String {
        switch self {
        case .modelNotFound: return "TimberCruising.momd not found in bundle"
        case .entityNotFound(let e): return "Core Data entity not found: \(e)"
        case .mappingFailed(let m): return "Mapping failed: \(m)"
        case .notFound(let id): return "Record not found: \(id)"
        }
    }
}
