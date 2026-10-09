import Foundation

/// Presentation-only debounce. Measurement locks and capture safety never wait
/// for this filter. A transient detector result must not flash a new instruction.
public struct StableGuidanceMessage {
    public private(set) var displayed: String?
    private var candidate: String?
    private var candidateSince: TimeInterval
    private var displayedSince: TimeInterval
    public init(initial: String?, now: TimeInterval) {
        displayed = initial; candidate = initial
        candidateSince = now; displayedSince = now
    }
    public mutating func update(_ message: String?, now: TimeInterval) -> String? {
        if now < candidateSince || now < displayedSince {
            self = Self(initial: message, now: now)
        }
        if message != candidate { candidate = message; candidateSince = now }
        if candidate != displayed, now - candidateSince >= 1,
           now - displayedSince >= 1.5 {
            displayed = candidate; displayedSince = now
        }
        return displayed
    }
}
