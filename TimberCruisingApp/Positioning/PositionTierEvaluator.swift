// Phase 4 §7.3 tier logic. REQ-CTR-005.
//
// Aggregates the up to four plot-center strategies (external RTK,
// GPS averaging, VIO offset-from-opening, VIO chain — per §7.3's
// "four strategies in priority order") and picks one to record on
// the Plot. The picking rule is simply: highest tier wins, with a
// source-priority tie-break that matches the spec's ordering.
//
// This module is pure data-in / data-out so it's reachable from
// macOS tests — the iOS glue lives in the ViewModels that feed
// candidates in from the live GPS / ARKit sessions.

import Foundation
import Models

public enum PositionTierEvaluator {

    /// Per-candidate trace kept on the decision so the UI can show
    /// "chose GPS (A) over VIO offset (B)" and so the debug log can
    /// inspect every strategy that was considered.
    public struct Candidate: Sendable, Equatable {
        public let result: PlotCenterResult
        /// Free-form note: "accepted", "rejected: tracking broke",
        /// "not run", etc. Kept on the evaluator output so the UI
        /// can surface why a higher-priority option was skipped.
        public let note: String

        public init(result: PlotCenterResult, note: String = "accepted") {
            self.result = result
            self.note = note
        }

        public static func == (lhs: Candidate, rhs: Candidate) -> Bool {
            lhs.note == rhs.note
                && lhs.result.lat == rhs.result.lat
                && lhs.result.lon == rhs.result.lon
                && lhs.result.source == rhs.result.source
                && lhs.result.tier == rhs.result.tier
        }
    }

    public struct Decision: Sendable, Equatable {
        public let chosen: PlotCenterResult
        /// Every strategy that had something to contribute. Useful
        /// for the "why did it pick this one" UI on the plot screen.
        public let considered: [Candidate]

        public init(chosen: PlotCenterResult, considered: [Candidate]) {
            self.chosen = chosen
            self.considered = considered
        }

        public static func == (lhs: Decision, rhs: Decision) -> Bool {
            lhs.considered == rhs.considered
                && lhs.chosen.source == rhs.chosen.source
                && lhs.chosen.tier == rhs.chosen.tier
                && lhs.chosen.lat == rhs.chosen.lat
                && lhs.chosen.lon == rhs.chosen.lon
        }
    }

    /// Decide which plot-center result to record. Returns nil if no
    /// strategy produced a usable result (i.e. `candidates` is empty
    /// or every entry is a "rejected" placeholder — callers should
    /// keep non-nil results only and let the evaluator pick).
    ///
    /// Rule:
    ///   1. Highest tier wins (A > B > C > D).
    ///   2. Tie → source priority per §7.3: externalRTK first, then
    ///      gpsAveraged, vioOffset, vioChain, manual.
    public static func decide(candidates: [Candidate]) -> Decision? {
        guard let best = candidates.max(by: { lhs, rhs in
            let l = lhs.result, r = rhs.result
            if l.tier != r.tier { return tierRank(l.tier) < tierRank(r.tier) }
            return sourcePriority(l.source) > sourcePriority(r.source)
        }) else { return nil }
        return Decision(chosen: best.result, considered: candidates)
    }

    // MARK: - Ordering tables

    /// A=3 .. D=0 so `max(by:)` picks the best tier.
    @inlinable
    public static func tierRank(_ t: PositionTier) -> Int {
        switch t {
        case .A: return 3
        case .B: return 2
        case .C: return 1
        case .D: return 0
        }
    }

    /// Lower number = higher priority. Matches §7.3's "four
    /// strategies in priority order".
    @inlinable
    public static func sourcePriority(_ s: PositionSource) -> Int {
        switch s {
        case .externalRTK: return 0
        case .gpsAveraged: return 1
        case .vioOffset:   return 2
        case .vioChain:    return 3
        case .manual:      return 4
        }
    }
}
