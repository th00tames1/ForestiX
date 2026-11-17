import Foundation

/// Use the timestamp attached to the pose, never the time a UI task processes it.
/// The 4 m/s admission limit is unchanged; queued frames must not look faster.
public struct CameraPoseSpeedGuard {
    public static let maximumSpeedMetersPerSecond: Float = 4
    private var previous: (position: SIMD3<Float>, timestamp: TimeInterval)?
    public init() {}
    public mutating func reset() { previous = nil }
    public mutating func observe(position: SIMD3<Float>?, timestamp: TimeInterval?) -> Bool {
        guard let position, let timestamp, timestamp.isFinite,
              position.x.isFinite, position.y.isFinite, position.z.isFinite else {
            reset(); return false
        }
        guard let old = previous else { previous = (position, timestamp); return false }
        let dt = timestamp - old.timestamp
        // Duplicate/out-of-order frames do not replace the last real sample.
        guard dt >= 0.01 else { return false }
        previous = (position, timestamp)
        guard dt <= 0.5 else { return false }
        let delta = position - old.position
        let distance = (delta.x * delta.x + delta.y * delta.y + delta.z * delta.z).squareRoot()
        return distance / Float(dt) > Self.maximumSpeedMetersPerSecond
    }
}
