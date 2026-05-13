// Phase 19 — user-selectable DBH measurement strategy. The historical
// approach (Taubin partial-arc circle fit) was geometrically unstable on
// the narrow arcs a hand-held LiDAR phone actually sees: the centre was
// well-constrained but the radius wandered ± 5 cm tick-to-tick even when
// the cruiser stood still. Peer apps (Arboreal, ForestScanner,
// Single-Shot SAM) all use the chord/silhouette method instead — read
// off the projected trunk width in pixels at the guide row, multiply
// by depth / fx, and you have the diameter directly. We make that the
// default and demote the partial-arc path to an opt-in legacy choice.

import Foundation

public enum DBHMeasurementMethod: String, Codable, Sendable, CaseIterable {
    /// Phase 19 default. Reads the trunk's projected width in pixels at
    /// (and just around) the guide row, converts to metres via the
    /// depth-pinhole identity `diameter_m = pixel_width × depth / fx`,
    /// and takes a multi-row median for branch / leaf robustness. No
    /// circle fit, no RANSAC — just geometry the LiDAR can see directly.
    case chord
    /// Pre-Phase-19 default. Back-projects every stem-strip pixel into
    /// world XZ, runs RANSAC + Taubin to fit a circle to the partial
    /// arc, then derives the diameter from the fitted radius. Still
    /// available as an opt-in for cruisers who specifically want the
    /// partial-arc geometry on heavily eccentric trunks.
    case partialArcCircleFit

    public var displayName: String {
        switch self {
        case .chord:                return "Chord (silhouette)"
        case .partialArcCircleFit:  return "Partial-arc circle fit"
        }
    }
}
