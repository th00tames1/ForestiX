// GROUND-TRUTH INPUT — the ONE parser every "typed truth" field on either
// platform runs through (scan-screen dev block, raw-capture console).
//
// CROSS-PLATFORM: rules are identical to the iOS sibling.
//   • ',' is accepted as the decimal separator and normalised to '.'.
//     A European/Korean numeric keypad emits ',' and a digits-only filter
//     silently turned "12,5" into 125 — a 10x corrupted ground truth.
//   • Whitespace is trimmed. An empty / unparseable field yields null, which
//     callers MUST treat as "no value typed" — never as zero and never as
//     "clear the stored truth". Clearing a stored truth is always explicit.
//   • Plausibility windows: DBH 1–300 cm, height 1–120 m. A value outside
//     the window is still accepted (it is the operator's measurement) but
//     the caller shows `fieldWarning(...)` next to the field. The window is
//     judged on the metric base and WORDED in the unit the field is typed in.
//   • The UNIT a truth was typed in belongs to the entry, not to the reader.
//     See the [Unit] section below.

package com.hcjeong.forestix.common

object TruthInput {

    // MARK: Parsing

    /// ',' → '.', whitespace trimmed. The Android text fields additionally
    /// run [sanitize] on each keystroke, so what reaches here is already
    /// numeric — the normalisation stays for parity and for pasted text.
    fun normalized(raw: String): String = raw.replace(',', '.').trim()

    /// Keystroke filter for the Android fields: digits plus ONE decimal
    /// separator, with ',' NORMALISED to '.' rather than deleted (the old
    /// filter dropped it and turned "12,5" into "125").
    fun sanitize(raw: String): String {
        val out = StringBuilder(raw.length)
        var haveSeparator = false
        for (c in raw) {
            when {
                c.isDigit() -> out.append(c)
                (c == '.' || c == ',') && !haveSeparator -> {
                    out.append('.')
                    haveSeparator = true
                }
            }
        }
        return out.toString()
    }

    /// Parsed value, or null when the field is empty / not a number.
    /// Locale-independent (parses the normalised string).
    fun parse(raw: String): Double? {
        val s = normalized(raw)
        if (s.isEmpty()) return null
        return s.toDoubleOrNull()
    }

    /// Parsed value that is also usable as a truth (finite and > 0).
    fun parsePositive(raw: String): Double? =
        parse(raw)?.takeIf { it.isFinite() && it > 0.0 }

    /// True when the field holds characters but doesn't parse — the state
    /// that must NEVER overwrite (or silently discard) a stored truth.
    fun isUnparseable(raw: String): Boolean =
        normalized(raw).isNotEmpty() && parsePositive(raw) == null

    // MARK: What is being typed

    /// The quantity a truth field stands for. It picks the unit pair, the
    /// label and the plausibility window in one place, so those three can
    /// never disagree about what the field holds.
    enum class Quantity {
        DIAMETER,   // base cm
        HEIGHT,     // base m
        DISTANCE,   // base m
    }

    // MARK: The unit the operator typed in

    /// The unit a typed truth is IN, recorded with the value (manifest
    /// `truth_unit`, research CSV `truth_unit`).
    ///
    /// The stored number stays in the app's metric base, so after the fact
    /// nothing in an export says whether a 12.5 was typed as centimetres or
    /// converted from inches — and a field labelled in metric while the
    /// cruiser worked in imperial is how a day of analysis was lost. Recording
    /// what was typed makes the reconciliation arithmetic instead of guesswork.
    enum class Unit(val raw: String) {
        CM("cm"),
        INCHES("in"),
        METERS("m"),
        FEET("ft"),
    }

    /// The unit a FRESH entry starts in: the cruiser's ACTIVE system, never a
    /// fixed metric default.
    fun defaultUnit(quantity: Quantity, imperial: Boolean): Unit = when (quantity) {
        Quantity.DIAMETER -> if (imperial) Unit.INCHES else Unit.CM
        Quantity.HEIGHT, Quantity.DISTANCE -> if (imperial) Unit.FEET else Unit.METERS
    }

    /// The other unit for the same quantity — what the per-entry toggle picks.
    fun toggled(unit: Unit): Unit = when (unit) {
        Unit.CM -> Unit.INCHES
        Unit.INCHES -> Unit.CM
        Unit.METERS -> Unit.FEET
        Unit.FEET -> Unit.METERS
    }

    /// A typed value converted to the app's metric base (cm for a diameter,
    /// m for a height or distance). The ONE place that conversion happens —
    /// every truth field routes through here rather than converting at the
    /// call site, because a call site that forgets is silently 2.54x wrong.
    fun toBase(value: Double, unit: Unit): Double = when (unit) {
        Unit.CM, Unit.METERS -> value
        Unit.INCHES -> Units.inchesToCm(value)
        Unit.FEET -> Units.feetToMeters(value)
    }

    /// A stored base value expressed back in [unit] — for putting a value the
    /// app is handing back into the field it was typed in.
    fun fromBase(base: Double, unit: Unit): Double = when (unit) {
        Unit.CM, Unit.METERS -> base
        Unit.INCHES -> Units.cmToInches(base)
        Unit.FEET -> Units.metersToFeet(base)
    }

    /// Parse and convert in one step: the base value a typed field means, or
    /// null when nothing usable was typed.
    fun parsePositiveBase(raw: String, unit: Unit): Double? =
        parsePositive(raw)?.let { toBase(it, unit) }

    /// Compact label for the scan panels' inline field — carries the unit, so
    /// what is typed and what is read can never disagree.
    fun fieldLabel(quantity: Quantity, unit: Unit): String = when (quantity) {
        Quantity.DIAMETER -> "True Ø (${unit.raw})"
        Quantity.HEIGHT -> "True H (${unit.raw})"
        Quantity.DISTANCE -> "True (${unit.raw})"
    }

    /// Longer label for the raw-capture console's field placeholder.
    fun promptLabel(quantity: Quantity, unit: Unit): String = when (quantity) {
        Quantity.DIAMETER -> "True Ø (${unit.raw})"
        Quantity.HEIGHT -> "True height (${unit.raw})"
        Quantity.DISTANCE -> "True distance (${unit.raw})"
    }

    /// A value put BACK into a truth field, rendered in the unit it was typed
    /// in — used when a queued truth could not be stored (its bundle write
    /// failed) and the operator must see the number again instead of losing
    /// it. Trailing zeros are trimmed so "12.5" round-trips as typed.
    fun text(base: Double, unit: Unit): String = text(fromBase(base, unit))

    /// The same rendering for a value already in the unit it will be shown in.
    fun text(value: Double): String {
        val s = String.format(java.util.Locale.US, "%.4f", value)
            .trimEnd('0').trimEnd('.')
        return if (s.isEmpty() || s == "-") "0" else s
    }

    // MARK: Plausibility windows

    const val DBH_MIN_CM = 1.0
    const val DBH_MAX_CM = 300.0
    const val HEIGHT_MIN_M = 1.0
    const val HEIGHT_MAX_M = 120.0

    /// A window bound written in the unit the FIELD is in: converted from the
    /// metric base, rounded to one decimal and trailing zeros trimmed. The
    /// metric bounds therefore still render exactly "1", "300" and "120".
    private fun boundText(base: Double, unit: Unit): String {
        val v = fromBase(base, unit)
        return text(Math.round(v * 10.0) / 10.0)
    }

    /// Warning for a typed DBH truth, or null when it is inside 1–300 cm.
    ///
    /// [unit] is the unit the FIELD is in. The window is ONE physical range
    /// and the judgement is made on the converted base either way, but the
    /// sentence has to be denominated in what the cruiser typed: reading
    /// "Outside 1–300 cm" under a field labelled "True Ø (in)" is the exact
    /// cm/in confusion this screen exists to prevent.
    fun dbhWarning(cm: Double, unit: Unit): String? {
        if (cm >= DBH_MIN_CM && cm <= DBH_MAX_CM) return null
        return "Outside ${boundText(DBH_MIN_CM, unit)}–${boundText(DBH_MAX_CM, unit)} " +
            "${unit.raw} — check the value"
    }

    /// Warning for a typed height truth, or null when inside 1–120 m. Same
    /// rule as [dbhWarning]: judged in the base, worded in the field's unit.
    fun heightWarning(m: Double, unit: Unit): String? {
        if (m >= HEIGHT_MIN_M && m <= HEIGHT_MAX_M) return null
        return "Outside ${boundText(HEIGHT_MIN_M, unit)}–${boundText(HEIGHT_MAX_M, unit)} " +
            "${unit.raw} — check the value"
    }

    /// Quantity-dispatched window check. The value is the METRIC BASE, so the
    /// window is stated once and an imperial entry is judged by the same
    /// numbers as a metric one; [unit] only decides how the bounds are
    /// WRITTEN. A distance has no cruising window.
    fun warning(base: Double, quantity: Quantity, unit: Unit): String? = when (quantity) {
        Quantity.DIAMETER -> dbhWarning(base, unit)
        Quantity.HEIGHT -> heightWarning(base, unit)
        Quantity.DISTANCE -> null
    }

    /// The inline warning a truth field shows for its CURRENT text and unit:
    /// "Not a number" for unparseable input, the plausibility warning for an
    /// out-of-window value, null otherwise.
    fun fieldWarning(raw: String, quantity: Quantity, unit: Unit): String? {
        if (isUnparseable(raw)) return "Not a number"
        val base = parsePositiveBase(raw, unit) ?: return null
        return warning(base, quantity, unit)
    }
}
