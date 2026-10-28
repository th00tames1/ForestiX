import XCTest
import Common

/// Native ARM scheduling check. No camera inputs, app records, settings or
/// rotation APIs are used; the blocked worker models uncooperative native work.
final class DeadlineWorkDeviceTests: XCTestCase {
    func testDeadlineRecoversBeforeNativeReturnsAndBlocksOverlap() async throws {
        let worker = DeadlineWorkExecutor(), finish = DispatchSemaphore(value: 0)
        defer { finish.signal() }
        let began = expectation(description: "native-like worker began")
        let pending = Task { try await worker.run(timeout: 0.1) { _ in
            began.fulfill(); finish.wait(); return 99
        } }
        await fulfillment(of: [began],timeout: 2)
        let start = ProcessInfo.processInfo.systemUptime
        do { _ = try await pending.value; XCTFail("Late result escaped") }
        catch { XCTAssertEqual(error as? DeadlineWorkError,.timedOut) }
        XCTAssertLessThan(ProcessInfo.processInfo.systemUptime-start,1)
        do { _ = try await worker.run(timeout: 1) { _ in 1 }; XCTFail("Native workers overlapped") }
        catch { XCTAssertEqual(error as? DeadlineWorkError,.busy) }
    }
}
