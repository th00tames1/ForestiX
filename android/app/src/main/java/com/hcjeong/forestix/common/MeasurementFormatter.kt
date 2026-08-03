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

    /// The same number [diameter] prints, WITHOUT the suffix — for the stat
    /// cells and table columns that set the value and its unit in two
    /// different type styles. It exists so those call sites do not each write
    /// their own / 2.54; one of them forgetting is a tenth-of-an-inch stand
    /// reported as a centimetre one.
    fun diameterValue(cm: Double, system: UnitSystem): Double =
        if (system == UnitSystem.METRIC) cm else Units.cmToInches(cm)

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

    /// A distance the cruiser is about to WALK, at pacing precision: whole
    /// metres or whole feet until the number gets long, then kilometres or
    /// miles.
    ///
    /// Deliberately NOT [distance]. That one renders a MEASUREMENT —
    /// centimetres under a metre, two decimals above — and a walking
    /// instruction has neither that precision nor any use for it; "142.37 m to
    /// the plot" is three digits of noise on a number the cruiser will pace to
    /// within a few strides.
    ///
    /// The rollovers are round in each system rather than converted from one
    /// another: metric turns over just under the kilometre (so the longest
    /// metre reading is "994 m" and never "1000 m"), imperial at the mile. A
    /// US cruiser reads "3200 ft" without hesitating — feet are the pacing
    /// unit — so breaking earlier would trade a readable number for an
    /// unreadable "0.61 mi". iOS prints the identical string.
    fun navDistance(m: Double, system: UnitSystem): String = when (system) {
        UnitSystem.METRIC ->
            if (m < 995) "${fixedDecimals(m, 0)} m"
            else "${fixedDecimals(m / 1000.0, 1)} km"
        UnitSystem.IMPERIAL -> {
            val feet = Units.metersToFeet(m)
            if (feet < 5280) "${fixedDecimals(feet, 0)} ft"
            else "${fixedDecimals(feet / 5280.0, 1)} mi"
        }
    }

    // MARK: - Guidance distances (the sentences that say where to stand)

    /// A distance quoted inside an INSTRUCTION — "stand within 4 m / 13 ft of
    /// the trunk", "you walked back more than 30 m / 100 ft".
    ///
    /// THE GATE BEHIND THE SENTENCE DOES NOT MOVE. Those limits are physical —
    /// the depth camera's usable range, the geometry of a tangent height — and
    /// a cruiser flipping a display toggle must not change which captures the
    /// app accepts or how it grades them. What changes is only how the limit is
    /// SAID.
    ///
    /// Rounded so it reads like an instruction rather than a conversion: whole
    /// feet close in, the nearest five further out (30 m becomes "100 ft", not
    /// "98 ft"). A cruiser standing at 98 ft where the sentence said 100 is
    /// inside a gate that was never sharp to begin with — these numbers are
    /// advice, and advice with two decimals on it stops being read.
    fun guidanceDistance(m: Double, system: UnitSystem): String =
        "${guidanceNumber(m, system)} ${if (system == UnitSystem.METRIC) "m" else "ft"}"

    /// The same, for a RANGE — "0.5–3 m" / "2–10 ft". One unit suffix on the
    /// pair, because it is one instruction.
    fun guidanceRange(fromM: Double, toM: Double, system: UnitSystem): String =
        "${guidanceNumber(fromM, system)}–${guidanceDistance(toM, system)}"

    private fun guidanceNumber(m: Double, system: UnitSystem): String = when (system) {
        // A tenth only where there is one — "3 m", not "3.0 m".
        UnitSystem.METRIC ->
            if (m == kotlin.math.round(m)) fixedDecimals(m, 0) else fixedDecimals(m, 1)
        UnitSystem.IMPERIAL -> {
            val feet = Units.metersToFeet(m)
            val rounded =
                if (feet < 20) kotlin.math.round(feet)
                else kotlin.math.round(feet / 5.0) * 5.0
            fixedDecimals(rounded, 0)
        }
    }

    /// A stored elevation (metres) in the unit the cruiser reads — feet in
    /// imperial, metres in metric.
    ///
    /// Elevation is the only length in this app the cruiser TYPES rather than
    /// measures, so its field needs both directions of the conversion, not just
    /// a rendered string; [elevationMetres] is the way back. Keeping the pair
    /// here keeps the ft/m factor in the one file that owns it — the
    /// alternative was a fourth hand-written 3.28084 out in a screen.
    ///
    /// Elevation renders and is typed in WHOLE units. The number comes off a
    /// map, an altimeter or a phone's barometric fix, none of which are good to
    /// a decimetre, and a cruiser typing "412" should read back "412".
    fun elevationDisplay(m: Double, system: UnitSystem): Double =
        if (system == UnitSystem.METRIC) m else m * 3.28084

    /// The inverse of [elevationDisplay] — what a typed elevation stores as.
    fun elevationMetres(display: Double, system: UnitSystem): Double =
        if (system == UnitSystem.METRIC) display else display / 3.28084

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

    // MARK: - Basal-area factor (prism)

    /// The unit a BAF is WRITTEN in for this cruiser: "ft²/ac" in the US
    /// convention, "m²/ha" in the metric one. A BAF box with no unit on it is
    /// how a metric cruiser types 4 into a field the engine reads as 20.
    fun bafUnit(system: UnitSystem) = if (system == UnitSystem.METRIC) "m²/ha" else "ft²/ac"

    /// A stored BAF (always ft²/ac — see `CruiseDesign.baf`) expressed in the
    /// unit this cruiser types in, and the way back. The pair exists for the
    /// same reason [elevationDisplay]/[elevationMetres] does: a BAF is TYPED,
    /// so the field needs both directions, and both must use one constant.
    /// The arithmetic itself stays in [Units] — a BAF is a basal area per unit
    /// land area and converts exactly like one.
    fun bafDisplay(stored: Double, system: UnitSystem): Double =
        if (system == UnitSystem.METRIC) Units.baPerAcreToBaPerHa(stored) else stored

    /// The inverse of [bafDisplay] — what a typed BAF stores as (ft²/ac).
    fun bafStored(display: Double, system: UnitSystem): Double =
        if (system == UnitSystem.METRIC) Units.baPerHaToBaPerAcre(display) else display

    /// The BAF a fresh design starts from: the US convention's 20 ft²/ac, or
    /// the metric convention's round 4 m²/ha (≈17 ft²/ac — the nearest whole
    /// prism a metric cruiser actually carries, not a conversion of 20).
    fun defaultBAFStored(system: UnitSystem): Double =
        if (system == UnitSystem.METRIC) Units.baPerHaToBaPerAcre(4.0) else 20.0

    // MARK: - Basal area per unit land area

    /// A stand's basal area per unit land area, in the numerator the cruiser's
    /// basis is written with.
    ///
    /// The inventory engine reports basal area as SQUARE METRES PER ACRE — the
    /// acre is the canonical stored basis, the square metre is the storage
    /// rule. Screens used to convert only the DENOMINATOR (× the per-hectare
    /// factor) and print the numerator as a literal "m²", so an imperial cruise
    /// read "11.5 m²/ac": a real number in a unit no cruise sheet uses, and
    /// 10.76× away from the ft²/ac the SAME app prints for the SAME stand on
    /// the quick-measure card. Both halves of the fraction convert here, or
    /// neither is right.
    fun basalAreaDensity(m2PerAcre: Double, unit: AreaUnit): Double =
        m2PerAcre * basalAreaDensityFactor(unit)

    /// The same conversion as a bare multiplier, for the confidence-interval
    /// rows: a mean and its half-width have to be scaled by ONE number or the
    /// band stops belonging to the value it brackets.
    fun basalAreaDensityFactor(unit: AreaUnit): Double =
        if (unit == AreaUnit.HECTARE) Units.ACRES_PER_HECTARE
        else Units.squareMetersToSquareFeet(1.0)

    /// The numerator alone — "m²" or "ft²" — for rows that already carry the
    /// "/ha" / "/ac" suffix in their heading.
    fun basalAreaNumeratorUnit(unit: AreaUnit): String =
        if (unit == AreaUnit.HECTARE) "m²" else "ft²"

    /// The whole label: "m²/ha" or "ft²/ac".
    fun basalAreaDensityUnit(unit: AreaUnit): String =
        unit.densityLabel(basalAreaNumeratorUnit(unit))

    // MARK: - Plot geometry (the sampling ring itself)

    /// The AREA INSIDE ONE PLOT, in the unit that convention sizes plots with:
    /// square metres for a metric cruiser (a "400 m² plot"), acres for a US one
    /// (a "1/10-acre plot").
    ///
    /// This is the one area readout that does NOT go through [AreaUnit]. That
    /// type governs the basis a per-area DENSITY rests on, and putting a plot
    /// through it gives a metric cruiser "0.0402 ha" — three leading zeros for
    /// a number they already know as 402 m². Stands are drawn in hectares and
    /// acres (BoundaryDrawScreen); plots are counted in square metres and
    /// acres. Different quantity, different convention.
    fun plotArea(m2: Double, system: UnitSystem): String = when (system) {
        UnitSystem.METRIC -> "${fixedDecimals(m2, 1)} m²"
        UnitSystem.IMPERIAL -> "${fixedDecimals(Units.squareMetersToAcres(m2), 3)} ac"
    }

    /// The bounds of the AR plot-radius slider, in the unit the cruiser drags
    /// in, and the step it lands on.
    ///
    /// The imperial range is round FEET, not a conversion of the metric one. A
    /// slider stepped in half METRES cannot be stopped on a round foot at all,
    /// so a US cruiser laying the 37.2 ft radius that makes a tenth of an acre
    /// had to settle for whatever half-metre was nearest. Half a foot is the
    /// same "half a unit" granularity the metric slider has always had, and it
    /// reaches every plot size in the US table. The two ranges cover the same
    /// ground — 30 m is 98 ft — so neither system inherits the other's
    /// fractions.
    fun plotRadiusSliderRange(system: UnitSystem): ClosedFloatingPointRange<Float> =
        if (system == UnitSystem.METRIC) 1f..30f else 3f..100f

    fun plotRadiusSliderStep(system: UnitSystem): Double =
        if (system == UnitSystem.METRIC) 0.5 else 0.5

    /// The number of discrete stops a Compose Slider needs to realise that
    /// step over that range — Compose counts the stops BETWEEN the ends, so it
    /// is one fewer than the number of reachable values.
    fun plotRadiusSliderSteps(system: UnitSystem): Int {
        val range = plotRadiusSliderRange(system)
        val span = (range.endInclusive - range.start).toDouble()
        return (span / plotRadiusSliderStep(system)).toInt() - 1
    }

    /// A stored plot radius (metres) in the unit the slider drags in, and the
    /// way back — the same bidirectional pair [elevationDisplay]/
    /// [elevationMetres] exists for, and for the same reason: the slider WRITES
    /// as well as reads, so a one-way format would store feet as metres.
    fun plotRadiusDisplay(m: Double, system: UnitSystem): Double =
        if (system == UnitSystem.METRIC) m else Units.metersToFeet(m)

    /// The inverse of [plotRadiusDisplay] — what a dragged radius stores as.
    fun plotRadiusMetres(display: Double, system: UnitSystem): Double =
        if (system == UnitSystem.METRIC) display else Units.feetToMeters(display)

    /// A plot length for a cruiser to read at arm's length: one decimal, the
    /// active unit ("7.2 m" / "23.6 ft").
    ///
    /// Coarser than [distance] on purpose. A plot radius is a number the
    /// cruiser CHOSE, not one the phone read, so it has no centimetre to
    /// report and the tape-measure switch below a metre would be nonsense on
    /// it. `plotLengthLabel` in the map layer is this function — one rounding
    /// rule, so the banner, the mini-map, the AR slider and the peek cannot
    /// print one ring three ways.
    fun plotLength(m: Double, system: UnitSystem): String =
        "${fixedDecimals(plotRadiusDisplay(m, system), 1)} " +
            if (system == UnitSystem.METRIC) "m" else "ft"

    /// A recorded sampling ring the way the quick-measure list states it:
    /// the radius the cruiser set and the ground it encloses.
    fun samplingPlotSummary(radiusM: Double, areaM2: Double, system: UnitSystem): String =
        "${plotLength(radiusM, system)} radius · ${plotArea(areaM2, system)}"

    // MARK: - Stand-area radius (the circular stratum)

    /// A circular AREA's radius in the unit the cruiser draws in, and the
    /// way back — the same bidirectional pair [plotRadiusDisplay]/
    /// [plotRadiusMetres] and [elevationDisplay]/[elevationMetres] exist for,
    /// and for the same reason: the field WRITES as well as reads, so a
    /// one-way format would store feet as metres.
    ///
    /// Its own pair rather than the plot one, because a plot radius and a
    /// stand radius are different quantities that happen to share a
    /// conversion: the plot pair is tied to the AR slider's range and step
    /// (1–30 m / 3–100 ft), and a stratum is drawn at hundreds of metres.
    /// Sharing them would put a stand's radius under a slider's bounds the
    /// first time someone tidied the two together.
    fun areaRadiusDisplay(m: Double, system: UnitSystem): Double =
        if (system == UnitSystem.METRIC) m else Units.metersToFeet(m)

    /// The inverse of [areaRadiusDisplay] — what a typed radius stores as.
    fun areaRadiusMetres(display: Double, system: UnitSystem): Double =
        if (system == UnitSystem.METRIC) display else Units.feetToMeters(display)

    /// The read-only echo beside the radius field: one decimal in the
    /// cruiser's unit ("50.0 m" / "164.0 ft"). One decimal because this is a
    /// number the cruiser CHOSE, not one the phone read — the same rounding
    /// rule, and the same reason, as [plotLength].
    fun areaRadius(m: Double, system: UnitSystem): String =
        "${fixedDecimals(areaRadiusDisplay(m, system), 1)} " +
            if (system == UnitSystem.METRIC) "m" else "ft"

    /// The unit suffix the radius field is labelled with.
    fun areaRadiusUnit(system: UnitSystem): String =
        if (system == UnitSystem.METRIC) "m" else "ft"

    // MARK: - Crown

    /// A crown's two spans, stored in metres, as one reading.
    ///
    /// ONE unit suffix for the pair: a crown is a single measurement read on
    /// two axes, and "4.2 m × 5.1 m" reads as two separate measurements of two
    /// separate things.
    /// ONE crown span on its own, for the scan screen's "wide · tall" line.
    /// Two decimals where [crownSpread] gives one: this is read at the moment
    /// of measurement, while the cruiser is still checking that the tap landed
    /// on the canopy edge, and the second decimal is what says it moved.
    fun crownSpan(m: Double, system: UnitSystem): String = when (system) {
        UnitSystem.METRIC -> "${fixedDecimals(m, 2)} m"
        UnitSystem.IMPERIAL -> "${fixedDecimals(Units.metersToFeet(m), 2)} ft"
    }

    fun crownSpread(aM: Double, bM: Double, system: UnitSystem): String = when (system) {
        UnitSystem.METRIC ->
            "${fixedDecimals(aM, 1)} × ${fixedDecimals(bM, 1)} m"
        UnitSystem.IMPERIAL ->
            "${fixedDecimals(Units.metersToFeet(aM), 1)} × " +
                "${fixedDecimals(Units.metersToFeet(bM), 1)} ft"
    }
}
