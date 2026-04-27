// Sampling-statistics engine — pure functions for cruise design and
// post-cruise reporting. Adopted from SilvaCruise's "full sampling-
// statistics engine" pitch (CV / SE / CI / SDI / Reineke / Curtis /
// VBAR / Neyman). All inputs are plain Swift arrays of plot-level
// metrics; nothing here touches Core Data, AR, or persistence.
//
// What's covered:
//
//   • cv / se / mean / standardDeviation
//   • confidenceInterval(t: cv: n:) — two-sided
//   • requiredSampleSize(targetSEPct: cv: t:) — for cruise design
//   • neymanAllocation(strataCV:strataN:totalSampleSize:) — for
//     stratified cruise design
//   • reineke SDI helpers
//   • curtisRD — Curtis Relative Density (PNW standard)
//
// Tests in CommonTests can pin known values from cruise-statistics
// textbooks (Avery & Burkhart, Iles "Sampling Methods" reference).

import Foundation

public enum SamplingStats {

    // MARK: - Basic descriptive stats

    public static func mean(_ xs: [Double]) -> Double {
        guard !xs.isEmpty else { return 0 }
        return xs.reduce(0, +) / Double(xs.count)
    }

    /// Unbiased sample standard deviation (n − 1 denominator).
    /// Returns 0 for sample size < 2.
    public static func standardDeviation(_ xs: [Double]) -> Double {
        guard xs.count >= 2 else { return 0 }
        let m = mean(xs)
        let sumSq = xs.reduce(0) { $0 + ($1 - m) * ($1 - m) }
        return (sumSq / Double(xs.count - 1)).squareRoot()
    }

    /// Coefficient of variation as a percentage.
    public static func cv(_ xs: [Double]) -> Double {
        let m = mean(xs)
        guard m != 0 else { return 0 }
        return standardDeviation(xs) / m * 100
    }

    /// Standard error of the mean (absolute units, not %).
    public static func se(_ xs: [Double]) -> Double {
        guard xs.count >= 2 else { return 0 }
        return standardDeviation(xs) / Double(xs.count).squareRoot()
    }

    /// Standard error of the mean as a percentage of the mean (one-
    /// sided SE%, often called sampling error in cruise reports).
    public static func sePct(_ xs: [Double]) -> Double {
        let m = mean(xs)
        guard m != 0 else { return 0 }
        return se(xs) / m * 100
    }

    // MARK: - Confidence intervals

    /// 95 % two-sided CI half-width on the mean using a Student-t
    /// approximation. `tApprox` defaults to 2.0 (n ≥ 30 rule of
    /// thumb); cruise designs that demand tight stats should pass
    /// the actual t-value for their df.
    public static func ciHalfWidth(_ xs: [Double], tApprox: Double = 2.0) -> Double {
        tApprox * se(xs)
    }

    public static func confidenceInterval(_ xs: [Double], tApprox: Double = 2.0)
        -> (lower: Double, upper: Double)? {
        guard xs.count >= 2 else { return nil }
        let m = mean(xs)
        let h = ciHalfWidth(xs, tApprox: tApprox)
        return (m - h, m + h)
    }

    // MARK: - Required sample size

    /// Target sample size for a desired one-sided sampling error
    /// (`targetSEPct`) given an estimated CV (`cv`, in %) and a
    /// t-multiplier (~2 for n ≥ 30, ~3 for very small n).
    /// Result rounds up.
    public static func requiredSampleSize(targetSEPct: Double,
                                          cv: Double,
                                          t: Double = 2.0) -> Int {
        guard targetSEPct > 0 else { return 0 }
        let n = pow((t * cv) / targetSEPct, 2)
        return Int(ceil(n))
    }

    // MARK: - Neyman allocation

    /// Optimal stratified sample-size allocation. For each stratum:
    ///     n_h = totalN × (W_h × σ_h) / Σ (W_k × σ_k)
    /// where `W_h = N_h / Σ N_k` (population weight). Inputs:
    ///   • `strataCV` — CV per stratum (%)
    ///   • `strataN` — population size per stratum (e.g. acres)
    ///   • `totalSampleSize` — total plots to allocate
    /// Returns plots per stratum, rounded down with the remainder
    /// distributed largest-fraction-first so rounding loss doesn't
    /// reduce the cruise's effective sample size.
    public static func neymanAllocation(
        strataCV: [Double],
        strataN: [Double],
        totalSampleSize: Int
    ) -> [Int] {
        guard strataCV.count == strataN.count, !strataCV.isEmpty,
              totalSampleSize > 0 else {
            return Array(repeating: 0, count: strataCV.count)
        }
        let totalN = strataN.reduce(0, +)
        guard totalN > 0 else { return Array(repeating: 0, count: strataCV.count) }
        // Use σ * W_h ∝ CV × N_h (the constant-mean factor cancels
        // out of the fraction; this is fine for typical cruise data).
        let weights = zip(strataCV, strataN).map { $0 * $1 }
        let sumW = weights.reduce(0, +)
        guard sumW > 0 else { return Array(repeating: 0, count: strataCV.count) }
        let raw = weights.map { Double(totalSampleSize) * $0 / sumW }
        var floored = raw.map(Int.init)   // floor
        var remaining = totalSampleSize - floored.reduce(0, +)
        let fractionsSorted = raw.enumerated()
            .map { ($0.offset, $0.element - Double(Int($0.element))) }
            .sorted { $0.1 > $1.1 }
        for (idx, _) in fractionsSorted where remaining > 0 {
            floored[idx] += 1
            remaining -= 1
        }
        return floored
    }

    // MARK: - Density indices

    /// Reineke SDI = TPA × (QMD_in / 10)^1.605
    /// Returns 0 for non-positive inputs.
    public static func reinekeSDI(tpa: Double, qmdInches: Double) -> Double {
        guard tpa > 0, qmdInches > 0 else { return 0 }
        return tpa * pow(qmdInches / 10.0, 1.605)
    }

    /// Reineke relative density vs a species Max SDI baseline,
    /// expressed as a percentage. Generic Max SDI of 717 is used
    /// downstream when species-specific tables aren't available.
    public static func reinekeRelativeDensityPct(sdi: Double, maxSDI: Double) -> Double {
        guard maxSDI > 0 else { return 0 }
        return min(200, max(0, sdi / maxSDI * 100))
    }

    /// Curtis Relative Density (PNW standard):
    ///     RD = BA / sqrt(QMD)   (BA in ft²/ac, QMD in inches)
    public static func curtisRD(baPerAcreFt2: Double, qmdInches: Double) -> Double {
        guard qmdInches > 0 else { return 0 }
        return baPerAcreFt2 / qmdInches.squareRoot()
    }

    // MARK: - Cruise rating

    /// Three-band cruise rating off the standard sampling-error
    /// percentage. These thresholds match the ones SilvaCruise uses
    /// for its "Acceptable / Marginal / Poor" verdict.
    public enum CruiseRating: String, Sendable {
        case acceptable, marginal, poor
    }

    public static func rating(forSEPct sePct: Double) -> CruiseRating {
        switch sePct {
        case ..<10:  return .acceptable
        case ..<20:  return .marginal
        default:     return .poor
        }
    }
}
