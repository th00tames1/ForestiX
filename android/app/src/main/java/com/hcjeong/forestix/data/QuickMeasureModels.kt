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
) {
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
