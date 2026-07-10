// Pending tree-number lock — Android analogue of iOS MapHomeScreen's
// `pendingTreeNumber` @State. The map home WRITES it when a capture flow
// is launched with a known tree identity:
//   · peek-card "Measure this tree" → the tapped pin's tree number
//   · chooser Full / DBH / Height rows → history.suggestedNextTreeNumber
// so the accepted reading lands on the tree the header promised.
// The DBH / Height scan screens CONSUME (and clear) it when composing the
// entry's treeNumber.

package com.hcjeong.forestix.ui

/// Single-slot handoff between the map home and the scan screens. Nullable
/// on purpose: null means "no lock — let the scan screen pick its own
/// number" (today's behaviour for flows launched outside the map home).
object PendingTreeNumber {
    @Volatile
    var value: Int? = null

    /// Read-and-clear, so a stale lock can never leak into a later,
    /// unrelated capture session.
    fun consume(): Int? = value.also { value = null }
}
