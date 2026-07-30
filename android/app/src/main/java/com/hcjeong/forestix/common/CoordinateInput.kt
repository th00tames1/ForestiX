// One rule for reading a coordinate a cruiser TYPED.
//
// The app has always been able to show where a reading was taken and never
// able to change it: a fix that never arrived, or arrived off the tree,
// stayed on the record forever. This is the parser behind the field log's
// editable Position row.
//
// IT ACCEPTS EXACTLY WHAT THE APP PRINTS — "44.56417, -123.28556", decimal
// degrees, latitude first, separated by a comma. Nothing else. A cruiser who
// types something this cannot read gets told so and the stored coordinate is
// left alone; guessing at "44 33 51 N" and storing the guess would put a
// tree somewhere nobody chose, which is worse than refusing.
//
// Ported 1:1 from / to iOS `Common/CoordinateInput.swift` — a cruise split
// across two phones must accept and refuse the same strings.

package com.hcjeong.forestix.common

import java.util.Locale

object CoordinateInput {

    /// What the text in the field means.
    sealed interface Result {
        /// A usable coordinate.
        data class Coordinate(val latitude: Double, val longitude: Double) : Result

        /// The field is empty — the cruiser is CLEARING the position, which
        /// is a legitimate answer ("this reading has no position") and not a
        /// failure to parse.
        data object Cleared : Result

        /// Unusable. Carries the sentence to put on screen.
        data class Refused(val message: String) : Result
    }

    /// Why a coordinate was refused. Byte-identical on both platforms.
    object Refusal {
        const val FORMAT =
            "Type the coordinate the way the app shows it: latitude, longitude " +
                "— for example 44.56417, -123.28556."
        const val LATITUDE_RANGE = "Latitude must be between -90 and 90."
        const val LONGITUDE_RANGE = "Longitude must be between -180 and 180."
    }

    /// How a stored coordinate is printed — the one format, so what the
    /// cruiser reads is exactly what this parser will take back.
    fun text(latitude: Double, longitude: Double): String =
        String.format(Locale.US, "%.5f, %.5f", latitude, longitude)

    fun parse(raw: String): Result {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return Result.Cleared

        // Exactly one comma. Two would mean the cruiser typed a decimal
        // comma ("44,565, -123,285"), and there is no way to tell which of
        // those commas separates the pair — so it is refused rather than
        // resolved by a coin toss.
        val parts = trimmed.split(",")
        if (parts.size != 2) return Result.Refused(Refusal.FORMAT)

        val lat = decimalDegrees(parts[0]) ?: return Result.Refused(Refusal.FORMAT)
        val lon = decimalDegrees(parts[1]) ?: return Result.Refused(Refusal.FORMAT)

        if (lat < -90.0 || lat > 90.0) return Result.Refused(Refusal.LATITUDE_RANGE)
        if (lon < -180.0 || lon > 180.0) return Result.Refused(Refusal.LONGITUDE_RANGE)
        return Result.Coordinate(lat, lon)
    }

    /// One signed decimal number, and nothing else. Deliberately hand-rolled
    /// rather than handed to a number formatter: a locale-aware parse would
    /// read "44.5" as 445 under a decimal-comma locale, which is a 100 km
    /// error that looks like a valid coordinate.
    private fun decimalDegrees(raw: String): Double? {
        val field = raw.trim()
        if (field.isEmpty()) return null
        var digits = 0
        var dots = 0
        field.forEachIndexed { index, ch ->
            when {
                ch == '-' || ch == '+' -> if (index != 0) return null
                ch == '.' -> {
                    dots += 1
                    if (dots > 1) return null
                }
                ch in '0'..'9' -> digits += 1
                else -> return null
            }
        }
        if (digits == 0) return null
        return field.toDoubleOrNull()
    }
}
