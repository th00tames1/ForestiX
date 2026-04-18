// Spec §7.4 H–D (Height–Diameter) Model.
//
// Form (Näslund):   H = 1.3 + D² / (a + b·D)²   (D = DBH cm, H = m)
//
// Fit (per project, per species) on plot close whenever n_measured ≥ 8 for
// that species. Uses Gauss-Newton non-linear least squares with the spec's
// initial guess a = 0.1·D̄, b = 0.05.
//
// For new trees with no measured height: apply the fitted (a,b). If the
// species has < 8 measured trees, callers may fall back to a project-wide
// pooled fit; this module does not load pooled data itself.

import Foundation

public enum HDModel {

    // MARK: - Näslund form

    /// H(D; a, b) = 1.3 + D² / (a + b·D)².
    public static func predict(dbhCm: Float, a: Float, b: Float) -> Float {
        let denom = a + b * dbhCm
        guard denom > 0 else { return 1.3 }
        let dd = dbhCm * dbhCm
        return 1.3 + dd / (denom * denom)
    }

    /// Partial derivative of H with respect to a.
    @inlinable
    static func partialA(dbhCm: Float, a: Float, b: Float) -> Float {
        let denom = a + b * dbhCm
        let dd = dbhCm * dbhCm
        return -2 * dd / (denom * denom * denom)
    }

    /// Partial derivative of H with respect to b.
    @inlinable
    static func partialB(dbhCm: Float, a: Float, b: Float) -> Float {
        let denom = a + b * dbhCm
        let dd = dbhCm * dbhCm
        return -2 * dd * dbhCm / (denom * denom * denom)
    }

    // MARK: - Fit result

    public struct Fit: Sendable, Equatable {
        public let a: Float
        public let b: Float
        public let nObs: Int
        public let rmse: Float
    }

    public enum FitError: Error, CustomStringConvertible {
        case notEnoughObservations(count: Int, required: Int)
        case didNotConverge
        case degenerateInput(reason: String)

        public var description: String {
            switch self {
            case .notEnoughObservations(let n, let r):
                return "Not enough observations: \(n) (need ≥ \(r))"
            case .didNotConverge:
                return "Gauss-Newton did not converge within iteration budget"
            case .degenerateInput(let r):
                return "Degenerate input: \(r)"
            }
        }
    }

    // MARK: - Gauss-Newton fit

    /// Fit Näslund H–D to observed pairs. Returns the fit, or throws.
    ///
    /// - Parameters:
    ///   - observations: `(dbhCm, heightM)` pairs. Heights ≤ 1.3 are dropped.
    ///   - minN: minimum observations (§7.4 uses 8 at the species level).
    ///   - maxIters: Gauss-Newton iteration budget.
    ///   - tol: convergence tolerance on the parameter update norm.
    public static func fit(
        observations: [(dbhCm: Float, heightM: Float)],
        minN: Int = 8,
        maxIters: Int = 50,
        tol: Float = 1e-6
    ) throws -> Fit {
        let clean = observations.filter { $0.dbhCm > 0 && $0.heightM > 1.3 }
        guard clean.count >= minN else {
            throw FitError.notEnoughObservations(count: clean.count, required: minN)
        }
        let dMean = clean.reduce(Float(0)) { $0 + $1.dbhCm } / Float(clean.count)
        guard dMean > 0 else { throw FitError.degenerateInput(reason: "DBH mean = 0") }

        // §7.4 initial guess.
        var a: Float = 0.1 * dMean
        var b: Float = 0.05

        for _ in 0..<maxIters {
            // Normal equations for min ||y − H(p)||²:
            //     (GᵀG) Δp = Gᵀ r,  where G_{i·} = ∂H/∂p_i, r = y − H(p).
            var jtj00: Float = 0, jtj01: Float = 0, jtj11: Float = 0
            var jtr0: Float = 0, jtr1: Float = 0

            for (d, h) in clean {
                let hPred = predict(dbhCm: d, a: a, b: b)
                let r = h - hPred
                // Model Jacobian entries (∂H/∂a, ∂H/∂b).
                let ga = partialA(dbhCm: d, a: a, b: b)
                let gb = partialB(dbhCm: d, a: a, b: b)
                jtj00 += ga * ga
                jtj01 += ga * gb
                jtj11 += gb * gb
                jtr0  += ga * r
                jtr1  += gb * r
            }

            let det = jtj00 * jtj11 - jtj01 * jtj01
            guard abs(det) > 1e-20 else {
                throw FitError.degenerateInput(reason: "Singular normal matrix")
            }
            // Solve 2x2 system:  [jtj00 jtj01] [da]   [jtr0]
            //                    [jtj01 jtj11] [db] = [jtr1]
            let da = (jtj11 * jtr0 - jtj01 * jtr1) / det
            let db = (jtj00 * jtr1 - jtj01 * jtr0) / det

            a += da
            b += db

            if sqrt(da * da + db * db) < tol { break }
        }

        let rmse = sqrt(clean.reduce(Float(0)) { sum, obs in
            let err = obs.heightM - predict(dbhCm: obs.dbhCm, a: a, b: b)
            return sum + err * err
        } / Float(clean.count))

        return Fit(a: a, b: b, nObs: clean.count, rmse: rmse)
    }

    // MARK: - Imputation

    /// §7.4 Step 4: predict H for a tree lacking a measured height.
    public static func impute(dbhCm: Float, fit: Fit) -> Float {
        predict(dbhCm: dbhCm, a: fit.a, b: fit.b)
    }
}
