// REQ-DBH-007 — PLY serialisation + sandbox write.

import XCTest
@testable import Sensors

final class PLYExporterTests: XCTestCase {

    func testEncodeASCIIHeader() {
        let pts: [SIMD2<Double>] = [
            SIMD2(0.1, 1.5),
            SIMD2(-0.05, 1.48)
        ]
        let text = PLYExporter.encodeASCII(points: pts)

        XCTAssertTrue(text.hasPrefix("ply\n"))
        XCTAssertTrue(text.contains("format ascii 1.0"))
        XCTAssertTrue(text.contains("element vertex 2"))
        XCTAssertTrue(text.contains("property float x"))
        XCTAssertTrue(text.contains("property float y"))
        XCTAssertTrue(text.contains("property float z"))
        XCTAssertTrue(text.contains("end_header\n"))
        XCTAssertTrue(text.contains("0.1 0 1.5"))
        XCTAssertTrue(text.contains("-0.05 0 1.48"))
    }

    func testEncodeASCIIEmptyPoints() {
        let text = PLYExporter.encodeASCII(points: [])
        XCTAssertTrue(text.contains("element vertex 0"))
        XCTAssertTrue(text.hasSuffix("end_header\n"))
    }

    func testWriteCreatesSandboxedFile() throws {
        let dir = try makeTempDir()
        defer { try? FileManager.default.removeItem(at: dir) }

        let treeId = UUID()
        let url = try PLYExporter.write(
            points: [SIMD2(0, 1.5), SIMD2(0.1, 1.5)],
            treeId: treeId, directory: dir)

        XCTAssertTrue(FileManager.default.fileExists(atPath: url.path))
        XCTAssertEqual(url.lastPathComponent, "\(treeId.uuidString).ply")
        XCTAssertTrue(url.path.contains("raw-scans"))

        let body = try String(contentsOf: url, encoding: .utf8)
        XCTAssertTrue(body.contains("element vertex 2"))
    }

    func testWriterClosureReturnsPath() throws {
        let dir = try makeTempDir()
        defer { try? FileManager.default.removeItem(at: dir) }

        let treeId = UUID()
        let writer = PLYExporter.writer(directory: dir, treeId: treeId)
        let path = writer([SIMD2(0, 1), SIMD2(0, 2)])

        XCTAssertNotNil(path)
        XCTAssertTrue(path!.hasSuffix("\(treeId.uuidString).ply"))
        XCTAssertTrue(FileManager.default.fileExists(atPath: path!))
    }

    // MARK: - Helpers

    private func makeTempDir() throws -> URL {
        let url = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("ply-exporter-\(UUID().uuidString)")
        try FileManager.default.createDirectory(
            at: url, withIntermediateDirectories: true)
        return url
    }
}
