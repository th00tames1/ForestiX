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
//     the caller shows `fieldWarning(...)` next to the field.

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

    /// A value put BACK into a truth field — used when a queued truth could
    /// not be stored (its bundle write failed) and the operator must see the
    /// number again instead of losing it. Trailing zeros are trimmed so
    /// "12.5" round-trips as typed.
    fun text(value: Double): String {
        val s = String.format(java.util.Locale.US, "%.4f", value)
            .trimEnd('0').trimEnd('.')
        return if (s.isEmpty() || s == "-") "0" else s
    }

    /// True when the field holds characters but doesn't parse — the state
    /// that must NEVER overwrite (or silently discard) a stored truth.
    fun isUnparseable(raw: String): Boolean =
        normalized(raw).isNotEmpty() && parsePositive(raw) == null

    // MARK: Plausibility windows

    const val DBH_MIN_CM = 1.0
    const val DBH_MAX_CM = 300.0
    const val HEIGHT_MIN_M = 1.0
    const val HEIGHT_MAX_M = 120.0

    /// Warning for a typed DBH truth, or null when it is inside 1–300 cm.
    fun dbhWarning(cm: Double): String? =
        if (cm < DBH_MIN_CM || cm > DBH_MAX_CM) "Outside 1–300 cm — check the value" else null

    /// Warning for a typed height truth, or null when inside 1–120 m.
    fun heightWarning(m: Double): String? =
        if (m < HEIGHT_MIN_M || m > HEIGHT_MAX_M) "Outside 1–120 m — check the value" else null

    /// Kind-dispatched convenience.
    fun warning(value: Double, isHeight: Boolean): String? =
        if (isHeight) heightWarning(value) else dbhWarning(value)

    /// The inline warning a truth field shows for its CURRENT text: "Not a
    /// number" for unparseable input, the plausibility warning for an
    /// out-of-window value, null otherwise.
    fun fieldWarning(raw: String, isHeight: Boolean): String? {
        if (isUnparseable(raw)) return "Not a number"
        val v = parsePositive(raw) ?: return null
        return warning(v, isHeight)
    }
}
