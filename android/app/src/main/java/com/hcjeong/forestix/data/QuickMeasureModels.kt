// Core measurement records — direct port of the iOS
// QuickMeasureEntry / QuickMeasurePlot structs (App/QuickMeasureHistory.swift).
// Field names, kinds, and unit semantics match 1:1 so CSV / bundle exports
// are byte-comparable with the iOS app.

package com.hcjeong.forestix.data

import com.hcjeong.forestix.common.Units
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
