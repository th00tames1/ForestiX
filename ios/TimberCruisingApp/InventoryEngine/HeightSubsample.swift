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
}
