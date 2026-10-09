// Position tier evaluator — 1:1 port of iOS
// Positioning/PositionTierEvaluator.swift (Phase 4 §7.3, REQ-CTR-005).
//
// Aggregates the up to four plot-center strategies (external RTK,
// GPS averaging, VIO offset-from-opening, VIO chain — per §7.3's
// "four strategies in priority order") and picks one to record on
// the Plot. The picking rule is simply: highest tier wins, with a
// source-priority tie-break that matches the spec's ordering.
//
// This module is pure data-in / data-out — the platform glue lives in
// the view-models that feed candidates in from the live GPS / AR
// sessions.

package com.hcjeong.forestix.positioning

import com.hcjeong.forestix.data.cruise.PlotCenterResult
import com.hcjeong.forestix.data.cruise.PositionSource
import com.hcjeong.forestix.data.cruise.PositionTier

object PositionTierEvaluator {

    /// Per-candidate trace kept on the decision so the UI can show
    /// "chose GPS (A) over VIO offset (B)" and so the debug log can
    /// inspect every strategy that was considered.
    class Candidate(
        val result: PlotCenterResult,
        /// Free-form note: "accepted", "rejected: tracking broke",
        /// "not run", etc. Kept on the evaluator output so the UI
        /// can surface why a higher-priority option was skipped.
        val note: String = "accepted",
    ) {
        /// Mirrors the custom Swift ==: note + lat/lon/source/tier only.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Candidate) return false
            return note == other.note &&
                result.lat == other.result.lat &&
                result.lon == other.result.lon &&
                result.source == other.result.source &&
                result.tier == other.result.tier
        }

        override fun hashCode(): Int {
            var h = note.hashCode()
            h = 31 * h + result.lat.hashCode()
            h = 31 * h + result.lon.hashCode()
            h = 31 * h + result.source.hashCode()
            h = 31 * h + result.tier.hashCode()
            return h
        }
    }

    class Decision(
        val chosen: PlotCenterResult,
        /// Every strategy that had something to contribute. Useful
        /// for the "why did it pick this one" UI on the plot screen.
        val considered: List<Candidate>,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Decision) return false
            return considered == other.considered &&
                chosen.source == other.chosen.source &&
                chosen.tier == other.chosen.tier &&
                chosen.lat == other.chosen.lat &&
                chosen.lon == other.chosen.lon
        }

        override fun hashCode(): Int {
            var h = considered.hashCode()
            h = 31 * h + chosen.source.hashCode()
            h = 31 * h + chosen.tier.hashCode()
            h = 31 * h + chosen.lat.hashCode()
            h = 31 * h + chosen.lon.hashCode()
            return h
        }
    }

    /// Decide which plot-center result to record. Returns null if no
    /// strategy produced a usable result (i.e. `candidates` is empty
    /// or every entry is a "rejected" placeholder — callers should
    /// keep non-null results only and let the evaluator pick).
    ///
    /// Rule:
    ///   1. Highest tier wins (A > B > C > D).
    ///   2. Tie → source priority per §7.3: externalRTK first, then
    ///      gpsAveraged, vioOffset, vioChain, manual.
    fun decide(candidates: List<Candidate>): Decision? {
        val best = candidates.maxWithOrNull(
            compareBy(
                { tierRank(it.result.tier) },
                { -sourcePriority(it.result.source) },
            )
        ) ?: return null
        return Decision(chosen = best.result, considered = candidates)
    }

    // MARK: - Ordering tables

    /// A=3 .. D=0 so the max pick chooses the best tier.
    fun tierRank(t: PositionTier): Int = when (t) {
        PositionTier.A -> 3
        PositionTier.B -> 2
        PositionTier.C -> 1
        PositionTier.D -> 0
    }

    /// Lower number = higher priority. Matches §7.3's "four
    /// strategies in priority order".
    ///
    /// `GPS_SINGLE` sits below both VIO strategies and above `MANUAL`: an
    /// un-averaged fix taken under canopy can be tens of metres out with
    /// nothing to reveal it, whereas a VIO offset or chain is anchored to a
    /// position that WAS averaged and degrades by a bounded drift fraction.
    /// Only the numbers' ORDER is meaningful — `decide` compares them, it
    /// never reads them as ranks — so inserting a case renumbers freely.
    fun sourcePriority(s: PositionSource): Int = when (s) {
        PositionSource.EXTERNAL_RTK -> 0
        PositionSource.GPS_AVERAGED -> 1
        PositionSource.VIO_OFFSET -> 2
        PositionSource.VIO_CHAIN -> 3
        PositionSource.GPS_SINGLE -> 4
        PositionSource.MANUAL -> 5
    }
}
