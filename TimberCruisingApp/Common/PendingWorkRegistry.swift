import Foundation

/// Retains detached evidence writes until both file I/O and their journal
/// callbacks finish. Waiting never cancels a write or depends on a scan view.
public final class PendingWorkRegistry: @unchecked Sendable {
    public static let rawCapture = PendingWorkRegistry()
    private let lock = NSLock()
    private var tasks: [UUID: Task<Void,Never>] = [:]
    public init() {}

    public func submit(_ operation: @escaping @Sendable () async -> Void) {
        let id = UUID()
        lock.lock()
        tasks[id] = Task.detached(priority: .utility) {
            await operation()
            self.finished(id)
        }
        lock.unlock()
    }
    private func finished(_ id: UUID) {
        lock.lock(); tasks.removeValue(forKey: id); lock.unlock()
    }
    private func snapshot() -> [Task<Void,Never>] {
        lock.lock(); defer { lock.unlock() }; return Array(tasks.values)
    }
    public func waitUntilIdle() async {
        while true {
            let pending = snapshot()
            if pending.isEmpty { return }
            for task in pending { await task.value }
        }
    }
}
