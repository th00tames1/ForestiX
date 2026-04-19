// Spec §7.6 live plot statistics for the tally-screen header strip.
// REQ-TAL-005: live updates must be ready within 300 ms of a tree add, so
// every path here is O(n_live_trees) with no I/O.
//
// Inputs are *pure* snapshots — the caller resolves species, volume
// equations, and (optionally) H–D fits before invoking this function. The
// result is a value type suitable for a @Published binding.

import Foundation
import Models

// MARK: - Result types

public struct PlotStats: Sendable, Equatable {
    public let liveTreeCount: Int
    public let tpa: Float                        // trees/acre
    public let baPerAcreM2: Float                // m²/acre
    public let qmdCm: Float                      // cm
    public let grossVolumePerAcreM3: Float       // m³/acre (all live trees)
    public let merchVolumePerAcreM3: Float       // m³/acre (stump → top-DIB)
    public let bySpecies: [String: SpeciesStat]

    public struct SpeciesStat: Sendable, Equatable {
        public let count: Int
        public let tpa: Float
        public let baPerAcreM2: Float
        public let grossVolumePerAcreM3: Float
    }

    public static let empty = PlotStats(
        liveTreeCount: 0, tpa: 0, baPerAcreM2: 0, qmdCm: 0,
        grossVolumePerAcreM3: 0, merchVolumePerAcreM3: 0,
        bySpecies: [:])
}

// MARK: - Calculator

public enum PlotStatsCalculator {

    /// Compute live stats for a plot. Soft-deleted trees and non-`.live`
    /// status trees are excluded from TPA, BA, QMD, and volume. Trees missing
    /// a measured height but with an `hdFits` entry for their species have
    /// height imputed via the Näslund model; otherwise volume for that tree
    /// contributes 0 (surfaced as a "missing height" warning elsewhere).
    ///
    /// - Parameters:
    ///   - plot: denormalized plot (uses `plotAreaAcres` for fixed-area EF).
    ///   - cruiseDesign: only `plotType` and `baf` are consulted.
    ///   - trees: all trees on the plot (caller need not pre-filter).
    ///   - species: map of speciesCode → config (for merch volume topDIB/stump).
    ///   - volumeEquations: map of speciesCode → volume equation. Missing
    ///     entries ⇒ volume contribution 0 for that species.
    ///   - hdFits: map of speciesCode → H–D fit for imputing missing heights.
    public static func compute(
        plot: Plot,
        cruiseDesign: CruiseDesign,
        trees: [Tree],
        species: [String: SpeciesConfig],
        volumeEquations: [String: any InventoryEngine.VolumeEquation],
        hdFits: [String: HDModel.Fit] = [:]
    ) -> PlotStats {

        let live = trees.filter { $0.deletedAt == nil && $0.status == .live }
        guard !live.isEmpty else { return .empty }

        let isFixed = cruiseDesign.plotType == .fixedArea
        // Pre-compute expansion factor for fixed-area (constant) or build
        // per-tree factor inside the loop for BAF.
        let fixedEF: Float = isFixed
            ? ExpansionFactors.fixedArea(plotAreaAcres: plot.plotAreaAcres)
            : 0

        var totalTPA: Float = 0
        var totalBAPerAcre: Float = 0
        var sumDbhSq: Float = 0                  // for QMD
        var totalGrossVolPerAcre: Float = 0
        var totalMerchVolPerAcre: Float = 0
        var bySpecies: [String: (count: Int, tpa: Float, ba: Float, vol: Float)] = [:]

        for tree in live {
            let ba = basalAreaM2(dbhCm: tree.dbhCm)
            sumDbhSq += tree.dbhCm * tree.dbhCm

            // Per-tree expansion factor.
            let ef: Float = isFixed
                ? fixedEF
                : (cruiseDesign.baf.map { ba > 0 ? $0 / ba : 0 } ?? 0)

            totalTPA += ef
            totalBAPerAcre += ba * ef

            // Height: measured or imputed from fit.
            let h: Float
            if let measured = tree.heightM, measured > 1.3 {
                h = measured
            } else if let fit = hdFits[tree.speciesCode] {
                h = HDModel.impute(dbhCm: tree.dbhCm, fit: fit)
            } else {
                h = 0   // no height, no volume contribution
            }

            var grossVolPerAcre: Float = 0
            var merchVolPerAcre: Float = 0
            if h > 1.3, let eq = volumeEquations[tree.speciesCode] {
                let vGross = eq.totalVolumeM3(dbhCm: tree.dbhCm, heightM: h)
                grossVolPerAcre = vGross * ef
                totalGrossVolPerAcre += grossVolPerAcre
                if let sp = species[tree.speciesCode] {
                    let vMerch = eq.merchantableVolumeM3(
                        dbhCm: tree.dbhCm, heightM: h,
                        topDibCm: sp.merchTopDibCm,
                        stumpHeightCm: sp.stumpHeightCm)
                    merchVolPerAcre = vMerch * ef
                    totalMerchVolPerAcre += merchVolPerAcre
                }
            }

            var bucket = bySpecies[tree.speciesCode] ?? (0, 0, 0, 0)
            bucket.count += 1
            bucket.tpa += ef
            bucket.ba += ba * ef
            bucket.vol += grossVolPerAcre
            bySpecies[tree.speciesCode] = bucket
        }

        let qmd = sqrt(sumDbhSq / Float(live.count))

        let speciesOut = bySpecies.mapValues { bucket in
            PlotStats.SpeciesStat(
                count: bucket.count,
                tpa: bucket.tpa,
                baPerAcreM2: bucket.ba,
                grossVolumePerAcreM3: bucket.vol)
        }

        return PlotStats(
            liveTreeCount: live.count,
            tpa: totalTPA,
            baPerAcreM2: totalBAPerAcre,
            qmdCm: qmd,
            grossVolumePerAcreM3: totalGrossVolPerAcre,
            merchVolumePerAcreM3: totalMerchVolPerAcre,
            bySpecies: speciesOut)
    }
}
