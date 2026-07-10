// Spec §7.5 Stand-Level Statistics. Stratified sampling with Satterthwaite
// approximation for the overall degrees of freedom.
//
// Per stratum h, plot values y_{h,j}:
//   ȳ_h     = mean(y)
//   s²_h    = unbiased variance
//   var_ȳ_h = s²_h / n_h · (1 − n_h/N_h)        [FPC]
//   Ŷ       = Σ_h A_h · ȳ_h
//   var_Ŷ   = Σ_h A_h² · var_ȳ_h
//   SE_Ŷ    = sqrt(var_Ŷ)
//   df      = Satterthwaite( {s²_h, n_h, A_h} )
//   CI95    = Ŷ ± t_{0.975, df} · SE_Ŷ
//
// If N_h is unknown (nil), treat FPC as 1 (infinite-population assumption).

import Foundation

public enum StandStatistics {

    // MARK: - Input types

    public struct StratumSample: Sendable {
        public let areaAcres: Float              // A_h
        public let plotValues: [Float]            // y_{h,j}
        public let populationSize: Int?           // N_h (optional; nil ⇒ skip FPC)

        public init(areaAcres: Float, plotValues: [Float], populationSize: Int? = nil) {
            self.areaAcres = areaAcres
            self.plotValues = plotValues
            self.populationSize = populationSize
        }
    }

    // MARK: - Output

    public struct Result: Sendable, Equatable {
        public let total: Float                   // Ŷ  (attribute × acre)
        public let se: Float                      // SE(Ŷ)
        public let df: Float                      // Satterthwaite degrees of freedom
        public let ci95Lower: Float               // Ŷ − t · SE
        public let ci95Upper: Float               // Ŷ + t · SE
        public let perStratumMean: [Float]        // ȳ_h in stratum order
        public let perStratumVarOfMean: [Float]   // var(ȳ_h) in stratum order
    }

    // MARK: - Public entry point

    public static func compute(strata: [StratumSample]) -> Result {
        precondition(!strata.isEmpty, "at least one stratum required")

        var yhat: Float = 0
        var varYhat: Float = 0
        var means: [Float] = []
        var varMeans: [Float] = []

        // Also gather per-stratum variance components for Satterthwaite df.
        var numer: Float = 0             // (Σ A_h² · var_ȳ_h)²
        var denom: Float = 0             // Σ (A_h² · var_ȳ_h)² / (n_h − 1)
        var totalN = 0

        for s in strata {
            totalN += s.plotValues.count
            let n = s.plotValues.count
            guard n >= 1 else {
                means.append(0)
                varMeans.append(0)
                continue
            }
            let yBar = s.plotValues.reduce(0, +) / Float(n)
            var s2: Float = 0
            if n >= 2 {
                s2 = s.plotValues.reduce(0) { $0 + ($1 - yBar) * ($1 - yBar) } / Float(n - 1)
            }
            let fpc: Float
            if let N = s.populationSize, N > 0 {
                fpc = max(0, 1 - Float(n) / Float(N))
            } else {
                fpc = 1
            }
            let varYBar = (n >= 2) ? (s2 / Float(n)) * fpc : 0
            means.append(yBar)
            varMeans.append(varYBar)

            yhat   += s.areaAcres * yBar
            varYhat += s.areaAcres * s.areaAcres * varYBar

            let contribution = s.areaAcres * s.areaAcres * varYBar
            if n >= 2 {
                denom += (contribution * contribution) / Float(n - 1)
            }
        }

        numer = varYhat * varYhat
        let df: Float = (denom > 0) ? (numer / denom) : Float(max(1, totalN - strata.count))
        let se = sqrt(max(0, varYhat))
        let tVal = tStudent975(df: df)
        let lower = yhat - tVal * se
        let upper = yhat + tVal * se

        return Result(
            total: yhat,
            se: se,
            df: df,
            ci95Lower: lower,
            ci95Upper: upper,
            perStratumMean: means,
            perStratumVarOfMean: varMeans
        )
    }

    // MARK: - Student's t (two-sided 95%) approximation
    //
    // A table-driven approximation keyed on integer df, linearly interpolated.
    // For df ≥ 120 we use the normal quantile 1.960.
    // Values from standard Student-t tables (0.975 quantile, two-sided 95%).

    private static let tTable: [(df: Float, t: Float)] = [
        (1, 12.706),
        (2, 4.303),
        (3, 3.182),
        (4, 2.776),
        (5, 2.571),
        (6, 2.447),
        (7, 2.365),
        (8, 2.306),
        (9, 2.262),
        (10, 2.228),
        (12, 2.179),
        (15, 2.131),
        (20, 2.086),
        (25, 2.060),
        (30, 2.042),
        (40, 2.021),
        (60, 2.000),
        (120, 1.980)
    ]

    public static func tStudent975(df: Float) -> Float {
        if df <= tTable.first!.df { return tTable.first!.t }
        if df >= 120 { return 1.960 }
        for i in 1..<tTable.count {
            let a = tTable[i - 1], b = tTable[i]
            if df <= b.df {
                let t = (df - a.df) / (b.df - a.df)
                return a.t + (b.t - a.t) * t
            }
        }
        return 1.960
    }
}
