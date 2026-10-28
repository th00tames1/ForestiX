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
// "f32m" (native metres, row-major little-endian); Android stores "u16mm"
// or f32m when its native buffer was unavailable. Legacy descriptors may
// omit confidence_file and then derive confidence from valid depth. Modern
// capture_evidence separately preserves actual float depth, confidence and
// clocks with checksums; canonical result_live is not the displayed result.
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
    public var measurementId: String? = nil
    public var captureEvidence: RawCaptureEvidence? = nil
    public var operatorEditId: String? = nil

    enum CodingKeys: String, CodingKey {
        case schema, platform, device
        case appCommit = "app_commit"
        case createdAt = "created_at"
        case kind, context, settings
        case resultLive = "result_live"
        case truth, gps
        case replaySelfcheck = "replay_selfcheck"
        case dbh, height
        case measurementId = "measurement_id", captureEvidence = "capture_evidence"
        case operatorEditId = "operator_edit_id"
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
        /// Where the height sighting angle comes from: "camera_pose", the
        /// elevation of the AR camera's forward axis, on both platforms.
        /// Optional so bundles written before the field existed still decode.
        public var heightAngleSource: String?
        public var dbhDepthInputPolicy: String? = nil
        public var dbhDepthCalibrationEpoch: Int? = nil
        enum CodingKeys: String, CodingKey {
            case algorithm, units
            case captureMode = "capture_mode"
            case calibration
            case heightAngleSource = "height_angle_source"
            case dbhDepthInputPolicy = "dbh_depth_input_policy"
            case dbhDepthCalibrationEpoch = "dbh_depth_calibration_epoch"
        }
        public init(algorithm: String, units: String, captureMode: String, calibration: Calibration,
                    heightAngleSource: String? = "camera_pose") {
            self.algorithm = algorithm; self.units = units
            self.captureMode = captureMode; self.calibration = calibration
            self.heightAngleSource = heightAngleSource
        }
    }

    public struct Calibration: Codable, Sendable {
        public var alpha: Double
        public var beta: Double
        public var depthNoiseMm: Double
        public var vioDriftFraction: Double
        /// WHICH ESTIMATOR alpha/beta were fitted against, carried so a replay
        /// can put them back exactly as the capture had them.
        ///
        /// It was missing, and the omission silently disarmed the coefficients
        /// on every replay: `ProjectCalibration.dbhCalibrationEpoch` defaults
        /// to 0, 0 never equals a live epoch, so `appliedToRawCm` judged every
        /// rebuilt calibration stale and returned the RAW diameter. For a
        /// calibrated project that made `DBHEpochRecompute` write uncalibrated
        /// widths back over calibrated ones — and the difference,
        /// `alpha + (beta-1)·raw`, is about a percent, the same size as a
        /// genuine epoch shift, so the review screen showed a plausible
        /// correction and nothing marked it as a loss.
        ///
        /// OPTIONAL, defaulting to 0 on read. Bundles written before this key
        /// existed keep exactly today's conservative behaviour — their
        /// coefficients stay refused — rather than being retro-fitted with an
        /// epoch nobody recorded.
        public var dbhCalibrationEpoch: Int?
        enum CodingKeys: String, CodingKey {
            case alpha, beta
            case depthNoiseMm = "depth_noise_mm"
            case vioDriftFraction = "vio_drift_fraction"
            case dbhCalibrationEpoch = "dbh_calibration_epoch"
        }
        public init(alpha: Double, beta: Double, depthNoiseMm: Double,
                    vioDriftFraction: Double, dbhCalibrationEpoch: Int? = nil) {
            self.alpha = alpha; self.beta = beta
            self.depthNoiseMm = depthNoiseMm; self.vioDriftFraction = vioDriftFraction
            self.dbhCalibrationEpoch = dbhCalibrationEpoch
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
        /// ALWAYS the app's metric base — cm for a diameter, m for a height.
        public var value: Double?
        public var enteredAt: String?
        /// The unit the operator actually TYPED ("cm" | "in" | "m" | "ft").
        /// Recorded rather than inferred at read time: `value` is normalised
        /// on the way in, so without this the export cannot tell an imperial
        /// entry from a metric one and has to guess. Nil on bundles written
        /// before the unit was recorded and on bundles with no truth — a
        /// reader must treat that as "not stated", not as metric.
        public var truthUnit: String?
        /// The number that WAS in `value` before `TruthUnitRepair` re-based it,
        /// exactly as the cruiser typed it. Nil on every truth that has not
        /// been repaired, which is nearly all of them.
        ///
        /// It is an audit crumb, not the idempotence mark — `truthUnit` is
        /// that, because "no unit recorded" is the whole definition of an
        /// affected truth. This is here so the cruiser can reconcile a repaired
        /// bundle against their backup without needing the report file, and so
        /// the digits they typed survive the correction rather than only the
        /// product of it.
        public var repairedFrom: Double?
        enum CodingKeys: String, CodingKey {
            case value
            case enteredAt = "entered_at"
            case truthUnit = "truth_unit"
            case repairedFrom = "repaired_from"
        }
        public init(value: Double?, enteredAt: String?, truthUnit: String? = nil,
                    repairedFrom: Double? = nil) {
            self.value = value; self.enteredAt = enteredAt
            self.truthUnit = truthUnit
            self.repairedFrom = repairedFrom
        }

        /// Explicit encoder. The SYNTHESIZED one emits `encodeIfPresent`, so a
        /// nil field disappears from manifest.json entirely, while the Android
        /// writer always writes `"truth_unit": null` (sensors/RawCaptureStore
        /// .kt `truthJson`). A corpus reader keyed on the presence of the key
        /// then sees a missing key on iOS bundles and a present-but-null key
        /// on Android bundles for the IDENTICAL "no truth typed" state.
        /// Writing all three keys unconditionally makes one document out of
        /// the two platforms' manifests. Decoding stays synthesized, which
        /// reads both shapes.
        public func encode(to encoder: Encoder) throws {
            var c = encoder.container(keyedBy: CodingKeys.self)
            try c.encode(value, forKey: .value)
            try c.encode(enteredAt, forKey: .enteredAt)
            try c.encode(truthUnit, forKey: .truthUnit)
            try c.encode(repairedFrom, forKey: .repairedFrom)
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
            public var format: String       // "f32m" | "u16mm"
            public var confidenceFile: String?
            public var depthInputPolicy: String?
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
                case confidenceFile = "confidence_file"
                case depthInputPolicy = "depth_input_policy"
                case width, height, format, fx, fy, cx, cy
                case tapPx = "tap_px"
                case axis
                case cameraPose = "camera_pose"
                case viewToDepth = "view_to_depth"
            }
            public init(depthFile: String, width: Int, height: Int, format: String,
                        fx: Double, fy: Double, cx: Double, cy: Double,
                        tapPx: [Double], axis: String, cameraPose: [Double], viewToDepth: [Double],
                        confidenceFile: String? = nil, depthInputPolicy: String? = nil) {
                self.depthFile = depthFile; self.width = width; self.height = height
                self.format = format; self.fx = fx; self.fy = fy; self.cx = cx; self.cy = cy
                self.tapPx = tapPx; self.axis = axis
                self.cameraPose = cameraPose; self.viewToDepth = viewToDepth
                self.confidenceFile = confidenceFile
                self.depthInputPolicy = depthInputPolicy
            }
        }

        public struct Bracket: Codable, Sendable {
            public var enabled: Bool
            public var left: Double
            public var right: Double
            /// Absent in historical bundles whose coordinate semantics vary.
            public var coordinateSpace: String?
            public var screenFractions: [Double]?
            public var viewportSize: [Double]?
            enum CodingKeys: String, CodingKey {
                case enabled, left, right
                case coordinateSpace = "coordinate_space"
                case screenFractions = "screen_fractions"
                case viewportSize = "viewport_size"
            }
            public init(enabled: Bool, left: Double, right: Double,
                        coordinateSpace: String? = nil,
                        screenFractions: [Double]? = nil,
                        viewportSize: [Double]? = nil) {
                self.enabled = enabled; self.left = left; self.right = right
                self.coordinateSpace = coordinateSpace
                self.screenFractions = screenFractions
                self.viewportSize = viewportSize
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
        /// VIO tracking dropped somewhere between anchoring the trunk and the
        /// aims. A dropout moves the world frame the anchor sits in, so it
        /// moves `d_h_m` — the entire scale of H — and the pose trail above
        /// has a hole where it happened. Optional so bundles written before
        /// this key still decode; nil there means UNRECORDED, not "clean".
        /// Byte-identical key on Android (`tracking_dropped` in the height
        /// block), and the same fact the research CSV column carries.
        public var trackingDropped: Bool?
        enum CodingKeys: String, CodingKey {
            case anchor, base, top
            case dHM = "d_h_m"
            case poseSamples = "pose_samples"
            case trackingDropped = "tracking_dropped"
        }
        public init(anchor: Anchor, base: Aim, top: Aim, dHM: Double,
                    poseSamples: [PoseSample], trackingDropped: Bool? = nil) {
            self.anchor = anchor; self.base = base; self.top = top
            self.dHM = dHM; self.poseSamples = poseSamples
            self.trackingDropped = trackingDropped
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
            // as before. `format` is "f32m" or "u16mm"; Android can use
            // either when the native raw-depth buffer was unavailable.
            public var depthFile: String?   // "depth_base.bin" / "depth_top.bin"
            public var rgbFile: String?     // "rgb_base.jpg" / "rgb_top.jpg"
            public var confidenceFile: String?
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
                case confidenceFile = "confidence_file"
                case width, height, format, fx, fy, cx, cy
            }
            public init(pitchDeg: Double, cameraPose: [Double],
                        depthFile: String? = nil, rgbFile: String? = nil,
                        width: Int? = nil, height: Int? = nil, format: String? = nil,
                        fx: Double? = nil, fy: Double? = nil,
                        cx: Double? = nil, cy: Double? = nil, confidenceFile: String? = nil) {
                self.pitchDeg = pitchDeg; self.cameraPose = cameraPose
                self.depthFile = depthFile; self.rgbFile = rgbFile
                self.width = width; self.height = height; self.format = format
                self.fx = fx; self.fy = fy; self.cx = cx; self.cy = cy
                self.confidenceFile = confidenceFile
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

    #if DEBUG
    /// Scoped to a synthetic test call; never a persisted app preference.
    /// Release builds have no override and always use the user's container.
    @TaskLocal static var testingRootDirectory: URL?
    #endif

    /// Documents/raw-captures — the whole bundle tree.
    public static var rootDirectory: URL {
        #if DEBUG
        if let dir = testingRootDirectory {
            try? FileManager.default.createDirectory(at:dir,withIntermediateDirectories:true)
            return dir
        }
        #endif
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

    /// Every readable bundle (newest first, by manifest `created_at`) together
    /// with the count of bundle directories actually present.
    ///
    /// READ-ONLY: every bundle directory counts, including incomplete writes
    /// and pending operator input. An unreadable bundle is not absent data.
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

    /// Compatibility entry point. Automatic cleanup must never erase partial
    /// field evidence or queued operator input. Explicit user deletion remains
    /// separate; refresh/export are read-only with respect to source bundles.
    @discardableResult
    public static func reapOrphanBundles() -> Int {
        0
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
            try RawOperatorEditJournal.atomic(data,to: manifestURL(id: id))
        } catch {
            throw RawCaptureError.writeFailed("manifest.json — \(error.localizedDescription)")
        }
    }

    // MARK: Ground truth + Accept (never silently dropped)

    /// Serialises "the recorder finishes the manifest" against "the operator
    /// enters a truth / taps Accept". A value entered while the bundle is
    /// still being written can no longer land in the gap between the two.
    static let patchLock = NSRecursiveLock()

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

    /// Preserve every byte after a failed capture. A failure note is best
    /// effort (storage itself may have failed); it never replaces a manifest.
    /// The directory overload is for synthetic, isolated storage tests.
    static func preserveFailedBundle(id: String,directory: URL? = nil) -> String {
        patchLock.lock()
        defer { patchLock.unlock() }
        let dir = directory ?? bundleDirectory(id: id)
        guard FileManager.default.fileExists(atPath: dir.path) else { return "" }
        let patch = (try? Data(contentsOf: dir.appendingPathComponent("pending_patch.json")))
            .flatMap { try? decoder().decode(PendingPatch.self,from: $0) }
        let hadTruth = patch?.truth?.value != nil
        let note: [String: Any] = ["schema": 1,"measurement_id": id,"status": "partial_write_failed",
                                 "created_at": ISO8601DateFormatter().string(from: Date())]
        if let data = try? JSONSerialization.data(withJSONObject: note,options: [.sortedKeys]) {
            try? data.write(to: dir.appendingPathComponent("write_failure.json"),options: .atomic)
        }
        return " · partial raw files retained"+(hadTruth ? " · typed ground truth not committed — keep input" : "")
    }

    /// What happened to a typed truth.
    ///
    /// `applied` — the value IS in a manifest on disk. This is the only state
    ///   that may clear the operator's input.
    /// `pending` — the value is parked in the bundle's sidecar for the
    ///   in-flight recorder to fold in. Not committed to a complete manifest;
    ///   the sidecar is retained if the recorder fails.
    ///   `pending` used to report `isDurable`, so the field was cleared the
    ///   instant the value was merely QUEUED and a later write failure took
    ///   the hand measurement with it, silently. Callers keep the text and
    ///   show a queued state until the capture itself reports SAVED.
    /// `failed` — completion was not confirmed. A durable sidecar may still
    /// exist for recovery; keep the text and warn.
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
    ///
    /// `value` is the metric base (cm / m); `unit` is what the operator typed,
    /// and is stored beside it so the export never has to infer the scale.
    public static func applyTruth(id: String,
                                  value: Double,
                                  unit: TruthInput.Unit) -> TruthOutcome {
        let truth = RawCaptureManifest.Truth(value: value,
                                             enteredAt: Self.isoNow(),
                                             truthUnit: unit.rawValue)
        do {
            return try RawOperatorEditJournal.update(directory: bundleDirectory(id: id),id: id,truth: truth) ? .applied : .pending
        } catch { return .failed(error.localizedDescription) }
    }

    /// Re-base a stored truth that was typed in imperial and stored as if it
    /// were the metric base — `TruthUnitRepair`'s write into a bundle.
    ///
    /// This is the ONE thing in the app that edits a manifest's truth without
    /// the cruiser typing into that bundle. It is not an inference: it acts
    /// only where the bundle ITSELF records no `truth_unit`, and it corrects
    /// the scale of the field rather than replacing the observation. The digits
    /// typed are kept in `repaired_from` and `entered_at` is NOT restamped —
    /// when the cruiser measured the stem has not changed.
    ///
    /// RE-CHECKS UNDER THE LOCK. `before` must still be what is on disk and the
    /// bundle must still record no unit, so a truth the console re-typed
    /// between the plan and this write is left alone, and a second run of the
    /// repair finds a unit and does nothing. No sidecar path: a bundle with no
    /// manifest has no stored truth to re-base, and parking a correction for a
    /// value that does not exist yet would apply it to whatever the recorder
    /// writes next.
    public static func repairTruthUnit(id: String,
                                       before: Double,
                                       after: Double,
                                       unit: TruthInput.Unit) -> Bool {
        patchLock.lock()
        defer { patchLock.unlock() }
        guard var m = loadManifest(id: id),
              m.measurementId == nil, m.captureEvidence == nil,
              !FileManager.default.fileExists(atPath: pendingPatchURL(id: id).path),
              m.truth.truthUnit == nil,
              let stored = m.truth.value,
              abs(stored - before) <= truthValueEpsilon
        else { return false }
        m.truth = RawCaptureManifest.Truth(value: after,
                                           enteredAt: m.truth.enteredAt,
                                           truthUnit: unit.rawValue,
                                           repairedFrom: stored)
        do { try writeManifest(m, id: id); return true } catch { return false }
    }

    /// Two truth values are the SAME value inside this band — the same number
    /// and the same reasoning as `TruthBackfill.valueEpsilon`: every writer
    /// puts the metric base through one parser, so the only difference between
    /// two copies of a truth is float round-trip.
    static let truthValueEpsilon: Double = 0.001

    /// Clear a stored truth. EXPLICIT ONLY — an empty or unparseable input
    /// must never reach this (that is how a good truth got wiped).
    @discardableResult
    public static func clearTruth(id: String) -> TruthOutcome {
        do {
            return try RawOperatorEditJournal.update(directory: bundleDirectory(id: id),id: id,
                truth: .init(value: nil,enteredAt: nil)) ? .applied : .pending
        } catch { return .failed(error.localizedDescription) }
    }

    /// The recorder's LAST write: folds in any operator input that landed
    /// while the bundle was still being written — a truth that patched the
    /// intermediate manifest, and/or a truth/Accept parked in the sidecar —
    /// then commits, all under the patch lock. In either interleaving nothing
    /// the operator entered is lost.
    static func commitManifestFoldingTruth(_ m: RawCaptureManifest, id: String) throws {
        try RawOperatorEditJournal.commitRecorder(m,directory: bundleDirectory(id: id),id: id)
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
        do {
            _ = try RawOperatorEditJournal.update(directory: bundleDirectory(id: id),id: id,accepted: true); return true
        } catch { return false }
    }

    public static func recoverPendingOperatorEdits(root: URL? = nil) -> [RawOperatorRecoveryIssue] {
        RawOperatorEditJournal.recover(root: root ?? rootDirectory)
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
        // Export partial/unreadable bundles too; never clean source evidence
        // as a side effect of exporting it.
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

    /// What produced this bundle, in enough detail to partition the corpus.
    ///
    /// THIS USED TO BE THE MARKETING VERSION, and it never moved: every iOS
    /// bundle in the validation corpus says "1.0 (2)" and every Android one
    /// "1.0 (1)". A comment in `DBHEstimator` asserted that `app_commit` was
    /// what separated two estimator eras. It was not, and the cost was real —
    /// the bracket's middle-half depth trim shipped partway through a
    /// 100-stem collection, moved every diameter, and stamped both eras
    /// identically. Half that corpus cannot be attributed to the code that
    /// produced it, and the only reason the study survived is that every
    /// diameter was recomputed offline from the stored depth frames.
    ///
    /// So the estimator's OWN version leads, and it is a constant this file
    /// controls rather than a build setting someone has to remember to bump:
    /// any change to what the estimators compute changes
    /// `DBHEstimator.estimatorEpoch`, and every bundle written afterwards
    /// says so. The build string is kept after it, for the cases where the
    /// estimator is innocent and the platform is not.
    static func appCommit() -> String {
        let info = Bundle.main.infoDictionary
        let short = info?["CFBundleShortVersionString"] as? String ?? "?"
        let build = info?["CFBundleVersion"] as? String ?? "?"
        return "est\(DBHEstimator.estimatorEpoch)/\(short) (\(build))"
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
            timestamp: frame.timestamp,
            viewMapping: frame.viewMapping)
    }

    /// Reconstruct a canonical frame from stored depth bytes + metadata.
    public static func reconstruct(from meta: RawCaptureManifest.DBHBundle.Frame,
                                   depth: [Float], confidence: [UInt8]? = nil) -> ARDepthFrame {
        var conf = confidence ?? [UInt8](repeating: 0, count: depth.count)
        if confidence == nil && (meta.depthInputPolicy ?? DBHMeasurementDepth.legacy) == DBHMeasurementDepth.legacy {
            for i in 0..<depth.count where validDepth(depth[i]) { conf[i] = 2 }
        }
        let m = meta.viewToDepth
        let mapping: DepthViewMapping? = m.count == 6 && m.allSatisfy({ $0.isFinite })
            ? .init(a: m[0], b: m[1], tx: m[2], c: m[3], d: m[4], ty: m[5]) : nil
        return ARDepthFrame(
            width: meta.width,
            height: meta.height,
            depth: depth,
            confidence: conf,
            intrinsics: RawCaptureMatrix.intrinsics(fx: meta.fx, fy: meta.fy,
                                                    cx: meta.cx, cy: meta.cy),
            cameraPoseWorld: RawCaptureMatrix.pose(meta.cameraPose),
            timestamp: 0,
            viewMapping: mapping,
            dbhDepthInputPolicy: meta.depthInputPolicy ?? DBHMeasurementDepth.legacy)
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
