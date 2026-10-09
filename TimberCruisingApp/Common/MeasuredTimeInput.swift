// One rule for a measurement time the cruiser SETS BY HAND.
//
// WHY THIS EXISTS. Sometimes a value will not go into the app at the tree, so
// the cruiser writes it in a notebook and types it in back at the office. The
// reading then carries the OFFICE time, and `createdAt` is not decoration: the
// validation analysis paired 97 trees across two phones by capture time
// because the phones do not share tree numbers, `TruthBackfill` attaches a
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
// Ported 1:1 from / to Android `common/MeasuredTimeInput.kt` — a cruise split
// across two phones must round and refuse identically, and every string below
// is byte-identical there.

import Foundation

public enum MeasuredTimeInput {

    /// What a picked time means.
    public enum Result: Equatable {
        /// Usable, already rounded down to the minute.
        case time(Date)
        /// Unusable. Carries the sentence to put on screen.
        case refused(String)
    }

    /// Why a time was refused. Byte-identical on both platforms.
    public enum Refusal {
        /// A measurement that has not happened yet is not a typo worth
        /// storing, and a future stamp would sort the row above readings that
        /// really are the newest.
        public static let future =
            "That time has not happened yet. Set the time the reading was measured."
    }

    /// The words this feature uses, in one place, so the two platforms cannot
    /// drift apart. Android holds the identical strings in `MeasuredTimeInput`.
    public enum Words {
        /// Row label wherever a reading's own time is shown.
        public static let label = "Measured"
        /// The editor's title.
        public static let editorTitle = "Measured at"
        public static let save = "Save time"
        public static let cancel = "Cancel"
        /// Appended to a time that did NOT come off the clock. The whole point
        /// of the stamp: a time the cruiser typed at a desk must never be
        /// indistinguishable from one the sensor recorded.
        public static let handSet = "set by hand"
        /// Shown while editing a reading that has NO capture behind it. Its
        /// stored time is only when the number was typed, so the notebook time
        /// is strictly better information and there is nothing to disagree
        /// with.
        public static let typedFooter =
            "Nothing was captured for this reading — the app stamped it when the number was typed in. Set the time it was measured."
        /// Shown while editing a reading that DOES have one. Said plainly at
        /// the moment of editing, because the raw-capture manifest holds the
        /// real capture instant and is never rewritten: after this the two
        /// records disagree, and `TruthBackfill` joins them by time.
        public static let sensorFooter =
            "This reading has a raw capture behind it. The capture keeps its own time, so the two will no longer agree."

        /// Where a reading's time came from, in words — the same shape as
        /// `FieldLogWords.positionSourceText`, and for the same reason.
        /// Unknown raws print themselves rather than being folded into
        /// "Stamped when recorded": a wrong provenance is worse than an
        /// opaque one.
        public static func sourceText(_ raw: String) -> String {
            switch raw {
            case "typed":  return "Typed by hand"
            case "device": return "Stamped when recorded"
            default:       return raw
            }
        }

        /// A printed time with its provenance attached — what every surface
        /// shows instead of the bare time. Built here rather than assembled at
        /// each call site so the marker can never be on one screen and missing
        /// from the next.
        public static func stamp(_ text: String, handSet: Bool) -> String {
            handSet ? "\(text) · \(self.handSet)" : text
        }

        /// The same thing, labelled, for a surface that lists readings rather
        /// than putting the time in a row of its own.
        public static func line(_ text: String, handSet: Bool) -> String {
            "\(label) \(stamp(text, handSet: handSet))"
        }
    }

    /// How a measurement time is PRINTED — the cruiser's own locale, date and
    /// time, no seconds. One printer, so the record sheet, the row and the
    /// editor's own read-back cannot show three different renderings of one
    /// instant.
    ///
    /// The formatter is built per call rather than cached in a global: this
    /// type is used from more than one actor, and a shared mutable
    /// `DateFormatter` is the classic way to make that unsafe. Drawing one
    /// sheet costs a handful of these.
    public static func text(_ date: Date) -> String {
        let f = DateFormatter()
        f.locale = .autoupdatingCurrent
        f.dateStyle = .medium
        f.timeStyle = .short          // minute precision, locale's own clock
        return f.string(from: date)
    }

    /// The instant a picked time actually stores: the same minute, seconds
    /// discarded.
    ///
    /// Floor, not round: rounding 10:41:59 up to 10:42 would store a minute
    /// the cruiser did not write. Done in epoch seconds because every real
    /// UTC offset is a whole number of minutes, so flooring the epoch and
    /// flooring the local wall clock are the same operation.
    public static func truncatedToMinute(_ date: Date) -> Date {
        let seconds = date.timeIntervalSince1970
        return Date(timeIntervalSince1970: (seconds / 60).rounded(.down) * 60)
    }

    /// Decide what a picked time means, changing nothing.
    ///
    /// `now` is a parameter so the same rule can be re-checked at the write
    /// (see `QuickMeasureHistory.setMeasuredTime`) rather than trusted from
    /// the screen that proposed it.
    public static func resolve(_ picked: Date, now: Date = Date()) -> Result {
        let stamped = truncatedToMinute(picked)
        // Compared against the WHOLE current instant, not its minute: picking
        // the minute the cruiser is standing in is legitimate (its seconds
        // floor to a moment already past), while the next minute is not.
        guard stamped <= now else { return .refused(Refusal.future) }
        return .time(stamped)
    }
}
