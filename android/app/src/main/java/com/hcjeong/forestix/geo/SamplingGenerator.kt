// Sampling plot generator — 1:1 port of iOS Geo/SamplingGenerator.swift
// (spec §8, REQ-PRJ-004): turn a set of stratum polygons plus a cruise design
// into a list of planned plot centres.
//
// Schemes:
//  • systematicGrid     — square grid at `gridSpacingMeters`, with a uniform
//                         random offset in [0, spacing) applied once to the
//                         whole project. Points that fall outside every
//                         stratum polygon are discarded.
//  • stratifiedRandom   — `nPerStratum` uniform-random points per stratum
//                         (rejection-sampled inside the polygon bounding box).
//  • manual             — passthrough; caller supplies the coordinates.
//
// All generation happens in a local ENU plane anchored on the centroid of
// the first stratum's bounding box. That keeps the arithmetic simple and,
// for cruise-sized AOIs, is well within the equirectangular error budget.
//
// The RNG (SplitMix64) and the uniform-double draws replicate the Swift
// stdlib `Double.random(in:using:)` algorithms bit-for-bit, so the same seed
// produces the same plot coordinates on both platforms.

package com.hcjeong.forestix.geo

import com.hcjeong.forestix.data.cruise.PlannedPlot
import com.hcjeong.forestix.data.cruise.SamplingScheme
import java.util.UUID
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

object SamplingGenerator {

    // MARK: - Input / output types

    data class StratumInput(
        val stratumId: UUID,
        /// outer + holes
        val rings: List<List<CoordinateConversions.LatLon>>,
    )

    data class GenerationOptions(
        val projectId: UUID,
        val scheme: SamplingScheme,
        /// required for systematicGrid
        val gridSpacingMeters: Double? = null,
        /// required for stratifiedRandom
        val nPerStratum: Int? = null,
        /// deterministic testing
        val seed: ULong,
    )

    /// Generation failure reasons; `description` strings match the iOS
    /// `GenerationError` cases byte-for-byte.
    sealed class GenerationError(val description: String) : Exception(description) {
        class MissingGridSpacing : GenerationError("systematicGrid scheme requires gridSpacingMeters")
        class MissingNPerStratum : GenerationError("stratifiedRandom scheme requires nPerStratum")
        class NoStrata : GenerationError("No strata supplied to SamplingGenerator")
        class InvalidGeometry(reason: String) : GenerationError("Invalid geometry: $reason")
    }

    // MARK: - Public API

    /// Generate planned plots for a project given its strata and cruise design.
    /// Returns plots in deterministic order, numbered starting at `startingPlotNumber`.
    fun generate(
        strata: List<StratumInput>,
        options: GenerationOptions,
        startingPlotNumber: Int = 1,
    ): List<PlannedPlot> {
        if (strata.isEmpty()) throw GenerationError.NoStrata()
        return when (options.scheme) {
            SamplingScheme.SYSTEMATIC_GRID -> {
                val spacing = options.gridSpacingMeters
                if (spacing == null || spacing <= 0) throw GenerationError.MissingGridSpacing()
                generateSystematicGrid(
                    strata = strata,
                    projectId = options.projectId,
                    spacing = spacing,
                    seed = options.seed,
                    startingPlotNumber = startingPlotNumber,
                )
            }
            SamplingScheme.STRATIFIED_RANDOM -> {
                val n = options.nPerStratum
                if (n == null || n <= 0) throw GenerationError.MissingNPerStratum()
                generateStratifiedRandom(
                    strata = strata,
                    projectId = options.projectId,
                    nPerStratum = n,
                    seed = options.seed,
                    startingPlotNumber = startingPlotNumber,
                )
            }
            // Manual plots are added one-by-one through the UI; this scheme
            // produces no automatically-generated plots.
            SamplingScheme.MANUAL -> emptyList()
        }
    }

    // MARK: - Systematic grid

    private fun generateSystematicGrid(
        strata: List<StratumInput>,
        projectId: UUID,
        spacing: Double,
        seed: ULong,
        startingPlotNumber: Int,
    ): List<PlannedPlot> {
        val origin = anchorLatLon(strata)
        val rng = SeededGenerator(seed)
        val jitterE = rng.randomDouble(0.0, spacing)
        val jitterN = rng.randomDouble(0.0, spacing)

        // Project every ring into ENU so we can both bound the grid and do
        // point-in-polygon tests in metres.
        val stratumProjections = strata.map { stratum ->
            Pair(stratum.stratumId, projectRings(stratum.rings, origin))
        }

        val bbox = boundingBox(stratumProjections.flatMap { it.second.flatten() })
        val startE = floor((bbox.minE - jitterE) / spacing) * spacing + jitterE
        val startN = floor((bbox.minN - jitterN) / spacing) * spacing + jitterN

        val plots = mutableListOf<PlannedPlot>()
        var number = startingPlotNumber
        var north = startN
        while (north <= bbox.maxN) {
            var east = startE
            while (east <= bbox.maxE) {
                val candidate = CoordinateConversions.ENU(east = east, north = north)
                val hit = stratumProjections.firstOrNull { pointInRings(candidate, it.second) }
                if (hit != null) {
                    val ll = CoordinateConversions.toLatLon(enu = candidate, origin = origin)
                    plots.add(
                        PlannedPlot(
                            id = UUID.randomUUID(),
                            projectId = projectId,
                            stratumId = hit.first,
                            plotNumber = number,
                            plannedLat = ll.latitude,
                            plannedLon = ll.longitude,
                            visited = false,
                        )
                    )
                    number += 1
                }
                east += spacing
            }
            north += spacing
        }
        return plots
    }

    // MARK: - Stratified random

    private fun generateStratifiedRandom(
        strata: List<StratumInput>,
        projectId: UUID,
        nPerStratum: Int,
        seed: ULong,
        startingPlotNumber: Int,
    ): List<PlannedPlot> {
        val origin = anchorLatLon(strata)
        val rng = SeededGenerator(seed)

        val plots = mutableListOf<PlannedPlot>()
        var number = startingPlotNumber
        // Rejection-sampling budget per stratum: enough attempts for tortured
        // shapes, but finite.
        val attemptBudget = max(200 * nPerStratum, 500)

        for (stratum in strata) {
            val rings = projectRings(stratum.rings, origin)
            val bbox = boundingBox(rings.flatten())
            var accepted = 0
            var attempts = 0
            while (accepted < nPerStratum && attempts < attemptBudget) {
                attempts += 1
                val east = rng.randomDoubleClosed(bbox.minE, bbox.maxE)
                val north = rng.randomDoubleClosed(bbox.minN, bbox.maxN)
                val candidate = CoordinateConversions.ENU(east = east, north = north)
                if (!pointInRings(candidate, rings)) continue
                val ll = CoordinateConversions.toLatLon(enu = candidate, origin = origin)
                plots.add(
                    PlannedPlot(
                        id = UUID.randomUUID(),
                        projectId = projectId,
                        stratumId = stratum.stratumId,
                        plotNumber = number,
                        plannedLat = ll.latitude,
                        plannedLon = ll.longitude,
                        visited = false,
                    )
                )
                accepted += 1
                number += 1
            }
        }
        return plots
    }

    // MARK: - Geometry helpers

    private fun anchorLatLon(strata: List<StratumInput>): CoordinateConversions.LatLon {
        val firstOuter = strata.firstOrNull()?.rings?.firstOrNull()
        if (firstOuter.isNullOrEmpty()) {
            throw GenerationError.InvalidGeometry("First stratum has no outer ring")
        }
        val meanLat = firstOuter.sumOf { it.latitude } / firstOuter.size.toDouble()
        val meanLon = firstOuter.sumOf { it.longitude } / firstOuter.size.toDouble()
        return CoordinateConversions.LatLon(latitude = meanLat, longitude = meanLon)
    }

    private fun projectRings(
        rings: List<List<CoordinateConversions.LatLon>>,
        origin: CoordinateConversions.LatLon,
    ): List<List<CoordinateConversions.ENU>> =
        rings.map { ring -> ring.map { CoordinateConversions.toENU(point = it, origin = origin) } }

    private data class BBox(val minE: Double, val maxE: Double, val minN: Double, val maxN: Double)

    private fun boundingBox(pts: List<CoordinateConversions.ENU>): BBox {
        var minE = Double.POSITIVE_INFINITY
        var maxE = Double.NEGATIVE_INFINITY
        var minN = Double.POSITIVE_INFINITY
        var maxN = Double.NEGATIVE_INFINITY
        for (p in pts) {
            minE = min(minE, p.east); maxE = max(maxE, p.east)
            minN = min(minN, p.north); maxN = max(maxN, p.north)
        }
        return BBox(minE = minE, maxE = maxE, minN = minN, maxN = maxN)
    }

    /// Outer ring inclusion, with holes excluded (standard GeoJSON semantics).
    private fun pointInRings(
        p: CoordinateConversions.ENU,
        rings: List<List<CoordinateConversions.ENU>>,
    ): Boolean {
        val outer = rings.firstOrNull() ?: return false
        if (!pointInRing(p, outer)) return false
        for (hole in rings.drop(1)) {
            if (pointInRing(p, hole)) return false
        }
        return true
    }

    /// Classic crossing-number point-in-polygon on a closed ring (first == last).
    private fun pointInRing(
        p: CoordinateConversions.ENU,
        ring: List<CoordinateConversions.ENU>,
    ): Boolean {
        if (ring.size < 3) return false
        var inside = false
        var j = ring.size - 1
        for (i in ring.indices) {
            val xi = ring[i].east
            val yi = ring[i].north
            val xj = ring[j].east
            val yj = ring[j].north
            val intersects = ((yi > p.north) != (yj > p.north)) &&
                (p.east < (xj - xi) * (p.north - yi) / (yj - yi) + xi)
            if (intersects) inside = !inside
            j = i
        }
        return inside
    }
}

// MARK: - Deterministic RNG

/// SplitMix64 — tiny, fast, good enough for reproducible test fixtures.
/// `next()` matches the iOS `SeededGenerator`, and the double draws replicate
/// the Swift stdlib `Double.random(in:using:)` Range / ClosedRange algorithms
/// (53-bit significand draw; Lemire's nearly-divisionless bounded draw for the
/// closed range) so the same seed yields identical values on both platforms.
internal class SeededGenerator(seed: ULong) {
    private var state: ULong = if (seed == 0uL) 0x9E37_79B9_7F4A_7C15uL else seed

    fun next(): ULong {
        state += 0x9E37_79B9_7F4A_7C15uL
        var z = state
        z = (z xor (z shr 30)) * 0xBF58_476D_1CE4_E5B9uL
        z = (z xor (z shr 27)) * 0x94D0_49BB_1331_11EBuL
        return z xor (z shr 31)
    }

    /// Swift `RandomNumberGenerator.next(upperBound:)` — Lemire's
    /// nearly-divisionless bounded draw. `upperBound` must be non-zero.
    fun next(upperBound: ULong): ULong {
        var random = next()
        var high = multiplyHigh(random, upperBound)
        var low = random * upperBound
        if (low < upperBound) {
            val t = (0uL - upperBound) % upperBound
            while (low < t) {
                random = next()
                high = multiplyHigh(random, upperBound)
                low = random * upperBound
            }
        }
        return high
    }

    /// Swift `Double.random(in: lower..<upper, using:)` — half-open range.
    fun randomDouble(lowerBound: Double, upperBound: Double): Double {
        val delta = upperBound - lowerBound
        while (true) {
            val rand = next() and (MAX_SIGNIFICAND - 1uL)
            val unitRandom = rand.toDouble() * ULP_OF_ONE_OVER_2
            val randFloat = delta * unitRandom + lowerBound
            if (randFloat != upperBound) return randFloat
        }
    }

    /// Swift `Double.random(in: lower...upper, using:)` — closed range.
    fun randomDoubleClosed(lowerBound: Double, upperBound: Double): Double {
        val delta = upperBound - lowerBound
        val rand = next(upperBound = MAX_SIGNIFICAND + 1uL)
        if (rand == MAX_SIGNIFICAND) return upperBound
        val unitRandom = rand.toDouble() * ULP_OF_ONE_OVER_2
        return delta * unitRandom + lowerBound
    }

    private companion object {
        /// 2^53 — one past the largest exactly-representable draw.
        val MAX_SIGNIFICAND: ULong = 1uL shl 53

        /// Double.ulpOfOne / 2 == 2^-53.
        const val ULP_OF_ONE_OVER_2: Double = 1.0 / 9007199254740992.0

        /// High 64 bits of the full 128-bit product a*b
        /// (UInt64.multipliedFullWidth equivalent, via 32-bit halves).
        fun multiplyHigh(a: ULong, b: ULong): ULong {
            val aLo = a and 0xFFFF_FFFFuL
            val aHi = a shr 32
            val bLo = b and 0xFFFF_FFFFuL
            val bHi = b shr 32
            val ll = aLo * bLo
            val lh = aLo * bHi
            val hl = aHi * bLo
            val hh = aHi * bHi
            val carry = (ll shr 32) + (lh and 0xFFFF_FFFFuL) + (hl and 0xFFFF_FFFFuL)
            return hh + (lh shr 32) + (hl shr 32) + (carry shr 32)
        }
    }
}
