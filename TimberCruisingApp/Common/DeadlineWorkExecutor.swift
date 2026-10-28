import Foundation

public enum DeadlineWorkError: Error, Equatable {
    case busy, timedOut
}

/// Never queues another native operation behind an unfinished one.
public final class SingleFlightGate: @unchecked Sendable {
    private let lock = NSLock()
    private var occupied = false
    public init() {}
    public func acquire() -> Bool {
        lock.lock(); defer { lock.unlock() }
        guard !occupied else { return false }; occupied = true; return true
    }
    public func release() { lock.lock(); occupied = false; lock.unlock() }
    public func run<T>(_ operation: () throws -> T) throws -> T {
        guard acquire() else { throw DeadlineWorkError.busy }
        defer { release() }; return try operation()
    }
}

/// The UI deadline does not pretend to terminate an uncooperative native call.
/// Its lease remains occupied until that call really returns. Late results
/// cannot resume the caller twice, and retries cannot accumulate workers.
public final class DeadlineWorkExecutor: @unchecked Sendable {
    private let gate = SingleFlightGate()
    public init() {}
    public func run<T: Sendable>(timeout: TimeInterval,
        operation: @escaping @Sendable (DeadlineWorkContext<T>) throws -> T) async throws -> T {
        guard gate.acquire() else { throw DeadlineWorkError.busy }
        let request = DeadlineWorkContext<T>()
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                request.install(continuation)
                let timer = DispatchWorkItem { request.abort(DeadlineWorkError.timedOut) }
                DispatchQueue.global(qos: .utility).asyncAfter(deadline: .now() + max(0.001, timeout), execute: timer)
                Task.detached(priority: .userInitiated) { [gate] in
                    let result = Result { try request.checkCancellation(); return try operation(request) }
                    gate.release()
                    timer.cancel()
                    request.complete(result)
                }
            }
        } onCancel: { request.abort(CancellationError()) }
    }
}

public final class DeadlineWorkContext<T: Sendable>: @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: CheckedContinuation<T, Error>?
    private var pending: Result<T, Error>?
    private var finished = false
    private var cancelled = false
    fileprivate func install(_ value: CheckedContinuation<T, Error>) {
        lock.lock()
        if finished { let result = pending!; pending = nil; lock.unlock(); value.resume(with: result) }
        else { continuation = value; lock.unlock() }
    }
    public func checkCancellation() throws {
        lock.lock(); let stopped = cancelled; lock.unlock()
        if stopped { throw CancellationError() }
    }
    public func retryBusy<R>(_ operation: () throws -> R) throws -> R {
        while true {
            try checkCancellation()
            do { return try operation() }
            catch DeadlineWorkError.busy { Thread.sleep(forTimeInterval: 0.01) }
        }
    }
    fileprivate func abort(_ error: Error) {
        resolve(.failure(error), cancelling: true)
    }
    fileprivate func complete(_ result: Result<T, Error>) { resolve(result, cancelling: false) }
    private func resolve(_ result: Result<T, Error>, cancelling: Bool) {
        lock.lock()
        guard !finished else { lock.unlock(); return }
        finished = true; cancelled = cancelling
        let waiter = continuation; continuation = nil
        if waiter == nil { pending = result }
        lock.unlock(); waiter?.resume(with: result)
    }
}
