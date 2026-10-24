import XCTest
import Common

final class DurableJSONLAppendTests: XCTestCase {
    private func root() -> URL { FileManager.default.temporaryDirectory.appendingPathComponent("forestix-jsonl-test-"+UUID().uuidString) }
    func testCreatesAndAppendsWithoutRewritingPriorEntries() throws {
        let dir = root(); defer { try? FileManager.default.removeItem(at: dir) }
        let file = dir.appendingPathComponent("journal.jsonl")
        try DurableJSONLAppend.append(line: Data(#"{"id":"1"}"#.utf8),header: "#v 7\n",to: file)
        let first = try Data(contentsOf: file)
        try DurableJSONLAppend.append(line: Data(#"{"id":"2"}"#.utf8),header: "#v 7\n",to: file)
        let second = try Data(contentsOf: file)
        XCTAssertEqual(second.prefix(first.count),first)
        XCTAssertEqual(String(decoding: second,as: UTF8.self),"#v 7\n{\"id\":\"1\"}\n{\"id\":\"2\"}\n")
    }
    func testIncompletePriorTailAndInvalidLinesArePreservedAndRefused() throws {
        let dir = root(); defer { try? FileManager.default.removeItem(at: dir) }
        try FileManager.default.createDirectory(at: dir,withIntermediateDirectories: true)
        let file = dir.appendingPathComponent("journal.jsonl"); let original = Data("#v 7\n{\"partial\":".utf8)
        try original.write(to: file)
        XCTAssertThrowsError(try DurableJSONLAppend.append(line: Data("{}".utf8),header: "#v 7\n",to: file))
        XCTAssertEqual(try Data(contentsOf: file),original)
        XCTAssertThrowsError(try DurableJSONLAppend.append(line: Data("{}\n{}".utf8),header: "#v 7\n",to: file))
        XCTAssertEqual(try Data(contentsOf: file),original)
    }
    func testUnavailableParentIsNotReportedAsSaved() throws {
        let file = root(); defer { try? FileManager.default.removeItem(at: file) }
        let original = Data("existing".utf8); try original.write(to: file)
        XCTAssertThrowsError(try DurableJSONLAppend.append(line: Data("{}".utf8),header: "#v 7\n",to: file.appendingPathComponent("journal.jsonl")))
        XCTAssertEqual(try Data(contentsOf: file),original)
    }
}
