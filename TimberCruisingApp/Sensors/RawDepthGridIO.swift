import Foundation

/// Bounded, format-aware legacy grid reader. The modern capture_evidence
/// reader remains the checksummed authority for exact inputs and timestamps.
enum RawDepthGridIO {
    struct Grid {
        let depth: [Float]
        let confidence: [UInt8]
    }

    private static func read(_ name: String, directory: URL, bytes: Int) throws -> Data {
        guard !name.isEmpty, name == (name as NSString).lastPathComponent,
              !name.contains(".."), !name.contains("\\") else { throw CocoaError(.fileReadInvalidFileName) }
        let file = directory.appendingPathComponent(name)
        let values = try file.resourceValues(forKeys: [.isSymbolicLinkKey,.isRegularFileKey,.fileSizeKey])
        guard values.isSymbolicLink != true, values.isRegularFile == true, values.fileSize == bytes,
              file.resolvingSymlinksInPath().deletingLastPathComponent().standardizedFileURL ==
                directory.resolvingSymlinksInPath().standardizedFileURL else { throw CocoaError(.fileReadCorruptFile) }
        let data = try Data(contentsOf: file)
        guard data.count == bytes else { throw CocoaError(.fileReadCorruptFile) }
        return data
    }

    static func read(directory: URL, width: Int, height: Int, format: String,
                     depthFile: String, confidenceFile: String?) throws -> Grid {
        guard (1...4096).contains(width), (1...4096).contains(height), width * height <= 4 * 1024 * 1024,
              format == "f32m" || format == "u16mm" else { throw CocoaError(.fileReadCorruptFile) }
        let count = width * height, stride = format == "u16mm" ? 2 : 4
        let bytes = try read(depthFile,directory: directory,bytes: count * stride)
        var depth = [Float](repeating: 0,count: count), confidence = [UInt8](repeating: 0,count: count)
        for i in 0..<count {
            let offset = i * stride
            if format == "u16mm" {
                let mm = Int(bytes[offset]) | Int(bytes[offset+1]) << 8
                // Exact Android ingestMmInto rule, including zero for holes.
                if (1...8000).contains(mm) { depth[i] = Float(mm) / 1000; confidence[i] = 2 }
            } else {
                let bits = UInt32(bytes[offset]) | UInt32(bytes[offset+1]) << 8 |
                    UInt32(bytes[offset+2]) << 16 | UInt32(bytes[offset+3]) << 24
                depth[i] = Float(bitPattern: bits)
                if RawCaptureFrame.validDepth(depth[i]) { confidence[i] = 2 }
            }
        }
        if let name = confidenceFile {
            confidence = Array(try read(name,directory: directory,bytes: count))
            guard confidence.allSatisfy({ $0 <= 2 }) else { throw CocoaError(.fileReadCorruptFile) }
        }
        return Grid(depth: depth,confidence: confidence)
    }
}
