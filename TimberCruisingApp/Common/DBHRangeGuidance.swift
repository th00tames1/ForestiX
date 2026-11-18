import Foundation

/// Presentation only: inspect fresh, unfiltered centre depth even when Auto
/// has no mask. Never feed this diagnostic back into a fit or capture gate.
public enum DBHRangeGuidance {
    public static let maximumAgeSeconds: TimeInterval = 0.5

    public static func message(distanceMeters: Float?, ageSeconds: TimeInterval, imperial: Bool) -> String? {
        guard ageSeconds.isFinite, ageSeconds >= 0, ageSeconds <= maximumAgeSeconds,
              let d = distanceMeters, d.isFinite, d > 0 else { return nil }
        if d < 0.5 { return MeasurementTargetRange.tooCloseMessage(imperial: imperial) }
        if d > 5 { return "Move closer — keep within \(imperial ? "16 ft" : "5 m") of the target." }
        return nil
    }

    /// Small 5x5 neighbourhood, before the measurement's distance rejection.
    /// Missing depth is unknown, not evidence that the target is too close.
    public static func median(centerX: Double, centerY: Double, width: Int, height: Int,
                              depthAt: (Int, Int) -> Float) -> Float? {
        guard centerX.isFinite, centerY.isFinite, centerX >= 0, centerY >= 0,
              centerX < Double(width), centerY < Double(height) else { return nil }
        let cx = Int(centerX.rounded()), cy = Int(centerY.rounded())
        guard cx < width, cy < height else { return nil }
        var values: [Float] = []
        values.reserveCapacity(25)
        for y in max(0, cy-2)...min(height-1, cy+2) {
            for x in max(0, cx-2)...min(width-1, cx+2) {
                let d = depthAt(x,y)
                if d.isFinite, d > 0 { values.append(d) }
            }
        }
        guard !values.isEmpty else { return nil }
        values.sort()
        return values[values.count/2]
    }
}
