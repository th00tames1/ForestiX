import Foundation

/// Measurement lifecycle evidence, never an estimator/capture permission.
public enum FieldCaptureEvent: Sendable {
    case started(id: String, rawRequested: Bool, aiEnabled: Bool, typed: Bool)
    case result(id: String, value: Double?, sigma: Double?, valid: Bool, reason: String?, ai: FieldAIProvenance)
    case refused(id: String, reason: String, ai: FieldAIProvenance?)
    case abandoned(id: String, reason: String)
    case stageRefused(id: String, stage: String, reason: String)
    case raw(id: String, status: FieldRawStatus, reason: String?, replayValue: Double?)
}

/// One screen/target session, with one ID per attempt. Persistence is a caller
/// responsibility so no IO, camera, GPS or estimator dependency enters this type.
public struct FieldMeasurementSession: Sendable {
    public private(set) var sessionId: String
    public private(set) var currentId: String?
    public private(set) var current: FieldMeasurementRecord?
    public private(set) var attemptNumber = 0
    private var clock: FieldMeasurementClock
    public init(now: Double, sessionId: String = UUID().uuidString) {
        self.sessionId = sessionId; clock = FieldMeasurementClock(now: now)
    }
    public mutating func setForeground(_ foreground: Bool, now: Double) {
        clock.setForeground(foreground,now: now)
        if current?.configuration.recordTiming == true { current?.timing = clock.snapshot(now: now) }
    }
    public mutating func begin(id: String, configuration: FieldStudyConfiguration, target: FieldStudyTarget?,
                               kind: FieldStudyKind, runtime: [String: String], references: [String],
                               rawRequested: Bool, aiEnabled: Bool, typed: Bool, now: Double,scope: FieldMeasurementScope? = nil,
                               protocolBinding: FieldProtocolBinding? = nil) throws {
        guard UUID(uuidString: id) != nil else { throw FieldStudyError.invalid("attempt UUID") }
        if let current, current.selection == .pending, current.outcome == .started || current.outcome == .result {
            throw FieldStudyError.conflict("unfinished attempt must be retired before a new one")
        }
        clock.beginAttempt(now: now); attemptNumber += 1; currentId = id
        guard configuration.enabled else { current = nil; return }
        try configuration.validate()
        if let target { try target.validate(); guard target.studyId == configuration.studyId else { throw FieldStudyError.invalid("capture target study") } }
        // An unassigned measurement is explicit and cannot accidentally join a
        // surveyed tree merely because its local display number happened to match.
        var record = FieldMeasurementRecord(id: id,sessionId: sessionId,treeId: target?.treeId ?? "unassigned:"+id,
            targetId: target?.id,targetSnapshot: target,kind: kind,attemptNumber: attemptNumber,
            configuration: configuration,runtimeSettings: runtime)
        record.referenceIds = references
        record.measurementScope = scope
        record.protocolBinding = protocolBinding
        record.rawStatus = rawRequested && !typed ? .pending : .notRequested
        record.rawBundleId = record.rawStatus == .pending ? id : nil
        record.ai.enabled = aiEnabled && !typed
        record.ai.reason = typed ? "typed_measurement" : nil
        if configuration.recordTiming { record.timing = clock.snapshot(now: now) }
        record.events = [.init(action: "attempt_started",detail: typed ? "typed" : "sensor")]
        if target == nil { record.events.append(.init(action: "target_unassigned")) }
        try record.validate(); current = record
    }
    public mutating func finish(id: String, value: Double?, sigma: Double?, valid: Bool,
                                reason: String?, ai: FieldAIProvenance, now: Double) throws {
        guard var record = current, record.id == id, record.outcome == .started else { return }
        clock.noteResult(validResult: valid,now: now)
        record.ai = ai
        record.liveValue = value.flatMap { $0.isFinite && $0 > 0 ? $0 : nil }
        record.liveSigma = sigma.flatMap { $0.isFinite && $0 >= 0 ? $0 : nil }
        record.outcome = valid && record.liveValue != nil ? .result : .failed
        record.failureReason = record.outcome == .failed ? reason ?? "no_valid_result" : nil
        record.events.append(.init(action: record.outcome == .result ? "result_produced" : "attempt_failed",detail: reason))
        if record.configuration.recordTiming { record.timing = clock.snapshot(now: now) }
        try record.validate(); current = record
    }
    public mutating func refuse(id: String, reason: String, ai: FieldAIProvenance? = nil, now: Double) throws {
        guard var record = current, record.id == id, record.outcome == .started else { return }
        record.outcome = .failed; record.failureReason = reason
        if let ai { record.ai = ai }
        if record.rawStatus == .pending { record.rawStatus = .failed; record.rawFailureReason = "capture_refused: "+reason }
        record.events.append(.init(action: "attempt_failed",detail: reason))
        if record.configuration.recordTiming { record.timing = clock.snapshot(now: now) }
        try record.validate(); current = record
    }
    public mutating func abandon(id: String, reason: String, now: Double) throws {
        guard var record = current, record.id == id, record.selection == .pending else { return }
        if record.outcome == .started { record.outcome = .cancelled; record.failureReason = reason }
        record.selection = .discarded; record.events.append(.init(action: "discarded",detail: reason))
        if record.rawStatus == .pending, record.outcome == .cancelled { record.rawStatus = .failed; record.rawFailureReason = "capture_cancelled" }
        if record.configuration.recordTiming { record.timing = clock.snapshot(now: now) }
        try record.validate(); current = record
    }
    public mutating func saveFailed(id: String, reason: String, now: Double) {
        guard current?.id == id, current?.selection == .pending else { return }
        current?.events.append(.init(action: "result_save_failed",detail: reason))
        if current?.configuration.recordTiming == true { current?.timing = clock.snapshot(now: now) }
    }
    public mutating func noteStageRefusal(id: String, stage: String, reason: String, now: Double) {
        guard current?.id == id, current?.outcome == .started else { return }
        current?.events.append(.init(action: "stage_refused",detail: stage+": "+reason))
        if current?.configuration.recordTiming == true { current?.timing = clock.snapshot(now: now) }
    }
    public mutating func accept(id: String, resultRecordId: String, resultStore: String, now: Double) throws {
        guard var record = current, record.id == id else { return }
        guard UUID(uuidString: resultRecordId) != nil, ["quick_history","cruise"].contains(resultStore)
        else { throw FieldStudyError.invalid("application result binding") }
        guard record.outcome == .result, record.liveValue != nil else { throw FieldStudyError.conflict("cannot accept an invalid attempt") }
        if record.selection == .accepted { return }
        guard record.selection == .pending else { throw FieldStudyError.conflict("discarded attempt") }
        clock.noteAccepted(now: now); record.selection = .accepted
        record.resultRecordId = resultRecordId; record.resultStore = resultStore
        record.events.append(.init(action: "result_saved",detail: resultStore+":"+resultRecordId))
        if record.configuration.recordTiming { record.timing = clock.snapshot(now: now) }
        try record.validate(); current = record
    }
    public var shouldPersistCurrent: Bool {
        guard let current else { return false }
        return current.configuration.recordFailedAttempts || current.outcome == .result
    }
    public mutating func updateUIFlow(_ evidence: FieldUIFlowTiming) throws {
        guard var record = current,record.configuration.recordTiming else { return }
        guard evidence.continues(record.uiFlowTiming) else { throw FieldStudyError.conflict("UI flow snapshot transition") }
        record.uiFlowTiming = evidence; try record.validate(); current = record
    }
}
