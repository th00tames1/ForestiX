// Shared Data-appending helpers used by ZipWriter (Common) and
// ShapefileExporter (Export). Kept in Common because both modules need
// the same endian-aware encoding primitives; duplicating is worse than
// sharing a public four-function extension.

import Foundation

public extension Data {
    mutating func appendBE(_ v: Int32) {
        var bigEndian = v.bigEndian
        Swift.withUnsafeBytes(of: &bigEndian) { self.append(contentsOf: $0) }
    }

    mutating func appendLE(_ v: Int16) {
        var littleEndian = v.littleEndian
        Swift.withUnsafeBytes(of: &littleEndian) { self.append(contentsOf: $0) }
    }

    mutating func appendLE(_ v: Int32) {
        var littleEndian = v.littleEndian
        Swift.withUnsafeBytes(of: &littleEndian) { self.append(contentsOf: $0) }
    }

    mutating func appendLE(_ v: UInt32) {
        var littleEndian = v.littleEndian
        Swift.withUnsafeBytes(of: &littleEndian) { self.append(contentsOf: $0) }
    }

    mutating func appendLE(_ v: UInt16) {
        var littleEndian = v.littleEndian
        Swift.withUnsafeBytes(of: &littleEndian) { self.append(contentsOf: $0) }
    }

    mutating func appendLE(_ v: Double) {
        var bits = v.bitPattern.littleEndian
        Swift.withUnsafeBytes(of: &bits) { self.append(contentsOf: $0) }
    }
}
