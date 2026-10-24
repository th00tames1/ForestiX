import XCTest
import Common

final class IdleResourceCacheTests: XCTestCase {
    private final class Resource {}
    private enum SyntheticError: Error { case failed }
    func testReusesUntilTrimAndDefersTrimAcrossNestedInferenceLeases() {
        let cache = IdleResourceCache<Resource>()
        var creations = 0
        func create() -> Resource { creations += 1; return Resource() }
        weak var retired: Resource?
        cache.withResource(create: create) { first in
            retired = first
            cache.trim()
            cache.withResource(create: create) { second in XCTAssertTrue(first === second) }
            XCTAssertNotNil(retired)
            XCTAssertEqual(creations,1)
        }
        XCTAssertNil(retired)
        cache.withResource(create: create) { _ in }
        XCTAssertEqual(creations,2)
    }
    func testThrowingInferenceReturnsItsLeaseAndThrowingCreationDoesNotPoisonCache() {
        let cache = IdleResourceCache<Resource>()
        XCTAssertThrowsError(try cache.withResource(create: { throw SyntheticError.failed }) { _ in })
        weak var retired: Resource?
        XCTAssertThrowsError(try cache.withResource(create: { Resource() }) { resource in
            retired = resource; cache.trim(); throw SyntheticError.failed
        })
        XCTAssertNil(retired)
        cache.withResource(create: { Resource() }) { XCTAssertNotNil($0) }
    }
    func testConcurrentMemoryTrimCannotRetireRunningInference() {
        let cache = IdleResourceCache<Resource>(),started = DispatchSemaphore(value: 0),finish = DispatchSemaphore(value: 0)
        let done = expectation(description: "inference finished")
        DispatchQueue.global().async {
            cache.withResource(create: { Resource() }) { _ in
                started.signal(); _ = finish.wait(timeout: .now()+5)
            }
            done.fulfill()
        }
        XCTAssertEqual(started.wait(timeout: .now()+5),.success)
        cache.trim()
        cache.withResource(create: { XCTFail("A running model must be reused"); return Resource() }) { _ in }
        finish.signal(); wait(for: [done],timeout: 5)
        var recreated = false
        cache.withResource(create: { recreated = true; return Resource() }) { _ in }
        XCTAssertTrue(recreated)
    }
}
