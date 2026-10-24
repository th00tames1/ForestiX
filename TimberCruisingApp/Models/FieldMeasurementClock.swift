import Foundation

public enum FieldContinuousTime {
    private static let origin = ContinuousClock.now
    /// ContinuousClock includes suspension; wall-clock adjustments cannot alter elapsed time.
    public static func seconds() -> Double {
        let c = origin.duration(to: .now).components
        return Double(c.seconds) + Double(c.attoseconds) / 1e18
    }
}

/// A screen/session clock survives retakes; a new target starts a new instance.
/// No timer callback, AR/GPS access or estimator dependency is required.
public struct FieldMeasurementClock: Sendable {
    private let started: Double
    private var last: Double
    private var backgroundStarted: Double?
    private var accumulatedBackground = 0.0
    private var attemptStarted: Double?
    private var attemptBackgroundAtStart = 0.0
    private var firstResult: Double?
    private var attemptFirstResult: Double?
    private var accepted: Double?
    private var valid = true
    public init(now: Double) {
        started = now.isFinite && now >= 0 ? now : 0
        last = started; valid = now.isFinite && now >= 0
    }
    @discardableResult private mutating func advance(_ now: Double) -> Bool {
        guard now.isFinite, now >= last else { valid = false; return false }
        last = now; return true
    }
    private func totalBackground(_ now: Double) -> Double {
        accumulatedBackground + (backgroundStarted.map { max(0, now - $0) } ?? 0)
    }
    public mutating func setForeground(_ foreground: Bool, now: Double) {
        guard advance(now) else { return }
        if foreground {
            if let start = backgroundStarted { accumulatedBackground += now - start; backgroundStarted = nil }
        } else if backgroundStarted == nil { backgroundStarted = now }
    }
    public mutating func beginAttempt(now: Double) {
        guard advance(now) else { return }
        attemptStarted = now; attemptBackgroundAtStart = totalBackground(now)
        attemptFirstResult = nil; accepted = nil
    }
    public mutating func noteResult(validResult: Bool, now: Double) {
        guard advance(now), validResult else { return }
        if firstResult == nil { firstResult = now - started }
        if attemptFirstResult == nil, let start = attemptStarted { attemptFirstResult = now - start }
    }
    public mutating func noteAccepted(now: Double) {
        guard advance(now) else { return }
        if accepted == nil { accepted = now - started }
    }
    public mutating func snapshot(now: Double) -> FieldTimingSnapshot {
        _ = advance(now)
        let elapsed = last - started; let bg = min(elapsed, totalBackground(last))
        let attemptElapsed = attemptStarted.map { last - $0 }
        let attemptActive = attemptElapsed.map { max(0, $0 - (bg - attemptBackgroundAtStart)) }
        return .init(screenElapsedSeconds: elapsed, activeElapsedSeconds: max(0, elapsed-bg), backgroundSeconds: bg,
                     attemptElapsedSeconds: attemptElapsed, attemptActiveSeconds: attemptActive,
                     firstValidResultElapsedSeconds: firstResult, attemptFirstResultSeconds: attemptFirstResult,
                     acceptedElapsedSeconds: accepted, clockValid: valid)
    }
}
