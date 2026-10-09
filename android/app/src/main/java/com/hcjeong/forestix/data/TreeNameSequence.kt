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
        val name = normalized(current)
        if (name.isEmpty()) return null
        val digits = trailingDigits.find(name)?.value ?: return name
        // Long, not Int: a pasted numeric id longer than nine digits must not
        // silently wrap into a different tree. Anything that will not parse is
        // handed back untouched, on the same principle as a name with no
        // number at all.
        val value = digits.toLongOrNull() ?: return name
        // `Long.MAX_VALUE + 1` WRAPS to Long.MIN_VALUE and would hand back a
        // name with a negative number in it; the Swift sibling's `Int.max + 1`
        // traps and crashes the app. Neither is a successor, so both platforms
        // return the name untouched — the same answer a name that will not
        // parse already gets.
        if (value == Long.MAX_VALUE) return name
        return name.dropLast(digits.length) +
            (value + 1).toString().padStart(digits.length, '0')
    }

    /// The name to offer for the next NEW tree, given every name already in
    /// the log, [names] ordered newest-first.
    ///
    /// [next] fed the most recent name is not enough, because the most recent
    /// name is not the highest one. Name T01, T02, T03, then re-measure T01
    /// from the peek card: that reading is appended carrying the name "T01"
    /// and a fresh timestamp, so the newest name in the log is "T01" again and
    /// the chooser proposes "T02" — a name already worn by another stem, now
    /// filed under a new tree number. The tree NUMBER suggestion is max + 1
    /// and cannot collide; the name has to be max + 1 too.
    ///
    /// The newest name still decides WHICH series is in play — the text in
    /// front of the trailing digits. A cruiser who moves from "T07" to "P2-01"
    /// has started a new run, and the successor comes from that run rather
    /// than from the abandoned one. Within the series, the highest number
    /// wins.
    fun nextInSeries(names: List<String>): String? {
        var series: String? = null
        var highest: String? = null
        var highestValue = Long.MIN_VALUE

        for (raw in names) {
            val name = normalized(raw)
            if (name.isEmpty()) continue
            val digits = trailingDigits.find(name)?.value.orEmpty()
            val stem = name.dropLast(digits.length)

            val inPlay = series
            if (inPlay == null) {
                // The first non-empty name is the newest one, and so fixes the
                // series every later name is compared against.
                series = stem
                highest = name
                // No trailing number, or one too long to parse: [next] hands
                // those back unchanged, and there is nothing for the rest of
                // the log to be higher than.
                val value = digits.toLongOrNull() ?: break
                highestValue = value
                continue
            }

            if (stem != inPlay) continue
            val value = digits.toLongOrNull() ?: continue
            if (value <= highestValue) continue
            highestValue = value
            highest = name
        }
        return next(highest)
    }

    // MARK: - Decomposition

    /// `trim()` strips newlines as well as spaces; the iOS sibling uses
    /// `.whitespacesAndNewlines` for the same reason — a pasted "T07\n" has to
    /// yield the same successor on both phones.
    private fun normalized(current: String?): String = current?.trim().orEmpty()
}
