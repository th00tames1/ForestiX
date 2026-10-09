// The rule behind "name one tree, and the next one names itself".
// Ported 1:1 from / to Android data/TreeNameSequence.kt — a cruise split
// across an iPhone and an Android phone joins on tree_name, so the two
// platforms must agree on every successor, including the padding.

import Foundation

public enum TreeNameSequence {

    /// The name to offer for the tree AFTER `current`.
    ///
    /// A trailing number is incremented and re-padded to the width it had, so
    /// "Plot3-T07" -> "Plot3-T08" and "12" -> "13". The padding is kept
    /// because it is what makes a tally sheet sort in tree order.
    ///
    /// A name with NO trailing number comes back UNCHANGED rather than
    /// growing a "2": the app cannot tell whether "Big oak" is the start of a
    /// series, and a silently invented "Big oak2" would put a name on a tree
    /// the cruiser never chose. They retype it instead.
    public static func next(_ current: String?) -> String? {
        let name = normalized(current)
        guard !name.isEmpty else { return nil }

        let digits = trailingDigits(of: name)
        guard !digits.isEmpty else { return name }
        // Int, not a smaller width: a pasted numeric id must not silently wrap
        // into a different tree. Anything that will not parse is handed back
        // untouched, on the same principle as a name with no number at all.
        guard let value = Int(digits) else { return name }
        // `Int.max + 1` TRAPS in Swift, which would crash the app outright on
        // a pasted 19-digit id; Kotlin's `Long.MAX_VALUE + 1` wraps to
        // Long.MIN_VALUE and hands back a name with a negative number in it.
        // Neither is a successor, so both platforms return the name untouched
        // — the same answer a name that will not parse already gets.
        let (incremented, overflowed) = value.addingReportingOverflow(1)
        guard !overflowed else { return name }

        let stem = String(name.dropLast(digits.count))
        var bumped = String(incremented)
        if bumped.count < digits.count {
            bumped = String(repeating: "0", count: digits.count - bumped.count) + bumped
        }
        return stem + bumped
    }

    /// The name to offer for the next NEW tree, given every name already in
    /// the log, `names` ordered newest-first.
    ///
    /// `next(_:)` fed the most recent name is not enough, because the most
    /// recent name is not the highest one. Name T01, T02, T03, then re-measure
    /// T01 from the peek card: that reading is appended carrying the name
    /// "T01" and a fresh timestamp, so the newest name in the log is "T01"
    /// again and the chooser proposes "T02" — a name already worn by another
    /// stem, now filed under a new tree number. The tree NUMBER suggestion is
    /// max + 1 and cannot collide; the name has to be max + 1 too.
    ///
    /// The newest name still decides WHICH series is in play — the text in
    /// front of the trailing digits. A cruiser who moves from "T07" to "P2-01"
    /// has started a new run, and the successor comes from that run rather
    /// than from the abandoned one. Within the series, the highest number
    /// wins.
    public static func nextInSeries(_ names: [String]) -> String? {
        var series: String?
        var highest: String?
        var highestValue = Int.min

        for raw in names {
            let name = normalized(raw)
            guard !name.isEmpty else { continue }
            let digits = trailingDigits(of: name)
            let stem = String(name.dropLast(digits.count))

            guard let inPlay = series else {
                // The first non-empty name is the newest one, and so fixes the
                // series every later name is compared against.
                series = stem
                highest = name
                // No trailing number, or one too long to parse: `next(_:)`
                // hands those back unchanged, and there is nothing for the
                // rest of the log to be higher than.
                guard !digits.isEmpty, let value = Int(digits) else { break }
                highestValue = value
                continue
            }

            guard stem == inPlay,
                  !digits.isEmpty,
                  let value = Int(digits),
                  value > highestValue else { continue }
            highestValue = value
            highest = name
        }
        return next(highest)
    }

    // MARK: - Decomposition

    /// `.whitespacesAndNewlines`, not `.whitespaces`: Kotlin's `trim()` in the
    /// Android sibling strips newlines too, and a pasted "T07\n" has to yield
    /// the same successor on both phones.
    private static func normalized(_ current: String?) -> String {
        (current ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// ASCII digits only, matching Kotlin's `\d+$`, which is ASCII-only unless
    /// UNICODE_CHARACTER_CLASS is set. Empty when the name ends in anything
    /// else.
    private static func trailingDigits(of name: String) -> String {
        var digits = ""
        for ch in name.reversed() {
            guard ch.isASCII, ch.isNumber else { break }
            digits.insert(ch, at: digits.startIndex)
        }
        return digits
    }
}
