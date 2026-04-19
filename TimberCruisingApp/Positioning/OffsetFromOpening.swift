// Spec §7.3.2 Offset-from-Opening. REQ-CTR-002.
//
// Under canopy GPS is often unusable, but ARKit VIO holds sub-metre
// relative accuracy over ≤ 200 m walks. So: get a clean GPS fix at a
// nearby opening, record the ARKit pose there, walk back to the plot
// center under continuous ARKit tracking, and subtract the pose
// difference to recover the plot center in lat/lon.
//
// Pure function: all state (opening fix, opening pose, plot pose,
// tracking-was-normal flag, compass heading at opening) is packed
// into `Input`. The Screen layer (OffsetFlowViewModel) snapshots
// these from the live ARKit session at each step then hands the tuple
// in here for the final geometry.
//
// Compass note: because ARKit was run with `.gravityAndHeading`,
// world-X ≈ East and world-−Z ≈ North to within a degree. The spec
// explicitly tells us NOT to apply an additional rotation from
// compass heading — the gravity-and-heading alignment is the rotation.

import Foundation
import simd
import Models

public enum OffsetFromOpening {

    public struct Input: Sendable {
        public let openingFix: PlotCenterResult
        public let openingPointWorld: SIMD3<Float>
        public let plotPointWorld: SIMD3<Float>
        public let trackingStateWasNormalThroughout: Bool

        public init(
            openingFix: PlotCenterResult,
            openingPointWorld: SIMD3<Float>,
            plotPointWorld: SIMD3<Float>,
            trackingStateWasNormalThroughout: Bool
        ) {
            self.openingFix = openingFix
            self.openingPointWorld = openingPointWorld
            self.plotPointWorld = plotPointWorld
            self.trackingStateWasNormalThroughout = trackingStateWasNormalThroughout
        }
    }

    /// §7.3.2 algorithm. Returns nil when tracking was not normal at
    /// some point in the walk (REQ-CTR-002).
    public static func compute(input: Input) -> PlotCenterResult? {
        guard input.trackingStateWasNormalThroughout else { return nil }

        let delta = input.plotPointWorld - input.openingPointWorld
        let walkDistance = simd_length(delta)

        // Spec step 5: world X ≈ east, world −Z ≈ north under
        // .gravityAndHeading. No extra rotation.
        let east  = Double(delta.x)
        let north = Double(-delta.z)

        // Spec step 6: convert ENU displacement to lat/lon delta at
        // the opening fix using the same linear approximation as
        // GPSAveraging. The approximation holds to <1 ppm over 200 m.
        let metersPerDegLat = 111_320.0
        let metersPerDegLon = 111_320.0 *
            cos(input.openingFix.lat * .pi / 180)
        let dLat = north / metersPerDegLat
        let dLon = east  / metersPerDegLon

        let tier = demote(
            base: input.openingFix.tier,
            walkDistanceM: walkDistance)

        return PlotCenterResult(
            lat: input.openingFix.lat + dLat,
            lon: input.openingFix.lon + dLon,
            source: .vioOffset,
            tier: tier,
            nSamples: input.openingFix.nSamples,
            medianHAccuracyM: input.openingFix.medianHAccuracyM,
            sampleStdXyM: input.openingFix.sampleStdXyM,
            offsetWalkM: walkDistance)
    }

    /// §7.3.2 tier rule: inherit the opening fix tier, demote one
    /// step if the walk was > 100 m, and clamp to D if > 200 m (the
    /// spec says "too much drift expected").
    public static func demote(
        base: PositionTier,
        walkDistanceM d: Float
    ) -> PositionTier {
        if d > 200 { return .D }
        if d > 100 { return oneStepDown(base) }
        return base
    }

    @inlinable
    static func oneStepDown(_ t: PositionTier) -> PositionTier {
        switch t {
        case .A: return .B
        case .B: return .C
        case .C: return .D
        case .D: return .D
        }
    }
}
