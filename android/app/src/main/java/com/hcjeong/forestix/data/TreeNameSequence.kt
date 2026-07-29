// The rule behind "name one tree, and the next one names itself".
// Ported 1:1 from / to iOS App/TreeNameSequence.swift — a cruise split across
// an iPhone and an Android phone joins on tree_name, so the two platforms must
// agree on every successor, including the padding.

package com.hcjeong.forestix.data

object TreeNameSequence {

    private val trailingDigits = Regex("\\d+$")

    /// The name to offer for the tree AFTER [current].
    ///
    /// A trailing number is incremented and re-padded to the width it had, so
    /// "Plot3-T07" -> "Plot3-T08" and "12" -> "13". The padding is kept
    /// because it is what makes a tally sheet sort in tree order.
    ///
    /// A name with NO trailing number comes back UNCHANGED rather than
    /// growing a "2": the app cannot tell whether "Big oak" is the start of a
    /// series, and a silently invented "Big oak2" would put a name on a tree
    /// the cruiser never chose. They retype it instead.
    fun next(current: String?): String? {
        val name = current?.trim().orEmpty()
        if (name.isEmpty()) return null
        val digits = trailingDigits.find(name)?.value ?: return name
        // Long, not Int: a pasted numeric id longer than nine digits must not
        // silently wrap into a different tree. Anything that will not parse is
        // handed back untouched, on the same principle as a name with no
        // number at all.
        val incremented = (digits.toLongOrNull() ?: return name) + 1
        return name.dropLast(digits.length) +
            incremented.toString().padStart(digits.length, '0')
    }
}
