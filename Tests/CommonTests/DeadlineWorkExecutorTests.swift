import XCTest
@testable import Common

final class DeadlineWorkExecutorTests: XCTestCase {
    func testSuccessAndErrorAreUnchanged() async throws {
        let worker = DeadlineWorkExecutor()
        let value = try await worker.run(timeout: 1) { _ in 42 }
        XCTAssertEqual(value, 42)
        do {
            let _: Int = try await worker.run(timeout: 1) { _ in throw CocoaError(.fileReadUnknown) }
            XCTFail("Error was swallowed")
        } catch { XCTAssertTrue(error is CocoaError) }
    }
    func testTimeoutDoesNotWaitForNativeAndDoesNotReleaseItsLease() async throws {
        let worker = DeadlineWorkExecutor(), finish = DispatchSemaphore(value: 0)
        let began = expectation(description: "native began")
        let task = Task { try await worker.run(timeout: 0.04) { _ in
            began.fulfill(); finish.wait(); return 99
        } }
        await fulfillment(of: [began],timeout: 1)
        let started = ProcessInfo.processInfo.systemUptime
        do { _ = try await task.value; XCTFail("Late result escaped") }
        catch { XCTAssertEqual(error as? DeadlineWorkError,.timedOut) }
        XCTAssertLessThan(ProcessInfo.processInfo.systemUptime-started,0.5)
        do { _ = try await worker.run(timeout: 1) { _ in 1 }; XCTFail("Queued another native operation") }
        catch { XCTAssertEqual(error as? DeadlineWorkError,.busy) }
        finish.signal()
        for _ in 0..<200 {
            do { let value = try await worker.run(timeout: 1) { _ in 7 }; XCTAssertEqual(value,7); return }
            catch DeadlineWorkError.busy { try await Task.sleep(for: .milliseconds(2)) }
        }
        XCTFail("Completed native work did not release its lease")
    }
    func testCancellationResumesImmediatelyButKeepsNativeLease() async throws {
        let worker = DeadlineWorkExecutor(), finish = DispatchSemaphore(value: 0)
        let began = expectation(description: "native began")
        let task = Task { try await worker.run(timeout: 10) { _ in began.fulfill(); finish.wait(); return 99 } }
        await fulfillment(of: [began],timeout: 1)
        task.cancel()
        do { _ = try await task.value; XCTFail("Cancelled work published") }
        catch { XCTAssertTrue(error is CancellationError) }
        do { _ = try await worker.run(timeout: 1) { _ in 1 }; XCTFail("Cancelled native work overlapped") }
        catch { XCTAssertEqual(error as? DeadlineWorkError,.busy) }
        finish.signal()
    }
    func testBusyLiveWorkerCanFinishWithoutFailingNormalCapture() async throws {
        let gate = SingleFlightGate(); XCTAssertTrue(gate.acquire())
        DispatchQueue.global().asyncAfter(deadline: .now()+0.025) { gate.release() }
        let value = try await DeadlineWorkExecutor().run(timeout: 1) { work in
            try work.retryBusy { try gate.run { 23 } }
        }
        XCTAssertEqual(value,23)
    }
    func testBusyRetryHonoursDeadline() async throws {
        do {
            _ = try await DeadlineWorkExecutor().run(timeout: 0.03) { work in
                try work.retryBusy { throw DeadlineWorkError.busy } as Int
            }
            XCTFail("Busy loop did not time out")
        } catch { XCTAssertEqual(error as? DeadlineWorkError,.timedOut) }
    }
}
