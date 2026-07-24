// RAW-CAPTURE REPLAY (developer-mode research) — the recording + on-disk
// store half of the system. Companion: RawCaptureReplay.swift (re-run
// engine).
//
// Purpose: record every raw input a measurement consumes so the owner can
// re-run current/updated estimator code on the stored field data offline
// (algorithm iteration against ground truth), with a per-capture
// reproducibility proof. Each capture is a self-contained bundle in
// Documents/raw-captures/<uuid>/ { manifest.json, depth_0..4.bin,
// rgb_0.jpg }.
//
// CROSS-PLATFORM: the manifest schema is byte-identical (key spelling) to
// the Android sibling — see MANIFEST SCHEMA below. iOS depth format is
// "f32m" (native metres, row-major little-endian); Android is "u16mm".
// Both platforms DERIVE per-pixel confidence from the stored depth via the
// shared valid-range rule (0.001–8.0 m → high, else hole), so no confidence
// buffer is stored and a reconstructed frame is bit-consistent with what
// the estimator re-consumes on replay. That is what lets the self-check be
// an exact reproducibility proof (RANSAC is deterministically seeded, the
// chord math is pure).
//
// COST WHEN OFF: nothing here runs unless the caller (a view model) is
// gated on `developerMode && rawCaptureEnabled`.

import Foundation
import simd
import Common
import Models

// MARK: - Recording context (set by the scan screen)

/// Per-session context the scan screen supplies so bundles can be joined
/// back to the field record. All fields optional-ish — quick-measure
/// captures legitimately carry nulls.
public struct RawCaptureContext: Sendable, Equatable {
    public var mode: String            // "quick" | "cruise"
    public var projectID: String?
    public var plotID: String?
    public var treeNumber: Int?
    public var units: String           // "metric" | "imperial"

    public init(mode: String = "quick",
                projectID: String? = nil,
                plotID: String? = nil,
                treeNumber: Int? = nil,
                units: String = "metric") {
        self.mode = mode
        self.projectID = projectID
        self.plotID = plotID
        self.treeNumber = treeNumber
        self.units = units
    }

    public static let quick = RawCaptureContext()
}

/// GPS fix passed in by the view model (which owns the LocationService
/// read — Sensors can't depend on Positioning).
public struct RawCaptureGPS: Sendable, Equatable {
    public var lat: Double
    public var lon: Double
    public var accM: Double
    public init(lat: Double, lon: Double, accM: Double) {
        self.lat = lat; self.lon = lon; self.accM = accM
    }
}

// MARK: - Manifest schema (LOCKED — key spelling identical to Android)

public struct RawCaptureManifest: Codable, Sendable {
    public var schema: Int
    public var platform: String
    public var device: String
    public var appCommit: String
    public var createdAt: String
    public var kind: String                 // "dbh" | "height"
    public var context: Context
    public var settings: Settings
    public var resultLive: ResultLive
    public var truth: Truth
    public var gps: GPS?
    public var replaySelfcheck: ReplaySelfcheck
    public var dbh: DBHBundle?
    public var height: HeightBundle?

    enum CodingKeys: String, CodingKey {
        case schema, platform, device
        case appCommit = "app_commit"
        case createdAt = "created_at"
        case kind, context, settings
        case resultLive = "result_live"
        case truth, gps
        case replaySelfcheck = "replay_selfcheck"
        case dbh, height
    }

    public struct Context: Codable, Sendable {
        public var mode: String
        public var projectId: String?
        public var plotId: String?
        public var treeNumber: Int?
        enum CodingKeys: String, CodingKey {
            case mode
            case projectId = "project_id"
            case plotId = "plot_id"
            case treeNumber = "tree_number"
        }
        public init(mode: String, projectId: String?, plotId: String?, treeNumber: Int?) {
            self.mode = mode; self.projectId = projectId
            self.plotId = plotId; self.treeNumber = treeNumber
        }
    }

    public struct Settings: Codable, Sendable {
        public var algorithm: String        // "silhouette" | "arc" | "depthBand"
        public var units: String            // "metric" | "imperial"
        public var captureMode: String      // "auto" | "manual"
        public var calibration: Calibration
        enum CodingKeys: String, CodingKey {
            case algorithm, units
            case captureMode = "capture_mode"
            case calibration
        }
        public init(algorithm: String, units: String, captureMode: String, calibration: Calibration) {
            self.algorithm = algorithm; self.units = units
            self.captureMode = captureMode; self.calibration = calibration
        }
    }

    public struct Calibration: Codable, Sendable {
        public var alpha: Double
        public var beta: Double
        public var depthNoiseMm: Double
        public var vioDriftFraction: Double
        enum CodingKeys: String, CodingKey {
            case alpha, beta
            case depthNoiseMm = "depth_noise_mm"
            case vioDriftFraction = "vio_drift_fraction"
        }
        public init(alpha: Double, beta: Double, depthNoiseMm: Double, vioDriftFraction: Double) {
            self.alpha = alpha; self.beta = beta
            self.depthNoiseMm = depthNoiseMm; self.vioDriftFraction = vioDriftFraction
        }
    }

    public struct ResultLive: Codable, Sendable {
        public var value: Double
        public var sigma: Double
        public var tier: String
        public var accepted: Bool
        public var perFrame: [Double]
        enum CodingKeys: String, CodingKey {
            case value, sigma, tier, accepted
            case perFrame = "per_frame"
        }
        public init(value: Double, sigma: Double, tier: String, accepted: Bool, perFrame: [Double]) {
            self.value = value; self.sigma = sigma; self.tier = tier
            self.accepted = accepted; self.perFrame = perFrame
        }
    }

    public struct Truth: Codable, Sendable {
        public var value: Double?
        public var enteredAt: String?
        enum CodingKeys: String, CodingKey {
            case value
            case enteredAt = "entered_at"
        }
        public init(value: Double?, enteredAt: String?) {
            self.value = value; self.enteredAt = enteredAt
        }
    }

    public struct GPS: Codable, Sendable {
        public var lat: Double
        public var lon: Double
        public var accM: Double
        enum CodingKeys: String, CodingKey {
            case lat, lon
            case accM = "acc_m"
        }
        public init(lat: Double, lon: Double, accM: Double) {
            self.lat = lat; self.lon = lon; self.accM = accM
        }
    }

    public struct ReplaySelfcheck: Codable, Sendable {
        public var status: String           // "pass" | "fail"
        public var rerunValue: Double?
        public var delta: Double?
        enum CodingKeys: String, CodingKey {
            case status
            case rerunValue = "rerun_value"
            case delta
        }
        public init(status: String, rerunValue: Double?, delta: Double?) {
            self.status = status; self.rerunValue = rerunValue; self.delta = delta
        }
    }

    // MARK: DBH sub-bundle

    public struct DBHBundle: Codable, Sendable {
        public var frames: [Frame]
        public var bracket: Bracket
        public var rgbFile: String?
        enum CodingKeys: String, CodingKey {
            case frames, bracket
            case rgbFile = "rgb_file"
        }
        public init(frames: [Frame], bracket: Bracket, rgbFile: String?) {
            self.frames = frames; self.bracket = bracket; self.rgbFile = rgbFile
        }

        public struct Frame: Codable, Sendable {
            public var depthFile: String
            public var width: Int
            public var height: Int
            public var format: String       // "f32m"
            public var fx: Double
            public var fy: Double
            public var cx: Double
            public var cy: Double
            public var tapPx: [Double]      // [x, y]
            public var axis: String         // "row" | "col"
            public var cameraPose: [Double] // 16, column-major
            public var viewToDepth: [Double] // 6 (a,b,tx,c,d,ty)
            enum CodingKeys: String, CodingKey {
                case depthFile = "depth_file"
                case width, height, format, fx, fy, cx, cy
                case tapPx = "tap_px"
                case axis
                case cameraPose = "camera_pose"
                case viewToDepth = "view_to_depth"
            }
            public init(depthFile: String, width: Int, height: Int, format: String,
                        fx: Double, fy: Double, cx: Double, cy: Double,
                        tapPx: [Double], axis: String, cameraPose: [Double], viewToDepth: [Double]) {
                self.depthFile = depthFile; self.width = width; self.height = height
                self.format = format; self.fx = fx; self.fy = fy; self.cx = cx; self.cy = cy
                self.tapPx = tapPx; self.axis = axis
                self.cameraPose = cameraPose; self.viewToDepth = viewToDepth
            }
        }

        public struct Bracket: Codable, Sendable {
            public var enabled: Bool
            public var left: Double
            public var right: Double
            public init(enabled: Bool, left: Double, right: Double) {
                self.enabled = enabled; self.left = left; self.right = right
            }
        }
    }

    // MARK: Height sub-bundle

    public struct HeightBundle: Codable, Sendable {
        public var anchor: Anchor
        public var base: Aim
        public var top: Aim
        public var dHM: Double
        public var poseSamples: [PoseSample]
        enum CodingKeys: String, CodingKey {
            case anchor, base, top
            case dHM = "d_h_m"
            case poseSamples = "pose_samples"
        }
        public init(anchor: Anchor, base: Aim, top: Aim, dHM: Double, poseSamples: [PoseSample]) {
            self.anchor = anchor; self.base = base; self.top = top
            self.dHM = dHM; self.poseSamples = poseSamples
        }

        public struct Anchor: Codable, Sendable {
            public var world: [Double]      // [x, y, z]
            public var hitType: String
            public var distanceM: Double
            public var cameraPose: [Double] // 16
            enum CodingKeys: String, CodingKey {
                case world
                case hitType = "hit_type"
                case distanceM = "distance_m"
                case cameraPose = "camera_pose"
            }
            public init(world: [Double], hitType: String, distanceM: Double, cameraPose: [Double]) {
                self.world = world; self.hitType = hitType
                self.distanceM = distanceM; self.cameraPose = cameraPose
            }
        }

        public struct Aim: Codable, Sendable {
            public var pitchDeg: Double
            public var cameraPose: [Double] // 16
            enum CodingKeys: String, CodingKey {
                case pitchDeg = "pitch_deg"
                case cameraPose = "camera_pose"
            }
            public init(pitchDeg: Double, cameraPose: [Double]) {
                self.pitchDeg = pitchDeg; self.cameraPose = cameraPose
            }
        }

        public struct PoseSample: Codable, Sendable {
            public var tMs: Int
            public var pose: [Double]       // 16
            enum CodingKeys: String, CodingKey {
                case tMs = "t_ms"
                case pose
            }
            public init(tMs: Int, pose: [Double]) {
                self.tMs = tMs; self.pose = pose
            }
        }
    }
}

// MARK: - Bundle summary (for the list UI)

public struct RawCaptureSummary: Identifiable, Sendable {
    public let id: String                   // uuid dir name
    public let manifest: RawCaptureManifest
    public var kind: String { manifest.kind }
    public init(id: String, manifest: RawCaptureManifest) {
        self.id = id; self.manifest = manifest
    }
}

// MARK: - On-disk store

public enum RawCaptureStore {

    /// Documents/raw-captures — the whole bundle tree.
    public static var rootDirectory: URL {
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("raw-captures", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    public static func bundleDirectory(id: String) -> URL {
        rootDirectory.appendingPathComponent(id, isDirectory: true)
    }

    static func manifestURL(id: String) -> URL {
        bundleDirectory(id: id).appendingPathComponent("manifest.json")
    }

    // MARK: Encoder / decoder — deterministic, human-diffable manifests.

    static func encoder() -> JSONEncoder {
        let e = JSONEncoder()
        e.outputFormatting = [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]
        return e
    }

    static func decoder() -> JSONDecoder { JSONDecoder() }

    // MARK: Listing / size / delete

    /// Every bundle on disk, newest first (by manifest `created_at`).
    /// Bundles whose manifest can't be parsed are skipped.
    public static func list() -> [RawCaptureSummary] {
        let fm = FileManager.default
        guard let entries = try? fm.contentsOfDirectory(
            at: rootDirectory,
            includingPropertiesForKeys: [.isDirectoryKey],
            options: [.skipsHiddenFiles])
        else { return [] }
        var out: [RawCaptureSummary] = []
        for dir in entries {
            var isDir: ObjCBool = false
            guard fm.fileExists(atPath: dir.path, isDirectory: &isDir), isDir.boolValue
            else { continue }
            let mURL = dir.appendingPathComponent("manifest.json")
            guard let data = try? Data(contentsOf: mURL),
                  let m = try? decoder().decode(RawCaptureManifest.self, from: data)
            else { continue }
            out.append(RawCaptureSummary(id: dir.lastPathComponent, manifest: m))
        }
        out.sort { $0.manifest.createdAt > $1.manifest.createdAt }
        return out
    }

    public static func count() -> Int { list().count }

    /// Total bytes across every bundle file.
    public static func totalSizeBytes() -> Int64 {
        let fm = FileManager.default
        guard let en = fm.enumerator(at: rootDirectory,
                                     includingPropertiesForKeys: [.fileSizeKey]) else { return 0 }
        var total: Int64 = 0
        for case let url as URL in en {
            if let sz = try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize {
                total += Int64(sz)
            }
        }
        return total
    }

    public static func delete(id: String) {
        try? FileManager.default.removeItem(at: bundleDirectory(id: id))
    }

    public static func clearAll() {
        let fm = FileManager.default
        if let entries = try? fm.contentsOfDirectory(at: rootDirectory,
                                                     includingPropertiesForKeys: nil) {
            for e in entries { try? fm.removeItem(at: e) }
        }
    }

    // MARK: Load / manifest patch

    public static func loadManifest(id: String) -> RawCaptureManifest? {
        guard let data = try? Data(contentsOf: manifestURL(id: id)) else { return nil }
        return try? decoder().decode(RawCaptureManifest.self, from: data)
    }

    static func writeManifest(_ m: RawCaptureManifest, id: String) {
        guard let data = try? encoder().encode(m) else { return }
        try? data.write(to: manifestURL(id: id))
    }

    /// Set (or clear) the ground-truth value on a stored bundle. Used by the
    /// scan screen at Accept (when the dev "True" field was filled) and by
    /// the replay UI's truth-entry field afterwards.
    @discardableResult
    public static func updateTruth(id: String, value: Double?) -> Bool {
        guard var m = loadManifest(id: id) else { return false }
        m.truth = RawCaptureManifest.Truth(
            value: value,
            enteredAt: value == nil ? nil : Self.isoNow())
        writeManifest(m, id: id)
        return true
    }

    // MARK: Export ZIP

    /// Zip the entire raw-captures tree (stored, uncompressed) so the whole
    /// research corpus can leave the device via the share sheet. Returns the
    /// written .zip URL (in the temp dir) or nil when empty / on failure.
    public static func exportZIP() -> URL? {
        let fm = FileManager.default
        guard let en = fm.enumerator(at: rootDirectory, includingPropertiesForKeys: nil)
        else { return nil }
        var files: [(String, Data)] = []
        let rootPath = rootDirectory.path
        for case let url as URL in en {
            var isDir: ObjCBool = false
            guard fm.fileExists(atPath: url.path, isDirectory: &isDir), !isDir.boolValue
            else { continue }
            guard let data = try? Data(contentsOf: url) else { continue }
            // Relative path under raw-captures/, forward slashes for ZIP.
            var rel = url.path
            if rel.hasPrefix(rootPath) { rel.removeFirst(rootPath.count) }
            rel = rel.drop(while: { $0 == "/" }).description
            files.append(("raw-captures/" + rel, data))
        }
        guard !files.isEmpty else { return nil }
        let archive = ZipWriter.storedArchive(files: files)
        let out = fm.temporaryDirectory
            .appendingPathComponent("forestix-raw-captures-\(Int(Date().timeIntervalSince1970)).zip")
        do { try archive.write(to: out); return out } catch { return nil }
    }

    // MARK: - Environment helpers

    static func isoNow() -> String {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f.string(from: Date())
    }

    static func deviceModel() -> String {
        var sysinfo = utsname()
        uname(&sysinfo)
        let machine = withUnsafePointer(to: &sysinfo.machine) {
            $0.withMemoryRebound(to: CChar.self, capacity: 1) { String(cString: $0) }
        }
        return machine.isEmpty ? "Apple" : machine
    }

    static func appCommit() -> String {
        let info = Bundle.main.infoDictionary
        let short = info?["CFBundleShortVersionString"] as? String ?? "?"
        let build = info?["CFBundleVersion"] as? String ?? "?"
        return "\(short) (\(build))"
    }

    // MARK: - Byte (de)serialization of an f32 depth buffer

    static func depthFileName(_ index: Int) -> String { "depth_\(index).bin" }

    /// Row-major little-endian f32 metres (Apple platforms are LE).
    static func writeDepth(_ depth: [Float], to url: URL) {
        let data = depth.withUnsafeBufferPointer { Data(buffer: $0) }
        try? data.write(to: url)
    }

    static func readDepth(url: URL, count: Int) -> [Float]? {
        guard count > 0, let data = try? Data(contentsOf: url) else { return nil }
        guard data.count >= count * MemoryLayout<Float>.stride else { return nil }
        return data.withUnsafeBytes { raw -> [Float] in
            Array(raw.bindMemory(to: Float.self).prefix(count))
        }
    }
}

// MARK: - Frame confidence derivation (SHARED with Android's ingestMmInto)

public enum RawCaptureFrame {

    /// Valid depth window for confidence derivation — mirrors Android
    /// `ingestMmInto` (1..8000 mm). Pixels inside → confidence 2 (high),
    /// outside → 0 (hole). The estimators only gate on `confidence >= 1`,
    /// so this canonical model selects exactly the in-range returns.
    public static func validDepth(_ d: Float) -> Bool { d >= 0.001 && d <= 8.0 }

    /// Rebuild a frame whose confidence is DERIVED from its depth via the
    /// shared rule. Used both when computing the canonical `result_live`
    /// and when reconstructing a frame on replay, so the two agree exactly.
    public static func canonicalized(_ frame: ARDepthFrame) -> ARDepthFrame {
        var conf = [UInt8](repeating: 0, count: frame.depth.count)
        for i in 0..<frame.depth.count where validDepth(frame.depth[i]) { conf[i] = 2 }
        return ARDepthFrame(
            width: frame.width,
            height: frame.height,
            depth: frame.depth,
            confidence: conf,
            intrinsics: frame.intrinsics,
            cameraPoseWorld: frame.cameraPoseWorld,
            timestamp: frame.timestamp)
    }

    /// Reconstruct a canonical frame from stored depth bytes + metadata.
    public static func reconstruct(from meta: RawCaptureManifest.DBHBundle.Frame,
                                   depth: [Float]) -> ARDepthFrame {
        var conf = [UInt8](repeating: 0, count: depth.count)
        for i in 0..<depth.count where validDepth(depth[i]) { conf[i] = 2 }
        return ARDepthFrame(
            width: meta.width,
            height: meta.height,
            depth: depth,
            confidence: conf,
            intrinsics: RawCaptureMatrix.intrinsics(fx: meta.fx, fy: meta.fy,
                                                    cx: meta.cx, cy: meta.cy),
            cameraPoseWorld: RawCaptureMatrix.pose(meta.cameraPose),
            timestamp: 0)
    }
}

// MARK: - Matrix <-> flat array helpers (column-major, matching ARKit)

public enum RawCaptureMatrix {

    public static func flat(_ m: simd_float4x4) -> [Double] {
        var out = [Double](); out.reserveCapacity(16)
        for c in 0..<4 {
            let col = m[c]
            out.append(Double(col.x)); out.append(Double(col.y))
            out.append(Double(col.z)); out.append(Double(col.w))
        }
        return out
    }

    public static func pose(_ a: [Double]) -> simd_float4x4 {
        guard a.count == 16 else { return matrix_identity_float4x4 }
        return simd_float4x4(
            SIMD4<Float>(Float(a[0]), Float(a[1]), Float(a[2]), Float(a[3])),
            SIMD4<Float>(Float(a[4]), Float(a[5]), Float(a[6]), Float(a[7])),
            SIMD4<Float>(Float(a[8]), Float(a[9]), Float(a[10]), Float(a[11])),
            SIMD4<Float>(Float(a[12]), Float(a[13]), Float(a[14]), Float(a[15])))
    }

    public static func intrinsics(fx: Double, fy: Double, cx: Double, cy: Double) -> simd_float3x3 {
        // Column-major: col0.x = fx, col1.y = fy, col2 = (cx, cy, 1).
        // BackProjection reads K[0,0]=fx, K[1,1]=fy, K[2,0]=cx, K[2,1]=cy.
        simd_float3x3(
            SIMD3<Float>(Float(fx), 0, 0),
            SIMD3<Float>(0, Float(fy), 0),
            SIMD3<Float>(Float(cx), Float(cy), 1))
    }

    /// Translation column of a column-major 4x4.
    public static func translation(_ a: [Double]) -> SIMD3<Float> {
        guard a.count == 16 else { return .zero }
        return SIMD3<Float>(Float(a[12]), Float(a[13]), Float(a[14]))
    }
}
