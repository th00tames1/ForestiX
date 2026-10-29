import Foundation

public enum AutoSafetyPolicy {
    public static let maximumSeconds: TimeInterval = 60
    public static let captureProcessingSeconds: TimeInterval = 5
    public static let liveErrorRetrySeconds: TimeInterval = 0.75
    public static let pairedInputWaitSeconds: TimeInterval = 0.25
    public static let pairedInputPollSeconds: TimeInterval = 0.016
    public static let liveRetryMessage = "Auto temporarily unavailable. Retrying…"
    public static let captureInputMessage = "Waiting for a fresh camera and depth frame."
    public static let captureChangedMessage = "Camera alignment or survey settings changed. Retry capture."
    public static let captureNoStemMessage = "No stem at the crosshair. Aim at the trunk or use Adjust."
    public static let captureInvalidDepthMessage = "Couldn't read depth in the captured trunk. Retry capture."
    public static let captureInferenceMessage = "Auto capture failed. Retry or use Adjust."
    public static let captureTimeoutMessage = "AI processing timed out. Use Adjust or retry Auto."
    public static let captureBusyMessage = "AI is still finishing. Use Adjust and retry Auto shortly."
    public static let timeoutMessage = "Auto stopped after 1 minute to reduce heat. Use Adjust."
    public static let heatMessage = "Device is hot. Use Adjust and let it cool down."
    /// Apply recovery only to an actual inference error, not a missing stem.
    /// The caller supplies its unchanged device/thermal-specific healthy cadence.
    public static func liveInferenceDelaySeconds(cadenceDelay: TimeInterval, failed: Bool) -> TimeInterval {
        guard cadenceDelay.isFinite, cadenceDelay >= 0 else { return liveErrorRetrySeconds }
        return failed ? max(cadenceDelay, liveErrorRetrySeconds) : cadenceDelay
    }
    public static func shouldReturnToAdjust(startedAt: TimeInterval, now: TimeInterval,
                                           isAiming: Bool) -> Bool {
        isAiming && startedAt.isFinite && now.isFinite && now - startedAt >= maximumSeconds
    }
}
