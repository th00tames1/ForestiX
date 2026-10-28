import Foundation

/// Trimming is deferred until the last lease completes. A model/session can
/// never be destroyed or replaced halfway through inference.
public final class IdleResourceCache<Resource>: @unchecked Sendable {
    private let lock = NSLock()
    private var resource: Resource?
    private var users = 0
    private var trimRequested = false
    public init() {}
    private func acquire(create: () throws -> Resource) rethrows -> Resource {
        lock.lock(); defer { lock.unlock() }
        if resource == nil { resource = try create() }
        users += 1
        return resource!
    }
    public func withResource<Result>(create: () throws -> Resource,
                                     _ operation: (Resource) throws -> Result) rethrows -> Result {
        let value = try acquire(create: create)
        defer { finished() }
        return try operation(value)
    }
    private func finished() {
        var retired: Resource?
        lock.lock(); users -= 1
        if users == 0 && trimRequested {
            retired = resource; resource = nil; trimRequested = false
        }
        lock.unlock()
        withExtendedLifetime(retired) {} // Native destruction outside the lock.
    }
    public func trim() {
        var retired: Resource?
        lock.lock()
        if users == 0 { retired = resource; resource = nil; trimRequested = false }
        else { trimRequested = true }
        lock.unlock()
        withExtendedLifetime(retired) {}
    }
}
