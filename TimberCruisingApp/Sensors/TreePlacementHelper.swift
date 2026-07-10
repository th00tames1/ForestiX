// Spec §7.3.2 + REQ-TAL-003. Given the plot-center ARKit world position
// captured when the plot was anchored and the camera's current world
// position at the moment a tree is added, compute the tree's bearing
// (compass deg, 0..360 clockwise from North) and horizontal distance in
// meters from plot center.
//
// World frame convention (spec §7.3 + ARKit `.gravityAndHeading`):
//   • +X → East
//   • +Y → up (against gravity)
//   • −Z → North   (so +Z = South, following ARKit's right-handed frame)
//
// This file is a pure-math helper on SIMD3<Float> so it works identically
// on-device and in `swift test` on macOS.

import Foundation
import simd

public enum TreePlacementHelper {

    public struct Placement: Sendable, Equatable {
        public let bearingDeg: Float         // 0..<360, clockwise from North
        public let distanceFromCenterM: Float

        public init(bearingDeg: Float, distanceFromCenterM: Float) {
            self.bearingDeg = bearingDeg
            self.distanceFromCenterM = distanceFromCenterM
        }
    }

    /// Returns nil if the camera is within 1 cm of plot center (no
    /// meaningful bearing). Otherwise returns bearing + horizontal distance.
    public static func placement(
        plotCenterWorld: SIMD3<Float>,
        cameraWorld: SIMD3<Float>
    ) -> Placement? {
        let dx = cameraWorld.x - plotCenterWorld.x      // east positive
        let dz = cameraWorld.z - plotCenterWorld.z      // south positive
        let horizontal = sqrt(dx * dx + dz * dz)
        guard horizontal >= 0.01 else { return nil }

        // Bearing clockwise from North. North is −Z, so:
        //   atan2(east, north) = atan2(dx, -dz)
        var bearing = atan2(dx, -dz) * 180 / .pi
        if bearing < 0 { bearing += 360 }
        return Placement(bearingDeg: bearing, distanceFromCenterM: horizontal)
    }
}
