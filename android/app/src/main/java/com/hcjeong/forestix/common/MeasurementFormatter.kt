// Unit-aware display formatter — central source of truth for how every
// measurement renders given the cruiser's chosen UnitSystem. Direct port
// of iOS App/MeasurementFormatter.swift. Storage stays metric; the
// display layer converts once on the way to the screen / CSV / share.

package com.hcjeong.forestix.common

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/// A number at a fixed number of decimals, rounded the way iOS rounds it.
///
/// iOS renders every measurement through `String(format:)`, which rounds
/// half-to-EVEN on the value's exact binary expansion. Kotlin's `String.format`
/// rounds half-UP, so a stored 42.25 cm printed "42.2" there and "42.3" here:
/// two platforms, two strings for one tree, which the house rule forbids.
/// `BigDecimal(double)` IS that exact binary expansion and HALF_EVEN is that
/// same rule — checked against iOS output over 5 200 values across the stored
/// DBH and height ranges at 0, 1 and 2 decimals: zero differences, where plain
/// `String.format` differed on 334 of them.
///
/// Non-finite values keep the old path: `BigDecimal` throws on NaN, and a
/// crash is worse than the "NaN"/"nan" spelling difference it would avoid.
internal fun fixedDecimals(value: Double, digits: Int): String =
    if (!value.isFinite()) String.format(Locale.US, "%.${digits}f", value)
    else BigDecimal(value).setScale(digits, RoundingMode.HALF_EVEN).toPlainString()

object MeasurementFormatter {

    // Diameter — stored cm.
    fun diameter(cm: Double, system: UnitSystem): String = when (system) {
        UnitSystem.METRIC -> "${fixedDecimals(cm, 1)} cm"
        UnitSystem.IMPERIAL -> "${fixedDecimals(cm / 2.54, 1)} in"
    }

    /// FLOORED at the smallest value each precision can print, so neither
    /// branch can render "±0.0 mm" / "±0.00 in". A zero band claims a perfect
    /// measurement — the one thing an uncertainty readout must never say — and
    /// the imperial branch rounds anything under 0.127 mm to zero, which the
    /// shipped depth-noise default can reach on a wide arc. Rounding UP to the
    /// floor overstates the band slightly, which is the safe direction.
    /// Matches the iOS sibling.
    fun diameterSigma(mm: Double, system: UnitSystem): String = when (system) {
        UnitSystem.METRIC -> "±${fixedDecimals(maxOf(mm, 0.1), 1)} mm"
        UnitSystem.IMPERIAL -> "±${fixedDecimals(maxOf(mm / 25.4, 0.01), 2)} in"
    }

    // Height — stored m.
    //
    /// ONE decimal. Two decimals were tried and taken back out: a tangent
    /// height is a difference of two sighted angles, and the σ it carries is
    /// decimetres at cruising range, so a centimetre digit was a precision the
    /// measurement does not have. The ± band beside it ([heightSigma]) is what
    /// says how much of the first decimal to believe. iOS prints the identical
    /// string.
    fun height(m: Double, system: UnitSystem): String = when (system) {
        UnitSystem.METRIC -> "${fixedDecimals(m, 1)} m"
        UnitSystem.IMPERIAL -> "${fixedDecimals(m * 3.28084, 1)} ft"
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
            if (m < 1) "${fixedDecimals(m * 100, 0)} cm"
            else "${fixedDecimals(m, 2)} m"
        UnitSystem.IMPERIAL -> "${fixedDecimals(m * 3.28084, 1)} ft"
    }

    /// The text an EDITABLE numeric field is PREFILLED with, for a value that
    /// came out of storage: the bare number in the unit it is stored in, with
    /// no unit suffix (the row carries that), rounded exactly the way the
    /// read-only surface for that quantity rounds it — one decimal for a
    /// diameter or a height, two for a distance. A form that shows a different
    /// number from the field log is two numbers for one tree.
    ///
    /// A ROUNDED prefill is only safe in a field whose screen refuses to write
    /// it back unedited. `18.27` prefills as "18.3", and saving that text over
    /// the stored value is a silent re-measurement of the tree — the cruiser
    /// opened a form and lost 3 cm. Every caller therefore compares the field's
    /// current text against this prefill and leaves the stored value alone when
    /// they are equal; see [TreeDetailScreen] and the map peek's edit sheet.
    /// Any new caller owes the same guard.
    fun entryText(value: Double, fractionDigits: Int): String =
        fixedDecimals(value, fractionDigits)

    fun diameterUnit(system: UnitSystem) = if (system == UnitSystem.METRIC) "cm" else "in"
    fun heightUnit(system: UnitSystem) = if (system == UnitSystem.METRIC) "m" else "ft"
}
