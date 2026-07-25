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
        /// LEGACY key, kept so an existing corpus still parses. It meant two
        /// different things on the two platforms ("fit not red" on iOS,
        /// "operator accepted" on Android), which is exactly why the two
        /// explicit booleans below now exist. Going forward BOTH platforms
        /// write it as a mirror of `operatorAccepted`, so the pooled corpus
        /// has one meaning for it.
        public var accepted: Bool
        /// RECORD-TIME quality gate: the live fit was not red.
        public var tierOk: Bool
        /// The cruiser tapped Accept on this capture. False at record time;
        /// set later by `RawCaptureStore.markAccepted`.
        public var operatorAccepted: Bool
        /// Depth frames stored in the bundle (DBH: burst frames; height:
        /// base/top aim frames, 0–2).
        public var frameCount: Int
        public var perFrame: [Double]
        enum CodingKeys: String, CodingKey {
            case value, sigma, tier, accepted
            case tierOk = "tier_ok"
            case operatorAccepted = "operator_accepted"
            case frameCount = "frame_count"
            case perFrame = "per_frame"
        }
        public init(value: Double, sigma: Double, tier: String, accepted: Bool,
                    tierOk: Bool? = nil, operatorAccepted: Bool = false,
                    frameCount: Int = 0, perFrame: [Double]) {
            self.value = value; self.sigma = sigma; self.tier = tier
            self.accepted = accepted
            self.tierOk = tierOk ?? accepted
            self.operatorAccepted = operatorAccepted
            self.frameCount = frameCount
            self.perFrame = perFrame
        }

        /// Hand-rolled decode so pre-existing bundles (which have `accepted`
        /// but none of the new keys) still load: `tier_ok` falls back to the
        /// legacy `accepted`, `operator_accepted` to false, `frame_count` to
        /// the per-frame count.
        public init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            value = try c.decode(Double.self, forKey: .value)
            sigma = try c.decode(Double.self, forKey: .sigma)
            tier = try c.decode(String.self, forKey: .tier)
            accepted = try c.decodeIfPresent(Bool.self, forKey: .accepted) ?? false
            perFrame = try c.decodeIfPresent([Double].self, forKey: .perFrame) ?? []
            tierOk = try c.decodeIfPresent(Bool.self, forKey: .tierOk) ?? accepted
            operatorAccepted =
                try c.decodeIfPresent(Bool.self, forKey: .operatorAccepted) ?? false
            frameCount =
                try c.decodeIfPresent(Int.self, forKey: .frameCount) ?? perFrame.count
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
            // Schema 2 (LOCKED, key-identical to Android): the depth frame +
            // reference RGB grabbed at THIS aim (base or top) tap, so a height
            // bundle can re-run depth-based height algorithms — not just the
            // tangent method. All optional: an old (schema 1) height capture
            // has none of these and still re-runs the tangent method exactly
            // as before. `format` is platform-specific ("f32m" iOS / "u16mm"
            // Android); every other key is byte-identical across platforms.
            public var depthFile: String?   // "depth_base.bin" / "depth_top.bin"
            public var rgbFile: String?     // "rgb_base.jpg" / "rgb_top.jpg"
            public var width: Int?
            public var height: Int?
            public var format: String?      // "f32m"
            public var fx: Double?
            public var fy: Double?
            public var cx: Double?
            public var cy: Double?
            enum CodingKeys: String, CodingKey {
                case pitchDeg = "pitch_deg"
                case cameraPose = "camera_pose"
                case depthFile = "depth_file"
                case rgbFile = "rgb_file"
                case width, height, format, fx, fy, cx, cy
            }
            public init(pitchDeg: Double, cameraPose: [Double],
                        depthFile: String? = nil, rgbFile: String? = nil,
                        width: Int? = nil, height: Int? = nil, format: String? = nil,
                        fx: Double? = nil, fy: Double? = nil,
                        cx: Double? = nil, cy: Double? = nil) {
                self.pitchDeg = pitchDeg; self.cameraPose = cameraPose
                self.depthFile = depthFile; self.rgbFile = rgbFile
                self.width = width; self.height = height; self.format = format
                self.fx = fx; self.fy = fy; self.cx = cx; self.cy = cy
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

// MARK: - Record outcome (what the scan screen shows after a capture)

/// The result of one raw-capture write attempt. A capture either RECORDS AND
/// SAYS SO or FAILS LOUDLY — the screens render this verbatim, so a failed
/// capture can never look like a saved one.
public enum RawCaptureOutcome: Sendable, Equatable {
    case saved(id: String, frames: Int)
    case failed(reason: String)

    public var isSaved: Bool { if case .saved = self { return true }; return false }

    public var id: String? {
        if case .saved(let id, _) = self { return id }
        return nil
    }
}

/// Errors surfaced (never swallowed) by the bundle writers.
public enum RawCaptureError: Error, LocalizedError, Sendable {
    case lowStorage(freeBytes: Int64)
    case writeFailed(String)
    case bundleMissing(String)

    /// Reasons are shown verbatim after "NOT SAVED — " on the scan screens,
    /// so they are phrased as a cause, not a sentence (Android wording).
    public var errorDescription: String? {
        switch self {
        case .lowStorage(let free):
            let s = ByteCountFormatter.string(fromByteCount: free, countStyle: .file)
            return "Low storage (\(s) free)"
        case .writeFailed(let m):   return m
        case .bundleMissing(let m): return m
        }
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

    // MARK: Free space

    /// Head-room a capture requires before it will even start writing. A
    /// bundle is ~1 MB, but a phone that is this close to full will start
    /// failing writes mid-bundle and leave half-written captures behind.
    public static let minimumFreeBytes: Int64 = 2 * 1024 * 1024 * 1024   // 2 GB

    /// Bytes available for new data on the Documents volume, or nil when the
    /// system won't tell us.
    public static func freeSpaceBytes() -> Int64? {
        let values = try? rootDirectory.resourceValues(
            forKeys: [.volumeAvailableCapacityForImportantUsageKey])
        if let v = values?.volumeAvailableCapacityForImportantUsage { return Int64(v) }
        let attrs = try? FileManager.default.attributesOfFileSystem(
            forPath: rootDirectory.path)
        return (attrs?[.systemFreeSize] as? NSNumber)?.int64Value
    }

    public static func freeSpaceText() -> String {
        guard let free = freeSpaceBytes() else { return "free space unknown" }
        return ByteCountFormatter.string(fromByteCount: free, countStyle: .file) + " free"
    }

    /// True when the device has less than `minimumFreeBytes` available.
    public static func isStorageLow() -> Bool {
        guard let free = freeSpaceBytes() else { return false }
        return free < minimumFreeBytes
    }

    /// Throws `.lowStorage` when a capture must not start. Called by the
    /// recorder before the first byte is written.
    static func assertStorageAvailable() throws {
        guard let free = freeSpaceBytes(), free < minimumFreeBytes else { return }
        throw RawCaptureError.lowStorage(freeBytes: free)
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

    /// WHAT IS ON DISK vs WHAT CAN BE READ.
    ///
    /// `list()` skips any bundle whose manifest.json is missing or won't
    /// decode. Such a capture used to be absent from the total, from every
    /// skip counter, from the Settings count and from the sweep footers whose
    /// arithmetic still "balanced" — a whole vanishing class of field data
    /// that nothing on screen could reveal. Counting the bundle DIRECTORIES
    /// straight off the filesystem makes the gap explicit:
    /// `unparseable = directories − parsed`.
    ///
    /// CROSS-PLATFORM: same definition and same wording as the Android sibling.
    public struct Inventory: Sendable, Equatable {
        /// Bundle directories on disk that claim to be captures.
        public let directories: Int
        /// Directories whose manifest decoded (== `list().count`).
        public let parsed: Int
        /// Captures that exist on disk but cannot be read. NEVER hidden.
        public var unparseable: Int { max(0, directories - parsed) }
        public init(directories: Int, parsed: Int) {
            self.directories = directories; self.parsed = parsed
        }
    }

    /// `list()` plus the on-disk accounting it is measured against.
    public struct Listing: Sendable {
        public let summaries: [RawCaptureSummary]
        public let inventory: Inventory
    }

    /// A directory holding ONLY `pending_patch.json` is a leftover: operator
    /// input was parked for a capture whose recorder then aborted, so nothing
    /// will ever complete it. `savePendingPatch` no longer creates bundle
    /// directories, so new ones can't appear — but a corpus recorded by an
    /// earlier build can hold them, and left alone they are swept into the
    /// export as empty bundles and counted as unreadable captures.
    ///
    /// They are only reaped past this grace window, so a bundle whose recorder
    /// is still between `createDirectory` and its first payload write is never
    /// touched (a bundle write finishes in seconds).
    static let orphanReapGraceSeconds: TimeInterval = 300

    /// Every readable bundle (newest first, by manifest `created_at`) together
    /// with the count of bundle directories actually present.
    ///
    /// READ-ONLY: sidecar-only leftovers are excluded from the count but NOT
    /// deleted here, because this runs inside SwiftUI body evaluation (the
    /// Settings summary) and a view body must not mutate the filesystem.
    /// Deletion is `reapOrphanBundles()`, called at explicit refresh points.
    public static func listing() -> Listing {
        let fm = FileManager.default
        guard let entries = try? fm.contentsOfDirectory(
            at: rootDirectory,
            includingPropertiesForKeys: [.isDirectoryKey],
            options: [.skipsHiddenFiles])
        else { return Listing(summaries: [], inventory: Inventory(directories: 0, parsed: 0)) }
        var out: [RawCaptureSummary] = []
        var directories = 0
        for dir in entries {
            var isDir: ObjCBool = false
            guard fm.fileExists(atPath: dir.path, isDirectory: &isDir), isDir.boolValue
            else { continue }
            let mURL = dir.appendingPathComponent("manifest.json")
            let hasManifest = fm.fileExists(atPath: mURL.path)
            // Only a manifest-less directory can be a sidecar leftover, so the
            // full contents scan stays off the common path.
            if !hasManifest, isSidecarOnly(dir: dir) { continue }
            directories += 1
            guard hasManifest,
                  let data = try? Data(contentsOf: mURL),
                  let m = try? decoder().decode(RawCaptureManifest.self, from: data)
            else { continue }        // counted in `directories`, reported as unparseable
            out.append(RawCaptureSummary(id: dir.lastPathComponent, manifest: m))
        }
        out.sort { $0.manifest.createdAt > $1.manifest.createdAt }
        return Listing(summaries: out,
                       inventory: Inventory(directories: directories, parsed: out.count))
    }

    public static func list() -> [RawCaptureSummary] { listing().summaries }

    /// On-disk accounting on its own (Settings summary, console footer).
    public static func inventory() -> Inventory { listing().inventory }

    /// True when the directory holds nothing but a pending sidecar and has sat
    /// that way past the grace window — a bundle whose recorder aborted. A
    /// directory still inside the window is treated as a live capture: it is
    /// counted (so it can't disappear from the accounting) and never removed.
    private static func isSidecarOnly(dir: URL) -> Bool {
        let fm = FileManager.default
        guard let names = try? fm.contentsOfDirectory(atPath: dir.path) else { return false }
        let payload = names.filter { $0 != "pending_patch.json" && !$0.hasPrefix(".") }
        guard payload.isEmpty, names.contains("pending_patch.json") else { return false }
        let modified = (try? dir.resourceValues(forKeys: [.contentModificationDateKey]))?
            .contentModificationDate ?? Date()
        return Date().timeIntervalSince(modified) > orphanReapGraceSeconds
    }

    /// Delete sidecar-only leftovers so they aren't swept into an export as
    /// empty bundles. Called where the operator explicitly refreshes the
    /// raw-captures console and before an export — never from a view body.
    /// Returns how many were removed.
    @discardableResult
    public static func reapOrphanBundles() -> Int {
        let fm = FileManager.default
        guard let entries = try? fm.contentsOfDirectory(
            at: rootDirectory,
            includingPropertiesForKeys: [.isDirectoryKey],
            options: [.skipsHiddenFiles])
        else { return 0 }
        var removed = 0
        for dir in entries {
            var isDir: ObjCBool = false
            guard fm.fileExists(atPath: dir.path, isDirectory: &isDir), isDir.boolValue,
                  !fm.fileExists(atPath: dir.appendingPathComponent("manifest.json").path),
                  isSidecarOnly(dir: dir)
            else { continue }
            if (try? fm.removeItem(at: dir)) != nil { removed += 1 }
        }
        return removed
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

    /// Write the manifest. THROWS — a swallowed failure here leaves a
    /// depth-only bundle that looks recorded but can never be replayed.
    static func writeManifest(_ m: RawCaptureManifest, id: String) throws {
        do {
            let data = try encoder().encode(m)
            try data.write(to: manifestURL(id: id), options: .atomic)
        } catch {
            throw RawCaptureError.writeFailed("manifest.json — \(error.localizedDescription)")
        }
    }

    // MARK: Ground truth + Accept (never silently dropped)

    /// Serialises "the recorder finishes the manifest" against "the operator
    /// enters a truth / taps Accept". A value entered while the bundle is
    /// still being written can no longer land in the gap between the two.
    private static let patchLock = NSLock()

    /// Sidecar carrying operator input that arrived BEFORE the manifest
    /// existed. The recorder folds it in under the same lock, then deletes it.
    /// Bundle-local bookkeeping — not part of the cross-platform manifest.
    static func pendingPatchURL(id: String) -> URL {
        bundleDirectory(id: id).appendingPathComponent("pending_patch.json")
    }

    struct PendingPatch: Codable, Sendable {
        var truth: RawCaptureManifest.Truth?
        var operatorAccepted: Bool
        enum CodingKeys: String, CodingKey {
            case truth
            case operatorAccepted = "operator_accepted"
        }
        init(truth: RawCaptureManifest.Truth? = nil, operatorAccepted: Bool = false) {
            self.truth = truth; self.operatorAccepted = operatorAccepted
        }
    }

    /// Caller must already hold `patchLock`.
    private static func loadPendingPatch(id: String) -> PendingPatch? {
        guard let data = try? Data(contentsOf: pendingPatchURL(id: id)) else { return nil }
        return try? decoder().decode(PendingPatch.self, from: data)
    }

    /// Merge whatever is parked in the sidecar into `m` before it is written,
    /// so no writer can clobber operator input it didn't know about.
    /// Caller must already hold `patchLock`.
    private static func absorbPendingPatch(into m: inout RawCaptureManifest, id: String) {
        guard let patch = loadPendingPatch(id: id) else { return }
        if let t = patch.truth, t.value != nil { m.truth = t }
        if patch.operatorAccepted {
            m.resultLive.operatorAccepted = true
            m.resultLive.accepted = true
        }
    }

    /// Caller must already hold `patchLock`.
    ///
    /// The bundle directory is NEVER created here. Creating it just to park a
    /// sidecar for an id whose recorder had already aborted left an empty
    /// zombie bundle that nothing reaps and the export sweeps up. The recorder
    /// creates the directory as its FIRST action, so the only time this throws
    /// is when the capture never started — which is exactly when the caller
    /// must be told the value is not durable instead of being handed a
    /// success it can clear the field on.
    private static func savePendingPatch(_ p: PendingPatch, id: String) throws {
        var isDir: ObjCBool = false
        guard FileManager.default.fileExists(atPath: bundleDirectory(id: id).path,
                                             isDirectory: &isDir), isDir.boolValue
        else { throw RawCaptureError.bundleMissing("capture bundle not on disk") }
        let data = try encoder().encode(p)
        try data.write(to: pendingPatchURL(id: id), options: .atomic)
    }

    /// Tear down a bundle whose write FAILED, and say whether operator input
    /// died with it. The writer used to `removeItem(at: dir)` outright, which
    /// destroyed a `pending_patch.json` the operator had already been told was
    /// queued — the pairing vanished with nothing on screen to show for it.
    /// Losing the pairing is acceptable; losing it INVISIBLY is not, so the
    /// returned note is appended to the NOT SAVED reason the scan screen
    /// renders verbatim.
    ///
    /// CROSS-PLATFORM: wording matched to the Android sibling.
    static func discardFailedBundle(id: String) -> String {
        patchLock.lock()
        defer { patchLock.unlock() }
        let patch = loadPendingPatch(id: id)
        let hadTruth = patch?.truth?.value != nil
        try? FileManager.default.removeItem(at: bundleDirectory(id: id))
        return hadTruth ? " · typed ground truth discarded — re-enter it" : ""
    }

    /// What happened to a typed truth.
    ///
    /// `applied` — the value IS in a manifest on disk. This is the only state
    ///   that may clear the operator's input.
    /// `pending` — the value is parked in the bundle's sidecar for the
    ///   in-flight recorder to fold in. Accepted, but NOT yet durable: if that
    ///   recorder then fails, the bundle is torn down and the sidecar with it.
    ///   `pending` used to report `isDurable`, so the field was cleared the
    ///   instant the value was merely QUEUED and a later write failure took
    ///   the hand measurement with it, silently. Callers keep the text and
    ///   show a queued state until the capture itself reports SAVED.
    /// `failed`  — nothing was written. Keep the text and warn.
    public enum TruthOutcome: Sendable, Equatable {
        case applied
        case pending
        case failed(String)

        /// In a manifest — the ONLY state that may clear the input field.
        public var isDurable: Bool { self == .applied }
        /// Accepted for an in-flight write; the input stays on screen.
        public var isQueued: Bool { self == .pending }
    }

    /// Attach a ground-truth value to a bundle. Safe at ANY point relative to
    /// the async bundle write: an existing manifest is patched, otherwise the
    /// value is parked in a sidecar the recorder picks up. Callers MUST NOT
    /// clear their input field unless the returned outcome `isDurable`.
    public static func applyTruth(id: String, value: Double) -> TruthOutcome {
        patchLock.lock()
        defer { patchLock.unlock() }
        let truth = RawCaptureManifest.Truth(value: value, enteredAt: Self.isoNow())
        if var m = loadManifest(id: id) {
            // Carry over anything already parked (e.g. an Accept that beat the
            // recorder) so writing the manifest can't drop it.
            absorbPendingPatch(into: &m, id: id)
            m.truth = truth
            do {
                try writeManifest(m, id: id)
                try? FileManager.default.removeItem(at: pendingPatchURL(id: id))
                return .applied
            } catch {
                return .failed(error.localizedDescription)
            }
        }
        // Manifest not on disk yet — park the value beside it.
        do {
            var patch = loadPendingPatch(id: id) ?? PendingPatch()
            patch.truth = truth
            try savePendingPatch(patch, id: id)
            return .pending
        } catch {
            return .failed(error.localizedDescription)
        }
    }

    /// Clear a stored truth. EXPLICIT ONLY — an empty or unparseable input
    /// must never reach this (that is how a good truth got wiped).
    @discardableResult
    public static func clearTruth(id: String) -> TruthOutcome {
        patchLock.lock()
        defer { patchLock.unlock() }
        if var patch = loadPendingPatch(id: id) {
            patch.truth = nil
            try? savePendingPatch(patch, id: id)
        }
        guard var m = loadManifest(id: id) else { return .failed("bundle not found") }
        m.truth = RawCaptureManifest.Truth(value: nil, enteredAt: nil)
        do {
            try writeManifest(m, id: id)
            return .applied
        } catch {
            return .failed(error.localizedDescription)
        }
    }

    /// The recorder's LAST write: folds in any operator input that landed
    /// while the bundle was still being written — a truth that patched the
    /// intermediate manifest, and/or a truth/Accept parked in the sidecar —
    /// then commits, all under the patch lock. In either interleaving nothing
    /// the operator entered is lost.
    static func commitManifestFoldingTruth(_ m: RawCaptureManifest, id: String) throws {
        patchLock.lock()
        defer { patchLock.unlock() }
        var manifest = m
        // (a) operator input that patched the intermediate manifest on disk
        if let onDisk = loadManifest(id: id) {
            if manifest.truth.value == nil, onDisk.truth.value != nil {
                manifest.truth = onDisk.truth
            }
            if onDisk.resultLive.operatorAccepted {
                manifest.resultLive.operatorAccepted = true
                manifest.resultLive.accepted = true
            }
        }
        // (b) operator input parked before any manifest existed
        if let patch = loadPendingPatch(id: id) {
            if let t = patch.truth, t.value != nil { manifest.truth = t }
            if patch.operatorAccepted {
                manifest.resultLive.operatorAccepted = true
                manifest.resultLive.accepted = true
            }
        }
        try writeManifest(manifest, id: id)
        try? FileManager.default.removeItem(at: pendingPatchURL(id: id))
    }

    // (The old `updateTruth(id:value:)` is deliberately gone: passing nil
    // meant "clear", so an unparseable field cleared a stored truth on the
    // way past. Callers now say which they mean — `applyTruth` or
    // `clearTruth` — and get a durability outcome back.)

    // MARK: Operator accept

    /// Mark a stored bundle as accepted BY THE CRUISER — `operator_accepted`,
    /// explicitly distinct from the record-time `tier_ok` quality gate. Like
    /// the truth it survives being called before the writer has finished.
    @discardableResult
    public static func markAccepted(id: String) -> Bool {
        patchLock.lock()
        defer { patchLock.unlock() }
        if var m = loadManifest(id: id) {
            absorbPendingPatch(into: &m, id: id)
            m.resultLive.operatorAccepted = true
            m.resultLive.accepted = true        // legacy mirror (older readers)
            do {
                try writeManifest(m, id: id)
                try? FileManager.default.removeItem(at: pendingPatchURL(id: id))
                return true
            } catch {
                return false
            }
        }
        var patch = loadPendingPatch(id: id) ?? PendingPatch()
        patch.operatorAccepted = true
        do {
            try savePendingPatch(patch, id: id)
            return true
        } catch {
            return false
        }
    }

    // MARK: Export ZIP

    /// Zip the entire raw-captures tree (stored, uncompressed) so the whole
    /// research corpus can leave the device via the share sheet.
    ///
    /// STREAMED, entry by entry, straight to a temp file: the previous version
    /// read every bundle into an array of `Data` and then concatenated a
    /// SECOND full copy into one archive `Data` — on a real corpus that is
    /// gigabytes twice over and the app is jetsam-killed, stranding the field
    /// data on the phone. Peak memory here is one 1 MiB chunk.
    ///
    /// Off the main actor (`nonisolated`, callers use a detached task) and
    /// THROWING — an export that fails must say so, not return silently.
    public static func exportZIPStreaming() throws -> URL {
        let fm = FileManager.default
        // Sidecar-only leftovers are not captures; keep them out of the corpus
        // instead of shipping empty bundles to the analyst.
        reapOrphanBundles()
        let rootPath = rootDirectory.path
        guard let en = fm.enumerator(at: rootDirectory, includingPropertiesForKeys: nil)
        else { throw RawCaptureError.bundleMissing("raw-captures folder unreadable") }

        var files: [(name: String, url: URL)] = []
        for case let url as URL in en {
            var isDir: ObjCBool = false
            guard fm.fileExists(atPath: url.path, isDirectory: &isDir), !isDir.boolValue
            else { continue }
            var rel = url.path
            if rel.hasPrefix(rootPath) { rel.removeFirst(rootPath.count) }
            rel = rel.drop(while: { $0 == "/" }).description
            files.append((name: "raw-captures/" + rel, url: url))
        }
        guard !files.isEmpty else {
            throw RawCaptureError.bundleMissing("no captures to export")
        }

        let out = fm.temporaryDirectory
            .appendingPathComponent("forestix-raw-captures-\(Int(Date().timeIntervalSince1970)).zip")
        let writer = try ZipStreamWriter(url: out)
        do {
            for f in files { try writer.add(name: f.name, fileURL: f.url) }
            try writer.finish()
        } catch {
            writer.cancel()
            try? fm.removeItem(at: out)
            throw error
        }
        return out
    }

    // (The old nil-returning `exportZIP()` is deliberately gone: a corpus
    // export that fails must say why, not hand back a nil the UI shrugs at.)

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

    /// Row-major little-endian f32 metres (Apple platforms are LE). THROWS —
    /// a swallowed depth-write failure leaves a manifest pointing at bytes
    /// that aren't there, which only shows up months later on replay.
    static func writeDepth(_ depth: [Float], to url: URL) throws {
        let data = depth.withUnsafeBufferPointer { Data(buffer: $0) }
        do {
            try data.write(to: url, options: .atomic)
        } catch {
            throw RawCaptureError.writeFailed(
                "\(url.lastPathComponent) — \(error.localizedDescription)")
        }
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
