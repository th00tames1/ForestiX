// Port of iOS Common/DataBinary.swift — endian-aware byte-buffer append
// helpers shared by ZipWriter and ShapefileExporter. Swift extends Data
// with overloaded appendLE/appendBE; Kotlin can't overload on Int16/Int32
// cleanly, so the width is spelled out in the function name instead.

package com.hcjeong.forestix.export

import java.io.ByteArrayOutputStream

/// Growable little/big-endian byte buffer standing in for Swift `Data`.
class BinaryData {
    private val buf = ByteArrayOutputStream()

    val size: Int get() = buf.size()

    fun append(bytes: ByteArray) {
        buf.write(bytes)
    }

    fun appendByte(v: Int) {
        buf.write(v and 0xFF)
    }

    fun appendZeros(count: Int) {
        buf.write(ByteArray(count))
    }

    fun appendLEInt16(v: Int) {
        buf.write(v and 0xFF)
        buf.write((v ushr 8) and 0xFF)
    }

    fun appendLEInt32(v: Int) {
        buf.write(v and 0xFF)
        buf.write((v ushr 8) and 0xFF)
        buf.write((v ushr 16) and 0xFF)
        buf.write((v ushr 24) and 0xFF)
    }

    /// Unsigned 32-bit little-endian (value carried in a Long, e.g. CRC32).
    fun appendLEUInt32(v: Long) {
        appendLEInt32((v and 0xFFFFFFFFL).toInt())
    }

    fun appendBEInt32(v: Int) {
        buf.write((v ushr 24) and 0xFF)
        buf.write((v ushr 16) and 0xFF)
        buf.write((v ushr 8) and 0xFF)
        buf.write(v and 0xFF)
    }

    fun appendLEDouble(v: Double) {
        val bits = java.lang.Double.doubleToLongBits(v)
        for (i in 0 until 8) {
            buf.write(((bits ushr (8 * i)) and 0xFFL).toInt())
        }
    }

    fun toByteArray(): ByteArray = buf.toByteArray()
}
