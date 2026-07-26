// Minimal ZIP *reader* — the inverse of ZipWriter, needed by the survey-
// boundary import path (a .kmz is a zipped .kml; a zipped shapefile is a
// .zip holding .shp/.dbf/.prj).
//
// ## Why hand-rolled
// Foundation ships no user-level unzip API on iOS. ZipWriter is
// write-only (and stored-method-only), so it cannot be reused here:
// real-world .kmz/.zip files from QGIS, Google Earth and government
// portals are DEFLATE-compressed. This reader therefore parses the
// end-of-central-directory record, walks the central directory, and
// inflates STORED (method 0) and DEFLATE (method 8) entries through the
// system Compression framework.
//
// ## Format references
//   * PKWARE APPNOTE.TXT v6.3.10 §4.3.7  Local file header
//                                §4.3.12 Central directory header
//                                §4.3.16 End of central directory record
//                                §4.3.14 ZIP64 end of central directory
//
// ## Scope
// Boundary files are small (a few MB at most), so the whole archive is
// held in memory and entries are materialised on demand. Encryption,
// multi-disk archives and compression methods other than 0/8 are
// rejected loudly rather than silently skipped.

import Foundation
#if canImport(Compression)
import Compression
#endif

public enum ZipReaderError: Error, CustomStringConvertible {
    case notAZipArchive
    case malformed(String)
    case unsupportedCompression(UInt16)
    case encrypted(String)
    case inflateFailed(String)

    public var description: String {
        switch self {
        case .notAZipArchive:
            return "Not a ZIP archive (no end-of-central-directory record)"
        case .malformed(let r): return "Malformed ZIP archive: \(r)"
        case .unsupportedCompression(let m):
            return "Unsupported ZIP compression method \(m) (only stored and deflate are supported)"
        case .encrypted(let name): return "ZIP entry is encrypted: \(name)"
        case .inflateFailed(let r): return "ZIP entry could not be decompressed: \(r)"
        }
    }
}

/// One central-directory record. `localHeaderOffset` is where the entry's
/// local file header starts — the payload sits after that header's own
/// (possibly different) name/extra fields.
public struct ZipEntry: Equatable, Sendable {
    public let name: String
    public let compressionMethod: UInt16
    public let compressedSize: Int
    public let uncompressedSize: Int
    public let localHeaderOffset: Int
    public let flags: UInt16

    public var isDirectory: Bool { name.hasSuffix("/") }

    /// Last path component, lowercased extension only (".kml", ".shp"…).
    public var pathExtension: String {
        (name as NSString).pathExtension.lowercased()
    }
}

public enum ZipReader {

    // MARK: - Public API

    /// Central-directory listing, in archive order.
    public static func entries(in archive: Data) throws -> [ZipEntry] {
        let bytes = [UInt8](archive)
        let eocd = try findEOCD(bytes)
        var cursor = eocd.centralDirectoryOffset
        var out: [ZipEntry] = []
        out.reserveCapacity(eocd.entryCount)
        for _ in 0..<eocd.entryCount {
            guard cursor + 46 <= bytes.count else {
                throw ZipReaderError.malformed("central directory truncated")
            }
            guard u32(bytes, cursor) == 0x0201_4b50 else {
                throw ZipReaderError.malformed("bad central-directory signature")
            }
            let flags        = u16(bytes, cursor + 8)
            let method       = u16(bytes, cursor + 10)
            let compSize     = Int(u32(bytes, cursor + 20))
            let uncompSize   = Int(u32(bytes, cursor + 24))
            let nameLen      = Int(u16(bytes, cursor + 28))
            let extraLen     = Int(u16(bytes, cursor + 30))
            let commentLen   = Int(u16(bytes, cursor + 32))
            let localOffset  = Int(u32(bytes, cursor + 42))
            let nameStart = cursor + 46
            guard nameStart + nameLen + extraLen + commentLen <= bytes.count else {
                throw ZipReaderError.malformed("central-directory entry overruns archive")
            }
            let rawName = Data(bytes[nameStart..<(nameStart + nameLen)])
            let name = String(data: rawName, encoding: .utf8)
                ?? String(decoding: rawName, as: UTF8.self)

            // ZIP64 extended information (header id 0x0001) overrides any
            // 0xFFFFFFFF placeholder in the 32-bit fields.
            var resolvedUncomp = uncompSize
            var resolvedComp = compSize
            var resolvedLocal = localOffset
            if uncompSize == 0xFFFF_FFFF || compSize == 0xFFFF_FFFF
                || localOffset == 0xFFFF_FFFF {
                let extraStart = nameStart + nameLen
                var p = extraStart
                while p + 4 <= extraStart + extraLen {
                    let id = u16(bytes, p)
                    let size = Int(u16(bytes, p + 2))
                    var q = p + 4
                    if id == 0x0001 {
                        if resolvedUncomp == 0xFFFF_FFFF, q + 8 <= bytes.count {
                            resolvedUncomp = Int(u64(bytes, q)); q += 8
                        }
                        if resolvedComp == 0xFFFF_FFFF, q + 8 <= bytes.count {
                            resolvedComp = Int(u64(bytes, q)); q += 8
                        }
                        if resolvedLocal == 0xFFFF_FFFF, q + 8 <= bytes.count {
                            resolvedLocal = Int(u64(bytes, q))
                        }
                        break
                    }
                    p += 4 + size
                }
            }

            out.append(ZipEntry(name: name,
                                compressionMethod: method,
                                compressedSize: resolvedComp,
                                uncompressedSize: resolvedUncomp,
                                localHeaderOffset: resolvedLocal,
                                flags: flags))
            cursor = nameStart + nameLen + extraLen + commentLen
        }
        return out
    }

    /// Materialise one entry's bytes.
    public static func extract(_ entry: ZipEntry, from archive: Data) throws -> Data {
        let bytes = [UInt8](archive)
        // Bit 0 of the general-purpose flags = encrypted.
        guard entry.flags & 0x0001 == 0 else {
            throw ZipReaderError.encrypted(entry.name)
        }
        let lho = entry.localHeaderOffset
        guard lho >= 0, lho + 30 <= bytes.count else {
            throw ZipReaderError.malformed("local header offset out of range")
        }
        guard u32(bytes, lho) == 0x0403_4b50 else {
            throw ZipReaderError.malformed("bad local-file-header signature")
        }
        let nameLen = Int(u16(bytes, lho + 26))
        let extraLen = Int(u16(bytes, lho + 28))
        let dataStart = lho + 30 + nameLen + extraLen
        let dataEnd = dataStart + entry.compressedSize
        guard dataStart <= bytes.count, dataEnd <= bytes.count, dataEnd >= dataStart else {
            throw ZipReaderError.malformed("entry payload overruns archive")
        }
        let payload = Data(bytes[dataStart..<dataEnd])

        switch entry.compressionMethod {
        case 0:
            return payload
        case 8:
            return try inflate(payload, expectedSize: entry.uncompressedSize)
        default:
            throw ZipReaderError.unsupportedCompression(entry.compressionMethod)
        }
    }

    /// Convenience: every non-directory entry as (name, bytes).
    public static func readAll(_ archive: Data) throws -> [(name: String, data: Data)] {
        try entries(in: archive)
            .filter { !$0.isDirectory }
            .map { ($0.name, try extract($0, from: archive)) }
    }

    // MARK: - End of central directory

    private struct EOCD {
        let entryCount: Int
        let centralDirectorySize: Int
        let centralDirectoryOffset: Int
    }

    private static func findEOCD(_ bytes: [UInt8]) throws -> EOCD {
        guard bytes.count >= 22 else { throw ZipReaderError.notAZipArchive }
        // Comment may be up to 65535 bytes; scan backwards for the sig.
        let lowest = max(0, bytes.count - 22 - 65_535)
        var idx = bytes.count - 22
        var found = -1
        while idx >= lowest {
            if u32(bytes, idx) == 0x0605_4b50 { found = idx; break }
            idx -= 1
        }
        guard found >= 0 else { throw ZipReaderError.notAZipArchive }

        var count = Int(u16(bytes, found + 10))
        var size = Int(u32(bytes, found + 12))
        var offset = Int(u32(bytes, found + 16))

        // ZIP64: the 32-bit fields are saturated and the real record is
        // located through the ZIP64 EOCD locator that precedes the EOCD.
        if count == 0xFFFF || size == 0xFFFF_FFFF || offset == 0xFFFF_FFFF {
            let locator = found - 20
            guard locator >= 0, u32(bytes, locator) == 0x0706_4b50 else {
                throw ZipReaderError.malformed("ZIP64 locator missing")
            }
            let z64 = Int(u64(bytes, locator + 8))
            guard z64 >= 0, z64 + 56 <= bytes.count,
                  u32(bytes, z64) == 0x0606_4b50 else {
                throw ZipReaderError.malformed("ZIP64 end-of-central-directory missing")
            }
            count = Int(u64(bytes, z64 + 32))
            size = Int(u64(bytes, z64 + 40))
            offset = Int(u64(bytes, z64 + 48))
        }

        guard offset >= 0, size >= 0, offset + size <= bytes.count, count >= 0 else {
            throw ZipReaderError.malformed("central directory out of range")
        }
        return EOCD(entryCount: count,
                    centralDirectorySize: size,
                    centralDirectoryOffset: offset)
    }

    // MARK: - Inflate

    /// Raw-DEFLATE decompression. Apple's `COMPRESSION_ZLIB` is the RAW
    /// deflate stream (no zlib wrapper), which is exactly what a ZIP
    /// entry stores. The streaming API is used rather than the one-shot
    /// buffer call so an entry whose header carries no (or a ZIP64
    /// placeholder) uncompressed size still decodes.
    static func inflate(_ payload: Data, expectedSize: Int) throws -> Data {
        #if canImport(Compression)
        guard !payload.isEmpty else { return Data() }
        let streamPtr = UnsafeMutablePointer<compression_stream>.allocate(capacity: 1)
        defer { streamPtr.deallocate() }
        guard compression_stream_init(streamPtr, COMPRESSION_STREAM_DECODE,
                                      COMPRESSION_ZLIB) == COMPRESSION_STATUS_OK
        else { throw ZipReaderError.inflateFailed("stream init failed") }
        defer { compression_stream_destroy(streamPtr) }

        let chunk = max(64 * 1024, min(expectedSize > 0 ? expectedSize : 0, 4 * 1024 * 1024))
        var output = Data()
        output.reserveCapacity(expectedSize > 0 ? expectedSize : payload.count * 4)
        let dst = UnsafeMutablePointer<UInt8>.allocate(capacity: chunk)
        defer { dst.deallocate() }

        var thrown: Error?
        payload.withUnsafeBytes { (raw: UnsafeRawBufferPointer) in
            guard let src = raw.bindMemory(to: UInt8.self).baseAddress else {
                thrown = ZipReaderError.inflateFailed("empty source buffer")
                return
            }
            streamPtr.pointee.src_ptr = src
            streamPtr.pointee.src_size = payload.count
            while true {
                streamPtr.pointee.dst_ptr = dst
                streamPtr.pointee.dst_size = chunk
                let status = compression_stream_process(streamPtr, 0)
                let produced = chunk - streamPtr.pointee.dst_size
                if produced > 0 { output.append(dst, count: produced) }
                switch status {
                case COMPRESSION_STATUS_OK:
                    // No progress and no input left ⇒ truncated stream.
                    if produced == 0 && streamPtr.pointee.src_size == 0 {
                        thrown = ZipReaderError.inflateFailed("truncated deflate stream")
                        return
                    }
                case COMPRESSION_STATUS_END:
                    return
                default:
                    thrown = ZipReaderError.inflateFailed("deflate error")
                    return
                }
            }
        }
        if let thrown { throw thrown }
        return output
        #else
        throw ZipReaderError.inflateFailed("Compression framework unavailable")
        #endif
    }

    // MARK: - Little-endian scalar reads

    private static func u16(_ b: [UInt8], _ i: Int) -> UInt16 {
        guard i >= 0, i + 2 <= b.count else { return 0 }
        return UInt16(b[i]) | (UInt16(b[i + 1]) << 8)
    }

    private static func u32(_ b: [UInt8], _ i: Int) -> UInt32 {
        guard i >= 0, i + 4 <= b.count else { return 0 }
        return UInt32(b[i]) | (UInt32(b[i + 1]) << 8)
            | (UInt32(b[i + 2]) << 16) | (UInt32(b[i + 3]) << 24)
    }

    private static func u64(_ b: [UInt8], _ i: Int) -> UInt64 {
        guard i >= 0, i + 8 <= b.count else { return 0 }
        var v: UInt64 = 0
        for k in (0..<8).reversed() { v = (v << 8) | UInt64(b[i + k]) }
        return v
    }
}
