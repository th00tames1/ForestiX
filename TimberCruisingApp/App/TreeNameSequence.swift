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
        let name = (current ?? "").trimmingCharacters(in: .whitespaces)
        guard !name.isEmpty else { return nil }

        var digits = ""
        for ch in name.reversed() {
            guard ch.isASCII, ch.isNumber else { break }
            digits.insert(ch, at: digits.startIndex)
        }
        guard !digits.isEmpty else { return name }
        // Int, not a smaller width: a pasted numeric id must not silently wrap
        // into a different tree. Anything that will not parse is handed back
        // untouched, on the same principle as a name with no number at all.
        guard let value = Int(digits) else { return name }

        let stem = String(name.dropLast(digits.count))
        var bumped = String(value + 1)
        if bumped.count < digits.count {
            bumped = String(repeating: "0", count: digits.count - bumped.count) + bumped
        }
        return stem + bumped
    }
}
