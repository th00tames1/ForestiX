// Unit-aware display formatter — central source of truth for how every
// measurement renders given the cruiser's chosen UnitSystem. Direct port
// of iOS App/MeasurementFormatter.swift. Storage stays metric; the
// display layer converts once on the way to the screen / CSV / share.

package com.hcjeong.forestix.common

import java.util.Locale

object MeasurementFormatter {

    // Diameter — stored cm.
    fun diameter(cm: Double, system: UnitSystem): String = when (system) {
        UnitSystem.METRIC -> String.format(Locale.US, "%.1f cm", cm)
        UnitSystem.IMPERIAL -> String.format(Locale.US, "%.1f in", cm / 2.54)
    }

    /// FLOORED at the smallest value each precision can print, so neither
    /// branch can render "±0.0 mm" / "±0.00 in". A zero band claims a perfect
    /// measurement — the one thing an uncertainty readout must never say — and
    /// the imperial branch rounds anything under 0.127 mm to zero, which the
    /// shipped depth-noise default can reach on a wide arc. Rounding UP to the
    /// floor overstates the band slightly, which is the safe direction.
    /// Matches the iOS sibling.
    fun diameterSigma(mm: Double, system: UnitSystem): String = when (system) {
        UnitSystem.METRIC -> String.format(Locale.US, "±%.1f mm", maxOf(mm, 0.1))
        UnitSystem.IMPERIAL -> String.format(Locale.US, "±%.2f in", maxOf(mm / 25.4, 0.01))
    }

    // Height — stored m.
    //
    /// TWO decimals, field-requested. One was a rounding coarser than the
    /// measurement: a walk-off tangent fit resolves well inside a decimetre
    /// on a clean sightline, and the validation study compares these numbers
    /// against a hand-measured truth typed to the centimetre. At one decimal
    /// two heights 6 cm apart printed the same string, which made a real
    /// difference between algorithms invisible on the screen that shows it.
    /// The ± band beside it ([heightSigma]) is what says how much of the
    /// second decimal to believe. iOS prints the identical string.
    fun height(m: Double, system: UnitSystem): String = when (system) {
        UnitSystem.METRIC -> String.format(Locale.US, "%.1f m", m)
        UnitSystem.IMPERIAL -> String.format(Locale.US, "%.1f ft", m * 3.28084)
    }

    /// Renders a height precision sigma (stored in metres).
    ///   • metric  → "±0.4 m"  (and "±0.04 m", never "±0.0 m")
    ///   • imperial → "±1.3 ft"
    ///
    /// Delegates to [UncertaintyBand] — the one place a ± band is rounded,
    /// shared with the estimators. This used to be its own "%.1f", so the
    /// field log's uncertainty column and the map's quick-measure detail
    /// printed "±0.0 m" for any reading under 5 cm of σ: precisely the
    /// readings the app is least sure about, wearing a claim of perfection.
    fun heightSigma(m: Double, system: UnitSystem): String =
        UncertaintyBand.text(m, system)

    // Distance / generic length — stored m. Metric readings under a metre
    // render in whole centimetres (field-glance precision).
    fun distance(m: Double, system: UnitSystem): String = when (system) {
        UnitSystem.METRIC ->
            if (m < 1) String.format(Locale.US, "%.0f cm", m * 100)
            else String.format(Locale.US, "%.2f m", m)
        UnitSystem.IMPERIAL -> String.format(Locale.US, "%.1f ft", m * 3.28084)
    }

    fun diameterUnit(system: UnitSystem) = if (system == UnitSystem.METRIC) "cm" else "in"
    fun heightUnit(system: UnitSystem) = if (system == UnitSystem.METRIC) "m" else "ft"
}
