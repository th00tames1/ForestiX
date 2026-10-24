import XCTest
import Persistence

final class CoreDataStartupFailureTests: XCTestCase {
    private final class TestFileManager: FileManager, @unchecked Sendable {
        var support: URL?
        var failSupport = false
        override func url(for directory: SearchPathDirectory,in domain: SearchPathDomainMask,
                          appropriateFor url: URL?,create shouldCreate: Bool) throws -> URL {
            if failSupport { throw NSError(domain: "SyntheticStorage",code: 42) }
            return try XCTUnwrap(support)
        }
    }
    func testApplicationSupportFailureNeverSubstitutesTemporaryStorage() {
        let fm = TestFileManager(); fm.failSupport = true
        XCTAssertThrowsError(try CoreDataStack.defaultStoreURL(fileManager: fm)) {
            XCTAssertEqual(($0 as NSError).domain,"SyntheticStorage")
            XCTAssertEqual(($0 as NSError).code,42)
        }
    }
    func testDirectoryFailurePreservesBlockingOriginalBytesAndRefusesStartup() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("forestix-startup-"+UUID().uuidString)
        try FileManager.default.createDirectory(at: root,withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let original = Data("Synthetic original file; do not replace".utf8)
        let blocked = root.appendingPathComponent("TimberCruising")
        try original.write(to: blocked)
        let fm = TestFileManager(); fm.support = root
        XCTAssertThrowsError(try CoreDataStack.defaultStoreURL(fileManager: fm))
        XCTAssertEqual(try Data(contentsOf: blocked),original)
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: root.path),["TimberCruising"])
    }
    func testNormalPersistentURLRetainsTheExistingPathAndDoesNotCreateADatabase() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("forestix-startup-"+UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let fm = TestFileManager(); fm.support = root
        let url = try CoreDataStack.defaultStoreURL(fileManager: fm)
        XCTAssertEqual(url,root.appendingPathComponent("TimberCruising/TimberCruising.sqlite"))
        XCTAssertTrue(FileManager.default.fileExists(atPath: url.deletingLastPathComponent().path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: url.path))
    }
}
