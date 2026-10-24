// Pending tree identity — Android analogue of iOS MapHomeScreen's
// `pendingTreeNumber` / `pendingTreeName` / `pendingSpeciesCode` @State. The
// map home WRITES it when a capture flow is launched with a known tree:
//   · peek-card "Measure this tree" → the tapped pin's tree number
//   · chooser Full / DBH / Height rows → the next free number, plus the name
//     and species the cruiser typed above the Full measurement row
// so the accepted reading lands on the tree the header promised, under the
// name the cruiser gave it.
// The DBH / Height scan screens CONSUME (and clear) it when composing the
// entry.

package com.hcjeong.forestix.ui

import com.hcjeong.forestix.data.fieldstudy.FieldStudyTarget
import com.hcjeong.forestix.data.fieldstudy.FieldUIFlowClock

/// Single-slot handoff between the map home and the scan screens. Every field
/// is nullable on purpose: null means "no lock — let the scan screen pick its
/// own" (today's behaviour for flows launched outside the map home).
object PendingTreeNumber {

    /// What the caller promised about the tree about to be measured.
    data class Lock(
        val number: Int? = null,
        val name: String? = null,
        val speciesCode: String? = null,
        /// True when this scan is a RE-MEASURE launched from the field log:
        /// the accepted reading takes the place of the one already on the
        /// tree instead of being appended beside it.
        val replaceExisting: Boolean = false,
        /// The ground truth already typed for the reading being replaced.
        /// The tape value did not change because the scan did, so it rides
        /// along rather than being lost with the superseded reading.
        val truth: Double? = null,
        /// How that truth got onto the superseded reading (a
        /// `QuickMeasureEntry.truthSource` raw, null = typed against the
        /// reading). It rides along with the value because the two are one
        /// fact: re-labelling a capture-recovered truth as one typed here
        /// would claim a provenance the cruiser never gave it.
        val truthSource: String? = null,
        /// The unit that truth was typed in (a `QuickMeasureEntry.truthUnit`
        /// raw, null = not stated). Carried for the same reason as the source,
        /// and with one extra consequence: it is TruthUnitRepair's durable
        /// "already re-based" mark, so a re-measure that dropped it would put a
        /// repaired truth back in front of the repair.
        val truthUnit: String? = null,
        /// The plot the reading must land in. Null = the active plot, which
        /// is what the map home wants; the field log names it explicitly,
        /// because a row being re-measured can belong to a plot that is not
        /// the active one and the replacement has to find it there.
        val plotID: java.util.UUID? = null,
        val fieldTarget: FieldStudyTarget? = null,
        val fieldTargetLocked: Boolean = false,
        val uiFlow:FieldUIFlowClock? = null,
        val fieldScope:com.hcjeong.forestix.data.fieldstudy.FieldMeasurementScope? = null,
    )

    @Volatile
    private var lock: Lock? = null

    fun set(
        number: Int?,
        name: String? = null,
        speciesCode: String? = null,
        replaceExisting: Boolean = false,
        truth: Double? = null,
        truthSource: String? = null,
        truthUnit: String? = null,
        plotID: java.util.UUID? = null,
        fieldTarget: FieldStudyTarget? = null,
        fieldTargetLocked: Boolean = false,
        uiFlow:FieldUIFlowClock? = null,
        fieldScope:com.hcjeong.forestix.data.fieldstudy.FieldMeasurementScope? = null,
    ) {
        // TRIMMED before it is stored, exactly as iOS does in
        // MapHomeScreen.lockChooserTree(). The name is a join key: a split
        // cruise pairs an iPhone's rows with an Android phone's on tree_name,
        // and the field log and the tree_name CSV column print it verbatim.
        // A gloved thumb or a paste that leaves a leading space must not
        // create " Plot3-T07" here and "Plot3-T07" there.
        lock = Lock(number, name?.trim()?.takeIf { it.isNotEmpty() }, speciesCode,
                    replaceExisting, truth, truthSource, truthUnit, plotID, fieldTarget, fieldTargetLocked, uiFlow,fieldScope)
    }

    /// Read-and-clear, so a stale lock can never leak into a later,
    /// unrelated capture session.
    fun consume(): Lock? = lock.also { lock = null }
    fun clearFieldTarget(id:String){if(lock?.fieldTarget?.id==id)lock=null}

    /** Identity-owned handoff: failed navigation may clear only its own payload. */
    fun stage(payload:Lock):Lock {
        val staged=payload.copy(name=payload.name?.trim()?.takeIf{it.isNotEmpty()},
            fieldTarget=payload.fieldTarget?.let{it.copy(attributes=it.attributes.toMap())})
        lock=staged;return staged
    }
    fun clearStaged(staged:Lock){if(lock===staged)lock=null}
}
