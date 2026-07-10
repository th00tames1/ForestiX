// NDJSON per-session append log: write → read round-trip, append
// semantics, delete.

import XCTest
@testable import Export

final class TrackLogRepositoryTests: XCTestCase {

    private func tmpRepo() throws -> FileTrackLogRepository {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("tracklog-\(UUID().uuidString)",
                                    isDirectory: true)
        return FileTrackLogRepository(rootDirectory: dir)
    }

    func testAppendAndReadBackMultipleEntries() throws {
        let repo = try tmpRepo()
        let sid = UUID()
        let t0 = Date(timeIntervalSince1970: 1_700_000_000)
        let e1 = TrackLogEntry(
            lat: 45, lon: -122, timestamp: t0,
            horizontalAccuracyM: 4)
        let e2 = TrackLogEntry(
            lat: 45.001, lon: -122.001,
            timestamp: t0.addingTimeInterval(30),
            horizontalAccuracyM: 5)
        try repo.append(sessionId: sid, entry: e1)
        try repo.append(sessionId: sid, entry: e2)

        let back = try repo.readAll(sessionId: sid)
        XCTAssertEqual(back.count, 2)
        XCTAssertEqual(back[0], e1)
        XCTAssertEqual(back[1], e2)
    }

    func testReadNonexistentSessionReturnsEmpty() throws {
        let repo = try tmpRepo()
        let entries = try repo.readAll(sessionId: UUID())
        XCTAssertTrue(entries.isEmpty)
    }

    func testDeleteRemovesFile() throws {
        let repo = try tmpRepo()
        let sid = UUID()
        try repo.append(sessionId: sid, entry: .init(
            lat: 0, lon: 0, timestamp: Date()))
        let url = try repo.fileURL(for: sid)
        XCTAssertTrue(FileManager.default.fileExists(atPath: url.path))
        try repo.deleteSession(sid)
        XCTAssertFalse(FileManager.default.fileExists(atPath: url.path))
    }

    func testEachSessionGetsOwnFile() throws {
        let repo = try tmpRepo()
        let a = UUID(); let b = UUID()
        try repo.append(sessionId: a, entry: .init(
            lat: 1, lon: 1, timestamp: Date()))
        try repo.append(sessionId: b, entry: .init(
            lat: 2, lon: 2, timestamp: Date()))
        XCTAssertEqual(try repo.readAll(sessionId: a).count, 1)
        XCTAssertEqual(try repo.readAll(sessionId: b).count, 1)
        XCTAssertNotEqual(try repo.readAll(sessionId: a).first!.lat,
                          try repo.readAll(sessionId: b).first!.lat)
    }
}
