import Foundation
import Darwin

/// A permanent, exclusive writer claim. A crashed/partial write must use a
/// NEW measurement UUID, never overwrite evidence under an old identity.
enum RawCaptureWriteGuard {
    static func claim(directory: URL,id: String,kind: String) throws {
        RawCaptureStore.patchLock.lock(); defer { RawCaptureStore.patchLock.unlock() }
        try RawOperatorEditJournal.validate(directory: directory,id: id)
        let fm = FileManager.default
        try fm.createDirectory(at: directory,withIntermediateDirectories: true)
        let names = try fm.contentsOfDirectory(atPath: directory.path)
        guard names.allSatisfy({ $0 == "pending_patch.json" }) else {
            throw RawCaptureError.writeFailed("existing raw capture preserved; use a new measurement ID")
        }
        let url = directory.appendingPathComponent("writer_claim.json")
        let fd = url.path.withCString { Darwin.open($0,O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW,0o600) }
        guard fd >= 0 else { throw RawCaptureError.writeFailed("raw capture writer already claimed or unavailable") }
        let handle = FileHandle(fileDescriptor: fd,closeOnDealloc: true); defer { try? handle.close() }
        let data = try JSONSerialization.data(withJSONObject: ["schema": 1,"measurement_id": id,"kind": kind,
            "claimed_at": ISO8601DateFormatter().string(from: Date())],options: [.sortedKeys])
        try handle.write(contentsOf: data); try handle.synchronize()
    }
}
