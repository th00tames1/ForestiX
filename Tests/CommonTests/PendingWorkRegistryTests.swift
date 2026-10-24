import XCTest
import Common

final class PendingWorkRegistryTests: XCTestCase {
    @MainActor private final class Published {
        var complete = false
        func finish() { complete = true }
    }
    @MainActor func testMainActorCanWaitWithoutBlockingTheEvidenceJournalCallback() async {
        let registry = PendingWorkRegistry(),published = Published()
        registry.submit { await published.finish() }
        await registry.waitUntilIdle()
        XCTAssertTrue(published.complete)
    }
    private actor Gate {
        private var open = false
        private var waiters: [CheckedContinuation<Void,Never>] = []
        func wait() async {
            if !open { await withCheckedContinuation { waiters.append($0) } }
        }
        func release() { open = true; let pending = waiters; waiters = []; pending.forEach { $0.resume() } }
    }
    private actor Counter {
        var value = 0
        func add() { value += 1 }
    }
    func testWaitIncludesCompletionCallbacksAndWorkSubmittedByThem() async {
        let registry = PendingWorkRegistry(),first = Gate(),second = Gate(),counter = Counter()
        registry.submit {
            await first.wait()
            registry.submit { await second.wait(); await counter.add() }
            await counter.add()
        }
        let drain = Task { await registry.waitUntilIdle() }
        let before = await counter.value; XCTAssertEqual(before,0)
        await first.release(); await second.release(); await drain.value
        let after = await counter.value; XCTAssertEqual(after,2)
        await registry.waitUntilIdle()
    }
    func testCancellingWaiterDoesNotCancelEvidenceWrite() async {
        let registry = PendingWorkRegistry(),gate = Gate(),counter = Counter()
        registry.submit { await gate.wait(); await counter.add() }
        let drain = Task { await registry.waitUntilIdle() }; drain.cancel()
        await gate.release(); await drain.value
        let value = await counter.value; XCTAssertEqual(value,1)
    }
}
