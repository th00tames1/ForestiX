// Which DBH sensing path the cruiser has selected on the scan screen.
// This is the user-facing 3-way method picker that underpins the
// within-device LiDAR-vs-AR comparison study:
//
//   • lidarDepth — depth point-cloud circle-fit / chord (LiDAR only)
//   • arMotion   — circle-fit to accumulated VIO feature points (no LiDAR)
//   • arCaliper  — two-tap trunk edges × AR-estimated distance (no LiDAR)
//
// Orthogonal to DBHMeasurementMethod (chord vs partial-arc), which only
// refines the LiDAR depth path. Persisted independently in AppSettings.

import Foundation

public enum DBHMethodSource: String, CaseIterable, Codable, Sendable {
    case lidarDepth
    case arMotion
    case arCaliper

    public var displayName: String {
        switch self {
        case .lidarDepth: return "LiDAR"
        case .arMotion:   return "AR motion"
        case .arCaliper:  return "AR caliper"
        }
    }

    /// Short tag for the dev HUD / research log.
    public var shortTag: String {
        switch self {
        case .lidarDepth: return "lidar-depth"
        case .arMotion:   return "ar-motion"
        case .arCaliper:  return "ar-caliper"
        }
    }

    /// True for the two depth-free AR paths (run on every iPhone).
    public var isAR: Bool { self != .lidarDepth }
}
