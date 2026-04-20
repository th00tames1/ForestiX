// Spec §7.3 strategy C (chain across plots). REQ-CTR-003.
//
// v0.4 scope (per Phase 4 decision Q4 "minimal"): maintain a shared
// ARKit world frame across plots, remember each plot center as a
// world-space point + known lat/lon (from GPS/offset), and break the
// chain when cumulative walk exceeds 200 m since the last anchor.
// The full Umeyama rigid-alignment across multiple opening fixes is
// deferred to v0.5 — the stub `alignChainToFixes` is provided so the
// screen/persistence layers can call it today and it becomes a no-op
// until v0.5 lands.
//
// Pure data container: the chain itself is a struct of world-space
// positions + their lat/lon anchors. The iOS glue (PlotCenterViewModel
// or a dedicated PositioningService) appends entries whenever a plot
// center is confirmed and asks `transfer(from:to:)` to compute the
// lat/lon for a subsequent plot reached by walking.

import Foundation
import simd
import Models

public struct VIOChain: Sendable, Equatable {

    public struct Anchor: Sendable, Equatable {
        public let lat: Double
        public let lon: Double
        public let pointWorld: SIMD3<Float>
        public let tier: PositionTier

        public init(
            lat: Double,
            lon: Double,
            pointWorld: SIMD3<Float>,
            tier: PositionTier
        ) {
            self.lat = lat
            self.lon = lon
            self.pointWorld = pointWorld
            self.tier = tier
        }
    }

    public private(set) var anchors: [Anchor]

    /// Walk-distance budget since the last anchor above which the
    /// chain is considered untrustworthy. Defaults to 200 m per spec.
    public let maxWalkBetweenAnchorsM: Float

    public init(
        anchors: [Anchor] = [],
        maxWalkBetweenAnchorsM: Float = 200
    ) {
        self.anchors = anchors
        self.maxWalkBetweenAnchorsM = maxWalkBetweenAnchorsM
    }

    // MARK: - Chain ops

    /// Append a confirmed anchor (typically a GPS-averaged plot center
    /// the user just finished). No-op on duplicates at the same
    /// world-space point.
    public mutating func append(_ a: Anchor) {
        if let last = anchors.last,
           simd_distance(last.pointWorld, a.pointWorld) < 1e-4 { return }
        anchors.append(a)
    }

    /// Clear the chain. Called when tracking drops to `.limited` or
    /// the user exceeds the walk budget — ARKit session must be
    /// re-anchored before the chain is meaningful again.
    public mutating func reset() {
        anchors.removeAll()
    }

    /// Compute the lat/lon of a new plot reached by walking to
    /// `destinationPointWorld`, inheriting from the most recent anchor.
    /// Returns nil if the chain is empty, tracking broke, or the walk
    /// exceeds the budget (REQ-CTR-003 "break and restart the chain
    /// on walks exceeding 200 m").
    public func transfer(
        to destinationPointWorld: SIMD3<Float>,
        trackingStateWasNormalThroughout: Bool
    ) -> PlotCenterResult? {
        guard trackingStateWasNormalThroughout else { return nil }
        guard let anchor = anchors.last else { return nil }
        let walk = simd_distance(anchor.pointWorld, destinationPointWorld)
        guard walk <= maxWalkBetweenAnchorsM else { return nil }

        let delta = destinationPointWorld - anchor.pointWorld
        let east  = Double(delta.x)
        let north = Double(-delta.z)
        let metersPerDegLat = 111_320.0
        let metersPerDegLon = 111_320.0 * cos(anchor.lat * .pi / 180)

        let tier = OffsetFromOpening.demote(
            base: anchor.tier,
            walkDistanceM: walk)

        return PlotCenterResult(
            lat: anchor.lat + north / metersPerDegLat,
            lon: anchor.lon + east  / metersPerDegLon,
            source: .vioChain,
            tier: tier,
            nSamples: 0,
            medianHAccuracyM: 0,
            sampleStdXyM: 0,
            offsetWalkM: walk)
    }

    // MARK: - v0.5 deferred: multi-fix rigid alignment

    /// Stub for v0.5. Callers may invoke; it's a no-op today. Full
    /// implementation will run Umeyama SVD over (world, lat-lon)
    /// correspondences to rebaseline the chain when multiple trusted
    /// opening fixes accumulate. Kept on the API surface so the
    /// screen layer can call it without platform gates.
    public mutating func alignChainToFixes(
        _ fixes: [(pointWorld: SIMD3<Float>, lat: Double, lon: Double)]
    ) {
        _ = fixes
        // TODO v0.5: Umeyama rigid alignment. No observed multi-fix
        // data yet to calibrate against, so defer.
    }
}
