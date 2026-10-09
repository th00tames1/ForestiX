import Foundation

/// Live presentation safety only. Never infer an orientation from depth content.
public enum PortraitCaptureSafety {
    public enum Status: Equatable, Sendable {
        case ready, inactive, transitioning, needsPortrait, needsFullScreen, needsLock

        public var message: String? {
            switch self {
            case .ready: return nil
            case .inactive: return "Return to the app to measure."
            case .transitioning: return "Waiting for screen alignment."
            case .needsPortrait: return "Waiting for portrait orientation."
            case .needsFullScreen: return "Open the app full screen to measure."
            case .needsLock: return "Waiting for screen rotation lock."
            }
        }
    }

    public static func status(isPad: Bool, requiresSceneLock: Bool,
                              isActive: Bool, isPortrait: Bool,
                              isFullScreen: Bool, isLocked: Bool,
                              isTransitioning: Bool) -> Status {
        guard isActive else { return .inactive }
        guard !isTransitioning else { return .transitioning }
        guard isPortrait else { return .needsPortrait }
        if isPad {
            guard isFullScreen else { return .needsFullScreen }
            if requiresSceneLock && !isLocked { return .needsLock }
        }
        return .ready
    }

    /// A queued frame from an earlier viewport must not reopen the capture gate.
    public static func acceptsFrame(currentRevision: UInt64, frameRevision: UInt64?) -> Bool {
        frameRevision == currentRevision
    }
}
