// The one rule for turning a tree's identity into the words on screen.
// Ported 1:1 from / to iOS Models/Tree.swift: a cruise split across an iPhone
// and an Android phone must not print one stem two ways.

package com.hcjeong.forestix.data.cruise

/// It lives apart from [Tree] because the tally loop has to label the tree it
/// is ABOUT to write — a target number and a pending name, with no row behind
/// them yet. Both callers must produce the same string, so both come through
/// here rather than one of them re-typing the format.
object TreeLabel {

    /// The cruiser's own name when they gave the tree one, else
    /// "Tree #<number>" — the same shape as the field log's
    /// `FieldLogRowModel.title`, so the two worlds call a tree one thing.
    ///
    /// The name is used VERBATIM. It was trimmed on the way in (the chooser
    /// and the tally both trim before storing) precisely so nothing downstream
    /// has to guess whether " Plot3-T07" and "Plot3-T07" are the same stem.
    fun title(name: String?, number: Int): String = name ?: "Tree #$number"

    /// How many glyphs a map pin can actually hold.
    ///
    /// The pin is a 30 dp teardrop with a 10.5 sp monospaced label drawn
    /// INSIDE it (see the PIN branch of `basemap/MapView.kt` and the iOS
    /// `BasemapMapView.teardropHead`). Four monospaced characters is what
    /// fits at full size; past that Android overflows the drop and iOS
    /// shrinks the type towards illegible.
    const val PIN_LABEL_MAX_CHARS = 4

    /// What a MAP PIN calls this tree — the short form of [title].
    ///
    /// The map used to print "T104" even for a tree the cruiser had named,
    /// which is the whole complaint. It cannot simply print the name: at
    /// four glyphs "Starker32" becomes "Star", and every tree in a stand
    /// named by one convention shares that prefix, so the pin would stop
    /// distinguishing the very trees it exists to distinguish.
    ///
    /// So the rule is TRAILING-BIASED, because that is where a cruiser's
    /// naming scheme puts the part that varies:
    ///   • no name          -> "T<number>", exactly as before;
    ///   • name that fits   -> the name, whole;
    ///   • longer name with a trailing digit run -> those digits
    ///     ("Starker32" -> "32", "Plot3-T07" -> "07");
    ///   • otherwise        -> the last few characters ("Big Doug" -> "Doug").
    /// Nothing is ellipsised: at four glyphs a "…" spends a quarter of the
    /// label saying "there is more", which the peek already says by
    /// printing the full [title] the moment the pin is tapped.
    ///
    /// A named pin therefore has no leading "T" and an unnamed one does,
    /// which is itself the signal that this stem is called something.
    fun pinTitle(name: String?, number: Int): String {
        if (name.isNullOrEmpty()) return "T$number"
        if (name.length <= PIN_LABEL_MAX_CHARS) return name
        val tailDigits = name.takeLastWhile { it in '0'..'9' }
        if (tailDigits.isNotEmpty()) return tailDigits.takeLast(PIN_LABEL_MAX_CHARS)
        return name.takeLast(PIN_LABEL_MAX_CHARS)
    }
}

/// What every cruise surface calls this tree — see [TreeLabel.title].
val Tree.displayTitle: String
    get() = TreeLabel.title(treeName, treeNumber)
