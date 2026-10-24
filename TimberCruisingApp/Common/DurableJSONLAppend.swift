import Foundation

public enum DurableJSONLAppend {
    private static let lock = NSLock()
    public enum WriteError: Error { case incompleteExistingTail, invalidLine }
    /// Preserve the entire existing journal. Roll back a failed append to its
    /// previous byte length; never glue a retry onto an incomplete JSON line.
    public static func append(line: Data, header: String, to url: URL) throws {
        guard !line.isEmpty, !line.contains(10), !line.contains(13) else { throw WriteError.invalidLine }
        lock.lock(); defer { lock.unlock() }
        let fm = FileManager.default
        try fm.createDirectory(at: url.deletingLastPathComponent(),withIntermediateDirectories: true)
        if !fm.fileExists(atPath: url.path) {
            var data = Data(header.utf8); data.append(line); data.append(10)
            try data.write(to: url,options: .atomic)
            let handle = try FileHandle(forWritingTo: url); defer { try? handle.close() }
            try handle.synchronize(); return
        }
        let handle = try FileHandle(forUpdating: url); defer { try? handle.close() }
        let offset = try handle.seekToEnd()
        if offset > 0 {
            try handle.seek(toOffset: offset-1)
            guard try handle.read(upToCount: 1) == Data([10]) else { throw WriteError.incompleteExistingTail }
        }
        try handle.seek(toOffset: offset)
        var data = line; data.append(10)
        do { try handle.write(contentsOf: data); try handle.synchronize() }
        catch {
            try? handle.truncate(atOffset: offset); try? handle.synchronize()
            throw error
        }
    }
}
