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
}

/// What every cruise surface calls this tree — see [TreeLabel.title].
val Tree.displayTitle: String
    get() = TreeLabel.title(treeName, treeNumber)
