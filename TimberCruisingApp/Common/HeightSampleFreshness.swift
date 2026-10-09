import Foundation

public enum HeightSampleFreshness {
    public static func isRecent(ageSeconds: TimeInterval) -> Bool {
        ageSeconds.isFinite && (0...0.5).contains(ageSeconds)
    }
}
