import Foundation
import CryptoKit

/// Field-study metadata is independent of the production estimators.
/// JSON keys are snake_case on both platforms; unknown/missing evidence is never inferred.
public enum FieldStudyKind: String, Codable, Sendable { case dbh, height }
public enum FieldReferenceRole: String, Codable, Sendable { case groundTruth = "ground_truth", comparison }
public enum FieldReferenceUnit: String, Codable, CaseIterable, Sendable {
    case cm, inch = "in", m, ft
    public func supports(_ kind: FieldStudyKind) -> Bool {
        kind == .dbh ? self == .cm || self == .inch : self == .m || self == .ft
    }
    public func baseValue(_ value: Double) -> Double {
        switch self { case .cm, .m: return value; case .inch: return value * 2.54; case .ft: return value * 0.3048 }
    }
}

public enum FieldStudyError: Error, Equatable, LocalizedError {
    case invalid(String), developerRequired, protocolLocked, unsupportedSchema(Int)
    case corrupt(String), missing(String), conflict(String)
    public var errorDescription: String? {
        switch self {
        case .invalid(let s): return "Invalid field-study data: \(s)"
        case .developerRequired: return "Ground-truth editing requires developer mode."
        case .protocolLocked: return "The protocol is locked. Create a new protocol version before changing its configuration."
        case .unsupportedSchema(let n): return "Unsupported field-study schema \(n)."
        case .corrupt(let s): return "Unreadable field-study record: \(s)"
        case .missing(let s): return "Missing field-study record: \(s)"
        case .conflict(let s): return "Field-study record conflict: \(s)"
        }
    }
}

public enum FieldStudyJSON {
    public static func encoder() -> JSONEncoder {
        let e = JSONEncoder(); e.keyEncodingStrategy = .convertToSnakeCase
        e.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]; return e
    }
    public static func decoder() -> JSONDecoder {
        let d = JSONDecoder(); d.keyDecodingStrategy = .convertFromSnakeCase; return d
    }
    public static func timestamp(_ date: Date = Date()) -> String {
        let f = ISO8601DateFormatter(); f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f.string(from: date)
    }
}

public enum FieldFingerprint {
    /// Length-prefixed UTF-8 avoids JSON number/escaping differences across platforms.
    public static func digest(_ parts: [String]) -> String {
        var hash = SHA256()
        for part in parts {
            let bytes = Data(part.utf8); var count = UInt32(bytes.count).bigEndian
            withUnsafeBytes(of: &count) { hash.update(data: Data($0)) }; hash.update(data: bytes)
        }
        return hash.finalize().map { String(format: "%02x", $0) }.joined()
    }
    public static func number(_ value: Double) -> String { String(format: "%016llx", value.bitPattern) }
    public static func settings(_ values: [String: String]) -> String {
        digest(values.keys.sorted().flatMap { [$0, values[$0]!] })
    }
}

public struct FieldReferenceMethod: Codable, Equatable, Sendable, Identifiable {
    public var id: String
    public var name: String
    public var kind: FieldStudyKind
    public var unit: FieldReferenceUnit
    public var role: FieldReferenceRole
    public var enabled: Bool
    public init(id: String = UUID().uuidString, name: String, kind: FieldStudyKind,
                unit: FieldReferenceUnit, role: FieldReferenceRole = .comparison, enabled: Bool = true) {
        self.id = id; self.name = name; self.kind = kind; self.unit = unit; self.role = role; self.enabled = enabled
    }
    public func validate() throws {
        guard !id.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              id.utf8.count <= 200, name.utf8.count <= 200, unit.supports(kind)
        else { throw FieldStudyError.invalid("reference method") }
    }
}

public struct FieldARConfiguration: Codable, Equatable, Sendable {
    public var enabled = false
    public var maximumDistanceM = 20.0
    public var maximumPoints = 5
    public var maximumHorizontalAccuracyM = 3.0
    public var maximumFixAgeSeconds = 2.0
    public var maximumHeadingAgeSeconds = 2.0
    public var maximumHeadingErrorDegrees = 10.0
    public var distanceHysteresisM = 2.0
    public var showDistances = false
    public init() {}
    public func validate() throws {
        let values = [maximumDistanceM, maximumHorizontalAccuracyM, maximumFixAgeSeconds,
                      maximumHeadingAgeSeconds, maximumHeadingErrorDegrees, distanceHysteresisM]
        guard values.allSatisfy(\.isFinite), (1...100).contains(maximumDistanceM),
              (1...20).contains(maximumPoints), (0.1...100).contains(maximumHorizontalAccuracyM),
              (0.1...10).contains(maximumFixAgeSeconds), (0.1...10).contains(maximumHeadingAgeSeconds),
              (0.1...45).contains(maximumHeadingErrorDegrees), (0...10).contains(distanceHysteresisM)
        else { throw FieldStudyError.invalid("AR overlay configuration") }
    }
}

public struct FieldStudyConfiguration: Codable, Equatable, Sendable {
    public var schema = 1
    public var enabled = false
    public var studyId = ""
    public var operatorId = ""
    public var protocolVersion = "1"
    public var protocolLocked = false
    public var plannedRepetitions = 3
    public var plannedRepeat = 1
    public var recordTiming = true
    public var recordFailedAttempts = true
    public var preserveAiInputs = true
    public var mapOverlayEnabled = true
    public var hideCompletedTargets = false
    public var showReferencesDuringMeasurement = false
    public var ar = FieldARConfiguration()
    public var referenceMethods: [FieldReferenceMethod] = [
        .init(id: "dbh-gt", name: "DBH reference", kind: .dbh, unit: .cm, role: .groundTruth),
        .init(id: "height-gt", name: "Height reference", kind: .height, unit: .m, role: .groundTruth)]
    /// Legacy exact runtime maps remain readable. New locks identify the
    /// first-capture policy; immutable per-platform baselines are separate.
    public var pinnedRuntimeSettings: [String: String] = [:]
    public init() {}
    public func validate() throws {
        guard schema == 1 else { throw FieldStudyError.unsupportedSchema(schema) }
        guard (!enabled || (!studyId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
                            !operatorId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)),
              !protocolVersion.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              [studyId, operatorId, protocolVersion].allSatisfy({ $0.utf8.count <= 200 }),
              (1...999).contains(plannedRepetitions), (1...plannedRepetitions).contains(plannedRepeat),
              referenceMethods.count <= 50, Set(referenceMethods.map(\.id)).count == referenceMethods.count
        else { throw FieldStudyError.invalid("study identity, repetitions or duplicate reference methods") }
        try ar.validate(); try referenceMethods.forEach { try $0.validate() }
    }
    public var protocolFingerprint: String {
        var parts = ["field-study-protocol-v1", studyId, protocolVersion, String(plannedRepetitions),
                     String(recordTiming), String(recordFailedAttempts), String(preserveAiInputs),
                     String(mapOverlayEnabled), String(hideCompletedTargets), String(showReferencesDuringMeasurement),
                     String(ar.enabled), FieldFingerprint.number(ar.maximumDistanceM), String(ar.maximumPoints),
                     FieldFingerprint.number(ar.maximumHorizontalAccuracyM), FieldFingerprint.number(ar.maximumFixAgeSeconds),
                     FieldFingerprint.number(ar.maximumHeadingAgeSeconds), FieldFingerprint.number(ar.maximumHeadingErrorDegrees),
                     FieldFingerprint.number(ar.distanceHysteresisM), String(ar.showDistances)]
        for m in referenceMethods.sorted(by: { $0.id < $1.id }) {
            parts += [m.id, m.name, m.kind.rawValue, m.unit.rawValue, m.role.rawValue, String(m.enabled)]
        }
        parts.append(FieldFingerprint.settings(pinnedRuntimeSettings)); return FieldFingerprint.digest(parts)
    }
    public func validateTransition(from previous: Self, developerMode: Bool) throws {
        try validate()
        if !developerMode && (referenceMethods != previous.referenceMethods ||
                              showReferencesDuringMeasurement != previous.showReferencesDuringMeasurement) {
            throw FieldStudyError.developerRequired
        }
        if previous.protocolLocked && studyId == previous.studyId && protocolVersion == previous.protocolVersion &&
            (protocolFingerprint != previous.protocolFingerprint || !protocolLocked) {
            throw FieldStudyError.protocolLocked
        }
    }
}

public struct FieldStudyTarget: Codable, Equatable, Sendable, Identifiable {
    public var id: String
    public var studyId: String
    public var treeId: String
    public var label: String
    public var latitude: Double?
    public var longitude: Double?
    public var altitudeM: Double?
    public var horizontalAccuracyM: Double?
    public var sourceLayerId: String
    public var sourceFeatureId: String
    public var sourceCrs: String
    public var attributes: [String: String]
    public init(studyId: String, treeId: String, label: String, latitude: Double? = nil, longitude: Double? = nil,
                altitudeM: Double? = nil, horizontalAccuracyM: Double? = nil,
                sourceLayerId: String = "manual", sourceFeatureId: String = "", sourceCrs: String = "EPSG:4326",
                attributes: [String: String] = [:]) {
        self.id = FieldFingerprint.digest([studyId, treeId]); self.studyId = studyId; self.treeId = treeId
        self.label = label; self.latitude = latitude; self.longitude = longitude; self.altitudeM = altitudeM
        self.horizontalAccuracyM = horizontalAccuracyM; self.sourceLayerId = sourceLayerId
        self.sourceFeatureId = sourceFeatureId; self.sourceCrs = sourceCrs; self.attributes = attributes
    }
    public func validate() throws {
        guard !studyId.isEmpty, !treeId.isEmpty, !label.isEmpty,
              [studyId, treeId, label, sourceLayerId, sourceFeatureId].allSatisfy({ $0.utf8.count <= 500 }),
              id == FieldFingerprint.digest([studyId, treeId]),
              (latitude == nil && longitude == nil ||
               latitude.map({ $0.isFinite && (-90...90).contains($0) }) == true &&
               longitude.map({ $0.isFinite && (-180...180).contains($0) }) == true),
              altitudeM.map({ $0.isFinite }) ?? true,
              horizontalAccuracyM.map({ $0.isFinite && $0 >= 0 }) ?? true
        else { throw FieldStudyError.invalid("target identity or coordinates") }
    }
}

public struct FieldReferenceObservation: Codable, Equatable, Sendable, Identifiable {
    public var id: String
    public var targetId: String
    public var method: FieldReferenceMethod
    public var inputValue: Double
    public var baseValue: Double
    /// nil means a tree-level reference shared by all planned repetitions.
    public var plannedRepeat: Int?
    public var referenceRepeat: Int
    public var operatorId: String
    public var recordedAt: String
    public var supersedesId: String?
    public var source: String
    public init(id: String = UUID().uuidString, targetId: String, method: FieldReferenceMethod, inputValue: Double,
                plannedRepeat: Int? = nil, referenceRepeat: Int = 1, operatorId: String,
                recordedAt: String = FieldStudyJSON.timestamp(), supersedesId: String? = nil, source: String = "typed") {
        self.id = id; self.targetId = targetId; self.method = method; self.inputValue = inputValue
        self.baseValue = method.unit.baseValue(inputValue); self.plannedRepeat = plannedRepeat
        self.referenceRepeat = referenceRepeat; self.operatorId = operatorId; self.recordedAt = recordedAt
        self.supersedesId = supersedesId; self.source = source
    }
    public func validate(developerMode: Bool) throws {
        guard developerMode else { throw FieldStudyError.developerRequired }
        try method.validate()
        guard UUID(uuidString: id) != nil, !targetId.isEmpty, !operatorId.isEmpty,
              inputValue.isFinite, inputValue > 0, baseValue.isFinite, baseValue > 0,
              baseValue == method.unit.baseValue(inputValue), plannedRepeat.map({ $0 > 0 }) ?? true,
              referenceRepeat > 0, !recordedAt.isEmpty,
              supersedesId.map({ UUID(uuidString: $0) != nil && $0 != id }) ?? true
        else { throw FieldStudyError.invalid("reference observation") }
    }
}

public enum FieldAttemptOutcome: String, Codable, Sendable { case started, result, failed, cancelled, interrupted }
public enum FieldSelectionStatus: String, Codable, Sendable { case pending, accepted, discarded, superseded }
public enum FieldRawStatus: String, Codable, Sendable { case notRequested = "not_requested", pending, saved, failed, missing }

public struct FieldAIProvenance: Codable, Equatable, Sendable {
    public var enabled = false
    public var inferenceRan = false
    public var maskDetected = false
    public var boundaryApplied = false
    public var userAdjustedBoundary = false
    public var inferenceId: String?
    public var reason: String?
    public init() {}
    public func validate() throws {
        guard (!maskDetected || inferenceRan), (!boundaryApplied || (maskDetected && inferenceRan)),
              (!boundaryApplied || inferenceId != nil)
        else { throw FieldStudyError.invalid("AI provenance") }
    }
}

public struct FieldTimingSnapshot: Codable, Equatable, Sendable {
    public var screenElapsedSeconds: Double
    public var activeElapsedSeconds: Double
    public var backgroundSeconds: Double
    public var attemptElapsedSeconds: Double?
    public var attemptActiveSeconds: Double?
    public var firstValidResultElapsedSeconds: Double?
    public var attemptFirstResultSeconds: Double?
    public var acceptedElapsedSeconds: Double?
    public var clockValid: Bool
}

public struct FieldMeasurementRecord: Codable, Equatable, Sendable, Identifiable {
    public var schema = 1
    public var id: String
    public var sessionId: String
    public var studyId: String
    public var treeId: String
    public var targetId: String?
    public var targetSnapshot: FieldStudyTarget?
    public var operatorId: String
    public var kind: FieldStudyKind
    public var plannedRepeat: Int
    public var attemptNumber: Int
    public var startedAt: String
    public var outcome: FieldAttemptOutcome = .started
    public var selection: FieldSelectionStatus = .pending
    public var failureReason: String?
    public var liveValue: Double?
    public var liveSigma: Double?
    public var replayValue: Double?
    /// Exact application result row, separate from the logical tree ID.
    public var resultRecordId: String?
    public var resultStore: String?
    public var rawStatus: FieldRawStatus = .notRequested
    public var rawBundleId: String?
    public var rawFailureReason: String?
    public var ai = FieldAIProvenance()
    public var timing: FieldTimingSnapshot?
    public var uiFlowTiming: FieldUIFlowTiming?
    public var configuration: FieldStudyConfiguration
    public var runtimeSettings: [String: String]
    public var protocolBinding: FieldProtocolBinding?
    public var referenceIds: [String] = []
    public var measurementScope: FieldMeasurementScope?
    public var revision = 0
    public var events: [Event] = []
    public struct Event: Codable, Equatable, Sendable {
        public var timestamp: String
        public var action: String
        public var detail: String?
        public init(action: String, detail: String? = nil, timestamp: String = FieldStudyJSON.timestamp()) {
            self.timestamp = timestamp; self.action = action; self.detail = detail
        }
    }
    public init(id: String = UUID().uuidString, sessionId: String, treeId: String, targetId: String? = nil,
                targetSnapshot: FieldStudyTarget? = nil, kind: FieldStudyKind, attemptNumber: Int, configuration: FieldStudyConfiguration,
                runtimeSettings: [String: String], startedAt: String = FieldStudyJSON.timestamp()) {
        self.id = id; self.sessionId = sessionId; self.studyId = configuration.studyId; self.treeId = treeId
        self.targetId = targetId; self.operatorId = configuration.operatorId; self.kind = kind
        self.targetSnapshot = targetSnapshot
        self.plannedRepeat = configuration.plannedRepeat; self.attemptNumber = attemptNumber
        self.configuration = configuration; self.runtimeSettings = runtimeSettings; self.startedAt = startedAt
    }
    public var protocolConforms: Bool {
        if configuration.protocolLocked,configuration.pinnedRuntimeSettings == FieldProtocolBinding.policy {
            return protocolBinding?.matches(configuration: configuration,kind: kind,runtime: runtimeSettings) == true
        }
        return !configuration.protocolLocked || (!configuration.pinnedRuntimeSettings.isEmpty &&
            configuration.pinnedRuntimeSettings == runtimeSettings)
    }
    public var wasApplicationUndone: Bool {
        selection == .discarded && outcome == .result && resultStore == "cruise" && resultRecordId != nil &&
            events.contains { $0.action == "application_undo" && $0.detail.flatMap(UUID.init(uuidString:)) != nil }
    }
    public func validate() throws {
        guard schema == 1 else { throw FieldStudyError.unsupportedSchema(schema) }
        try configuration.validate(); try ai.validate()
        try protocolBinding?.validate()
        guard UUID(uuidString: id) != nil, UUID(uuidString: sessionId) != nil,
              !studyId.isEmpty, !treeId.isEmpty, !operatorId.isEmpty,
              studyId == configuration.studyId, operatorId == configuration.operatorId,
              (1...configuration.plannedRepetitions).contains(plannedRepeat), attemptNumber > 0, revision >= 0,
              liveValue.map({ $0.isFinite && $0 > 0 }) ?? true,
              liveSigma.map({ $0.isFinite && $0 >= 0 }) ?? true,
              replayValue.map({ $0.isFinite && $0 > 0 }) ?? true,
              (rawBundleId == nil || rawBundleId == id),
              (rawStatus != .saved || rawBundleId == id),
              (resultRecordId == nil && resultStore == nil ||
               resultRecordId.flatMap({ UUID(uuidString: $0) }) != nil && ["quick_history","cruise"].contains(resultStore ?? "")),
              (outcome != .result || liveValue != nil),
              (targetId == nil || (targetSnapshot?.id == targetId && targetSnapshot?.studyId == studyId && targetSnapshot?.treeId == treeId)),
              (selection != .accepted || (outcome == .result && liveValue != nil)),
              (outcome != .failed || failureReason?.isEmpty == false)
        else { throw FieldStudyError.invalid("measurement record") }
        try targetSnapshot?.validate()
        if let scope = measurementScope {
            try scope.validate()
            guard configuration == scope.configuration,targetSnapshot == scope.target,
                  referenceIds == scope.references(for: kind).map(\.id) else { throw FieldStudyError.invalid("measurement scope binding") }
        }
        if let ui = uiFlowTiming {
            try ui.validate()
            guard ui.kind == kind,configuration.recordTiming,
                  ui.atResult == nil || outcome == .result,
                  ui.atAccepted == nil || selection == .accepted || selection == .superseded || wasApplicationUndone else { throw FieldStudyError.invalid("UI flow measurement binding") }
        }
        if let t = timing {
            let durations = [t.screenElapsedSeconds, t.activeElapsedSeconds, t.backgroundSeconds] +
                [t.attemptElapsedSeconds, t.attemptActiveSeconds, t.firstValidResultElapsedSeconds,
                 t.attemptFirstResultSeconds, t.acceptedElapsedSeconds].compactMap { $0 }
            guard durations.allSatisfy({ $0.isFinite && $0 >= 0 }),
                  abs(t.screenElapsedSeconds - t.activeElapsedSeconds - t.backgroundSeconds) < 0.000001,
                  t.attemptElapsedSeconds.map({ $0 <= t.screenElapsedSeconds }) ?? true,
                  t.firstValidResultElapsedSeconds.map({ $0 <= t.screenElapsedSeconds }) ?? true,
                  t.acceptedElapsedSeconds.map({ $0 <= t.screenElapsedSeconds }) ?? true,
                  t.attemptActiveSeconds.map({ $0 <= (t.attemptElapsedSeconds ?? -1) }) ?? true,
                  t.attemptFirstResultSeconds.map({ $0 <= (t.attemptElapsedSeconds ?? -1) }) ?? true
            else { throw FieldStudyError.invalid("measurement timing") }
        }
    }
}
