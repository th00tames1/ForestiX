// Spec §7.4 + REQ-HGT-007. Deterministic height-subsample rule evaluated
// once per tree at Add-Tree time to decide whether the flow asks for a
// measured height or leaves `heightM` nil for later H–D imputation.
//
// Pure function; uses only the rule + existing (non-deleted) trees on the
// plot. `newTreeNumber` is the tally number that would be assigned to the
// tree about to be added (typically liveCount+1 at the point of call).

import Foundation
import Models

public enum HeightSubsample {

    /// Returns `true` when the flow should require a measured height on the
    /// tree currently being added. Callers still allow the user to skip
    /// measurement (it will be imputed from the H–D model).
    ///
    /// Live trees only are counted when applying per-species rules — dead or
    /// soft-deleted trees don't carry measured heights into the subsample.
    public static func shouldMeasureHeight(
        rule: HeightSubsampleRule,
        newTreeNumber: Int,
        newSpeciesCode: String,
        existingTreesOnPlot: [Tree]
    ) -> Bool {
        switch rule {
        case .allTrees:
            return true
        case .none:
            return false
        case .everyKth(let k):
            guard k > 0 else { return true }
            // First tree (newTreeNumber == 1) always measured.
            return newTreeNumber % k == 1 || k == 1
        case .perSpeciesCount(let minPerSpeciesOnPlot):
            let haveMeasuredForSpecies = existingTreesOnPlot.filter {
                $0.deletedAt == nil
                && $0.speciesCode == newSpeciesCode
                && $0.heightM != nil
                && $0.heightSource == "measured"
            }.count
            return haveMeasuredForSpecies < minPerSpeciesOnPlot
        }
    }

    // MARK: - Plot-level progress (how much of the subsample is left)

    /// How many measured heights the rule asks of one plot, and how many of
    /// them it already has. The plot peek reads this to answer the only
    /// question a cruiser has in the stand: is this plot's subsample done.
    public struct Progress: Equatable, Sendable {
        /// Measured heights that COUNT TOWARD `target` — never the plot's
        /// raw height count, so the numerator can never overrun the
        /// denominator or report a plot as done on the strength of heights
        /// the rule did not ask for. When there is no target it falls back
        /// to the plot's plain measured count, which is all that can honestly
        /// be said.
        public let measured: Int
        /// How many the rule asks of THIS plot, or nil when the rule sets no
        /// target at all. NEVER invented: a nil (or zero) target has to be
        /// rendered as a plain count, not as a denominator.
        public let target: Int?
    }

    /// Progress of `rule` over one plot's trees. Deleted trees are dropped
    /// here exactly as `shouldMeasureHeight` drops them.
    ///
    /// One owner for the rule: `allTrees` and `everyKth` are decided per tree
    /// from the tree's own tally number, so their target is counted by asking
    /// `shouldMeasureHeight` itself rather than re-deriving the k arithmetic
    /// beside it — the peek cannot ask for a different number than the flow.
    public static func progress(
        rule: HeightSubsampleRule,
        treesOnPlot: [Tree]
    ) -> Progress {
        let live = treesOnPlot.filter { $0.deletedAt == nil }
        /// The plot's plain measured count — what is reported when the rule
        /// asks nothing of this plot and there is no denominator to pair with.
        let plainCount = live.filter(isMeasured).count

        switch rule {
        case .none:
            // The rule asks for nothing, so there is no denominator: a
            // fraction here would invent an obligation the cruiser does not
            // have. The plain count still reports what the plot carries,
            // because a height measured off-rule is still a height.
            return Progress(measured: plainCount, target: nil)

        case .perSpeciesCount(let minPerSpeciesOnPlot):
            // The rule is per SPECIES, so a single plot-wide "of N" is a lie
            // the moment a second species is tallied — a plot could satisfy
            // the rule for oak, carry nothing for pine, and still read done.
            // Summed per species instead, with BOTH sides capped at what that
            // species can supply: you cannot measure a tree that is not on
            // the plot, and surplus heights for one species never fill
            // another's quota.
            guard minPerSpeciesOnPlot > 0 else {
                return Progress(measured: plainCount, target: 0)
            }
            var target = 0
            var have = 0
            for (_, ofSpecies) in Dictionary(grouping: live, by: \.speciesCode) {
                target += min(minPerSpeciesOnPlot, ofSpecies.count)
                have += min(minPerSpeciesOnPlot, ofSpecies.filter(isMeasured).count)
            }
            return Progress(measured: have, target: target)

        case .allTrees, .everyKth:
            let selected = live.filter {
                shouldMeasureHeight(rule: rule,
                                    newTreeNumber: $0.treeNumber,
                                    newSpeciesCode: $0.speciesCode,
                                    existingTreesOnPlot: live)
            }
            // Counted over the SELECTED trees only. `everyKth` names
            // particular tally numbers, so a height on a tree the rule did
            // not pick does not close the gap on one it did: with k = 5 over
            // eleven trees, heights on #1–#3 are one of the three the rule
            // wants, not three of three, and #6 and #11 are still standing
            // unmeasured. Reading "3 of 3" there is the exact lie this
            // readout exists to prevent.
            guard !selected.isEmpty else {
                return Progress(measured: plainCount, target: 0)
            }
            return Progress(measured: selected.filter(isMeasured).count,
                            target: selected.count)
        }
    }

    /// The rule's own notion of "this tree already has one": a MEASURED
    /// height. `perSpeciesCount` counts exactly this, so the progress
    /// readout counts exactly this — an imputed height fills a volume, not a
    /// subsample slot, and must never make a plot read as done. In practice
    /// only the height flows write `heightM`, and they stamp
    /// `heightSource = "measured"` as they do; the tag is what separates
    /// them from imputed heights arriving through an import.
    private static func isMeasured(_ tree: Tree) -> Bool {
        tree.heightM != nil && tree.heightSource == "measured"
    }
}
