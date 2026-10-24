import XCTest
import Models
import Persistence

/// Real Core Data transactions, synthetic in-memory inventory only.
final class ConditionalInventoryWriteTests: XCTestCase {
    private func repository() throws -> CoreDataTreeRepository {
        let stack = try CoreDataStack(configuration: .inMemory,model: TestModelLoader.loadTimberCruisingModel())
        return CoreDataTreeRepository(stack: stack)
    }
    private func tree(plot: UUID = UUID(),number: Int = 7,id: UUID = UUID()) -> Tree {
        .init(id: id,plotId: plot,treeNumber: number,treeName: "SYNTHETIC",speciesCode: "DF",status: .live,
              dbhCm: 30,dbhMethod: .manualCaliper,dbhSigmaMm: nil,dbhRmseMm: nil,dbhCoverageDeg: nil,dbhNInliers: nil,
              dbhConfidence: .green,dbhIsIrregular: false,heightM: 21,heightMethod: .manualEntry,heightSource: "measured",
              heightSigmaM: nil,heightDHM: nil,heightAlphaTopDeg: nil,heightAlphaBaseDeg: nil,heightConfidence: .green,
              bearingFromCenterDeg: 25,distanceFromCenterM: 4,boundaryCall: "in",crownClass: "dominant",
              damageCodes: ["OLD"],isMultistem: false,parentTreeId: nil,notes: "old",photoPath: "old.jpg",rawScanPath: nil,
              createdAt: Date(timeIntervalSince1970: 100),updatedAt: Date(timeIntervalSince1970: 101),deletedAt: nil)
    }
    func testConditionalCreationIsIdempotentAndGuardsUUIDAndReservedNumber() throws {
        let repo = try repository(),first = tree()
        let saved = try repo.createIfNumberFree(first)
        XCTAssertEqual(saved,first); XCTAssertEqual(try repo.createIfNumberFree(saved),saved)
        var sameID = first; sameID.notes = "different"
        XCTAssertThrowsError(try repo.createIfNumberFree(sameID))
        XCTAssertThrowsError(try repo.createIfNumberFree(tree(plot: first.plotId)))
        XCTAssertEqual(try repo.readCurrent(id: saved.id),saved)
        try repo.delete(id: saved.id,at: Date(timeIntervalSince1970: 200))
        XCTAssertThrowsError(try repo.createIfNumberFree(tree(plot: first.plotId)))
        XCTAssertNil(try repo.readCurrent(id: saved.id))
        XCTAssertNotNil(try repo.createIfNumberFree(tree())) // another Plot may use #7
    }
    func testInvalidNumberAndNewDeletedRowNeverReachStore() throws {
        let repo = try repository(),plot = UUID()
        for n in [0,-1,2_147_483_648] { XCTAssertThrowsError(try repo.createIfNumberFree(tree(plot: plot,number: n))) }
        var deleted = tree(plot: plot); deleted.deletedAt = Date()
        XCTAssertThrowsError(try repo.createIfNumberFree(deleted))
        XCTAssertTrue(try repo.listByPlot(plot,includeDeleted: true).isEmpty)
    }
    func testConditionalReplacementAndUndoCannotOverwriteIndependentEdit() throws {
        let repo = try repository(),old = try repo.createIfNumberFree(tree())
        var changed = old; changed.dbhCm = 40; changed.updatedAt = Date(timeIntervalSince1970: 200)
        let saved = try repo.replaceIfUnchanged(changed,expected: old)
        XCTAssertEqual(try repo.readCurrent(id: old.id),saved)
        XCTAssertEqual(try repo.listCurrentByPlot(old.plotId,includeDeleted: false),[saved])
        XCTAssertThrowsError(try repo.replaceIfUnchanged(old,expected: old))
        XCTAssertThrowsError(try repo.deleteIfUnchanged(old))
        var independent = saved; independent.notes = "edited elsewhere"
        _ = try repo.update(independent)
        XCTAssertThrowsError(try repo.replaceIfUnchanged(old,expected: saved))
        XCTAssertThrowsError(try repo.deleteIfUnchanged(saved))
        XCTAssertEqual(try repo.readCurrent(id: old.id)?.notes,"edited elsewhere")
    }
    func testExactRestoreAndExactNewRowUndoAreSupported() throws {
        let repo = try repository(),old = try repo.createIfNumberFree(tree())
        var next = old; next.dbhCm = 42; next.photoPath = "new.jpg"
        let saved = try repo.replaceIfUnchanged(next,expected: old)
        let restored = try repo.replaceIfUnchanged(old,expected: saved)
        XCTAssertEqual(restored,old); XCTAssertEqual(try repo.readCurrent(id: old.id),old)
        XCTAssertEqual(try repo.listCurrentByPlot(old.plotId,includeDeleted: true),[old])
        try repo.deleteIfUnchanged(restored)
        XCTAssertNil(try repo.readCurrent(id: old.id))
        XCTAssertThrowsError(try repo.deleteIfUnchanged(restored))
        XCTAssertThrowsError(try repo.replaceIfUnchanged(saved,expected: old))
    }
    func testConditionalWritesCannotMoveRenumberOrResurrectTree() throws {
        let repo = try repository(),old = try repo.createIfNumberFree(tree())
        var moved = old; moved.plotId = UUID()
        var renumbered = old; renumbered.treeNumber += 1
        var deleted = old; deleted.deletedAt = Date(timeIntervalSince1970: 200)
        for next in [moved,renumbered,deleted,tree(plot: old.plotId)] {
            XCTAssertThrowsError(try repo.replaceIfUnchanged(next,expected: old))
        }
        try repo.delete(id: old.id,at: Date(timeIntervalSince1970: 200))
        XCTAssertThrowsError(try repo.replaceIfUnchanged(old,expected: old))
        XCTAssertThrowsError(try repo.deleteIfUnchanged(old))
        let tombstone = try XCTUnwrap(repo.read(id: old.id,includeDeleted: true))
        XCTAssertThrowsError(try repo.replaceIfUnchanged(old,expected: tombstone))
        XCTAssertThrowsError(try repo.deleteIfUnchanged(tombstone))
    }
    func testConcurrentConditionalCreationsReserveNumberOnlyOnce() throws {
        let repo = try repository(),plot = UUID(),a = tree(plot: plot),b = tree(plot: plot)
        let group = DispatchGroup(),lock = NSLock()
        var successes: [Tree] = [],failures = 0
        for candidate in [a,b] {
            group.enter(); DispatchQueue.global().async {
                defer { group.leave() }
                do { let result = try repo.createIfNumberFree(candidate); lock.lock(); successes.append(result); lock.unlock() }
                catch { lock.lock(); failures += 1; lock.unlock() }
            }
        }
        XCTAssertEqual(group.wait(timeout: .now()+10),.success)
        XCTAssertEqual(successes.count,1); XCTAssertEqual(failures,1)
        let winner = try XCTUnwrap(successes.first)
        XCTAssertEqual(try repo.readCurrent(id: winner.id),winner)
        XCTAssertNil(try repo.readCurrent(id: winner.id == a.id ? b.id : a.id))
    }
}
