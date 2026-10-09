// Pooled (species-blind) H–D support for the cruise quick-tally world
// (field-benchmark batch, both platforms). Heights are measured on
// demand — a handful of (DBH, height) pairs per plot — so plot/stand
// volume estimates missing heights from a POOLED Näslund curve over those
// pairs whenever ≥ 3 exist, reusing the EXISTING HDModel Gauss-Newton
// machinery at a lower minN. No new statistics: the §7.4 per-species
// project fits (n ≥ 8, persisted on plot close) stay the authority where
// they exist — the pooled curve only fills species without one. Callers
// pool PLOT-level pairs first and fall back to a STAND-level (all-plots)
// pool when the plot's own pool is too small.

package com.hcjeong.forestix.inventory

import com.hcjeong.forestix.data.cruise.Tree

object PooledHeights {

    /// Caption gate (locked): "Height curve active — other trees
    /// estimated" appears at ≥ 3 measured (DBH, H) pairs.
    const val MIN_PAIRS = 3

    /// The pooled (DBH, height) observations: non-deleted trees carrying a
    /// real height (same > 1.3 m / DBH > 0 guards as the §7.4 rollup; no
    /// species dimension). Matches the sample heights sheet's row list —
    /// NOT the peek's "N of M", which counts the subsample the rule asks
    /// for (HeightSubsample.progress) and so counts a measured height the
    /// pooled fit cannot use.
    fun pairs(trees: List<Tree>): List<HDModel.Observation> =
        trees.filter { it.deletedAt == null }
            .mapNotNull { t ->
                val h = t.heightM ?: return@mapNotNull null
                if (h > 1.3f && t.dbhCm > 0f) HDModel.Observation(t.dbhCm, h) else null
            }

    /// Pooled Näslund fit over ≥ MIN_PAIRS pairs via the existing
    /// Gauss-Newton fitter; null when the pool is too small or the fit
    /// degenerates/diverges (callers simply keep today's behaviour then).
    fun fit(trees: List<Tree>): HDModel.Fit? {
        val obs = pairs(trees)
        if (obs.size < MIN_PAIRS) return null
        return try {
            HDModel.fit(observations = obs, minN = MIN_PAIRS)
        } catch (_: Exception) {
            null
        }
    }

    /// Effective per-species fit map for PlotStatsCalculator: the persisted
    /// §7.4 per-species fits win; the pooled curve fills every species
    /// present on the plot (including the blank code) that lacks one.
    fun overlay(
        persisted: Map<String, HDModel.Fit>,
        pooled: HDModel.Fit?,
        trees: List<Tree>,
    ): Map<String, HDModel.Fit> {
        if (pooled == null) return persisted
        val out = persisted.toMutableMap()
        for (code in trees.map { it.speciesCode }.distinct()) {
            if (code !in out) out[code] = pooled
        }
        return out
    }
}
