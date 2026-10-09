import Foundation

public enum AutoSafetyPolicy {
    public static let maximumSeconds: TimeInterval = 30
    public static let timeoutMessage = "Auto stopped after 30 seconds to reduce heat. Use Adjust."
    public static let heatMessage = "Device is hot. Use Adjust and let it cool down."
    public static func shouldReturnToAdjust(startedAt: TimeInterval, now: TimeInterval,
                                           isAiming: Bool) -> Bool {
        isAiming && startedAt.isFinite && now.isFinite && now - startedAt >= maximumSeconds
    }
}
