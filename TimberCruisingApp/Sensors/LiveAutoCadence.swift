import Foundation

/// One inference at a time; 16 Hz nominal, original 8 Hz under thermal pressure.
public enum LiveAutoCadence {
    public static let idleSeconds = 0.0625
    public static func delaySeconds(elapsed: Double, thermallyLimited: Bool = false) -> Double {
        let period = thermallyLimited ? 0.125 : 0.0625
        guard elapsed.isFinite, elapsed >= 0 else { return period }
        return max(thermallyLimited ? 0.02 : 0.002,period-elapsed)
    }
}
