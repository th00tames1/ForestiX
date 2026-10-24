import Foundation
import CryptoKit
import Models
import simd

/// Additive evidence: never substitutes a re-derived value for the displayed reading.
public struct RawCapturedResult: Codable, Sendable, Equatable {
    public var value: Double?
    public var sigma: Double?
    public var tier: String?
    public var method: String?
    public var valid: Bool
    public init(value: Double?, sigma: Double?, tier: String?, method: String?, valid: Bool) {
        self.value = value.flatMap { $0.isFinite ? $0 : nil }
        self.sigma = sigma.flatMap { $0.isFinite && $0 >= 0 ? $0 : nil }
        self.tier = tier; self.method = method; self.valid = valid
    }
}

public struct RawCapturedFile: Codable, Sendable, Equatable {
    public var file: String
    public var bytes: Int
    public var sha256: String
    func checkedURL(in directory: URL) throws -> URL {
        guard !file.isEmpty, file == (file as NSString).lastPathComponent, !file.contains(".."),
              !file.contains("\\"), bytes >= 0, bytes <= 32*1024*1024, sha256.count == 64,
              sha256.allSatisfy({ $0.isHexDigit }) else { throw FieldStudyError.invalid("raw evidence file") }
        return directory.appendingPathComponent(file)
    }
    func read(in directory: URL) throws -> Data {
        let url = try checkedURL(in: directory)
        let attributes = try url.resourceValues(forKeys: [.isRegularFileKey,.isSymbolicLinkKey,.fileSizeKey])
        guard attributes.isRegularFile == true, attributes.isSymbolicLink != true, attributes.fileSize == bytes else {
            throw FieldStudyError.invalid("raw evidence file type")
        }
        let data = try Data(contentsOf: url)
        guard data.count == bytes, RawCaptureEvidenceIO.hash(data) == sha256 else { throw FieldStudyError.invalid("raw evidence checksum") }
        return data
    }
}

public struct RawCapturedFrame: Codable, Sendable, Equatable {
    public var role: String
    public var width: Int
    public var height: Int
    public var depth: RawCapturedFile
    public var confidence: RawCapturedFile
    public var cameraFrameSeconds: Double?
    public var depthSensorSeconds: Double?
    public var depthTimeSource: String
    public var cameraPose: [Double]
    public var intrinsics: [Double]
    public var viewToDepth: [Double]?
    public var viewportRevision: String?
    public var sourceFrameId: String? = nil
    public var depthSensorId: String? = nil
    public var depthAgeNanoseconds: String? = nil
    public var cameraSensorId: String? = nil
    enum CodingKeys: String, CodingKey {
        case role,width,height,depth,confidence,intrinsics
        case cameraFrameSeconds = "camera_frame_seconds", depthSensorSeconds = "depth_sensor_seconds"
        case depthTimeSource = "depth_time_source", cameraPose = "camera_pose", viewToDepth = "view_to_depth"
        case viewportRevision = "viewport_revision"
        case sourceFrameId = "source_frame_id",depthSensorId = "depth_sensor_id"
        case depthAgeNanoseconds = "depth_age_nanoseconds",cameraSensorId = "camera_sensor_id"
    }
}

public struct RawCaptureEvidence: Codable, Sendable {
    public var version = 1
    public var measurementId: String
    public var clock: String
    public var resultSeconds: Double
    public var displayedResult: RawCapturedResult
    public var ai: FieldAIProvenance
    public var frames: [RawCapturedFrame] = []
    public var images: [RawCapturedImage]? = nil
    public var aiInference: RawAIInference? = nil
    public var aiEvidenceStatus: String? = nil
    public var aiEvidenceReason: String? = nil
    public var attemptFailureReason: String? = nil
    /// Original scalar inputs, not a degrees/radians round-trip.
    public var scalars: [String: Double] = [:]
    public var replayValue: Double?
    public var replayDelta: Double?
    public var replayStatus = "not_run"
    enum CodingKeys: String, CodingKey {
        case version,clock,ai,frames,scalars,images
        case measurementId = "measurement_id",resultSeconds = "result_seconds",displayedResult = "displayed_result"
        case replayValue = "replay_value",replayDelta = "replay_delta",replayStatus = "replay_status"
        case aiInference = "ai_inference",aiEvidenceStatus = "ai_evidence_status",aiEvidenceReason = "ai_evidence_reason"
        case attemptFailureReason = "attempt_failure_reason"
    }
    public init(measurementId: String,resultSeconds: Double,displayedResult: RawCapturedResult,ai: FieldAIProvenance = .init()) {
        self.measurementId = measurementId; self.clock = "system_uptime_seconds"
        self.resultSeconds = resultSeconds; self.displayedResult = displayedResult; self.ai = ai
    }
    mutating func noteReplay(_ value: Double?) {
        replayValue = value.flatMap { $0.isFinite && $0 > 0 ? $0 : nil }
        replayDelta = displayedResult.value.flatMap { original in replayValue.map { $0-original } }
        replayStatus = replayDelta.map { $0 == 0 ? "pass" : "mismatch" } ?? "no_result"
    }
    public var aiRecordingWarning: String? {
        guard let status = aiEvidenceStatus else { return nil }
        if ["not_requested","no_inference"].contains(status) { return nil }
        if status == "saved" { return aiEvidenceReason.map { "AI input metadata: "+$0 } }
        return "AI input NOT saved: "+status+(aiEvidenceReason.map { " ("+$0+")" } ?? "")
    }
}

public struct RawCapturedImage: Codable, Sendable, Equatable {
    public var role: String
    public var file: RawCapturedFile?
    public var width: Int
    public var height: Int
    public var cameraFrameSeconds: Double?
    public var imageSensorSeconds: Double?
    public var timeSource: String
    public var intrinsics: [Double]
    public var cameraPose: [Double]
    public var viewToImage: [Double]?
    public var coordinateSpace: String
    public var sourceFrameId: String? = nil
    public var imageSensorId: String? = nil
    enum CodingKeys: String, CodingKey {
        case role,file,width,height,intrinsics
        case cameraFrameSeconds = "camera_frame_seconds",imageSensorSeconds = "image_sensor_seconds"
        case timeSource = "time_source",cameraPose = "camera_pose",viewToImage = "view_to_image",coordinateSpace = "coordinate_space"
        case sourceFrameId = "source_frame_id",imageSensorId = "image_sensor_id"
    }
}
public struct RawCameraJPEG: Sendable {
    public var data: Data
    public var metadata: RawCapturedImage
}

public enum RawCaptureEvidenceIO {
    static func attachImage(_ metadata: RawCapturedImage?,data: Data?,file: String?,role: String,directory: URL) throws -> RawCapturedImage? {
        guard var metadata else { return nil }
        metadata.role = role
        if let data {
            if let file {
                let proof = RawCapturedFile(file: file,bytes: data.count,sha256: hash(data))
                _ = try proof.read(in: directory); metadata.file = proof
            } else { metadata.file = try write(data,name: "image_"+role+".jpg",directory: directory) }
        }
        return metadata
    }
    static func hash(_ data: Data) -> String { SHA256.hash(data: data).map { String(format: "%02x",$0) }.joined() }
    static func write(_ data: Data,name: String,directory: URL) throws -> RawCapturedFile {
        let proof = RawCapturedFile(file: name,bytes: data.count,sha256: hash(data))
        try data.write(to: proof.checkedURL(in: directory),options: .atomic)
        return proof
    }
    static func writeFrame(_ f: ARDepthFrame,role: String,directory: URL) throws -> RawCapturedFrame {
        guard f.width > 0, f.height > 0, f.width <= 4096, f.height <= 4096,
              f.depth.count <= 4*1024*1024, f.depth.count == f.width*f.height,
              f.confidence.count == f.depth.count, f.confidence.allSatisfy({ $0 <= 2 }) else {
            throw FieldStudyError.invalid("original raw frame")
        }
        let words = f.depth.map { $0.bitPattern.littleEndian }
        let depthData = words.withUnsafeBytes { Data($0) }
        return try .init(role: role,width: f.width,height: f.height,
            depth: write(depthData,name: "input_"+role+".f32",directory: directory),
            confidence: write(Data(f.confidence),name: "input_"+role+".conf",directory: directory),
            cameraFrameSeconds: f.timestamp.isFinite ? f.timestamp : nil, depthSensorSeconds: nil,
            depthTimeSource: "ar_frame_scene_depth_association",
            cameraPose: RawCaptureMatrix.flat(f.cameraPoseWorld),
            intrinsics: (0..<3).flatMap { c in (0..<3).map { r in Double(f.intrinsics[c,r]) } },
            viewToDepth: f.viewMapping?.flattened,viewportRevision: f.viewportRevision.map(String.init),
            sourceFrameId: "ar_frame_time_bits:"+String(f.timestamp.bitPattern,radix: 16))
    }
    public static func readFrame(_ meta: RawCapturedFrame,directory: URL) throws -> ARDepthFrame {
        guard meta.width > 0,meta.height > 0,meta.width <= 4096,meta.height <= 4096,
              meta.width*meta.height <= 4*1024*1024,meta.cameraPose.count == 16,meta.intrinsics.count == 9,
              (meta.cameraPose+meta.intrinsics).allSatisfy(\.isFinite),
              meta.viewToDepth.map({ $0.count == 6 && $0.allSatisfy(\.isFinite) }) ?? true else {
            throw FieldStudyError.invalid("original frame geometry")
        }
        let count = meta.width*meta.height
        let data = try meta.depth.read(in: directory); let confidence = try meta.confidence.read(in: directory)
        guard data.count == count*4,confidence.count == count,confidence.allSatisfy({ $0 <= 2 }) else {
            throw FieldStudyError.invalid("original frame byte count")
        }
        let depth = data.withUnsafeBytes { raw in (0..<count).map { Float(bitPattern: UInt32(littleEndian: raw.loadUnaligned(fromByteOffset: $0*4,as: UInt32.self))) } }
        let v = meta.intrinsics.map(Float.init)
        let k = simd_float3x3(SIMD3(v[0],v[1],v[2]),SIMD3(v[3],v[4],v[5]),SIMD3(v[6],v[7],v[8]))
        let m = meta.viewToDepth
        return ARDepthFrame(width: meta.width,height: meta.height,depth: depth,confidence: Array(confidence),intrinsics: k,
            cameraPoseWorld: RawCaptureMatrix.pose(meta.cameraPose),timestamp: meta.cameraFrameSeconds ?? 0,
            viewMapping: m.map { .init(a: $0[0],b: $0[1],tx: $0[2],c: $0[3],d: $0[4],ty: $0[5]) },
            viewportRevision: meta.viewportRevision.flatMap(UInt64.init))
    }
}
