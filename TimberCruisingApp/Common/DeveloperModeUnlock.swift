import Foundation

/// Hidden map gesture. Counts only a continuous sequence, never lifetime taps.
public struct DeveloperModeUnlock: Sendable {
    public static let requiredTaps = 7
    public static let maximumGapSeconds: TimeInterval = 1.2
    private var count = 0
    private var lastTap: TimeInterval?

    public init() {}

    /// An old public-toggle preference does not authorize the new hidden mode.
    public static func isEnabled(savedEnabled: Bool, gestureUnlocked: Bool) -> Bool {
        savedEnabled && gestureUnlocked
    }

    public mutating func reset() {
        count = 0
        lastTap = nil
    }

    /// Supply monotonic uptime, not wall-clock time.
    public mutating func tap(at now: TimeInterval) -> Bool {
        guard now.isFinite else { reset(); return false }
        if let lastTap, now < lastTap || now - lastTap > Self.maximumGapSeconds {
            reset()
        }
        lastTap = now
        count += 1
        guard count == Self.requiredTaps else { return false }
        reset()
        return true
    }
}
