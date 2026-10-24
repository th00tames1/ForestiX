import XCTest
@testable import Common

final class ZipReaderSafetyTests: XCTestCase {
    func testOversizedLocalOffsetsAndPayloadSizesThrowInsteadOfOverflowing() throws {
        let archive = ZipWriter.storedArchive(files: [("x",Data())])
        let offset = ZipEntry(name: "x",compressionMethod: 0,compressedSize: 0,uncompressedSize: 0,
                              localHeaderOffset: Int.max,flags: 0,crc32: 0)
        XCTAssertThrowsError(try ZipReader.extract(offset,from: archive))
        let payload = ZipEntry(name: "x",compressionMethod: 0,compressedSize: Int.max,uncompressedSize: 0,
                               localHeaderOffset: 0,flags: 0,crc32: 0)
        XCTAssertThrowsError(try ZipReader.extract(payload,from: archive))
    }
    func testStoredPayloadCRCAndDeclaredLengthAreVerified() throws {
        var archive = ZipWriter.storedArchive(files: [("x",Data([1,2,3]))])
        let entry = try XCTUnwrap(ZipReader.entries(in: archive).first)
        XCTAssertEqual(try ZipReader.extract(entry,from: archive),Data([1,2,3]))
        archive[31] ^= 1
        XCTAssertThrowsError(try ZipReader.extract(entry,from: archive))
        let badLength = ZipEntry(name: entry.name,compressionMethod: 0,compressedSize: 3,uncompressedSize: 4,
                                 localHeaderOffset: 0,flags: 0,crc32: entry.crc32)
        XCTAssertThrowsError(try ZipReader.extract(badLength,from: archive))
    }
}
