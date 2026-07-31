// One rule for a measurement time the cruiser SETS BY HAND.
//
// WHY THIS EXISTS. Sometimes a value will not go into the app at the tree, so
// the cruiser writes it in a notebook and types it in back at the office. The
// reading then carries the OFFICE time, and `createdAt` is not decoration: the
// validation analysis paired 97 trees across two phones by capture time
// because the phones do not share tree numbers, TruthBackfill attaches a
// manifest truth to a reading by nearest timestamp, and the export classifier
// reads live-vs-superseded partly off time order. A wrong time is therefore a
// wrong join, not a cosmetic blemish.
//
// So the time may be edited — and never silently. This file holds the two
// halves of that: the RULE (what a set time is allowed to be, and what it is
// rounded to) and the WORDS (what the cruiser is told while setting it).
//
// TO THE MINUTE, because that is the precision the cruiser records: asked
// directly, they said 분 단위까지. A picker that offered seconds would invite a
// precision the notebook does not have, and a stored second the cruiser never
// chose would be a number the app invented.
//
// Ported 1:1 from / to iOS `Common/MeasuredTimeInput.swift` — a cruise split
// across two phones must round and refuse identically, and every string below
// is byte-identical there.

package com.hcjeong.forestix.common

import java.text.DateFormat
import java.util.Date
import java.util.Locale

object MeasuredTimeInput {

    /// What a picked time means.
    sealed interface Result {
        /// Usable, already rounded down to the minute.
        data class Time(val epochMs: Long) : Result

        /// Unusable. Carries the sentence to put on screen.
        data class Refused(val message: String) : Result
    }

    /// Why a time was refused. Byte-identical on both platforms.
    object Refusal {
        /// A measurement that has not happened yet is not a typo worth
        /// storing, and a future stamp would sort the row above readings that
        /// really are the newest.
        const val FUTURE =
            "That time has not happened yet. Set the time the reading was measured."
    }

    /// The words this feature uses, in one place, so the two platforms cannot
    /// drift apart. iOS holds the identical strings in `MeasuredTimeInput.Words`.
    object Words {
        /// Row label wherever a reading's own time is shown.
        const val LABEL = "Measured"

        /// The editor's title.
        const val EDITOR_TITLE = "Measured at"
        const val SAVE = "Save time"
        const val CANCEL = "Cancel"

        /// Appended to a time that did NOT come off the clock. The whole point
        /// of the stamp: a time the cruiser typed at a desk must never be
        /// indistinguishable from one the sensor recorded.
        const val HAND_SET = "set by hand"

        /// Shown while editing a reading that has NO capture behind it. Its
        /// stored time is only when the number was typed, so the notebook time
        /// is strictly better information and there is nothing to disagree
        /// with.
        const val TYPED_FOOTER =
            "Nothing was captured for this reading — the app stamped it when " +
                "the number was typed in. Set the time it was measured."

        /// Shown while editing a reading that DOES have one. Said plainly at
        /// the moment of editing, because the raw-capture manifest holds the
        /// real capture instant and is never rewritten: after this the two
        /// records disagree, and TruthBackfill joins them by time.
        const val SENSOR_FOOTER =
            "This reading has a raw capture behind it. The capture keeps its " +
                "own time, so the two will no longer agree."

        /// Where a reading's time came from, in words — the same shape as
        /// `FieldLogWords.positionSourceText`, and for the same reason.
        /// Unknown raws print themselves rather than being folded into
        /// "Stamped when recorded": a wrong provenance is worse than an opaque
        /// one.
        fun sourceText(raw: String): String = when (raw) {
            "typed" -> "Typed by hand"
            "device" -> "Stamped when recorded"
            else -> raw
        }

        /// A printed time with its provenance attached — what every surface
        /// shows instead of the bare time. Built here rather than assembled at
        /// each call site so the marker can never be on one screen and missing
        /// from the next.
        fun stamp(text: String, handSet: Boolean): String =
            if (handSet) "$text · $HAND_SET" else text

        /// The same thing, labelled, for a surface that lists readings rather
        /// than putting the time in a row of its own.
        fun line(text: String, handSet: Boolean): String =
            "$LABEL ${stamp(text, handSet)}"
    }

    /// How a measurement time is PRINTED — the cruiser's own locale, date and
    /// time, no seconds. One printer, so the record sheet, the row and the
    /// editor's own read-back cannot show three different renderings of one
    /// instant.
    ///
    /// MEDIUM date + SHORT time, which is what the iOS sibling's
    /// `dateStyle = .medium, timeStyle = .short` resolves to, so the same
    /// reading reads the same way on either phone.
    fun text(epochMs: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault())
            .format(Date(epochMs))

    /// The instant a picked time actually stores: the same minute, seconds
    /// discarded.
    ///
    /// Floor, not round: rounding 10:41:59 up to 10:42 would store a minute
    /// the cruiser did not write. Done in epoch millis because every real UTC
    /// offset is a whole number of minutes, so flooring the epoch and flooring
    /// the local wall clock are the same operation. `floorDiv`, not `/`, so a
    /// pre-1970 instant floors backwards rather than towards zero.
    fun truncatedToMinute(epochMs: Long): Long =
        Math.floorDiv(epochMs, 60_000L) * 60_000L

    /// Decide what a picked time means, changing nothing.
    ///
    /// [now] is a parameter so the same rule can be re-checked at the write
    /// (see `QuickMeasureHistory.setMeasuredTime`) rather than trusted from the
    /// screen that proposed it.
    fun resolve(pickedMs: Long, now: Long = System.currentTimeMillis()): Result {
        val stamped = truncatedToMinute(pickedMs)
        // Compared against the WHOLE current instant, not its minute: picking
        // the minute the cruiser is standing in is legitimate (its seconds
        // floor to a moment already past), while the next minute is not.
        return if (stamped <= now) Result.Time(stamped) else Result.Refused(Refusal.FUTURE)
    }
}
