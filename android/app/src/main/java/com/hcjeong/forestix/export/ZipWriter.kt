// Port of iOS Common/ZipWriter.swift — minimal ZIP writer for Phase 6
// Shapefile bundles.
//
// Only the PKZIP "stored" method (compression_method = 0) is implemented,
// byte-matching the iOS writer's local headers / central directory /
// end-of-central-directory records so the two platforms emit structurally
// identical archives. (java.util.zip.ZipOutputStream would add extra
// fields and data descriptors that break parity.)
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

package com.hcjeong.forestix.export

import java.util.Calendar
import java.util.TimeZone
import java.util.zip.CRC32
import kotlin.math.max

object ZipWriter {

    /// Produce an uncompressed ZIP archive containing the supplied
    /// (filename → bytes) pairs, preserving input order.
    fun storedArchive(files: List<Pair<String, ByteArray>>): ByteArray {
        val archive = BinaryData()

        class CentralEntry(
            val name: String,
            val crc: Long,
            val size: Int,
            val localHeaderOffset: Int,
        )

        val centralEntries = mutableListOf<CentralEntry>()
        val (dosDate, dosTime) = dosDateTime(System.currentTimeMillis())

        for ((name, payload) in files) {
            val offset = archive.size
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            val crc = crc32(payload)
            val size = payload.size

            // Local file header — APPNOTE §4.3.7
            archive.appendLEUInt32(0x04034b50L)     // signature
            archive.appendLEInt16(20)               // version needed
            archive.appendLEInt16(0x0800)           // flags — UTF-8 bit (EFS)
            archive.appendLEInt16(0)                // compression: stored
            archive.appendLEInt16(dosTime)
            archive.appendLEInt16(dosDate)
            archive.appendLEUInt32(crc)
            archive.appendLEInt32(size)             // compressed size
            archive.appendLEInt32(size)             // uncompressed size
            archive.appendLEInt16(nameBytes.size)
            archive.appendLEInt16(0)                // extra field length
            archive.append(nameBytes)
            archive.append(payload)

            centralEntries.add(CentralEntry(
                name = name, crc = crc, size = size,
                localHeaderOffset = offset))
        }

        // Central directory — APPNOTE §4.3.12
        val cdOffset = archive.size
        for (e in centralEntries) {
            val nameBytes = e.name.toByteArray(Charsets.UTF_8)
            archive.appendLEUInt32(0x02014b50L)     // signature
            archive.appendLEInt16(0x031E)           // version made by — UNIX
            archive.appendLEInt16(20)               // version needed
            archive.appendLEInt16(0x0800)           // flags — UTF-8
            archive.appendLEInt16(0)                // compression: stored
            archive.appendLEInt16(dosTime)
            archive.appendLEInt16(dosDate)
            archive.appendLEUInt32(e.crc)
            archive.appendLEInt32(e.size)
            archive.appendLEInt32(e.size)
            archive.appendLEInt16(nameBytes.size)
            archive.appendLEInt16(0)                // extra length
            archive.appendLEInt16(0)                // comment length
            archive.appendLEInt16(0)                // disk number start
            archive.appendLEInt16(0)                // internal attrs
            archive.appendLEInt32(0)                // external attrs
            archive.appendLEInt32(e.localHeaderOffset)
            archive.append(nameBytes)
        }
        val cdSize = archive.size - cdOffset

        // End-of-central-directory — APPNOTE §4.3.16
        archive.appendLEUInt32(0x06054b50L)         // signature
        archive.appendLEInt16(0)                    // this disk
        archive.appendLEInt16(0)                    // disk with CD
        archive.appendLEInt16(centralEntries.size)
        archive.appendLEInt16(centralEntries.size)
        archive.appendLEInt32(cdSize)
        archive.appendLEInt32(cdOffset)
        archive.appendLEInt16(0)                    // comment length

        return archive.toByteArray()
    }

    // MARK: - DOS date / time packing

    private fun dosDateTime(epochMs: Long): Pair<Int, Int> {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = epochMs
        val year = max(1980, cal.get(Calendar.YEAR))
        val month = cal.get(Calendar.MONTH) + 1
        val day = cal.get(Calendar.DAY_OF_MONTH)
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        val second = cal.get(Calendar.SECOND)

        val date = ((year - 1980) and 0x7F shl 9) or
            ((month and 0x0F) shl 5) or
            (day and 0x1F)
        val time = ((hour and 0x1F) shl 11) or
            ((minute and 0x3F) shl 5) or
            ((second / 2) and 0x1F)
        return date to time
    }

    // MARK: - CRC32 (IEEE polynomial, same as iOS table implementation)

    fun crc32(data: ByteArray): Long {
        val crc = CRC32()
        crc.update(data)
        return crc.value
    }
}
