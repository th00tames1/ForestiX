// Core measurement records — direct port of the iOS
// QuickMeasureEntry / QuickMeasurePlot structs (App/QuickMeasureHistory.swift).
// Field names, kinds, and unit semantics match 1:1 so CSV / bundle exports
// are byte-comparable with the iOS app.

package com.hcjeong.forestix.data

import com.hcjeong.forestix.common.Units
import com.hcjeong.forestix.data.cruise.PositionSource
import java.util.UUID

/// What a reading measures. `value` / `secondaryValue` semantics:
///   dbh          -> value = diameter (cm)
///   height       -> value = height (m)
///   crown        -> value = width (m),  secondaryValue = height (m)
///   distance     -> value = distance (m); method carries live/two-point + source
///   samplingPlot -> value = radius (m), secondaryValue = area (m^2)
enum class MeasureKind { DBH, HEIGHT, CROWN, DISTANCE, SAMPLING_PLOT;
    /// Lowercase rawValue matching the Swift enum cases (camelCase) so
    /// JSON / CSV is identical across platforms.
    val raw: String
        get() = when (this) {
            DBH -> "dbh"
            HEIGHT -> "height"
            CROWN -> "crown"
            DISTANCE -> "distance"
            SAMPLING_PLOT -> "samplingPlot"
        }

    companion object {
        fun fromRaw(s: String) = when (s) {
            "dbh" -> DBH
            "height" -> HEIGHT
            "crown" -> CROWN
            "distance" -> DISTANCE
            "samplingPlot" -> SAMPLING_PLOT
            else -> DBH
        }
    }
}

/// The vocabulary of [QuickMeasureEntry.truthSource]. One case, because there
/// is exactly one way a truth arrives that is not "the cruiser typed it here";
/// a second case for "typed" would have to be back-stamped onto a corpus that
/// never recorded it, which is a claim about data we do not have.
/// Raw string byte-identical to the iOS `QuickMeasureEntry.TruthSource`.
enum class TruthSource(val raw: String) {
    /// Recovered from a raw-capture manifest by TruthBackfill and attached to
    /// this reading by (kind, tree, plot, nearest time).
    CAPTURE("capture");

    companion object {
        fun fromRaw(s: String?) = entries.firstOrNull { it.raw == s }
    }
}

enum class StemPosition(val raw: String, val displayName: String) {
    DBH("dbh", "DBH"),
    BUTT("butt", "Butt"),
    UPPER_STEM("upperStem", "Upper stem"),
    STUMP("stump", "Stump");

    companion object {
        fun fromRaw(s: String?) = entries.firstOrNull { it.raw == s }
    }
}

data class QuickMeasureEntry(
    val id: UUID = UUID.randomUUID(),
    val kind: MeasureKind,
    val value: Double,
    val secondaryValue: Double? = null,
    val sigma: Double? = null,
    val confidenceRaw: String,
    val method: String,
    val createdAt: Long = System.currentTimeMillis(),
    val treeNumber: Int? = null,
    /// Cruiser-typed name for the tree ("Plot3-T07"), chosen in the measure
    /// chooser before the scan. Null for every reading taken before naming
    /// existed and for any tree left unnamed — read it through
    /// [displayTreeName], never raw, so those readings keep looking the way
    /// they always have.
    val treeName: String? = null,
    val plotID: UUID? = null,
    val speciesCode: String? = null,
    val position: StemPosition? = null,
    val damageCodes: List<String> = emptyList(),
    val note: String? = null,
    /// Capture context (map home): GPS fix + AR-view snapshot taken at the
    /// moment the cruiser hit Accept. Optional — older entries have none.
    val latitude: Double? = null,
    val longitude: Double? = null,
    /// Filename inside MeasurePhotoStore's directory (not a full path).
    val photoPath: String? = null,
    /// How the DBH edges were found: "auto" (automatic edge-finding),
    /// "manual" (ADJUST edge-bracket) or "typed" (a number the cruiser
    /// entered by hand). Null for non-DBH kinds and for entries recorded
    /// before the column existed. Same vocabulary as iOS.
    val captureMode: String? = null,
    /// Hand-measured GROUND TRUTH for this reading — the tape diameter or
    /// the pole/clinometer height the accuracy study compares against.
    /// Same unit as [value] (cm for diameter, m otherwise).
    ///
    /// It lives ON the reading, not in the raw-capture manifest keyed on
    /// tree number: a truth typed against a tree number alone could not say
    /// WHICH of that tree's readings it was measured for, and it vanished
    /// with the raw-capture bundle. Null = no truth was ever entered —
    /// never zero, which would read as a measured 0 cm.
    val truth: Double? = null,
    /// How [truth] came to sit on this reading — the same shape of provenance
    /// stamp as [captureMode] and [positionSource], and read through
    /// [truthRecordedSource] for the same reason.
    ///
    /// Null is not an unknown: until the recovery pass existed, the ONLY
    /// writer of [truth] was a number the cruiser typed against this reading
    /// (scan screen or field log), so an unlabelled truth IS a typed one.
    /// [TruthSource.CAPTURE] marks a truth that was typed for a raw CAPTURE
    /// and attached to this reading by inference — same tree, same kind, same
    /// plot, nearest timestamp. It is the cruiser's own tape number either
    /// way, but the analysis must be able to separate "typed here" from
    /// "matched here", because the second one carries a matching assumption
    /// the first does not.
    val truthSource: String? = null,
    /// Where this reading's coordinate came from — a [PositionSource] raw
    /// string, the SAME vocabulary the cruise Plot records ("gpsSingle",
    /// "manual", …), so one word means one thing in both worlds.
    ///
    /// Null on every row written before this column existed. That is not an
    /// unknown: until the field log let a coordinate be typed, the
    /// Accept-time GPS snapshot was the ONLY thing that ever wrote
    /// latitude/longitude, so an unlabelled located row is a device fix.
    /// [positionRecordedSource] states that rule once and everything that
    /// displays or exports the source reads it through there — a typed
    /// coordinate that exported as if the GPS produced it would be the same
    /// class of error as a typed diameter carrying a sensor sigma.
    val positionSource: String? = null,
) {
    /// How this reading's tree is labelled anywhere a tree is named — the
    /// cruiser's name when there is one, else the bare "#12" the log and the
    /// map pins have always shown. Null only for a reading that belongs to no
    /// tree at all (a sampling plot, a loose distance).
    val displayTreeName: String?
        get() = treeName ?: treeNumber?.let { "#$it" }

    /// cm for diameter, m elsewhere.
    val valueUnit: String
        get() = if (kind == MeasureKind.DBH) "cm" else "m"

    /// mm for diameter sigma, m elsewhere.
    val sigmaUnit: String
        get() = if (kind == MeasureKind.DBH) "mm" else "m"

    /// Unit for secondaryValue: crown height m, plot area m^2.
    val secondaryValueUnit: String
        get() = when (kind) {
            MeasureKind.CROWN -> "m"
            MeasureKind.SAMPLING_PLOT -> "m\u00B2"
            else -> ""
        }

    // MARK: Hand-typed readings

    /// The `method` raw a hand-typed reading of this kind carries — the same
    /// manual-entry arms the scan screens stamp on a typed diameter/height.
    /// The compound kinds have no typed arm, so they keep what produced them.
    val typedMethodRaw: String
        get() = when (kind) {
            MeasureKind.DBH -> "manualVisual"
            MeasureKind.HEIGHT -> "manualEntry"
            else -> method
        }

    /// This reading re-stated with a value the cruiser TYPED.
    ///
    /// A typed number has no geometry behind it, so it cannot keep the
    /// sensor's sigma or its edge provenance: sigma is dropped (never
    /// zeroed — a 0 band claims a perfect measurement and poisons the sigma
    /// column the accuracy work reads) and captureMode becomes "typed", the
    /// vocabulary the scan screens already stamp. createdAt is kept:
    /// correcting a number is not a second visit to the tree.
    fun typedValue(newValue: Double): QuickMeasureEntry =
        copy(value = newValue, sigma = null,
             method = typedMethodRaw, captureMode = "typed")

    /// This reading re-homed into another plot — the ONLY field a move
    /// touches.
    ///
    /// Named rather than left as a bare `copy(plotID = …)` at each call site,
    /// and matching the iOS `inPlot`: re-filing a reading must change nothing
    /// ABOUT the reading, and a move that quietly lost a sigma or a tape
    /// number would cost the accuracy study the observation, not just its
    /// grouping. `copy` carries everything else across by construction, which
    /// is exactly the guarantee wanted here.
    fun inPlot(newPlotID: UUID?): QuickMeasureEntry = copy(plotID = newPlotID)

    /// The source to SHOW and to EXPORT for this reading's truth: the stored
    /// one, or "typed" for a truth that predates the column (see
    /// [truthSource]). Null only when there is no truth.
    val truthRecordedSource: String?
        get() = if (truth == null) null else (truthSource ?: "typed")

    /// This reading with its ground truth set (or cleared with null).
    /// Nothing else moves — a truth is an observation ABOUT the reading, not
    /// a change to it.
    ///
    /// [source] travels WITH the value because the two are one fact: a truth
    /// and how it got here. It defaults to null, which is what every
    /// hand-typed path means; only the recovery pass passes
    /// [TruthSource.CAPTURE]. Clearing drops the source too — an absent truth
    /// has no provenance, exactly as [settingPosition] treats an absent
    /// coordinate.
    fun settingTruth(newTruth: Double?, source: String? = null): QuickMeasureEntry =
        copy(truth = newTruth, truthSource = if (newTruth == null) null else source)

    /// The source to SHOW and to EXPORT for this reading's coordinate: the
    /// stored one, or "gpsSingle" for a located row that predates the column
    /// (see [positionSource]). Null only when there is no coordinate.
    val positionRecordedSource: String?
        get() = if (latitude == null || longitude == null) {
            null
        } else {
            positionSource ?: PositionSource.GPS_SINGLE.raw
        }

    /// This reading with its coordinate replaced by one the cruiser TYPED,
    /// or cleared back to no position at all (null, null).
    ///
    /// Nothing else moves — where a reading was taken is an observation
    /// ABOUT it, not a change to the measurement — but the source is
    /// restamped "manual" so no later reader can mistake a hand-entered
    /// coordinate for a device fix. Clearing drops the source too: an absent
    /// position has no provenance to record.
    fun settingPosition(newLat: Double?, newLon: Double?): QuickMeasureEntry {
        val hasFix = newLat != null && newLon != null
        return copy(
            latitude = if (hasFix) newLat else null,
            longitude = if (hasFix) newLon else null,
            positionSource = if (hasFix) PositionSource.MANUAL.raw else null,
        )
    }

    companion object {
        /// A brand-new reading the cruiser typed for a tree the sensors
        /// never measured. No sigma, no GPS fix, no photo — none of those
        /// exist for a number that came off a tape. "yellow" is the tier the
        /// scan screens' own manual-entry path stamps: usable, unverified.
        ///
        /// [speciesCode] rides along because a tree entered by hand is named
        /// and identified in the same breath as it is measured; it is an
        /// observation about the stem, not about the number, so carrying it
        /// changes nothing about how the reading is stamped. It defaults to
        /// null so the row editor's "complete this half-measured tree" path
        /// is untouched.
        fun typed(
            kind: MeasureKind,
            value: Double,
            treeNumber: Int?,
            treeName: String? = null,
            plotID: java.util.UUID?,
            speciesCode: String? = null,
            truth: Double? = null,
        ) = QuickMeasureEntry(
            kind = kind,
            value = value,
            sigma = null,
            confidenceRaw = "yellow",
            method = if (kind == MeasureKind.HEIGHT) "manualEntry" else "manualVisual",
            treeNumber = treeNumber,
            treeName = treeName,
            plotID = plotID,
            speciesCode = speciesCode,
            position = if (kind == MeasureKind.DBH) StemPosition.DBH else null,
            captureMode = "typed",
            truth = truth,
        )
    }
}

data class QuickMeasurePlot(
    val id: UUID = UUID.randomUUID(),
    var name: String,
    var unitName: String = "",
    var acres: Double? = null,
    var typeRaw: String = "fixed",
    var baf: Double? = null,
    var radiusFt: Double? = null,
    var parentPlotID: UUID? = null,
    var nestedKind: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val isDefault: Boolean = false,
) {
    val isNested: Boolean get() = parentPlotID != null
}
