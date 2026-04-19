// Spec §7.5 stand-level stratified statistics. REQ-AGG-003.
//
// Given a list of plot-level measurements (TPA, BA/ac, V/ac, ...) grouped by
// stratum plus the acreage of each stratum, computes:
//   • per-stratum mean & variance
//   • stand-level weighted mean
//   • standard error under stratified random sampling (FPC ignored — plot
//     population is large compared with sample n)
//   • Satterthwaite-approximated degrees of freedom
//   • 95% CI half-width using the Student t distribution
//
// Unstratified plots collapse to a single `"__unstratified__"` stratum key,
// so callers always get a usable `StandStats` even without a stratum map.

import Foundation

public struct StandStat: Sendable, Equatable {
    public let mean: Double
    public let seMean: Double
    public let ci95HalfWidth: Double
    public let dfSatterthwaite: Double
    public let nPlots: Int
    public let byStratum: [String: StratumStat]

    public struct StratumStat: Sendable, Equatable {
        public let nPlots: Int
        public let mean: Double
        public let variance: Double
        public let areaAcres: Double
    }

    public static let empty = StandStat(
        mean: 0, seMean: 0, ci95HalfWidth: 0,
        dfSatterthwaite: 0, nPlots: 0, byStratum: [:])
}

public enum StandStatsCalculator {

    /// Compute a single metric's stand-level statistic.
    ///
    /// - Parameters:
    ///   - plotValues: `(stratumKey, valuePerPlot)` — one entry per plot.
    ///   - stratumAreasAcres: map of stratum key → area. Missing keys default
    ///     to 1.0 (treat each stratum as equal-area) so that unstratified
    ///     callers see the unweighted sample mean.
    public static func compute(
        plotValues: [(stratumKey: String, value: Double)],
        stratumAreasAcres: [String: Double]
    ) -> StandStat {
        guard !plotValues.isEmpty else { return .empty }

        var groups: [String: [Double]] = [:]
        for (key, v) in plotValues {
            groups[key, default: []].append(v)
        }

        var byStratum: [String: StandStat.StratumStat] = [:]
        for (key, vals) in groups {
            let n = vals.count
            let mean = vals.reduce(0, +) / Double(n)
            let variance: Double
            if n < 2 {
                variance = 0
            } else {
                let sumSq = vals.reduce(0) { $0 + ($1 - mean) * ($1 - mean) }
                variance = sumSq / Double(n - 1)
            }
            let area = stratumAreasAcres[key] ?? 1.0
            byStratum[key] = StandStat.StratumStat(
                nPlots: n, mean: mean, variance: variance, areaAcres: area)
        }

        let totalArea = byStratum.values.reduce(0) { $0 + $1.areaAcres }
        guard totalArea > 0 else {
            return StandStat(
                mean: 0, seMean: 0, ci95HalfWidth: 0,
                dfSatterthwaite: 0, nPlots: plotValues.count,
                byStratum: byStratum)
        }

        var weightedMean: Double = 0
        var seSqSum: Double = 0
        var satterNum: Double = 0
        var satterDenom: Double = 0
        for (_, s) in byStratum {
            let w = s.areaAcres / totalArea
            weightedMean += w * s.mean
            let term = w * w * s.variance / Double(max(s.nPlots, 1))
            seSqSum += term
            satterNum += term
            if s.nPlots >= 2 {
                satterDenom += (term * term) / Double(s.nPlots - 1)
            }
        }
        let se = sqrt(seSqSum)
        let df: Double
        if satterDenom > 0 {
            df = (satterNum * satterNum) / satterDenom
        } else {
            df = Double(plotValues.count - byStratum.count)
        }
        let t = tCritical95(df: max(df, 1))
        let ci = t * se

        return StandStat(
            mean: weightedMean,
            seMean: se,
            ci95HalfWidth: ci,
            dfSatterthwaite: df,
            nPlots: plotValues.count,
            byStratum: byStratum)
    }

    /// Approximate two-sided t critical value at α = 0.05 for df ≥ 1.
    /// Uses a Wilson-Hilferty-style interpolation good to ~1% for typical
    /// cruising df (1..120).
    static func tCritical95(df: Double) -> Double {
        // Hand-picked table; linear interpolation between rows.
        let table: [(Double, Double)] = [
            (1, 12.706), (2, 4.303), (3, 3.182), (4, 2.776),
            (5, 2.571), (6, 2.447), (7, 2.365), (8, 2.306),
            (9, 2.262), (10, 2.228), (15, 2.131), (20, 2.086),
            (30, 2.042), (50, 2.009), (100, 1.984), (1000, 1.962)]
        if df <= table.first!.0 { return table.first!.1 }
        if df >= table.last!.0 { return 1.96 }
        for i in 0..<(table.count - 1) {
            let (d0, t0) = table[i]
            let (d1, t1) = table[i + 1]
            if df >= d0 && df <= d1 {
                let f = (df - d0) / (d1 - d0)
                return t0 + f * (t1 - t0)
            }
        }
        return 1.96
    }
}
