import Foundation

public enum AutoSafetyPolicy {
    public static let maximumSeconds: TimeInterval = 60
    public static let captureProcessingSeconds: TimeInterval = 5
    public static let captureTimeoutMessage = "AI processing timed out. Use Adjust or retry Auto."
    public static let captureBusyMessage = "AI is still finishing. Use Adjust and retry Auto shortly."
    public static let timeoutMessage = "Auto stopped after 1 minute to reduce heat. Use Adjust."
    public static let heatMessage = "Device is hot. Use Adjust and let it cool down."
    public static func shouldReturnToAdjust(startedAt: TimeInterval, now: TimeInterval,
                                           isAiming: Bool) -> Bool {
        isAiming && startedAt.isFinite && now.isFinite && now - startedAt >= maximumSeconds
    }
}
