// Which DBH sensing path the cruiser has selected on the scan screen.
//
// FIELD FIX — this used to be a 3-way picker (LiDAR depth / AR motion /
// AR caliper) for the within-device comparison study. The two AR arms are
// GONE: they recorded no raw captures, so nothing measured with them could
// ever be re-derived offline, and the picker put a mis-tap between the
// cruiser and the only path that produces research-grade data. Exactly one
// method remains.
//
//   • lidarDepth — depth point-cloud circle-fit / chord (LiDAR only)
//
// The type is kept (rather than deleted) because its raw value is the
// `depth_source` column of the research CSV and the raw-capture manifest;
// keeping it single-valued keeps those exports joinable with the rows the
// study has already collected. Android matches.
//
// Orthogonal to DBHMeasurementMethod (chord vs partial-arc), which only
// refines the LiDAR depth path.

import Foundation

public enum DBHMethodSource: String, CaseIterable, Codable, Sendable {
    case lidarDepth

    public var displayName: String {
        switch self {
        case .lidarDepth: return "LiDAR"
        }
    }

    /// Short tag for the dev HUD / research log.
    public var shortTag: String {
        switch self {
        case .lidarDepth: return "lidar-depth"
        }
    }
}
