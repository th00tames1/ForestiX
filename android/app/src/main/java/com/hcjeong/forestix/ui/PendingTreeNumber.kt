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
        /// The plot the reading must land in. Null = the active plot, which
        /// is what the map home wants; the field log names it explicitly,
        /// because a row being re-measured can belong to a plot that is not
        /// the active one and the replacement has to find it there.
        val plotID: java.util.UUID? = null,
    )

    @Volatile
    private var lock: Lock? = null

    fun set(
        number: Int?,
        name: String? = null,
        speciesCode: String? = null,
        replaceExisting: Boolean = false,
        truth: Double? = null,
        plotID: java.util.UUID? = null,
    ) {
        lock = Lock(number, name?.takeIf { it.isNotBlank() }, speciesCode,
                    replaceExisting, truth, plotID)
    }

    /// Read-and-clear, so a stale lock can never leak into a later,
    /// unrelated capture session.
    fun consume(): Lock? = lock.also { lock = null }
}
