import Foundation

/// First use binds each platform/kind/input mode once. Context, GPS, target,
/// operator and repetition are deliberately not calculation settings.
public struct FieldProtocolBinding: Codable, Equatable, Sendable {
    public static let policy = ["protocol_lock_policy": "first_capture_v1"]
    public let schema: Int
    public let id: String
    public let protocolFingerprint: String
    public let kind: FieldStudyKind
    public let platform: String
    public let inputMode: String
    public let settings: [String: String]
    /// Semantic procedure identity is separate from device/build/model and
    /// calibration identity. Equality does not assert equal sensor accuracy.
    public var procedureFingerprint: String {
        var values = ["procedure_schema":"1","kind":kind.rawValue,"input_mode":inputMode]
        let keys = inputMode == "typed" ? [] : kind == .dbh ?
            ["dbh_estimator_epoch","dbh_method","dbh_chord_algorithm","capture_frame_count","depth_sampling_fraction","depth_sampling_statistic","depth_surface_correction","breast_height_guide_height_m"] :
            ["height_method","height_procedure_version"]
        for key in keys { values[key] = settings[key] }
        if values["dbh_method"] == "partialArcCircleFit" { values["dbh_method"] = "arc" }
        return FieldFingerprint.settings(values)
    }

    public init(configuration: FieldStudyConfiguration, kind: FieldStudyKind, runtime: [String: String]) throws {
        schema = 1; protocolFingerprint = configuration.protocolFingerprint; self.kind = kind
        platform = runtime["platform"] ?? ""; inputMode = runtime["input_mode"] ?? ""
        settings = try Self.project(kind: kind,runtime: runtime)
        id = Self.identity(protocol: protocolFingerprint,kind: kind,platform: platform,inputMode: inputMode)
        try validate()
    }
    private static func identity(protocol fingerprint: String,kind: FieldStudyKind,platform: String,inputMode: String) -> String {
        FieldFingerprint.digest(["field-protocol-binding-v1",fingerprint,kind.rawValue,platform,inputMode])
    }
    public static func project(kind: FieldStudyKind,runtime: [String: String]) throws -> [String: String] {
        guard ["ios","android"].contains(runtime["platform"] ?? ""),
              ["typed","sensor"].contains(runtime["input_mode"] ?? "") else { throw FieldStudyError.invalid("protocol runtime identity") }
        var keys = ["platform","input_mode","app_version","app_build"]
        if runtime["input_mode"] == "sensor" {
            keys += ["measurement_source","calibration.depth_noise_mm","calibration.vio_drift_fraction"]
            if kind == .dbh {
                keys += ["dbh_estimator_epoch","dbh_method","dbh_chord_algorithm","capture_frame_count",
                         "depth_sampling_fraction","depth_sampling_statistic","depth_surface_correction","model_sha256",
                         "calibration.alpha","calibration.beta","calibration.epoch","calibration.depth_discontinuity_m"]
            } else { keys += ["height_method","height_procedure_version"] }
        }
        var values: [String: String] = [:]
        for key in keys {
            guard let value = runtime[key],!value.isEmpty,!["unknown","unavailable"].contains(value) else {
                throw FieldStudyError.missing("protocol setting: "+key)
            }
            values[key] = value
        }
        if let model = values["model_sha256"],model.count != 64 || !model.allSatisfy({ $0.isHexDigit && !$0.isUppercase }) {
            throw FieldStudyError.invalid("protocol model fingerprint")
        }
        // Optional only for decoding old baselines. New runtime records carry
        // the physical guide height; never silently repin a historical lock.
        if kind == .dbh,runtime["input_mode"] == "sensor",let height = runtime["breast_height_guide_height_m"] {
            guard height.count == 16,let bits = UInt64(height,radix: 16),
                  Double(bitPattern: bits).isFinite,Double(bitPattern: bits) > 0,
                  FieldFingerprint.number(Double(bitPattern: bits)) == height else {
                throw FieldStudyError.invalid("protocol guide height")
            }
            values["breast_height_guide_height_m"] = height
        }
        return values
    }
    public func validate() throws {
        guard schema == 1,protocolFingerprint.count == 64,
              id == Self.identity(protocol: protocolFingerprint,kind: kind,platform: platform,inputMode: inputMode),
              settings["platform"] == platform,settings["input_mode"] == inputMode,
              try Self.project(kind: kind,runtime: settings) == settings else { throw FieldStudyError.invalid("protocol binding") }
    }
    public func matches(configuration: FieldStudyConfiguration,kind: FieldStudyKind,runtime: [String: String]) -> Bool {
        (try? validate()) != nil && configuration.pinnedRuntimeSettings == Self.policy &&
            protocolFingerprint == configuration.protocolFingerprint && self.kind == kind &&
            (try? Self.project(kind: kind,runtime: runtime)) == settings
    }
    /// Do not silently repin an existing locked version, including legacy locks.
    public static func preparing(_ next: FieldStudyConfiguration,from previous: FieldStudyConfiguration) -> FieldStudyConfiguration {
        var copy = next
        if next.protocolLocked && (!previous.protocolLocked || next.studyId != previous.studyId || next.protocolVersion != previous.protocolVersion) {
            copy.pinnedRuntimeSettings = policy
        }
        return copy
    }
}
