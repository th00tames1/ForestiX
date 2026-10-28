import Foundation
import CryptoKit

public enum YoloModelIdentity {
    private static let lock = NSLock()
    nonisolated(unsafe) private static var cached: String?
    public static var bundledURL: URL? {
        Bundle.module.url(forResource: "yolo26_alignment",withExtension: "onnx",subdirectory: "Models")
            ?? Bundle.module.url(forResource: "yolo26_alignment",withExtension: "onnx")
    }
    /// Call on a utility worker, once per application runtime, not per inference.
    public static func sha256() throws -> String? {
        lock.lock(); defer { lock.unlock() }
        if let cached { return cached }
        guard let url = bundledURL else { return nil }
        let file = try FileHandle(forReadingFrom: url); defer { try? file.close() }
        var hash = SHA256()
        while let data = try file.read(upToCount: 64*1024), !data.isEmpty { hash.update(data: data) }
        let result = hash.finalize().map { String(format: "%02x",$0) }.joined()
        cached = result; return result
    }
}
