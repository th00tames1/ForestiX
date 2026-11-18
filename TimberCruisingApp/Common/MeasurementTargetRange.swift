import Foundation

/// Admission for live AR target placement, not an estimator or a correction.
/// The physical limit never changes with display units. Do not use the
/// short-range anchor maximum for distant height base/top sightings.
public enum MeasurementTargetRange {
    public static let minimumMeters: Float = 0.5
    public enum Rejection: Equatable, Sendable { case unavailable, tooClose, tooFar }

    public static func rejection(distanceMeters: Float?, maximumMeters: Float? = nil) -> Rejection? {
        guard let d = distanceMeters, d.isFinite, d >= 0 else { return .unavailable }
        if let maximumMeters, !maximumMeters.isFinite || maximumMeters < minimumMeters { return .unavailable }
        if d < minimumMeters { return .tooClose }
        if let maximumMeters, d > maximumMeters { return .tooFar }
        return nil
    }
    public static func distance(camera: SIMD3<Float>, target: SIMD3<Float>) -> Float {
        let v = target - camera
        return (v.x*v.x + v.y*v.y + v.z*v.z).squareRoot()
    }
    public static func tooCloseMessage(imperial: Bool) -> String {
        // Round the advice up in feet so following it clears the metric gate.
        let limit = imperial ? "2 ft" : "0.5 m"
        return "Move back — keep at least \(limit) from the target."
    }
}
