// THE ± BAND — one implementation, for every surface that prints one.
//
// A band is the only place the app tells a cruiser how far a number could
// be out, so two rules hold everywhere it is printed:
//
//   1. It is NEVER "±0.0". A stored σ_H of 0.04 m is genuinely reachable
//      now that a red fit is acceptable and gets committed, and at one
//      decimal place it printed "±0.0 m" — a claim of perfect precision on
//      the app's worst readings, in the same breath as the caution that
//      said the reading was loose. So: two decimals below 0.1, and a 0.01
//      floor on the PRINTED value.
//   2. It is in the cruiser's units. A band that stayed in metres inside
//      an otherwise-converted sentence told an imperial cruiser a number
//      they could not compare with the height printed next to it.
//
// The stored σ is untouched by any of this — it is formatting, not a
// change to the propagation or to any threshold. Lives in common/ so the
// estimators (sensors) and the screens (ui) share one implementation
// rather than each rounding their own way.
//
// Byte-identical to the iOS sibling (Models/UncertaintyBand.swift).

package com.hcjeong.forestix.common

import java.util.Locale

object UncertaintyBand {

    /// Floor, in the display unit, below which a band would read as zero.
    private const val PRINT_FLOOR = 0.01

    /// A ± band for a value stored in metres, rendered in `system`'s unit.
    ///   • metric   → "±0.04 m", "±0.4 m"
    ///   • imperial → "±0.13 ft", "±1.3 ft"
    fun text(metres: Double, system: UnitSystem): String {
        val value = if (system == UnitSystem.METRIC) metres else metres * 3.28084
        val unit = if (system == UnitSystem.METRIC) "m" else "ft"
        val shown = maxOf(value, PRINT_FLOOR)
        val digits =
            if (shown < 0.1) String.format(Locale.US, "%.2f", shown)
            else String.format(Locale.US, "%.1f", shown)
        return "±$digits $unit"
    }
}
