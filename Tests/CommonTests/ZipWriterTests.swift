// Phase 7 — ZipWriter lives in Common after moving from Export. Keep a
// minimal round-trip test so both consumers (ShapefileExporter and
// BackupArchive) have byte-level confidence.

import XCTest
@testable import Common

final class ZipWriterTests: XCTestCase {

    func testStoredArchiveProducesReadableZipStructure() {
        let files: [(String, Data)] = [
            ("hello.txt", Data("hello".utf8)),
            ("nested/a.json", Data(#"{"k":1}"#.utf8))
        ]
        let archive = ZipWriter.storedArchive(files: files)

        // Every stored ZIP starts with the local file header signature.
        XCTAssertEqual(archive[0], 0x50)
        XCTAssertEqual(archive[1], 0x4B)
        XCTAssertEqual(archive[2], 0x03)
        XCTAssertEqual(archive[3], 0x04)

        // End-of-central-directory signature present.
        let eocdSig: [UInt8] = [0x50, 0x4B, 0x05, 0x06]
        let found = (0..<archive.count - 3).contains { i in
            archive[i] == eocdSig[0] && archive[i+1] == eocdSig[1]
                && archive[i+2] == eocdSig[2] && archive[i+3] == eocdSig[3]
        }
        XCTAssertTrue(found, "EOCD signature should exist")
    }

    func testCRC32MatchesKnownValue() {
        // Standard IEEE CRC32 of "hello" == 0x3610A686.
        XCTAssertEqual(ZipWriter.crc32(of: Data("hello".utf8)),
                       0x3610A686)
    }
}
