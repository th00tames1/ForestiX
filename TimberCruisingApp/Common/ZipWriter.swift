// Minimal ZIP writer for Phase 6 Shapefile bundles.
//
// ## What this is (and isn't)
// Foundation on iOS/macOS doesn't expose a user-level ZIP API that works
// without either shelling out (iOS-unfriendly) or pulling in a 3rd-party
// library. Our output volume is tiny (≤ a few MB per cruise) and we don't
// care about compression ratio — shapefile binaries are already near-
// theoretical-minimum dense. So we implement only the PKZIP "stored"
// method (compression_method = 0), which lets any unzipper (Finder, macOS
// Archive Utility, `unzip`, Python's zipfile)
// open the archive as a regular ZIP.
//
// ## Format references
//   * PKWARE APPNOTE.TXT v6.3.10 §4.3 Local file header
//                                §4.3.12 Central directory header
//                                §4.3.16 End of central directory record
//
// ## Limitations
//   * No deflate. Every entry is stored verbatim.
//   * No file attributes.
//
// ## Two writers
//   * `storedArchive(files:)` — the original all-in-memory builder, still
//     used by the tiny shapefile bundles.
//   * `ZipStreamWriter` — appends ONE entry at a time straight to a file on
//     disk, so a multi-GB raw-capture corpus never has to fit in RAM (the
//     in-memory path held the corpus twice and was killed by the OS). It
//     emits ZIP64 records once the archive passes 4 GiB instead of trapping
//     on a UInt32 offset conversion.

import Foundation

public enum ZipWriter {

    /// Produce an uncompressed ZIP archive containing the supplied
    /// (filename → data) pairs, preserving input order.
    public static func storedArchive(files: [(String, Data)]) -> Data {
        var archive = Data()
        struct CentralEntry {
            let name: String
            let crc: UInt32
            let size: UInt32
            let localHeaderOffset: UInt32
        }
        var centralEntries: [CentralEntry] = []
        let (dosDate, dosTime) = dosDateTime(Date())

        for (name, payload) in files {
            let offset = UInt32(archive.count)
            let nameBytes = Data(name.utf8)
            let crc = crc32(of: payload)
            let size = UInt32(payload.count)

            // Local file header — APPNOTE §4.3.7
            archive.appendLE(UInt32(0x04034b50))   // signature
            archive.appendLE(UInt16(20))           // version needed
            archive.appendLE(UInt16(0x0800))       // flags — UTF-8 bit (EFS)
            archive.appendLE(UInt16(0))            // compression: stored
            archive.appendLE(dosTime)
            archive.appendLE(dosDate)
            archive.appendLE(crc)
            archive.appendLE(size)                 // compressed size
            archive.appendLE(size)                 // uncompressed size
            archive.appendLE(UInt16(nameBytes.count))
            archive.appendLE(UInt16(0))            // extra field length
            archive.append(nameBytes)
            archive.append(payload)

            centralEntries.append(CentralEntry(
                name: name, crc: crc, size: size,
                localHeaderOffset: offset))
        }

        // Central directory — APPNOTE §4.3.12
        let cdOffset = UInt32(archive.count)
        for e in centralEntries {
            let nameBytes = Data(e.name.utf8)
            archive.appendLE(UInt32(0x02014b50))   // signature
            archive.appendLE(UInt16(0x031E))       // version made by — UNIX
            archive.appendLE(UInt16(20))           // version needed
            archive.appendLE(UInt16(0x0800))       // flags — UTF-8
            archive.appendLE(UInt16(0))            // compression: stored
            archive.appendLE(dosTime)
            archive.appendLE(dosDate)
            archive.appendLE(e.crc)
            archive.appendLE(e.size)
            archive.appendLE(e.size)
            archive.appendLE(UInt16(nameBytes.count))
            archive.appendLE(UInt16(0))            // extra length
            archive.appendLE(UInt16(0))            // comment length
            archive.appendLE(UInt16(0))            // disk number start
            archive.appendLE(UInt16(0))            // internal attrs
            archive.appendLE(UInt32(0))            // external attrs
            archive.appendLE(e.localHeaderOffset)
            archive.append(nameBytes)
        }
        let cdSize = UInt32(archive.count) - cdOffset

        // End-of-central-directory — APPNOTE §4.3.16
        archive.appendLE(UInt32(0x06054b50))       // signature
        archive.appendLE(UInt16(0))                // this disk
        archive.appendLE(UInt16(0))                // disk with CD
        archive.appendLE(UInt16(centralEntries.count))
        archive.appendLE(UInt16(centralEntries.count))
        archive.appendLE(cdSize)
        archive.appendLE(cdOffset)
        archive.appendLE(UInt16(0))                // comment length

        return archive
    }

    // MARK: - DOS date / time packing

    static func dosDateTime(_ d: Date) -> (date: UInt16, time: UInt16) {
        let cal = Calendar(identifier: .gregorian)
        let utc = TimeZone(identifier: "UTC")!
        let c = cal.dateComponents(in: utc, from: d)
        let year = max(1980, c.year ?? 1980)
        let month = c.month ?? 1
        let day = c.day ?? 1
        let hour = c.hour ?? 0
        let minute = c.minute ?? 0
        let second = c.second ?? 0

        let yearBits = UInt16((year - 1980) & 0x7F) << 9
        let monthBits = UInt16(month & 0x0F) << 5
        let dayBits = UInt16(day & 0x1F)
        let date = yearBits | monthBits | dayBits

        let hourBits = UInt16(hour & 0x1F) << 11
        let minuteBits = UInt16(minute & 0x3F) << 5
        let secondBits = UInt16((second / 2) & 0x1F)
        let time = hourBits | minuteBits | secondBits

        return (date, time)
    }

    // MARK: - CRC32 (IEEE polynomial 0xEDB88320)

    private static let crcTable: [UInt32] = {
        var table = [UInt32](repeating: 0, count: 256)
        for i in 0..<256 {
            var c = UInt32(i)
            for _ in 0..<8 {
                c = (c & 1) == 1 ? (0xEDB88320 ^ (c >> 1)) : (c >> 1)
            }
            table[i] = c
        }
        return table
    }()

    public static func crc32(of data: Data) -> UInt32 {
        crc32Finish(crc32Update(crc32Seed, data))
    }

    /// Incremental CRC32 so a file can be checksummed in bounded-size
    /// chunks instead of being read into memory whole.
    public static let crc32Seed: UInt32 = 0xFFFFFFFF

    public static func crc32Update(_ running: UInt32, _ data: Data) -> UInt32 {
        var c = running
        data.withUnsafeBytes { (buf: UnsafeRawBufferPointer) in
            for byte in buf {
                c = crcTable[Int((c ^ UInt32(byte)) & 0xFF)] ^ (c >> 8)
            }
        }
        return c
    }

    public static func crc32Finish(_ running: UInt32) -> UInt32 {
        running ^ 0xFFFFFFFF
    }
}

// MARK: - Streaming writer (large archives, bounded memory)

/// Writes a stored (uncompressed) ZIP **entry by entry** to a file on disk.
/// Peak memory is one chunk (1 MB), not the archive — the raw-capture corpus
/// export used to build the whole thing in RAM and be jetsam-killed.
///
/// ZIP64: local headers stay 32-bit (a single capture file is never ≥ 4 GiB),
/// but once an entry's local-header offset — or the central directory itself —
/// passes 4 GiB, the central record carries a ZIP64 extended-information extra
/// field and the archive is closed with a ZIP64 end-of-central-directory
/// record + locator. APPNOTE §4.5.3, §4.3.14, §4.3.15.
public final class ZipStreamWriter {

    public enum ZipError: Error, LocalizedError {
        case cannotCreate(String)
        case writeFailed(String)
        case readFailed(String)
        case entryTooLarge(String)

        public var errorDescription: String? {
            switch self {
            case .cannotCreate(let p):  return "Couldn't create the archive at \(p)."
            case .writeFailed(let m):   return "Couldn't write the archive: \(m)"
            case .readFailed(let m):    return "Couldn't read a capture file: \(m)"
            case .entryTooLarge(let n): return "\(n) is larger than 4 GiB."
            }
        }
    }

    private struct Entry {
        let name: String
        let crc: UInt32
        let size: UInt32
        let offset: UInt64
    }

    private let handle: FileHandle
    private let dosDate: UInt16
    private let dosTime: UInt16
    private var entries: [Entry] = []
    private var offset: UInt64 = 0
    private var closed = false

    /// Chunk size for the copy + checksum passes.
    private static let chunkBytes = 1 << 20      // 1 MiB

    public init(url: URL) throws {
        let fm = FileManager.default
        try? fm.removeItem(at: url)
        guard fm.createFile(atPath: url.path, contents: nil) else {
            throw ZipError.cannotCreate(url.path)
        }
        do {
            handle = try FileHandle(forWritingTo: url)
        } catch {
            throw ZipError.cannotCreate(url.path)
        }
        let stamp = ZipWriter.dosDateTime(Date())
        dosDate = stamp.date
        dosTime = stamp.time
    }

    /// Append one file from disk in ONE bounded pass: the payload is
    /// checksummed and copied in the same walk, then the local header's CRC
    /// and size fields are back-patched with the bytes ACTUALLY written, and
    /// that same counted size is what the central directory records.
    ///
    /// The declared size is never taken from a file attribute. It used to be
    /// `(attrs[.size] as? NSNumber)?.uint64Value ?? 0` over a
    /// `(try? attributesOfItem(…)) ?? [:]`: when the attribute read failed but
    /// the file still opened, both headers declared 0 bytes while the real
    /// payload was appended anyway. The archive passes every structural check,
    /// and then any unzipper reads 0 bytes for that entry and parses the
    /// payload as the next local file header — silently losing that entry and
    /// everything after it in the archive. A size that is written into the
    /// format may not have a fallback; it is counted or the write throws.
    public func add(name: String, fileURL: URL) throws {
        // Best-effort early bail so an oversized file isn't copied before it
        // is rejected. An UNREADABLE attribute is not an error here — the
        // authoritative size is the byte count below.
        if let declared = (try? FileManager.default
            .attributesOfItem(atPath: fileURL.path))?[.size] as? NSNumber,
           declared.uint64Value > UInt64(UInt32.max) {
            throw ZipError.entryTooLarge(name)
        }

        // Provisional header — crc/size are placeholders, patched below.
        let headerOffset = try writeLocalHeader(name: name, crc: 0, size: 0)

        var crc = ZipWriter.crc32Seed
        var written: UInt64 = 0
        do {
            let reader = try FileHandle(forReadingFrom: fileURL)
            defer { try? reader.close() }
            while true {
                let chunk = try reader.read(upToCount: Self.chunkBytes) ?? Data()
                if chunk.isEmpty { break }
                written &+= UInt64(chunk.count)
                guard written <= UInt64(UInt32.max) else {
                    throw ZipError.entryTooLarge(name)
                }
                crc = ZipWriter.crc32Update(crc, chunk)
                try write(chunk)
            }
        } catch let e as ZipError {
            throw e
        } catch {
            throw ZipError.readFailed("\(name): \(error.localizedDescription)")
        }

        let finalCRC = ZipWriter.crc32Finish(crc)
        let size = UInt32(written)
        // Local file header, APPNOTE §4.3.7: crc at +14, compressed size at
        // +18, uncompressed size at +22 — 12 contiguous bytes.
        var patch = Data()
        patch.appendLE(finalCRC)
        patch.appendLE(size)
        patch.appendLE(size)
        try backpatch(at: headerOffset + 14, patch)
        entries.append(Entry(name: name, crc: finalCRC, size: size, offset: headerOffset))
    }

    /// Append one in-memory blob (small generated files, e.g. a README).
    /// The size here IS the byte count, so nothing needs patching.
    public func add(name: String, data: Data) throws {
        guard data.count <= Int(UInt32.max) else { throw ZipError.entryTooLarge(name) }
        let crc = ZipWriter.crc32(of: data)
        let size = UInt32(data.count)
        let headerOffset = try writeLocalHeader(name: name, crc: crc, size: size)
        try write(data)
        entries.append(Entry(name: name, crc: crc, size: size, offset: headerOffset))
    }

    /// Central directory + (ZIP64) end-of-central-directory. Idempotent.
    public func finish() throws {
        guard !closed else { return }
        closed = true
        let cdOffset = offset
        var cd = Data()
        for e in entries {
            let nameBytes = Data(e.name.utf8)
            let needsZip64 = e.offset >= UInt64(UInt32.max)
            var extra = Data()
            if needsZip64 {
                extra.appendLE(UInt16(0x0001))          // ZIP64 extended info
                extra.appendLE(UInt16(8))               // just the 8-byte offset
                extra.appendLE64(e.offset)
            }
            cd.appendLE(UInt32(0x02014b50))             // signature
            cd.appendLE(UInt16(0x031E))                 // version made by — UNIX
            cd.appendLE(UInt16(needsZip64 ? 45 : 20))   // version needed
            cd.appendLE(UInt16(0x0800))                 // flags — UTF-8
            cd.appendLE(UInt16(0))                      // compression: stored
            cd.appendLE(dosTime)
            cd.appendLE(dosDate)
            cd.appendLE(e.crc)
            cd.appendLE(e.size)
            cd.appendLE(e.size)
            cd.appendLE(UInt16(nameBytes.count))
            cd.appendLE(UInt16(extra.count))
            cd.appendLE(UInt16(0))                      // comment length
            cd.appendLE(UInt16(0))                      // disk number start
            cd.appendLE(UInt16(0))                      // internal attrs
            cd.appendLE(UInt32(0))                      // external attrs
            cd.appendLE(needsZip64 ? UInt32.max : UInt32(e.offset))
            cd.append(nameBytes)
            cd.append(extra)
            if cd.count >= Self.chunkBytes {
                try write(cd)
                cd.removeAll(keepingCapacity: true)
            }
        }
        if !cd.isEmpty { try write(cd) }
        let cdSize = offset - cdOffset

        let count = entries.count
        let needsZip64 = count > Int(UInt16.max)
            || cdOffset >= UInt64(UInt32.max)
            || cdSize >= UInt64(UInt32.max)

        var tail = Data()
        if needsZip64 {
            let z64Offset = offset
            tail.appendLE(UInt32(0x06064b50))           // ZIP64 EOCD signature
            tail.appendLE64(UInt64(44))                 // size of the record that follows
            tail.appendLE(UInt16(45))                   // version made by
            tail.appendLE(UInt16(45))                   // version needed
            tail.appendLE(UInt32(0))                    // this disk
            tail.appendLE(UInt32(0))                    // disk with CD
            tail.appendLE64(UInt64(count))              // entries on this disk
            tail.appendLE64(UInt64(count))              // entries total
            tail.appendLE64(cdSize)
            tail.appendLE64(cdOffset)
            tail.appendLE(UInt32(0x07064b50))           // ZIP64 EOCD locator
            tail.appendLE(UInt32(0))                    // disk with ZIP64 EOCD
            tail.appendLE64(z64Offset)
            tail.appendLE(UInt32(1))                    // total disks
        }
        tail.appendLE(UInt32(0x06054b50))               // EOCD signature
        tail.appendLE(UInt16(0))                        // this disk
        tail.appendLE(UInt16(0))                        // disk with CD
        tail.appendLE(UInt16(min(count, Int(UInt16.max))))
        tail.appendLE(UInt16(min(count, Int(UInt16.max))))
        tail.appendLE(cdSize >= UInt64(UInt32.max) ? UInt32.max : UInt32(cdSize))
        tail.appendLE(cdOffset >= UInt64(UInt32.max) ? UInt32.max : UInt32(cdOffset))
        tail.appendLE(UInt16(0))                        // comment length
        try write(tail)
        try? handle.close()
    }

    /// Abandon the archive (leaves the partial file for the caller to delete).
    public func cancel() {
        closed = true
        try? handle.close()
    }

    // MARK: Internals

    /// Writes the local file header and returns its absolute offset. The
    /// caller appends the `Entry` once the real crc/size are known, so a
    /// central-directory record can never carry a size the payload didn't have.
    @discardableResult
    private func writeLocalHeader(name: String, crc: UInt32, size: UInt32) throws -> UInt64 {
        let nameBytes = Data(name.utf8)
        let localOffset = offset
        var header = Data()
        header.appendLE(UInt32(0x04034b50))     // signature
        header.appendLE(UInt16(20))             // version needed
        header.appendLE(UInt16(0x0800))         // flags — UTF-8 (EFS)
        header.appendLE(UInt16(0))              // compression: stored
        header.appendLE(dosTime)
        header.appendLE(dosDate)
        header.appendLE(crc)
        header.appendLE(size)                   // compressed size
        header.appendLE(size)                   // uncompressed size
        header.appendLE(UInt16(nameBytes.count))
        header.appendLE(UInt16(0))              // extra field length
        header.append(nameBytes)
        try write(header)
        return localOffset
    }

    private func write(_ data: Data) throws {
        do {
            try handle.write(contentsOf: data)
        } catch {
            throw ZipError.writeFailed(error.localizedDescription)
        }
        offset &+= UInt64(data.count)
    }

    /// Overwrite bytes already committed to the archive file, then return the
    /// write head to the end. Used to stamp an entry's real crc/size into its
    /// local header once the payload has been counted. The archive is a temp
    /// file on disk, so it is always seekable.
    private func backpatch(at absoluteOffset: UInt64, _ data: Data) throws {
        let end = offset
        do {
            try handle.seek(toOffset: absoluteOffset)
            try handle.write(contentsOf: data)
            try handle.seek(toOffset: end)
        } catch {
            throw ZipError.writeFailed(error.localizedDescription)
        }
    }
}

public extension Data {
    mutating func appendLE64(_ v: UInt64) {
        var littleEndian = v.littleEndian
        Swift.withUnsafeBytes(of: &littleEndian) { self.append(contentsOf: $0) }
    }
}
