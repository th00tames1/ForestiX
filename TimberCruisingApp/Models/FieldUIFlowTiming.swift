import Foundation

public struct FieldUIFlowDuration: Codable, Equatable, Sendable {
    public var elapsedSeconds: Double
    public var foregroundSeconds: Double
    public var inactiveSeconds: Double
    public var clockValid: Bool
    public init(elapsedSeconds: Double,foregroundSeconds: Double,inactiveSeconds: Double,clockValid: Bool) {
        self.elapsedSeconds = elapsedSeconds; self.foregroundSeconds = foregroundSeconds
        self.inactiveSeconds = inactiveSeconds; self.clockValid = clockValid
    }
    public func validate() throws {
        guard [elapsedSeconds,foregroundSeconds,inactiveSeconds].allSatisfy({ $0.isFinite && $0 >= 0 }),
              abs(elapsedSeconds-foregroundSeconds-inactiveSeconds) < 0.000001 else { throw FieldStudyError.invalid("UI flow duration") }
    }
}

/// Original visible-screen flow, distinct from target/session/attempt timing.
/// Foreground is APP foreground time, not inferred screen work or sensor uptime.
public struct FieldUIFlowTiming: Codable, Equatable, Sendable {
    public var schema = 1
    public var id: String
    public var kind: FieldStudyKind
    public var openedAt: String
    public var current: FieldUIFlowDuration
    public var atResult: FieldUIFlowDuration?
    public var atAccepted: FieldUIFlowDuration?
    public init(id: String,kind: FieldStudyKind,openedAt: String,current: FieldUIFlowDuration,
                atResult: FieldUIFlowDuration? = nil,atAccepted: FieldUIFlowDuration? = nil) {
        self.id = id; self.kind = kind; self.openedAt = openedAt; self.current = current
        self.atResult = atResult; self.atAccepted = atAccepted
    }
    public func validate() throws {
        guard schema == 1,UUID(uuidString: id) != nil,!openedAt.isEmpty else { throw FieldStudyError.invalid("UI flow identity") }
        try current.validate(); try atResult?.validate(); try atAccepted?.validate()
        guard atResult.map({ $0.elapsedSeconds <= current.elapsedSeconds }) ?? true,
              atAccepted.map({ $0.elapsedSeconds <= current.elapsedSeconds }) ?? true,
              atAccepted.map({ $0.elapsedSeconds >= (atResult?.elapsedSeconds ?? 0) }) ?? true else { throw FieldStudyError.invalid("UI flow event order") }
    }
    public func continues(_ previous: Self?) -> Bool {
        guard let previous else { return true }
        return id == previous.id && kind == previous.kind && openedAt == previous.openedAt &&
            current.elapsedSeconds >= previous.current.elapsedSeconds &&
            current.foregroundSeconds + 0.000001 >= previous.current.foregroundSeconds && current.inactiveSeconds + 0.000001 >= previous.current.inactiveSeconds &&
            (previous.current.clockValid || !current.clockValid) &&
            (previous.atResult == nil || previous.atResult == atResult) &&
            (previous.atAccepted == nil || previous.atAccepted == atAccepted)
    }
}

/// Lazy first appearance. Reusing this reference across a target rebind preserves
/// selection/setup delay without carrying another target's attempt or result.
public final class FieldUIFlowClock: @unchecked Sendable {
    public let id: String
    public let kind: FieldStudyKind
    private let lock = NSLock()
    private var clock: FieldMeasurementClock?
    private var openedAt: String?
    public init(kind: FieldStudyKind,id: String = UUID().uuidString) { self.kind = kind; self.id = id }
    public func open(now: Double,foreground: Bool,openedAt: String = FieldStudyJSON.timestamp()) {
        lock.lock(); defer { lock.unlock() }
        if clock == nil { clock = FieldMeasurementClock(now: now); self.openedAt = openedAt }
        clock?.setForeground(foreground,now: now)
    }
    public func foreground(_ active: Bool,now: Double) {
        lock.lock(); defer { lock.unlock() }; clock?.setForeground(active,now: now)
    }
    public func evidence(now: Double,previous: FieldUIFlowTiming?,result: Bool,accepted: Bool) throws -> FieldUIFlowTiming? {
        lock.lock(); defer { lock.unlock() }
        guard let t = clock?.snapshot(now: now),let openedAt else { return nil }
        let duration = FieldUIFlowDuration(elapsedSeconds: t.screenElapsedSeconds,foregroundSeconds: t.activeElapsedSeconds,
            inactiveSeconds: t.backgroundSeconds,clockValid: t.clockValid)
        let next = FieldUIFlowTiming(id: id,kind: kind,openedAt: openedAt,current: duration,
            atResult: previous?.atResult ?? (result ? duration : nil),atAccepted: previous?.atAccepted ?? (accepted ? duration : nil))
        try next.validate()
        guard next.continues(previous) else { throw FieldStudyError.conflict("UI flow identity or frozen event") }
        return next
    }
}

/// App lifecycle only. Navigation disappearance must never enter this registry.
/// Weak references and no periodic work keep closed flows from retaining screens.
public final class FieldUIFlowRegistry: @unchecked Sendable {
    public static let shared = FieldUIFlowRegistry()
    private final class WeakFlow { weak var value: FieldUIFlowClock?; init(_ value: FieldUIFlowClock) { self.value = value } }
    private let lock = NSLock()
    private var owners = Set<String>()
    private var flows: [String:WeakFlow] = [:]
    public init() {}
    public func owner(_ id: String,active: Bool,now: Double) {
        lock.lock(); defer { lock.unlock() }
        let before = !owners.isEmpty
        if active { owners.insert(id) } else { owners.remove(id) }
        flows = flows.filter { $0.value.value != nil }
        let after = !owners.isEmpty
        if before != after { for f in flows.values { f.value?.foreground(after,now: now) } }
    }
    public func track(_ flow: FieldUIFlowClock,now: Double) {
        lock.lock(); defer { lock.unlock() }
        flows = flows.filter { $0.value.value != nil }; flows[flow.id] = WeakFlow(flow)
        flow.open(now: now,foreground: !owners.isEmpty)
    }
}
