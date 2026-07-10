// Spec §8 Geo/SamplingGenerator. REQ-PRJ-004: turn a set of stratum polygons
// plus a cruise design into a list of planned plot centres.
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

import Foundation
import Models

public enum SamplingGenerator {

    // MARK: - Input / output types

    public struct StratumInput: Sendable {
        public let stratumId: UUID
        public let rings: [[CoordinateConversions.LatLon]]   // outer + holes

        public init(stratumId: UUID, rings: [[CoordinateConversions.LatLon]]) {
            self.stratumId = stratumId
            self.rings = rings
        }
    }

    public struct GenerationOptions: Sendable {
        public var projectId: UUID
        public var scheme: SamplingScheme
        public var gridSpacingMeters: Double?          // required for .systematicGrid
        public var nPerStratum: Int?                   // required for .stratifiedRandom
        public var seed: UInt64                        // deterministic testing

        public init(
            projectId: UUID,
            scheme: SamplingScheme,
            gridSpacingMeters: Double? = nil,
            nPerStratum: Int? = nil,
            seed: UInt64
        ) {
            self.projectId = projectId
            self.scheme = scheme
            self.gridSpacingMeters = gridSpacingMeters
            self.nPerStratum = nPerStratum
            self.seed = seed
        }
    }

    public enum GenerationError: Error, CustomStringConvertible {
        case missingGridSpacing
        case missingNPerStratum
        case noStrata
        case invalidGeometry(String)

        public var description: String {
            switch self {
            case .missingGridSpacing: return "systematicGrid scheme requires gridSpacingMeters"
            case .missingNPerStratum: return "stratifiedRandom scheme requires nPerStratum"
            case .noStrata: return "No strata supplied to SamplingGenerator"
            case .invalidGeometry(let r): return "Invalid geometry: \(r)"
            }
        }
    }

    // MARK: - Public API

    /// Generate planned plots for a project given its strata and cruise design.
    /// Returns plots in deterministic order, numbered starting at `startingPlotNumber`.
    public static func generate(
        strata: [StratumInput],
        options: GenerationOptions,
        startingPlotNumber: Int = 1
    ) throws -> [PlannedPlot] {
        guard !strata.isEmpty else { throw GenerationError.noStrata }
        switch options.scheme {
        case .systematicGrid:
            guard let spacing = options.gridSpacingMeters, spacing > 0 else {
                throw GenerationError.missingGridSpacing
            }
            return try generateSystematicGrid(
                strata: strata,
                projectId: options.projectId,
                spacing: spacing,
                seed: options.seed,
                startingPlotNumber: startingPlotNumber
            )
        case .stratifiedRandom:
            guard let n = options.nPerStratum, n > 0 else {
                throw GenerationError.missingNPerStratum
            }
            return try generateStratifiedRandom(
                strata: strata,
                projectId: options.projectId,
                nPerStratum: n,
                seed: options.seed,
                startingPlotNumber: startingPlotNumber
            )
        case .manual:
            // Manual plots are added one-by-one through the UI; this scheme
            // produces no automatically-generated plots.
            return []
        }
    }

    // MARK: - Systematic grid

    private static func generateSystematicGrid(
        strata: [StratumInput],
        projectId: UUID,
        spacing: Double,
        seed: UInt64,
        startingPlotNumber: Int
    ) throws -> [PlannedPlot] {
        let origin = try anchorLatLon(strata: strata)
        var rng = SeededGenerator(seed: seed)
        let jitterE = Double.random(in: 0..<spacing, using: &rng)
        let jitterN = Double.random(in: 0..<spacing, using: &rng)

        // Project every ring into ENU so we can both bound the grid and do
        // point-in-polygon tests in metres.
        let stratumProjections = strata.map { stratum in
            (id: stratum.stratumId, rings: projectRings(stratum.rings, origin: origin))
        }

        let bbox = boundingBox(stratumProjections.flatMap { $0.rings.flatMap { $0 } })
        let startE = floor((bbox.minE - jitterE) / spacing) * spacing + jitterE
        let startN = floor((bbox.minN - jitterN) / spacing) * spacing + jitterN

        var plots: [PlannedPlot] = []
        var number = startingPlotNumber
        var north = startN
        while north <= bbox.maxN {
            var east = startE
            while east <= bbox.maxE {
                let candidate = CoordinateConversions.ENU(east: east, north: north)
                if let hit = stratumProjections.first(where: { pointInRings(candidate, rings: $0.rings) }) {
                    let ll = CoordinateConversions.toLatLon(enu: candidate, origin: origin)
                    plots.append(
                        PlannedPlot(
                            id: UUID(),
                            projectId: projectId,
                            stratumId: hit.id,
                            plotNumber: number,
                            plannedLat: ll.latitude,
                            plannedLon: ll.longitude,
                            visited: false
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

    private static func generateStratifiedRandom(
        strata: [StratumInput],
        projectId: UUID,
        nPerStratum: Int,
        seed: UInt64,
        startingPlotNumber: Int
    ) throws -> [PlannedPlot] {
        let origin = try anchorLatLon(strata: strata)
        var rng = SeededGenerator(seed: seed)

        var plots: [PlannedPlot] = []
        var number = startingPlotNumber
        // Rejection-sampling budget per stratum: enough attempts for tortured
        // shapes, but finite.
        let attemptBudget = max(200 * nPerStratum, 500)

        for stratum in strata {
            let rings = projectRings(stratum.rings, origin: origin)
            let bbox = boundingBox(rings.flatMap { $0 })
            var accepted = 0
            var attempts = 0
            while accepted < nPerStratum, attempts < attemptBudget {
                attempts += 1
                let east = Double.random(in: bbox.minE...bbox.maxE, using: &rng)
                let north = Double.random(in: bbox.minN...bbox.maxN, using: &rng)
                let candidate = CoordinateConversions.ENU(east: east, north: north)
                guard pointInRings(candidate, rings: rings) else { continue }
                let ll = CoordinateConversions.toLatLon(enu: candidate, origin: origin)
                plots.append(
                    PlannedPlot(
                        id: UUID(),
                        projectId: projectId,
                        stratumId: stratum.stratumId,
                        plotNumber: number,
                        plannedLat: ll.latitude,
                        plannedLon: ll.longitude,
                        visited: false
                    )
                )
                accepted += 1
                number += 1
            }
        }
        return plots
    }

    // MARK: - Geometry helpers

    private static func anchorLatLon(strata: [StratumInput]) throws -> CoordinateConversions.LatLon {
        guard let firstOuter = strata.first?.rings.first, !firstOuter.isEmpty else {
            throw GenerationError.invalidGeometry("First stratum has no outer ring")
        }
        let meanLat = firstOuter.map(\.latitude).reduce(0, +) / Double(firstOuter.count)
        let meanLon = firstOuter.map(\.longitude).reduce(0, +) / Double(firstOuter.count)
        return CoordinateConversions.LatLon(latitude: meanLat, longitude: meanLon)
    }

    private static func projectRings(
        _ rings: [[CoordinateConversions.LatLon]],
        origin: CoordinateConversions.LatLon
    ) -> [[CoordinateConversions.ENU]] {
        rings.map { ring in ring.map { CoordinateConversions.toENU(point: $0, origin: origin) } }
    }

    private struct BBox { let minE, maxE, minN, maxN: Double }

    private static func boundingBox(_ pts: [CoordinateConversions.ENU]) -> BBox {
        var minE = Double.infinity, maxE = -Double.infinity
        var minN = Double.infinity, maxN = -Double.infinity
        for p in pts {
            minE = min(minE, p.east); maxE = max(maxE, p.east)
            minN = min(minN, p.north); maxN = max(maxN, p.north)
        }
        return BBox(minE: minE, maxE: maxE, minN: minN, maxN: maxN)
    }

    /// Outer ring inclusion, with holes excluded (standard GeoJSON semantics).
    private static func pointInRings(
        _ p: CoordinateConversions.ENU,
        rings: [[CoordinateConversions.ENU]]
    ) -> Bool {
        guard let outer = rings.first, pointInRing(p, ring: outer) else { return false }
        for hole in rings.dropFirst() where pointInRing(p, ring: hole) {
            return false
        }
        return true
    }

    /// Classic crossing-number point-in-polygon on a closed ring (first == last).
    private static func pointInRing(
        _ p: CoordinateConversions.ENU,
        ring: [CoordinateConversions.ENU]
    ) -> Bool {
        guard ring.count >= 3 else { return false }
        var inside = false
        var j = ring.count - 1
        for i in 0..<ring.count {
            let xi = ring[i].east,  yi = ring[i].north
            let xj = ring[j].east,  yj = ring[j].north
            let intersects = ((yi > p.north) != (yj > p.north)) &&
                (p.east < (xj - xi) * (p.north - yi) / (yj - yi) + xi)
            if intersects { inside.toggle() }
            j = i
        }
        return inside
    }
}

// MARK: - Deterministic RNG

/// SplitMix64 — tiny, fast, good enough for reproducible test fixtures.
/// Stdlib `Double.random(in:using:)` accepts any `RandomNumberGenerator`.
struct SeededGenerator: RandomNumberGenerator {
    private var state: UInt64
    init(seed: UInt64) { self.state = seed == 0 ? 0x9E37_79B9_7F4A_7C15 : seed }

    mutating func next() -> UInt64 {
        state &+= 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }
}
