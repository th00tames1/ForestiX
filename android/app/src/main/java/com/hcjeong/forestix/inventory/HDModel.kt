// Port of iOS InventoryEngine/HDModel.swift.
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

package com.hcjeong.forestix.inventory

import kotlin.math.abs
import kotlin.math.sqrt

object HDModel {

    // MARK: - Observation

    /// One measured (DBH, height) pair. Swift uses a labelled tuple
    /// `(dbhCm: Float, heightM: Float)`; Kotlin needs a named type.
    data class Observation(val dbhCm: Float, val heightM: Float)

    // MARK: - Näslund form

    /// H(D; a, b) = 1.3 + D² / (a + b·D)².
    fun predict(dbhCm: Float, a: Float, b: Float): Float {
        val denom = a + b * dbhCm
        if (denom <= 0f) return 1.3f
        val dd = dbhCm * dbhCm
        return 1.3f + dd / (denom * denom)
    }

    /// Partial derivative of H with respect to a.
    internal fun partialA(dbhCm: Float, a: Float, b: Float): Float {
        val denom = a + b * dbhCm
        val dd = dbhCm * dbhCm
        return -2f * dd / (denom * denom * denom)
    }

    /// Partial derivative of H with respect to b.
    internal fun partialB(dbhCm: Float, a: Float, b: Float): Float {
        val denom = a + b * dbhCm
        val dd = dbhCm * dbhCm
        return -2f * dd * dbhCm / (denom * denom * denom)
    }

    // MARK: - Fit result

    data class Fit(
        val a: Float,
        val b: Float,
        val nObs: Int,
        val rmse: Float,
    ) {
        /// Persistence-side dictionary form used by `HeightDiameterFit.coefficients`.
        val coefficients: Map<String, Float>
            get() = mapOf("a" to a, "b" to b)

        companion object {
            /// Rehydrate a `Fit` from `HeightDiameterFit.coefficients`. Returns
            /// null when the dictionary is missing `a` or `b` (e.g. from a
            /// future model form that used different keys).
            fun fromCoefficients(
                dict: Map<String, Float>,
                nObs: Int,
                rmse: Float,
            ): Fit? {
                val a = dict["a"] ?: return null
                val b = dict["b"] ?: return null
                return Fit(a = a, b = b, nObs = nObs, rmse = rmse)
            }
        }
    }

    sealed class FitError(message: String) : Exception(message) {
        class NotEnoughObservations(val count: Int, val required: Int) :
            FitError("Not enough observations: $count (need ≥ $required)")

        class DidNotConverge :
            FitError("Gauss-Newton did not converge within iteration budget")

        class DegenerateInput(val reason: String) :
            FitError("Degenerate input: $reason")
    }

    // MARK: - Gauss-Newton fit

    /// Fit Näslund H–D to observed pairs. Returns the fit, or throws [FitError].
    ///
    /// - observations: `(dbhCm, heightM)` pairs. Heights ≤ 1.3 are dropped.
    /// - minN: minimum observations (§7.4 uses 8 at the species level).
    /// - maxIters: Gauss-Newton iteration budget.
    /// - tol: convergence tolerance on the parameter update norm.
    /// - initial: optional warm-start `(a, b)`.
    @Throws(FitError::class)
    fun fit(
        observations: List<Observation>,
        minN: Int = 8,
        maxIters: Int = 50,
        tol: Float = 1e-6f,
        initial: Pair<Float, Float>? = null,
    ): Fit {
        val clean = observations.filter { it.dbhCm > 0f && it.heightM > 1.3f }
        if (clean.size < minN) {
            throw FitError.NotEnoughObservations(count = clean.size, required = minN)
        }
        val dMean = clean.fold(0f) { acc, o -> acc + o.dbhCm } / clean.size.toFloat()
        if (dMean <= 0f) throw FitError.DegenerateInput(reason = "DBH mean = 0")

        // §7.4 initial guess, or warm-start from the caller's prior fit.
        var a: Float = initial?.first ?: (0.1f * dMean)
        var b: Float = initial?.second ?: 0.05f

        for (iter in 0 until maxIters) {
            // Normal equations for min ||y − H(p)||²:
            //     (GᵀG) Δp = Gᵀ r,  where G_{i·} = ∂H/∂p_i, r = y − H(p).
            var jtj00 = 0f; var jtj01 = 0f; var jtj11 = 0f
            var jtr0 = 0f; var jtr1 = 0f

            for ((d, h) in clean) {
                val hPred = predict(dbhCm = d, a = a, b = b)
                val r = h - hPred
                // Model Jacobian entries (∂H/∂a, ∂H/∂b).
                val ga = partialA(dbhCm = d, a = a, b = b)
                val gb = partialB(dbhCm = d, a = a, b = b)
                jtj00 += ga * ga
                jtj01 += ga * gb
                jtj11 += gb * gb
                jtr0 += ga * r
                jtr1 += gb * r
            }

            val det = jtj00 * jtj11 - jtj01 * jtj01
            if (abs(det) <= 1e-20f) {
                throw FitError.DegenerateInput(reason = "Singular normal matrix")
            }
            // Solve 2x2 system:  [jtj00 jtj01] [da]   [jtr0]
            //                    [jtj01 jtj11] [db] = [jtr1]
            val da = (jtj11 * jtr0 - jtj01 * jtr1) / det
            val db = (jtj00 * jtr1 - jtj01 * jtr0) / det

            a += da
            b += db

            if (sqrt(da * da + db * db) < tol) break
        }

        val rmse = sqrt(clean.fold(0f) { sum, obs ->
            val err = obs.heightM - predict(dbhCm = obs.dbhCm, a = a, b = b)
            sum + err * err
        } / clean.size.toFloat())

        return Fit(a = a, b = b, nObs = clean.size, rmse = rmse)
    }

    // MARK: - Rolling update (§7.4 "on plot close")

    /// Rolling refit. Callers assemble *all* measured (dbh, height)
    /// observations for the species — prior + newly-added — and hand
    /// them in. When `previous` is non-null, Gauss-Newton warms up from
    /// its coefficients for faster convergence and better stability
    /// when the new observations barely move the fit.
    @Throws(FitError::class)
    fun update(
        previous: Fit?,
        observations: List<Observation>,
        minN: Int = 8,
        maxIters: Int = 50,
        tol: Float = 1e-6f,
    ): Fit {
        val warm: Pair<Float, Float>? = previous?.let { it.a to it.b }
        return fit(
            observations = observations,
            minN = minN,
            maxIters = maxIters,
            tol = tol,
            initial = warm)
    }

    // MARK: - Imputation

    /// §7.4 Step 4: predict H for a tree lacking a measured height.
    fun impute(dbhCm: Float, fit: Fit): Float =
        predict(dbhCm = dbhCm, a = fit.a, b = fit.b)
}
