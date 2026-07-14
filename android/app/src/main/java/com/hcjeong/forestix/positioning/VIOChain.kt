// VIO chain — 1:1 port of iOS Positioning/VIOChain.swift
// (spec §7.3 strategy C, chain across plots. REQ-CTR-003).
//
// v0.4 scope (per Phase 4 decision Q4 "minimal"): maintain a shared
// AR world frame across plots, remember each plot center as a
// world-space point + known lat/lon (from GPS/offset), and break the
// chain when cumulative walk exceeds 200 m since the last anchor.
// The full Umeyama rigid-alignment across multiple opening fixes is
// deferred to v0.5 — the stub `alignChainToFixes` is provided so the
// screen/persistence layers can call it today and it becomes a no-op
// until v0.5 lands.
//
// Data container: the chain itself is a list of world-space positions
// + their lat/lon anchors. The platform glue (the centre-recording sheet or a
// dedicated PositioningService) appends entries whenever a plot center
// is confirmed and asks `transfer(to:)` to compute the lat/lon for a
// subsequent plot reached by walking.

package com.hcjeong.forestix.positioning

import com.hcjeong.forestix.ar.Vec3
import com.hcjeong.forestix.ar.distance
import com.hcjeong.forestix.data.cruise.PlotCenterResult
import com.hcjeong.forestix.data.cruise.PositionSource
import com.hcjeong.forestix.data.cruise.PositionTier

class VIOChain(
    anchors: List<Anchor> = emptyList(),
    /// Walk-distance budget since the last anchor above which the
    /// chain is considered untrustworthy. Defaults to 200 m per spec.
    val maxWalkBetweenAnchorsM: Float = 200f,
) {

    data class Anchor(
        val lat: Double,
        val lon: Double,
        val pointWorld: Vec3,
        val tier: PositionTier,
    )

    private val _anchors: MutableList<Anchor> = anchors.toMutableList()
    val anchors: List<Anchor> get() = _anchors

    // MARK: - Chain ops

    /// Append a confirmed anchor (typically a GPS-averaged plot center
    /// the user just finished). No-op on duplicates at the same
    /// world-space point.
    fun append(a: Anchor) {
        val last = _anchors.lastOrNull()
        if (last != null && distance(last.pointWorld, a.pointWorld) < 1e-4f) return
        _anchors.add(a)
    }

    /// Clear the chain. Called when tracking drops or the user exceeds
    /// the walk budget — the AR session must be re-anchored before the
    /// chain is meaningful again.
    fun reset() {
        _anchors.clear()
    }

    /// Compute the lat/lon of a new plot reached by walking to
    /// `destinationPointWorld`, inheriting from the most recent anchor.
    /// Returns null if the chain is empty, tracking broke, or the walk
    /// exceeds the budget (REQ-CTR-003 "break and restart the chain
    /// on walks exceeding 200 m").
    fun transfer(
        destinationPointWorld: Vec3,
        trackingStateWasNormalThroughout: Boolean,
    ): PlotCenterResult? {
        if (!trackingStateWasNormalThroughout) return null
        val anchor = _anchors.lastOrNull() ?: return null
        val walk = distance(anchor.pointWorld, destinationPointWorld)
        if (walk > maxWalkBetweenAnchorsM) return null

        val delta = destinationPointWorld - anchor.pointWorld
        val east = delta.x.toDouble()
        val north = (-delta.z).toDouble()
        val metersPerDegLat = 111_320.0
        val metersPerDegLon = 111_320.0 * kotlin.math.cos(anchor.lat * kotlin.math.PI / 180)

        val tier = OffsetFromOpening.demote(
            base = anchor.tier,
            walkDistanceM = walk,
        )

        return PlotCenterResult(
            lat = anchor.lat + north / metersPerDegLat,
            lon = anchor.lon + east / metersPerDegLon,
            source = PositionSource.VIO_CHAIN,
            tier = tier,
            nSamples = 0,
            medianHAccuracyM = 0f,
            sampleStdXyM = 0f,
            offsetWalkM = walk,
        )
    }

    // MARK: - v0.5 deferred: multi-fix rigid alignment

    /// Stub for v0.5. Callers may invoke; it's a no-op today. Full
    /// implementation will run Umeyama SVD over (world, lat-lon)
    /// correspondences to rebaseline the chain when multiple trusted
    /// opening fixes accumulate. Kept on the API surface so the
    /// screen layer can call it without platform gates.
    @Suppress("UNUSED_PARAMETER")
    fun alignChainToFixes(fixes: List<Fix>) {
        // TODO v0.5: Umeyama rigid alignment. No observed multi-fix
        // data yet to calibrate against, so defer.
    }

    /// (pointWorld, lat, lon) correspondence for `alignChainToFixes` —
    /// Kotlin has no anonymous tuples, so the Swift tuple becomes this.
    data class Fix(val pointWorld: Vec3, val lat: Double, val lon: Double)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VIOChain) return false
        return _anchors == other._anchors &&
            maxWalkBetweenAnchorsM == other.maxWalkBetweenAnchorsM
    }

    override fun hashCode(): Int =
        31 * _anchors.hashCode() + maxWalkBetweenAnchorsM.hashCode()
}
