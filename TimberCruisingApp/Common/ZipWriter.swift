// Minimal ZIP writer for Phase 6 Shapefile bundles.
//
// ## What this is (and isn't)
// Foundation on iOS/macOS doesn't expose a user-level ZIP API that works
// without either shelling out (iOS-unfriendly) or pulling in a 3rd-party
// library. Our output volume is tiny (≤ a few MB per cruise) and we don't
// care about compression ratio — shapefile binaries are already near-
// theoretical-minimum dense. So we implement only the PKZIP "stored"
// method (compression_method = 0), which lets any unzipper (Finder, macOS
// Archive Utility, `unzip`, Python's zipfile, QGIS's virtual filesystem)
// open the archive as a regular ZIP.
//
// ## Format references
//   * PKWARE APPNOTE.TXT v6.3.10 §4.3 Local file header
//                                §4.3.12 Central directory header
//                                §4.3.16 End of central directory record
//
// ## Limitations
//   * No ZIP64 (archives > 4 GiB). We only ever emit a handful of KB.
//   * No deflate. Every entry is stored verbatim.
//   * No file attributes, no extra fields.

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

    private static func dosDateTime(_ d: Date) -> (date: UInt16, time: UInt16) {
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
        var c: UInt32 = 0xFFFFFFFF
        data.withUnsafeBytes { (buf: UnsafeRawBufferPointer) in
            for byte in buf {
                c = crcTable[Int((c ^ UInt32(byte)) & 0xFF)] ^ (c >> 8)
            }
        }
        return c ^ 0xFFFFFFFF
    }
}
